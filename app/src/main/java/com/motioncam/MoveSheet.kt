package com.motioncam

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import com.motioncam.cloud.THUMB_FOLDER
import com.motioncam.cloud.UploadSource
import com.motioncam.cloud.thumbName
import com.motioncam.remote.RemoteRepo
import com.motioncam.remote.RemoteSource
import com.motioncam.remote.RemoteSources
import com.motioncam.remote.StreamCache
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * "Move or copy to…": puts clips of a remote folder into another remote folder, also on another service (Drive →
 * your own server, Dropbox → Drive…). Each clip is downloaded to this phone, uploaded there with its thumbnail and
 * tags, and for Move then deleted from the first folder (protected clips are copied but stay; see [RemoteRepo.deleteFromCloud]).
 * A clip already in the other folder (same name and size) isn't uploaded again.
 */
object MoveSheet {
    private fun plural(n: Int) = "$n clip${if (n == 1) "" else "s"}"

    fun show(a: Activity, src: RemoteSource, clips: List<Clip>, done: () -> Unit = {}) {
        if (clips.isEmpty()) return
        val targets = RemoteSources.all(a).filter { it.id != src.id && it.mode != RemoteSources.MODE_LINK }
        if (targets.isEmpty()) {
            AlertDialog.Builder(a).setTitle("No other remote folder")
                .setMessage("Add another remote folder first (Google Drive, Dropbox or your own FTP/SFTP server); then clips can be moved or copied there.")
                .setPositiveButton("Add remote folder") { _, _ -> a.startActivity(android.content.Intent(a, com.motioncam.remote.RemoteFoldersActivity::class.java)) }
                .setNegativeButton("Cancel", null).show()
            return
        }
        val canMove = CloudDelete.canDelete(src)
        fun d(v: Int) = Ui.dp(a, v)
        var chosen: RemoteSource? = null
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(d(20), d(4), d(20), d(8)) }
        box.addView(Ui.label(a, "To which folder?", 13f, 0xFFB0B4BA.toInt()).apply { setPadding(0, 0, 0, d(6)) })
        lateinit var dialog: AlertDialog
        val marks = ArrayList<android.widget.TextView>()
        fun buttons() {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.apply { isEnabled = chosen != null && canMove; alpha = if (isEnabled) 1f else 0.4f }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.apply { isEnabled = chosen != null; alpha = if (isEnabled) 1f else 0.4f }
        }
        for (t in targets) {
            val row = LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, d(10), 0, d(10)); isClickable = true
                val tv = android.util.TypedValue()
                a.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                if (tv.resourceId != 0) setBackgroundResource(tv.resourceId)
            }
            val dot = Ui.label(a, "", 12f)
            row.addView(dot, LinearLayout.LayoutParams(d(22), d(22)))
            marks += dot
            val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(d(14), 0, 0, 0) }
            col.addView(Ui.label(a, t.name, 15f).apply { setTypeface(typeface, Typeface.BOLD); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            col.addView(Ui.label(a, t.describe, 12f, Ui.GREY).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
            row.contentDescription = t.name + ", " + t.describe
            row.setOnClickListener {
                chosen = t
                targets.forEachIndexed { i, x -> paintRadio(a, marks[i], x === t) }
                buttons()
            }
            paintRadio(a, dot, false)
            box.addView(row)
        }
        box.addView(Ui.label(a, (if (canMove) "Move: copies them there, then deletes them here (for every phone; protected clips stay here too). " else
            "This folder is a viewer link, so its clips can only be copied. ") +
            "Copy: puts a copy there and keeps these. Tags go along; collections and protection stay with this folder.", 12f, Ui.GREY)
            .apply { setPadding(0, d(10), 0, 0) })
        dialog = AlertDialog.Builder(a).setTitle("Move or copy ${plural(clips.size)}")
            .setView(ScrollView(a).apply { addView(box) })
            .setPositiveButton("Move", null)
            .setNeutralButton("Copy", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            buttons()
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { chosen?.let { t -> dialog.dismiss(); run(a, src, t, clips, false, done) } }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val t = chosen ?: return@setOnClickListener
                val prot = RemoteRepo.protectedNames(a, src)
                val keep = clips.count { it.name in prot }
                AlertDialog.Builder(a).setTitle("Move ${plural(clips.size)} to ${t.name}?")
                    .setMessage("They're copied to ${t.name}, then deleted from ${src.name}, also for your other phones viewing it." +
                        if (keep > 0) "\n\n$keep protected clip${if (keep == 1) " is" else "s are"} copied but also kept in ${src.name}." else "")
                    .setPositiveButton("Move") { _, _ -> dialog.dismiss(); run(a, src, t, clips, true, done) }
                    .setNegativeButton("Cancel", null).show()
            }
        }
        dialog.show()
    }

    private fun paintRadio(a: Activity, tv: android.widget.TextView, on: Boolean) {
        tv.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setStroke(Ui.dp(a, 2), if (on) Ui.ACCENT else Ui.GREY)
            if (on) setColor(0xFF3A3320.toInt())
        }
        tv.text = if (on) "●" else ""
        tv.setTextColor(Ui.ACCENT)
        tv.gravity = Gravity.CENTER
    }

    private class Result(var copied: Int = 0, var already: Int = 0, var clash: Int = 0, var failed: Int = 0, var lastError: String? = null,
                         var deleted: Int = 0, var kept: Int = 0, val ok: ArrayList<Clip> = ArrayList())

    private fun fileSource(f: File) = object : UploadSource {
        override val size = f.length()
        override fun open(offset: Long): InputStream = FileInputStream(f).also { it.channel.position(offset) }
    }

    private fun run(a: Activity, src: RemoteSource, target: RemoteSource, clips: List<Clip>, move: Boolean, done: () -> Unit) {
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        val tv = Ui.label(a, "Starting…", 14f).apply { val p = Ui.dp(a, 20); setPadding(p, p, p, 0) }
        val dlg = AlertDialog.Builder(a).setTitle((if (move) "Moving " else "Copying ") + plural(clips.size) + " to ${target.name}")
            .setView(tv).setCancelable(false)
            .setNegativeButton("Stop") { _, _ -> cancelled.set(true) }.show()
        fun status(s: String) = a.runOnUiThread { tv.text = s }
        val app = a.applicationContext
        Thread {
            val r = Result()
            try {
                RemoteSources.writer(app, target).use { w ->
                    val existing = w.list(target.folderId).associateBy { it.name }
                    var thumbs: String? = null
                    var thumbNames: Set<String>? = null
                    val list = clips.sortedBy { it.startMs }
                    list.forEachIndexed { i, c ->
                        if (cancelled.get()) return@forEachIndexed
                        val head = "Clip ${i + 1} of ${list.size}"
                        val e = RemoteRepo.entry(c.id) ?: run { r.failed++; r.lastError = "not in the folder's list any more"; return@forEachIndexed }
                        val there = existing[e.name]
                        try {
                            when {
                                there != null && there.size == e.file.size -> r.already++
                                there != null -> { r.clash++; return@forEachIndexed }
                                else -> {
                                    val f = StreamCache.downloadBlocking(app, e, { cancelled.get() }) { pct -> status("$head  ·  downloading $pct%") }
                                    if (cancelled.get()) return@forEachIndexed
                                    val total = f.length().coerceAtLeast(1)
                                    w.upload(target.folderId, e.name, fileSource(f), null, {}) { sent ->
                                        if (cancelled.get()) throw java.io.IOException("stopped")
                                        status("$head  ·  uploading ${(sent * 100 / total).toInt()}%")
                                    }
                                    r.copied++
                                }
                            }
                            r.ok += c
                            // its thumbnail, so the other folder shows the right picture without downloading the clip
                            RemoteRepo.thumbJpeg(app, c.id)?.let { jpg ->
                                try {
                                    if (thumbs == null) {
                                        val tf = w.listFolders(target.folderId).firstOrNull { it.name == THUMB_FOLDER } ?: w.createFolder(target.folderId, THUMB_FOLDER)
                                        thumbs = tf.id
                                        thumbNames = w.list(tf.id).map { it.name }.toHashSet()
                                    }
                                    val tn = thumbName(e.name, e.durationMs)
                                    if (thumbNames?.contains(tn) != true) w.upload(thumbs!!, tn, fileSource(jpg), null, {}, {})
                                } catch (_: Exception) {}
                            }
                        } catch (ex: Exception) {
                            if (cancelled.get()) { try { w.discardPartial(target.folderId, e.name) } catch (_: Exception) {} }
                            else { r.failed++; r.lastError = ex.message ?: ex.javaClass.simpleName }
                        }
                    }
                    status("Copying tags…")
                    try { TagStore.writeTo(app, w, target.folderId, TagStore.byName(app, src.id, r.ok)) } catch (_: Exception) {}
                }
            } catch (ex: Exception) {
                r.failed += clips.size - r.ok.size - r.clash - r.failed
                r.lastError = ex.message ?: ex.javaClass.simpleName
            }
            // the other folder: show the new clips, and tell its other viewers
            try { RemoteRepo.refresh(app, target) } catch (_: Exception) {}
            com.motioncam.live.Live.nudge(app, target.kind, target.folderId, "clips")
            TagStore.syncAsync(app, target)
            if (move && r.ok.isNotEmpty() && !cancelled.get()) {
                status("Deleting from ${src.name}…")
                try {
                    val d = RemoteRepo.deleteFromCloud(app, src, r.ok) { _, _ -> }
                    r.deleted = d.deleted; r.kept = d.protectedKept
                    if (d.failed > 0) { r.lastError = "couldn't delete ${d.failed} from ${src.name}: ${d.lastError}" }
                } catch (ex: Exception) { r.lastError = "couldn't delete from ${src.name}: ${ex.message}" }
            }
            a.runOnUiThread {
                try { dlg.dismiss() } catch (_: Exception) {}
                val parts = ArrayList<String>()
                val got = r.copied + r.already
                parts += (if (move) "Moved " + plural(r.deleted) else "Copied " + plural(got)) + " to ${target.name}."
                if (r.already > 0) parts += "${plural(r.already)} were already there (not uploaded again)."
                if (move && r.kept > 0) parts += "${plural(r.kept)} protected: copied, and also kept in ${src.name}."
                if (r.clash > 0) parts += "${plural(r.clash)} skipped: a different clip with the same name is already there."
                if (r.failed > 0) parts += "${r.failed} failed" + (r.lastError?.let { ": $it" } ?: ".")
                else r.lastError?.let { parts += it.replaceFirstChar { ch -> ch.uppercase() } + "." }
                if (cancelled.get()) parts += "Stopped" + if (move) "; nothing was deleted from ${src.name}." else "."
                if (!a.isFinishing) AlertDialog.Builder(a).setTitle(if (move) "Move" else "Copy").setMessage(parts.joinToString("\n\n"))
                    .setPositiveButton("OK", null).show()
                done()
            }
        }.start()
    }
}
