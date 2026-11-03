package com.motioncam.cloud

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Google Drive REST v3 with the `drive.file` scope: only files and folders this app created are visible,
 * so the app can never see or touch anything else in the user's Drive.
 * [accessToken] returns a valid OAuth access token (from Google Play services); [invalidate] drops a
 * cached one after a 401.
 */
class DriveProvider(private val accessToken: () -> String, private val invalidate: (String) -> Unit) : CloudProvider {
    private val api = "https://www.googleapis.com/drive/v3"
    private val folderMime = "application/vnd.google-apps.folder"
    private val SMALL = 5L * 1024 * 1024

    private fun call(method: String, url: String, json: JSONObject? = null, what: String): JSONObject {
        repeat(2) { attempt ->
            val tok = accessToken()
            val h = mutableMapOf("Authorization" to "Bearer $tok")
            if (json != null) h["Content-Type"] = "application/json; charset=UTF-8"
            val r = Http.request(method, url, h, body = json?.toString()?.toByteArray())
            if (r.code == 401 && attempt == 0) { invalidate(tok); return@repeat }
            if (!r.ok) Http.fail(r, what)
            return if (r.body.isBlank()) JSONObject() else JSONObject(r.body)
        }
        throw CloudException("$what: not signed in", CloudException.Kind.AUTH)
    }

    override fun test(): String {
        val o = call("GET", "$api/about?fields=user(emailAddress,displayName)", what = "Check account")
        val u = o.optJSONObject("user")
        return u?.optString("emailAddress")?.takeIf { it.isNotEmpty() } ?: u?.optString("displayName") ?: "Google account"
    }

    override fun listFolders(parent: String?): List<RemoteFolder> {
        val q = "mimeType='$folderMime' and trashed=false" + (parent?.let { " and '$it' in parents" } ?: "")
        return pages(q, "files(id,name)").map { RemoteFolder(it.getString("id"), it.getString("name")) }.sortedBy { it.name.lowercase() }
    }

    override fun createFolder(parent: String?, name: String): RemoteFolder {
        val body = JSONObject().put("name", name).put("mimeType", folderMime)
        if (parent != null) body.put("parents", JSONArray().put(parent))
        val o = call("POST", "$api/files?fields=id,name", body, "Create folder")
        return RemoteFolder(o.getString("id"), o.getString("name"))
    }

    override fun list(folder: String): List<RemoteFile> =
        pages("'$folder' in parents and trashed=false and mimeType!='$folderMime'", "files(id,name,size,createdTime)").map {
            RemoteFile(it.getString("id"), it.getString("name"), it.optString("size", "0").toLongOrNull() ?: 0L, parseTime(it.optString("createdTime")))
        }

    private fun pages(q: String, fields: String): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        var token: String? = null
        do {
            val url = "$api/files?pageSize=1000&spaces=drive&q=${Http.enc(q)}&fields=${Http.enc("nextPageToken,$fields")}" +
                (token?.let { "&pageToken=${Http.enc(it)}" } ?: "")
            val o = call("GET", url, what = "List files")
            val arr = o.optJSONArray("files") ?: JSONArray()
            for (i in 0 until arr.length()) out += arr.getJSONObject(i)
            token = o.optString("nextPageToken").takeIf { it.isNotEmpty() }
        } while (token != null)
        return out
    }

    override fun quota(folder: String): Quota? {
        val q = call("GET", "$api/about?fields=storageQuota", what = "Check storage").optJSONObject("storageQuota") ?: return null
        val used = q.optString("usage", "0").toLongOrNull() ?: 0L
        val limit = q.optString("limit", "").toLongOrNull()   // absent = unlimited
        return Quota(used, limit)
    }

    /**
     * Small clips: one multipart request. Larger: resumable upload in 8 MB chunks; the session URL is the
     * resume state (valid about a week).
     */
    override fun upload(folder: String, name: String, src: UploadSource, resume: String?,
                        saveResume: (String?) -> Unit, progress: (Long) -> Unit): RemoteFile {
        if (resume == null && src.size <= SMALL) return uploadSmall(folder, name, src, progress)
        var session = resume
        var offset = 0L
        if (session != null) {
            val st = status(session, src.size)
            if (st == null) { session = null; saveResume(null) }
            else if (st < 0) return finished(session, src.size) ?: throw CloudException("Upload finished but no file info", CloudException.Kind.OTHER)
            else offset = st
        }
        if (session == null) {
            val tok = accessToken()
            val meta = JSONObject().put("name", name).put("parents", JSONArray().put(folder)).toString().toByteArray()
            val r = Http.request("POST", "https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&fields=id,name,size,createdTime",
                mapOf("Authorization" to "Bearer $tok", "Content-Type" to "application/json; charset=UTF-8",
                    "X-Upload-Content-Type" to "video/mp4", "X-Upload-Content-Length" to src.size.toString()), body = meta)
            if (r.code == 401) { invalidate(tok); throw CloudException("Sign-in expired", CloudException.Kind.NETWORK) }
            if (!r.ok) Http.fail(r, "Start upload")
            session = r.header("Location") ?: throw CloudException("No upload session", CloudException.Kind.OTHER)
            saveResume(session)
        }
        progress(offset)
        val chunk = 8L * 1024 * 1024
        while (true) {
            val end = minOf(offset + chunk, src.size) - 1
            val len = end - offset + 1
            val r = src.open(offset).use { input ->
                Http.request("PUT", session!!, mapOf("Content-Range" to "bytes $offset-$end/${src.size}"),
                    stream = input, bodyLength = len, progress = progress, timeoutMs = 120_000)
            }
            when {
                r.code == 200 || r.code == 201 -> return fileOf(JSONObject(r.body))
                r.code == 308 -> offset = r.header("Range")?.substringAfterLast("-")?.toLongOrNull()?.plus(1) ?: (end + 1)
                r.code == 404 || r.code == 410 -> { saveResume(null); throw CloudException("Upload session expired", CloudException.Kind.NETWORK) }
                else -> Http.fail(r, "Upload")
            }
        }
    }

    private fun uploadSmall(folder: String, name: String, src: UploadSource, progress: (Long) -> Unit): RemoteFile {
        val data = src.open(0).use { it.readBytes() }
        val boundary = "motioncam" + System.nanoTime()
        val meta = JSONObject().put("name", name).put("parents", JSONArray().put(folder)).toString()
        val body = java.io.ByteArrayOutputStream(data.size + 512).apply {
            write("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$meta\r\n--$boundary\r\nContent-Type: video/mp4\r\n\r\n".toByteArray())
            write(data)
            write("\r\n--$boundary--\r\n".toByteArray())
        }.toByteArray()
        repeat(2) { attempt ->
            val tok = accessToken()
            val r = Http.request("POST", "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id,name,size,createdTime",
                mapOf("Authorization" to "Bearer $tok", "Content-Type" to "multipart/related; boundary=$boundary"),
                body = body, timeoutMs = 120_000)
            if (r.code == 401 && attempt == 0) { invalidate(tok); return@repeat }
            if (!r.ok) Http.fail(r, "Upload")
            progress(data.size.toLong())
            return fileOf(JSONObject(r.body))
        }
        throw CloudException("Upload: not signed in", CloudException.Kind.AUTH)
    }

    /** Bytes the server has (>= 0), -1 if complete, null if the session is gone. */
    private fun status(session: String, size: Long): Long? {
        val r = Http.request("PUT", session, mapOf("Content-Range" to "bytes */$size"), body = ByteArray(0))
        return when (r.code) {
            200, 201 -> -1L
            308 -> r.header("Range")?.substringAfterLast("-")?.toLongOrNull()?.plus(1) ?: 0L
            404, 410 -> null
            else -> if (r.code == 429 || r.code >= 500) throw CloudException("Drive busy (${r.code})", CloudException.Kind.NETWORK) else null
        }
    }

    private fun finished(session: String, size: Long): RemoteFile? {
        val r = Http.request("PUT", session, mapOf("Content-Range" to "bytes */$size"), body = ByteArray(0))
        return if (r.ok && r.body.isNotBlank()) fileOf(JSONObject(r.body)) else null
    }

    private fun fileOf(o: JSONObject) = RemoteFile(o.getString("id"), o.optString("name"),
        o.optString("size", "0").toLongOrNull() ?: 0L, parseTime(o.optString("createdTime")).takeIf { it > 0 } ?: System.currentTimeMillis())

    override fun delete(folder: String, file: RemoteFile) {
        val tok = accessToken()
        val r = Http.request("DELETE", "$api/files/${file.id}", mapOf("Authorization" to "Bearer $tok"))
        if (r.code == 404) return
        if (r.code == 401) { invalidate(tok); throw CloudException("Sign-in expired", CloudException.Kind.NETWORK) }
        if (!r.ok) Http.fail(r, "Delete")
    }

    private fun parseTime(s: String?): Long {
        if (s.isNullOrEmpty()) return 0
        return try {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(s.take(19))!!.time
        } catch (e: Exception) { 0 }
    }
}
