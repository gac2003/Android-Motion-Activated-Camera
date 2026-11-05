package com.motioncam.live

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import com.motioncam.Fmt
import com.motioncam.IconDrawable
import com.motioncam.Prefs
import com.motioncam.Ui
import com.motioncam.cloud.Device
import com.motioncam.topBar
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Activity: what happened at your cameras, newest first, grouped by day and by phone (the Activity tab), or one
 * camera's own (with [Alerts.EXTRA_CAM]). History is kept as long as "Keep activity" says (forever by default) and
 * loads a few days at a time as you scroll. Filters can be combined (an entry shows if it matches any of them); the
 * calendar jumps to a day; ⋮ deletes activity precisely.
 */
open class ActivityLogActivity : Activity() {
    /** Shown as the Activity tab (big title, no back arrow). */
    protected open val asTab = false
    private var cam: String? = null
    private lateinit var prefs: Prefs
    private lateinit var box: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var chips: LinearLayout
    private lateinit var info: LinearLayout
    private val ui = Handler(Looper.getMainLooper())
    private val filters = HashSet<String>()
    /** Showing from the end of this day back (server time); 0 = from now, updating live. */
    private var jumpEnd = 0L
    private var jumpLabel = ""
    private val sources = LinkedHashMap<String, Source>()
    private val phonesChanged: () -> Unit = { syncSources(); renderSoon() }
    private var seenVersion = -1

    /** One camera's events: the last days live, then older pages as the list is scrolled. */
    private inner class Source(val cam: String) {
        var live: List<ActivityEvent> = emptyList()
        val older = ArrayList<ActivityEvent>()
        /** Oldest server time loaded (pages continue before it). */
        var cursor = Long.MAX_VALUE
        var done = false
        var loading = false
        var liveL: ValueEventListener? = null
        var liveFrom = 0L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        cam = intent.getStringExtra(Alerts.EXTRA_CAM)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.BG) }
        val calBtn = headButton(IconDrawable.Kind.CALENDAR, "Go to a day") { openCalendar() }
        lateinit var moreBtn: View
        moreBtn = headButton(IconDrawable.Kind.MORE, "More") { menu(moreBtn) }
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(calBtn)
            addView(moreBtn, LinearLayout.LayoutParams(d(44), d(44)).apply { leftMargin = d(8) })
        }
        val land = asTab && com.motioncam.Nav.landscape(this)
        chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(d(if (land) 4 else 16), d(4), d(16), d(4)) }
        val chipScroll = android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) }
        if (land) {
            // landscape: title, filters and buttons share one row
            val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(d(16), d(4), d(12), d(2)) }
            head.addView(Ui.label(this, "Activity", 20f).apply { setTypeface(typeface, Typeface.BOLD); setPadding(0, 0, d(8), 0) })
            head.addView(chipScroll, LinearLayout.LayoutParams(0, -2, 1f))
            head.addView(actions)
            root.addView(head)
        } else if (asTab) {
            val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(d(16), d(16), d(16), d(6)) }
            head.addView(Ui.label(this, "Activity", 26f).apply { setTypeface(typeface, Typeface.BOLD) }, LinearLayout.LayoutParams(0, -2, 1f))
            head.addView(actions)
            root.addView(head)
        } else {
            root.addView(topBar(this, (cam?.let { Live.phones[it]?.name?.ifEmpty { null } ?: "Camera" } ?: "All cameras") + " · activity", actions))
        }
        if (!land) root.addView(chipScroll)
        info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(d(16), 0, d(16), 0) }
        root.addView(info)
        box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(d(16), 0, d(16), d(24)) }
        scroll = ScrollView(this).apply {
            addView(box)
            setOnScrollChangeListener { v, _, y, _, _ ->
                val child = (v as ScrollView).getChildAt(0) ?: return@setOnScrollChangeListener
                if (y + v.height >= child.height - d(600)) loadMore()
            }
        }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        if (!land) Ui.capWidth(box)   // landscape uses the width for two columns
        setContentView(root)
        buildChips()
        KeepActivity.load(this)
    }

    private fun d(v: Int) = Ui.dp(this, v)

    private fun headButton(kind: IconDrawable.Kind, name: String, click: () -> Unit): View = FrameLayout(this).apply {
        background = GradientDrawable().apply { setColor(0xFF1E2126.toInt()); cornerRadius = d(12).toFloat(); setStroke(d(1), 0xFF3A3E45.toInt()) }
        addView(ImageView(this@ActivityLogActivity).apply { setImageDrawable(IconDrawable(kind)) }, FrameLayout.LayoutParams(d(22), d(22), Gravity.CENTER))
        isClickable = true
        contentDescription = name
        tooltipText = name
        setOnClickListener { click() }
        layoutParams = LinearLayout.LayoutParams(d(44), d(44))
    }

    /** Entries after this are marked new (when activity was last looked at, before this visit). */
    private var newSince = Long.MAX_VALUE
    /** Per camera, when its activity was last looked at (before this visit): its entries after that are marked new. */
    private val seenBefore = HashMap<String, Long>()

    override fun onResume() {
        super.onResume()
        newSince = AlertInbox.seenAt(this, cam)
        seenBefore.clear()
        for (c in (Live.phones.keys + Live.cams.keys + listOfNotNull(cam))) seenBefore[c] = AlertInbox.seenAt(this, c)
        AlertInbox.markSeen(this, cam)
        if (cam == null) { Live.viewerActive = true; Live.refresh(this) }
        // activity deleted meanwhile: start again from the top
        if (seenVersion != version) { seenVersion = version; resetSources() }
        syncSources()
        Live.listeners += phonesChanged
        render()
    }

    override fun onPause() {
        Live.listeners -= phonesChanged
        for (s in sources.values) detachLive(s)
        super.onPause()
    }

    // ------------------------------------------------------------------ loading

    /** The cameras whose activity this shows. */
    private fun camIds(): List<String> {
        cam?.let { return listOf(it) }
        val me = Device.id(this)
        return (Live.phones.keys + Live.cams.keys).filter { it != me }.distinct()
    }

    private fun resetSources() {
        for (s in sources.values) detachLive(s)
        sources.clear()
    }

    private fun syncSources() {
        for (c in camIds()) {
            val s = sources.getOrPut(c) { Source(c) }
            if (jumpEnd == 0L && s.liveL == null) attachLive(s)
            if (s.cursor == Long.MAX_VALUE && !s.loading && !s.done) {
                s.cursor = if (jumpEnd > 0) jumpEnd + 1 else s.liveFrom
                loadPage(s)
            }
        }
    }

    private fun attachLive(s: Source) {
        if (s.liveFrom == 0L) s.liveFrom = Live.now() - LIVE_DAYS * 86_400_000L
        val l = object : ValueEventListener {
            override fun onDataChange(snap: DataSnapshot) {
                s.live = parse(s.cam, snap)
                s.live.filter { it.type in ActivityTypes.OVERVIEW && it.type != "clip" }.map { it.t }.let { AlertInbox.noteEvents(this@ActivityLogActivity, s.cam, it) }
                renderSoon()
            }
            override fun onCancelled(e: DatabaseError) {}
        }
        s.liveL = l
        Live.ref(this, "cams/${s.cam}/events")?.orderByChild("t")?.startAt(s.liveFrom.toDouble())?.addValueEventListener(l)
    }

    private fun detachLive(s: Source) {
        s.liveL?.let { Live.ref(this, "cams/${s.cam}/events")?.removeEventListener(it) }
        s.liveL = null
    }

    private fun parse(c: String, snap: DataSnapshot): List<ActivityEvent> = snap.children.mapNotNull { e ->
        val t = (e.child("t").value as? Number)?.toLong() ?: return@mapNotNull null
        val dd = LiveNet.open(this, e.child("d").value as? String) ?: return@mapNotNull null
        try { ActivityEvent(c, t - Live.serverOffset, JSONObject(dd), e.key ?: "") } catch (_: Exception) { null }
    }

    /** The next older page of one camera. */
    private fun loadPage(s: Source) {
        if (s.loading || s.done) return
        val q = Live.ref(this, "cams/${s.cam}/events")?.orderByChild("t")?.endAt((s.cursor - 1).toDouble())?.limitToLast(PAGE)
        if (q == null) { s.done = true; return }
        s.loading = true
        q.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snap: DataSnapshot) {
                s.loading = false
                val list = parse(s.cam, snap)
                val serverTimes = snap.children.mapNotNull { (it.child("t").value as? Number)?.toLong() }
                if (serverTimes.isEmpty()) s.done = true
                else { s.cursor = serverTimes.minOrNull() ?: s.cursor; if (snap.childrenCount < PAGE) s.done = true }
                s.older.addAll(list)
                renderSoon()
            }
            override fun onCancelled(e: DatabaseError) { s.loading = false; s.done = true; renderSoon() }
        })
    }

    /** Scrolled near the end: the next page of every camera whose loaded history ends the soonest. */
    private fun loadMore() {
        val f = frontier()
        for (s in sources.values) if (!s.done && !s.loading && frontierOf(s) >= f) loadPage(s)
    }

    /** Entries older than this may still be missing (a camera's history isn't loaded that far yet). */
    private fun frontier(): Long = sources.values.filter { !it.done }.maxOfOrNull { frontierOf(it) } ?: Long.MIN_VALUE
    private fun frontierOf(s: Source): Long = if (s.cursor == Long.MAX_VALUE) Long.MAX_VALUE else s.cursor - Live.serverOffset

    // ------------------------------------------------------------------ filters, calendar, menu

    private fun buildChips() {
        chips.removeAllViews()
        fun chip(label: String, on: Boolean, click: () -> Unit) = Ui.label(this, (if (on && label != "All") "✓ " else "") + label, 13f).apply {
            setPadding(d(12), d(7), d(12), d(7))
            setTypeface(typeface, if (on) Typeface.BOLD else Typeface.NORMAL)
            setTextColor(if (on) 0xFF15171B.toInt() else Color.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = d(18).toFloat()
                if (on) setColor(Ui.ACCENT) else { setColor(0); setStroke(d(1), 0xFF3A3E45.toInt()) }
            }
            isClickable = true
            contentDescription = label + if (on) ", on" else ""
            setOnClickListener { click() }
        }
        chips.addView(chip("All", filters.isEmpty()) { filters.clear(); buildChips(); render() }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = d(6) })
        for ((id, label, _) in ActivityTypes.FILTERS) {
            val on = id in filters
            chips.addView(chip(label, on) { if (on) filters -= id else filters += id; buildChips(); render() },
                LinearLayout.LayoutParams(-2, -2).apply { rightMargin = d(6) })
        }
    }

    private fun menu(anchor: View) {
        val pm = PopupMenu(this, anchor)
        pm.menu.add(0, 1, 0, "Delete activity…")
        pm.menu.add(0, 2, 1, "Keep activity: " + KeepActivity.label(KeepActivity.cached))
        pm.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> startActivity(Intent(this, DeleteActivityActivity::class.java).apply { cam?.let { c -> putExtra(Alerts.EXTRA_CAM, c) } })
                2 -> startActivity(Intent(this, AlertSettingsActivity::class.java))
            }
            true
        }
        pm.show()
    }

    private fun jumpTo(dayStartLocal: Long) {
        val c = Calendar.getInstance().apply { timeInMillis = dayStartLocal; add(Calendar.DAY_OF_MONTH, 1) }
        if (dayStartLocal >= startOfDay(System.currentTimeMillis())) { backToNow(); return }
        jumpEnd = c.timeInMillis - 1 + Live.serverOffset
        jumpLabel = dayTitle(dayStartLocal, lower = true)
        resetSources(); syncSources(); scroll.scrollTo(0, 0); render()
    }

    private fun backToNow() { jumpEnd = 0L; resetSources(); syncSources(); scroll.scrollTo(0, 0); render() }

    private fun openCalendar() {
        CalendarSheet(this, camIds(), filters.flatMap { ActivityTypes.letters(it) }.toSet(), cam != null, filterText()) { day -> jumpTo(day) }.show()
    }

    private fun filterText(): String = ActivityTypes.FILTERS.filter { it.first in filters }.joinToString(" or ") { it.second }

    // ------------------------------------------------------------------ list

    private var pending = false
    private fun renderSoon() {
        if (pending) return
        pending = true
        ui.postDelayed({ pending = false; if (!isFinishing) render() }, 120)
    }

    private fun render() {
        if (isFinishing) return
        AlertInbox.markSeen(this, cam)   // anything arriving while this is open is seen too
        info.removeAllViews()
        if (filters.isNotEmpty()) info.addView(infoLine("Showing ", filterText(), "", null) {})
        if (jumpEnd > 0) info.addView(infoLine("From ", jumpLabel, " back", "Back to today") { backToNow() })
        box.removeAllViews()
        val all = cam == null
        val want = ActivityTypes.FILTERS.filter { it.first in filters }.flatMap { it.third }.toSet()
        val front = frontier()
        val raw = sources.values.flatMap { s -> (if (jumpEnd == 0L) s.live else emptyList()) + s.older }
            .distinctBy { it.cam + "/" + it.key + "/" + it.t }
            .filter { it.type != "clip_up" && (!all || it.type in ActivityTypes.OVERVIEW) }
        val list = ActivityTypes.pair(raw).filter { (want.isEmpty() || it.type in want) && it.t >= front }
        val loading = sources.values.any { it.loading } || (sources.isEmpty() && !Live.devicesLoaded)
        if (list.isEmpty()) {
            box.addView(Ui.label(this, if (!loading && sources.values.all { it.done })
                (if (filters.isEmpty()) "No activity yet." else "Nothing of these kinds here.") else "Loading…", 14f, Ui.GREY).apply {
                gravity = Gravity.CENTER; setPadding(d(8), d(28), d(8), d(28))
            }, LinearLayout.LayoutParams(-1, -2))
            if (!loading) loadMore()
            return
        }
        var lastDay = -1L
        var block: LinearLayout? = null
        var blockCam: String? = null
        // landscape: two events side by side in each card (newest first, left to right)
        val twoCols = com.motioncam.Nav.landscape(this)
        var pair: LinearLayout? = null
        for (e in list) {
            val day = startOfDay(e.t)
            if (day != lastDay) {
                lastDay = day
                box.addView(Ui.label(this, dayTitle(day), 13f, Ui.ACCENT).apply {
                    setTypeface(typeface, Typeface.BOLD); letterSpacing = 0.06f; setPadding(0, d(16), 0, d(2))
                })
                block = null
            }
            if (block == null || blockCam != e.cam) {
                pair = null
                blockCam = e.cam
                val b = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(d(12), d(4), d(12), d(4))
                    background = GradientDrawable().apply { setColor(0xFF1E2126.toInt()); cornerRadius = d(14).toFloat() }
                }
                if (all) b.addView(phoneHeader(e.cam))
                box.addView(b, LinearLayout.LayoutParams(-1, -2).apply { topMargin = d(8) })
                block = b
            }
            val row = eventRow(this, prefs, e, showCam = false, openCamera = all, newSince = newSince, seenFor = { c -> seenBefore[c] ?: newSince })
            if (!twoCols) block.addView(row)
            else {
                val p = pair?.takeIf { it.childCount < 2 } ?: LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; weightSum = 2f }.also { block.addView(it); pair = it }
                p.addView(row, LinearLayout.LayoutParams(0, -2, 1f).apply { if (p.childCount == 1) leftMargin = d(16) })
            }
        }
        val more = sources.values.any { !it.done }
        box.addView(Ui.label(this, when {
            more && loading -> "Loading older activity…"
            more -> "Load older activity"
            else -> "That's everything" + if (KeepActivity.cached > 0) " kept (" + KeepActivity.label(KeepActivity.cached).lowercase() + ")." else "."
        }, 13f, if (more && !loading) Ui.ACCENT else Ui.GREY).apply {
            gravity = Gravity.CENTER
            setPadding(d(8), d(20), d(8), d(8))
            if (more && !loading) { isClickable = true; setOnClickListener { loadMore() } }
        }, LinearLayout.LayoutParams(-1, -2))
        // the list doesn't fill the screen yet: load further back right away
        if (more && !loading) scroll.post { val ch = scroll.getChildAt(0); if (ch != null && ch.height <= scroll.height + d(200)) loadMore() }
    }

    /** The phone's name heads its card: tap for that phone's own Activity (Back returns here). */
    private fun phoneHeader(c: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, d(8), 0, d(4))
        isClickable = true
        background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x30FFFFFF), null,
            android.graphics.drawable.ColorDrawable(0xFFFFFFFF.toInt()))
        contentDescription = "${camName(c)}: open its activity"
        addView(Ui.label(this@ActivityLogActivity, camName(c), 14f, Ui.ACCENT).apply { setTypeface(typeface, Typeface.BOLD) },
            LinearLayout.LayoutParams(0, -2, 1f))
        addView(Ui.label(this@ActivityLogActivity, "Its activity ›", 12f, Ui.GREY))
        setOnClickListener {
            startActivity(Intent(this@ActivityLogActivity, ActivityLogActivity::class.java).putExtra(Alerts.EXTRA_CAM, c).putExtra(EXTRA_FROM_ALL, true))
        }
    }

    private fun infoLine(lead: String, bold: String, tail: String, action: String?, click: () -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, d(2), 0, d(2))
        addView(Ui.label(this@ActivityLogActivity, "", 12f, 0xFFB0B4BA.toInt()).apply {
            text = android.text.SpannableStringBuilder(lead).apply {
                val s = length; append(bold)
                setSpan(android.text.style.StyleSpan(Typeface.BOLD), s, length, 0)
                setSpan(android.text.style.ForegroundColorSpan(Color.WHITE), s, length, 0)
                append(tail)
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (action != null) addView(Ui.label(this@ActivityLogActivity, action, 12f, Ui.ACCENT).apply {
            setPadding(d(8), d(6), 0, d(6)); isClickable = true; setOnClickListener { click() }
        })
    }

    private fun camName(c: String) = Live.phones[c]?.name?.ifEmpty { null } ?: "Camera"

    companion object {
        private const val EXTRA_FROM_ALL = "fromAll"
        private const val PAGE = 150
        private const val LIVE_DAYS = 3L
        /** Bumped when activity is deleted, so open lists load again. */
        @Volatile var version = 0
        /** Bursts ("Motion detected ×N") opened to show their clips. */
        private val openBursts = HashSet<String>()

        fun startOfDay(ms: Long): Long = Calendar.getInstance().apply {
            timeInMillis = ms; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        /** TODAY, YESTERDAY, TUE 3 NOV (or "Tue 3 Nov" with [lower]). */
        fun dayTitle(dayStart: Long, lower: Boolean = false): String {
            val today = startOfDay(System.currentTimeMillis())
            val yesterday = startOfDay(today - 12 * 3_600_000L)
            val s = when (dayStart) {
                today -> "Today"
                yesterday -> "Yesterday"
                else -> SimpleDateFormat(if (Calendar.getInstance().apply { timeInMillis = dayStart }.get(Calendar.YEAR) ==
                    Calendar.getInstance().get(Calendar.YEAR)) "EEE d MMM" else "EEE d MMM yyyy", Locale.getDefault()).format(Date(dayStart))
            }
            return if (lower) s else s.uppercase()
        }

        /** 8s, 1m 05s */
        private fun durText(sec: Long) = if (sec < 60) "${sec}s" else "${sec / 60}m %02ds".format(sec % 60)

        private fun clipSec(clip: JSONObject): Long? = (clip.optLong("dur") / 1000).takeIf { it > 0 }
            ?: Regex("(\\d+)\\s*s").find(clip.optString("text"))?.groupValues?.get(1)?.toLongOrNull()

        /** The time in a clip's file name (MC_yyyyMMdd_HHmmss.mp4). */
        private fun clipTime(clip: JSONObject): Long? = Regex("(\\d{8}_\\d{6})").find(clip.optString("name"))?.groupValues?.get(1)?.let {
            try { SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).parse(it)?.time } catch (_: Exception) { null }
        }

        private fun text(e: ActivityEvent): String = when (e.type) {
            "motion" -> "Motion detected"
            "clip" -> "Clip saved"
            "motion_burst" -> "Motion detected ×${e.d.optInt("n")}"
            else -> e.d.optString("text")
        }

        /**
         * One event as a row: time and what happened, on one line; a recording gets "▶ 3s" in the accent colour (tap to
         * watch). A very busy hour is one row, "Motion detected ×N · 2–3 PM": tap it for its clips. Tapping a row without
         * a clip opens the camera (when [openCamera]). [showCam]: the camera's name in the row.
         */
        fun eventRow(a: Activity, prefs: Prefs, e: ActivityEvent, showCam: Boolean, openCamera: Boolean = true, seconds: Boolean = true,
                     newSince: Long = Long.MAX_VALUE, seenFor: ((String) -> Long)? = null): View {
            val ctx: Context = a
            val r = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP   // time on the first line (baselines line up), also when the text wraps
                setPadding(0, Ui.dp(ctx, 8), 0, Ui.dp(ctx, 8))
                isClickable = true
                background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x30FFFFFF), null,
                    android.graphics.drawable.ColorDrawable(0xFFFFFFFF.toInt()))
            }
            val bad = e.type in setOf("hot", "safety_stop", "pin_locked")
            // new since activity was last looked at: a yellow dot before the time
            // new = after its camera's activity was last looked at (the same rule as the number on the Activity tab,
            // which doesn't count a clip saved without a motion before it)
            val isNew = e.type != "clip" && e.t > (seenFor?.invoke(e.cam) ?: newSince)
            r.addView(Ui.label(ctx, (if (isNew) "● " else "") + Fmt.t(prefs, e.t, seconds = seconds), 13f,
                if (bad) 0xFFFF8A85.toInt() else if (isNew) Ui.ACCENT else Ui.GREY).apply {
                minWidth = Ui.dp(ctx, if (seconds) 100 else 76)
                if (isNew) contentDescription = "New: " + text
            })
            val clip = e.clip ?: e.d.takeIf { e.type == "clip" }
            val ref = clip?.let { Alerts.clipRef(it) }
            var main = text(e) + if (showCam) "  ·  " + (Live.phones[e.cam]?.name?.ifEmpty { null } ?: "Camera") else ""
            val burst = e.type == "motion_burst"
            if (burst) {
                val from = e.d.optLong("from").takeIf { it > 0 } ?: e.t
                main += "  ·  " + Fmt.t(prefs, from, seconds = false).replace(":00", "") + "–" + Fmt.t(prefs, from + 3_600_000L, seconds = false).replace(":00", "")
            }
            r.addView(Ui.label(ctx, main, 14f, if (bad) 0xFFFF8A85.toInt() else 0xFFFFFFFF.toInt()).apply {
                maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
                contentDescription = if (ref != null) "$main, with a clip — tap to watch" else null
            }, LinearLayout.LayoutParams(0, -2, 1f))
            if (clip != null) {
                val sec = clipSec(clip)
                r.addView(Ui.label(ctx, (if (ref != null) "▶ " else "") + (sec?.let { durText(it) } ?: "clip"), 13f, if (ref != null) Ui.ACCENT else Ui.GREY)
                    .apply { setPadding(Ui.dp(ctx, 8), 0, 0, 0) })
            }
            if (!burst) {
                r.setOnClickListener {
                    if (ref != null) OpenClipActivity.open(a, e.cam, ref.toString())
                    else if (openCamera) a.startActivity(Intent(a, RemoteCameraActivity::class.java).putExtra(Alerts.EXTRA_CAM, e.cam))
                }
                return r
            }
            // a busy hour: its clips under it, shown on tap
            val clips = e.d.optJSONArray("clips")
            val n = clips?.length() ?: 0
            val k = e.cam + "/" + e.key + "/" + e.t
            val open = k in openBursts
            val mark = Ui.label(ctx, if (n == 0) "" else if (open) "▴" else "▾ $n", 13f, Ui.ACCENT).apply { setPadding(Ui.dp(ctx, 8), 0, 0, 0) }
            r.addView(mark)
            val wrap = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            wrap.addView(r)
            val list = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL; setPadding(Ui.dp(ctx, 24), 0, 0, Ui.dp(ctx, 6))
                visibility = if (open) View.VISIBLE else View.GONE
            }
            fun fill() {
                if (list.childCount > 0 || clips == null) return
                for (i in 0 until n) {
                    val c = clips.optJSONObject(i) ?: continue
                    val cr = Alerts.clipRef(c)
                    list.addView(Ui.label(ctx, (clipTime(c)?.let { Fmt.t(prefs, it, seconds = true) + "   " } ?: "Clip ${i + 1}   ") + "▶ " +
                        (clipSec(c)?.let { durText(it) } ?: "clip"), 13f, if (cr != null) Ui.ACCENT else Ui.GREY).apply {
                        setPadding(0, Ui.dp(ctx, 6), 0, Ui.dp(ctx, 6))
                        isClickable = true
                        setOnClickListener { if (cr != null) OpenClipActivity.open(a, e.cam, cr.toString()) }
                    })
                }
                if (e.d.optInt("more") > 0) list.addView(Ui.label(ctx, "…and ${e.d.optInt("more")} more in Clips", 12f, Ui.GREY))
            }
            if (open) fill()
            wrap.addView(list)
            r.setOnClickListener {
                if (n == 0) return@setOnClickListener
                if (k in openBursts) { openBursts -= k; list.visibility = View.GONE } else { openBursts += k; fill(); list.visibility = View.VISIBLE }
                mark.text = if (k in openBursts) "▴" else "▾ $n"
            }
            return wrap
        }
    }
}

/**
 * The month calendar of Activity: a dot on each day with activity (of the kinds the filters show), from each camera's
 * per-day summary (no events are downloaded). Tap a day to jump there.
 */
class CalendarSheet(private val a: Activity, private val cams: List<String>, private val letters: Set<String>, private val single: Boolean,
                    private val scope: String, private val pick: (Long) -> Unit) {
    private val days = java.util.concurrent.ConcurrentHashMap<String, MutableSet<String>>()
    private val month = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1) }
    private lateinit var dialog: Dialog
    private lateinit var body: LinearLayout
    @Volatile private var loaded = 0

    private fun d(v: Int) = Ui.dp(a, v)

    fun show() {
        dialog = Dialog(a).apply { requestWindowFeature(Window.FEATURE_NO_TITLE) }
        body = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d(16), d(10), d(16), d(22))
            background = GradientDrawable().apply {
                setColor(0xFF1E2126.toInt())
                val r = d(20).toFloat(); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
        }
        dialog.setContentView(body)
        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
        }
        render()
        dialog.show()
        for (c in cams) {
            val ref = Live.ref(a, "cams/$c/days")
            if (ref == null) { loaded++; continue }
            ref.addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(s: DataSnapshot) {
                    for (day in s.children) {
                        val k = day.key ?: continue
                        val set = days.getOrPut(k) { java.util.concurrent.ConcurrentHashMap.newKeySet() }
                        for (l in day.children) if (((l.value as? Number)?.toLong() ?: 0L) > 0L && l.key != "n") l.key?.let { set += it }
                    }
                    loaded++
                    a.runOnUiThread { if (dialog.isShowing) render() }
                }
                override fun onCancelled(e: DatabaseError) { loaded++ }
            })
        }
    }

    private fun has(key: String): Boolean {
        val set = days[key] ?: return false
        val usable = if (single) set else set - "s"   // settings changes are only in a camera's own Activity
        return if (letters.isEmpty()) usable.isNotEmpty() else usable.any { it in letters }
    }

    private fun render() {
        body.removeAllViews()
        body.addView(View(a).apply { background = GradientDrawable().apply { setColor(0xFF4A4E55.toInt()); cornerRadius = d(2).toFloat() } },
            LinearLayout.LayoutParams(d(36), d(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = d(8) })
        val head = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val now = Calendar.getInstance()
        val atNow = month.get(Calendar.YEAR) == now.get(Calendar.YEAR) && month.get(Calendar.MONTH) == now.get(Calendar.MONTH)
        val earliest = days.keys.minOrNull()
        val monthKey = SimpleDateFormat("yyyyMM", Locale.US).format(month.time)
        val atFirst = earliest == null || earliest.take(6) >= monthKey
        fun nav(t: String, on: Boolean, delta: Int, desc: String) = Ui.label(a, t, 24f, if (on) Color.WHITE else 0xFF4A4E55.toInt()).apply {
            gravity = Gravity.CENTER; contentDescription = desc
            isClickable = on; if (on) setOnClickListener { month.add(Calendar.MONTH, delta); render() }
        }
        head.addView(nav("‹", !atFirst, -1, "Previous month"), LinearLayout.LayoutParams(d(44), d(44)))
        head.addView(Ui.label(a, SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(month.time), 16f).apply {
            gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(nav("›", !atNow, 1, "Next month"), LinearLayout.LayoutParams(d(44), d(44)))
        body.addView(head)
        val fdw = Calendar.getInstance().firstDayOfWeek
        val wk = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        val names = java.text.DateFormatSymbols.getInstance(Locale.getDefault()).shortWeekdays
        for (i in 0 until 7) {
            val dow = (fdw - 1 + i) % 7 + 1
            wk.addView(Ui.label(a, names[dow].take(1).uppercase(), 11f, Ui.GREY).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        body.addView(wk, LinearLayout.LayoutParams(-1, -2).apply { topMargin = d(4); bottomMargin = d(4) })
        val c = month.clone() as Calendar
        val lead = (c.get(Calendar.DAY_OF_WEEK) - fdw + 7) % 7
        val count = c.getActualMaximum(Calendar.DAY_OF_MONTH)
        val keyFmt = SimpleDateFormat("yyyyMMdd", Locale.US)
        val todayKey = keyFmt.format(Date())
        var row = LinearLayout(a)
        for (cell in 0 until ((lead + count + 6) / 7) * 7) {
            if (cell % 7 == 0) { row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }; body.addView(row) }
            val dayN = cell - lead + 1
            val v = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
            if (dayN in 1..count) {
                c.set(Calendar.DAY_OF_MONTH, dayN)
                val key = keyFmt.format(c.time)
                val on = has(key)
                val future = key > todayKey
                val today = key == todayKey
                v.addView(Ui.label(a, dayN.toString(), 14f, when { on -> Color.WHITE; future -> 0xFF4A4E55.toInt(); else -> 0xFF7A7F87.toInt() }).apply {
                    gravity = Gravity.CENTER
                    if (on) setTypeface(typeface, Typeface.BOLD)
                })
                v.addView(View(a).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (on) Ui.ACCENT else 0) } },
                    LinearLayout.LayoutParams(d(5), d(5)).apply { topMargin = d(3) })
                if (today) v.background = GradientDrawable().apply { cornerRadius = d(12).toFloat(); setStroke(d(1), Ui.ACCENT) }
                v.contentDescription = "$dayN" + if (on) ", has activity" else ""
                if (on || today) {
                    val start = ActivityLogActivity.startOfDay(c.timeInMillis)
                    v.isClickable = true
                    v.setOnClickListener { dialog.dismiss(); pick(start) }
                }
            }
            row.addView(v, LinearLayout.LayoutParams(0, d(44), 1f).apply { setMargins(d(1), d(1), d(1), d(1)) })
        }
        body.addView(Ui.label(a, if (loaded < cams.size) "Loading…" else "A dot marks a day with " + scope.ifEmpty { "activity" } + ". Tap it to jump there.",
            12f, Ui.GREY).apply { setPadding(0, d(10), 0, 0) })
    }
}

/** The network's "Keep activity" setting (days; 0 = forever, the default), shared by every phone. */
object KeepActivity {
    @Volatile var cached = 0
    val OPTIONS = listOf(0, 365, 30)
    fun label(days: Int) = when (days) { 0 -> "Forever"; 365 -> "1 year"; 30 -> "30 days"; else -> "$days days" }

    fun load(ctx: Context, done: (() -> Unit)? = null) {
        Live.ref(ctx, "settings/keepDays")?.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) { cached = (s.value as? Number)?.toInt() ?: 0; done?.invoke() }
            override fun onCancelled(e: DatabaseError) {}
        })
    }

    fun set(ctx: Context, days: Int) {
        cached = days
        Live.ref(ctx, "settings/keepDays")?.setValue(if (days == 0) null else days)
    }
}
