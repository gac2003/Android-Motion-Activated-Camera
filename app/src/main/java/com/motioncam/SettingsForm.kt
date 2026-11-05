package com.motioncam

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A slider for lists you scroll through: it only moves when its knob is dragged. A tap elsewhere on the track (easy
 * to do by accident while scrolling) does nothing, and a tap on the knob without moving it doesn't change the value.
 */
class GrabSeekBar(ctx: Context) : SeekBar(ctx) {
    private var grabbed = false
    private var downX = 0f
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private val reach = Ui.dp(ctx, 28)

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val th = thumb?.bounds
                val cx = paddingLeft - thumbOffset + (th?.centerX() ?: 0)
                grabbed = abs(e.x - cx) <= reach
                downX = e.x
                if (!grabbed) return false
            }
            MotionEvent.ACTION_UP -> if (grabbed && abs(e.x - downX) < slop) {
                // a tap on the knob: no change
                val c = MotionEvent.obtain(e).apply { action = MotionEvent.ACTION_CANCEL }
                super.onTouchEvent(c); c.recycle(); grabbed = false; return true
            }
        }
        if (!grabbed) return false
        val r = super.onTouchEvent(e)
        if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) grabbed = false
        return r
    }
}

/**
 * The rows of MotionCam's settings screens: switches, drop-down choices, and numbers with a slider plus a box to type
 * the exact value. Used by this phone's Settings and, with the same labels and units, by a camera's remote settings.
 */
class SettingsForm(private val a: Activity, var box: LinearLayout) {
    private fun dp(v: Int) = Ui.dp(a, v)

    fun note(s: String) {
        box.addView(Ui.label(a, s, 12f, 0xFFB0B4BA.toInt()).apply { setPadding(0, dp(6), 0, dp(6)) })
    }

    fun toggle(label: String, value: Boolean, enabled: Boolean = true, set: (Boolean) -> Unit): Switch =
        Switch(a).apply {
            text = label
            setTextColor(Color.WHITE)
            isChecked = value
            isEnabled = enabled
            setOnCheckedChangeListener { _, b -> set(b) }
            setPadding(0, dp(8), 0, dp(8))
        }.also { box.addView(it) }

    private fun numberField(initial: String, decimal: Boolean, signed: Boolean = false): EditText = EditText(a).apply {
        setText(initial)
        setTextColor(Color.WHITE)
        gravity = Gravity.END
        inputType = InputType.TYPE_CLASS_NUMBER or (if (decimal) InputType.TYPE_NUMBER_FLAG_DECIMAL else 0) or
            (if (signed) InputType.TYPE_NUMBER_FLAG_SIGNED else 0)
        imeOptions = EditorInfo.IME_ACTION_DONE
        setSingleLine()
        setSelectAllOnFocus(true)
        minWidth = dp(72)
    }

    /**
     * Integer setting with slider + exact text entry. If [offLabel] is given, 0 means "off" and
     * values between 1 and [minActive]-1 are raised to [minActive].
     */
    fun number(
        label: String, unit: String, min: Int, max: Int, value: Int,
        offLabel: String? = null, minActive: Int = 1, slider: Boolean = true, enabled: Boolean = true, set: (Int) -> Unit,
    ) {
        val head = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val field = numberField(if (offLabel != null && value == 0) "" else "$value", false)
        if (offLabel != null) field.hint = offLabel
        field.isEnabled = enabled
        head.addView(Ui.label(a, label, 14f), LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(field)
        head.addView(Ui.label(a, unit, 13f, Ui.GREY))
        head.setPadding(0, dp(10), 0, 0)
        box.addView(head)

        var sb: SeekBar? = null
        var syncing = false
        fun normalise(v: Int): Int {
            if (offLabel != null && v <= 0) return 0
            return v.coerceIn(maxOf(min, if (offLabel != null) minActive else min), max)
        }
        fun show(v: Int) {
            syncing = true
            field.setText(if (offLabel != null && v == 0) "" else "$v")
            sb?.progress = (if (offLabel != null && v == 0) min else v) - min
            syncing = false
        }
        if (slider) {
            sb = GrabSeekBar(a).apply {
                this.max = max - min
                progress = (value - min).coerceIn(0, max - min)
                isEnabled = enabled
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                        if (!fromUser || syncing) return
                        val v = normalise(p + min)
                        set(v)
                        syncing = true
                        field.setText(if (offLabel != null && v == 0) "" else "$v")
                        syncing = false
                    }
                    override fun onStartTrackingTouch(s: SeekBar) {}
                    override fun onStopTrackingTouch(s: SeekBar) {}
                })
            }
            box.addView(sb)
        }
        var committed = value
        fun commit() {
            if (syncing) return
            val v = normalise(field.text.toString().trim().toIntOrNull() ?: if (offLabel != null) 0 else committed)
            if (v != committed) { committed = v; set(v) }
            show(v)
        }
        field.setOnEditorActionListener { _, _, _ -> commit(); field.clearFocus(); false }
        field.setOnFocusChangeListener { _, has -> if (!has) commit() }
    }

    /** Decimal setting (one decimal place) with slider + exact text entry. */
    fun decimal(label: String, unit: String, min: Float, max: Float, value: Float, enabled: Boolean = true, set: (Float) -> Unit) =
        scaled(label, unit, value.toDouble(), steps = ((max - min) * 10).roundToInt(),
            toPos = { ((it - min) * 10).roundToInt() }, fromPos = { (min + it / 10.0) },
            format = { "%.1f".format(it) }, parse = { it.replace(',', '.').toDoubleOrNull()?.coerceIn(min.toDouble(), max.toDouble()) },
            decimalInput = true, enabled = enabled) { set(it.toFloat()) }

    /**
     * A value with a slider of [steps] positions and a box for the exact value. [toPos]/[fromPos] map between the value
     * and the slider (so the slider can be logarithmic, reversed…); [format]/[parse] are what the box shows and accepts
     * (parse returns null for "not valid", and gets an empty string when the box is cleared — e.g. "∞" for focus).
     */
    fun scaled(
        label: String, unit: String, value: Double, steps: Int,
        toPos: (Double) -> Int, fromPos: (Int) -> Double, format: (Double) -> String, parse: (String) -> Double?,
        hint: String? = null, decimalInput: Boolean = true, signed: Boolean = false, enabled: Boolean = true, set: (Double) -> Unit,
    ) {
        val head = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val field = numberField(format(value), decimalInput, signed)
        hint?.let { field.hint = it }
        field.isEnabled = enabled
        head.addView(Ui.label(a, label, 14f), LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(field)
        head.addView(Ui.label(a, unit, 13f, Ui.GREY))
        head.setPadding(0, dp(10), 0, 0)
        box.addView(head)
        var syncing = false
        var current = value
        val sb = GrabSeekBar(a).apply {
            max = steps.coerceAtLeast(1)
            progress = toPos(value).coerceIn(0, max)
            isEnabled = enabled
        }
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                if (!fromUser || syncing) return
                current = fromPos(p)
                set(current)
                syncing = true; field.setText(format(current)); syncing = false
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
        box.addView(sb)
        fun commit() {
            if (syncing) return
            val v = parse(field.text.toString().trim())
            if (v == null) { syncing = true; field.setText(format(current)); syncing = false; return }
            if (format(v) != format(current)) { current = v; set(v) }
            syncing = true
            field.setText(format(current))
            sb.progress = toPos(current).coerceIn(0, sb.max)
            syncing = false
        }
        field.setOnEditorActionListener { _, _, _ -> commit(); field.clearFocus(); false }
        field.setOnFocusChangeListener { _, has -> if (!has) commit() }
    }

    fun choice(label: String, options: List<String>, current: Int, enabled: Boolean = true, set: (Int) -> Unit) {
        box.addView(Ui.label(a, label, 14f).apply { setPadding(0, dp(10), 0, 0) })
        val sp = Spinner(a)
        sp.adapter = ArrayAdapter(a, android.R.layout.simple_spinner_dropdown_item, options)
        sp.setSelection(current.coerceIn(0, (options.size - 1).coerceAtLeast(0)))
        sp.isEnabled = enabled
        var first = true
        sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (first) { first = false; return }
                set(pos)
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        box.addView(sp)
    }
}
