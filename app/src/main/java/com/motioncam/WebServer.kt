package com.motioncam

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import android.util.Base64
import java.io.BufferedInputStream
import java.io.FileInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Tiny HTTP server (no dependencies) for remote viewing: live MJPEG, snapshot, status and recorded clips.
 * Protected by HTTP Basic auth (user "admin"). Works over any IP link: USB tethering, `adb forward`,
 * Bluetooth tethering, the phone's own hotspot, or normal Wi-Fi.
 */
class WebServer(private val ctx: Context, private val prefs: Prefs) {
    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()
    @Volatile private var running = false
    private val slots = java.util.concurrent.Semaphore(12)

    fun start(port: Int): Boolean {
        stop()
        return try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(port))
            server = ss
            running = true
            Thread({
                while (running) {
                    val s = try { ss.accept() } catch (e: IOException) { break }
                    // limit simultaneous connections so a misbehaving client cannot exhaust the phone
                    if (!slots.tryAcquire()) { try { s.close() } catch (_: Exception) {}; continue }
                    pool.execute { try { handle(s) } finally { slots.release() } }
                }
            }, "web-accept").start()
            true
        } catch (e: Exception) { false }
    }

    fun stop() {
        running = false
        try { server?.close() } catch (_: Exception) {}
        server = null
    }

    private fun authorised(h: Map<String, String>): Boolean {
        val pw = prefs.password
        if (pw.isEmpty()) return false
        val a = h["authorization"] ?: return false
        if (!a.startsWith("Basic ", ignoreCase = true)) return false
        val decoded = try { String(Base64.decode(a.substring(6).trim(), Base64.DEFAULT), Charsets.UTF_8) } catch (e: Exception) { return false }
        val expected = "admin:$pw"
        return MessageDigest.isEqual(decoded.toByteArray(), expected.toByteArray())
    }

    private fun handle(sock: Socket) {
        try {
            sock.soTimeout = 15000
            sock.tcpNoDelay = true
            val input = BufferedInputStream(sock.getInputStream())
            val out = sock.getOutputStream()
            val reqLine = readLine(input) ?: return
            val parts = reqLine.split(" ")
            if (parts.size < 2) return
            val headers = HashMap<String, String>()
            var lines = 0
            while (true) {
                if (++lines > 64) return
                val l = readLine(input) ?: return
                if (l.isEmpty()) break
                val i = l.indexOf(':')
                if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
            }
            if (!authorised(headers)) {
                text(out, 401, "Unauthorized", "text/plain", "Password required", "WWW-Authenticate: Basic realm=\"MotionCam\"\r\n")
                return
            }
            val full = parts[1]
            val path = full.substringBefore('?')
            when {
                parts[0] != "GET" -> text(out, 405, "Method Not Allowed", "text/plain", "GET only")
                path == "/" -> text(out, 200, "OK", "text/html; charset=utf-8", INDEX_HTML)
                path == "/status.json" -> text(out, 200, "OK", "application/json", statusJson())
                path == "/snapshot.jpg" -> snapshot(out)
                path == "/stream.mjpg" -> { sock.soTimeout = 0; stream(out) }
                path == "/clips" -> text(out, 200, "OK", "text/html; charset=utf-8", clipsHtml())
                path.startsWith("/clip/") -> clip(out, path.removePrefix("/clip/"), headers["range"], full.contains("dl=1"))
                else -> text(out, 404, "Not Found", "text/plain", "Not found")
            }
        } catch (_: Exception) {
        } finally {
            try { sock.close() } catch (_: Exception) {}
        }
    }

    private fun readLine(input: BufferedInputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) break
            if (c != '\r'.code) sb.append(c.toChar())
            if (sb.length > 8192) return null
        }
        return sb.toString()
    }

    private fun text(out: OutputStream, code: Int, msg: String, type: String, body: String, extra: String = "") {
        val b = body.toByteArray(Charsets.UTF_8)
        out.write("HTTP/1.1 $code $msg\r\nContent-Type: $type\r\nContent-Length: ${b.size}\r\nCache-Control: no-store\r\nConnection: close\r\n$extra\r\n".toByteArray())
        out.write(b)
        out.flush()
    }

    private fun snapshot(out: OutputStream) {
        FrameHub.clients++
        try {
            val after = FrameHub.currentSeq()
            val f = FrameHub.waitNext(after, 4000)?.second ?: FrameHub.latest()
            if (f == null) { text(out, 503, "Service Unavailable", "text/plain", "No frame yet"); return }
            out.write("HTTP/1.1 200 OK\r\nContent-Type: image/jpeg\r\nContent-Length: ${f.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray())
            out.write(f)
            out.flush()
        } finally { FrameHub.clients-- }
    }

    private fun stream(out: OutputStream) {
        out.write("HTTP/1.1 200 OK\r\nContent-Type: multipart/x-mixed-replace; boundary=frame\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray())
        FrameHub.clients++
        try {
            var seq = 0L
            while (running) {
                val f = FrameHub.waitNext(seq, 5000) ?: continue
                seq = f.first
                out.write("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${f.second.size}\r\n\r\n".toByteArray())
                out.write(f.second)
                out.write("\r\n".toByteArray())
                out.flush()
            }
        } finally { FrameHub.clients-- }
    }

    private fun statusJson(): String {
        val s = StatusBus.status
        fun esc(x: String) = x.replace("\\", "\\\\").replace("\"", "\\\"")
        return "{\"mode\":\"${s.mode}\",\"message\":\"${esc(s.message)}\",\"lens\":\"${esc(s.lens)}\"," +
            "\"format\":\"${esc(s.format)}\",\"recordingSince\":${s.recordingSinceWall},\"lastMotion\":${s.lastMotionWall}," +
            "\"battery\":${s.batteryPct},\"tempC\":${s.tempC},\"charging\":${s.charging},\"now\":${System.currentTimeMillis()}}"
    }

    private fun clipsHtml(): String {
        val df = SimpleDateFormat("yyyy-MM-dd  " + Fmt.time(prefs).toPattern(), Locale.US)
        val sb = StringBuilder(PAGE_HEAD)
        sb.append("<a href='/'>&larr; Live</a><h2>Recorded clips</h2><table>")
        for (c in ClipStore.list(ctx)) {
            sb.append("<tr><td>").append(df.format(Date(c.startMs))).append("</td><td>")
                .append(c.size / 1_000_000).append(" MB</td><td>").append(c.durationMs / 1000).append(" s</td><td>")
                .append("<a href='/clip/").append(c.id).append("'>play</a> · <a href='/clip/").append(c.id).append("?dl=1'>download</a></td></tr>")
        }
        sb.append("</table></body></html>")
        return sb.toString()
    }

    private fun clip(out: OutputStream, idStr: String, range: String?, download: Boolean) {
        val id = idStr.toLongOrNull()
        val known = id != null && ClipStore.list(ctx).any { it.id == id }
        if (!known) { text(out, 404, "Not Found", "text/plain", "No such clip"); return }
        val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id!!)
        val pfd = ctx.contentResolver.openFileDescriptor(uri, "r") ?: run { text(out, 404, "Not Found", "text/plain", "Gone"); return }
        pfd.use {
            val total = it.statSize
            var start = 0L
            var end = total - 1
            var partial = false
            if (range != null && range.startsWith("bytes=")) {
                val r = range.removePrefix("bytes=").split("-")
                r[0].toLongOrNull()?.let { s -> start = s; partial = true }
                if (r.size > 1) r[1].toLongOrNull()?.let { e -> end = minOf(e, total - 1) }
            }
            if (start > end) { text(out, 416, "Range Not Satisfiable", "text/plain", ""); return }
            val len = end - start + 1
            val disp = if (download) "Content-Disposition: attachment; filename=\"clip_$id.mp4\"\r\n" else ""
            val head = StringBuilder()
            head.append(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
            head.append("Content-Type: video/mp4\r\nAccept-Ranges: bytes\r\nContent-Length: $len\r\nConnection: close\r\n$disp")
            if (partial) head.append("Content-Range: bytes $start-$end/$total\r\n")
            head.append("\r\n")
            out.write(head.toString().toByteArray())
            val fis = FileInputStream(it.fileDescriptor)
            fis.channel.position(start)
            val buf = ByteArray(64 * 1024)
            var left = len
            while (left > 0) {
                val n = fis.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n <= 0) break
                out.write(buf, 0, n)
                left -= n
            }
            out.flush()
        }
    }

    companion object {
        private const val PAGE_HEAD = "<!doctype html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>" +
            "<title>MotionCam</title><style>body{background:#111;color:#ddd;font-family:sans-serif;margin:12px}a{color:#6cf}td{padding:4px 12px}" +
            "</style></head><body>"

        private val INDEX_HTML = PAGE_HEAD +
            "<h2>MotionCam</h2><img src='/stream.mjpg' style='width:100%;max-width:1280px;background:#000'>" +
            "<p id='s'>…</p><p><a href='/clips'>Recorded clips</a> · <a href='/snapshot.jpg'>Snapshot</a></p>" +
            "<script>function u(){fetch('/status.json').then(r=>r.json()).then(j=>{" +
            "let t=j.mode+' — '+j.message+' · '+j.format+' · battery '+j.battery+'%'+(j.charging?' (charging)':'')+' · '+j.tempC+'°C';" +
            "if(j.lastMotion)t+=' · last motion '+Math.round((j.now-j.lastMotion)/1000)+'s ago';" +
            "document.getElementById('s').textContent=t}).catch(()=>{})}u();setInterval(u,2000);</script></body></html>"

        /** Non-loopback IPv4 addresses, e.g. the USB-tether or hotspot address of the phone. */
        fun localAddresses(): List<Pair<String, String>> {
            val out = ArrayList<Pair<String, String>>()
            try {
                for (ni in NetworkInterface.getNetworkInterfaces()) {
                    if (!ni.isUp || ni.isLoopback) continue
                    for (a in ni.inetAddresses) {
                        val h = a.hostAddress ?: continue
                        if (a is java.net.Inet4Address) out += Pair(ni.name, h)
                    }
                }
            } catch (_: Exception) {}
            return out
        }
    }
}
