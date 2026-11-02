package com.motioncam

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.TextView

object Ui {
    const val BG = 0xFF101114.toInt()
    const val PANEL = 0xCC15171B.toInt()
    const val ACCENT = 0xFFFFC107.toInt()
    const val RED = 0xFFE53935.toInt()

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
