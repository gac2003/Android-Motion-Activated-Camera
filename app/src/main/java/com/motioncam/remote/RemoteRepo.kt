package com.motioncam.remote

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.motioncam.Clip
import com.motioncam.ClipStore
import com.motioncam.ClipThumbs
import com.motioncam.cloud.CloudException
import com.motioncam.cloud.RemoteFile
import com.motioncam.cloud.THUMB_FOLDER
import com.motioncam.cloud.isOurClip
import com.motioncam.cloud.parseThumbName
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Clips of remote folders, presented as ordinary [Clip]s so the normal gallery and timeline player work
 * unchanged:
 *  - ids are negative (never clash with the phone's own clips) and stable (derived from source + file name),
 *    so remote Favourites / collections keep working across refreshes;
 *  - the uri points at [StreamProxy] on 127.0.0.1, which streams (and caches) the file from the cloud.
 * The last listing of each folder is saved, so the gallery opens instantly and works offline for cached clips.
 */
object RemoteRepo {
    class Entry(val src: RemoteSource, val file: RemoteFile, val thumb: RemoteFile?,
                @Volatile var durationMs: Long, @Volatile var durationKnown: Boolean) {
        val name get() = file.name
    }

    private val entries = ConcurrentHashMap<Long, Entry>()
    private val lists = ConcurrentHashMap<String, List<Clip>>()
    private val durations = ConcurrentHashMap<String, MutableMap<String, Long>>()
    private val nameFmt get() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    fun isRemote(clip: Clip) = clip.id < 0
    fun entry(id: Long): Entry? = entries[id]

    /** Stable negative id for a clip name in a source (64-bit FNV-1a). */
    fun idFor(sourceId: String, name: String): Long {
        var h = -0x340d631b7bdddcdbL
        for (ch in "$sourceId|$name") { h = h xor ch.code.toLong(); h *= 0x100000001b3L }
        val v = (h and Long.MAX_VALUE).coerceAtLeast(1)
        return -v
    }

    fun dataDir(ctx: Context, sourceId: String) = File(ctx.filesDir, "remote/$sourceId").apply { mkdirs() }
    fun cacheDir(ctx: Context, sourceId: String) = File(ctx.cacheDir, "remote/$sourceId").apply { mkdirs() }
    private fun thumbsDir(ctx: Context, sourceId: String) = File(cacheDir(ctx, sourceId), "thumbs").apply { mkdirs() }

    // ------------------------------------------------------------------ listing

    /** Clips from memory or the last saved listing (no network). Newest first. */
    fun cached(ctx: Context, src: RemoteSource): List<Clip> {
        lists[src.id]?.let { return it }
        val f = File(dataDir(ctx, src.id), "listing.json")
        if (!f.exists()) return emptyList()
        return try {
            val o = JSONObject(f.readText())
            build(ctx, src, files(o.getJSONArray("files")), files(o.getJSONArray("thumbs")))
        } catch (e: Exception) { emptyList() }
    }

    /** Lists the folder again (network; call off the main thread). Throws [CloudException]. */
    fun refresh(ctx: Context, src: RemoteSource): List<Clip> {
        val (clipFiles, thumbFiles) = RemoteSources.reader(ctx, src).use { r ->
            val all = r.list(src.folderId)
            val tf = try {
                r.listFolders(src.folderId).firstOrNull { it.name == THUMB_FOLDER }?.let { r.list(it.id) } ?: emptyList()
            } catch (e: CloudException) { if (e.kind == CloudException.Kind.AUTH) throw e; emptyList() }
            Pair(all.filter { isOurClip(it.name) }, tf.filter { it.name.endsWith(".jpg") })
        }
        val o = JSONObject().put("files", json(clipFiles)).put("thumbs", json(thumbFiles))
        try {
            val f = File(dataDir(ctx, src.id), "listing.json")
            val t = File(f.path + ".tmp"); t.writeText(o.toString()); t.renameTo(f)
        } catch (_: Exception) {}
        lists.remove(src.id)
        noteSeen(ctx, src.id, clipFiles.map { it.name })
        val clips = build(ctx, src, clipFiles, thumbFiles)
        src.lastRefresh = System.currentTimeMillis()
        src.clipCount = clips.size
        RemoteSources.update(ctx, src)
        // shared collections: track clips missing from the folder, then sync collections (which then syncs marks)
        SharedSync.noteListing(ctx, src.id, clips.map { it.name }.toSet())
        SharedSync.schedule(ctx, src.id, 300)
        return clips
    }

    // ---- what this phone knows about clips that are no longer in a folder (to tell "deleted" from "not uploaded yet")

    private fun seenFile(ctx: Context, id: String) = File(dataDir(ctx, id), "seen.txt")
    private fun deletedFile(ctx: Context, id: String) = File(dataDir(ctx, id), "deleted.json")

    /** Remembers clip names seen in the folder (the newest 20 000). */
    @Synchronized
    fun noteSeen(ctx: Context, id: String, names: Collection<String>) {
        try {
            val f = seenFile(ctx, id)
            val set = LinkedHashSet(if (f.exists()) f.readLines() else emptyList())
            if (set.containsAll(names)) return
            set.addAll(names.sorted())
            f.writeText(set.toList().takeLast(20_000).joinToString("\n"))
        } catch (_: Exception) {}
    }

    fun seenBefore(ctx: Context, id: String, name: String): Boolean =
        try { seenFile(ctx, id).let { it.exists() && it.readLines().contains(name) } } catch (_: Exception) { false }

    /** Clips this phone deleted from the folder (name → when). */
    @Synchronized
    fun noteDeleted(ctx: Context, id: String, name: String) {
        try {
            val f = deletedFile(ctx, id)
            val o = if (f.exists()) JSONObject(f.readText()) else JSONObject()
            o.put(name, System.currentTimeMillis())
            f.writeText(o.toString())
        } catch (_: Exception) {}
    }

    fun deletedAt(ctx: Context, id: String, name: String): Long? =
        try { deletedFile(ctx, id).takeIf { it.exists() }?.let { JSONObject(it.readText()).optLong(name).takeIf { t -> t > 0 } } } catch (_: Exception) { null }

    /** Cached listing, or a network listing if there is none yet. */
    fun clips(ctx: Context, src: RemoteSource): List<Clip> = cached(ctx, src).ifEmpty { refresh(ctx, src) }

    private fun json(fs: List<RemoteFile>) = JSONArray().apply {
        for (f in fs) put(JSONObject().put("id", f.id).put("name", f.name).put("size", f.size).put("mod", f.modifiedMs).put("thumb", f.thumbUrl ?: ""))
    }

    private fun files(a: JSONArray) = (0 until a.length()).map { a.getJSONObject(it) }.map {
        RemoteFile(it.getString("id"), it.getString("name"), it.optLong("size"), it.optLong("mod"), it.optString("thumb").ifEmpty { null })
    }

    private fun build(ctx: Context, src: RemoteSource, clipFiles: List<RemoteFile>, thumbFiles: List<RemoteFile>): List<Clip> {
        val thumbs = HashMap<String, Pair<RemoteFile, Long>>()
        for (t in thumbFiles) parseThumbName(t.name)?.let { (clipName, dur) -> thumbs[clipName] = Pair(t, dur) }
        val known = durationsOf(ctx, src.id)
        // same name twice (e.g. uploaded twice before the duplicate check): keep the bigger file
        val byName = clipFiles.groupBy { it.name }.mapValues { (_, v) -> v.maxByOrNull { it.size }!! }
        // bytes per ms from clips with a known length, to estimate the others until they are opened
        val rates = byName.values.mapNotNull { f ->
            val d = thumbs[f.name]?.second?.takeIf { it > 0 } ?: known[f.name]
            if (d != null && d > 0 && f.size > 0) f.size.toDouble() / d else null
        }.sorted()
        val rate = if (rates.isNotEmpty()) rates[rates.size / 2] else 1_500.0   // ~12 Mbit/s
        val out = ArrayList<Clip>()
        for (f in byName.values) {
            val id = idFor(src.id, f.name)
            val t = thumbs[f.name]
            val d = t?.second?.takeIf { it > 0 } ?: known[f.name]
            val dur = d ?: (f.size / rate).toLong().coerceAtLeast(1000)
            val e = Entry(src, f, t?.first, dur, d != null)
            entries[id] = e
            out += Clip(id, StreamProxy.uri(ctx, id), f.name, f.size, startOf(f), dur)
        }
        out.sortByDescending { it.startMs }
        lists[src.id] = out
        return out
    }

    private fun startOf(f: RemoteFile): Long {
        if (f.name.length >= 18) try { return nameFmt.parse(f.name.substring(3, 18))!!.time } catch (_: Exception) {}
        return f.modifiedMs
    }

    // ------------------------------------------------------------------ durations

    private fun durationsOf(ctx: Context, sourceId: String): MutableMap<String, Long> = durations.getOrPut(sourceId) {
        val m = ConcurrentHashMap<String, Long>()
        try {
            val f = File(dataDir(ctx, sourceId), "durations.json")
            if (f.exists()) { val o = JSONObject(f.readText()); for (k in o.keys()) m[k] = o.getLong(k) }
        } catch (_: Exception) {}
        m
    }

    /** The real length, once the player or a download found it (replaces the estimate). */
    fun setDuration(ctx: Context, id: Long, ms: Long) {
        val e = entries[id] ?: return
        if (ms <= 0) return
        e.durationMs = ms
        e.durationKnown = true
        val m = durationsOf(ctx, e.src.id)
        if (m[e.name] == ms) return
        m[e.name] = ms
        lists.remove(e.src.id)   // rebuilt with the new length next time
        try {
            val o = JSONObject(); for ((k, v) in m) o.put(k, v)
            val f = File(dataDir(ctx, e.src.id), "durations.json")
            val t = File(f.path + ".tmp"); t.writeText(o.toString()); t.renameTo(f)
        } catch (_: Exception) {}
    }

    // ------------------------------------------------------------------ thumbnails

    private fun thumbFile(ctx: Context, e: Entry) = File(thumbsDir(ctx, e.src.id), e.name.removeSuffix(".mp4") + ".jpg")
    /** Marks a thumbnail made by the service (early frame), to be replaced by a better one when possible. */
    private fun weakMark(ctx: Context, e: Entry) = File(thumbsDir(ctx, e.src.id), e.name.removeSuffix(".mp4") + ".weak")

    fun thumbReady(ctx: Context, clip: Clip): Boolean = entries[clip.id]?.let { thumbFile(ctx, it).exists() } == true

    fun thumbCached(ctx: Context, clip: Clip): Bitmap? = entries[clip.id]?.let { e ->
        thumbFile(ctx, e).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }
    }

    /** The cached JPEG of a remote clip's thumbnail, if any (used when saving to the phone). */
    fun thumbJpeg(ctx: Context, id: Long): File? = entries[id]?.let { e -> thumbFile(ctx, e).takeIf { it.exists() && !weakMark(ctx, e).exists() } }

    /** Readers shared by thumbnail loads, one per source (FTP/SFTP connections are used one at a time). */
    private val thumbReaders = HashMap<String, RemoteReader>()
    private val thumbLocks = ConcurrentHashMap<String, Any>()
    /** After a sign-in problem, don't hammer the service for every tile. */
    private val thumbBlockedUntil = ConcurrentHashMap<String, Long>()

    private fun <T> withThumbReader(ctx: Context, src: RemoteSource, f: (RemoteReader) -> T): T {
        val lock = if (src.streams) Any() else thumbLocks.getOrPut(src.id) { Any() }
        synchronized(lock) {
            val r = synchronized(thumbReaders) { thumbReaders.getOrPut(src.id) { RemoteSources.reader(ctx, src) } }
            try { return f(r) } catch (e: Exception) {
                if (!src.streams) synchronized(thumbReaders) { thumbReaders.remove(src.id)?.let { try { it.close() } catch (_: Exception) {} } }
                throw e
            }
        }
    }

    /**
     * Best thumbnail for a remote clip (blocking):
     *  1. the peak-motion thumbnail the recording phone uploaded;
     *  2. the best frame of the clip, if it is already downloaded;
     *  3. the service's own preview (Drive / Dropbox), replaced by (2) once the clip has been played.
     */
    fun thumbLoad(ctx: Context, clip: Clip): Bitmap? {
        val e = entries[clip.id] ?: return null
        val f = thumbFile(ctx, e)
        if (f.exists()) return BitmapFactory.decodeFile(f.path)
        if ((thumbBlockedUntil[e.src.id] ?: 0) > System.currentTimeMillis()) return null
        try {
            if (e.thumb != null) {
                val bytes = withThumbReader(ctx, e.src) { r -> r.open(e.thumb, 0).use { it.readBytes() } }
                if (bytes.isNotEmpty()) { write(f, bytes); return BitmapFactory.decodeFile(f.path) }
            }
            val local = StreamCache.completeFile(ctx, e)
            if (local != null) return improve(ctx, e, local)
            if (e.src.streams) {
                val bytes = withThumbReader(ctx, e.src) { r -> r.serviceThumbnail(e.file) }
                if (bytes != null && bytes.isNotEmpty()) {
                    write(f, bytes)
                    weakMark(ctx, e).createNewFile()
                    return BitmapFactory.decodeFile(f.path)
                }
            }
        } catch (ex: CloudException) {
            if (ex.kind == CloudException.Kind.AUTH || ex.kind == CloudException.Kind.NETWORK)
                thumbBlockedUntil[e.src.id] = System.currentTimeMillis() + 60_000
        } catch (_: Exception) {}
        return null
    }

    /** A clip finished downloading: learn its length and, if it had no proper thumbnail, make one. */
    fun onDownloaded(ctx: Context, e: Entry, file: File) {
        val id = idFor(e.src.id, e.name)
        if (!e.durationKnown) ClipThumbs.durationOf(file)?.let { setDuration(ctx, id, it) }
        val f = thumbFile(ctx, e)
        if (!f.exists() || weakMark(ctx, e).exists()) improve(ctx, e, file)
    }

    private fun improve(ctx: Context, e: Entry, local: File): Bitmap? {
        val b = ClipThumbs.bestFromFile(local) ?: return null
        try {
            val f = thumbFile(ctx, e)
            val t = File(f.path + ".tmp")
            t.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            t.renameTo(f)
            weakMark(ctx, e).delete()
        } catch (_: Exception) {}
        return b
    }

    private fun write(f: File, b: ByteArray) {
        val t = File(f.path + ".tmp"); t.writeBytes(b); t.renameTo(f)
    }

    // ------------------------------------------------------------------ save to phone / forget

    class SaveResult(var saved: Int = 0, var skipped: Int = 0, var failed: Int = 0, var lastError: String? = null)

    /**
     * Downloads clips into the phone's own Clips. Saved copies are marked "never upload", so they are not
     * backed up again (or deleted by phone clean-up). Clips already on the phone (same name) are skipped.
     */
    fun saveToPhone(ctx: Context, clips: List<Clip>, progress: (done: Int, total: Int, bytesPct: Int) -> Unit, cancelled: () -> Boolean): SaveResult {
        val res = SaveResult()
        val existing = ClipStore.list(ctx).map { it.name }.toHashSet()
        clips.sortedBy { it.startMs }.forEachIndexed { i, c ->
            if (cancelled()) return res
            progress(i, clips.size, 0)
            val e = entries[c.id] ?: run { res.failed++; return@forEachIndexed }
            if (e.name in existing) { res.skipped++; return@forEachIndexed }
            try {
                val file = StreamCache.downloadBlocking(ctx, e, cancelled) { pct -> progress(i, clips.size, pct) }
                if (cancelled()) return res
                val newId = ClipStore.importCopy(ctx, file, e.name, c.startMs)
                thumbJpeg(ctx, c.id)?.let { ClipThumbs.adopt(ctx, newId, it) }
                existing += e.name
                res.saved++
            } catch (ex: Exception) {
                res.failed++
                res.lastError = ex.message
            }
        }
        progress(clips.size, clips.size, 100)
        return res
    }

    private val keeping = java.util.Collections.synchronizedSet(HashSet<String>())

    /**
     * Someone removed the cloud protection of clips that are in this phone's collections for the folder: keep a
     * copy on this phone (Remote Folders → options), so the collection still has them if the cloud copy goes.
     */
    fun keepOverridden(ctx: Context, sourceId: String, names: Set<String>) {
        if (!com.motioncam.Prefs(ctx).remoteKeepCopy) return
        val src = RemoteSources.get(ctx, sourceId) ?: return
        val onPhone = ClipStore.list(ctx).map { it.name }.toHashSet()
        val todo = cached(ctx, src).filter { it.name in names && it.name !in onPhone && keeping.add(sourceId + "/" + it.name) }
        if (todo.isEmpty()) return
        Thread {
            try { saveToPhone(ctx, todo, { _, _, _ -> }, { false }) } catch (_: Exception) {}
            finally { for (c in todo) keeping.remove(sourceId + "/" + c.name) }
        }.start()
    }

    class DeleteResult(var deleted: Int = 0, var protectedKept: Int = 0, var failed: Int = 0, var lastError: String? = null)

    /** Protected clips of a remote folder from the last sync (by hand, or in a shared collection). */
    fun protectedNames(ctx: Context, src: RemoteSource): Set<String> =
        com.motioncam.cloud.MarkSync.protectedNames(listOf(com.motioncam.cloud.MarkSync.remoteKey(src.id)))

    /**
     * "Delete from cloud" (confirmed by the user): deletes clips that are not protected, together with their
     * uploaded thumbnail, protection marks and shared-collection entries. Protection is re-checked right before.
     */
    fun deleteFromCloud(ctx: Context, src: RemoteSource, clips: List<Clip>, progress: (Int, Int) -> Unit): DeleteResult {
        val res = DeleteResult()
        val space = com.motioncam.cloud.MarkSync.remoteKey(src.id)
        val fresh = com.motioncam.cloud.MarkSync.syncNow(ctx, space)
        val prot = fresh.marks.values.filter { it.protected }.map { it.clip }.toSet()
        RemoteSources.reader(ctx, src).use { r ->
            val thumbFolder = try { r.listFolders(src.folderId).firstOrNull { it.name == THUMB_FOLDER } } catch (e: Exception) { null }
            val marks = r.markStore(src.folderId)
            val shared = r.collectionFiles(src.folderId)
            clips.forEachIndexed { i, c ->
                progress(i, clips.size)
                val e = entries[c.id] ?: run { res.failed++; return@forEachIndexed }
                if (e.name in prot) { res.protectedKept++; return@forEachIndexed }
                try {
                    r.delete(src.folderId, e.file)
                    if (e.thumb != null && thumbFolder != null) try { r.delete(thumbFolder.id, e.thumb) } catch (_: Exception) {}
                    try { marks.forget(e.name) } catch (_: Exception) {}
                    try { shared.forgetClip(e.name) } catch (_: Exception) {}
                    // this phone's copies of it
                    File(File(cacheDir(ctx, src.id), "clips"), e.name).delete()
                    thumbFile(ctx, e).delete()
                    entries.remove(c.id)
                    noteDeleted(ctx, src.id, e.name)
                    res.deleted++
                } catch (ex: Exception) { res.failed++; res.lastError = ex.message }
            }
        }
        progress(clips.size, clips.size)
        lists.remove(src.id)
        try { refresh(ctx, src) } catch (_: Exception) {}
        SharedSync.schedule(ctx, src.id, 0)
        com.motioncam.live.Live.nudge(ctx, src.kind, src.folderId, "clips")
        return res
    }

    /** Removes everything this device stored for a source (listing, cache, collections). */
    fun forget(ctx: Context, src: RemoteSource) {
        lists.remove(src.id)
        durations.remove(src.id)
        entries.entries.removeAll { it.value.src.id == src.id }
        synchronized(thumbReaders) { thumbReaders.remove(src.id)?.let { try { it.close() } catch (_: Exception) {} } }
        dataDir(ctx, src.id).deleteRecursively()
        cacheDir(ctx, src.id).deleteRecursively()
    }

    /** Drops the in-memory listing (e.g. after the source was edited). */
    fun invalidate(sourceId: String) { lists.remove(sourceId) }
}
