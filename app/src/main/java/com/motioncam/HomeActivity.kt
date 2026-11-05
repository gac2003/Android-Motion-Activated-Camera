package com.motioncam

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.motioncam.cloud.Device
import com.motioncam.live.ActivityFeed
import com.motioncam.live.ActivityLogActivity
import com.motioncam.live.AlertInbox
import com.motioncam.live.Alerts
import com.motioncam.live.CamRemote
import com.motioncam.live.CameraLink
import com.motioncam.live.Live
import com.motioncam.live.LiveActivity
import com.motioncam.live.LiveConfig
import com.motioncam.live.NetJoin
import com.motioncam.live.RemoteCameraActivity

/**
 * Home: the same on every phone. This phone's Camera mode (also the middle tab), an overview of your cameras (picture, state, battery,
 * last motion), offline warnings and the latest activity. The first time, a short welcome: connect your phones,
 * where to start, and (optionally) a remote folder.
 */
class HomeActivity : Activity(), Nav.Reselect {
    private lateinit var prefs: Prefs
    private lateinit var scroll: ScrollView
    private lateinit var box: LinearLayout
    private val ui = Handler(Looper.getMainLooper())
    private var feed: ActivityFeed? = null
    private var recentBox: LinearLayout? = null
    /** Landscape: cameras on the left, recent activity in a column of its own on the right. */
    private var side: LinearLayout? = null
    private val land by lazy { Nav.landscape(this) }
    private val expanded = HashSet<String>()
    private val changed: () -> Unit = { renderSoon() }
    private var camState = ""
    private val statusL: (Status) -> Unit = { st ->
        val k = "${CameraService.monitoring}/${CameraService.inStandby}/${st.mode == Mode.RECORDING}"
        if (k != camState) { camState = k; renderSoon() }
    }
    private var pending = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = Ui.dp(this@HomeActivity, 16)
            setPadding(p, if (land) Ui.dp(this@HomeActivity, 4) else p, if (land) Ui.dp(this@HomeActivity, 8) else p, p * 2)
        }
        scroll = ScrollView(this).apply { setBackgroundColor(Ui.BG); addView(box) }
        if (land) {
            val right = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(Ui.dp(this@HomeActivity, 8), Ui.dp(this@HomeActivity, 4), Ui.dp(this@HomeActivity, 16), Ui.dp(this@HomeActivity, 24))
            }
            side = right
            val two = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(Ui.BG)
                addView(scroll, LinearLayout.LayoutParams(0, -1, 3f))
                addView(ScrollView(this@HomeActivity).apply { addView(right) }, LinearLayout.LayoutParams(0, -1, 2f))
            }
            setContentView(Nav.wrap(this, two, Nav.Tab.HOME))
        } else {
            Ui.capWidth(box)
            setContentView(Nav.wrap(this, scroll, Nav.Tab.HOME))
        }
        // reopen where it was left: Camera mode, or the tab that was in front
        if (savedInstanceState == null && welcomeDone()) {
            if (Nav.lastPlace(this) == "camera") Nav.camera(this)
            else if (Nav.lastTab(this) != Nav.Tab.HOME) Nav.go(this, Nav.lastTab(this))
        }
    }

    override fun onResume() {
        super.onResume()
        Nav.tabShown(this, Nav.Tab.HOME)
        Live.listeners += changed
        AlertInbox.listeners += changed
        StatusBus.add(statusL)
        CameraService.ensureStandby(this)
        feed = ActivityFeed(this, 40) { fillRecent() }
        render()
        ui.postDelayed(tick, 30_000)
    }

    override fun onPause() {
        Live.listeners -= changed
        AlertInbox.listeners -= changed
        StatusBus.remove(statusL)
        feed?.stop(); feed = null
        CardPictures.stop(this)
        ui.removeCallbacks(tick)
        super.onPause()
    }

    /** "motion 2m ago" stays current. */
    private val tick = object : Runnable { override fun run() { render(); ui.postDelayed(this, 30_000) } }

    override fun onTabReselected() { scroll.smoothScrollTo(0, 0) }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { Nav.leaveTab(this, Nav.Tab.HOME) }

    private fun renderSoon() {
        if (pending) return
        pending = true
        ui.postDelayed({ pending = false; if (!isFinishing) render() }, 150)
    }

    // ------------------------------------------------------------------ first run

    private fun welcomeSp() = getSharedPreferences("nav", MODE_PRIVATE)
    /** The welcome steps were finished or skipped (an install from before Home counts as done). */
    private fun welcomeDone(): Boolean {
        val sp = welcomeSp()
        if (!sp.contains("welcomeDone")) {
            val old = getSharedPreferences("motioncam", MODE_PRIVATE).contains("password")   // set by earlier versions
            sp.edit().putBoolean("welcomeDone", old).apply()
        }
        return sp.getBoolean("welcomeDone", false)
    }
    private fun setWelcomeDone() = welcomeSp().edit().putBoolean("welcomeDone", true).apply()

    private fun d(v: Int) = Ui.dp(this, v)

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply { setColor(0xFF1E2126.toInt()); cornerRadius = d(16).toFloat() }
        setPadding(d(14), d(14), d(14), d(14))
    }

    private fun pillButton(text: String, primary: Boolean, click: () -> Unit): TextView = Ui.label(this, text, 14f).apply {
        gravity = Gravity.CENTER
        setPadding(d(12), d(11), d(12), d(11))
        setTypeface(typeface, if (primary) Typeface.BOLD else Typeface.NORMAL)
        background = GradientDrawable().apply {
            cornerRadius = d(24).toFloat()
            if (primary) setColor(Ui.ACCENT) else { setColor(0); setStroke(d(1), 0xFF4A4E55.toInt()) }
        }
        setTextColor(if (primary) 0xFF15171B.toInt() else Color.WHITE)
        isClickable = true
        setOnClickListener { click() }
    }

    private fun step(n: Int, active: Boolean, title: String, body: String, extra: String? = null): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; alpha = if (active) 1f else 0.85f }
        row.addView(Ui.label(this, n.toString(), 14f, if (active) 0xFF15171B.toInt() else Color.WHITE).apply {
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (active) Ui.ACCENT else 0xFF3A3E45.toInt()) }
        }, LinearLayout.LayoutParams(d(28), d(28)))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(d(12), 0, 0, 0) }
        col.addView(Ui.label(this, title, 16f).apply {
            setTypeface(typeface, Typeface.BOLD)
            if (extra != null) text = android.text.SpannableStringBuilder(title).apply {
                append("  "); val s = length; append(extra)
                setSpan(android.text.style.ForegroundColorSpan(Ui.GREY), s, length, 0)
                setSpan(android.text.style.RelativeSizeSpan(0.75f), s, length, 0)
                setSpan(android.text.style.StyleSpan(Typeface.NORMAL), s, length, 0)
            }
        })
        col.addView(Ui.label(this, body, 13f, 0xFFB0B4BA.toInt()).apply { setPadding(0, d(4), 0, d(4)) })
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        return row
    }

    private fun divider() = View(this).apply { setBackgroundColor(0xFF2A2D33.toInt()) }

    private fun welcome() {
        box.addView(Ui.label(this, "Welcome to MotionCam", 26f).apply { setTypeface(typeface, Typeface.BOLD); setPadding(0, d(8), 0, d(4)) })
        box.addView(Ui.label(this, "Turn spare phones into motion cameras and watch them from this one. Three short steps:", 14f, 0xFFB0B4BA.toInt())
            .apply { setPadding(0, 0, 0, d(14)) })
        val c = card()
        val joined = NetJoin.joined(this)
        val s1 = step(1, !joined, "Connect your phones",
            if (joined) "Connected: " + (com.motioncam.live.LiveNet.account(this) ?: "network code") else "The same on every phone you use, so they find each other.")
        val col1 = s1.getChildAt(1) as LinearLayout
        if (!joined && LiveConfig.configured) {
            col1.addView(pillButton("Sign in with Google", true) { NetJoin.signInGoogle(this) { render() } }, lp(top = 4))
            col1.addView(pillButton("Use a network code instead", false) { NetJoin.codeChoice(this) { render() } }, lp(top = 8))
        }
        c.addView(s1)
        c.addView(divider(), LinearLayout.LayoutParams(-1, d(1)).apply { topMargin = d(12); bottomMargin = d(12) })
        val s2 = step(2, joined, "Where to start?", "Just where you land now: Camera mode and Home are always one tap apart.")
        val grid = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun choice(t: String, sub: String, click: () -> Unit) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d(10), d(10), d(10), d(10))
            background = GradientDrawable().apply { cornerRadius = d(12).toFloat(); setStroke(d(1), 0xFF4A4E55.toInt()) }
            isClickable = true
            setOnClickListener { click() }
            addView(Ui.label(this@HomeActivity, t, 14f).apply { setTypeface(typeface, Typeface.BOLD) })
            addView(Ui.label(this@HomeActivity, sub, 12f, 0xFFB0B4BA.toInt()).apply { setPadding(0, d(4), 0, 0) })
        }
        grid.addView(choice("Home", "See my cameras and clips") { setWelcomeDone(); render() }, LinearLayout.LayoutParams(0, -1, 1f).apply { rightMargin = d(4) })
        grid.addView(choice("Camera mode", "Point this phone at a room") { setWelcomeDone(); Nav.camera(this) }, LinearLayout.LayoutParams(0, -1, 1f).apply { leftMargin = d(4) })
        (s2.getChildAt(1) as LinearLayout).addView(grid, lp(top = 4))
        c.addView(s2)
        c.addView(divider(), LinearLayout.LayoutParams(-1, d(1)).apply { topMargin = d(12); bottomMargin = d(12) })
        val s3 = step(3, false, "Add a remote folder",
            "Google Drive, Dropbox or your own server: clips are backed up there and every phone can watch them.", extra = "optional")
        s3.isClickable = true
        s3.setOnClickListener { startActivity(Intent(this, com.motioncam.remote.RemoteFoldersActivity::class.java)) }
        c.addView(s3)
        box.addView(c)
        box.addView(Ui.label(this, "Skip — just record on this phone", 14f, Ui.ACCENT).apply {
            gravity = Gravity.CENTER
            setPadding(d(10), d(14), d(10), d(10))
            isClickable = true
            setOnClickListener { setWelcomeDone(); Nav.camera(this@HomeActivity) }
        }, LinearLayout.LayoutParams(-1, -2))
    }

    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = d(top) }

    // ------------------------------------------------------------------ Home

    private fun render() {
        val y = scroll.scrollY
        box.removeAllViews()
        side?.removeAllViews()
        recentBox = null
        if (!welcomeDone()) { welcome(); scroll.post { scroll.scrollTo(0, y) }; return }
        if (land) {
            // landscape: the title and the Camera mode row share one line
            box.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(Ui.label(this@HomeActivity, "MotionCam", 20f).apply { setTypeface(typeface, Typeface.BOLD); setPadding(0, 0, d(14), 0) })
                addView(cameraModeRow(), LinearLayout.LayoutParams(0, -2, 1f))
            })
        } else {
            box.addView(Ui.label(this, "MotionCam", 26f).apply { setTypeface(typeface, Typeface.BOLD); setPadding(0, d(8), 0, d(14)) })
            box.addView(cameraModeRow())
        }
        if (!LiveConfig.configured) { scroll.post { scroll.scrollTo(0, y) }; return }
        if (!NetJoin.joined(this)) {
            val c = card()
            c.addView(Ui.label(this, "Connect your phones", 16f).apply { setTypeface(typeface, Typeface.BOLD) })
            c.addView(Ui.label(this, "Join the same network on every phone to see your cameras here, get their alerts and control them.", 13f,
                0xFFB0B4BA.toInt()).apply { setPadding(0, d(4), 0, d(8)) })
            c.addView(pillButton("Sign in with Google", true) { NetJoin.signInGoogle(this) { render() } }, lp())
            c.addView(pillButton("Use a network code instead", false) { NetJoin.codeChoice(this) { render() } }, lp(top = 8))
            box.addView(c, lp(top = 12))
            scroll.post { scroll.scrollTo(0, y) }
            return
        }
        overview()
        recent()
        scroll.post { scroll.scrollTo(0, y) }
    }

    /** This phone as a camera: its exact state, one tap away (like the Camera tab). */
    private fun cameraModeRow(): View {
        val s = StatusBus.status
        val monitoring = CameraService.monitoring
        val watched = CameraLink.streaming
        val (sub, color) = when {
            monitoring && s.mode == Mode.RECORDING -> "Recording · motion now" to 0xFFFF8A85.toInt()
            monitoring -> "Monitoring" + (if (s.lastMotionWall > 0) " · motion " + ago(System.currentTimeMillis() - s.lastMotionWall) + " ago" else "") to 0xFF81C784.toInt()
            CameraService.inStandby -> "Standby · your phones can start it" to 0xFFB0B4BA.toInt()
            else -> "Use this phone as a camera" to Ui.GREY
        }
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(d(14), d(12), d(10), d(12))
            background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x30FFFFFF),
                GradientDrawable().apply { setColor(0xFF1E2126.toInt()); cornerRadius = d(14).toFloat() }, null)
            isClickable = true
            contentDescription = "Camera mode: $sub" + if (watched) ", being watched" else ""
            setOnClickListener { Nav.camera(this@HomeActivity) }
        }
        r.addView(ImageView(this).apply { setImageDrawable(IconDrawable(IconDrawable.Kind.VIDEOCAM, if (monitoring) 0xFF81C784.toInt() else Color.WHITE)) },
            LinearLayout.LayoutParams(d(24), d(24)))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(d(12), 0, 0, 0) }
        col.addView(Ui.label(this, "Camera mode", 15f))
        col.addView(Ui.label(this, sub, 12f, color))
        r.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        if (watched) r.addView(FrameLayout(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Ui.RED) }
            addView(ImageView(this@HomeActivity).apply { setImageDrawable(IconDrawable(IconDrawable.Kind.EYE)) },
                FrameLayout.LayoutParams(d(17), d(17), Gravity.CENTER))
        }, LinearLayout.LayoutParams(d(28), d(28)).apply { rightMargin = d(6) })
        r.addView(Ui.label(this, "›", 22f, Ui.GREY))
        return r
    }

    /** Other phones that are (or were) cameras. */
    private fun cameras(): List<Live.Phone> {
        val me = Device.id(this)
        return Live.phones.values.filter { it.id != me && (it.camera || Live.cams[it.id]?.status != null) }
            .sortedWith(compareBy({ !it.online }, { it.name.lowercase() }))
    }

    private fun overview() {
        val cams = cameras()
        CardPictures.follow(this, cams.map { it.id }) { renderSoon() }
        val on = cams.count { it.online }
        val off = cams.size - on
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM; setPadding(0, d(if (land) 14 else 22), 0, d(8)) }
        head.addView(Ui.label(this, "CAMERA OVERVIEW", 13f, Ui.ACCENT).apply { setTypeface(typeface, Typeface.BOLD); letterSpacing = 0.06f },
            LinearLayout.LayoutParams(0, -2, 1f))
        if (cams.isNotEmpty()) head.addView(Ui.label(this, "$on online" + if (off > 0) " · $off offline" else "", 13f, Ui.GREY))
        box.addView(head)
        if (cams.isEmpty()) {
            box.addView(Ui.label(this, if (Live.devicesLoaded) "No cameras yet. Open Camera mode on another phone of yours (on the same network) and it appears here."
                else "Connecting…", 13f, Ui.GREY).apply { setPadding(0, d(4), 0, d(4)) })
            return
        }
        // a camera that was monitoring and lost its connection: a current state, shown on top
        for (p in cams.filter { !it.online && Live.cams[it.id]?.status?.optBoolean("monitoring") == true }) {
            val since = if (p.seen > 0) " since " + Fmt.t(prefs, p.seen - Live.serverOffset, seconds = false) else ""
            val name = p.name.ifEmpty { "A camera" }
            val open = p.id in expanded
            val w = Ui.label(this, "", 13f, Ui.ACCENT).apply {
                setPadding(d(12), d(10), d(12), d(10))
                background = GradientDrawable().apply { setColor(0xFF2A2416.toInt()); cornerRadius = d(12).toFloat() }
                text = android.text.SpannableStringBuilder("⚠  $name is offline$since").apply {
                    if (open) append(". It keeps recording if it still runs; its clips upload once it's back online.")
                    else { append("… "); val s = length; append("read more"); setSpan(android.text.style.UnderlineSpan(), s, length, 0) }
                }
                isClickable = true
                setOnClickListener { if (open) expanded -= p.id else expanded += p.id; render() }
            }
            box.addView(w, lp(top = 4).apply { bottomMargin = d(8) })
        }
        // a camera paused because it is too hot: also a current state, shown on top
        for (p in cams.filter { it.online && Live.cams[it.id]?.status?.optString("mode") == "PAUSED_HOT" }) {
            val t = Live.cams[p.id]?.status?.optDouble("tempC", 0.0) ?: 0.0
            box.addView(Ui.label(this, "🌡  ${p.name.ifEmpty { "A camera" }} is too hot" + (if (t > 0) " (%.0f °C)".format(t) else "") +
                ": recording is paused until it cools down. Move it out of the sun or away from heat; unplugging a fast charger helps.", 13f, 0xFFFF8A65.toInt()).apply {
                setPadding(d(12), d(10), d(12), d(10))
                background = GradientDrawable().apply { setColor(0xFF2E1E19.toInt()); cornerRadius = d(12).toFloat() }
            }, lp(top = 4).apply { bottomMargin = d(8) })
        }
        // 2 cards per row; 3 in landscape
        val per = if (land) 3 else 2
        for ((r, group) in cams.chunked(per).withIndex()) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; weightSum = per.toFloat() }
            box.addView(row, lp(top = if (r == 0) 0 else 12))
            group.forEachIndexed { i, p ->
                row.addView(cameraCard(p), LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) leftMargin = d(12) })
            }
        }
    }

    private fun cameraCard(p: Live.Phone): View {
        val st = Live.cams[p.id]?.status
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(0xFF1E2126.toInt()); cornerRadius = d(14).toFloat() }
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            isClickable = true
            foreground = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x30FFFFFF), null,
                GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = d(14).toFloat() })
            setOnClickListener { startActivity(Intent(this@HomeActivity, RemoteCameraActivity::class.java).putExtra(Alerts.EXTRA_CAM, p.id)) }
        }
        val pic = FrameLayout(this).apply { setBackgroundColor(0xFF2B2E33.toInt()) }
        val img = CardPictures.picture(this, p.id)
        if (img != null) pic.addView(ImageView(this).apply {
            setImageBitmap(img.first); scaleType = ImageView.ScaleType.CENTER_CROP
            if (!p.online) alpha = 0.45f
        }, FrameLayout.LayoutParams(-1, -1))
        else pic.addView(ImageView(this).apply { setImageDrawable(IconDrawable(IconDrawable.Kind.VIDEOCAM, 0xFF4A4E55.toInt())) },
            FrameLayout.LayoutParams(d(32), d(32), Gravity.CENTER))
        if (img != null) pic.addView(Ui.label(this, Fmt.t(prefs, img.second, seconds = false), 11f).apply {
            setPadding(d(6), d(2), d(6), d(2))
            background = GradientDrawable().apply { setColor(0x99000000.toInt()); cornerRadius = d(6).toFloat() }
        }, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { setMargins(d(6), d(6), d(6), d(6)) })
        val recording = p.online && st?.optString("mode") == "RECORDING"
        if (recording) pic.addView(Ui.label(this, "● REC", 10f).apply {
            setTypeface(typeface, Typeface.BOLD)
            setPadding(d(7), d(2), d(7), d(2))
            background = GradientDrawable().apply { setColor(Ui.RED); cornerRadius = d(10).toFloat() }
        }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(d(6), d(6), d(6), d(6)) })
        c.addView(pic, LinearLayout.LayoutParams(-1, d(100)))
        val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(d(10), d(8), d(10), d(10)) }
        t.addView(Ui.label(this, p.name.ifEmpty { "Camera" }, 15f).apply { setTypeface(typeface, Typeface.BOLD); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        val mode = st?.optString("mode")
        val temp = st?.optDouble("tempC", 0.0) ?: 0.0
        val (state, color) = when {
            !p.online -> "Offline" + (if (p.seen > 0) " since " + Fmt.t(prefs, p.seen - Live.serverOffset, seconds = false) else "") to Ui.ACCENT
            // paused by the camera phone itself: say so instead of "Monitoring"
            mode == "PAUSED_HOT" -> "Too hot · paused" + (if (temp > 0) " (%.0f °C)".format(temp) else "") to 0xFFFF8A65.toInt()
            mode == "PAUSED_STORAGE" -> "Storage full · paused" to 0xFFFF8A65.toInt()
            recording -> "Recording" to 0xFFFF8A85.toInt()
            st?.optBoolean("monitoring") == true -> "Monitoring" to 0xFF81C784.toInt()
            st?.optBoolean("standby") == true -> "Standby" to 0xFFB0B4BA.toInt()
            else -> "Not monitoring" to Ui.GREY
        }
        t.addView(Ui.label(this, state, 12f, color).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        val detail = Ui.label(this, "", 12f, Ui.GREY).apply { maxLines = 2 }
        val parts = ArrayList<CharSequence>()
        if (st != null && st.optInt("battery", -1) >= 0) parts += BatteryDrawable.text(detail, st.optInt("battery"), st.optBoolean("charging"))
        // getting warm (40 °C and up, before it pauses): the temperature, in orange
        if (p.online && temp >= 40 && mode != "PAUSED_HOT") parts += android.text.SpannableString("%.0f °C".format(temp)).apply {
            setSpan(android.text.style.ForegroundColorSpan(0xFFFF8A65.toInt()), 0, length, 0)
        }
        st?.optLong("lastMotion")?.takeIf { it > 0 }?.let { parts += "motion " + ago(System.currentTimeMillis() - it) + " ago" }
        detail.text = android.text.SpannableStringBuilder().apply { parts.forEachIndexed { i, x -> if (i > 0) append(" · "); append(x) } }
        if (parts.isNotEmpty()) t.addView(detail)
        c.addView(t)
        c.contentDescription = "${p.name}: $state"
        return c
    }

    private fun recent() {
        val box = side ?: box
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, d(if (land) 14 else 22), 0, d(8)) }
        head.addView(Ui.label(this, "RECENT ACTIVITY", 13f, Ui.ACCENT).apply { setTypeface(typeface, Typeface.BOLD); letterSpacing = 0.06f },
            LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(Ui.label(this, "See all ›", 13f, Ui.ACCENT).apply {
            setPadding(d(8), d(4), 0, d(4)); isClickable = true
            setOnClickListener { Nav.go(this@HomeActivity, Nav.Tab.ACTIVITY) }
        })
        box.addView(head)
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(0xFF1E2126.toInt()); cornerRadius = d(14).toFloat() }
            setPadding(d(12), d(4), d(12), d(4))
        }
        recentBox = c
        box.addView(c)
        feed?.sync()
        fillRecent()
    }

    private fun fillRecent() {
        val c = recentBox ?: return
        if (isFinishing) return
        c.removeAllViews()
        val f = feed ?: return
        val list = f.events().take(8)
        if (list.isEmpty()) {
            c.addView(Ui.label(this, if (f.loaded) "No camera activity yet." else "Loading…", 13f, Ui.GREY).apply { setPadding(0, d(10), 0, d(10)) })
            return
        }
        val since = AlertInbox.seenAt(this, null)
        for (e in list) c.addView(ActivityLogActivity.eventRow(this, prefs, e, showCam = true, seconds = false, newSince = since,
            seenFor = { cam -> AlertInbox.seenAt(this, cam) }))
    }

    /** Compact "time ago": 45s, 12m, 3h, 2d. */
    private fun ago(ms: Long): String {
        val sec = (ms / 1000).coerceAtLeast(0)
        return when {
            sec < 60 -> "${sec}s"
            sec < 3600 -> "${sec / 60}m"
            sec < 86_400 -> "${sec / 3600}h"
            else -> "${sec / 86_400}d"
        }
    }
}

/**
 * The picture on a camera's Home card: the newest of the last live frame this phone saw and the camera's card picture
 * (its latest clip's motion frame, or a snapshot every 15 minutes if the camera allows it). A camera whose live view
 * needs the PIN locks its card picture with it: shown once this phone knows the PIN.
 */
object CardPictures {
    private val pics = java.util.concurrent.ConcurrentHashMap<String, Pair<android.graphics.Bitmap, Long>>()
    private val seen = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val listeners = HashMap<String, com.google.firebase.database.ValueEventListener>()

    fun picture(ctx: android.content.Context, cam: String): Pair<android.graphics.Bitmap, Long>? {
        val live = CamRemote.lastFrames[cam]
        val card = pics[cam]
        return listOfNotNull(live, card).maxByOrNull { it.second }
    }

    /** Follows the cameras' card pictures while Home is open; [changed] runs on the main thread. */
    fun follow(ctx: android.content.Context, cams: Collection<String>, changed: () -> Unit) {
        for (cam in cams) if (!listeners.containsKey(cam)) {
            val l = object : com.google.firebase.database.ValueEventListener {
                override fun onDataChange(s: com.google.firebase.database.DataSnapshot) {
                    val t = (s.child("t").value as? Number)?.toLong() ?: return
                    if (seen[cam] == t) return
                    val at = (s.child("at").value as? Number)?.toLong() ?: t
                    val sealed = s.child("img").value as? String
                    val locked = s.child("pin").value == true
                    Thread {
                        var bytes = com.motioncam.live.LiveNet.openBytes(ctx, sealed) ?: return@Thread
                        if (locked) {
                            val pin = com.motioncam.cloud.SecretStore.get(ctx, "live.pin.$cam") ?: return@Thread
                            bytes = com.motioncam.live.CameraLink.pinOpen(cam, pin, bytes) ?: return@Thread
                        }
                        val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@Thread
                        seen[cam] = t
                        pics[cam] = bmp to at
                        android.os.Handler(android.os.Looper.getMainLooper()).post(changed)
                    }.start()
                }
                override fun onCancelled(e: com.google.firebase.database.DatabaseError) {}
            }
            Live.ref(ctx, "cams/$cam/thumb")?.addValueEventListener(l)
            listeners[cam] = l
        }
    }

    fun stop(ctx: android.content.Context) {
        for ((cam, l) in listeners) Live.ref(ctx, "cams/$cam/thumb")?.removeEventListener(l)
        listeners.clear()
    }
}
