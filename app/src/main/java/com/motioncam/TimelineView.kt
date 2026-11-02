package com.motioncam

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * Surveillance-style timeline: the playhead is fixed in the middle, the time axis moves.
 * Clips are drawn as yellow blocks. Drag to scrub (seeks on release), tap to jump.
 */
class TimelineView(ctx: Context) : View(ctx) {
    var clips: List<Clip> = emptyList(); set(v) { field = v; invalidate() }
    var playhead: Long = System.currentTimeMillis(); set(v) { if (!dragging) { field = v; invalidate() } }
    var windowMs: Long = 3_600_000L; set(v) { field = v; invalidate() }
    var onSeek: ((Long) -> Unit)? = null

    private var dragging = false
    private var downX = 0f
    private var downHead = 0L
    private val d = resources.displayMetrics.density
    private val bg = Paint().apply { color = 0xFF16181C.toInt() }
    private val seg = Paint().apply { color = Ui.ACCENT }
    private val tick = Paint().apply { color = 0xFF555A62.toInt(); strokeWidth = d }
    private val head = Paint().apply { color = Ui.RED; strokeWidth = 2 * d }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.LTGRAY; textSize = 10 * d }
    private val fmtHm = SimpleDateFormat("HH:mm", Locale.getDefault())

    private fun msPerPx() = windowMs.toFloat() / maxOf(1, width)
    private fun xOf(t: Long) = width / 2f + (t - playhead) / msPerPx()
    private fun tOf(x: Float) = playhead + ((x - width / 2f) * msPerPx()).toLong()

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        c.drawRect(0f, 0f, w, h, bg)
        val t0 = tOf(0f)
        val t1 = tOf(w)

        // ticks
        val step = when {
            windowMs <= 20 * 60_000L -> 60_000L
            windowMs <= 2 * 3_600_000L -> 10 * 60_000L
            windowMs <= 8 * 3_600_000L -> 3_600_000L
            else -> 3 * 3_600_000L
        }
        val cal = Calendar.getInstance().apply { timeInMillis = t0; set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0); set(Calendar.MINUTE, 0) }
        var t = cal.timeInMillis
        while (t < t1) {
            if (t >= t0) {
                val x = xOf(t)
                c.drawLine(x, h * 0.62f, x, h, tick)
                c.drawText(fmtHm.format(Date(t)), x + 3 * d, h - 4 * d, txt)
            }
            t += step
        }
        // clips
        val top = h * 0.18f
        val bot = h * 0.58f
        for (cl in clips) {
            if (cl.endMs < t0 || cl.startMs > t1) continue
            val x0 = xOf(cl.startMs)
            val x1 = maxOf(xOf(cl.endMs), x0 + 2 * d)
            c.drawRect(x0, top, x1, bot, seg)
        }
        // playhead
        c.drawLine(w / 2f, 0f, w / 2f, h, head)
        c.drawText(SimpleDateFormat("EEE d MMM  HH:mm:ss", Locale.getDefault()).format(Date(playhead)), 6 * d, 12 * d, txt)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { dragging = true; downX = e.x; downHead = playhead; parent?.requestDisallowInterceptTouchEvent(true) }
            MotionEvent.ACTION_MOVE -> {
                playheadInternal(downHead - ((e.x - downX) * msPerPx()).toLong())
            }
            MotionEvent.ACTION_UP -> {
                dragging = false
                val target = if (abs(e.x - downX) < 8 * d) {
                    playheadInternal(downHead); tOf(e.x)  // tap: jump to the tapped time
                } else playhead
                onSeek?.invoke(target)
            }
            MotionEvent.ACTION_CANCEL -> { dragging = false; playheadInternal(downHead) }
        }
        return true
    }

    private fun playheadInternal(v: Long) {
        val wasDragging = dragging
        dragging = false
        playhead = v
        dragging = wasDragging
    }
}
