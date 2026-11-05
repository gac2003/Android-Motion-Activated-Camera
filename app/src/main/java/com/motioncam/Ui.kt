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
    /** Screen width in dp. */
    fun widthDp(ctx: Context): Float = ctx.resources.displayMetrics.widthPixels / ctx.resources.displayMetrics.density

    /**
     * Keeps a settings-style page at a readable width on tablets (>= 700 dp); phones are unaffected.
     * Call with the content view placed inside a ScrollView.
     */
    fun capWidth(content: View) {
        val ctx = content.context
        if (widthDp(ctx) < 700f) return
        content.layoutParams = android.widget.FrameLayout.LayoutParams(dp(ctx, 640), -2, Gravity.CENTER_HORIZONTAL)
    }

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

/** Title bar with a back arrow that returns to the previous (main) screen. */
fun topBar(a: android.app.Activity, title: String, right: View? = null): LinearLayout =
    LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(Ui.BG)
        val p = Ui.dp(a, 8)
        setPadding(p, p, p * 2, p)
        addView(Ui.label(a, "‹", 30f).apply {
            setPadding(Ui.dp(a, 8), 0, Ui.dp(a, 16), Ui.dp(a, 4))
            setOnClickListener { a.finish() }
        })
        addView(Ui.label(a, title, 20f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END },
            LinearLayout.LayoutParams(0, -2, 1f))
        if (right != null) addView(right)
    }

/**
 * A horizontal bar whose optional buttons drop out (into an overflow menu) only when they don't fit.
 * Buttons are dropped in reverse order of [collapsible] (least important last in that list). On screens
 * where everything fits nothing changes.
 */
class FitRow(ctx: Context) : LinearLayout(ctx) {
    /** Buttons that may be hidden, most important first. */
    val collapsible = ArrayList<View>()
    /** Width the flexible title needs at minimum (dp). */
    var titleMinDp = 56
    var title: View? = null
    /** Buttons currently hidden; show them in the overflow menu. */
    var hidden: List<View> = emptyList(); private set

    init { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

    private fun natural(c: View): Int {
        c.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val lp = c.layoutParams as? MarginLayoutParams
        return c.measuredWidth + (lp?.leftMargin ?: 0) + (lp?.rightMargin ?: 0)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (View.MeasureSpec.getMode(widthMeasureSpec) != View.MeasureSpec.UNSPECIFIED) {
            val avail = View.MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
            var need = Ui.dp(context, titleMinDp)
            for (i in 0 until childCount) {
                val c = getChildAt(i)
                if (c === title || c in collapsible || c.visibility == View.GONE) continue
                need += natural(c)
            }
            val widths = collapsible.map { natural(it) }
            need += widths.sum()
            val hide = HashSet<View>()
            var i = collapsible.lastIndex
            while (need > avail && i >= 0) { need -= widths[i]; hide += collapsible[i]; i-- }
            for (c in collapsible) {
                val v = if (c in hide) View.GONE else View.VISIBLE
                if (c.visibility != v) c.visibility = v
            }
            hidden = collapsible.filter { it in hide }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
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

    private var lastSelected: Boolean? = null
    private var lastColor = 0
    private var marked = false

    /** A small yellow dot in the corner: this value was changed here but not sent yet. */
    fun mark(on: Boolean) {
        if (marked == on) return
        marked = on
        view.foreground = if (!on) null else android.graphics.drawable.LayerDrawable(arrayOf(GradientDrawable().apply {
            shape = GradientDrawable.OVAL; setColor(Ui.ACCENT)
        })).apply {
            val d = Ui.dp(view.context, 6)
            setLayerGravity(0, Gravity.TOP or Gravity.END)
            setLayerSize(0, d, d)
            setLayerInset(0, 0, Ui.dp(view.context, 4), Ui.dp(view.context, 5), 0)
        }
        view.contentDescription = name + if (on) " (changed, not sent yet)" else ""
    }

    fun set(v: String, auto: Boolean, selected: Boolean, enabled: Boolean = true) {
        if (value.text != v) value.text = v
        val color = if (!enabled) Ui.GREY else if (auto) Ui.ACCENT else Color.WHITE
        if (color != lastColor) { lastColor = color; value.setTextColor(color) }
        if (lastSelected == selected) return   // avoid re-creating backgrounds 4x a second
        lastSelected = selected
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

/** Lays children out left to right, wrapping onto new lines (for chips). */
class FlowLayout(ctx: Context, private val hGap: Int = Ui.dp(ctx, 8), private val vGap: Int = Ui.dp(ctx, 8)) : android.view.ViewGroup(ctx) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxW = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        var x = 0; var y = 0; var lineH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == View.GONE) continue
            c.measure(MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            if (x > 0 && x + c.measuredWidth > maxW) { x = 0; y += lineH + vGap; lineH = 0 }
            x += c.measuredWidth + hGap
            lineH = maxOf(lineH, c.measuredHeight)
        }
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), y + lineH + paddingTop + paddingBottom)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxW = r - l - paddingLeft - paddingRight
        var x = 0; var y = 0; var lineH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == View.GONE) continue
            if (x > 0 && x + c.measuredWidth > maxW) { x = 0; y += lineH + vGap; lineH = 0 }
            c.layout(paddingLeft + x, paddingTop + y, paddingLeft + x + c.measuredWidth, paddingTop + y + c.measuredHeight)
            x += c.measuredWidth + hGap
            lineH = maxOf(lineH, c.measuredHeight)
        }
    }
}
