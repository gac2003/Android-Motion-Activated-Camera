package com.motioncam.cloud

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/**
 * Minimal FTP / FTPS (explicit TLS) client: passive mode, binary transfers, resume via REST.
 * Uploads go to "<name>.part" and are renamed when complete, so half-uploaded files never look like clips.
 */
class FtpProvider(
    private val host: String, private val port: Int, private val user: String, private val pass: String,
    private val tls: Boolean,
) : CloudProvider {
    private var sock: Socket? = null
    private var reader: BufferedReader? = null
    private var out: OutputStream? = null
    private val ssl: SSLContext by lazy { SSLContext.getInstance("TLS").apply { init(null, null, null) } }

    private class Reply(val code: Int, val text: String)

    private fun net(e: IOException): Nothing = throw CloudException("FTP connection problem: ${e.message}", CloudException.Kind.NETWORK, e)

    private fun read(): Reply {
        val r = reader ?: throw CloudException("Not connected", CloudException.Kind.NETWORK)
        var line = r.readLine() ?: throw CloudException("FTP server closed the connection", CloudException.Kind.NETWORK)
        val sb = StringBuilder(line)
        if (line.length > 3 && line[3] == '-') {
            val end = line.substring(0, 3) + " "
            while (true) {
                line = r.readLine() ?: break
                sb.append('\n').append(line)
                if (line.startsWith(end)) break
            }
        }
        return Reply(line.take(3).toIntOrNull() ?: 0, sb.toString())
    }

    private fun cmd(c: String): Reply {
        try {
            out!!.write("$c\r\n".toByteArray(Charsets.UTF_8))
            out!!.flush()
            return read()
        } catch (e: IOException) { net(e) }
    }

    private fun expect(r: Reply, vararg ok: Int, what: String): Reply {
        if (r.code in ok) return r
        val kind = when (r.code) {
            530 -> CloudException.Kind.AUTH
            452, 552 -> CloudException.Kind.QUOTA
            550 -> CloudException.Kind.NOT_FOUND
            421, 425, 426, 450, 451 -> CloudException.Kind.NETWORK
            else -> CloudException.Kind.OTHER
        }
        throw CloudException("$what: ${r.text.take(200)}", kind)
    }

    private fun connect() {
        if (sock?.isConnected == true && sock?.isClosed == false) return
        try {
            var s = Socket()
            s.connect(InetSocketAddress(host, port), 15_000)
            s.soTimeout = 60_000
            setStreams(s)
            expect(read(), 220, what = "Connect")
            if (tls) {
                expect(cmd("AUTH TLS"), 234, what = "Start TLS (server may not support FTPS)")
                val t = ssl.socketFactory.createSocket(s, host, port, true) as SSLSocket
                t.startHandshake()
                s = t
                setStreams(s)
            }
            val u = cmd("USER $user")
            if (u.code == 331) expect(cmd("PASS $pass"), 230, 202, what = "Log in")
            else expect(u, 230, what = "Log in")
            if (tls) { cmd("PBSZ 0"); expect(cmd("PROT P"), 200, what = "Protect data") }
            expect(cmd("TYPE I"), 200, what = "Binary mode")
        } catch (e: IOException) { net(e) }
    }

    private fun setStreams(s: Socket) {
        sock = s
        reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
        out = s.getOutputStream()
    }

    /** Opens a passive data connection (EPSV, falling back to PASV; always to the control host). */
    private fun data(): Socket {
        val r = cmd("EPSV")
        val p = if (r.code == 229) {
            Regex("\\(\\|\\|\\|(\\d+)\\|\\)").find(r.text)?.groupValues?.get(1)?.toInt()
        } else null
        val dport = p ?: run {
            val pr = expect(cmd("PASV"), 227, what = "Passive mode")
            val n = Regex("(\\d+),(\\d+),(\\d+),(\\d+),(\\d+),(\\d+)").find(pr.text)?.groupValues
                ?: throw CloudException("Bad PASV reply", CloudException.Kind.OTHER)
            n[5].toInt() * 256 + n[6].toInt()
        }
        try {
            val d = Socket()
            d.connect(InetSocketAddress(host, dport), 15_000)
            d.soTimeout = 60_000
            if (!tls) return d
            val t = ssl.socketFactory.createSocket(d, host, dport, true) as SSLSocket
            t.useClientMode = true
            return t
        } catch (e: IOException) { net(e) }
    }

    private fun path(folder: String, name: String) = folder.trimEnd('/') + "/" + name

    override fun test(): String { connect(); cmd("NOOP"); return "$user@$host" }

    override fun listFolders(parent: String?): List<RemoteFolder> {
        connect()
        val dir = parent ?: (Regex("\"(.*)\"").find(cmd("PWD").text)?.groupValues?.get(1) ?: "/")
        return mlsd(dir).filter { it[0] == "dir" }.map { RemoteFolder(path(dir, it[1] as String), it[1] as String) }.sortedBy { it.name.lowercase() }
    }

    override fun createFolder(parent: String?, name: String): RemoteFolder {
        connect()
        val dir = parent ?: (Regex("\"(.*)\"").find(cmd("PWD").text)?.groupValues?.get(1) ?: "/")
        val p = path(dir, name)
        val r = cmd("MKD $p")
        if (r.code != 257 && r.code != 550) expect(r, 257, what = "Create folder")
        return RemoteFolder(p, name)
    }

    /** (type, name, size, modifiedMs) via MLSD; falls back to NLST + SIZE on old servers. */
    private fun mlsd(dir: String): List<Array<Any>> {
        val d = data()
        val r = cmd("MLSD $dir")
        if (r.code !in listOf(125, 150)) {
            try { d.close() } catch (_: Exception) {}
            if (r.code in listOf(500, 501, 502)) return nlst(dir)
            expect(r, 150, what = "List folder")
        }
        val lines = try { d.getInputStream().bufferedReader(Charsets.UTF_8).use { it.readLines() } } catch (e: IOException) { net(e) } finally { d.close() }
        expect(read(), 226, 250, what = "List folder")
        val fmt = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return lines.mapNotNull { l ->
            val sp = l.indexOf(' ')
            if (sp < 0) return@mapNotNull null
            val facts = l.substring(0, sp).split(';').filter { it.contains('=') }.associate { it.substringBefore('=').lowercase() to it.substringAfter('=') }
            val name = l.substring(sp + 1)
            val type = facts["type"]?.lowercase() ?: return@mapNotNull null
            if (type == "cdir" || type == "pdir") return@mapNotNull null
            val mod = facts["modify"]?.take(14)?.let { try { fmt.parse(it)?.time } catch (e: Exception) { null } } ?: 0L
            arrayOf<Any>(if (type == "dir") "dir" else "file", name, facts["size"]?.toLongOrNull() ?: 0L, mod)
        }
    }

    private fun nlst(dir: String): List<Array<Any>> {
        val d = data()
        expect(cmd("NLST $dir"), 125, 150, what = "List folder")
        val names = try { d.getInputStream().bufferedReader().use { it.readLines() } } catch (e: IOException) { net(e) } finally { d.close() }
        expect(read(), 226, 250, what = "List folder")
        return names.map { it.substringAfterLast('/') }.filter { it.isNotEmpty() }.map { n ->
            val sz = size(path(dir, n))
            arrayOf<Any>(if (sz == null) "dir" else "file", n, sz ?: 0L, 0L)
        }
    }

    private fun size(p: String): Long? {
        val r = cmd("SIZE $p")
        return if (r.code == 213) r.text.substring(4).trim().toLongOrNull() else null
    }

    override fun list(folder: String): List<RemoteFile> {
        connect()
        return mlsd(folder).filter { it[0] == "file" }.map {
            RemoteFile(path(folder, it[1] as String), it[1] as String, it[2] as Long, it[3] as Long)
        }
    }

    override fun quota(folder: String): Quota? = null   // FTP has no standard way to read free space

    override fun upload(folder: String, name: String, src: UploadSource, resume: String?,
                        saveResume: (String?) -> Unit, progress: (Long) -> Unit): RemoteFile {
        connect()
        val final = path(folder, name)
        val part = "$final.part"
        var offset = size(part) ?: 0L
        if (offset > src.size) { cmd("DELE $part"); offset = 0 }
        if (offset < src.size) {
            val d = data()
            if (offset > 0) expect(cmd("REST $offset"), 350, what = "Resume")
            expect(cmd("STOR $part"), 125, 150, what = "Upload")
            progress(offset)
            try {
                d.getOutputStream().use { o ->
                    src.open(offset).use { input ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            o.write(buf, 0, n)
                            progress(n.toLong())
                        }
                    }
                }
            } catch (e: IOException) { net(e) } finally { try { d.close() } catch (_: Exception) {} }
            expect(read(), 226, 250, what = "Upload")
        }
        expect(cmd("RNFR $part"), 350, what = "Rename")
        expect(cmd("RNTO $final"), 250, what = "Rename")
        return RemoteFile(final, name, src.size, System.currentTimeMillis())
    }

    /** RETR from [offset]. Reading to the end and closing finishes the transfer; closing early drops the connection. */
    override fun open(file: RemoteFile, offset: Long): java.io.InputStream {
        connect()
        val d = data()
        if (offset > 0) expect(cmd("REST $offset"), 350, what = "Resume")
        expect(cmd("RETR ${file.id}"), 125, 150, what = "Download")
        val input = try { d.getInputStream() } catch (e: IOException) { net(e) }
        return object : java.io.FilterInputStream(input) {
            private var eof = false
            private var closed = false
            override fun read(): Int = super.read().also { if (it < 0) eof = true }
            override fun read(b: ByteArray, off: Int, len: Int): Int = try {
                super.read(b, off, len).also { if (it < 0) eof = true }
            } catch (e: IOException) { net(e) }
            override fun close() {
                if (closed) return
                closed = true
                try { d.close() } catch (_: Exception) {}
                if (eof) { try { read226() } catch (_: Exception) { dropConnection() } } else dropConnection()
            }
        }
    }

    private fun read226() { expect(read(), 226, 250, what = "Download") }

    private fun dropConnection() { try { sock?.close() } catch (_: Exception) {}; sock = null }

    override fun delete(folder: String, file: RemoteFile) {
        connect()
        val r = cmd("DELE ${file.id}")
        if (r.code != 550) expect(r, 250, what = "Delete")
    }

    override fun discardPartial(folder: String, name: String) {
        try { connect(); cmd("DELE ${path(folder, name)}.part") } catch (_: Exception) {}
    }

    override fun close() {
        try { out?.write("QUIT\r\n".toByteArray()); out?.flush() } catch (_: Exception) {}
        try { sock?.close() } catch (_: Exception) {}
        sock = null
    }
}
