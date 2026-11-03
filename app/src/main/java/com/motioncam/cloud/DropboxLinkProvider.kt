package com.motioncam.cloud

import org.json.JSONObject
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Read-only access to a folder someone else shared as a Dropbox link (made with "Create viewer link" on the
 * recording phone). Calls are made with the viewer's own Dropbox sign-in ([dbx]); paths are relative to
 * the shared folder ("" = the folder itself). Nothing here can write, rename or delete.
 */
class DropboxLinkProvider(private val dbx: DropboxProvider, private val link: String) : CloudProvider {

    private fun linkArg() = JSONObject().put("url", link)

    override fun test(): String = dbx.test()

    /** Name of the shared folder. */
    fun folderName(): String =
        dbx.rpc("sharing/get_shared_link_metadata", linkArg(), "Open link").optString("name").ifEmpty { "Shared folder" }

    private fun listAll(path: String): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        var o = dbx.rpc("files/list_folder", JSONObject().put("path", path).put("shared_link", linkArg()).put("limit", 2000), "List folder")
        while (true) {
            val arr = o.getJSONArray("entries")
            for (i in 0 until arr.length()) out += arr.getJSONObject(i)
            if (!o.optBoolean("has_more")) break
            o = dbx.rpc("files/list_folder/continue", JSONObject().put("cursor", o.getString("cursor")), "List folder")
        }
        return out
    }

    override fun listFolders(parent: String?): List<RemoteFolder> {
        val p = parent ?: ""
        return listAll(p).filter { it.optString(".tag") == "folder" }
            .map { RemoteFolder(p.trimEnd('/') + "/" + it.getString("name"), it.getString("name")) }.sortedBy { it.name.lowercase() }
    }

    override fun list(folder: String): List<RemoteFile> =
        listAll(folder).filter { it.optString(".tag") == "file" }.map {
            RemoteFile(folder.trimEnd('/') + "/" + it.getString("name"), it.getString("name"), it.optLong("size"), parseTime(it.optString("client_modified")))
        }

    override fun open(file: RemoteFile, offset: Long): InputStream =
        dbx.content("sharing/get_shared_link_file", linkArg().put("path", file.id), offset, "Download")

    override fun serviceThumbnail(file: RemoteFile): ByteArray? = try {
        dbx.content("files/get_thumbnail_v2", JSONObject()
            .put("resource", JSONObject().put(".tag", "link").put("url", link).put("path", file.id))
            .put("format", "jpeg").put("size", "w480h320"), 0, "Thumbnail").use { it.readBytes() }
    } catch (e: Exception) { null }

    // ---- never used for a shared link: read-only
    override fun createFolder(parent: String?, name: String): RemoteFolder = readOnly()
    override fun quota(folder: String): Quota? = null
    override fun upload(folder: String, name: String, src: UploadSource, resume: String?,
                        saveResume: (String?) -> Unit, progress: (Long) -> Unit): RemoteFile = readOnly()
    override fun delete(folder: String, file: RemoteFile) { readOnly() }
    private fun readOnly(): Nothing = throw CloudException("A shared link is read-only", CloudException.Kind.OTHER)

    private fun parseTime(s: String?): Long = try {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(s!!.take(19))!!.time
    } catch (e: Exception) { 0 }
}
