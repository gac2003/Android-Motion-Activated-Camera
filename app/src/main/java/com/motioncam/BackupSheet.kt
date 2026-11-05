package com.motioncam

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.motioncam.cloud.CloudConfig
import com.motioncam.cloud.CloudSync
import com.motioncam.cloud.Dest
import com.motioncam.remote.FolderDests
import com.motioncam.remote.RemoteSource

/**
 * "Back up to…": which backup folders these phone clips are in, as checkboxes. Tick a folder to back them up there
 * (it doesn't have to back up new clips by itself); untick one to take them out of it again, which deletes their cloud
 * copies there and is confirmed first. For a mixed selection a box shows "–" (some are there); tapping it goes to all,
 * then none, then back to as it was.
 */
object BackupSheet {
    private enum class State { ALL, SOME, NONE }

    /** One folder: an existing backup destination, or a remote folder this phone hasn't backed up to yet. */
    private class Row(val dest: Dest?, val source: RemoteSource?, val name: String, val sub: String,
                      val inCount: Int, val upCount: Int, val waiting: Int, val start: State) {
        var want = start
    }

    fun show(a: Activity, clips: List<Clip>, done: () -> Unit = {}) {
        val list = clips.filter { !ClipStore.isSavedCopy(a, it.id) }
        val skipped = clips.size - list.size
        if (list.isEmpty()) {
            if (skipped > 0) Toast.makeText(a, "Copies saved from a remote folder are never backed up", Toast.LENGTH_LONG).show()
            return
        }
        val ids = list.map { it.id }
        val n = ids.size
        Thread {
            val pres = CloudSync.presence(a, ids)
            val rows = ArrayList<Row>()
            for ((d, p) in pres) {
                val c = CloudConfig(a, d)
                val inN = p.first + p.second
                rows += Row(d, null, c.folderName ?: d.label,
                    d.label + (c.account?.let { " · $it" } ?: c.host?.let { " · $it" } ?: "") + if (c.enabled) " · backs up new clips" else "",
                    inN, p.first, p.second, when { inN >= n -> State.ALL; inN > 0 -> State.SOME; else -> State.NONE })
            }
            for (s in FolderDests.unused(a)) rows += Row(null, s, s.name, s.describe, 0, 0, 0, State.NONE)
            a.runOnUiThread { if (!a.isFinishing) open(a, list, rows, skipped, done) }
        }.start()
    }

    private fun d(a: Activity, v: Int) = Ui.dp(a, v)

    private fun open(a: Activity, clips: List<Clip>, rows: List<Row>, skipped: Int, done: () -> Unit) {
        val n = clips.size
        if (rows.isEmpty()) {
            AlertDialog.Builder(a).setTitle("No backup folders yet")
                .setMessage("Add a remote folder first (Google Drive, Dropbox or your own FTP/SFTP server); then you can back clips up to it.")
                .setPositiveButton("Add remote folder") { _, _ ->
                    a.startActivity(android.content.Intent(a, com.motioncam.remote.RemoteFoldersActivity::class.java)
                        .putExtra(com.motioncam.remote.RemoteFoldersActivity.EXTRA_BACKUP, true))
                }
                .setNegativeButton("Cancel", null).show()
            return
        }
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(d(a, 20), d(a, 4), d(a, 20), d(a, 8)) }
        box.addView(Ui.label(a, "Tick the folders " + (if (n == 1) "this clip" else "these clips") + " should be in.", 13f, 0xFFB0B4BA.toInt())
            .apply { setPadding(0, 0, 0, d(a, 8)) })
        lateinit var dialog: AlertDialog
        fun refreshButton() {
            val changes = rows.count { it.want != it.start }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.apply { isEnabled = changes > 0; alpha = if (changes > 0) 1f else 0.4f }
        }
        for (r in rows) box.addView(row(a, r, n) { refreshButton() })
        box.addView(Ui.label(a, "Folders marked \"backs up new clips\" do so by themselves; choosing a folder here doesn't change that." +
            if (skipped > 0) "\n\n$skipped selected clip${if (skipped == 1) " is a copy" else "s are copies"} saved from a remote folder (never backed up)." else "",
            12f, Ui.GREY).apply { setPadding(0, d(a, 10), 0, 0) })
        dialog = AlertDialog.Builder(a).setTitle("Back up " + if (n == 1) "1 clip" else "$n clips")
            .setView(ScrollView(a).apply { addView(box) })
            .setPositiveButton("Apply", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            refreshButton()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { apply(a, clips, rows, dialog, done) }
        }
        dialog.show()
    }

    private fun row(a: Activity, r: Row, n: Int, changed: () -> Unit): View {
        val v = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, d(a, 10), 0, d(a, 10))
            isClickable = true
            val tv = android.util.TypedValue()
            a.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
            if (tv.resourceId != 0) setBackgroundResource(tv.resourceId)
        }
        val check = Ui.label(a, "", 15f, 0xFF15171B.toInt()).apply { gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD) }
        v.addView(check, LinearLayout.LayoutParams(d(a, 24), d(a, 24)))
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(d(a, 14), 0, 0, 0) }
        col.addView(Ui.label(a, r.name, 15f).apply { setTypeface(typeface, Typeface.BOLD); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        col.addView(Ui.label(a, r.sub, 12f, Ui.GREY).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        val status = Ui.label(a, "", 12f)
        col.addView(status)
        v.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        fun paint() {
            check.text = when (r.want) { State.ALL -> "✓"; State.SOME -> "–"; State.NONE -> "" }
            check.background = GradientDrawable().apply {
                cornerRadius = d(a, 5).toFloat()
                if (r.want == State.NONE) { setColor(0); setStroke(d(a, 2), Ui.GREY) } else setColor(Ui.ACCENT)
            }
            val now = when {
                r.inCount >= n -> if (n == 1) "In this folder" else "All $n in this folder"
                r.inCount > 0 -> "${r.inCount} of $n in this folder"
                else -> "Not in this folder"
            } + if (r.waiting > 0) " · ${r.waiting} waiting to upload" else ""
            val (change, color) = when {
                r.want == r.start -> "" to Ui.GREY
                r.want == State.ALL -> ("  →  will back up " + (n - r.inCount)) to Ui.ACCENT
                r.want == State.NONE -> ("  →  will remove " + r.inCount + " from this folder") to 0xFFFF8A85.toInt()
                else -> "" to Ui.GREY
            }
            status.text = android.text.SpannableStringBuilder(now).apply {
                setSpan(android.text.style.ForegroundColorSpan(0xFFB0B4BA.toInt()), 0, length, 0)
                if (change.isNotEmpty()) { val s = length; append(change); setSpan(android.text.style.ForegroundColorSpan(color), s, length, 0) }
            }
            v.contentDescription = "${r.name}: $now" + if (change.isNotEmpty()) change.replace("→", ",") else ""
        }
        v.setOnClickListener {
            // all → none; none → all; a mixed folder also offers going back to how it was
            r.want = when (r.want) {
                State.ALL -> State.NONE
                State.NONE -> if (r.start == State.SOME) State.SOME else State.ALL
                State.SOME -> State.ALL
            }
            paint(); changed()
        }
        paint()
        return v
    }

    private fun apply(a: Activity, clips: List<Clip>, rows: List<Row>, dialog: AlertDialog, done: () -> Unit) {
        val adds = rows.filter { it.want == State.ALL && it.start != State.ALL }
        val removes = rows.filter { it.want == State.NONE && it.start != State.NONE }
        if (adds.isEmpty() && removes.isEmpty()) { dialog.dismiss(); return }
        val deleting = removes.filter { it.upCount > 0 }
        if (deleting.isNotEmpty()) {
            val names = deleting.joinToString { "\"${it.name}\"" }
            val count = deleting.sumOf { it.upCount }
            AlertDialog.Builder(a).setTitle("Remove from " + if (deleting.size == 1) deleting[0].name + "?" else "${deleting.size} folders?")
                .setMessage("This deletes the cloud copies of these clips in $names ($count file${if (count == 1) "" else "s"}), " +
                    "also for your other phones viewing ${if (deleting.size == 1) "that folder" else "those folders"} — even protected ones. " +
                    "The clips on this phone are kept.")
                .setPositiveButton("Remove") { _, _ -> run(a, clips, adds, removes, dialog, done) }
                .setNegativeButton("Cancel", null).show()
        } else run(a, clips, adds, removes, dialog, done)
    }

    private fun run(a: Activity, clips: List<Clip>, adds: List<Row>, removes: List<Row>, dialog: AlertDialog, done: () -> Unit) {
        dialog.dismiss()
        val ids = clips.map { it.id }
        val app = a.applicationContext
        Toast.makeText(a, if (removes.isNotEmpty()) "Updating backup folders…" else "Backing up…", Toast.LENGTH_SHORT).show()
        Thread {
            val dests = adds.mapNotNull { r -> r.dest ?: r.source?.let { FolderDests.destFor(app, it) } }
            val queued = if (dests.isNotEmpty()) CloudSync.enqueueTo(app, ids, dests) else 0
            var removed = 0
            var failed: String? = null
            for (r in removes) r.dest?.let { d -> try { removed += CloudSync.removeFrom(app, ids, d) } catch (e: Exception) { failed = r.name + ": " + (e.message ?: "failed") } }
            a.runOnUiThread {
                val parts = ArrayList<String>()
                if (adds.isNotEmpty()) parts += if (queued > 0) "$queued upload${if (queued == 1) "" else "s"} queued" else "Already there"
                if (removes.isNotEmpty()) parts += "removed $removed from the cloud"
                failed?.let { parts += "couldn't remove from $it" }
                Toast.makeText(a, parts.joinToString(" · ").replaceFirstChar { it.uppercase() }, Toast.LENGTH_LONG).show()
                done()
            }
        }.start()
    }
}
