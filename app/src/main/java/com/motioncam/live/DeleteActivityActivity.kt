package com.motioncam.live

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import com.motioncam.Ui
import com.motioncam.cloud.Device
import com.motioncam.topBar
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Deletes activity precisely: when (last hour … all time, or a range of days), from which phones and which kinds. The
 * events are shared, so they are gone from every phone; clips are never touched. Counts come from each camera's per-day
 * summary (about), or the events themselves for the last hour.
 */
class DeleteActivityActivity : Activity() {
    private lateinit var box: LinearLayout
    private var range = "week"
    private val phones = LinkedHashSet<String>()
    private val kinds = HashSet<String>()   // empty = all kinds
    private var customFrom = 0L
    private var customTo = 0L
    /** Per phone: day (yyyyMMdd) → letter → count. */
    private val summaries = HashMap<String, Map<String, Map<String, Long>>>()
    private var hourCount = HashMap<String, Int>()
    private var busy = false
    private var doneText: String? = null

    private val RANGES = listOf("hour" to "Last hour", "today" to "Today", "week" to "Last 7 days", "month" to "Last 30 days",
        "old" to "Older than 1 year", "all" to "All time", "custom" to "Custom range…")
    private val KINDS = listOf("m" to "Motion & clips", "o" to "Monitoring", "b" to "Battery & heat", "s" to "Settings changes")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.BG) }
        root.addView(topBar(this, "Delete activity"))
        box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(d(16), 0, d(16), d(24)) }
        root.addView(ScrollView(this).apply { addView(box) }, LinearLayout.LayoutParams(-1, 0, 1f))
        Ui.capWidth(box)
        setContentView(root)
        val today = ActivityLogActivity.startOfDay(System.currentTimeMillis())
        customFrom = today - 2 * 86_400_000L; customTo = today
        val ids = allIds()
        intent.getStringExtra(Alerts.EXTRA_CAM)?.let { phones += it } ?: phones.addAll(ids)
        for (id in ids) load(id)
        render()
    }

    private fun d(v: Int) = Ui.dp(this, v)

    /** Every phone whose activity may be stored (this one too, if it was a camera). */
    private fun allIds(): List<String> = (Live.phones.keys + Live.cams.keys + Device.id(this)).distinct()
        .sortedWith(compareBy({ it == Device.id(this) }, { name(it).lowercase() }))

    private fun name(id: String) = (Live.phones[id]?.name?.ifEmpty { null } ?: "Phone") + if (id == Device.id(this)) " (this phone)" else ""

    private fun load(id: String) {
        Live.ref(this, "cams/$id/days")?.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                summaries[id] = s.children.associate { day ->
                    (day.key ?: "") to day.children.associate { (it.key ?: "") to ((it.value as? Number)?.toLong() ?: 0L) }
                }
                render()
            }
            override fun onCancelled(e: DatabaseError) {}
        })
        // the last hour can't be told from the per-day summary: count those events
        Live.ref(this, "cams/$id/events")?.orderByChild("t")?.startAt((Live.now() - 3_600_000L).toDouble())
            ?.addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(s: DataSnapshot) {
                    hourCount[id] = s.children.count { c -> letterOf(c)?.let { kinds.isEmpty() || it in kinds } == true }
                    render()
                }
                override fun onCancelled(e: DatabaseError) {}
            })
    }

    /** The kind letter of a stored event (clips go with motion), or null if it can't be read. */
    private fun letterOf(c: DataSnapshot): String? = read(c)?.first

    /**
     * (kind letter for choosing, letter counted in the per-day summary or null, how many it counted there). Clips aren't
     * in the summary; a busy hour counted each motion after the 20th.
     */
    private fun read(c: DataSnapshot): Triple<String, String?, Long>? {
        val dd = LiveNet.open(this, c.child("d").value as? String) ?: return null
        val o = try { JSONObject(dd) } catch (_: Exception) { return null }
        return when (val type = o.optString("type")) {
            "clip", "clip_up" -> Triple("m", null, 0L)
            "motion_burst" -> Triple("m", "m", (o.optInt("n") - CameraLink.BURST_AFTER).coerceAtLeast(0).toLong())
            else -> CameraLink.kindLetter(type)?.let { Triple(it, it, 1L) } ?: Triple("o", null, 0L)
        }
    }

    /** The chosen period, local time [from, to]. */
    private fun period(): Pair<Long, Long> {
        val now = System.currentTimeMillis()
        val today = ActivityLogActivity.startOfDay(now)
        return when (range) {
            "hour" -> now - 3_600_000L to now + 60_000L
            "today" -> today to now + 60_000L
            "week" -> today - 6 * 86_400_000L to now + 60_000L
            "month" -> today - 29 * 86_400_000L to now + 60_000L
            "old" -> 0L to ActivityLogActivity.startOfDay(today - 365 * 86_400_000L) - 1
            "all" -> 0L to now + 3_600_000L
            else -> ActivityLogActivity.startOfDay(customFrom) to ActivityLogActivity.startOfDay(customTo) + 86_400_000L - 1
        }
    }

    private val keyFmt get() = SimpleDateFormat("yyyyMMdd", Locale.US)

    /** About how many entries the choice deletes. */
    private fun count(): Long {
        if (range == "hour") return phones.sumOf { (hourCount[it] ?: 0).toLong() }
        val (from, to) = period()
        val a = keyFmt.format(Date(from)); val b = keyFmt.format(Date(to))
        var n = 0L
        for (id in phones) for ((day, m) in summaries[id] ?: continue) {
            if (day < a || day > b) continue
            for ((l, v) in m) if (l != "n" && (kinds.isEmpty() || l in kinds)) n += v
        }
        return n
    }

    private fun sayRange(): String = when (range) {
        "hour" -> "from the last hour"; "today" -> "from today"; "week" -> "from the last 7 days"; "month" -> "from the last 30 days"
        "old" -> "older than 1 year"; "all" -> "from all time"
        else -> "from " + SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(customFrom)) + " to " +
            SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(customTo))
    }

    private fun sayPhones(): String = when {
        phones.size == allIds().size -> "every phone"
        phones.isEmpty() -> "no phone"
        else -> phones.joinToString { name(it) }
    }

    private fun sayKinds(): String = if (kinds.isEmpty()) "all kinds" else KINDS.filter { it.first in kinds }.joinToString(" or ") { it.second }

    // ------------------------------------------------------------------ screen

    private fun title(s: String) = box.addView(Ui.label(this, s, 12f, Ui.GREY).apply {
        setTypeface(typeface, Typeface.BOLD); letterSpacing = 0.06f; setPadding(d(4), d(18), 0, d(8))
    })

    private fun chip(label: String, on: Boolean, check: Boolean, click: () -> Unit): TextView = Ui.label(this, (if (on && check) "✓ " else "") + label, 13f).apply {
        setPadding(d(12), d(8), d(12), d(8))
        setTypeface(typeface, if (on) Typeface.BOLD else Typeface.NORMAL)
        setTextColor(if (on) 0xFF15171B.toInt() else Color.WHITE)
        background = GradientDrawable().apply {
            cornerRadius = d(18).toFloat()
            if (on) setColor(Ui.ACCENT) else { setColor(0); setStroke(d(1), 0xFF3A3E45.toInt()) }
        }
        isClickable = true
        setOnClickListener { if (!busy) click() }
    }

    private fun wrapRow(views: List<View>) {
        // a simple wrapping row (chips flow onto the next line when they don't fit)
        val w = Ui.widthDp(this).coerceAtMost(640f) - 32
        var line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        var used = 0f
        box.addView(line)
        for (v in views) {
            v.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            val vw = v.measuredWidth / resources.displayMetrics.density + 6
            if (used > 0 && used + vw > w) { line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }; box.addView(line); used = 0f }
            line.addView(v, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = d(6); bottomMargin = d(6) })
            used += vw
        }
    }

    private fun render() {
        if (isFinishing) return
        box.removeAllViews()
        title("WHEN")
        wrapRow(RANGES.map { (id, label) -> chip(label, range == id, false) { range = id; doneText = null; render() } })
        if (range == "custom") {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            fun dateBtn(lead: String, ms: Long, set: (Long) -> Unit) = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(d(12), d(8), d(12), d(8))
                background = GradientDrawable().apply { setColor(0xFF1E2126.toInt()); cornerRadius = d(12).toFloat(); setStroke(d(1), 0xFF3A3E45.toInt()) }
                addView(Ui.label(this@DeleteActivityActivity, lead, 11f, Ui.GREY))
                addView(Ui.label(this@DeleteActivityActivity, SimpleDateFormat("EEE d MMM yyyy", Locale.getDefault()).format(Date(ms)), 14f))
                isClickable = true
                setOnClickListener {
                    val c = Calendar.getInstance().apply { timeInMillis = ms }
                    DatePickerDialog(this@DeleteActivityActivity, { _, y, mo, dd ->
                        set(Calendar.getInstance().apply { set(y, mo, dd, 0, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis); render()
                    }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
                }
            }
            row.addView(dateBtn("From", customFrom) { customFrom = it; if (customTo < it) customTo = it }, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = d(4) })
            row.addView(dateBtn("To", customTo) { customTo = it; if (customFrom > it) customFrom = it }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = d(4) })
            box.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = d(4) })
        }

        title("FROM WHICH PHONES")
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(0xFF1E2126.toInt()); cornerRadius = d(14).toFloat() }
        }
        val ids = allIds()
        fun check(label: String, on: Boolean, bold: Boolean, first: Boolean, click: () -> Unit) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(d(14), d(11), d(14), d(11))
            isClickable = true
            contentDescription = label + if (on) ", selected" else ""
            setOnClickListener { if (!busy) click() }
            addView(Ui.label(this@DeleteActivityActivity, if (on) "✓" else "", 14f, 0xFF15171B.toInt()).apply {
                gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD)
                background = GradientDrawable().apply { cornerRadius = d(6).toFloat(); if (on) setColor(Ui.ACCENT) else { setColor(0); setStroke(d(2), Ui.GREY) } }
            }, LinearLayout.LayoutParams(d(22), d(22)))
            addView(Ui.label(this@DeleteActivityActivity, label, 15f).apply { setPadding(d(12), 0, 0, 0); if (bold) setTypeface(typeface, Typeface.BOLD) })
            if (!first) background = null
        }
        val allOn = phones.size == ids.size
        list.addView(check("All phones", allOn, true, true) { if (allOn) phones.clear() else phones.addAll(ids); doneText = null; render() })
        for (id in ids) {
            list.addView(View(this).apply { setBackgroundColor(0xFF2A2D33.toInt()) }, LinearLayout.LayoutParams(-1, d(1)))
            val on = id in phones
            list.addView(check(name(id), on, false, false) { if (on) phones -= id else phones += id; doneText = null; render() })
        }
        box.addView(list)

        title("WHICH KINDS")
        wrapRow(listOf(chip("All kinds", kinds.isEmpty(), false) { kinds.clear(); doneText = null; reloadHour(); render() }) +
            KINDS.map { (id, label) -> chip(label, id in kinds, true) { if (id in kinds) kinds -= id else kinds += id; doneText = null; reloadHour(); render() } })

        val n = count()
        val loading = summaries.size < ids.size
        val sum = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d(14), d(12), d(14), d(12))
            background = GradientDrawable().apply { setColor(0xFF1E2126.toInt()); cornerRadius = d(14).toFloat() }
        }
        sum.addView(Ui.label(this, "", 15f).apply {
            text = android.text.SpannableStringBuilder().apply {
                val s = length
                append(if (loading) "Counting…" else (if (range == "hour") "" else "About ") + "%,d".format(n) + if (n == 1L) " event" else " events")
                setSpan(android.text.style.ForegroundColorSpan(0xFFFF8A85.toInt()), s, length, 0)
                setSpan(android.text.style.StyleSpan(Typeface.BOLD), s, length, 0)
                append(" " + sayRange() + " · " + sayPhones() + " · " + sayKinds())
            }
        })
        sum.addView(Ui.label(this, "Gone from every phone, as these events are shared. Clips aren't touched: delete those in Clips.", 12f, Ui.GREY)
            .apply { setPadding(0, d(4), 0, 0) })
        box.addView(sum, LinearLayout.LayoutParams(-1, -2).apply { topMargin = d(22) })

        val none = phones.isEmpty() || (!loading && n == 0L)
        box.addView(Ui.label(this, when { busy -> "Deleting…"; none -> "Nothing to delete"; else -> "Delete " + (if (range == "hour") "" else "about ") + "%,d".format(n) + if (n == 1L) " event" else " events" }, 15f).apply {
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, d(14), 0, d(14))
            setTextColor(if (none || busy) Ui.GREY else Color.WHITE)
            background = GradientDrawable().apply { cornerRadius = d(26).toFloat(); setColor(if (none || busy) 0xFF3A3E45.toInt() else Ui.RED) }
            isClickable = !none && !busy
            if (!none && !busy) setOnClickListener { confirm(n) }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = d(12) })
        doneText?.let { box.addView(Ui.label(this, it, 13f, 0xFF81C784.toInt()).apply { gravity = Gravity.CENTER; setPadding(0, d(10), 0, 0) }, LinearLayout.LayoutParams(-1, -2)) }
    }

    private fun reloadHour() { if (range == "hour") for (id in allIds()) load(id) }

    private fun confirm(n: Long) {
        AlertDialog.Builder(this).setTitle("Delete " + (if (range == "hour") "" else "about ") + "%,d".format(n) + " events?")
            .setMessage("Activity ${sayRange()} on ${sayPhones()} (${sayKinds()}) is removed from every phone. This can't be undone.")
            .setPositiveButton("Delete") { _, _ -> run() }
            .setNegativeButton("Cancel", null).show()
    }

    // ------------------------------------------------------------------ deleting

    private fun run() {
        busy = true
        render()
        val (from, to) = period()
        val todo = phones.toList()
        var left = todo.size
        var total = 0
        for (id in todo) deletePhone(id, from, to) { n ->
            total += n
            if (--left == 0) {
                busy = false
                ActivityLogActivity.version++
                doneText = "Deleted " + "%,d".format(total) + if (total == 1) " event." else " events."
                Toast.makeText(this, doneText, Toast.LENGTH_SHORT).show()
                summaries.clear(); hourCount.clear()
                for (i in allIds()) load(i)
                render()
            }
        }
    }

    private fun deletePhone(id: String, from: Long, to: Long, done: (Int) -> Unit) {
        val events = Live.ref(this, "cams/$id/events") ?: run { done(0); return }
        // everything of a phone: one removal (and its calendar)
        if (range == "all" && kinds.isEmpty()) {
            val n = summaries[id]?.values?.sumOf { m -> m.filterKeys { it != "n" }.values.sum() }?.toInt() ?: 0
            events.removeValue()
            Live.ref(this, "cams/$id/days")?.removeValue()
            done(n); return
        }
        val off = Live.serverOffset
        var deleted = 0
        val dec = HashMap<String, HashMap<String, Long>>()
        fun page(start: Long) {
            events.orderByChild("t").startAt((start).toDouble()).endAt((to + off).toDouble()).limitToFirst(500)
                .addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(s: DataSnapshot) {
                        val m = HashMap<String, Any?>()
                        var last = start
                        for (c in s.children) {
                            val t = (c.child("t").value as? Number)?.toLong() ?: continue
                            last = maxOf(last, t)
                            val (l, sl, sn) = read(c) ?: continue
                            if (kinds.isNotEmpty() && l !in kinds) continue
                            c.key?.let { m[it] = null }
                            if (sl != null && sn > 0) {
                                val day = keyFmt.format(Date(t - off))
                                dec.getOrPut(day) { HashMap() }.let { it[sl] = (it[sl] ?: 0) + sn }
                            }
                        }
                        if (m.isNotEmpty()) events.updateChildren(m)
                        deleted += m.size
                        if (s.childrenCount >= 500) page(last + 1)
                        else {
                            // the calendar's per-day counts follow
                            for ((day, ls) in dec) {
                                val u = HashMap<String, Any>()
                                for ((l, v) in ls) u[l] = ServerValue.increment(-v)
                                u["n"] = ServerValue.increment(-ls.values.sum())
                                Live.ref(this@DeleteActivityActivity, "cams/$id/days/$day")?.updateChildren(u)
                            }
                            done(deleted)
                        }
                    }
                    override fun onCancelled(e: DatabaseError) { done(deleted) }
                })
        }
        page(from + off)
    }
}
