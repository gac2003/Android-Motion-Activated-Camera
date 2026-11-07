package com.motioncam.cloud

import java.io.InputStream

enum class CloudKind(val key: String, val label: String) {
    DRIVE("drive", "Google Drive"),
    DROPBOX("dropbox", "Dropbox"),
    FTP("ftp", "FTP / FTPS"),
    SFTP("sftp", "SFTP");

    companion object { fun of(key: String?) = values().firstOrNull { it.key == key } }
}

/**
 * A backup destination: one folder on one service, so a phone can back up to several folders (even on the same
 * service). The first destination of each service keeps the service's own key ("drive"), so backups set up before
 * this existed keep their settings, upload records and protection marks; further ones are "drive~<id>".
 */
class Dest private constructor(val key: String, val kind: CloudKind) {
    val label get() = kind.label
    val isPrimary get() = key == kind.key
    override fun equals(other: Any?) = other is Dest && other.key == key
    override fun hashCode() = key.hashCode()
    override fun toString() = key

    companion object {
        private fun sp(ctx: android.content.Context) = ctx.getSharedPreferences("cloud_dests", android.content.Context.MODE_PRIVATE)
        fun primary(k: CloudKind) = Dest(k.key, k)
        fun of(key: String?): Dest? {
            if (key == null) return null
            val k = CloudKind.of(key.substringBefore('~')) ?: return null
            return Dest(key, k)
        }
        private fun extras(ctx: android.content.Context): List<String> =
            sp(ctx).getString("extra", "")!!.split(',').filter { it.isNotBlank() }
        /** Every destination this phone knows (set up or not): the four services' own, then the extra ones. */
        fun all(ctx: android.content.Context): List<Dest> = CloudKind.values().map { primary(it) } + extras(ctx).mapNotNull { of(it) }
        /** A new, empty destination on [k]. */
        fun create(ctx: android.content.Context, k: CloudKind): Dest {
            val d = Dest(k.key + "~" + java.lang.Long.toString(System.currentTimeMillis(), 36), k)
            sp(ctx).edit().putString("extra", (extras(ctx) + d.key).joinToString(",")).apply()
            return d
        }
        /** Forgets an extra destination (the service's own one is only switched off). */
        fun remove(ctx: android.content.Context, d: Dest) {
            if (d.isPrimary) return
            sp(ctx).edit().putString("extra", extras(ctx).filter { it != d.key }.joinToString(",")).apply()
        }
    }
}

/** A file in the backup folder. [id] is the provider's handle (Drive file id, Dropbox/FTP/SFTP path). */
class RemoteFile(val id: String, val name: String, val size: Long, val modifiedMs: Long, val thumbUrl: String? = null,
                 /** Google Drive: MotionCam's hidden app properties (protection marks). */
                 val props: Map<String, String> = emptyMap())

/** A folder. [id] is a Drive folder id or a path for the other providers. */
class RemoteFolder(val id: String, val name: String)

/** Storage usage; [total] null = unknown or unlimited. */
class Quota(val used: Long, val total: Long?) {
    val free: Long? get() = total?.let { it - used }
}

/** Errors are classified so the queue knows whether to retry soon, wait, or ask the user. */
class CloudException(message: String, val kind: Kind, cause: Throwable? = null) : Exception(message, cause) {
    enum class Kind { AUTH, QUOTA, NETWORK, NOT_FOUND, OTHER }
}

/** Something to upload, readable from any offset (needed to resume). */
interface UploadSource {
    val size: Long
    fun open(offset: Long): InputStream
}

/**
 * One cloud/server backend. All calls block and may throw [CloudException].
 * Implementations only ever touch the folder they are given.
 */
interface CloudProvider : java.io.Closeable {
    /** Checks credentials; returns a short description ("Signed in as …"). */
    fun test(): String
    fun listFolders(parent: String?): List<RemoteFolder>
    fun createFolder(parent: String?, name: String): RemoteFolder
    /** Files (not folders) directly inside [folder]. */
    fun list(folder: String): List<RemoteFile>
    fun quota(folder: String): Quota?
    /**
     * Uploads [src] as [name] into [folder]. [resume] is state saved by a previous attempt (or null);
     * [saveResume] is called whenever new resume state should be persisted. [progress] gets bytes sent and
     * may throw to abort (the upload can then resume later).
     */
    fun upload(folder: String, name: String, src: UploadSource, resume: String?,
               saveResume: (String?) -> Unit, progress: (Long) -> Unit): RemoteFile
    fun delete(folder: String, file: RemoteFile)
    /** Removes leftovers of a cancelled upload (e.g. "<name>.part"), best effort. */
    fun discardPartial(folder: String, name: String) {}
    /** Reads [file] from byte [offset] to the end (used by remote folders, which only ever read). */
    fun open(file: RemoteFile, offset: Long): InputStream =
        throw CloudException("Reading is not supported here", CloudException.Kind.OTHER)
    /**
     * Copies [file] into [toFolder] as [name] on the service itself (nothing passes through the phone), or returns null
     * when the service can't. Only between folders of the same account.
     */
    fun serverCopy(file: RemoteFile, toFolder: String, name: String): RemoteFile? = null
    /** The service's own preview image of a video, if it has one (JPEG bytes). */
    fun serviceThumbnail(file: RemoteFile): ByteArray? = null
    override fun close() {}
}

/** Our clips only: never touch anything else in the folder. */
fun isOurClip(name: String) = name.startsWith("MC_") && name.endsWith(".mp4")

/**
 * Peak-motion thumbnails are uploaded next to the clips, in this subfolder, so other devices viewing the
 * folder see the right frame without downloading videos. Name: <clip name without .mp4>_<duration ms>.jpg
 */
const val THUMB_FOLDER = ".thumbnails"

fun thumbName(clipName: String, durationMs: Long) = clipName.removeSuffix(".mp4") + "_" + durationMs.coerceAtLeast(0) + ".jpg"

/** Parses a thumbnail name: clip name (with .mp4) and duration, or null if it is not one of ours. */
fun parseThumbName(name: String): Pair<String, Long>? {
    val m = Regex("^(MC_.+)_(\\d+)(?: \\(\\d+\\))?\\.jpg$").find(name) ?: return null
    return Pair(m.groupValues[1] + ".mp4", m.groupValues[2].toLong())
}

/** Bytes as an [UploadSource]. */
fun bytesSource(b: ByteArray) = object : UploadSource {
    override val size = b.size.toLong()
    override fun open(offset: Long): InputStream = java.io.ByteArrayInputStream(b, offset.toInt(), (b.size - offset).toInt())
}
