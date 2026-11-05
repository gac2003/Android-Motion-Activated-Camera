package com.motioncam.remote

import android.content.Context
import com.motioncam.cloud.CloudConfig
import com.motioncam.cloud.CloudKind
import com.motioncam.cloud.Dest

/** Remote folders as places this phone can back clips up to (a backup destination per folder). */
object FolderDests {
    /** The destinations pointing at this folder. */
    fun destsOf(ctx: Context, s: RemoteSource): List<Dest> {
        if (s.mode == RemoteSources.MODE_LINK) return emptyList()
        return Dest.all(ctx).filter { d -> d.kind == s.kind && CloudConfig(ctx, d).let { c -> c.isConfigured && c.folderId == s.folderId } }
    }

    /**
     * The folder's destination, set up if needed (reusing the service's unused one, otherwise adding one). A new one
     * doesn't back up new clips by itself: only clips chosen for it.
     */
    fun destFor(ctx: Context, s: RemoteSource): Dest {
        destsOf(ctx, s).firstOrNull()?.let { return it }
        val d = Dest.primary(s.kind).takeIf { CloudConfig(ctx, it).let { c -> !c.isConfigured || c.folderId == null } }
            ?: Dest.create(ctx, s.kind)
        val c = CloudConfig(ctx, d)
        when (s.kind) {
            CloudKind.DRIVE -> c.account = s.account
            CloudKind.DROPBOX -> { c.refreshToken = s.refreshToken; c.account = s.account }
            CloudKind.FTP -> { c.host = s.host; c.port = s.port; c.user = s.user; c.tls = s.tls; c.password = s.password }
            CloudKind.SFTP -> {
                c.host = s.host; c.port = s.port; c.user = s.user; c.useKey = s.useKey; c.password = s.password
                c.privateKey = s.privateKey; c.passphrase = s.passphrase; c.hostKey = s.hostKey
            }
        }
        c.folderId = s.folderId; c.folderName = s.folderName.ifEmpty { s.name }
        c.blocked = null
        c.enabled = false
        return d
    }

    /** Remote folders this phone could back up to but has no destination for yet. */
    fun unused(ctx: Context): List<RemoteSource> = RemoteSources.all(ctx).filter { s ->
        s.mode != RemoteSources.MODE_LINK && destsOf(ctx, s).isEmpty()
    }

    // ---- accounts already used by this phone's folders (to pick one when adding another folder)

    /** Google accounts of Drive folders and backup destinations. */
    fun googleAccounts(ctx: Context): List<String> =
        (RemoteSources.all(ctx).filter { it.kind == CloudKind.DRIVE }.mapNotNull { it.account } +
            Dest.all(ctx).filter { it.kind == CloudKind.DRIVE }.mapNotNull { CloudConfig(ctx, it).takeIf { c -> c.isConfigured }?.account })
            .filter { it.isNotBlank() }.distinct()

    class DropboxAccount(val who: String, val refresh: String)

    /** Dropbox accounts (with their sign-in) of folders and backup destinations. */
    fun dropboxAccounts(ctx: Context): List<DropboxAccount> {
        val out = ArrayList<DropboxAccount>()
        for (s in RemoteSources.all(ctx)) if (s.kind == CloudKind.DROPBOX) s.refreshToken?.let { out += DropboxAccount(s.account ?: "Dropbox", it) }
        for (d in Dest.all(ctx)) if (d.kind == CloudKind.DROPBOX) CloudConfig(ctx, d).let { c -> c.refreshToken?.let { out += DropboxAccount(c.account ?: "Dropbox", it) } }
        return out.distinctBy { it.who }
    }

    /** FTP / SFTP logins of folders and backup destinations, as a starting point for a new folder on the same server. */
    fun servers(ctx: Context, kind: CloudKind): List<RemoteSource> {
        val out = ArrayList<RemoteSource>()
        for (s in RemoteSources.all(ctx)) if (s.kind == kind) out += RemoteSource(RemoteSources.newId(), "", kind, RemoteSources.MODE_OWN, "", "", null,
            host = s.host, port = s.port, user = s.user, tls = s.tls, useKey = s.useKey, hostKey = s.hostKey).also {
            it.password = s.password; it.privateKey = s.privateKey; it.passphrase = s.passphrase }
        for (d in Dest.all(ctx)) if (d.kind == kind) CloudConfig(ctx, d).takeIf { it.isConfigured }?.let { c ->
            out += RemoteSource(RemoteSources.newId(), "", kind, RemoteSources.MODE_OWN, "", "", null,
                host = c.host ?: "", port = c.port, user = c.user ?: "", tls = c.tls, useKey = c.useKey, hostKey = c.hostKey).also {
                it.password = c.password; it.privateKey = c.privateKey; it.passphrase = c.passphrase }
        }
        return out.filter { it.host.isNotEmpty() }.distinctBy { "${it.user}@${it.host}:${it.port}" }
    }
}
