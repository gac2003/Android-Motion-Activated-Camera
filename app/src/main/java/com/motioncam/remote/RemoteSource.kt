package com.motioncam.remote

import android.content.Context
import com.motioncam.cloud.CloudException
import com.motioncam.cloud.CloudKind
import com.motioncam.cloud.CloudProvider
import com.motioncam.cloud.DriveProvider
import com.motioncam.cloud.DropboxLinkProvider
import com.motioncam.cloud.DropboxProvider
import com.motioncam.cloud.FtpProvider
import com.motioncam.cloud.GoogleAuth
import com.motioncam.cloud.RemoteFile
import com.motioncam.cloud.RemoteFolder
import com.motioncam.cloud.SecretStore
import com.motioncam.cloud.SftpProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream

/**
 * A folder of MotionCam clips on another device's backup destination, viewed read-only.
 *  - [MODE_OWN]: the viewer is signed in to the same account as the recording phone (Google Drive only
 *    supports this: MotionCam's limited Drive access only sees files MotionCam created in that account).
 *  - [MODE_LINK]: a Dropbox viewer link made on the recording phone (any Dropbox account can open it).
 * Passwords, keys and sign-in tokens are kept encrypted in [SecretStore], never in the JSON.
 */
class RemoteSource(
    val id: String,
    var name: String,
    val kind: CloudKind,
    val mode: String,
    val folderId: String,
    val folderName: String,
    val account: String?,
    val host: String = "",
    val port: Int = 0,
    val user: String = "",
    val tls: Boolean = true,
    val useKey: Boolean = false,
    var hostKey: String? = null,
    val link: String? = null,
    var lastRefresh: Long = 0,
    var clipCount: Int = 0,
    val added: Long = System.currentTimeMillis(),
) {
    // secrets (loaded from / saved to SecretStore)
    var password: String? = null
    var privateKey: String? = null
    var passphrase: String? = null
    var refreshToken: String? = null

    val describe: String get() = when (kind) {
        CloudKind.DRIVE -> "Google Drive · " + (account ?: "")
        CloudKind.DROPBOX -> if (mode == RemoteSources.MODE_LINK) "Dropbox · viewer link" else "Dropbox · " + (account ?: "")
        CloudKind.FTP -> (if (tls) "FTPS · " else "FTP · ") + "$user@$host"
        CloudKind.SFTP -> "SFTP · $user@$host"
    }

    /** FTP/SFTP: whole clips are downloaded before playing (no streaming). */
    val streams: Boolean get() = kind == CloudKind.DRIVE || kind == CloudKind.DROPBOX
}

/**
 * Only the reading half of a [CloudProvider]. Remote folders only ever use this, so the viewer cannot
 * upload, rename or delete anything in a folder it views.
 */
class RemoteReader(private val p: CloudProvider) : java.io.Closeable {
    fun test(): String = p.test()
    fun listFolders(parent: String?): List<RemoteFolder> = p.listFolders(parent)
    fun list(folder: String): List<RemoteFile> = p.list(folder)
    fun open(file: RemoteFile, offset: Long): InputStream = p.open(file, offset)
    fun serviceThumbnail(file: RemoteFile): ByteArray? = p.serviceThumbnail(file)
    /**
     * Protection marks of the folder. This is the one thing a remote folder may change: tiny marks (Drive: hidden
     * properties; others: files in ".keep"), never the clips. Read-only for a Dropbox viewer link.
     */
    fun markStore(folder: String): com.motioncam.cloud.MarkStore = com.motioncam.cloud.MarkStore.forProvider(p, folder)
    /** Dropbox link only: the shared folder's name. */
    fun linkFolderName(): String? = (p as? DropboxLinkProvider)?.folderName()
    override fun close() = p.close()
}

object RemoteSources {
    const val MODE_OWN = "own"
    const val MODE_LINK = "link"

    private var cache: MutableList<RemoteSource>? = null
    private fun sp(ctx: Context) = ctx.getSharedPreferences("remote_sources", Context.MODE_PRIVATE)

    @Synchronized
    fun all(ctx: Context): List<RemoteSource> {
        cache?.let { return it.toList() }
        val out = ArrayList<RemoteSource>()
        try {
            val a = JSONArray(sp(ctx).getString("list", "[]"))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val k = CloudKind.of(o.getString("kind")) ?: continue
                val s = RemoteSource(o.getString("id"), o.getString("name"), k, o.optString("mode", MODE_OWN),
                    o.getString("folderId"), o.optString("folderName"), o.optString("account").ifEmpty { null },
                    o.optString("host"), o.optInt("port"), o.optString("user"), o.optBoolean("tls", true), o.optBoolean("useKey"),
                    o.optString("hostKey").ifEmpty { null }, o.optString("link").ifEmpty { null },
                    o.optLong("lastRefresh"), o.optInt("clipCount"), o.optLong("added"))
                s.password = SecretStore.get(ctx, "remote.${s.id}.password")
                s.privateKey = SecretStore.get(ctx, "remote.${s.id}.privateKey")
                s.passphrase = SecretStore.get(ctx, "remote.${s.id}.passphrase")
                s.refreshToken = SecretStore.get(ctx, "remote.${s.id}.refresh")
                out += s
            }
        } catch (_: Exception) {}
        cache = out
        return out.toList()
    }

    fun get(ctx: Context, id: String?): RemoteSource? = if (id == null) null else all(ctx).firstOrNull { it.id == id }

    @Synchronized
    private fun persist(ctx: Context) {
        val a = JSONArray()
        for (s in cache ?: return) a.put(JSONObject().put("id", s.id).put("name", s.name).put("kind", s.kind.key).put("mode", s.mode)
            .put("folderId", s.folderId).put("folderName", s.folderName).put("account", s.account ?: "")
            .put("host", s.host).put("port", s.port).put("user", s.user).put("tls", s.tls).put("useKey", s.useKey)
            .put("hostKey", s.hostKey ?: "").put("link", s.link ?: "").put("lastRefresh", s.lastRefresh)
            .put("clipCount", s.clipCount).put("added", s.added))
        sp(ctx).edit().putString("list", a.toString()).apply()
    }

    @Synchronized
    fun add(ctx: Context, s: RemoteSource) {
        all(ctx)
        cache!!.removeAll { it.id == s.id }
        cache!!.add(s)
        SecretStore.put(ctx, "remote.${s.id}.password", s.password)
        SecretStore.put(ctx, "remote.${s.id}.privateKey", s.privateKey)
        SecretStore.put(ctx, "remote.${s.id}.passphrase", s.passphrase)
        SecretStore.put(ctx, "remote.${s.id}.refresh", s.refreshToken)
        persist(ctx)
    }

    /** Saves non-secret changes (name, refresh time, host key…). */
    @Synchronized
    fun update(ctx: Context, s: RemoteSource) {
        all(ctx)
        val i = cache!!.indexOfFirst { it.id == s.id }
        if (i >= 0) { cache!![i] = s; persist(ctx) }
    }

    @Synchronized
    fun remove(ctx: Context, id: String) {
        all(ctx)
        cache!!.removeAll { it.id == id }
        for (k in listOf("password", "privateKey", "passphrase", "refresh")) SecretStore.put(ctx, "remote.$id.$k", null)
        persist(ctx)
    }

    fun newId() = "r" + System.currentTimeMillis().toString(36)

    /** A new connection to the source (one per caller; FTP/SFTP connections are not shared between threads). */
    fun reader(ctx: Context, s: RemoteSource): RemoteReader = RemoteReader(provider(ctx.applicationContext, s))

    private fun provider(ctx: Context, s: RemoteSource): CloudProvider = when (s.kind) {
        CloudKind.DRIVE -> DriveProvider({ GoogleAuth.token(ctx, s.account) }, { GoogleAuth.invalidate(ctx, it) })
        CloudKind.DROPBOX -> {
            val dbx = DropboxProvider(DropboxProvider.APP_KEY,
                s.refreshToken ?: throw CloudException("Dropbox is not signed in on this device", CloudException.Kind.AUTH))
            if (s.mode == MODE_LINK) DropboxLinkProvider(dbx, s.link ?: "") else dbx
        }
        CloudKind.FTP -> FtpProvider(s.host, s.port, s.user, s.password ?: "", s.tls)
        CloudKind.SFTP -> SftpProvider(s.host, s.port, s.user,
            if (s.useKey) null else s.password, if (s.useKey) s.privateKey else null, s.passphrase, s.hostKey) { fp ->
            s.hostKey = fp
            if (get(ctx, s.id) != null) update(ctx, s)
        }
    }
}
