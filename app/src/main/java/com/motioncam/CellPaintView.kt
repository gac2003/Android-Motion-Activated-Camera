package com.motioncam

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/**
 * Painting an area on a camera's picture from another phone, cell by cell (the same 16 × 9 cells as the camera's own
 * ZONES and THUMB tools): drag over the picture to mark cells, drag starting on a marked cell to unmark. Marked
 * cells stay clear, the rest is dimmed; with nothing marked only the grid shows. [rotation] turns the picture as shown
 * into the camera's own orientation, [picture] says where the picture is inside this view.
 */
class CellPaintView(ctx: Context) : View(ctx) {
    var picture: () -> RectF? = { null }
    var rotation = 0
    /** Called when the finger lifts, with the area as the camera stores it ("" = nothing marked). */
    var onChange: (String) -> Unit = {}
    var touching = false; private set

    private var cells = BooleanArray(Zones.CELLS)
    private var paintValue = true
    private val d = ctx.resources.displayMetrics.density
    private val dim = Paint().apply { color = 0x99000000.toInt() }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x55FFFFFF; strokeWidth = 1f * d }

    /** Shows the camera's area (ignored while a finger is painting). */
    fun set(area: String) {
        if (touching) return
        cells = Zones.parseArea(area)
        invalidate()
    }

    fun value(): String = Zones.formatArea(cells)

    private fun pic(): RectF = picture() ?: RectF(0f, 0f, width.toFloat(), height.toFloat())
    /** The grid as shown: 16 × 9, or 9 × 16 when the picture is turned on its side. */
    private fun cols() = if (rotation % 180 == 0) Zones.CW else Zones.CH
    private fun rows() = if (rotation % 180 == 0) Zones.CH else Zones.CW

    /** The camera's cell under a point of the picture as shown (u, v in 0..1). */
    private fun cellAt(u: Float, v: Float): Int {
        val (xn, yn) = Zones.unrotate(u, v, rotation)
        return (yn * Zones.CH).toInt().coerceIn(0, Zones.CH - 1) * Zones.CW + (xn * Zones.CW).toInt().coerceIn(0, Zones.CW - 1)
    }

    override fun onDraw(c: Canvas) {
        val p = pic()
        if (p.width() <= 0f || p.height() <= 0f) return
        val nc = cols(); val nr = rows()
        val cw = p.width() / nc; val ch = p.height() / nr
        if (cells.any { it }) for (r in 0 until nr) for (k in 0 until nc) {
            if (!cells[cellAt((k + 0.5f) / nc, (r + 0.5f) / nr)]) c.drawRect(p.left + k * cw, p.top + r * ch, p.left + (k + 1) * cw, p.top + (r + 1) * ch, dim)
        }
        for (k in 0..nc) c.drawLine(p.left + k * cw, p.top, p.left + k * cw, p.bottom, grid)
        for (r in 0..nr) c.drawLine(p.left, p.top + r * ch, p.right, p.top + r * ch, grid)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val p = pic()
        if (p.width() <= 0f || p.height() <= 0f) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val u = (ev.x - p.left) / p.width(); val v = (ev.y - p.top) / p.height()
                // outside the picture: a touch starting there is for whatever lies under this view
                if (u < 0f || u >= 1f || v < 0f || v >= 1f) return touching
                val i = cellAt(u, v)
                if (ev.actionMasked == MotionEvent.ACTION_DOWN) { touching = true; paintValue = !cells[i] }
                if (cells[i] != paintValue) { cells[i] = paintValue; invalidate() }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (touching) { touching = false; onChange(value()) }
        }
        return true
    }
}
