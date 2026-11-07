package com.motioncam.remote

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import com.motioncam.Fmt
import com.motioncam.Prefs
import com.motioncam.Ui
import com.motioncam.topBar

/** Moves and copies between remote folders: progress, pause / resume, stop, retry (see [TransferQueue]). */
class TransfersActivity : Activity() {
    private lateinit var box: LinearLayout
    private val ui = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable { override fun run() { render(); ui.postDelayed(this, 1000) } }
    private val changed: () -> Unit = { ui.post { render() } }
    private fun d(v: Int) = Ui.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.BG) }
        val clear = Ui.chip(this, "Clear finished").apply { setOnClickListener { TransferQueue.clearFinished(this@TransfersActivity) } }
        root.addView(topBar(this, "Transfers", clear))
        box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(d(16), d(4), d(16), d(24)) }
        root.addView(ScrollView(this).apply { addView(box) }, LinearLayout.LayoutParams(-1, 0, 1f))
        Ui.capWidth(box)
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        TransferQueue.listeners += changed
        TransferQueue.start(this)   // resume anything left from before
        ui.post(ticker)
    }

    override fun onPause() {
        TransferQueue.listeners -= changed
        ui.removeCallbacks(ticker)
        super.onPause()
    }

    private fun render() {
        val vs = TransferQueue.views(this)
        box.removeAllViews()
        if (vs.isEmpty()) {
            box.addView(Ui.label(this, "Nothing is being moved or copied.\n\nIn a remote folder, select clips → More → Move or copy to…: " +
                "they move in the background and show here.", 14f, Ui.GREY).apply { setPadding(0, d(24), 0, 0); gravity = Gravity.CENTER })
            return
        }
        box.addView(Ui.label(this, "Clips in the same Google Drive or Dropbox account are copied by the service itself (nothing " +
            "downloads). Between different services each clip streams across, ${TransferQueue.WORKERS} at a time. A Move deletes the " +
            "originals when its clips are all across (protected ones are kept).", 12f, Ui.GREY).apply { setPadding(d(2), d(8), d(2), d(4)) })
        for (v in vs) box.addView(card(v), LinearLayout.LayoutParams(-1, -2).apply { topMargin = d(10) })
    }

    private fun card(v: TransferQueue.View): View = LinearLayout(this).apply {
        val b = v.b
        orientation = LinearLayout.VERTICAL
        setPadding(d(14), d(12), d(14), d(12))
        background = GradientDrawable().apply { setColor(0xFF1E2126.toInt()); cornerRadius = d(14).toFloat() }
        val finished = v.done + v.failed + v.skipped
        val verb = when {
            b.active -> if (b.move) "Moving" else "Copying"
            else -> if (b.move) "Moved" else "Copied"
        }
        addView(Ui.label(this@TransfersActivity, "$verb ${b.total} clip${if (b.total == 1) "" else "s"}", 15f).apply { setTypeface(typeface, Typeface.BOLD) })
        addView(Ui.label(this@TransfersActivity, "${v.srcName}  →  ${v.dstName}", 13f, 0xFFB0B4BA.toInt()).apply { setPadding(0, d(2), 0, d(6)) })
        if (b.active) addView(ProgressBar(this@TransfersActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = b.total * 100
            progress = finished * 100 + v.progress
            progressTintList = android.content.res.ColorStateList.valueOf(Ui.ACCENT)
        }, LinearLayout.LayoutParams(-1, d(10)))
        val state = when {
            !b.active -> "Finished " + Fmt.t(Prefs(this@TransfersActivity), b.finishedAt.coerceAtLeast(0), seconds = false)
            b.paused -> "Paused"
            v.running > 0 -> "In progress"
            else -> "Waiting"
        }
        val parts = listOfNotNull("$finished of ${b.total}", state,
            if (v.failed > 0) "${v.failed} failed" else null,
            if (v.skipped > 0) "${v.skipped} already there (left alone)" else null,
            if (b.kept > 0) "${b.kept} protected, also kept in ${v.srcName}" else null,
            b.note)
        addView(Ui.label(this@TransfersActivity, parts.joinToString("  ·  "), 12f, if (v.failed > 0) 0xFFFF8A85.toInt() else Ui.GREY).apply {
            setPadding(0, d(6), 0, 0)
        })
        b.items.firstOrNull { it.state == TransferQueue.FAIL }?.error?.let {
            addView(Ui.label(this@TransfersActivity, "Last error: $it", 12f, 0xFFFF8A85.toInt()).apply { setPadding(0, d(2), 0, 0) })
        }
        val row = LinearLayout(this@TransfersActivity).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, d(10), 0, 0) }
        fun btn(t: String, color: Int = 0xFFFFFFFF.toInt(), click: () -> Unit) = row.addView(Ui.chip(this@TransfersActivity, t).apply {
            setTextColor(color); setOnClickListener { click() }
        }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = d(8) })
        val ctx = this@TransfersActivity
        if (b.active) {
            btn(if (b.paused) "Resume" else "Pause") { TransferQueue.setPaused(ctx, b.id, !b.paused) }
            btn("Stop", 0xFFFF8A85.toInt()) {
                AlertDialog.Builder(ctx).setTitle("Stop this transfer?")
                    .setMessage("Clips not started yet stay where they are." + if (b.move) " Clips already copied are deleted from ${v.srcName} as for a finished move." else "")
                    .setPositiveButton("Stop") { _, _ -> TransferQueue.cancel(ctx, b.id) }.setNegativeButton("Keep going", null).show()
            }
        }
        if (v.failed > 0) btn("Retry failed", Ui.ACCENT) { TransferQueue.retry(ctx, b.id) }
        if (!b.active) btn("Remove") { TransferQueue.remove(ctx, b.id) }
        if (row.childCount > 0) addView(row)
    }
}
