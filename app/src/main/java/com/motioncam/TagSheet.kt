package com.motioncam

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast

/**
 * "Tags…": the tags of one or more clips, as checkboxes (like "Back up to…"). A box shows ✓ when every clip has the
 * tag and "–" when some do. Type to find a tag among many, or to make a new one. Remote folder tags are saved in the
 * folder, so the other phones see them too.
 */
object TagSheet {
    private enum class State { ALL, SOME, NONE }
    private class Row(val tag: String, val start: State) { var want = start }

    /** [scope]: null = this phone's clips, else the remote folder id; [pool] = all clips there (for the tag list). */
    fun show(a: Activity, scope: String?, clips: List<Clip>, pool: List<Clip>, done: () -> Unit = {}) {
        if (clips.isEmpty()) return
        val n = clips.size
        val rows = ArrayList<Row>()
        for ((tag, _) in TagStore.all(a, scope, pool)) {
            val have = clips.count { c -> TagStore.tagsOf(a, scope, c).any { it.equals(tag, true) } }
            rows += Row(tag, when { have >= n -> State.ALL; have > 0 -> State.SOME; else -> State.NONE })
        }
        // tags these clips have first, then the most used
        rows.sortBy { if (it.start == State.NONE) 1 else 0 }
        fun d(v: Int) = Ui.dp(a, v)

        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(d(20), d(4), d(20), d(4)) }
        val input = EditText(a).apply {
            hint = if (rows.isEmpty()) "New tag, e.g. Delivery" else "Find or add a tag"
            setTextColor(Color.WHITE); setHintTextColor(0xFF6A6F76.toInt()); isSingleLine = true
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            setPadding(d(12), d(10), d(12), d(10))
            background = GradientDrawable().apply { cornerRadius = d(12).toFloat(); setColor(0xFF1E2126.toInt()) }
        }
        box.addView(input, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = d(6) })
        val listBox = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(a).apply { addView(listBox) }
        box.addView(scroll, LinearLayout.LayoutParams(-1, minOf(d(360), a.resources.displayMetrics.heightPixels / 2)))
        lateinit var dialog: AlertDialog

        fun refreshButton() {
            val changes = rows.count { it.want != it.start }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.apply { isEnabled = changes > 0; alpha = if (changes > 0) 1f else 0.4f }
        }

        fun rowView(r: Row): View {
            val v = LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(0, d(10), 0, d(10)); isClickable = true
                val tv = android.util.TypedValue()
                a.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                if (tv.resourceId != 0) setBackgroundResource(tv.resourceId)
            }
            val check = Ui.label(a, "", 15f, 0xFF15171B.toInt()).apply { gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD) }
            v.addView(check, LinearLayout.LayoutParams(d(24), d(24)))
            val label = Ui.label(a, r.tag, 15f).apply { setPadding(d(14), 0, 0, 0); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
            v.addView(label, LinearLayout.LayoutParams(0, -2, 1f))
            val note = Ui.label(a, "", 12f, Ui.GREY)
            v.addView(note)
            fun paint() {
                check.text = when (r.want) { State.ALL -> "✓"; State.SOME -> "–"; State.NONE -> "" }
                check.background = GradientDrawable().apply {
                    cornerRadius = d(5).toFloat()
                    if (r.want == State.NONE) { setColor(0); setStroke(d(2), Ui.GREY) } else setColor(Ui.ACCENT)
                }
                note.text = when {
                    r.want == r.start && r.start == State.SOME -> "some"
                    r.want == r.start -> ""
                    r.want == State.ALL -> "will add"
                    r.want == State.NONE -> "will remove"
                    else -> ""
                }
                note.setTextColor(if (r.want == State.NONE && r.start != State.NONE) 0xFFFF8A85.toInt() else if (r.want != r.start) Ui.ACCENT else Ui.GREY)
                v.contentDescription = r.tag + ": " + when (r.want) { State.ALL -> "on"; State.SOME -> "on some"; State.NONE -> "off" }
            }
            v.setOnClickListener {
                r.want = when (r.want) {
                    State.ALL -> State.NONE
                    State.NONE -> if (r.start == State.SOME) State.SOME else State.ALL
                    State.SOME -> State.ALL
                }
                paint(); refreshButton()
            }
            paint()
            return v
        }

        fun render() {
            listBox.removeAllViews()
            val q = TagStore.clean(input.text.toString())
            val shown = rows.filter { q.isEmpty() || it.tag.contains(q, true) }
            if (q.isNotEmpty() && rows.none { it.tag.equals(q, true) }) {
                listBox.addView(Ui.label(a, "＋  Add tag \"$q\"", 15f, Ui.ACCENT).apply {
                    setPadding(0, d(12), 0, d(12)); isClickable = true
                    setOnClickListener {
                        rows.add(0, Row(q, State.NONE).apply { want = State.ALL })
                        input.setText("")
                        render(); refreshButton()
                    }
                })
            }
            for (r in shown) listBox.addView(rowView(r))
            if (rows.isEmpty() && q.isEmpty()) listBox.addView(Ui.label(a,
                "No tags yet. Type a name above (for example Delivery, Cat or Car) and tap Add. Search can then show only clips with that tag.",
                13f, Ui.GREY).apply { setPadding(0, d(8), 0, d(8)) })
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, af: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = render()
        })
        input.setOnEditorActionListener { _, _, _ ->
            val q = TagStore.clean(input.text.toString())
            if (q.isNotEmpty()) {
                val r = rows.firstOrNull { it.tag.equals(q, true) } ?: Row(q, State.NONE).also { rows.add(0, it) }
                r.want = State.ALL
                input.setText("")
                render(); refreshButton()
            }
            true
        }
        render()
        dialog = AlertDialog.Builder(a).setTitle("Tags · " + if (n == 1) "1 clip" else "$n clips")
            .setView(box)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            refreshButton()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val add = rows.filter { it.want == State.ALL && it.start != State.ALL }.map { it.tag }.toSet()
                val remove = rows.filter { it.want == State.NONE && it.start != State.NONE }.map { it.tag }.toSet()
                TagStore.change(a, scope, clips, add, remove)
                val msg = listOfNotNull(
                    if (add.isNotEmpty()) "Added " + add.joinToString { "\"$it\"" } else null,
                    if (remove.isNotEmpty()) "removed " + remove.joinToString { "\"$it\"" } else null,
                ).joinToString("; ").replaceFirstChar { it.uppercase() }
                if (msg.isNotEmpty()) Toast.makeText(a, msg, Toast.LENGTH_SHORT).show()
                dialog.dismiss()
                done()
            }
        }
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog.show()
    }
}
