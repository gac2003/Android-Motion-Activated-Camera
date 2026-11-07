package com.motioncam.cloud

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Dropbox API v2 with an "App folder" app: every path is inside Apps/MotionCam, nothing else is reachable.
 * Uses a long-lived refresh token from the PKCE sign-in (no app secret).
 */
class DropboxProvider(private val appKey: String, private val refreshToken: String) : CloudProvider {
    private var access: String? = null
    private var accessExp = 0L

    companion object {
        /** The Dropbox app's key, set when building (credentials.properties or CI variables; see SETUP.md). */
        val APP_KEY: String = com.motioncam.BuildConfig.DROPBOX_APP_KEY
        /** Dropbox is usable in this build. */
        val configured get() = APP_KEY.isNotEmpty()
        const val REDIRECT = "http://localhost:53682/"
        const val REDIRECT_PORT = 53682

        fun authorizeUrl(challenge: String, state: String) =
            "https://www.dropbox.com/oauth2/authorize?client_id=$APP_KEY&response_type=code&token_access_type=offline" +
                "&code_challenge=$challenge&code_challenge_method=S256&redirect_uri=${Http.enc(REDIRECT)}&state=$state"

        /** Exchanges the sign-in code for a refresh token. */
        fun exchange(code: String, verifier: String): String {
            val r = Http.request("POST", "https://api.dropboxapi.com/oauth2/token",
                mapOf("Content-Type" to "application/x-www-form-urlencoded"),
                body = Http.form("code" to code, "grant_type" to "authorization_code", "client_id" to APP_KEY,
                    "code_verifier" to verifier, "redirect_uri" to REDIRECT))
            if (!r.ok) Http.fail(r, "Dropbox sign-in")
            return JSONObject(r.body).optString("refresh_token").takeIf { it.isNotEmpty() }
                ?: throw CloudException("Dropbox did not return a refresh token", CloudException.Kind.AUTH)
        }
    }

    @Synchronized
    internal fun token(): String {
        val now = System.currentTimeMillis()
        access?.let { if (now < accessExp - 60_000) return it }
        val r = Http.request("POST", "https://api.dropboxapi.com/oauth2/token",
            mapOf("Content-Type" to "application/x-www-form-urlencoded"),
            body = Http.form("grant_type" to "refresh_token", "refresh_token" to refreshToken, "client_id" to appKey))
        if (r.code == 400 || r.code == 401) throw CloudException("Dropbox sign-in expired — reconnect", CloudException.Kind.AUTH)
        if (!r.ok) Http.fail(r, "Dropbox token")
        val o = JSONObject(r.body)
        access = o.getString("access_token")
        accessExp = now + o.optLong("expires_in", 14_400) * 1000
        return access!!
    }

    internal fun rpc(endpoint: String, args: JSONObject?, what: String): JSONObject {
        repeat(2) { attempt ->
            val r = Http.request("POST", "https://api.dropboxapi.com/2/$endpoint",
                mapOf("Authorization" to "Bearer ${token()}", "Content-Type" to "application/json"),
                body = (args?.toString() ?: "null").toByteArray())
            if (r.code == 401 && attempt == 0) { access = null; return@repeat }
            if (!r.ok) fail(r, what)
            return if (r.body.isBlank() || r.body == "null") JSONObject() else JSONObject(r.body)
        }
        throw CloudException("$what: not signed in", CloudException.Kind.AUTH)
    }

    internal fun fail(r: Http.Resp, what: String): Nothing {
        if (r.body.contains("missing_scope")) throw CloudException(
            "$what: this Dropbox sign-in is missing a permission. Disconnect and connect Dropbox again.", CloudException.Kind.AUTH)
        if (r.code == 409 && r.body.contains("not_found")) throw CloudException("$what: folder not found", CloudException.Kind.NOT_FOUND)
        if (r.body.contains("insufficient_space")) throw CloudException("Dropbox is full", CloudException.Kind.QUOTA)
        Http.fail(r, what)
    }

    /** Dropbox-API-Arg must be ASCII. */
    internal fun arg(o: JSONObject) = o.toString().map { if (it.code > 126) "\\u%04x".format(it.code) else it.toString() }.joinToString("")

    override fun test(): String {
        val o = rpc("users/get_current_account", null, "Check account")
        return o.optString("email").takeIf { it.isNotEmpty() } ?: o.optJSONObject("name")?.optString("display_name") ?: "Dropbox account"
    }

    override fun listFolders(parent: String?): List<RemoteFolder> =
        listAll(parent ?: "").filter { it.optString(".tag") == "folder" }
            .map { RemoteFolder(it.getString("path_display"), it.getString("name")) }.sortedBy { it.name.lowercase() }

    override fun createFolder(parent: String?, name: String): RemoteFolder {
        val path = (parent ?: "").trimEnd('/') + "/" + name
        val o = rpc("files/create_folder_v2", JSONObject().put("path", path).put("autorename", false), "Create folder")
        val m = o.optJSONObject("metadata")
        return RemoteFolder(m?.optString("path_display") ?: path, m?.optString("name") ?: name)
    }

    override fun list(folder: String): List<RemoteFile> =
        listAll(folder).filter { it.optString(".tag") == "file" }.map {
            RemoteFile(it.getString("path_display"), it.getString("name"), it.optLong("size"), parseTime(it.optString("client_modified")))
        }

    private fun listAll(path: String): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        var o = rpc("files/list_folder", JSONObject().put("path", path).put("limit", 2000), "List folder")
        while (true) {
            val arr = o.getJSONArray("entries")
            for (i in 0 until arr.length()) out += arr.getJSONObject(i)
            if (!o.optBoolean("has_more")) break
            o = rpc("files/list_folder/continue", JSONObject().put("cursor", o.getString("cursor")), "List folder")
        }
        return out
    }

    override fun quota(folder: String): Quota? {
        val o = rpc("users/get_space_usage", null, "Check storage")
        val a = o.optJSONObject("allocation")
        val total = when (a?.optString(".tag")) {
            "individual" -> a.optLong("allocated")
            "team" -> a.optLong("allocated")
            else -> null
        }
        return Quota(o.optLong("used"), total?.takeIf { it > 0 })
    }

    /** Upload sessions in 8 MB chunks; resume state = "sessionId|offset". */
    override fun upload(folder: String, name: String, src: UploadSource, resume: String?,
                        saveResume: (String?) -> Unit, progress: (Long) -> Unit): RemoteFile {
        val path = folder.trimEnd('/') + "/" + name
        val chunk = 8L * 1024 * 1024
        if (resume == null && src.size <= chunk) {
            // small clip: one request
            val r = src.open(0).use { input ->
                Http.request("POST", "https://content.dropboxapi.com/2/files/upload",
                    mapOf("Authorization" to "Bearer ${token()}", "Content-Type" to "application/octet-stream",
                        "Dropbox-API-Arg" to arg(JSONObject().put("path", path).put("mode", "add").put("autorename", true).put("mute", true))),
                    stream = input, bodyLength = src.size, progress = progress, timeoutMs = 120_000)
            }
            if (!r.ok) fail(r, "Upload")
            val m = JSONObject(r.body)
            return RemoteFile(m.optString("path_display", path), m.optString("name", name), m.optLong("size", src.size), System.currentTimeMillis())
        }
        var sid = resume?.substringBefore("|")
        var offset = resume?.substringAfter("|")?.toLongOrNull() ?: 0L
        if (sid == null) {
            offset = 0
            val first = minOf(chunk, src.size)
            val r = src.open(0).use { input ->
                Http.request("POST", "https://content.dropboxapi.com/2/files/upload_session/start",
                    mapOf("Authorization" to "Bearer ${token()}", "Content-Type" to "application/octet-stream",
                        "Dropbox-API-Arg" to arg(JSONObject().put("close", false))),
                    stream = input, bodyLength = first, progress = progress, timeoutMs = 120_000)
            }
            if (!r.ok) fail(r, "Start upload")
            sid = JSONObject(r.body).getString("session_id")
            offset = first
            saveResume("$sid|$offset")
        } else progress(offset)
        while (offset < src.size) {
            val len = minOf(chunk, src.size - offset)
            val cursor = JSONObject().put("session_id", sid).put("offset", offset)
            val r = src.open(offset).use { input ->
                Http.request("POST", "https://content.dropboxapi.com/2/files/upload_session/append_v2",
                    mapOf("Authorization" to "Bearer ${token()}", "Content-Type" to "application/octet-stream",
                        "Dropbox-API-Arg" to arg(JSONObject().put("cursor", cursor).put("close", false))),
                    stream = input, bodyLength = len, progress = progress, timeoutMs = 120_000)
            }
            if (r.code == 409 && r.body.contains("incorrect_offset")) {
                // the server has a different amount than we thought (e.g. a chunk landed before a disconnect)
                val correct = Regex("\"correct_offset\":\\s*(\\d+)").find(r.body)?.groupValues?.get(1)?.toLongOrNull()
                if (correct != null) { offset = correct; saveResume("$sid|$offset"); continue }
            }
            if (r.code == 404 || (r.code == 409 && r.body.contains("not_found"))) {
                saveResume(null); throw CloudException("Upload session expired", CloudException.Kind.NETWORK)
            }
            if (!r.ok) fail(r, "Upload")
            offset += len
            saveResume("$sid|$offset")
        }
        val commit = JSONObject().put("path", path).put("mode", "add").put("autorename", true).put("mute", true)
        val r = Http.request("POST", "https://content.dropboxapi.com/2/files/upload_session/finish",
            mapOf("Authorization" to "Bearer ${token()}", "Content-Type" to "application/octet-stream",
                "Dropbox-API-Arg" to arg(JSONObject().put("cursor", JSONObject().put("session_id", sid).put("offset", offset)).put("commit", commit))),
            body = ByteArray(0))
        if (!r.ok) fail(r, "Finish upload")
        val m = JSONObject(r.body)
        return RemoteFile(m.optString("path_display", path), m.optString("name", name), m.optLong("size", src.size), System.currentTimeMillis())
    }

    /** GET on a content endpoint (download / thumbnail), with an optional byte offset. */
    internal fun content(endpoint: String, args: JSONObject, offset: Long, what: String): java.io.InputStream {
        repeat(2) { attempt ->
            val h = mutableMapOf("Authorization" to "Bearer ${token()}", "Dropbox-API-Arg" to arg(args))
            if (offset > 0) h["Range"] = "bytes=$offset-"
            try {
                return Http.download("https://content.dropboxapi.com/2/$endpoint", h, what).second
            } catch (e: CloudException) {
                if (e.kind == CloudException.Kind.AUTH && attempt == 0 && !(e.message ?: "").contains("missing_scope")) { synchronized(this) { access = null }; return@repeat }
                if ((e.message ?: "").contains("missing_scope")) throw CloudException(
                    "$what: this Dropbox sign-in is missing a permission. Disconnect and connect Dropbox again.", CloudException.Kind.AUTH)
                if ((e.message ?: "").contains("HTTP 409") && (e.message ?: "").contains("not_found")) throw CloudException("$what: not found", CloudException.Kind.NOT_FOUND)
                throw e
            }
        }
        throw CloudException("$what: not signed in", CloudException.Kind.AUTH)
    }

    override fun open(file: RemoteFile, offset: Long): java.io.InputStream =
        content("files/download", JSONObject().put("path", file.id), offset, "Download")

    override fun serviceThumbnail(file: RemoteFile): ByteArray? = try {
        content("files/get_thumbnail_v2", JSONObject()
            .put("resource", JSONObject().put(".tag", "path").put("path", file.id))
            .put("format", "jpeg").put("size", "w480h320"), 0, "Thumbnail").use { it.readBytes() }
    } catch (e: Exception) { null }

    /**
     * A view-only link to [path] (normally the backup folder) that another Dropbox user can paste into
     * MotionCam's Remote folders. Reuses an existing link.
     */
    fun viewerLink(path: String): String {
        try {
            val o = rpc("sharing/create_shared_link_with_settings", JSONObject().put("path", path)
                .put("settings", JSONObject().put("audience", "public").put("access", "viewer").put("allow_download", true)), "Create link")
            return o.getString("url")
        } catch (e: CloudException) {
            if (!(e.message ?: "").contains("shared_link_already_exists")) throw e
        }
        return existingLink(path) ?: throw CloudException("Dropbox did not return the existing link", CloudException.Kind.OTHER)
    }

    fun existingLink(path: String): String? {
        val o = rpc("sharing/list_shared_links", JSONObject().put("path", path).put("direct_only", true), "Read links")
        val arr = o.optJSONArray("links") ?: return null
        return (0 until arr.length()).map { arr.getJSONObject(it) }.firstOrNull { it.optString(".tag") == "folder" || it.has("url") }?.optString("url")
    }

    /** Stops the link from working for everyone who has it. */
    fun revokeLink(url: String) {
        try { rpc("sharing/revoke_shared_link", JSONObject().put("url", url), "Revoke link") }
        catch (e: CloudException) { if (e.kind != CloudException.Kind.NOT_FOUND && !(e.message ?: "").contains("shared_link_not_found")) throw e }
    }

    override fun serverCopy(file: RemoteFile, toFolder: String, name: String): RemoteFile? {
        val o = rpc("files/copy_v2", JSONObject().put("from_path", file.id).put("to_path", toFolder.trimEnd('/') + "/" + name)
            .put("autorename", false), "Copy")
        val m = o.optJSONObject("metadata") ?: return null
        return RemoteFile(m.getString("path_display"), m.getString("name"), m.optLong("size"), parseTime(m.optString("client_modified")))
    }

    override fun delete(folder: String, file: RemoteFile) {
        try { rpc("files/delete_v2", JSONObject().put("path", file.id), "Delete") }
        catch (e: CloudException) { if (e.kind != CloudException.Kind.NOT_FOUND) throw e }
    }

    private fun parseTime(s: String?): Long = try {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(s!!.take(19))!!.time
    } catch (e: Exception) { 0 }
}
