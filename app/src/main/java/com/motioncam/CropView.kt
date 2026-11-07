package com.motioncam

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The CROP frame over the picture, like a photo editor's crop tool: outside the area is dimmed, a rule-of-thirds
 * grid and corner handles show while editing. Drag inside to move it, drag a corner or an edge to resize it (keeping
 * the chosen shape). Coordinates are 0..1 of the picture as shown; [rotation] turns them into the camera's.
 *
 * Not editing, it only outlines what is recorded (and ignores touches).
 */
class CropView(ctx: Context) : View(ctx) {
    /** Where the picture is drawn inside this view (null = the whole view). */
    var picture: () -> RectF? = { null }
    /** The picture's rotation on screen (clockwise degrees from the sensor). */
    var rotation = 0
    /** Width / height on screen; 0 = free. */
    var shape = 0f
        set(v) { field = v; if (v > 0f) { r.set(shaped(r, v)); invalidate() } }
    var editing = false
        set(v) { field = v; isClickable = v; invalidate() }
    /** Called while dragging and once more (final = true) when the finger lifts. */
    var onChange: (rect: RectF?, final: Boolean) -> Unit = { _, _ -> }
    /** A finger is holding the frame (true) or let go (false): the bars fade so the picture under them shows. */
    var onHold: (Boolean) -> Unit = {}

    /** The area on screen, 0..1; the whole picture = no crop. */
    private val r = RectF(0f, 0f, 1f, 1f)
    private val d = ctx.resources.displayMetrics.density
    private val dim = Paint().apply { color = 0x99000000.toInt() }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE; strokeWidth = 1.5f * d }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x66FFFFFF; strokeWidth = 1f * d }
    private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Ui.ACCENT; strokeWidth = 4f * d; strokeCap = Paint.Cap.ROUND }

    /** Shows [sensor] (camera coordinates; null = whole picture). */
    fun set(sensor: RectF?) {
        if (dragging != 0) return
        r.set(if (sensor == null) RectF(0f, 0f, 1f, 1f) else Crop.toDisplay(sensor, rotation))
        invalidate()
    }

    /** The area in camera coordinates, null = the whole picture. */
    fun sensorRect(): RectF? = if (Crop.isWhole(r)) null else Crop.toSensor(r, rotation)

    fun reset() { r.set(0f, 0f, 1f, 1f); invalidate(); onChange(null, true) }

    private fun pic(): RectF = picture() ?: RectF(0f, 0f, width.toFloat(), height.toFloat())

    /** Screen aspect of the picture, for shapes in screen pixels. */
    private fun picAspect(): Float = pic().let { if (it.height() > 0) it.width() / it.height() else 16f / 9f }

    /** [x] resized to [aspect] (screen) around its centre, fitting the picture. */
    private fun shaped(x: RectF, aspect: Float): RectF {
        val k = aspect / picAspect()   // width/height in 0..1 units
        var w = x.width(); var h = w / k
        if (h > x.height()) { h = x.height(); w = h * k }
        if (w > 1f) { w = 1f; h = w / k }
        if (h > 1f) { h = 1f; w = h * k }
        val cx = x.centerX().coerceIn(w / 2, 1f - w / 2); val cy = x.centerY().coerceIn(h / 2, 1f - h / 2)
        return RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
    }

    override fun onDraw(c: Canvas) {
        val p = pic()
        val whole = Crop.isWhole(r)
        if (whole && !editing) return
        val x0 = p.left + r.left * p.width(); val x1 = p.left + r.right * p.width()
        val y0 = p.top + r.top * p.height(); val y1 = p.top + r.bottom * p.height()
        if (!whole) {
            dim.alpha = if (editing) 0x99 else 0x66
            c.drawRect(p.left, p.top, p.right, y0, dim)
            c.drawRect(p.left, y1, p.right, p.bottom, dim)
            c.drawRect(p.left, y0, x0, y1, dim)
            c.drawRect(x1, y0, p.right, y1, dim)
        }
        c.drawRect(x0, y0, x1, y1, line)
        if (!editing) return
        for (i in 1..2) {
            val gx = x0 + (x1 - x0) * i / 3; val gy = y0 + (y1 - y0) * i / 3
            c.drawLine(gx, y0, gx, y1, grid); c.drawLine(x0, gy, x1, gy, grid)
        }
        val L = min(22f * d, min(x1 - x0, y1 - y0) / 3)
        val path = Path()
        for ((cx, cy) in listOf(x0 to y0, x1 to y0, x0 to y1, x1 to y1)) {
            val sx = if (cx == x0) 1 else -1; val sy = if (cy == y0) 1 else -1
            path.moveTo(cx, cy + sy * L); path.lineTo(cx, cy); path.lineTo(cx + sx * L, cy)
        }
        // edge handles
        val mx = (x0 + x1) / 2; val my = (y0 + y1) / 2; val e = min(12f * d, L / 2)
        path.moveTo(mx - e, y0); path.lineTo(mx + e, y0); path.moveTo(mx - e, y1); path.lineTo(mx + e, y1)
        path.moveTo(x0, my - e); path.lineTo(x0, my + e); path.moveTo(x1, my - e); path.lineTo(x1, my + e)
        c.drawPath(path, handle)
    }

    // what a drag holds: bit 1 left, 2 top, 4 right, 8 bottom, 16 the whole rect
    private var dragging = 0
    private var lastX = 0f; private var lastY = 0f

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!editing) return false
        val p = pic()
        if (p.width() <= 0 || p.height() <= 0) return true
        val u = (ev.x - p.left) / p.width(); val v = (ev.y - p.top) / p.height()
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val tu = 28f * d / p.width(); val tv = 28f * d / p.height()
                var g = 0
                if (v > r.top - tv && v < r.bottom + tv) {
                    if (abs(u - r.left) < tu) g = g or 1 else if (abs(u - r.right) < tu) g = g or 4
                }
                if (u > r.left - tu && u < r.right + tu) {
                    if (abs(v - r.top) < tv) g = g or 2 else if (abs(v - r.bottom) < tv) g = g or 8
                }
                if (g == 0 && r.contains(u, v)) g = 16
                dragging = g
                lastX = u; lastY = v
                if (g != 0) { parent?.requestDisallowInterceptTouchEvent(true); onHold(true) }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging == 0) return true
                val du = u - lastX; val dv = v - lastY
                lastX = u; lastY = v
                drag(du, dv)
                invalidate()
                onChange(sensorRect(), false)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging != 0) { dragging = 0; onHold(false); onChange(sensorRect(), true) }
                else if (ev.actionMasked == MotionEvent.ACTION_UP) performClick()
            }
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    companion object {
        /** Fades [v] almost away while the frame is held, back when let go. */
        fun fade(v: View, holding: Boolean) {
            v.animate().cancel()
            v.animate().alpha(if (holding) 0.08f else 1f).setDuration(if (holding) 120 else 200).start()
        }

        /** The bar shown while editing a crop: Cancel · Turn off crop · Done, small and see-through over the picture. */
        fun bar(ctx: Context, cancel: () -> Unit, off: () -> Unit, done: () -> Unit): android.widget.LinearLayout {
            val d = { v: Int -> Ui.dp(ctx, v) }
            fun btn(text: String, fill: Int, fg: Int, act: () -> Unit) = Ui.label(ctx, text, 13f, fg).apply {
                gravity = android.view.Gravity.CENTER
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(d(16), d(7), d(16), d(7))
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = d(18).toFloat(); setColor(fill); setStroke(d(1), 0x40FFFFFF)
                }
                isClickable = true
                setOnClickListener { act() }
            }
            return android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER
                setPadding(d(12), d(4), d(12), d(10))
                visibility = GONE
                fun lp(l: Int) = android.widget.LinearLayout.LayoutParams(-2, -2).apply { leftMargin = l }
                addView(btn("Cancel", 0x8C202328.toInt(), Color.WHITE, cancel), lp(0))
                addView(btn("Turn off crop", 0x8C202328.toInt(), 0xFFFF8A80.toInt(), off), lp(d(10)))
                addView(btn("Done", (Ui.ACCENT and 0x00FFFFFF) or 0xD9000000.toInt(), Color.BLACK, done), lp(d(10)))
            }
        }
    }

    private fun drag(du: Float, dv: Float) {
        val g = dragging
        if (g == 16) {
            val w = r.width(); val h = r.height()
            val l = (r.left + du).coerceIn(0f, 1f - w); val t = (r.top + dv).coerceIn(0f, 1f - h)
            r.set(l, t, l + w, t + h); return
        }
        val n = RectF(r)
        if (g and 1 != 0) n.left = (n.left + du).coerceIn(0f, n.right - Crop.MIN)
        if (g and 4 != 0) n.right = (n.right + du).coerceIn(n.left + Crop.MIN, 1f)
        if (g and 2 != 0) n.top = (n.top + dv).coerceIn(0f, n.bottom - Crop.MIN)
        if (g and 8 != 0) n.bottom = (n.bottom + dv).coerceIn(n.top + Crop.MIN, 1f)
        if (shape > 0f) {
            // keep the shape: the side being dragged leads, the other follows from the fixed corner / edge
            val k = shape / picAspect()
            val horiz = g and 5 != 0; val vert = g and 10 != 0
            var w = n.width(); var h = n.height()
            if (horiz && (!vert || abs(du) >= abs(dv))) h = w / k else w = h * k
            // fit inside the picture from the anchored side
            val maxW = if (g and 1 != 0) n.right else 1f - n.left
            val maxH = if (g and 2 != 0) n.bottom else 1f - n.top
            if (w > maxW) { w = maxW; h = w / k }
            if (h > maxH) { h = maxH; w = h * k }
            if (w < Crop.MIN || h < Crop.MIN) return
            if (g and 1 != 0) n.left = n.right - w else if (g and 4 != 0) n.right = n.left + w
            else { val cx = r.centerX(); n.left = (cx - w / 2).coerceIn(0f, 1f - w); n.right = n.left + w }
            if (g and 2 != 0) n.top = n.bottom - h else if (g and 8 != 0) n.bottom = n.top + h
            else { val cy = r.centerY(); n.top = (cy - h / 2).coerceIn(0f, 1f - h); n.bottom = n.top + h }
        }
        r.set(max(0f, n.left), max(0f, n.top), min(1f, n.right), min(1f, n.bottom))
    }
}
