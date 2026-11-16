package com.motioncam

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Search: a full screen of filters for the clips shown (this phone's, a remote folder's or a collection's), with the
 * number of matching clips on the button at the bottom ("Show 12 clips"). Close (✕ or Back) leaves the filter as it
 * was. Sections: Date (quick choices and a calendar for a day or a range), Tags and Collections (tap once to include,
 * again to exclude, once more to clear; Any / All for the included ones), Length, File size, and how the different
 * filters combine.
 */
class SearchSheet private constructor(
    private val a: Activity,
    private val where: String,
    private val f: ClipFilter,
    private val clips: List<Clip>,
    private val tagMap: Map<Long, Set<String>>,
    private val cols: List<ColInfo>,
    private val onApply: (ClipFilter) -> Unit,
) {
    class ColInfo(val id: String, val name: String, val ids: Set<Long>)

    companion object {
        fun show(a: Activity, where: String, start: ClipFilter, clips: List<Clip>, tagsOf: (Clip) -> Set<String>,
                 cols: List<ColInfo>, onApply: (ClipFilter) -> Unit) {
            SearchSheet(a, where, start.copy(), clips, clips.associate { it.id to tagsOf(it) }, cols, onApply).open()
        }

        /** The sheet open now (to keep it, with its unsaved choices, when the phone is turned). */
        var showing: SearchSheet? = null
            private set

        private const val SHOWN_TAGS = 24
        private val DATE_PRESETS = listOf(null to "Any time", "today" to "Today", "yesterday" to "Yesterday", "7d" to "Last 7 days", "30d" to "Last 30 days")
        private val LENGTHS = listOf(Triple("Any length", null, null), Triple("Under 15 s", null, 15), Triple("15 s – 1 min", 15, 60),
            Triple("1 – 5 min", 60, 300), Triple("Over 5 min", 300, null))
        private val SIZES = listOf(Triple("Any size", null, null), Triple("Under 10 MB", null, 10), Triple("10 – 50 MB", 10, 50),
            Triple("50 – 200 MB", 50, 200), Triple("Over 200 MB", 200, null))
    }

    private val dialog = Dialog(a, android.R.style.Theme_Material_NoActionBar).apply { requestWindowFeature(Window.FEATURE_NO_TITLE) }
    private fun d(v: Int) = Ui.dp(a, v)
    private val colById = cols.associateBy { it.id }
    private val tagCounts: List<Pair<String, Int>> = run {
        val m = java.util.TreeMap<String, Int>(String.CASE_INSENSITIVE_ORDER)
        for (t in tagMap.values) for (x in t) m[x] = (m[x] ?: 0) + 1
        m.entries.map { it.key to it.value }.sortedWith(compareBy({ -it.second }, { it.first.lowercase() }))
    }
    private val clipDays: Map<Long, Int> = clips.groupingBy { ClipFilter.dayStart(it.startMs) }.eachCount()

    /** Redrawn after every change. */
    private val repaints = ArrayList<() -> Unit>()
    private lateinit var showBtn: TextView
    private lateinit var clearBtn: TextView

    /** The choices made so far (not applied yet), for reopening after the screen is rebuilt. */
    fun draft(): String = f.toJson().toString()
    fun belongsTo(x: Activity) = a === x
    fun close() { try { dialog.dismiss() } catch (_: Exception) {} }

    private fun open() {
        showing = this
        dialog.setOnDismissListener { if (showing === this) showing = null }
        val root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.BG) }
        // top: close, title and what is searched; Clear all on the right
        val top = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(d(4), d(8), d(12), d(4)) }
        top.addView(iconButton(a, IconDrawable.Kind.CLOSE, 48, 12) { dialog.dismiss() }.apply { contentDescription = "Close without changing the filter" })
        val tt = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(d(4), 0, 0, 0) }
        tt.addView(Ui.label(a, "Search clips", 20f).apply { setTypeface(typeface, Typeface.BOLD) })
        tt.addView(Ui.label(a, where, 12f, Ui.GREY).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        top.addView(tt, LinearLayout.LayoutParams(0, -2, 1f))
        clearBtn = Ui.label(a, "Clear all", 15f, Ui.ACCENT).apply {
            setPadding(d(12), d(10), d(12), d(10)); isClickable = true
            setOnClickListener { f.clear(); changed() }
        }
        top.addView(clearBtn)
        root.addView(top)

        val body = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(d(16), 0, d(16), d(24)) }
        dateSection(body)
        tagSection(body)
        colSection(body)
        rangeSection(body, "Length", "seconds", LENGTHS, { f.minSec }, { f.maxSec }) { lo, hi -> f.minSec = lo; f.maxSec = hi }
        rangeSection(body, "File size", "MB", SIZES, { f.minMb }, { f.maxMb }) { lo, hi -> f.minMb = lo; f.maxMb = hi }
        combineSection(body)
        root.addView(ScrollView(a).apply { addView(body); isFillViewport = true }, LinearLayout.LayoutParams(-1, 0, 1f))

        // bottom: how many clips the filter shows, and showing them
        // landscape: a slim bar with the button at the end (the usual place for a sheet's main action when wide)
        val land = a.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val bottom = LinearLayout(a).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            if (land) setPadding(d(16), d(6), d(16), d(6)) else setPadding(d(16), d(10), d(16), d(16))
            setBackgroundColor(0xFF1A1C20.toInt())
        }
        showBtn = Ui.label(a, "", if (land) 14f else 16f, Color.BLACK).apply {
            gravity = Gravity.CENTER
            if (land) setPadding(d(24), d(9), d(24), d(9)) else setPadding(0, d(14), 0, d(14))
            setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable().apply { cornerRadius = d(26).toFloat(); setColor(Ui.ACCENT) }
            isClickable = true
            setOnClickListener { onApply(f); dialog.dismiss() }
        }
        bottom.addView(showBtn, if (land) LinearLayout.LayoutParams(-2, -2).also { showBtn.minWidth = d(220) } else LinearLayout.LayoutParams(-1, -2))
        root.addView(bottom)

        dialog.setContentView(root)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN)
        changed()
        dialog.show()
    }

    private fun changed() {
        for (r in repaints) r()
        val n = clips.count { c -> f.matches(c, tagMap[c.id] ?: emptySet()) { id -> colById[id]?.ids?.contains(c.id) == true } }
        showBtn.text = when {
            !f.active -> "Show all ${clips.size} clip${if (clips.size == 1) "" else "s"}"
            n == 0 -> "No clips match"
            else -> "Show $n clip${if (n == 1) "" else "s"}"
        }
        clearBtn.alpha = if (f.active) 1f else 0.35f
        clearBtn.isEnabled = f.active
    }

    // ------------------------------------------------------------------ building blocks

    private fun header(parent: LinearLayout, title: String, right: View? = null): TextView {
        val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, d(20), 0, d(8)) }
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        col.addView(Ui.label(a, title, 17f).apply { setTypeface(typeface, Typeface.BOLD) })
        val sub = Ui.label(a, "", 12f, Ui.ACCENT).apply { visibility = View.GONE }
        col.addView(sub)
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        right?.let { row.addView(it) }
        parent.addView(row)
        // a thin line under each section title, like a list of groups
        parent.addView(View(a).apply { setBackgroundColor(0xFF2A2D33.toInt()) }, LinearLayout.LayoutParams(-1, d(1)).apply { bottomMargin = d(10) })
        return sub
    }

    private fun setSub(sub: TextView, s: String?) { sub.text = s ?: ""; sub.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE }

    private fun chip(text: String, click: () -> Unit) = Ui.chip(a, text).apply {
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
        setPadding(d(14), d(8), d(14), d(8))
        minWidth = 0
        setOnClickListener { click() }
    }

    /** A two- or more-way switch ("Any" / "All"). */
    private fun segmented(options: List<String>, sel: () -> Int, pick: (Int) -> Unit): LinearLayout {
        val box = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val tvs = options.mapIndexed { i, s ->
            Ui.chip(a, s).apply { minWidth = d(52); setOnClickListener { pick(i); changed() } }.also {
                box.addView(it, LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) leftMargin = d(4) })
            }
        }
        repaints += { tvs.forEachIndexed { i, tv -> Ui.setChipState(tv, sel() == i) } }
        return box
    }

    /** Tri-state chip: 0 off, 1 included (✓), 2 excluded (✕, struck through). */
    private fun paintTri(tv: TextView, state: Int, label: String, count: Int?) {
        tv.text = (when (state) { 1 -> "✓ "; 2 -> "✕ "; else -> "" }) + label + (count?.let { "  $it" } ?: "")
        when (state) {
            0 -> Ui.setChipState(tv, false)
            1 -> Ui.setChipState(tv, true)
            else -> {
                tv.background = GradientDrawable().apply { cornerRadius = d(10).toFloat(); setColor(0xFF3A1E1E.toInt()); setStroke(d(1), 0xFFFF6B66.toInt()) }
                tv.setTextColor(0xFFFF8A85.toInt())
            }
        }
        tv.paintFlags = if (state == 2) tv.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG else tv.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
        tv.contentDescription = label + when (state) { 1 -> ", included"; 2 -> ", excluded"; else -> "" }
    }

    private fun hint(parent: LinearLayout, s: String) =
        parent.addView(Ui.label(a, s, 12f, Ui.GREY).apply { setPadding(0, 0, 0, d(8)) })

    private fun numberField(hint: String) = EditText(a).apply {
        this.hint = hint
        inputType = InputType.TYPE_CLASS_NUMBER
        setTextColor(Color.WHITE); setHintTextColor(0xFF6A6F76.toInt())
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
        gravity = Gravity.CENTER
        setPadding(d(8), d(8), d(8), d(8))
        background = GradientDrawable().apply { cornerRadius = d(10).toFloat(); setColor(0xFF1E2126.toInt()); setStroke(d(1), 0xFF3A3E45.toInt()) }
        isSingleLine = true
    }

    // ------------------------------------------------------------------ date

    private fun dateSection(body: LinearLayout) {
        val sub = header(body, "Date")
        repaints += { setSub(sub, if (f.hasDate) f.dateText() else null) }
        val flow = FlowLayout(a)
        for ((key, label) in DATE_PRESETS) {
            val tv = chip(label) { f.datePreset = key; f.from = null; f.to = null; changed() }
            flow.addView(tv)
            repaints += { Ui.setChipState(tv, if (key == null) !f.hasDate else f.datePreset == key) }
        }
        // the calendar is big: it opens from its own chip (marked while a picked date or range is on)
        var calOpen = false
        val cal = calendar()
        val calHint = Ui.label(a, "Tap a day for that day, then another day for a range. Days with clips have a dot.", 12f, Ui.GREY).apply { setPadding(0, 0, 0, d(8)) }
        lateinit var pick: TextView
        pick = chip("") { calOpen = !calOpen; changed() }
        flow.addView(pick)
        repaints += {
            val custom = f.datePreset == null && f.hasDate
            pick.text = if (calOpen) "Hide calendar ▴" else if (custom) f.dateText() + "  ▾" else "Pick dates…  ▾"
            Ui.setChipState(pick, custom)
            cal.visibility = if (calOpen) View.VISIBLE else View.GONE
            calHint.visibility = cal.visibility
        }
        body.addView(flow)
        body.addView(cal, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = d(12) })
        body.addView(calHint)
    }

    private fun monthOf(ms: Long) = Calendar.getInstance().apply { timeInMillis = ms }.let { it.get(Calendar.YEAR) * 12 + it.get(Calendar.MONTH) }

    private fun calendar(): LinearLayout {
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        val head = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val prev = Ui.label(a, "‹", 26f).apply { setPadding(d(14), 0, d(14), 0); contentDescription = "Previous month" }
        val next = Ui.label(a, "›", 26f).apply { setPadding(d(14), 0, d(14), 0); contentDescription = "Next month" }
        val title = Ui.label(a, "", 16f).apply { gravity = Gravity.CENTER }
        head.addView(prev); head.addView(title, LinearLayout.LayoutParams(0, -2, 1f)); head.addView(next)
        box.addView(head, LinearLayout.LayoutParams(-1, -2))
        val grid = GridLayout(a).apply { columnCount = 7 }
        box.addView(grid)
        val now = monthOf(System.currentTimeMillis())
        val first = minOf(clipDays.keys.minOrNull()?.let { monthOf(it) } ?: now, now)
        val last = maxOf(clipDays.keys.maxOrNull()?.let { monthOf(it) } ?: now, now)
        var month = f.range()?.let { (s, e) -> monthOf(if (e == Long.MAX_VALUE) s else e - 1) }?.coerceIn(first, last)
            ?: clipDays.keys.maxOrNull()?.let { monthOf(it) } ?: now
        val winW = minOf(a.resources.displayMetrics.widthPixels - d(32), d(480))
        val cell = (winW / 7).coerceIn(d(36), d(52))
        fun render() {
            grid.removeAllViews()
            val cal = Calendar.getInstance().apply { clear(); set(month / 12, month % 12, 1) }
            title.text = SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(cal.time)
            prev.alpha = if (month > first) 1f else 0.25f
            next.alpha = if (month < last) 1f else 0.25f
            val fdw = cal.firstDayOfWeek
            val names = java.text.DateFormatSymbols.getInstance().shortWeekdays
            for (i in 0 until 7) grid.addView(Ui.label(a, names[(fdw - 1 + i) % 7 + 1].take(2), 12f, Ui.GREY).apply { gravity = Gravity.CENTER },
                GridLayout.LayoutParams().apply { width = cell; height = d(24) })
            repeat((cal.get(Calendar.DAY_OF_WEEK) - fdw + 7) % 7) { grid.addView(View(a), GridLayout.LayoutParams().apply { width = cell; height = cell }) }
            val r = f.range()
            val today = ClipFilter.dayStart(System.currentTimeMillis())
            for (dn in 1..cal.getActualMaximum(Calendar.DAY_OF_MONTH)) {
                cal.set(Calendar.DAY_OF_MONTH, dn)
                val day = ClipFilter.dayStart(cal.timeInMillis)
                val n = clipDays[day] ?: 0
                val inside = r != null && day >= r.first && day < r.second
                val edge = inside && (day == r!!.first || ClipFilter.addDays(day, 1) >= r.second)
                val tv = Ui.label(a, "$dn", 14f, when { edge -> Color.BLACK; n > 0 -> Color.WHITE; else -> 0xFF5C6067.toInt() }).apply {
                    gravity = Gravity.CENTER
                    if (n > 0) setTypeface(typeface, Typeface.BOLD)
                    background = when {
                        edge -> GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Ui.ACCENT) }
                        inside -> GradientDrawable().apply { cornerRadius = d(8).toFloat(); setColor(0xFF3A3320.toInt()) }
                        day == today -> GradientDrawable().apply { shape = GradientDrawable.OVAL; setStroke(d(1), Ui.GREY) }
                        else -> null
                    }
                    // a dot under days that have clips
                    if (n > 0) {
                        val dot = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (edge) Color.BLACK else Ui.ACCENT); setSize(d(4), d(4)) }
                        setCompoundDrawablesWithIntrinsicBounds(null, null, null, dot)
                        compoundDrawablePadding = -d(2)
                        setPadding(0, d(4), 0, d(4))
                    }
                    contentDescription = "$dn" + if (n > 0) ", $n clip${if (n == 1) "" else "s"}" else ""
                    setOnClickListener { pickDay(day); changed() }
                }
                grid.addView(tv, GridLayout.LayoutParams().apply { width = cell - d(2); height = cell - d(2); setMargins(d(1), d(1), d(1), d(1)) })
            }
        }
        prev.setOnClickListener { if (month > first) { month--; render() } }
        next.setOnClickListener { if (month < last) { month++; render() } }
        repaints += { render() }
        return box
    }

    /** First tap: that day. Second tap: a range to that day. Then a tap starts again. */
    private fun pickDay(day: Long) {
        val fr = f.from; val t = f.to
        if (f.datePreset == null && fr != null && fr == t && day != fr) {
            if (day < fr) { f.from = day; f.to = fr } else f.to = day
        } else { f.datePreset = null; f.from = day; f.to = day }
    }

    // ------------------------------------------------------------------ tags and collections

    private fun tagSection(body: LinearLayout) {
        val sub = header(body, "Tags", segmented(listOf("Any", "All"), { if (f.tagsAll) 1 else 0 }) { f.tagsAll = it == 1 })
        repaints += { setSub(sub, if (f.tagsIn.size > 1) (if (f.tagsAll) "Clips with all the ✓ tags" else "Clips with any ✓ tag") else null) }
        if (tagCounts.isEmpty()) {
            hint(body, "No tags yet. To tag clips, select them (hold a clip) and choose More → Tags…, or use ⋮ while playing.")
            return
        }
        triHint(body)
        var query = ""
        var showAll = false
        if (tagCounts.size > 12) {
            val search = EditText(a).apply {
                hint = "Search ${tagCounts.size} tags"
                setTextColor(Color.WHITE); setHintTextColor(0xFF6A6F76.toInt()); isSingleLine = true
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
                val icon = IconDrawable(IconDrawable.Kind.SEARCH, Ui.GREY).apply { setBounds(0, 0, d(20), d(20)) }
                setCompoundDrawablesRelative(icon, null, null, null); compoundDrawablePadding = d(8)
                setPadding(d(12), d(10), d(12), d(10))
                background = GradientDrawable().apply { cornerRadius = d(12).toFloat(); setColor(0xFF1E2126.toInt()) }
            }
            body.addView(search, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = d(10) })
            search.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, af: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { query = s?.toString()?.trim() ?: ""; changed() }
            })
        }
        val flow = FlowLayout(a)
        body.addView(flow)
        val more = Ui.label(a, "", 14f, Ui.ACCENT).apply { setPadding(0, d(10), 0, d(4)); isClickable = true; setOnClickListener { showAll = !showAll; changed() } }
        body.addView(more)
        repaints += {
            flow.removeAllViews()
            // chosen ones first, then the most used; a search shows every match
            val matching = tagCounts.filter { query.isEmpty() || it.first.contains(query, true) }
            val chosen = matching.filter { it.first in f.tagsIn || it.first in f.tagsOut }
            val rest = matching.filter { it.first !in f.tagsIn && it.first !in f.tagsOut }
            val limit = if (showAll || query.isNotEmpty()) Int.MAX_VALUE else maxOf(0, SHOWN_TAGS - chosen.size)
            for ((tag, n) in chosen + rest.take(limit)) {
                val tv = chip(tag) {
                    when (tag) {
                        in f.tagsIn -> { f.tagsIn.remove(tag); f.tagsOut.add(tag) }
                        in f.tagsOut -> f.tagsOut.remove(tag)
                        else -> f.tagsIn.add(tag)
                    }
                    changed()
                }
                paintTri(tv, when (tag) { in f.tagsIn -> 1; in f.tagsOut -> 2; else -> 0 }, tag, n)
                flow.addView(tv)
            }
            if (matching.isEmpty()) flow.addView(Ui.label(a, "No tag matches \"$query\"", 13f, Ui.GREY))
            val hidden = rest.size - minOf(rest.size, limit)
            more.visibility = if (query.isEmpty() && (hidden > 0 || showAll) && tagCounts.size > SHOWN_TAGS) View.VISIBLE else View.GONE
            more.text = if (showAll) "Show fewer" else "Show all ${tagCounts.size} tags"
        }
    }

    /** How the three states work, until the user says they've got it. */
    private fun triHint(body: LinearLayout) {
        val sp = a.getSharedPreferences("search_ui", 0)
        if (sp.getBoolean("triHintSeen", false)) return
        val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, d(8)) }
        row.addView(Ui.label(a, "Tap once to include ✓, again to exclude ✕, once more to clear.", 12f, 0xFFB0B4BA.toInt()), LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(Ui.label(a, "Got it", 13f, Ui.ACCENT).apply {
            setPadding(d(12), d(6), 0, d(6)); isClickable = true
            setOnClickListener { sp.edit().putBoolean("triHintSeen", true).apply(); row.visibility = View.GONE }
        })
        body.addView(row)
    }

    /** A find box for long lists (tags, collections). */
    private fun findBox(body: LinearLayout, hint: String, onQuery: (String) -> Unit) {
        val search = EditText(a).apply {
            this.hint = hint
            setTextColor(Color.WHITE); setHintTextColor(0xFF6A6F76.toInt()); isSingleLine = true
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
            val icon = IconDrawable(IconDrawable.Kind.SEARCH, Ui.GREY).apply { setBounds(0, 0, d(20), d(20)) }
            setCompoundDrawablesRelative(icon, null, null, null); compoundDrawablePadding = d(8)
            setPadding(d(12), d(10), d(12), d(10))
            background = GradientDrawable().apply { cornerRadius = d(12).toFloat(); setColor(0xFF1E2126.toInt()) }
        }
        body.addView(search, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = d(10) })
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, af: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { onQuery(s?.toString()?.trim() ?: "") }
        })
    }

    private fun colSection(body: LinearLayout) {
        val sub = header(body, "Collections", segmented(listOf("Any", "All"), { if (f.colsAll) 1 else 0 }) { f.colsAll = it == 1 })
        repaints += { setSub(sub, if (f.colsIn.size > 1) (if (f.colsAll) "Clips in all the ✓ collections" else "Clips in any ✓ collection") else null) }
        if (cols.isEmpty()) { hint(body, "No collections here yet."); return }
        // like tags: many collections get a find box; chosen ones first, then the fullest, the rest behind "Show all"
        var query = ""
        var showAll = false
        if (cols.size > 12) findBox(body, "Find among ${cols.size} collections") { query = it; changed() }
        val counts = cols.associate { c -> c.id to clips.count { it.id in c.ids } }
        val ordered = cols.sortedWith(compareBy({ -(counts[it.id] ?: 0) }, { it.name.lowercase() }))
        val flow = FlowLayout(a)
        body.addView(flow)
        val more = Ui.label(a, "", 14f, Ui.ACCENT).apply { setPadding(0, d(10), 0, d(4)); isClickable = true; setOnClickListener { showAll = !showAll; changed() } }
        body.addView(more)
        repaints += {
            flow.removeAllViews()
            val matching = ordered.filter { query.isEmpty() || it.name.contains(query, true) }
            val chosen = matching.filter { it.id in f.colsIn || it.id in f.colsOut }
            val rest = matching.filter { it.id !in f.colsIn && it.id !in f.colsOut }
            val limit = if (showAll || query.isNotEmpty()) Int.MAX_VALUE else maxOf(0, SHOWN_TAGS - chosen.size)
            for (c in chosen + rest.take(limit)) {
                val tv = chip(c.name) {
                    when (c.id) {
                        in f.colsIn -> { f.colsIn.remove(c.id); f.colsOut.add(c.id) }
                        in f.colsOut -> f.colsOut.remove(c.id)
                        else -> f.colsIn.add(c.id)
                    }
                    changed()
                }
                paintTri(tv, when (c.id) { in f.colsIn -> 1; in f.colsOut -> 2; else -> 0 }, c.name, counts[c.id])
                flow.addView(tv)
            }
            if (matching.isEmpty()) flow.addView(Ui.label(a, "No collection matches \"$query\"", 13f, Ui.GREY))
            val hidden = rest.size - minOf(rest.size, limit)
            more.visibility = if (query.isEmpty() && (hidden > 0 || showAll) && cols.size > SHOWN_TAGS) View.VISIBLE else View.GONE
            more.text = if (showAll) "Show fewer" else "Show all ${cols.size} collections"
        }
    }

    // ------------------------------------------------------------------ length and size

    private fun rangeSection(body: LinearLayout, title: String, unit: String, presets: List<Triple<String, Int?, Int?>>,
                             lo: () -> Int?, hi: () -> Int?, set: (Int?, Int?) -> Unit) {
        val sub = header(body, title)
        val fmt: (Int) -> String = if (unit == "seconds") { x -> ClipFilter.secText(x) } else { x -> "$x $unit" }
        repaints += { setSub(sub, if (lo() != null || hi() != null) ClipFilter.rangeText(lo(), hi(), fmt) else null) }
        val flow = FlowLayout(a)
        val minF = numberField("min")
        val maxF = numberField("max")
        var typing = false
        for ((label, a1, b1) in presets) {
            val tv = chip(label) { set(a1, b1); changed() }
            flow.addView(tv)
            repaints += { Ui.setChipState(tv, lo() == a1 && hi() == b1) }
        }
        body.addView(flow)
        val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, d(10), 0, 0) }
        row.addView(Ui.label(a, "From", 14f, 0xFFB0B4BA.toInt()))
        row.addView(minF, LinearLayout.LayoutParams(d(84), -2).apply { setMargins(d(10), 0, d(10), 0) })
        row.addView(Ui.label(a, "to", 14f, 0xFFB0B4BA.toInt()))
        row.addView(maxF, LinearLayout.LayoutParams(d(84), -2).apply { setMargins(d(10), 0, d(10), 0) })
        row.addView(Ui.label(a, unit, 14f, 0xFFB0B4BA.toInt()))
        body.addView(row)
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, af: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (typing) return
                typing = true
                set(minF.text.toString().toIntOrNull(), maxF.text.toString().toIntOrNull())
                changed()
                typing = false
            }
        }
        minF.addTextChangedListener(watcher)
        maxF.addTextChangedListener(watcher)
        repaints += {
            if (!typing) {
                typing = true
                val l = lo()?.toString() ?: ""; val h = hi()?.toString() ?: ""
                if (minF.text.toString() != l) minF.setText(l)
                if (maxF.text.toString() != h) maxF.setText(h)
                typing = false
            }
        }
    }

    // ------------------------------------------------------------------ combining

    private fun combineSection(body: LinearLayout) {
        header(body, "Show clips that match")
        body.addView(segmented(listOf("All filters", "Any filter"), { if (f.matchAny) 1 else 0 }) { f.matchAny = it == 1 })
        val note = Ui.label(a, "", 12f, Ui.GREY).apply { setPadding(0, d(8), 0, 0) }
        body.addView(note)
        repaints += {
            note.text = (if (f.matchAny) "A clip shows if it matches at least one of the filters above (date, tags, collections, length, size)."
                else "A clip shows only if it matches every filter above.") +
                " Excluded (✕) tags and collections always hide clips."
        }
    }
}
