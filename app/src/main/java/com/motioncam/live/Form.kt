package com.motioncam.live

import android.app.Activity
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import com.motioncam.Ui

/** Small builders for the live-connection screens (same look as the settings screens). */
class Form(private val a: Activity, val box: LinearLayout) {
    fun dp(v: Int) = Ui.dp(a, v)

    fun title(s: String) = box.addView(Ui.label(a, s.uppercase(), 13f, Ui.ACCENT).apply { setPadding(0, dp(20), 0, dp(4)) })

    fun note(s: String, color: Int = 0xFFB0B4BA.toInt()): TextView =
        Ui.label(a, s, 12f, color).apply { setPadding(0, dp(4), 0, dp(4)) }.also { box.addView(it) }

    fun line(s: String, size: Float = 14f, color: Int = Color.WHITE): TextView =
        Ui.label(a, s, size, color).apply { setPadding(0, dp(3), 0, dp(3)) }.also { box.addView(it) }

    fun button(label: String, full: Boolean = true, click: () -> Unit): TextView =
        Ui.chip(a, label).apply { setOnClickListener { click() } }.also {
            box.addView(it, LinearLayout.LayoutParams(if (full) -1 else -2, -2).apply { topMargin = dp(8) })
        }

    fun row(vararg views: View): LinearLayout = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        views.forEach { addView(it, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6); topMargin = dp(6) }) }
    }.also { box.addView(it) }

    fun chip(label: String, click: () -> Unit) = Ui.chip(a, label).apply { setOnClickListener { click() } }

    fun toggle(label: String, value: Boolean, enabled: Boolean = true, set: (Boolean) -> Unit): Switch =
        Switch(a).apply {
            text = label; setTextColor(Color.WHITE); isChecked = value; isEnabled = enabled
            setOnCheckedChangeListener { _, b -> set(b) }
            setPadding(0, dp(6), 0, dp(6))
        }.also { box.addView(it) }

    /** Slider with its value shown; [onDone] when the finger lifts (so a camera isn't flooded with changes). */
    fun slider(label: String, min: Int, max: Int, value: Int, show: (Int) -> String, enabled: Boolean = true, onDone: (Int) -> Unit): SeekBar {
        val head = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
        val v = Ui.label(a, show(value), 13f, Ui.ACCENT)
        head.addView(Ui.label(a, label, 14f), LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(v)
        box.addView(head)
        val sb = SeekBar(a).apply {
            this.max = (max - min).coerceAtLeast(1); progress = (value - min).coerceIn(0, this.max); isEnabled = enabled
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) { v.text = show(p + min) }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) { onDone(s.progress + min) }
            })
        }
        box.addView(sb)
        return sb
    }

    fun choice(label: String, options: List<String>, current: Int, enabled: Boolean = true, set: (Int) -> Unit) {
        box.addView(Ui.label(a, label, 14f).apply { setPadding(0, dp(8), 0, 0) })
        val g = android.widget.RadioGroup(a)
        options.forEachIndexed { i, o ->
            g.addView(android.widget.RadioButton(a).apply { text = o; id = 3000 + i; setTextColor(Color.WHITE); isChecked = i == current; isEnabled = enabled })
        }
        g.setOnCheckedChangeListener { _, id -> set(id - 3000) }
        box.addView(g)
    }
}
