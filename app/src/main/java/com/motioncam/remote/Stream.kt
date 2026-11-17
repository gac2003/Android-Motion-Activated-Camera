package com.motioncam.remote

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Downloads remote clips into the cache, one background download per clip. Readers (the local stream
 * server, save-to-phone) can use the bytes as soon as they arrive. Finished clips are kept (up to
 * [MAX_CACHE] for all remote folders together, least recently used removed first), so replaying,
 * scrubbing and filmstrip frames are instant and work offline.
 *
 * Only what is wanted is downloaded:
 *  - a short clip (up to [SMALL]) is fetched whole at once, so going from clip to clip stays seamless;
 *  - the clip being watched ([watching]) is fetched whole too, whatever its size;
 *  - any other longer clip is fetched only as far as it is being read plus a few MB ahead: its start when the player
 *    prepares it as the next clip. When nobody reads on (skipped, the player left), the download stops and keeps what
 *    it has; it carries on from there when the clip is read or watched again;
 *  - a clip that is almost complete (90 %, or under [TAIL] to go) is finished anyway, and so is one that is being
 *    saved to the phone or copied.
 */
object StreamCache {
    const val MAX_CACHE = 1_500_000_000L

    /** Clips up to this size are downloaded whole straight away. */
    const val SMALL = 10_000_000L
    /** A longer clip starts with this much. */
    private const val INITIAL = 4_000_000L
    /** When a reader is within [LOW] of what is wanted, the download goes on to [HIGH] past the reader. */
    private const val LOW = 2_000_000L
    private const val HIGH = 8_000_000L
    private const val TAIL = 2_000_000L

    class Download(val ctx: Context, val entry: RemoteRepo.Entry, val part: File, val done: File) {
        /** How many bytes of the clip are wanted for now (Long.MAX_VALUE = all of it). Guarded by [lock]. */
        var want = Long.MAX_VALUE
        /** A download thread is at work on it. Guarded by [lock]. */
        @Volatile var running = false
        @Volatile var available = 0L
        @Volatile var complete = false
        @Volatile var error: Exception? = null
        /** When the last bytes arrived: a slow download keeps going as long as it moves. */
        @Volatile var lastProgress = System.currentTimeMillis()
        val lock = Object()
        val size get() = entry.file.size
    }

    private val active = ConcurrentHashMap<String, Download>()
    private val pool = Executors.newFixedThreadPool(3) { r -> Thread(r, "remote-dl").apply { isDaemon = true } }

    private fun key(e: RemoteRepo.Entry) = e.src.id + "/" + e.name
    private fun clipsDir(ctx: Context, e: RemoteRepo.Entry) = File(RemoteRepo.cacheDir(ctx, e.src.id), "clips").apply { mkdirs() }
    private fun doneFile(ctx: Context, e: RemoteRepo.Entry) = File(clipsDir(ctx, e), e.name)

    /** The fully downloaded file, or null. */
    fun completeFile(ctx: Context, e: RemoteRepo.Entry): File? {
        val f = doneFile(ctx, e)
        return if (f.exists() && (e.file.size <= 0 || f.length() == e.file.size)) f else null
    }

    /** Starts (or joins) the download of [e]. */
    fun start(ctx: Context, e: RemoteRepo.Entry): Download {
        val app = ctx.applicationContext
        synchronized(active) {
            active[key(e)]?.let { if (it.error == null) return it }
            val done = doneFile(app, e)
            val dl = Download(app, e, File(done.path + ".part"), done)
            if (completeFile(app, e) != null) {
                dl.available = done.length(); dl.complete = true
                done.setLastModified(System.currentTimeMillis())
                return dl
            }
            active[key(e)] = dl
            // a short clip (or one of unknown size) whole; a longer one its start, the rest as it is read
            synchronized(dl.lock) {
                dl.want = if (dl.size <= 0 || dl.size <= SMALL) Long.MAX_VALUE else INITIAL
                dl.running = true
            }
            pool.execute { run(app, dl) }
            return dl
        }
    }

    /**
     * A reader is at byte [pos] of the clip (or wants all of it: Long.MAX_VALUE): the download goes on far enough
     * ahead of it, starting again if it had stopped.
     */
    fun need(dl: Download, pos: Long) {
        synchronized(dl.lock) {
            if (dl.complete || dl.error != null) return
            var w = dl.want
            if (pos >= Long.MAX_VALUE - HIGH) w = Long.MAX_VALUE
            else if (pos + LOW >= w) w = pos + HIGH
            if (dl.size > 0 && w != Long.MAX_VALUE && dl.size - w < TAIL) w = Long.MAX_VALUE   // nearly all of it: finish
            dl.want = w
            if (!dl.running && dl.available < w) {
                dl.running = true
                dl.lastProgress = System.currentTimeMillis()
                pool.execute { run(dl.ctx, dl) }
            }
        }
    }

    @Volatile private var watched: String? = null

    /**
     * The clip being watched now ([e]; null = none, the player closed or shows a clip on the phone): it is downloaded
     * whole, so scrubbing and its filmstrip are quick. The one watched before stops where it is (unless nearly complete).
     */
    fun watching(ctx: Context, e: RemoteRepo.Entry?) {
        val k = e?.let { key(it) }
        val old = watched
        if (old == k) return
        watched = k
        if (old != null) active[old]?.let { dl -> synchronized(dl.lock) { if (!dl.complete) dl.want = dl.available } }
        if (e != null) need(start(ctx, e), Long.MAX_VALUE)
    }

    /** Enough for now: as far as wanted, unless the clip is nearly complete anyway. */
    private fun enough(dl: Download): Boolean = synchronized(dl.lock) {
        dl.available >= dl.want && !(dl.size > 0 && dl.available * 10 >= dl.size * 9)
    }

    /** A slow connection: the download only gives up after this long without receiving anything. */
    const val STALL_MS = 3 * 60_000L

    private fun run(ctx: Context, dl: Download) {
        try {
            if (dl.part.exists() && dl.size in 1 until dl.part.length()) dl.part.delete()
            // like a video app on a slow connection: a dropped or timed-out download picks up where it stopped, as long
            // as it still gets somewhere (it only gives up after STALL_MS with nothing arriving)
            var tries = 0
            var paused = false
            while (true) {
                val offset = if (dl.part.exists()) dl.part.length() else 0L   // resume a partial download
                dl.available = offset
                if (dl.size > 0 && offset >= dl.size) break
                try {
                    RemoteSources.reader(ctx, dl.entry.src).use { r ->
                        r.open(dl.entry.file, offset).use { input ->
                            RandomAccessFile(dl.part, "rw").use { out ->
                                out.seek(offset)
                                val buf = ByteArray(128 * 1024)
                                while (true) {
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                    synchronized(dl.lock) { dl.available += n; dl.lastProgress = System.currentTimeMillis(); dl.lock.notifyAll() }
                                    if (enough(dl)) { paused = true; break }   // nobody is reading on: stop here, keep what's in
                                }
                            }
                        }
                    }
                    break
                } catch (e: Exception) {
                    if (e is com.motioncam.cloud.CloudException && e.kind == com.motioncam.cloud.CloudException.Kind.AUTH) throw e   // retrying won't help
                    if (System.currentTimeMillis() - dl.lastProgress > STALL_MS) throw e
                    tries++
                    Thread.sleep(minOf(2_000L * tries, 15_000L))
                }
            }
            if (paused) {
                // stopped part-way on purpose; carry on at once if a reader asked for more meanwhile
                synchronized(dl.lock) {
                    if (dl.available < dl.want) { dl.lastProgress = System.currentTimeMillis(); pool.execute { run(ctx, dl) } }
                    else { dl.running = false; dl.lock.notifyAll() }
                }
                return
            }
            if (dl.size > 0 && dl.part.length() != dl.size) throw IOException("download incomplete (${dl.part.length()} of ${dl.size} bytes)")
            dl.part.renameTo(dl.done)
            synchronized(dl.lock) { dl.available = dl.done.length(); dl.complete = true; dl.running = false; dl.lock.notifyAll() }
            synchronized(active) { if (active[key(dl.entry)] === dl) active.remove(key(dl.entry)) }
            try { RemoteRepo.onDownloaded(ctx, dl.entry, dl.done) } catch (_: Throwable) {}
            trim(ctx)
        } catch (e: Exception) {
            synchronized(dl.lock) { dl.error = e; dl.running = false; dl.lock.notifyAll() }
            synchronized(active) { if (active[key(dl.entry)] === dl) active.remove(key(dl.entry)) }
        }
    }

    /**
     * Waits until [pos] bytes are available (or the download ended). Returns false on failure, or when nothing has
     * arrived for [timeoutMs]: a slow download that still moves is waited for (not a fixed time for each piece).
     */
    fun waitFor(dl: Download, pos: Long, timeoutMs: Long = STALL_MS): Boolean {
        need(dl, pos)   // the download goes on (or starts again) far enough for this reader
        synchronized(dl.lock) {
            while (dl.available < pos && !dl.complete && dl.error == null) {
                if (System.currentTimeMillis() - dl.lastProgress > timeoutMs) return false
                dl.lock.wait(1000)
            }
            return dl.available >= pos || dl.complete
        }
    }

    /** Downloads the whole clip (or uses the cached copy) and returns the file. */
    fun downloadBlocking(ctx: Context, e: RemoteRepo.Entry, cancelled: () -> Boolean, pct: (Int) -> Unit): File {
        completeFile(ctx, e)?.let { return it }
        var dl = start(ctx, e)
        var retries = 0
        while (true) {
            need(dl, Long.MAX_VALUE)   // all of it
            if (cancelled()) throw IOException("cancelled")
            synchronized(dl.lock) { if (!dl.complete && dl.error == null) dl.lock.wait(500) }
            if (dl.size > 0) pct((dl.available * 100 / dl.size).toInt().coerceIn(0, 100))
            if (dl.complete) return dl.done
            val err = dl.error
            if (err != null) {
                if (++retries > 3) throw err
                Thread.sleep(2000L * retries)
                dl = start(ctx, e)
            }
        }
    }

    /** Keeps the cache under [MAX_CACHE]: oldest-used finished clips go first. */
    @Synchronized
    fun trim(ctx: Context) {
        val root = File(ctx.cacheDir, "remote")
        val files = root.listFiles()?.flatMap { File(it, "clips").listFiles()?.toList() ?: emptyList() }
            ?.filter { it.isFile && !it.name.endsWith(".part") } ?: return
        // parts of clips that were left unfinished and not touched for a day
        val now = System.currentTimeMillis()
        root.listFiles()?.forEach { d -> File(d, "clips").listFiles()?.forEach { f ->
            if (f.name.endsWith(".part") && now - f.lastModified() > 86_400_000L) synchronized(active) {
                val dl = active.values.firstOrNull { it.part == f }
                if (dl == null || !dl.running) { if (dl != null) active.remove(key(dl.entry)); f.delete() }
            }
        } }
        var total = files.sumOf { it.length() }
        if (total <= MAX_CACHE) return
        val busy = active.values.map { it.done.path }.toSet()
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= MAX_CACHE) break
            if (f.path in busy) continue
            total -= f.length()
            f.delete()
        }
    }

    /** Size of all downloaded remote clips. */
    fun cacheBytes(ctx: Context): Long =
        File(ctx.cacheDir, "remote").walkTopDown().filter { it.isFile && it.parentFile?.name == "clips" }.sumOf { it.length() }

    fun clearAll(ctx: Context) {
        File(ctx.cacheDir, "remote").listFiles()?.forEach { src ->
            File(src, "clips").listFiles()?.forEach { f ->
                synchronized(active) {
                    val dl = active.values.firstOrNull { it.part == f || it.done == f }
                    if (dl == null || !dl.running) { if (dl != null) active.remove(key(dl.entry)); f.delete() }
                }
            }
        }
    }
}

/**
 * A tiny HTTP server on 127.0.0.1 that the video player (and frame grabber) read remote clips from. It adds
 * the sign-in to each request and supports byte ranges, so playback starts before the whole clip is in and
 * seeking works. Bytes come from the cache download where possible; a jump far ahead of it (the index at
 * the end of an MP4) is fetched directly from the cloud with a range request.
 * URLs carry a random secret, so other apps on the phone cannot use the server to read the clips.
 */
object StreamProxy {
    private var server: ServerSocket? = null
    private var appCtx: Context? = null
    private val secret = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "remote-stream").apply { isDaemon = true } }

    @Synchronized
    private fun ensure(ctx: Context): Int {
        server?.let { if (!it.isClosed) return it.localPort }
        appCtx = ctx.applicationContext
        val s = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        server = s
        Thread({
            while (!s.isClosed) {
                val c = try { s.accept() } catch (e: IOException) { break }
                pool.execute { try { handle(c) } catch (_: Exception) {} finally { try { c.close() } catch (_: Exception) {} } }
            }
        }, "remote-proxy").apply { isDaemon = true }.start()
        return s.localPort
    }

    fun uri(ctx: Context, id: Long): Uri = Uri.parse("http://127.0.0.1:${ensure(ctx)}/$secret/$id.mp4")

    private fun handle(sock: Socket) {
        sock.soTimeout = 60_000
        val input = sock.getInputStream()
        val line = readLine(input) ?: return
        val headers = HashMap<String, String>()
        while (true) {
            val h = readLine(input) ?: break
            if (h.isEmpty()) break
            val i = h.indexOf(':')
            if (i > 0) headers[h.substring(0, i).trim().lowercase()] = h.substring(i + 1).trim()
        }
        val parts = line.split(" ")
        val out = sock.getOutputStream()
        if (parts.size < 2) return
        val method = parts[0]
        val path = parts[1].trimStart('/').split("/")
        if (path.size != 2 || path[0] != secret) { status(out, 403, "Forbidden"); return }
        val id = path[1].removeSuffix(".mp4").toLongOrNull()
        val ctx = appCtx ?: return
        val e = id?.let { RemoteRepo.entry(it) } ?: run { status(out, 404, "Not Found"); return }

        // unknown size (rare): only possible to serve once the whole file is in
        var size = e.file.size
        if (size <= 0) {
            val dl = StreamCache.start(ctx, e)
            if (!StreamCache.waitFor(dl, Long.MAX_VALUE, 180_000) || !dl.complete) { status(out, 502, "Bad Gateway"); return }
            size = dl.done.length()
        }
        val range = headers["range"]?.let { Regex("bytes=(\\d*)-(\\d*)").find(it) }
        var start = 0L
        var end = size - 1
        if (range != null) {
            val a = range.groupValues[1]
            val b = range.groupValues[2]
            if (a.isEmpty() && b.isNotEmpty()) start = maxOf(0, size - b.toLong())   // last N bytes
            else {
                start = a.toLongOrNull() ?: 0
                if (b.isNotEmpty()) end = minOf(end, b.toLong())
            }
        }
        if (start >= size || start > end) {
            out.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$size\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            return
        }
        val len = end - start + 1
        val head = StringBuilder()
        head.append(if (range != null) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
        head.append("Content-Type: video/mp4\r\nAccept-Ranges: bytes\r\nContent-Length: $len\r\n")
        if (range != null) head.append("Content-Range: bytes $start-$end/$size\r\n")
        head.append("Connection: close\r\n\r\n")
        out.write(head.toString().toByteArray())
        if (method == "HEAD") return

        StreamCache.completeFile(ctx, e)?.let { f -> f.setLastModified(System.currentTimeMillis()); copyFile(f, start, len, out); return }
        val dl = StreamCache.start(ctx, e)
        // far ahead of the download (e.g. the MP4 index at the end): ask the cloud for just that part
        if (e.src.streams && start > dl.available + 2_000_000) { upstream(ctx, e, start, len, out); return }
        var pos = start
        val stop = start + len
        val buf = ByteArray(64 * 1024)
        var raf: RandomAccessFile? = null
        try {
            while (pos < stop) {
                if (!StreamCache.waitFor(dl, pos + 1)) {
                    // the download failed or stalled: continue straight from the cloud if possible
                    if (e.src.streams) { upstream(ctx, e, pos, stop - pos, out); return }
                    return
                }
                val file = raf ?: (try { RandomAccessFile(if (dl.complete) dl.done else dl.part, "r") }
                    catch (ex: IOException) { RandomAccessFile(dl.done, "r") }).also { raf = it }   // (renamed just now)
                val availNow = if (dl.complete) size else dl.available
                val n = minOf(buf.size.toLong(), stop - pos, availNow - pos).toInt()
                if (n <= 0) continue
                file.seek(pos)
                val r = file.read(buf, 0, n)
                if (r <= 0) { Thread.sleep(50); continue }
                out.write(buf, 0, r)
                pos += r
            }
            out.flush()
        } finally { try { raf?.close() } catch (_: Exception) {} }
    }

    private fun upstream(ctx: Context, e: RemoteRepo.Entry, start: Long, len: Long, out: OutputStream) {
        RemoteSources.reader(ctx, e.src).use { r ->
            r.open(e.file, start).use { input -> pipe(input, len, out) }
        }
    }

    private fun copyFile(f: File, start: Long, len: Long, out: OutputStream) {
        RandomAccessFile(f, "r").use { raf ->
            raf.seek(start)
            val buf = ByteArray(64 * 1024)
            var left = len
            while (left > 0) {
                val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) break
                out.write(buf, 0, n)
                left -= n
            }
        }
        out.flush()
    }

    private fun pipe(input: InputStream, len: Long, out: OutputStream) {
        val buf = ByteArray(64 * 1024)
        var left = len
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) break
            out.write(buf, 0, n)
            left -= n
        }
        out.flush()
    }

    private fun status(out: OutputStream, code: Int, text: String) {
        out.write("HTTP/1.1 $code $text\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
            if (sb.length > 8192) return null
        }
    }
}
