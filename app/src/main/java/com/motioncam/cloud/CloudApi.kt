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
class RemoteFile(val id: String, val name: String, val size: Long, val modifiedMs: Long)

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
    override fun close() {}
}

/** Our clips only: never touch anything else in the folder. */
fun isOurClip(name: String) = name.startsWith("MC_") && name.endsWith(".mp4")
