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
        s.mode != RemoteSources.MODE_LINK && destsOf(ctx, s).isEmpty() &&
            (com.motioncam.Prefs(ctx).remoteFtp || (s.kind != CloudKind.FTP && s.kind != CloudKind.SFTP))
    }
}
