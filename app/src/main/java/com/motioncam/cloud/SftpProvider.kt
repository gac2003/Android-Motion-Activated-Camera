package com.motioncam.cloud

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import com.jcraft.jsch.SftpException
import com.jcraft.jsch.SftpProgressMonitor

/**
 * SFTP over SSH (JSch). Password or private-key login. The server's host key is trusted on first use and
 * must match afterwards (protects against someone impersonating the server).
 * Uploads go to "<name>.part" (resumable by appending) and are renamed when complete.
 */
class SftpProvider(
    private val host: String, private val port: Int, private val user: String,
    private val password: String?, private val privateKey: String?, private val passphrase: String?,
    private val trustedHostKey: String?, private val onNewHostKey: (String) -> Unit,
) : CloudProvider {
    private var session: Session? = null
    private var ch: ChannelSftp? = null

    private fun sftp(): ChannelSftp {
        ch?.let { if (it.isConnected) return it }
        try {
            val jsch = JSch()
            if (privateKey != null) jsch.addIdentity("key", privateKey.toByteArray(), null, passphrase?.toByteArray())
            val s = jsch.getSession(user, host, port)
            if (password != null && privateKey == null) s.setPassword(password)
            s.setConfig("StrictHostKeyChecking", "no")   // verified manually below (trust on first use)
            s.setConfig("PreferredAuthentications", if (privateKey != null) "publickey" else "password,keyboard-interactive")
            s.timeout = 30_000
            s.connect(15_000)
            val fp = s.hostKey.getFingerPrint(jsch)
            if (trustedHostKey == null) onNewHostKey(fp)
            else if (!trustedHostKey.equals(fp, ignoreCase = true)) {
                s.disconnect()
                throw CloudException("Server identity changed (host key $fp). If you reinstalled the server, tap \"Forget host key\".", CloudException.Kind.AUTH)
            }
            val c = s.openChannel("sftp") as ChannelSftp
            c.connect(15_000)
            session = s
            ch = c
            return c
        } catch (e: JSchException) {
            val m = e.message ?: ""
            val kind = if (m.contains("Auth", true)) CloudException.Kind.AUTH else CloudException.Kind.NETWORK
            throw CloudException("SFTP: $m", kind, e)
        }
    }

    private fun <T> op(what: String, f: (ChannelSftp) -> T): T = try { f(sftp()) } catch (e: SftpException) {
        // an abort from our own progress callback (e.g. connection dropped / stop requested) arrives wrapped
        (e.cause as? CloudException)?.let { throw it }
        val kind = when (e.id) {
            ChannelSftp.SSH_FX_NO_SUCH_FILE -> CloudException.Kind.NOT_FOUND
            ChannelSftp.SSH_FX_PERMISSION_DENIED -> CloudException.Kind.OTHER
            ChannelSftp.SSH_FX_CONNECTION_LOST, ChannelSftp.SSH_FX_NO_CONNECTION -> CloudException.Kind.NETWORK
            else -> if ((e.message ?: "").contains("space", true) || (e.message ?: "").contains("quota", true)) CloudException.Kind.QUOTA else CloudException.Kind.OTHER
        }
        throw CloudException("$what: ${e.message}", kind, e)
    }

    private fun path(folder: String, name: String) = folder.trimEnd('/') + "/" + name

    override fun test(): String { op("Connect") { it.pwd() }; return "$user@$host" }

    override fun listFolders(parent: String?): List<RemoteFolder> = op("List folder") { c ->
        val dir = parent ?: c.pwd()
        @Suppress("UNCHECKED_CAST")
        (c.ls(dir) as java.util.Vector<ChannelSftp.LsEntry>)
            .filter { it.attrs.isDir && it.filename != "." && it.filename != ".." }
            .map { RemoteFolder(path(dir, it.filename), it.filename) }.sortedBy { it.name.lowercase() }
    }

    override fun createFolder(parent: String?, name: String): RemoteFolder = op("Create folder") { c ->
        val p = path(parent ?: c.pwd(), name)
        try { c.mkdir(p) } catch (e: SftpException) { if (e.id != ChannelSftp.SSH_FX_FAILURE) throw e }   // exists
        RemoteFolder(p, name)
    }

    override fun list(folder: String): List<RemoteFile> = op("List folder") { c ->
        @Suppress("UNCHECKED_CAST")
        (c.ls(folder) as java.util.Vector<ChannelSftp.LsEntry>)
            .filter { !it.attrs.isDir }
            .map { RemoteFile(path(folder, it.filename), it.filename, it.attrs.size, it.attrs.mTime * 1000L) }
    }

    /** Uses the statvfs@openssh.com extension when the server supports it. */
    override fun quota(folder: String): Quota? = try {
        val v = sftp().statVFS(folder)
        val total = v.size * 1024
        val avail = v.availForNonRoot * 1024
        if (total > 0) Quota(total - avail, total) else null
    } catch (e: Exception) { null }

    override fun upload(folder: String, name: String, src: UploadSource, resume: String?,
                        saveResume: (String?) -> Unit, progress: (Long) -> Unit): RemoteFile = op("Upload") { c ->
        val final = path(folder, name)
        val part = "$final.part"
        var offset = try { c.stat(part).size } catch (e: SftpException) { 0L }
        if (offset > src.size) { c.rm(part); offset = 0 }
        progress(offset)
        if (offset < src.size) {
            val monitor = object : SftpProgressMonitor {
                override fun init(op: Int, s: String?, d: String?, max: Long) {}
                override fun count(count: Long): Boolean { progress(count); return true }
                override fun end() {}
            }
            src.open(offset).use { input -> c.put(input, part, monitor, if (offset > 0) ChannelSftp.APPEND else ChannelSftp.OVERWRITE) }
        }
        try { c.rm(final) } catch (_: SftpException) {}
        c.rename(part, final)
        RemoteFile(final, name, src.size, System.currentTimeMillis())
    }

    override fun delete(folder: String, file: RemoteFile) {
        try { op("Delete") { it.rm(file.id) } } catch (e: CloudException) { if (e.kind != CloudException.Kind.NOT_FOUND) throw e }
    }

    override fun discardPartial(folder: String, name: String) {
        try { sftp().rm(path(folder, name) + ".part") } catch (_: Exception) {}
    }

    override fun close() {
        try { ch?.disconnect() } catch (_: Exception) {}
        try { session?.disconnect() } catch (_: Exception) {}
        ch = null
        session = null
    }
}
