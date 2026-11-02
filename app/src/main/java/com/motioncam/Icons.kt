package com.motioncam

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.widget.ImageView

/** Small vector icons drawn in code (24x24 design grid), always crisp and white. */
class IconDrawable(private val kind: Kind, private val color: Int = 0xFFFFFFFF.toInt()) : Drawable() {
    enum class Kind { PLAY, PAUSE, PREV, NEXT, VOLUME, MUTE, BACK, MORE, ZOOM_RESET }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; this.color = color }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; this.color = color; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()

    override fun draw(c: Canvas) {
        val b = bounds
        val s = minOf(b.width(), b.height()) / 24f
        c.save()
        c.translate(b.left + (b.width() - 24 * s) / 2, b.top + (b.height() - 24 * s) / 2)
        c.scale(s, s)
        stroke.strokeWidth = 2f
        path.reset()
        when (kind) {
            // triangle centroid sits on the centre, so it looks centred inside a circle
            Kind.PLAY -> { path.moveTo(8.5f, 5f); path.lineTo(19f, 12f); path.lineTo(8.5f, 19f); path.close(); c.drawPath(path, fill) }
            Kind.PAUSE -> {
                c.drawRoundRect(RectF(6.5f, 5f, 10.5f, 19f), 1f, 1f, fill)
                c.drawRoundRect(RectF(13.5f, 5f, 17.5f, 19f), 1f, 1f, fill)
            }
            Kind.PREV -> {
                c.drawRoundRect(RectF(5f, 6f, 7.2f, 18f), 1f, 1f, fill)
                path.moveTo(19f, 6f); path.lineTo(8.5f, 12f); path.lineTo(19f, 18f); path.close(); c.drawPath(path, fill)
            }
            Kind.NEXT -> {
                c.drawRoundRect(RectF(16.8f, 6f, 19f, 18f), 1f, 1f, fill)
                path.moveTo(5f, 6f); path.lineTo(15.5f, 12f); path.lineTo(5f, 18f); path.close(); c.drawPath(path, fill)
            }
            Kind.VOLUME, Kind.MUTE -> {
                path.moveTo(3.5f, 9f); path.lineTo(7.5f, 9f); path.lineTo(12.5f, 4.5f); path.lineTo(12.5f, 19.5f)
                path.lineTo(7.5f, 15f); path.lineTo(3.5f, 15f); path.close()
                c.drawPath(path, fill)
                if (kind == Kind.VOLUME) {
                    c.drawArc(RectF(10f, 8f, 18f, 16f), -50f, 100f, false, stroke)
                    c.drawArc(RectF(9f, 4.5f, 21.5f, 19.5f), -50f, 100f, false, stroke)
                } else {
                    c.drawLine(15.5f, 9f, 21f, 15f, stroke)
                    c.drawLine(21f, 9f, 15.5f, 15f, stroke)
                }
            }
            Kind.BACK -> {
                stroke.strokeWidth = 2.4f
                path.moveTo(15f, 4.5f); path.lineTo(7.5f, 12f); path.lineTo(15f, 19.5f); c.drawPath(path, stroke)
            }
            Kind.MORE -> for (y in listOf(5f, 12f, 19f)) c.drawCircle(12f, y, 2f, fill)
            Kind.ZOOM_RESET -> {
                // four corner brackets pointing outwards = "fit to screen"
                stroke.strokeWidth = 2.2f
                path.moveTo(4f, 9f); path.lineTo(4f, 4f); path.lineTo(9f, 4f)
                path.moveTo(15f, 4f); path.lineTo(20f, 4f); path.lineTo(20f, 9f)
                path.moveTo(20f, 15f); path.lineTo(20f, 20f); path.lineTo(15f, 20f)
                path.moveTo(9f, 20f); path.lineTo(4f, 20f); path.lineTo(4f, 15f)
                c.drawPath(path, stroke)
            }
        }
        c.restore()
    }

    override fun setAlpha(alpha: Int) { fill.alpha = alpha; stroke.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(cf: ColorFilter?) { fill.colorFilter = cf; stroke.colorFilter = cf; invalidateSelf() }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** An ImageView showing an [IconDrawable], with padding for a comfortable touch target. */
fun iconButton(ctx: Context, kind: IconDrawable.Kind, sizeDp: Int = 44, padDp: Int = 10, click: (() -> Unit)? = null): ImageView =
    ImageView(ctx).apply {
        setImageDrawable(IconDrawable(kind))
        val p = Ui.dp(ctx, padDp)
        setPadding(p, p, p, p)
        minimumWidth = Ui.dp(ctx, sizeDp)
        minimumHeight = Ui.dp(ctx, sizeDp)
        if (click != null) setOnClickListener { click() }
    }
