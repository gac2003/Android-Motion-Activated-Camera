package com.motioncam

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

object Ui {
    const val BG = 0xFF101114.toInt()
    const val PANEL = 0xCC15171B.toInt()
    const val ACCENT = 0xFFFFC107.toInt()   // "auto" yellow, like Samsung Pro mode
    const val RED = 0xFFE53935.toInt()
    const val GREY = 0xFF8A8F96.toInt()

    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density + 0.5f).toInt()

    fun label(ctx: Context, s: String, sp: Float = 14f, color: Int = Color.WHITE): TextView =
        TextView(ctx).apply {
            text = s
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            setTextColor(color)
        }

    fun chip(ctx: Context, s: String): TextView =
        TextView(ctx).apply {
            text = s
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(ctx, 12), dp(ctx, 6), dp(ctx, 12), dp(ctx, 6))
            minWidth = dp(ctx, 64)
            isClickable = true
            setChipState(this, false)
        }

    fun setChipState(tv: TextView, active: Boolean) {
        val bg = GradientDrawable()
        bg.cornerRadius = dp(tv.context, 10).toFloat()
        bg.setColor(if (active) 0xFF3A3320.toInt() else 0xAA2A2D33.toInt())
        if (active) bg.setStroke(dp(tv.context, 1), ACCENT)
        tv.background = bg
        tv.setTextColor(if (active) ACCENT else Color.WHITE)
    }

    fun pill(tv: TextView, color: Int) {
        val bg = GradientDrawable()
        bg.cornerRadius = dp(tv.context, 22).toFloat()
        bg.setColor(color)
        tv.background = bg
        tv.setTextColor(Color.WHITE)
    }
}

/** A Pro-mode control button: small title, value underneath. Yellow value = automatic. */
class Chip(ctx: Context, val name: String) {
    val title: TextView = Ui.label(ctx, name, 10f, Ui.GREY).apply { gravity = Gravity.CENTER }
    val value: TextView = Ui.label(ctx, "", 13f).apply { gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD) }
    val view: LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        minimumWidth = Ui.dp(ctx, 62)
        setPadding(Ui.dp(ctx, 10), Ui.dp(ctx, 5), Ui.dp(ctx, 10), Ui.dp(ctx, 5))
        isClickable = true
        addView(title)
        addView(value)
    }

    fun set(v: String, auto: Boolean, selected: Boolean, enabled: Boolean = true) {
        if (value.text != v) value.text = v
        value.setTextColor(if (!enabled) Ui.GREY else if (auto) Ui.ACCENT else Color.WHITE)
        val bg = GradientDrawable()
        bg.cornerRadius = Ui.dp(view.context, 10).toFloat()
        bg.setColor(if (selected) 0xFF3C4048.toInt() else 0xAA2A2D33.toInt())
        if (selected) bg.setStroke(Ui.dp(view.context, 1), Color.WHITE)
        view.background = bg
    }
}

/** Centres its children inside the largest rectangle of the given aspect ratio (width / height). */
class AspectFrame(ctx: Context) : FrameLayout(ctx) {
    var aspect = 16f / 9f
        set(v) { if (field != v) { field = v; requestLayout() } }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = View.MeasureSpec.getSize(widthMeasureSpec)
        val h = View.MeasureSpec.getSize(heightMeasureSpec)
        var cw = w
        var ch = (w / aspect).toInt()
        if (ch > h) { ch = h; cw = (h * aspect).toInt() }
        super.onMeasure(
            View.MeasureSpec.makeMeasureSpec(cw, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(ch, View.MeasureSpec.EXACTLY),
        )
    }
}
