package com.motioncam.cloud

import java.io.InputStream

enum class CloudKind(val key: String, val label: String) {
    DRIVE("drive", "Google Drive"),
    DROPBOX("dropbox", "Dropbox"),
    FTP("ftp", "FTP / FTPS"),
    SFTP("sftp", "SFTP");

    companion object { fun of(key: String?) = values().firstOrNull { it.key == key } }
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
