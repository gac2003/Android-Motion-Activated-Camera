package com.motioncam.live

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import com.motioncam.Fmt
import com.motioncam.Prefs
import com.motioncam.Ui
import com.motioncam.topBar
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A camera's events of the last 7 days (motion, clips, monitoring, battery / heat…), filterable. */
class ActivityLogActivity : Activity() {
    private lateinit var cam: String
    private lateinit var f: Form
    private lateinit var chips: LinearLayout
    private var events: List<Pair<Long, JSONObject>> = emptyList()
    private var filter = 0
    private val filters = listOf(
        "All" to emptySet(), "Motion" to setOf("motion"), "Clips" to setOf("clip"),
        "Monitoring" to setOf("monitor_on", "monitor_off", "monitor_paused", "safety_stop", "pin_locked"),
        "Battery & heat" to setOf("battery", "unplugged", "hot"))
    private var listener: ValueEventListener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cam = intent.getStringExtra(Alerts.EXTRA_CAM) ?: run { finish(); return }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.BG) }
        root.addView(topBar(this, "Activity · " + (Live.phones[cam]?.name?.ifEmpty { null } ?: "Camera")))
        chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(Ui.dp(this@ActivityLogActivity, 12), 0, 0, Ui.dp(this@ActivityLogActivity, 6)) }
        root.addView(android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) })
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; val p = Ui.dp(this@ActivityLogActivity, 16); setPadding(p, 0, p, p) }
        root.addView(ScrollView(this).apply { addView(box) }, LinearLayout.LayoutParams(-1, 0, 1f))
        Ui.capWidth(box)
        setContentView(root)
        f = Form(this, box)
        buildChips()
        f.note("Loading…")
    }

    override fun onResume() {
        super.onResume()
        listener = object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                events = s.children.mapNotNull { c ->
                    val t = (c.child("t").value as? Number)?.toLong() ?: return@mapNotNull null
                    val d = LiveNet.open(this@ActivityLogActivity, c.child("d").value as? String) ?: return@mapNotNull null
                    try { Pair(t - Live.serverOffset, JSONObject(d)) } catch (_: Exception) { null }
                }.sortedByDescending { it.first }
                render()
            }
            override fun onCancelled(e: DatabaseError) { f.box.removeAllViews(); f.note("Could not load: ${e.message}") }
        }.also { Live.ref(this, "cams/$cam/events")?.orderByChild("t")?.limitToLast(500)?.addValueEventListener(it) }
    }

    override fun onPause() {
        listener?.let { Live.ref(this, "cams/$cam/events")?.removeEventListener(it) }
        super.onPause()
    }

    private fun buildChips() {
        chips.removeAllViews()
        filters.forEachIndexed { i, (label, _) ->
            chips.addView(Ui.chip(this, label).apply { Ui.setChipState(this, i == filter); setOnClickListener { filter = i; buildChips(); render() } },
                LinearLayout.LayoutParams(-2, -2).apply { rightMargin = Ui.dp(this@ActivityLogActivity, 6) })
        }
    }

    private fun icon(type: String) = when (type) {
        "motion" -> "🏃"; "clip" -> "🎞"; "monitor_on" -> "▶"; "monitor_off", "monitor_paused" -> "⏸"; "safety_stop" -> "⛔"
        "battery" -> "🔋"; "unplugged" -> "🔌"; "hot" -> "🌡"; "pin_locked" -> "🔒"; else -> "•"
    }

    private fun render() {
        f.box.removeAllViews()
        val want = filters[filter].second
        val list = events.filter { want.isEmpty() || it.second.optString("type") in want }
        if (list.isEmpty()) { f.note("Nothing in the last 7 days."); return }
        val prefs = Prefs(this)
        val dayFmt = SimpleDateFormat("EEE, MMM d", Locale.getDefault())
        var lastDay = ""
        for ((t, d) in list) {
            val day = dayFmt.format(Date(t))
            if (day != lastDay) { lastDay = day; f.title(day) }
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, Ui.dp(this@ActivityLogActivity, 4), 0, Ui.dp(this@ActivityLogActivity, 4)) }
            r.addView(Ui.label(this, Fmt.t(prefs, t), 13f, Ui.GREY).apply { minWidth = Ui.dp(this@ActivityLogActivity, 96) })
            r.addView(Ui.label(this, icon(d.optString("type")) + "  " + d.optString("text"), 14f), LinearLayout.LayoutParams(0, -2, 1f))
            f.box.addView(r)
        }
        f.note("Events are kept for 7 days.")
    }
}
