package com.motioncam

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * One choice in an [OptionSheet]: a short [title], an optional one-line [desc] in a dimmer colour, an optional icon,
 * [checked] for the current value, [danger] (red) for destructive actions, and an optional [section] heading.
 */
class Opt(
    val title: String,
    val desc: String? = null,
    val icon: IconDrawable.Kind? = null,
    val checked: Boolean = false,
    val danger: Boolean = false,
    val section: String? = null,
    val enabled: Boolean = true,
    val action: () -> Unit,
)

/**
 * Readable option lists (instead of plain dialog items that run together as one block of text): each option is a
 * title with a dimmer description underneath, comfortable touch height, dividers between options, section headings
 * for longer menus, a ✓ on the current choice and red for destructive actions.
 */
object OptionSheet {
    fun show(a: Activity, title: String?, opts: List<Opt>, subtitle: String? = null): AlertDialog {
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(0, Ui.dp(a, if (title == null) 8 else 0), 0, Ui.dp(a, 4)) }
        if (subtitle != null) box.addView(Ui.label(a, subtitle, 13f, Ui.GREY).apply { setPadding(Ui.dp(a, 24), 0, Ui.dp(a, 24), Ui.dp(a, 8)) })
        lateinit var dialog: AlertDialog
        var lastSection: String? = null
        opts.forEachIndexed { i, o ->
            if (o.section != null && o.section != lastSection) {
                lastSection = o.section
                box.addView(Ui.label(a, o.section.uppercase(), 11f, Ui.ACCENT).apply {
                    setPadding(Ui.dp(a, 24), Ui.dp(a, if (i == 0) 4 else 14), Ui.dp(a, 24), Ui.dp(a, 2))
                    letterSpacing = 0.06f
                })
            } else if (i > 0) {
                box.addView(View(a).apply { setBackgroundColor(0x1FFFFFFF) }, LinearLayout.LayoutParams(-1, 1).apply {
                    leftMargin = Ui.dp(a, if (o.icon != null) 64 else 24); rightMargin = Ui.dp(a, 16)
                })
            }
            box.addView(row(a, o) { dialog.dismiss(); o.action() })
        }
        dialog = AlertDialog.Builder(a).apply { if (title != null) setTitle(title) }
            .setView(ScrollView(a).apply { addView(box) })
            .setNegativeButton("Cancel", null).create()
        dialog.show()
        return dialog
    }

    private fun row(a: Activity, o: Opt, click: () -> Unit): View = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = Ui.dp(a, 52)
        setPadding(Ui.dp(a, 24), Ui.dp(a, 10), Ui.dp(a, 16), Ui.dp(a, 10))
        val tv = android.util.TypedValue()
        a.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
        if (tv.resourceId != 0) setBackgroundResource(tv.resourceId)
        isEnabled = o.enabled
        alpha = if (o.enabled) 1f else 0.4f
        if (o.enabled) setOnClickListener { click() }
        val color = if (o.danger) Ui.RED else Color.WHITE
        if (o.icon != null) addView(ImageView(a).apply { setImageDrawable(IconDrawable(o.icon, color)) },
            LinearLayout.LayoutParams(Ui.dp(a, 24), Ui.dp(a, 24)).apply { rightMargin = Ui.dp(a, 16) })
        val texts = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(Ui.label(a, o.title, 16f, color))
        if (o.desc != null) texts.addView(Ui.label(a, o.desc, 13f, 0xFFA0A4AA.toInt()).apply { setPadding(0, Ui.dp(a, 2), 0, 0) })
        addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        if (o.checked) addView(Ui.label(a, "✓", 18f, Ui.ACCENT).apply { setPadding(Ui.dp(a, 12), 0, 0, 0) })
    }
}
