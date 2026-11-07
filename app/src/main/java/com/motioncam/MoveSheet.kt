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
 * A clip already in the other folder (same name) is skipped, unless the user chooses to copy it under a new name.
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

    /**
     * Looks in the other folder first. Clips already there (same name) aren't copied again unless the user chooses to
     * copy them under a new name ("MC_…_2.mp4"); by default they're left out (and, for a Move, kept here).
     */
    private fun run(a: Activity, src: RemoteSource, target: RemoteSource, clips: List<Clip>, move: Boolean, done: () -> Unit) {
        val wait = AlertDialog.Builder(a).setMessage("Checking ${target.name}…").setCancelable(false).show()
        Thread {
            val there = try { RemoteSources.writer(a, target).use { w -> w.list(target.folderId).map { it.name }.toHashSet() } } catch (_: Exception) { null }
            a.runOnUiThread {
                try { wait.dismiss() } catch (_: Exception) {}
                if (a.isFinishing) return@runOnUiThread
                if (there == null) {
                    // can't look now: the queue still checks each clip before copying it, and leaves existing ones alone
                    enqueue(a, src, target, clips, move, emptySet(), done); return@runOnUiThread
                }
                val dup = clips.filter { it.name in there }
                if (dup.isEmpty()) { enqueue(a, src, target, clips, move, emptySet(), done); return@runOnUiThread }
                val rest = clips.filter { it.name !in there }
                val all = rest.isEmpty()
                val msg = (if (all) "All ${plural(clips.size)} are" else "${dup.size} of ${plural(clips.size)} ${if (dup.size == 1) "is" else "are"}") +
                    " already in ${target.name} (a clip with the same name)." +
                    "\n\nSkip: ${if (all) "nothing is" else "only the other ${plural(rest.size)} are"} ${if (move) "moved" else "copied"}" +
                    (if (move) "; the skipped ones stay in ${src.name}." else ".") +
                    "\n\nCopy with a new name: they're put there again as a separate clip (e.g. …_2.mp4), keeping the one that's there."
                AlertDialog.Builder(a).setTitle("Already in ${target.name}")
                    .setMessage(msg)
                    .setPositiveButton("Skip them") { _, _ ->
                        if (all) { android.widget.Toast.makeText(a, "Nothing to ${if (move) "move" else "copy"}: they're all in ${target.name} already", android.widget.Toast.LENGTH_LONG).show(); done() }
                        else enqueue(a, src, target, rest, move, emptySet(), done)
                    }
                    .setNeutralButton("Copy with a new name") { _, _ -> enqueue(a, src, target, clips, move, dup.map { it.name }.toSet(), done) }
                    .setNegativeButton("Cancel", null).show()
            }
        }.start()
    }

    /** Hands the clips to the transfer queue: it runs in the background, with a notification and Transfers. */
    private fun enqueue(a: Activity, src: RemoteSource, target: RemoteSource, clips: List<Clip>, move: Boolean, rename: Set<String>, done: () -> Unit) {
        val n = com.motioncam.remote.TransferQueue.enqueue(a, src, target, clips, move, rename)
        val what = (if (move) "Moving " else "Copying ") + plural(n) + " to ${target.name}"
        android.widget.Toast.makeText(a, if (n == 0) "Those clips are already on their way to ${target.name}"
            else "$what in the background · Remote folders → Transfers", android.widget.Toast.LENGTH_LONG).show()
        done()
    }
}
