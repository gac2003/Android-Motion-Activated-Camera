package com.motioncam

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.Size
import org.json.JSONObject
import java.io.File
import kotlin.math.abs

/**
 * Thumbnails that show the action, not the empty background.
 *  - New clips: the camera engine records when the largest moving area was in view ("peak motion") and the
 *    thumbnail is taken from exactly that moment.
 *  - Older clips: a few frames are sampled and the one that differs most from the opening frame (usually the
 *    one with the person in it) is used.
 * Results are cached as small JPEGs, so this work happens once per clip.
 */
object ClipThumbs {
    const val W = 384
    const val H = 216
    private var peaks: MutableMap<Long, Long>? = null

    private fun peaksFile(ctx: Context) = File(ctx.filesDir, "clip_peaks.json")
    private fun cacheFile(ctx: Context, id: Long) = File(File(ctx.cacheDir, "thumbs").apply { mkdirs() }, "$id.jpg")

    @Synchronized
    private fun peaks(ctx: Context): MutableMap<Long, Long> {
        peaks?.let { return it }
        val m = HashMap<Long, Long>()
        try {
            val f = peaksFile(ctx)
            if (f.exists()) {
                val o = JSONObject(f.readText())
                for (k in o.keys()) m[k.toLong()] = o.getLong(k)
            }
        } catch (_: Exception) {}
        peaks = m
        return m
    }

    @Synchronized
    private fun savePeaks(ctx: Context) {
        val o = JSONObject()
        for ((k, v) in peaks(ctx)) o.put(k.toString(), v)
        try { val t = File(peaksFile(ctx).path + ".tmp"); t.writeText(o.toString()); t.renameTo(peaksFile(ctx)) } catch (_: Exception) {}
    }

    /** Called by the camera engine when a clip is saved: [offsetMs] = time of peak motion within the clip. */
    fun setPeak(ctx: Context, clipId: Long, offsetMs: Long) {
        synchronized(this) { peaks(ctx)[clipId] = offsetMs.coerceAtLeast(0) }
        savePeaks(ctx)
        cacheFile(ctx, clipId).delete()
    }

    /** Clip deleted: drop its thumbnail and peak. */
    fun forget(ctx: Context, clipId: Long) {
        cacheFile(ctx, clipId).delete()
        val had = synchronized(this) { peaks(ctx).remove(clipId) != null }
        if (had) savePeaks(ctx)
    }

    /** Cached thumbnail if it exists (fast, for scrolling lists). */
    fun cached(ctx: Context, clip: Clip): Bitmap? {
        val f = cacheFile(ctx, clip.id)
        return if (f.exists()) BitmapFactory.decodeFile(f.path) else null
    }

    /** Best thumbnail for [clip] (blocking; call off the main thread). */
    fun load(ctx: Context, clip: Clip): Bitmap? {
        cached(ctx, clip)?.let { return it }
        var bmp: Bitmap? = null
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, clip.uri)
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: clip.durationMs
            val known = synchronized(this) { peaks(ctx)[clip.id] }
            val t = known ?: bestFrame(r, dur).also { synchronized(this) { peaks(ctx)[clip.id] = it }; savePeaks(ctx) }
            bmp = r.getScaledFrameAtTime(t.coerceIn(0, maxOf(0, dur - 1)) * 1000, MediaMetadataRetriever.OPTION_CLOSEST, W, H)
        } catch (_: Exception) {
        } finally {
            try { r.release() } catch (_: Exception) {}
        }
        if (bmp == null) bmp = try { ctx.contentResolver.loadThumbnail(clip.uri, Size(W, H), null) } catch (e: Exception) { null }
        bmp?.let { b ->
            try {
                val f = cacheFile(ctx, clip.id)
                val tmp = File(f.path + ".tmp")
                tmp.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                tmp.renameTo(f)
            } catch (_: Exception) {}
        }
        return bmp
    }

    /** Time (ms) of the sampled frame that differs most from the opening frame. */
    private fun bestFrame(r: MediaMetadataRetriever, dur: Long): Long {
        if (dur <= 0) return 0
        val base = small(r, 0) ?: return dur / 2
        var best = dur / 2
        var bestDiff = -1L
        for (i in 1..8) {
            val t = dur * i / 9
            val f = small(r, t) ?: continue
            var d = 0L
            for (k in base.indices) d += abs(base[k] - f[k])
            if (d > bestDiff) { bestDiff = d; best = t }
        }
        return best
    }

    /** Luma of a tiny version of the frame at [ms]. */
    private fun small(r: MediaMetadataRetriever, ms: Long): IntArray? {
        val b = r.getScaledFrameAtTime(ms * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 64, 64) ?: return null
        val sw = 32
        val sh = 18
        val s = Bitmap.createScaledBitmap(b, sw, sh, true)
        val px = IntArray(sw * sh)
        s.getPixels(px, 0, sw, 0, 0, sw, sh)
        return IntArray(px.size) { val c = px[it]; ((c shr 16 and 0xFF) * 3 + (c shr 8 and 0xFF) * 6 + (c and 0xFF)) / 10 }
    }
}
