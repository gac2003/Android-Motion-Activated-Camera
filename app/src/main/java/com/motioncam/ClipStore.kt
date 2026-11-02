package com.motioncam

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Clip(val id: Long, val uri: Uri, val name: String, val size: Long, val addedSec: Long, val durationMs: Long)

/** Clips are saved to the shared Movies/MotionCam folder (visible in Gallery and over USB file transfer). */
object ClipStore {
    private const val REL_PATH = "Movies/MotionCam"
    private val COLLECTION: Uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI

    class Pending(val uri: Uri, val pfd: ParcelFileDescriptor)

    fun create(ctx: Context): Pending {
        val name = "MC_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        val cv = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, REL_PATH)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = ctx.contentResolver.insert(COLLECTION, cv) ?: error("Cannot create clip")
        val pfd = ctx.contentResolver.openFileDescriptor(uri, "rw") ?: error("Cannot open clip")
        return Pending(uri, pfd)
    }

    fun finish(ctx: Context, p: Pending, keep: Boolean) {
        try { p.pfd.close() } catch (_: Exception) {}
        try {
            if (keep) {
                val cv = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                ctx.contentResolver.update(p.uri, cv, null, null)
            } else {
                ctx.contentResolver.delete(p.uri, null, null)
            }
        } catch (_: Exception) {}
    }

    fun list(ctx: Context): List<Clip> {
        val out = ArrayList<Clip>()
        val proj = arrayOf(
            MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_ADDED, MediaStore.Video.Media.DURATION,
        )
        try {
            ctx.contentResolver.query(
                COLLECTION, proj, "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?", arrayOf("$REL_PATH/%"),
                "${MediaStore.Video.Media.DATE_ADDED} DESC",
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    out += Clip(id, ContentUris.withAppendedId(COLLECTION, id), c.getString(1) ?: "?", c.getLong(2), c.getLong(3), c.getLong(4))
                }
            }
        } catch (_: Exception) {}
        return out
    }

    fun delete(ctx: Context, clip: Clip): Boolean =
        try { ctx.contentResolver.delete(clip.uri, null, null) > 0 } catch (_: Exception) { false }

    fun freeBytes(): Long = try {
        StatFs(Environment.getExternalStorageDirectory().path).availableBytes
    } catch (_: Exception) { Long.MAX_VALUE }

    /** Deletes by age, then oldest-first until under the size cap and with at least [minFree] bytes free. */
    fun cleanup(ctx: Context, prefs: Prefs, minFree: Long = 1_000_000_000L) {
        val clips = list(ctx).sortedBy { it.addedSec }.toMutableList()
        val cutoff = System.currentTimeMillis() / 1000 - prefs.keepDays * 86400L
        val iter = clips.iterator()
        while (iter.hasNext()) {
            val c = iter.next()
            if (c.addedSec < cutoff) { delete(ctx, c); iter.remove() }
        }
        val cap = prefs.maxStorageGb * 1_000_000_000L
        var total = clips.sumOf { it.size }
        while (clips.isNotEmpty() && (total > cap || freeBytes() < minFree)) {
            val c = clips.removeAt(0)
            if (delete(ctx, c)) total -= c.size
        }
    }
}
