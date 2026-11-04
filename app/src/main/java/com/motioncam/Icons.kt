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
class IconDrawable(private val kind: Kind, private val tint: Int = 0xFFFFFFFF.toInt()) : Drawable() {
    enum class Kind { PLAY, PAUSE, PREV, NEXT, VOLUME, MUTE, BACK, MORE, ZOOM_RESET, CALENDAR, FOLDER, CLOUD, REFRESH, DOWNLOAD, SETTINGS, CLIPS, VIDEOCAM, NETWORK, LIVE }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = tint }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = tint; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
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
            Kind.FOLDER -> {
                stroke.strokeWidth = 2f
                path.moveTo(3.5f, 18.5f); path.lineTo(3.5f, 6f); path.lineTo(9.5f, 6f); path.lineTo(11.5f, 8.5f)
                path.lineTo(20.5f, 8.5f); path.lineTo(20.5f, 18.5f); path.close()
                c.drawPath(path, stroke)
            }
            Kind.CLOUD -> {
                stroke.strokeWidth = 2f
                path.moveTo(7f, 18.5f)
                path.cubicTo(4.2f, 18.5f, 2.5f, 16.6f, 2.5f, 14.3f)
                path.cubicTo(2.5f, 12f, 4.3f, 10.2f, 6.6f, 10.2f)
                path.cubicTo(7.3f, 7.2f, 9.6f, 5.5f, 12.3f, 5.5f)
                path.cubicTo(15.6f, 5.5f, 18.2f, 8f, 18.4f, 11.2f)
                path.cubicTo(20.2f, 11.5f, 21.5f, 13f, 21.5f, 14.9f)
                path.cubicTo(21.5f, 16.9f, 19.9f, 18.5f, 17.9f, 18.5f)
                path.close()
                c.drawPath(path, stroke)
            }
            Kind.SETTINGS -> {
                // gear: 8 teeth, outer ring, hub
                stroke.strokeWidth = 3f
                val cap = stroke.strokeCap
                stroke.strokeCap = Paint.Cap.BUTT
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    c.drawLine(12f + 6f * Math.cos(a).toFloat(), 12f + 6f * Math.sin(a).toFloat(),
                        12f + 9f * Math.cos(a).toFloat(), 12f + 9f * Math.sin(a).toFloat(), stroke)
                }
                stroke.strokeCap = cap
                stroke.strokeWidth = 2f
                c.drawCircle(12f, 12f, 6.4f, stroke)
                c.drawCircle(12f, 12f, 2.4f, stroke)
            }
            Kind.CLIPS -> {
                stroke.strokeWidth = 2f
                c.drawRoundRect(RectF(3.5f, 5.5f, 20.5f, 18.5f), 2.5f, 2.5f, stroke)
                path.moveTo(10f, 9f); path.lineTo(15.5f, 12f); path.lineTo(10f, 15f); path.close()
                c.drawPath(path, fill)
            }
            Kind.LIVE -> {
                // live connection: a cloud with a camera lens in it, and a wi-fi signal rising from its top
                val sc = 0.82f
                c.save(); c.translate(2.16f, 6.33f); c.scale(sc, sc)
                stroke.strokeWidth = 2f / sc
                path.moveTo(7f, 18.5f)
                path.cubicTo(4.2f, 18.5f, 2.5f, 16.6f, 2.5f, 14.3f)
                path.cubicTo(2.5f, 12f, 4.3f, 10.2f, 6.6f, 10.2f)
                path.cubicTo(7.3f, 7.2f, 9.6f, 5.5f, 12.3f, 5.5f)
                path.cubicTo(15.6f, 5.5f, 18.2f, 8f, 18.4f, 11.2f)
                path.cubicTo(20.2f, 11.5f, 21.5f, 13f, 21.5f, 14.9f)
                path.cubicTo(21.5f, 16.9f, 19.9f, 18.5f, 17.9f, 18.5f)
                path.close()
                c.drawPath(path, stroke)
                c.restore()
                stroke.strokeWidth = 1.7f
                c.drawCircle(12f, 17.3f, 2.9f, stroke)                 // lens
                c.drawCircle(12f, 17.3f, 1f, fill)
                stroke.strokeWidth = 1.6f
                for (r in floatArrayOf(2.2f, 4.6f, 7f)) c.drawArc(RectF(12f - r, 9f - r, 12f + r, 9f + r), 225f, 90f, false, stroke)
            }
            Kind.NETWORK -> {
                // three linked devices (filled nodes, connecting lines): "your phones connected"
                stroke.strokeWidth = 1.8f
                val n = arrayOf(floatArrayOf(12f, 5f), floatArrayOf(4.5f, 18.5f), floatArrayOf(19.5f, 18.5f))
                for (i in 0 until 3) for (j in i + 1 until 3) c.drawLine(n[i][0], n[i][1], n[j][0], n[j][1], stroke)
                for (p in n) c.drawCircle(p[0], p[1], 2.8f, fill)
            }
            Kind.VIDEOCAM -> {
                stroke.strokeWidth = 2f
                c.drawRoundRect(RectF(2.5f, 7f, 15f, 17f), 2f, 2f, stroke)
                path.moveTo(16f, 10.5f); path.lineTo(21.5f, 7.5f); path.lineTo(21.5f, 16.5f); path.lineTo(16f, 13.5f); path.close()
                c.drawPath(path, fill)
            }
            Kind.DOWNLOAD -> {
                stroke.strokeWidth = 2.2f
                c.drawLine(12f, 3.5f, 12f, 15f, stroke)
                path.moveTo(7f, 10.5f); path.lineTo(12f, 15.5f); path.lineTo(17f, 10.5f)
                c.drawPath(path, stroke)
                c.drawLine(4.5f, 20f, 19.5f, 20f, stroke)
            }
            Kind.REFRESH -> {
                // open circle (gap at the upper right) ending in a solid arrowhead, like the usual refresh icon
                stroke.strokeWidth = 2.2f
                c.drawArc(RectF(5.2f, 5.2f, 18.8f, 18.8f), 30f, 280f, false, stroke)
                path.moveTo(19.13f, 9.1f); path.lineTo(17.97f, 3.95f); path.lineTo(13.85f, 8.86f); path.close()
                c.drawPath(path, fill)
            }
            Kind.CALENDAR -> {
                stroke.strokeWidth = 2f
                c.drawRoundRect(RectF(3.5f, 5f, 20.5f, 20f), 2.5f, 2.5f, stroke)
                c.drawLine(3.5f, 9.5f, 20.5f, 9.5f, stroke)
                c.drawLine(8f, 3f, 8f, 6.5f, stroke)
                c.drawLine(16f, 3f, 16f, 6.5f, stroke)
                for (y in listOf(13f, 16.5f)) for (x in listOf(8f, 12f, 16f)) c.drawCircle(x, y, 1.1f, fill)
            }
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

/** A button with an icon above a short label (for a row of evenly spaced actions). */
fun labeledButton(ctx: Context, kind: IconDrawable.Kind, label: String, tint: Int = 0xFFFFFFFF.toInt(), click: () -> Unit): android.widget.LinearLayout =
    android.widget.LinearLayout(ctx).apply {
        orientation = android.widget.LinearLayout.VERTICAL
        gravity = android.view.Gravity.CENTER_HORIZONTAL
        val p = Ui.dp(ctx, 6)
        setPadding(p / 2, p, p / 2, p)
        val tv = android.util.TypedValue()
        ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
        if (tv.resourceId != 0) setBackgroundResource(tv.resourceId)
        isClickable = true
        contentDescription = label
        setOnClickListener { click() }
        addView(ImageView(ctx).apply { setImageDrawable(IconDrawable(kind, tint)) },
            android.widget.LinearLayout.LayoutParams(Ui.dp(ctx, 24), Ui.dp(ctx, 24)))
        addView(Ui.label(ctx, label, 11f, if (tint == 0xFFFFFFFF.toInt()) 0xFFD0D3D8.toInt() else tint).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = android.view.Gravity.CENTER
            setPadding(0, Ui.dp(ctx, 3), 0, 0)
        }, android.widget.LinearLayout.LayoutParams(-1, -2))
    }

/** An ImageView showing an [IconDrawable], with padding for a comfortable touch target. */
fun iconButton(ctx: Context, kind: IconDrawable.Kind, sizeDp: Int = 44, padDp: Int = 10, click: (() -> Unit)? = null): ImageView =
    ImageView(ctx).apply {
        setImageDrawable(IconDrawable(kind))
        val p = Ui.dp(ctx, padDp)
        setPadding(p, p, p, p)
        minimumWidth = Ui.dp(ctx, sizeDp)
        minimumHeight = Ui.dp(ctx, sizeDp)
        if (click != null) {
            setOnClickListener { click() }
            pressFeedback(this)
        }
    }

/**
 * Visible response to a press, even when the tap opens another screen right away: the standard round ripple and a
 * quick shrink of the icon while the finger is down.
 */
fun pressFeedback(v: android.view.View) {
    if (v.background == null) {
        val tv = android.util.TypedValue()
        v.context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
        if (tv.resourceId != 0) v.setBackgroundResource(tv.resourceId)
    }
    v.setOnTouchListener { view, e ->
        when (e.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> view.animate().scaleX(0.86f).scaleY(0.86f).setDuration(80).start()
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL ->
                view.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
        }
        false   // the click still happens normally
    }
}
