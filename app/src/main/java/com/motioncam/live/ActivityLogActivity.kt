package com.motioncam.live

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
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

/**
 * Camera events of the last 7 days, updating live. With a camera: that camera's events (all kinds). Without: every
 * camera's notable events, newest first, consecutive events of one phone grouped in a card under its name. Motion and
 * the clip it recorded are one entry (the clip under the motion, tap to watch).
 */
class ActivityLogActivity : Activity() {
    private var cam: String? = null
    private lateinit var f: Form
    private lateinit var chips: LinearLayout
    private var single: List<ActivityEvent> = emptyList()
    private var feed: ActivityFeed? = null
    private var filter = 0
    private val filters = listOf(
        "All" to emptySet(), "Motion & clips" to setOf("motion", "clip"),
        "Monitoring" to setOf("monitor_on", "monitor_off", "monitor_paused", "safety_stop", "pin_locked", "remote_taken", "settings_local"),
        "Battery & heat" to setOf("battery", "unplugged", "hot"))
    private var listener: ValueEventListener? = null
    private val phonesChanged: () -> Unit = { feed?.sync() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cam = intent.getStringExtra(Alerts.EXTRA_CAM)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.BG) }
        // a camera's Activity links to the all-cameras one (and back: the all-cameras one opens each camera's)
        root.addView(topBar(this, cam?.let { "Activity · " + (Live.phones[it]?.name?.ifEmpty { null } ?: "Camera") } ?: "Activity · all cameras",
            if (cam != null) Ui.chip(this, "All cameras").apply {
                setOnClickListener {
                    // came from the all-cameras list: just go back to it
                    if (intent.getBooleanExtra(EXTRA_FROM_ALL, false)) finish()
                    else startActivity(Intent(this@ActivityLogActivity, ActivityLogActivity::class.java))
                }
            } else null))
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
        val c = cam
        if (c != null) {
            listener = object : ValueEventListener {
                override fun onDataChange(s: DataSnapshot) {
                    single = s.children.mapNotNull { e ->
                        val t = (e.child("t").value as? Number)?.toLong() ?: return@mapNotNull null
                        val d = LiveNet.open(this@ActivityLogActivity, e.child("d").value as? String) ?: return@mapNotNull null
                        try { ActivityEvent(c, t - Live.serverOffset, JSONObject(d)) } catch (_: Exception) { null }
                    }
                    render()
                }
                override fun onCancelled(e: DatabaseError) { f.box.removeAllViews(); f.note("Could not load: ${e.message}") }
            }.also { Live.ref(this, "cams/$c/events")?.orderByChild("t")?.limitToLast(500)?.addValueEventListener(it) }
        } else {
            Live.viewerActive = true
            Live.refresh(this)
            feed = ActivityFeed(this, 300) { render() }.also { it.sync() }
            Live.listeners += phonesChanged
        }
    }

    override fun onPause() {
        listener?.let { l -> cam?.let { Live.ref(this, "cams/$it/events")?.removeEventListener(l) } }
        listener = null
        Live.listeners -= phonesChanged
        feed?.stop(); feed = null
        super.onPause()
    }

    private fun buildChips() {
        chips.removeAllViews()
        filters.forEachIndexed { i, (label, _) ->
            chips.addView(Ui.chip(this, label).apply { Ui.setChipState(this, i == filter); setOnClickListener { filter = i; buildChips(); render() } },
                LinearLayout.LayoutParams(-2, -2).apply { rightMargin = Ui.dp(this@ActivityLogActivity, 6) })
        }
    }

    private fun render() {
        if (isFinishing) return
        f.box.removeAllViews()
        val want = filters[filter].second
        val all = cam == null
        val base = if (all) feed?.events() ?: emptyList() else ActivityTypes.pair(single.filter { it.type != "clip_up" })
        val list = base.filter { want.isEmpty() || it.type in want }
        if (list.isEmpty()) { f.note(if (all && feed?.loaded != true) "Loading…" else "Nothing in the last 7 days."); return }
        val prefs = Prefs(this)
        val dayFmt = SimpleDateFormat("EEE, MMM d", Locale.getDefault())
        var lastDay = ""
        var block: LinearLayout? = null
        var blockCam: String? = null
        for (e in list) {
            val day = dayFmt.format(Date(e.t))
            if (day != lastDay) { lastDay = day; f.title(day); block = null }
            if (!all) { f.box.addView(eventRow(this, prefs, e, showCam = false, openCamera = false)); continue }
            // all cameras: consecutive events of one phone share a card with its name
            if (block == null || blockCam != e.cam) {
                blockCam = e.cam
                block = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    val p = Ui.dp(this@ActivityLogActivity, 10); setPadding(p, p / 2, p, p / 2)
                    background = GradientDrawable().apply { setColor(0xFF1E2126.toInt()); cornerRadius = Ui.dp(this@ActivityLogActivity, 10).toFloat() }
                }
                // the phone's name heads its card: tap for that phone's own Activity (Back returns here)
                val c = e.cam
                block.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, Ui.dp(this@ActivityLogActivity, 6), 0, Ui.dp(this@ActivityLogActivity, 4))
                    isClickable = true
                    background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x30FFFFFF), null,
                        android.graphics.drawable.ColorDrawable(0xFFFFFFFF.toInt()))
                    contentDescription = "${camName(c)}: open its activity"
                    addView(Ui.label(this@ActivityLogActivity, camName(c), 14f, Ui.ACCENT).apply { setTypeface(typeface, android.graphics.Typeface.BOLD) },
                        LinearLayout.LayoutParams(0, -2, 1f))
                    addView(Ui.label(this@ActivityLogActivity, "Its activity ›", 12f, Ui.GREY))
                    setOnClickListener {
                        startActivity(Intent(this@ActivityLogActivity, ActivityLogActivity::class.java).putExtra(Alerts.EXTRA_CAM, c).putExtra(EXTRA_FROM_ALL, true))
                    }
                })
                f.box.addView(block, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(this@ActivityLogActivity, 8) })
            }
            block.addView(eventRow(this, prefs, e, showCam = false))
        }
        f.note("Events are kept for 7 days." + if (all) " Settings changes and who changed a camera are in each camera's own Activity." else "")
    }

    private fun camName(c: String) = Live.phones[c]?.name?.ifEmpty { null } ?: "Camera"

    companion object {
        private const val EXTRA_FROM_ALL = "fromAll"

        /**
         * One event as a row: time, icon and text; a motion with its clip gets the clip on a second line (tap to watch).
         * Tapping opens the clip when there is one, otherwise the camera. [showCam]: the camera's name in the row.
         */
        fun eventRow(a: Activity, prefs: Prefs, e: ActivityEvent, showCam: Boolean, openCamera: Boolean = true): View {
            val ctx: Context = a
            val r = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, Ui.dp(ctx, 5), 0, Ui.dp(ctx, 5))
                isClickable = true
                background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x30FFFFFF), null,
                    android.graphics.drawable.ColorDrawable(0xFFFFFFFF.toInt()))
            }
            r.addView(Ui.label(ctx, Fmt.t(prefs, e.t), 13f, Ui.GREY).apply { minWidth = Ui.dp(ctx, 84) })
            val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            val main = (if (e.type == "motion") "Motion detected" else e.d.optString("text")) +
                if (showCam) "  ·  " + (Live.phones[e.cam]?.name?.ifEmpty { null } ?: "Camera") else ""
            texts.addView(Ui.label(ctx, ActivityTypes.icon(e.type) + "  " + main, 14f,
                if (e.type in setOf("hot", "safety_stop", "pin_locked")) Ui.RED else 0xFFFFFFFF.toInt()))
            val clip = e.clip ?: e.d.takeIf { e.type == "clip" }
            val ref = clip?.let { Alerts.clipRef(it) }
            if (e.clip != null) texts.addView(Ui.label(ctx, "    🎞  " + e.clip!!.optString("text") + if (ref != null) "  ·  ▶ Watch" else "", 13f,
                if (ref != null) Ui.ACCENT else Ui.GREY))
            else if (e.type == "clip" && ref != null) texts.addView(Ui.label(ctx, "    ▶ Watch", 13f, Ui.ACCENT))
            r.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
            r.setOnClickListener {
                if (ref != null) OpenClipActivity.open(a, e.cam, ref.toString())
                else if (openCamera) a.startActivity(Intent(a, RemoteCameraActivity::class.java).putExtra(Alerts.EXTRA_CAM, e.cam))
            }
            return r
        }
    }
}
