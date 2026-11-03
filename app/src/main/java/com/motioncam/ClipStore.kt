package com.motioncam

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Clip(val id: Long, val uri: Uri, val name: String, val size: Long, val startMs: Long, val durationMs: Long) {
    val endMs get() = startMs + maxOf(durationMs, 1000L)
}

/** Clips live in the shared Movies/MotionCam folder (visible in Gallery and over USB file transfer). */
object ClipStore {
    private const val REL_PATH = "Movies/MotionCam"
    private val COLLECTION: Uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    private val nameFmt get() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    class Pending(val uri: Uri, val pfd: ParcelFileDescriptor)

    /** Creates a hidden (pending) file. It is renamed to its real start time in [finish]. */
    fun create(ctx: Context): Pending {
        val cv = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "MC_armed_${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, REL_PATH)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = ctx.contentResolver.insert(COLLECTION, cv) ?: error("Cannot create clip")
        val pfd = ctx.contentResolver.openFileDescriptor(uri, "rw") ?: error("Cannot open clip")
        return Pending(uri, pfd)
    }

    fun finish(ctx: Context, p: Pending, keep: Boolean, startMs: Long) {
        try { p.pfd.close() } catch (_: Exception) {}
        try {
            if (!keep) { ctx.contentResolver.delete(p.uri, null, null); return }
            val cv = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "MC_" + nameFmt.format(Date(startMs)) + ".mp4")
                put(MediaStore.Video.Media.DATE_TAKEN, startMs)
                put(MediaStore.Video.Media.IS_PENDING, 0)
            }
            try {
                ctx.contentResolver.update(p.uri, cv, null, null)
            } catch (e: Exception) {
                // name clash or rename not allowed: just publish it
                ctx.contentResolver.update(p.uri, ContentValues().apply {
                    put(MediaStore.Video.Media.DATE_TAKEN, startMs)
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                }, null, null)
            }
        } catch (_: Exception) {}
    }

    /** Removes half-written files left behind if the app was killed while recording/armed. */
    fun deleteOrphans(ctx: Context) {
        try {
            val args = Bundle().apply {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ? AND ${MediaStore.Video.Media.IS_PENDING}=1")
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf("$REL_PATH/%"))
                putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
            }
            val ids = ArrayList<Long>()
            ctx.contentResolver.query(COLLECTION, arrayOf(MediaStore.Video.Media._ID), args, null)?.use { c ->
                while (c.moveToNext()) ids += c.getLong(0)
            }
            for (id in ids) ctx.contentResolver.delete(ContentUris.withAppendedId(COLLECTION, id), null, null)
        } catch (_: Exception) {}
    }

    private fun parseStart(name: String, taken: Long, addedSec: Long): Long {
        if (name.startsWith("MC_") && name.length >= 18) {
            try { return nameFmt.parse(name.substring(3, 18))!!.time } catch (_: Exception) {}
        }
        return if (taken > 0) taken else addedSec * 1000
    }

    /** All finished clips, newest first. */
    fun list(ctx: Context): List<Clip> = query(ctx, null)

    /** One clip by id (cheap; used by the upload queue). */
    fun get(ctx: Context, id: Long): Clip? = query(ctx, id).firstOrNull()

    private fun query(ctx: Context, onlyId: Long?): List<Clip> {
        val out = ArrayList<Clip>()
        val proj = arrayOf(
            MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_ADDED, MediaStore.Video.Media.DURATION, MediaStore.Video.Media.DATE_TAKEN,
        )
        try {
            ctx.contentResolver.query(
                COLLECTION, proj,
                "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?" + if (onlyId != null) " AND ${MediaStore.Video.Media._ID}=?" else "",
                if (onlyId != null) arrayOf("$REL_PATH/%", onlyId.toString()) else arrayOf("$REL_PATH/%"), null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val name = c.getString(1) ?: "?"
                    out += Clip(id, ContentUris.withAppendedId(COLLECTION, id), name, c.getLong(2),
                        parseStart(name, c.getLong(5), c.getLong(3)), c.getLong(4))
                }
            }
        } catch (_: Exception) {}
        out.sortByDescending { it.startMs }
        return out
    }

    // ------------------------------------------------------------------ copies saved from remote folders

    private fun savedFile(ctx: Context) = java.io.File(ctx.filesDir, "saved_from_remote.json")
    private var saved: MutableSet<Long>? = null

    @Synchronized
    private fun savedIds(ctx: Context): MutableSet<Long> {
        saved?.let { return it }
        val s = HashSet<Long>()
        try {
            val f = savedFile(ctx)
            if (f.exists()) { val a = org.json.JSONArray(f.readText()); for (i in 0 until a.length()) s += a.getLong(i) }
        } catch (_: Exception) {}
        saved = s
        return s
    }

    /**
     * Clips saved from a remote folder: never uploaded to cloud backup (they are already in someone's cloud)
     * and never removed by the phone's automatic clean-up.
     */
    @Synchronized
    fun isSavedCopy(ctx: Context, id: Long) = id in savedIds(ctx)

    @Synchronized
    private fun markSaved(ctx: Context, id: Long) {
        savedIds(ctx) += id
        try {
            val f = savedFile(ctx)
            val t = java.io.File(f.path + ".tmp")
            t.writeText(org.json.JSONArray(savedIds(ctx).toList()).toString())
            t.renameTo(f)
        } catch (_: Exception) {}
    }

    /** Copies a downloaded remote clip into Movies/MotionCam (keeping its name and time). Returns the new id. */
    fun importCopy(ctx: Context, file: java.io.File, name: String, startMs: Long): Long {
        val cv = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, REL_PATH)
            put(MediaStore.Video.Media.DATE_TAKEN, startMs)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = ctx.contentResolver.insert(COLLECTION, cv) ?: error("Cannot create the copy")
        val id = ContentUris.parseId(uri)
        markSaved(ctx, id)   // before it becomes visible, so backup can never pick it up
        try {
            ctx.contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out, 256 * 1024) } }
                ?: error("Cannot write the copy")
            ctx.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            try { ctx.contentResolver.delete(uri, null, null) } catch (_: Exception) {}
            throw e
        }
        return id
    }

    fun delete(ctx: Context, clip: Clip): Boolean =
        (try { ctx.contentResolver.delete(clip.uri, null, null) > 0 } catch (_: Exception) { false })
            .also { if (it) ClipThumbs.forget(ctx, clip.id) }

    fun freeBytes(): Long = try {
        StatFs(Environment.getExternalStorageDirectory().path).availableBytes
    } catch (_: Exception) { Long.MAX_VALUE }

    /** Applies the optional age / size limits and (optionally) frees space when the phone is nearly full. */
    fun cleanup(ctx: Context, prefs: Prefs, minFree: Long = 1_000_000_000L) {
        // copies saved from remote folders are kept until the user deletes them
        val clips = list(ctx).filter { !isSavedCopy(ctx, it.id) }.sortedBy { it.startMs }.toMutableList()
        if (prefs.keepDays > 0) {
            val cutoff = System.currentTimeMillis() - prefs.keepDays * 86_400_000L
            val iter = clips.iterator()
            while (iter.hasNext()) {
                val c = iter.next()
                if (c.startMs < cutoff) { delete(ctx, c); iter.remove() }
            }
        }
        if (prefs.maxStorageGb > 0) {
            val cap = prefs.maxStorageGb * 1_000_000_000L
            var total = clips.sumOf { it.size }
            while (clips.isNotEmpty() && total > cap) {
                val c = clips.removeAt(0)
                if (delete(ctx, c)) total -= c.size
            }
        }
        if (prefs.autoDeleteLow) {
            while (clips.isNotEmpty() && freeBytes() < minFree) delete(ctx, clips.removeAt(0))
        }
    }
}
