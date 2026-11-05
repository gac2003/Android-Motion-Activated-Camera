package com.motioncam.cloud

import android.content.Context
import com.motioncam.Collections

/** Settings for one backup destination. Secrets go through [SecretStore]. */
class CloudConfig(private val ctx: Context, val dest: Dest) {
    /** The service's own destination (backups set up before several folders were possible). */
    constructor(ctx: Context, kind: CloudKind) : this(ctx, Dest.primary(kind))
    val kind: CloudKind get() = dest.kind
    private val sp = ctx.getSharedPreferences("cloud_${dest.key}", Context.MODE_PRIVATE)

    /** Forgets everything about this destination (settings and sign-in). */
    fun clearAll() {
        sp.edit().clear().apply()
        for (k in listOf("password", "privateKey", "passphrase", "refresh")) SecretStore.put(ctx, "${dest.key}.$k", null)
    }
    private fun s(k: String) = sp.getString(k, null)
    private fun put(k: String, v: String?) = sp.edit().putString(k, v).apply()

    var enabled: Boolean get() = sp.getBoolean("enabled", false); set(v) = sp.edit().putBoolean("enabled", v).apply()

    /** Folder id (Drive) or path (others) and its display name. */
    var folderId: String? get() = s("folderId"); set(v) = put("folderId", v)
    var folderName: String? get() = s("folderName"); set(v) = put("folderName", v)

    /** What to do when the destination is full. */
    var fullPolicy: Int get() = sp.getInt("fullPolicy", DELETE_OLDEST); set(v) = sp.edit().putInt("fullPolicy", v).apply()
    /** Use at most this many GB in the folder (0 = no limit). */
    var maxGb: Int get() = sp.getInt("maxGb", 0); set(v) = sp.edit().putInt("maxGb", v).apply()
    /** Delete cloud copies older than this many days (0 = never). */
    var keepDays: Int get() = sp.getInt("keepDays", 0); set(v) = sp.edit().putInt("keepDays", v).apply()
    /** Collections whose clips are never deleted from this destination. */
    var protectedCollections: Set<String>
        get() = sp.getStringSet("protect", setOf(Collections.FAVOURITES)) ?: emptySet()
        set(v) = sp.edit().putStringSet("protect", v).apply()

    /** Shown in the UI: "Signed in as …" or "user@host". */
    var account: String? get() = s("account"); set(v) = put("account", v)

    // FTP / SFTP
    var host: String get() = s("host") ?: ""; set(v) = put("host", v.trim())
    var port: Int get() = sp.getInt("port", if (kind == CloudKind.SFTP) 22 else 21); set(v) = sp.edit().putInt("port", v).apply()
    var user: String get() = s("user") ?: ""; set(v) = put("user", v.trim())
    /** FTP: use explicit TLS (FTPS). */
    var tls: Boolean get() = sp.getBoolean("tls", true); set(v) = sp.edit().putBoolean("tls", v).apply()
    /** SFTP: authenticate with a private key instead of a password. */
    var useKey: Boolean get() = sp.getBoolean("useKey", false); set(v) = sp.edit().putBoolean("useKey", v).apply()
    /** SFTP: trusted server host-key fingerprint (trust on first use). */
    var hostKey: String? get() = s("hostKey"); set(v) = put("hostKey", v)

    var password: String? get() = SecretStore.get(ctx, "${dest.key}.password"); set(v) = SecretStore.put(ctx, "${dest.key}.password", v)
    var privateKey: String? get() = SecretStore.get(ctx, "${dest.key}.privateKey"); set(v) = SecretStore.put(ctx, "${dest.key}.privateKey", v)
    var passphrase: String? get() = SecretStore.get(ctx, "${dest.key}.passphrase"); set(v) = SecretStore.put(ctx, "${dest.key}.passphrase", v)
    /** Dropbox refresh token. (Google sign-in is managed by Google Play services.) */
    var refreshToken: String? get() = SecretStore.get(ctx, "${dest.key}.refresh"); set(v) = SecretStore.put(ctx, "${dest.key}.refresh", v)

    // status
    var lastOkAt: Long get() = sp.getLong("lastOkAt", 0); set(v) = sp.edit().putLong("lastOkAt", v).apply()
    var lastError: String? get() = s("lastError"); set(v) = put("lastError", v)
    /** Set when uploads are paused until the user acts (sign-in expired, storage full). */
    var blocked: String? get() = s("blocked"); set(v) = put("blocked", v)
    var lastRetentionAt: Long get() = sp.getLong("lastRetentionAt", 0); set(v) = sp.edit().putLong("lastRetentionAt", v).apply()

    val isConfigured: Boolean get() = when (kind) {
        CloudKind.DRIVE -> account != null
        CloudKind.DROPBOX -> refreshToken != null
        CloudKind.FTP -> host.isNotEmpty() && user.isNotEmpty()
        CloudKind.SFTP -> host.isNotEmpty() && user.isNotEmpty() && (if (useKey) privateKey != null else password != null)
    }

    /** When this phone's copy of a folder-wide setting was set (see [SharedFolderSettings]); 0 = never shared. */
    fun sharedAt(key: String): Long = sp.getLong("at:$key", 0)
    fun setSharedAt(key: String, at: Long) = sp.edit().putLong("at:$key", at).apply()

    /**
     * Reset: this phone's protected collections back to the default; the folder-wide clean-up rules are taken from the
     * folder again (they belong to every phone backing up there). Account, folder and on/off are kept.
     */
    fun resetPolicies() {
        val e = sp.edit().remove("fullPolicy").remove("maxGb").remove("keepDays").remove("protect")
        for (k in SharedFolderSettings.KEYS) e.remove("at:$k")
        e.apply()
    }

    /** Forget the account / credentials (keeps the folder choice and policies). */
    fun disconnect() {
        account = null
        refreshToken = null
        password = null
        privateKey = null
        passphrase = null
        hostKey = null
        blocked = null
    }

    companion object {
        const val DELETE_OLDEST = 0
        const val PAUSE = 1
    }
}
