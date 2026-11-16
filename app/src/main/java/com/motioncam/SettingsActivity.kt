package com.motioncam

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.*
import kotlin.math.roundToInt

open class SettingsActivity : Activity() {
    companion object {
        /** Show one category's settings (see [CATEGORIES]); without it: the list of categories. */
        const val EXTRA_SECTION = "section"
        /** With [EXTRA_SECTION]: a setting's label to scroll to and flash (from search). */
        const val EXTRA_HIGHLIGHT = "highlight"
    }
    /** Shown as the Settings tab (big title, no back arrow). */
    protected open val asTab = false
    private var sec: String? = null
    private lateinit var prefs: Prefs
    private lateinit var box: LinearLayout
    private lateinit var scroll: ScrollView
    private var searchField: EditText? = null
    /** Landscape: categories (and search) on the left, the chosen category's settings on the right. */
    private var pane: LinearLayout? = null
    private var paneScroll: ScrollView? = null
    private var paneTitle: TextView? = null
    private var paneSec: String? = null

    /** Live connection state the page was built with (it is changed on its own screen). */
    private var builtLiveOn: Boolean? = null

    /** Lines that show changing state (cloud status, queue, unread alerts, cache): refreshed while the page is open. */
    private val live = ArrayList<() -> Unit>()
    private val ui = android.os.Handler(android.os.Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() { live.forEach { try { it() } catch (_: Exception) {} }; ui.postDelayed(this, 2000) }
    }
    private val inboxChanged: () -> Unit = { live.forEach { try { it() } catch (_: Exception) {} } }
    /** Settings changed from another phone while this page is open: show them. */
    private val remoteChanged: () -> Unit = { runOnUiThread { recreate() } }

    override fun onResume() {
        super.onResume()
        if (builtLiveOn != null && builtLiveOn != prefs.liveOn) recreate()   // show the new on/off state
        builtLiveOn = prefs.liveOn
        ui.post(ticker)
        com.motioncam.live.AlertInbox.listeners += inboxChanged
        com.motioncam.live.RemoteSettings.listeners += remoteChanged
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        sec = intent.getStringExtra(EXTRA_SECTION)
        if (sec == null && Nav.landscape(this)) { twoPane(); return }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.BG) }
        if (asTab || sec == null) root.addView(Ui.label(this, "Settings", 26f).apply {
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(Ui.dp(this@SettingsActivity, 16), Ui.dp(this@SettingsActivity, 16), Ui.dp(this@SettingsActivity, 16), Ui.dp(this@SettingsActivity, 4))
        }) else root.addView(topBar(this, if (sec == "camera") "Camera settings" else CATEGORIES.firstOrNull { it.id == sec }?.title ?: "Settings"))
        if (asTab || sec == null) root.addView(searchBar())
        scroll = ScrollView(this)
        box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = Ui.dp(this@SettingsActivity, 16)
            setPadding(p, 0, p, p * 2)
        }
        scroll.addView(box)
        Ui.capWidth(box)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        val sec = sec
        if (sec == null) { categories(); return }

        buildSection(sec)
        highlight(intent.getStringExtra(EXTRA_HIGHLIGHT))
    }

    /** One category's rows (also built off screen to index them for search). */
    private fun buildSection(sec: String) {
        // Camera mode's ⚙ starts with the preview, so it's quick to turn off and on
        if (sec == "display" || sec == "camera") {
        title("Preview & display", if (sec == "camera") listOf("previewOn", "peaking", "lowPowerIdle") else Prefs.PREVIEW_KEYS)
        toggle("Show live camera preview", prefs.previewOn) { prefs.previewOn = it }
        note("Off: the screen shows no picture (the camera still watches and records). Saves a little power and keeps the screen dark.")
        toggle("Focus peaking (green edges) while adjusting FOCUS", prefs.peaking) { prefs.peaking = it }
        toggle("Low-power standby while monitoring with the screen off", prefs.lowPowerIdle) { prefs.lowPowerIdle = it }
        note("Between clips the camera only watches for motion at a reduced frame rate. As soon as motion starts a clip, " +
            "it switches to your recording frame rate, so clips are always recorded at the fps chosen below.")
        if (sec == "display") toggle("24-hour clock (off = AM/PM)", prefs.use24h) { prefs.use24h = it }

        }
        if (sec == "camera") cameraBlock()
        if (sec == "recording" || sec == "camera") {
        title("Recording", Prefs.RECORDING_KEYS)
        choice("Resolution", listOf("720p", "1080p (FHD)", "4K"), listOf(720, 1080, 2160).indexOf(prefs.resHeight).coerceAtLeast(1)) {
            prefs.resHeight = listOf(720, 1080, 2160)[it]
        }
        choice("Frame rate", listOf("24 fps", "30 fps", "60 fps"), listOf(24, 30, 60).indexOf(prefs.fps).coerceAtLeast(1)) {
            prefs.fps = listOf(24, 30, 60)[it]
        }
        choice("Codec", listOf("H.264 (most compatible)", "HEVC / H.265 (smaller files)"), if (prefs.hevc) 1 else 0) { prefs.hevc = it == 1 }
        number("Bitrate", "Mbps", 0, 100, prefs.bitrateMbps, offLabel = "auto") { prefs.bitrateMbps = it }
        toggle("Record audio", prefs.audio) { prefs.audio = it }
        val preOpts = listOf(0, 500, 1000, 2000, 3000)
        choice("Pre-record (video from before the motion)", listOf("Off", "0.5 s", "1 s", "2 s", "3 s"), preOpts.indexOf(prefs.preRecordMs).coerceAtLeast(0)) {
            prefs.preRecordMs = preOpts[it]
        }
        note("Pre-record keeps the video encoder running while monitoring, so each clip starts before the moment " +
            "motion was detected. It uses noticeably more power and heat than Off (all lengths cost about the same). " +
            "With audio on, the microphone stays active while monitoring.")
        toggle("Discard very short clips when I stop monitoring", prefs.discardShortOnStop) { prefs.discardShortOnStop = it }
        decimal("Very short = less than (from when motion was detected)", "s", 0.5f, 10f, prefs.discardShortMs / 1000f) {
            prefs.discardShortMs = (it * 1000).roundToInt()
        }
        note("Picking the phone up to turn monitoring off often starts a clip. If you stop monitoring on this screen while such " +
            "a short clip is recording, it is discarded and a small Undo appears for a few seconds. Clips that end on their " +
            "own (no more motion) are never affected, and stopping from the notification keeps the clip.")
        choice("Video rotation", listOf("Auto (follow phone)", "0°", "90°", "180°", "270°"), listOf(-1, 0, 90, 180, 270).indexOf(prefs.rotation).coerceAtLeast(0)) {
            prefs.rotation = listOf(-1, 0, 90, 180, 270)[it]
        }
        note("For a phone fixed on a stand, set the rotation explicitly so clips are always upright, even with the screen locked.")

        }
        if (sec == "motion" || sec == "camera") {
        title("Motion detection", Prefs.MOTION_KEYS)
        number("Sensitivity (1 = least, 14 = most; default 5)", "", 1, MotionDetector.MAX_SENSITIVITY, prefs.sensitivity) { prefs.sensitivity = it }
        choice("Light filter", listOf("Off (any brightness change)", "Normal — ignore lights turning on/off, exposure changes", "Strong — also ignore glare and reflections (peepholes, windows)"), prefs.lightFilter) {
            prefs.lightFilter = it
        }
        decimal("Minimum moving area", "% of frame", 0.2f, 20f, prefs.minAreaPct) { prefs.minAreaPct = it }
        number("Motion must last at least", "ms", 0, 3000, prefs.minMotionMs) { prefs.minMotionMs = it }
        number("Stop recording after no motion for (0 = as soon as motion ends)", "s", 0, 600, prefs.stopDelaySec) { prefs.stopDelaySec = it }
        number("Split long events into clips of", "min", 1, 60, prefs.maxClipMin) { prefs.maxClipMin = it }
        note("Draw detection zones with the ZONES button in Camera mode: red = moving, dark = ignored. " +
            "For a peephole, mask everything outside the lens circle and pick the Strong light filter.")

        }
        if (sec == "player") {
        title("Player", Prefs.PLAYER_KEYS)
        val skipOpts = listOf(0, 500, 1000, 2000, 5000, 10_000)
        choice("Double-tap skip (left / right of the video)", listOf("Frame by frame", "0.5 s", "1 s (default)", "2 s", "5 s", "10 s"),
            skipOpts.indexOf(prefs.skipMs).coerceAtLeast(0)) { prefs.skipMs = skipOpts[it] }
        note("Also changeable in the player with the ± button next to the speed. Frame by frame pauses and steps one frame " +
            "per tap. Pinch the filmstrip to show more or fewer frames of the playing clip.")

        }
        if (sec == "storage" || sec == "camera") {
        title(if (sec == "camera") "Storage on this phone" else "Storage", Prefs.STORAGE_KEYS)
        number("Delete clips older than", "days", 0, 365, prefs.keepDays, offLabel = "never") { prefs.keepDays = it }
        number("Storage limit for clips", "GB", 0, 500, prefs.maxStorageGb, offLabel = "no limit") { prefs.maxStorageGb = it }
        toggle("When the phone is nearly full, delete the oldest clips (otherwise recording pauses)", prefs.autoDeleteLow) { prefs.autoDeleteLow = it }

        title(if (sec == "camera") "Battery & heat" else "Protection", Prefs.PROTECTION_KEYS)
        number("Stop monitoring when unplugged battery falls to", "%", 0, 50, prefs.minBattery, offLabel = "off") { prefs.minBattery = it }
        number("Pause the camera when battery temperature reaches", "°C", 0, 70, prefs.maxTempC, offLabel = "off", minActive = 35) { prefs.maxTempC = it }
        button("Exempt from battery optimisation (recommended)") {
            try {
                val pm = getSystemService(POWER_SERVICE) as PowerManager
                if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
                } else Toast.makeText(this, "Already exempt", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
        button("Camera log (why monitoring stopped)") { showCameraLog() }

        }
        if (sec == "camera") {
        title("Watching this camera", null)
        toggle("Web viewer while monitoring (watch from a computer browser)", prefs.serverOn) { prefs.serverOn = it }
        button("Web viewer settings (password, port, addresses)…") { openCategory("web") }
        button("Your phones: remote control, PIN, standby, card picture…") { openCategory("phones") }
        button("Backup of this camera's clips (remote folders)…") { openCategory("cloud") }
        }
        if (sec == "web") {
        title("Remote viewing", Prefs.REMOTE_KEYS)
        toggle("Enable web viewer while monitoring", prefs.serverOn) { prefs.serverOn = it }
        number("Port", "", 1024, 65535, prefs.port, slider = false) { prefs.port = it }
        text("Password (user name: admin, min. 4 characters)", prefs.password) { if (it.length >= 4) prefs.password = it }
        toggle("Higher quality stream (uses more data)", prefs.streamHd) { prefs.streamHd = it }
        val addrs = WebServer.localAddresses()
        note(
            "Open one of these in a browser on the device you view from:\n" +
                (if (addrs.isEmpty()) "(no network link right now)\n" else addrs.joinToString("\n") { "  http://${it.second}:${prefs.port}    [${it.first}]" } + "\n") +
                "\nNo Wi-Fi needed:\n" +
                "• USB: turn on USB tethering (Settings → Hotspot & tethering; on Samsung: Connections → Mobile Hotspot and Tethering), then open the tether address above on the PC.\n" +
                "• USB debugging: run  adb forward tcp:${prefs.port} tcp:${prefs.port}  then open http://localhost:${prefs.port}.\n" +
                "• Bluetooth: pair, enable Bluetooth tethering (low frame rate).\n" +
                "• Or join the phone's own hotspot from your laptop."
        )

        }
        if (sec == "cloud") {
        // cloud folders: a link to the folders themselves, and their settings right here (same code as Remote Folders → ⚙)
        title("Remote folders (cloud backup)", Prefs.CLOUD_KEYS + Prefs.REMOTE_FOLDER_KEYS)
        note("The folders themselves (the one this phone backs up to, other phones' folders, the upload queue) are in " +
            "Clips → Remote folders.")
        box.addView(Ui.chip(this, "").apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            val chip = this
            val update = {
                val on = com.motioncam.cloud.CloudSync.enabledKinds(this@SettingsActivity)
                val q = com.motioncam.cloud.CloudSync.counts(this@SettingsActivity, null)
                val t = "Open Remote folders\n" + (if (on.isEmpty()) "Backup is off" else "Backing up to " + (if (on.size == 1) on[0].label else "${on.size} folders")) +
                    (if (q.queued > 0) " · ${q.queued} waiting" else "") + (if (q.failed > 0) " · ${q.failed} failed" else "")
                if (chip.text != t) chip.text = t
            }
            update()
            live += update
            setOnClickListener { startActivity(Intent(this@SettingsActivity, com.motioncam.remote.RemoteFoldersActivity::class.java)) }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(this@SettingsActivity, 8) })
        com.motioncam.remote.FolderSettings.storageRows(this, box, live)
        com.motioncam.remote.FolderSettings.build(this, form, { s ->
            SettingsForm.indexer?.invoke(1, s)
            box.addView(Ui.label(this, s, 14f, Ui.ACCENT).apply { setPadding(0, Ui.dp(this@SettingsActivity, 14), 0, 0) })
        }, { note(it) })

        }
        if (sec == "about") about()
        if (sec == "reset") {
        title("Reset", null)
        box.addView(Ui.chip(this, "Reset ALL settings to default").apply {
            setTextColor(Ui.RED)
            setOnClickListener {
                confirmReset("Reset all settings?", "Every setting, camera control, detection zone and button layout goes back to " +
                    "its default, including cloud clean-up rules and alerts.\n\nKept: recorded clips, " +
                    "collections, cloud accounts and backup folders (and whether backup is on), remote folders, your phones' network " +
                    "(sign-in) and PINs, and the web viewer password.", Prefs.ALL_KEYS)
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(this@SettingsActivity, 8) })
        }
    }

    /** A category of settings: where it opens and its one-line summary. */
    class Category(val id: String, val group: String, val title: String, val kind: IconDrawable.Kind, val color: Int, val sub: (Activity) -> String)

    private val CATEGORIES = listOf(
        Category("recording", "THIS PHONE AS A CAMERA", "Camera & recording", IconDrawable.Kind.VIDEOCAM, 0xFFF5C518.toInt()) { a ->
            val p = Prefs(a); "${if (p.resHeight >= 2160) "4K" else "${p.resHeight}p"} · ${p.fps} fps · " + (if (p.audio) "audio" else "no audio") + " · pre-record, rotation" },
        Category("motion", "THIS PHONE AS A CAMERA", "Motion detection", IconDrawable.Kind.PULSE, 0xFFF5C518.toInt()) { a ->
            "Sensitivity ${Prefs(a).sensitivity} · light filter, zones, clip length" },
        Category("phones", "EVERY PHONE", "Your phones", IconDrawable.Kind.NETWORK, 0xFF8AB4F8.toInt()) { a ->
            if (com.motioncam.live.NetJoin.joined(a)) "Network, phones, PINs, standby" else "Connect your phones" },
        Category("alerts", "EVERY PHONE", "Alerts & activity", IconDrawable.Kind.CLOCK, 0xFF8AB4F8.toInt()) { _ ->
            "Which events ring · keep activity " + com.motioncam.live.KeepActivity.label(com.motioncam.live.KeepActivity.cached).lowercase() },
        Category("cloud", "EVERY PHONE", "Remote folders & backup", IconDrawable.Kind.CLOUD_UP, 0xFF81C784.toInt()) { a ->
            val on = com.motioncam.cloud.CloudSync.enabledKinds(a)
            val q = com.motioncam.cloud.CloudSync.counts(a, null)
            (if (on.isEmpty()) "Backup is off" else "Backing up to " + (if (on.size == 1) on[0].label else "${on.size} folders")) +
                (if (q.queued > 0) " · ${q.queued} waiting" else " · upload queue") },
        Category("storage", "EVERY PHONE", "Storage & protection", IconDrawable.Kind.LOCK, 0xFF81C784.toInt()) { a ->
            val p = Prefs(a); (if (p.keepDays > 0) "Keep ${p.keepDays} days" else "Keep clips") + " · battery and heat limits" },
        Category("player", "APP", "Player", IconDrawable.Kind.PLAY, 0xFFC58AF9.toInt()) { _ -> "Double-tap skip, filmstrip" },
        Category("web", "APP", "Web viewer", IconDrawable.Kind.SCREEN, 0xFFC58AF9.toInt()) { a -> if (Prefs(a).serverOn) "On while monitoring" else "Watch from a computer browser" },
        Category("display", "APP", "Display", IconDrawable.Kind.EYE, 0xFFFFB74D.toInt()) { a ->
            "Preview, focus peaking, " + if (Prefs(a).use24h) "24-hour clock" else "12-hour clock" },
        Category("about", "APP", "About & help", IconDrawable.Kind.SETTINGS, 0xFFB0B4BA.toInt()) { a ->
            "Version " + (try { a.packageManager.getPackageInfo(a.packageName, 0).versionName } catch (_: Exception) { "" }) + " · user guide" },
        Category("reset", "APP", "Reset", IconDrawable.Kind.REFRESH, 0xFFFF8A85.toInt()) { _ -> "Restore default settings" },
    )

    /** Landscape: list on the left (title, search, categories), the chosen category on the right. */
    private fun twoPane() {
        val d = { v: Int -> Ui.dp(this, v) }
        val root = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(Ui.BG) }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        left.addView(Ui.label(this, "Settings", 20f).apply {
            setTypeface(typeface, android.graphics.Typeface.BOLD); setPadding(d(16), d(8), d(16), 0)
        })
        left.addView(searchBar())
        scroll = ScrollView(this)
        box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(d(12), 0, d(12), d(16)) }
        scroll.addView(box)
        left.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(left, LinearLayout.LayoutParams(d(320), -1))
        root.addView(View(this).apply { setBackgroundColor(0xFF2A2D33.toInt()) }, LinearLayout.LayoutParams(d(1), -1))
        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        paneTitle = Ui.label(this, "", 18f).apply { setTypeface(typeface, android.graphics.Typeface.BOLD); setPadding(d(20), d(12), d(20), 0) }
        right.addView(paneTitle)
        pane = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(d(20), 0, d(20), d(32)) }
        paneScroll = ScrollView(this).apply { addView(pane) }
        right.addView(paneScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(right, LinearLayout.LayoutParams(0, -1, 1f))
        setContentView(root)
        // the category last shown on the right (remembered)
        showPane(getSharedPreferences("settings_ui", 0).getString("pane", "recording") ?: "recording", null)
    }

    /** Shows a category on the right (and marks it in the list); [label] scrolls to that setting and flashes it. */
    private fun showPane(id: String, label: String?) {
        val p = pane ?: return
        paneSec = id
        getSharedPreferences("settings_ui", 0).edit().putString("pane", id).apply()
        live.clear()
        p.removeAllViews()
        paneTitle?.text = if (id == "camera") "Camera settings" else catTitle(id)
        val left = box
        box = p; form.box = p
        try { buildSection(id) } catch (_: Throwable) {} finally { box = left; form.box = left }
        // the list: categories again (with this one marked), unless a search is showing
        if (searchField?.text.isNullOrEmpty()) { box.removeAllViews(); categories() }
        paneScroll?.scrollTo(0, 0)
        if (label != null) highlight(label, p, paneScroll!!)
    }

    /** The Settings tab: categories in groups, each with its icon and a summary of what's set. */
    private fun categories() {
        com.motioncam.live.KeepActivity.load(this) { runOnUiThread { live.forEach { try { it() } catch (_: Exception) {} } } }
        var group = ""
        var card: LinearLayout? = null
        for (c in CATEGORIES) {
            if (c.group != group) {
                group = c.group
                box.addView(Ui.label(this, group, 12f, Ui.GREY).apply {
                    setTypeface(typeface, android.graphics.Typeface.BOLD); letterSpacing = 0.06f
                    setPadding(Ui.dp(this@SettingsActivity, 4), Ui.dp(this@SettingsActivity, 18), 0, Ui.dp(this@SettingsActivity, 6))
                })
                card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    background = android.graphics.drawable.GradientDrawable().apply { setColor(0xFF1E2126.toInt()); cornerRadius = Ui.dp(this@SettingsActivity, 14).toFloat() }
                }
                box.addView(card)
            } else card?.addView(android.view.View(this).apply { setBackgroundColor(0xFF2A2D33.toInt()) }, LinearLayout.LayoutParams(-1, Ui.dp(this, 1)))
            card?.addView(categoryRow(c))
        }
    }

    private fun categoryRow(c: Category): android.view.View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val d = { v: Int -> Ui.dp(this@SettingsActivity, v) }
        setPadding(d(14), d(10), d(10), d(10))
        isClickable = true
        background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x30FFFFFF), null,
            android.graphics.drawable.ColorDrawable(0xFFFFFFFF.toInt()))
        setOnClickListener { openCategory(c.id) }
        if (pane != null && c.id == paneSec) setBackgroundColor(0xFF2E3238.toInt())
        addView(android.widget.FrameLayout(this@SettingsActivity).apply {
            background = android.graphics.drawable.GradientDrawable().apply { setColor(c.color); cornerRadius = d(9).toFloat() }
            addView(android.widget.ImageView(this@SettingsActivity).apply { setImageDrawable(IconDrawable(c.kind, 0xFF15171B.toInt())) },
                android.widget.FrameLayout.LayoutParams(d(18), d(18), Gravity.CENTER))
        }, LinearLayout.LayoutParams(d(32), d(32)))
        val col = LinearLayout(this@SettingsActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(d(12), 0, d(6), 0) }
        col.addView(Ui.label(this@SettingsActivity, c.title, 15f, if (c.id == "reset") 0xFFFF8A85.toInt() else Color.WHITE))
        val sub = Ui.label(this@SettingsActivity, c.sub(this@SettingsActivity), 12f, Ui.GREY).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        col.addView(sub)
        live += { val t = c.sub(this@SettingsActivity); if (sub.text != t) sub.text = t }
        addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        addView(Ui.label(this@SettingsActivity, "›", 20f, 0xFF6B7078.toInt()))
        contentDescription = c.title
    }

    private fun openCategory(id: String) {
        if (pane != null && id != "phones" && id != "alerts") { showPane(id, null); return }
        when (id) {
            "phones" -> startActivity(Intent(this, com.motioncam.live.LiveActivity::class.java))
            "alerts" -> startActivity(Intent(this, com.motioncam.live.AlertSettingsActivity::class.java))
            else -> startActivity(Intent(this, SettingsActivity::class.java).putExtra(EXTRA_SECTION, id))
        }
    }

    /**
     * Camera mode's ⚙: this phone's camera controls (as on its strip), then recording and motion detection — everything
     * your other phones can change from this camera's screen. Changes apply at once.
     */
    private fun cameraBlock() {
        title("Camera", Prefs.CAMERA_KEYS)
        note("Everything here can also be changed from your other phones (this camera's screen there). The same controls are " +
            "on Camera mode's strip; yellow there = automatic.")
        val holder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(holder)
        fun build() {
            holder.removeAllViews()
            val sf = SettingsForm(this, holder)
            val c = ProCaps.forPrefs(this, prefs)
            fun pro() = EngineHost.peek()?.applyPro()
            // lens
            val lenses = Lenses.list(this)
            val cur = c?.lens?.key ?: prefs.camera
            holder.addView(Ui.chip(this, "Lens: " + (lenses.firstOrNull { it.key == cur }?.label?.substringBefore(" [") ?: "—")).apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setOnClickListener {
                    if (lenses.isEmpty()) return@setOnClickListener
                    OptionSheet.show(this@SettingsActivity, "Camera / lens", lenses.map { l ->
                        Opt(l.label.substringBefore(" ["), l.label.substringAfter(" [", "").removeSuffix("]").ifEmpty { null }, checked = l.key == cur) {
                            prefs.camera = l.key; prefs.zoom = 1f
                            EngineHost.peek()?.applyConfig()
                            build()
                        }
                    })
                }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(this@SettingsActivity, 8) })
            // exposure: EV while automatic; ISO + shutter when manual
            sf.toggle("Exposure: automatic", prefs.exposureAuto) { prefs.exposureAuto = it; pro(); build() }
            if (prefs.exposureAuto) {
                val step = (c?.evStep ?: (1f / 6)).toDouble()
                val lo = c?.evMin ?: -12; val hi = c?.evMax ?: 12
                sf.scaled("Exposure compensation", "EV", prefs.evIndex * step, hi - lo,
                    toPos = { (it / step).roundToInt() - lo }, fromPos = { (it + lo) * step },
                    format = { (if (it > 0.001) "+" else "") + "%.1f".format(it) },
                    parse = { t -> t.replace(',', '.').replace("+", "").toDoubleOrNull()?.let { ((it / step).roundToInt().coerceIn(lo, hi)) * step } },
                    signed = true) { prefs.evIndex = (it / step).roundToInt(); pro() }
            } else if (c?.manualSensor != false) {
                val isoLo = (c?.isoMin ?: 50).toDouble(); val isoHi = (c?.isoMax ?: 3200).toDouble()
                sf.scaled("ISO", "", prefs.iso.toDouble(), 1000, toPos = { toLog(it, isoLo, isoHi) }, fromPos = { fromLog(it, isoLo, isoHi).roundToInt().toDouble() },
                    format = { "${it.roundToInt()}" }, parse = { it.toDoubleOrNull()?.coerceIn(isoLo, isoHi)?.roundToInt()?.toDouble() },
                    decimalInput = false) { prefs.iso = it.roundToInt(); pro() }
                val eMin = (c?.expMinNs ?: 100_000L).coerceAtLeast(125_000L).toDouble()
                val eMax = minOf(c?.expMaxNs ?: 250_000_000L, 1_000_000_000L / prefs.fps.coerceAtLeast(1)).toDouble().coerceAtLeast(eMin * 2)
                sf.scaled("Shutter speed  1 /", "s", prefs.shutterNs.toDouble(), 1000,
                    toPos = { toLog(it, eMin, eMax) }, fromPos = { fromLog(it, eMin, eMax) },
                    format = { ns -> val den = 1e9 / ns; if (den >= 10) "${den.roundToInt()}" else "%.2f".format(den).trimEnd('0').trimEnd('.') },
                    parse = { t -> t.replace(',', '.').removePrefix("1/").toDoubleOrNull()?.takeIf { it > 0 }?.let { (1e9 / it).coerceIn(eMin, eMax) } }
                ) { prefs.shutterNs = it.toLong(); pro() }
            }
            // focus: distance in metres (empty = infinity)
            val minFocus = (c?.minFocusDiopter ?: 0f).toDouble()
            if (minFocus > 0) {
                sf.toggle("Focus: automatic", prefs.focusAuto) { prefs.focusAuto = it; pro(); build() }
                if (!prefs.focusAuto) {
                    fun mOf(pos: Double): Double { val d = (1 - pos) * minFocus; return if (d < 0.1) Double.POSITIVE_INFINITY else 1 / d }
                    sf.scaled("Focus distance", "m", prefs.focusPos.toDouble().coerceIn(0.0, 1.0), 1000,
                        toPos = { (it * 1000).roundToInt() }, fromPos = { it / 1000.0 },
                        format = { pos -> mOf(pos).let { if (it.isInfinite()) "" else if (it < 10) "%.2f".format(it) else "%.1f".format(it) } },
                        parse = { t -> if (t.isEmpty() || t == "∞") 1.0 else t.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 }?.let { m ->
                            (1 - (1 / m) / minFocus).coerceIn(0.0, 1.0) } },
                        hint = "∞") { prefs.focusPos = it.toFloat(); pro() }
                    note("Closest: about %.2f m. Leave the box empty for infinity (∞).".format(1 / minFocus))
                }
            }
            // white balance: automatic, or the camera's presets
            val presets = ProCaps.wbPresets
            sf.toggle("White balance: automatic", prefs.wbAuto) { prefs.wbAuto = it; pro(); build() }
            if (!prefs.wbAuto) {
                sf.scaled("White balance", "K", ProCaps.nearestPreset(prefs.wbKelvin).toDouble(), presets.size - 1,
                    toPos = { it.roundToInt() }, fromPos = { it.toDouble() },
                    format = { "${presets[it.roundToInt().coerceIn(0, presets.size - 1)].second}" },
                    parse = { t -> t.toIntOrNull()?.let { ProCaps.nearestPreset(it).toDouble() } },
                    decimalInput = false) { prefs.wbKelvin = presets[it.roundToInt().coerceIn(0, presets.size - 1)].second; pro() }
            }
            val zMin = c?.zoomMin ?: 1f; val zMax = c?.zoomMax ?: 1f
            if (zMax > zMin + 0.01f) sf.decimal("Zoom", "×", zMin, zMax, prefs.zoom) { prefs.zoom = (it * 10).roundToInt() / 10f; pro() }
        }
        build()
    }

    // ------------------------------------------------------------------ search

    /** One searchable setting: its label, where it is (category id, section heading) and the notes under it. */
    private class Hit(val label: String, val cat: String, val section: String, var desc: String = "", val isCategory: Boolean = false)

    private var index: List<Hit>? = null

    /** Other words people use for what a category holds (matched against categories only, so rows aren't flooded). */
    private val SYNONYMS = mapOf(
        "recording" to "video quality resolution 4k 1080p 720p fps frame rate codec hevc h265 h264 bitrate audio microphone sound pre-record rotation orientation",
        "motion" to "sensitivity zones mask detection light filter peephole trigger",
        "storage" to "space delete old clips full disk memory battery temperature heat hot overheating protection",
        "cloud" to "cloud backup upload google drive dropbox ftp sftp server wifi wi-fi data charging quality thumbnails",
        "player" to "playback skip double tap filmstrip speed",
        "web" to "browser computer pc stream password port remote viewing",
        "display" to "screen preview peaking clock time 24 hour am pm power",
        "phones" to "network pin remote control live connect sign in standby firebase",
        "alerts" to "notifications notify ring sound vibrate alert activity history keep quiet hours",
        "about" to "version help guide setup diagnostics troubleshooting battery drain log debug",
        "reset" to "default restore reset",
        "camera" to "lens exposure ev iso shutter focus white balance wb zoom preview camera mode",
    )

    /** Settings that live on their own screens (not built here). */
    private val OTHER = listOf(
        Hit("Connect your phones (network)", "phones", ""), Hit("Stay connected in the background", "phones", "Alerts on this phone"),
        Hit("Let other phones see and control this phone while it is monitoring", "phones", "This phone as a camera"),
        Hit("Change PIN", "phones", "This phone as a camera"), Hit("PIN also needed for the live view and frames", "phones", "This phone as a camera"),
        Hit("Card picture every 15 minutes", "phones", "This phone as a camera"),
        Hit("Standby: can be started from your other phones", "phones", "This phone as a camera"), Hit("Remove a phone", "phones", ""),
        Hit("Keep activity", "alerts", "Activity"), Hit("Show camera alerts on this phone", "alerts", ""),
        Hit("Check for alerts every 15 minutes when MotionCam is closed", "alerts", ""), Hit("Where alerts appear", "alerts", ""),
        Hit("Motion detected", "alerts", "Which events"), Hit("New clip saved", "alerts", "Which events"), Hit("Battery low", "alerts", "Which events"),
        Hit("Charger unplugged", "alerts", "Which events"), Hit("Too hot (camera paused)", "alerts", "Which events"),
        Hit("Monitoring stopped", "alerts", "Which events"), Hit("Camera offline", "alerts", "Which events"), Hit("Back online", "alerts", "Which events"),
        Hit("Collection sync needs a decision", "alerts", "Which events"), Hit("Quiet hours", "alerts", ""),
        Hit("Upload queue", "cloud", "Remote folders (cloud backup)"), Hit("Clear downloaded clips", "cloud", "Remote folders (cloud backup)"),
        Hit("Add a remote folder", "clips", "Remote folders"), Hit("Lens", "camera", "Camera"),
        Hit("Detection zones (ZONES in Camera mode)", "motion", "Motion detection"),
    )

    private fun catTitle(id: String): String = CATEGORIES.firstOrNull { it.id == id }?.title ?: when (id) {
        "camera" -> "Camera mode ⚙"
        "clips" -> "Clips"
        else -> id
    }

    /** Builds every category off screen once, collecting each row's label, its section and its notes. */
    private fun buildIndex(): List<Hit> {
        val out = ArrayList<Hit>()
        for (c in CATEGORIES) out += Hit(c.title, c.id, "", isCategory = true)
        out += Hit("Camera settings", "camera", "", isCategory = true)
        val savedBox = box
        val savedLive = ArrayList(live)
        val scratch = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val seen = HashSet<String>()
        for (id in listOf("recording", "motion", "storage", "cloud", "player", "web", "display", "about", "reset", "camera")) {
            var heading = ""
            var last: Hit? = null
            SettingsForm.indexer = { kind, t ->
                when (kind) {
                    1 -> heading = t
                    0 -> {
                        // Camera mode's ⚙ repeats rows of other categories: those are found there already
                        val key = t.lowercase()
                        last = if (id == "camera" && key in seen) null else Hit(t, id, heading).also { seen += key; out += it }
                    }
                    else -> last?.let { it.desc += " " + t }
                }
            }
            scratch.removeAllViews()
            box = scratch
            form.box = scratch
            try { buildSection(id) } catch (_: Throwable) {}
        }
        SettingsForm.indexer = null
        box = savedBox
        form.box = savedBox
        live.clear(); live.addAll(savedLive)
        out += OTHER
        return out
    }

    /** Best matches first: label starts with the words, then a word in the label, then inside it, then its section or notes. */
    private fun search(q: String): List<Hit> {
        val idx = index ?: buildIndex().also { index = it }
        val words = q.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        return idx.mapNotNull { h ->
            val label = h.label.lowercase()
            val around = (h.section + " " + h.desc + " " + catTitle(h.cat)).lowercase()
            val syn = if (h.isCategory) SYNONYMS[h.cat] ?: "" else ""
            var score = 0
            for (w in words) {
                score += when {
                    label.startsWith(w) -> 0
                    Regex("\\b" + Regex.escape(w)).containsMatchIn(label) -> 1
                    label.contains(w) -> 2
                    syn.contains(w) -> 3
                    around.contains(w) -> 5
                    else -> return@mapNotNull null
                }
            }
            h to score * 2 - (if (h.isCategory) 1 else 0)
        }.sortedWith(compareBy({ it.second }, { it.first.label.length })).map { it.first }.distinctBy { it.cat + "|" + it.label.lowercase() }.take(60)
    }

    /** The search field at the top of the Settings tab (like a phone's own settings). */
    private fun searchBar(): View {
        val d = { v: Int -> Ui.dp(this, v) }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply { setColor(0xFF1E2126.toInt()); cornerRadius = d(24).toFloat() }
            setPadding(d(14), 0, d(4), 0)
        }
        bar.addView(ImageView(this).apply { setImageDrawable(IconDrawable(IconDrawable.Kind.SEARCH, Ui.GREY)) }, LinearLayout.LayoutParams(d(20), d(20)))
        val et = EditText(this).apply {
            hint = "Search settings"
            background = null
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF8A8F96.toInt())
            textSize = 15f
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(d(10), d(10), d(4), d(10))
            contentDescription = "Search settings"
        }
        searchField = et
        bar.addView(et, LinearLayout.LayoutParams(0, -2, 1f))
        val clear = iconButton(this, IconDrawable.Kind.CLOSE, 40, 10) { et.setText("") }.apply { contentDescription = "Clear search"; visibility = View.GONE }
        bar.addView(clear)
        et.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(e: android.text.Editable?) {
                val q = e?.toString() ?: ""
                clear.visibility = if (q.isEmpty()) View.GONE else View.VISIBLE
                showResults(q)
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        et.setOnEditorActionListener { v, _, _ -> hideKeyboard(v); true }
        return FrameLayout(this).apply {
            setPadding(d(16), d(6), d(16), d(6))
            addView(bar, FrameLayout.LayoutParams(-1, d(48)))
            Ui.capWidth(bar)
        }
    }

    private fun hideKeyboard(v: View) {
        (getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)?.hideSoftInputFromWindow(v.windowToken, 0)
        v.clearFocus()
    }

    /** Results while typing; the categories again when the field is empty. */
    private fun showResults(q: String) {
        box.removeAllViews()
        live.clear()
        if (q.isBlank()) { categories(); return }
        val hits = search(q.trim())
        val d = { v: Int -> Ui.dp(this, v) }
        if (hits.isEmpty()) {
            box.addView(Ui.label(this, "No settings found for \"${q.trim()}\"", 15f).apply { setPadding(d(4), d(18), d(4), d(4)) })
            note("Try another word, like battery, upload, preview or PIN.")
            return
        }
        box.addView(Ui.label(this, if (hits.size == 1) "1 result" else "${hits.size} results", 12f, Ui.GREY).apply { setPadding(d(4), d(10), 0, d(6)) })
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply { setColor(0xFF1E2126.toInt()); cornerRadius = d(14).toFloat() }
        }
        box.addView(card)
        val words = q.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        hits.forEachIndexed { i, h ->
            if (i > 0) card.addView(View(this).apply { setBackgroundColor(0xFF2A2D33.toInt()) }, LinearLayout.LayoutParams(-1, d(1)))
            card.addView(resultRow(h, words))
        }
    }

    private fun resultRow(h: Hit, words: List<String>): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val d = { v: Int -> Ui.dp(this@SettingsActivity, v) }
        setPadding(d(14), d(10), d(10), d(10))
        isClickable = true
        background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x30FFFFFF), null,
            android.graphics.drawable.ColorDrawable(0xFFFFFFFF.toInt()))
        val c = CATEGORIES.firstOrNull { it.id == h.cat }
        addView(FrameLayout(this@SettingsActivity).apply {
            background = android.graphics.drawable.GradientDrawable().apply { setColor(c?.color ?: 0xFFF5C518.toInt()); cornerRadius = d(8).toFloat() }
            addView(ImageView(this@SettingsActivity).apply { setImageDrawable(IconDrawable(c?.kind ?: if (h.cat == "clips") IconDrawable.Kind.CLIPS else IconDrawable.Kind.VIDEOCAM, 0xFF15171B.toInt())) },
                FrameLayout.LayoutParams(d(16), d(16), Gravity.CENTER))
        }, LinearLayout.LayoutParams(d(28), d(28)))
        val col = LinearLayout(this@SettingsActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(d(12), 0, d(6), 0) }
        // the typed words stand out in the label
        val label = android.text.SpannableString(h.label)
        val low = h.label.lowercase()
        for (w in words) {
            var at = low.indexOf(w)
            while (at >= 0) {
                label.setSpan(android.text.style.ForegroundColorSpan(Ui.ACCENT), at, at + w.length, 0)
                label.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), at, at + w.length, 0)
                at = low.indexOf(w, at + w.length)
            }
        }
        col.addView(Ui.label(this@SettingsActivity, "", 15f).apply { text = label })
        val path = if (h.isCategory) "Settings category" else listOf(catTitle(h.cat), h.section.takeIf { it.isNotEmpty() && !it.equals(catTitle(h.cat), true) })
            .filterNotNull().joinToString("  ›  ")
        col.addView(Ui.label(this@SettingsActivity, path, 12f, Ui.GREY).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        addView(Ui.label(this@SettingsActivity, "›", 20f, 0xFF6B7078.toInt()))
        contentDescription = h.label + ", " + path
        setOnClickListener { searchField?.let { hideKeyboard(it) }; openHit(h) }
    }

    private fun openHit(h: Hit) {
        when (h.cat) {
            "phones" -> startActivity(Intent(this, com.motioncam.live.LiveActivity::class.java))
            "alerts" -> startActivity(Intent(this, com.motioncam.live.AlertSettingsActivity::class.java))
            "clips" -> startActivity(Intent(this, com.motioncam.remote.RemoteFoldersActivity::class.java))
            else -> if (pane != null) showPane(h.cat, if (h.isCategory) null else h.label)
            else startActivity(Intent(this, SettingsActivity::class.java).putExtra(EXTRA_SECTION, h.cat).apply {
                if (!h.isCategory) putExtra(EXTRA_HIGHLIGHT, h.label)
            })
        }
    }

    /** Opened from search: scroll to the setting and flash it, like a phone's settings do. */
    private fun highlight(label: String?, box: LinearLayout = this.box, scroll: ScrollView = this.scroll) {
        if (label.isNullOrEmpty()) return
        box.post {
            val target = findLabel(box, label) ?: return@post
            // the setting's row: the first view whose parent is a vertical list
            var row: View = target
            while (true) {
                val p = row.parent as? LinearLayout ?: break
                if (p.orientation == LinearLayout.VERTICAL) break
                row = p
            }
            var y = 0
            var v: View? = row
            while (v != null && v !== box) { y += v.top; v = v.parent as? View }
            scroll.smoothScrollTo(0, (y - Ui.dp(this, 32)).coerceAtLeast(0))
            android.animation.ValueAnimator.ofArgb(0x66F5C518, 0x00F5C518).apply {
                duration = 2200
                startDelay = 350
                addUpdateListener { row.setBackgroundColor(it.animatedValue as Int) }
                start()
            }
        }
    }

    private fun findLabel(v: View, label: String): View? {
        if (v is TextView && v.text?.toString()?.removeSuffix("…") == label) return v
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) findLabel(v.getChildAt(i), label)?.let { return it }
        return null
    }

    @Deprecated("Deprecated in Java")
    /** What happened to monitoring on this phone: crashes, restarts by Android, camera errors, connection drops. */
    private fun showCameraLog() {
        val tv = android.widget.TextView(this).apply {
            text = Health.text(this@SettingsActivity).lines().reversed().joinToString("\n").trim()
            textSize = 11f; setTextColor(0xFFDDDDDD.toInt()); typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            val p = Ui.dp(this@SettingsActivity, 16); setPadding(p, p / 2, p, p)
        }
        android.app.AlertDialog.Builder(this).setTitle("Camera log (newest first)")
            .setView(android.widget.ScrollView(this).apply { addView(tv) })
            .setPositiveButton("Close", null)
            .setNeutralButton("Share") { _, _ ->
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, "MotionCam camera log").putExtra(Intent.EXTRA_TEXT, Health.text(this)), "Share the camera log"))
            }
            .setNegativeButton("Clear") { _, _ -> Health.clear(this) }
            .show()
    }

    override fun onBackPressed() {
        // Back first clears a search, then leaves
        val f = searchField
        if (f != null && f.text.isNotEmpty()) { f.setText(""); hideKeyboard(f); return }
        @Suppress("DEPRECATION") super.onBackPressed()
    }

    private fun toLog(v: Double, lo: Double, hi: Double): Int = (Math.log(v.coerceIn(lo, hi) / lo) / Math.log(hi / lo) * 1000).roundToInt()
    private fun fromLog(p: Int, lo: Double, hi: Double): Double = lo * Math.exp(Math.log(hi / lo) * p / 1000.0)

    /** Version and where the setup guide is. */
    private fun about() {
        title("About", null)
        val v = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "" }
        box.addView(Ui.label(this, "MotionCam $v", 16f).apply { setPadding(0, Ui.dp(this@SettingsActivity, 6), 0, Ui.dp(this@SettingsActivity, 4)) })
        note("Turns spare phones into motion cameras: record clips on motion, watch and control your cameras from your other phones, " +
            "and back clips up to Google Drive, Dropbox or your own server.")
        button("User guide (opens in the browser)") {
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/gac2003/Android-Motion-Activated-Camera/blob/main/docs/USER_GUIDE.md"))) }
            catch (_: Exception) { Toast.makeText(this, "No browser found", Toast.LENGTH_SHORT).show() }
        }
        note("Clips stay on your phones and in the folders you choose. To run MotionCam on your own free accounts, see SETUP.md on the project's page.")
        button("Diagnostics (what's running, battery, heat, logs)") { startActivity(Intent(this, DiagnosticsActivity::class.java)) }
        note("For troubleshooting: what MotionCam is doing on this phone right now. Copy it to share it.")
    }

    private fun confirmReset(title: String, msg: String, keys: List<String>) {
        android.app.AlertDialog.Builder(this).setTitle(title).setMessage(msg)
            .setPositiveButton("Reset") { _, _ ->
                prefs.reset(keys)
                ResetEffects.apply(this, keys)
                EngineHost.peek()?.applyConfig()
                EngineHost.peek()?.applyPro()
                Toast.makeText(this, "Reset to defaults", Toast.LENGTH_SHORT).show()
                recreate()
            }
            .setNegativeButton("Cancel", null).show()
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(ticker)
        com.motioncam.live.AlertInbox.listeners -= inboxChanged
        com.motioncam.live.RemoteSettings.listeners -= remoteChanged
        currentFocus?.clearFocus()   // commits a value still being typed
        EngineHost.peek()?.applyConfig()
        CameraService.instance?.reloadServer()
    }

    // ------------------------------------------------------------------ row builders

    /** Section heading; with [keys] it gets a "Reset" button for just that section. */
    private fun title(s: String, keys: List<String>?) {
        SettingsForm.indexer?.invoke(1, s)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, Ui.dp(this@SettingsActivity, 22), 0, Ui.dp(this@SettingsActivity, 4))
        }
        row.addView(Ui.label(this, s.uppercase(), 13f, Ui.ACCENT), LinearLayout.LayoutParams(0, -2, 1f))
        if (keys != null) {
            row.addView(Ui.label(this, "Reset", 12f, Ui.GREY).apply {
                setPadding(Ui.dp(this@SettingsActivity, 12), Ui.dp(this@SettingsActivity, 4), 0, Ui.dp(this@SettingsActivity, 4))
                setOnClickListener { confirmReset("Reset \"$s\"?", "Settings in this section go back to their defaults." + when (s) {
                    "Remote folders (cloud backup)" -> "\n\nAlso each destination's clean-up rules (when full, size cap, delete after N days, protected " +
                        "collections) and paused uploads resume. Accounts, folders and whether backup is on are kept."
                    "Live connection" -> "\n\nThe live connection is turned off and this phone becomes viewer-only; alert settings and " +
                        "muted cameras are reset. Your sign-in / network code and PINs are kept."
                    else -> ""
                }, keys) }
            })
        }
        box.addView(row)
    }

    private fun note(s: String) {
        SettingsForm.indexer?.invoke(2, s)
        box.addView(Ui.label(this, s, 12f, 0xFFB0B4BA.toInt()).apply { setPadding(0, Ui.dp(this@SettingsActivity, 6), 0, Ui.dp(this@SettingsActivity, 6)) })
    }

    /** Rows shared with a camera's remote settings (same look, slider + exact value, knob-only sliders). */
    private val form by lazy { SettingsForm(this, box) }

    private fun toggle(label: String, value: Boolean, set: (Boolean) -> Unit) { form.toggle(label, value) { set(it) } }

    private fun button(label: String, click: () -> Unit) {
        SettingsForm.indexer?.invoke(0, label.removeSuffix("…"))
        box.addView(Ui.chip(this, label).apply { setOnClickListener { click() } },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(this@SettingsActivity, 8) })
    }

    private fun number(
        label: String, unit: String, min: Int, max: Int, value: Int,
        offLabel: String? = null, minActive: Int = 1, slider: Boolean = true, set: (Int) -> Unit,
    ) = form.number(label, unit, min, max, value, offLabel, minActive, slider, set = set)

    private fun decimal(label: String, unit: String, min: Float, max: Float, value: Float, set: (Float) -> Unit) =
        form.decimal(label, unit, min, max, value, set = set)

    private fun choice(label: String, options: List<String>, current: Int, set: (Int) -> Unit) = form.choice(label, options, current, set = set)

    private fun text(label: String, value: String, set: (String) -> Unit) {
        SettingsForm.indexer?.invoke(0, label)
        box.addView(Ui.label(this, label, 14f).apply { setPadding(0, Ui.dp(this@SettingsActivity, 10), 0, 0) })
        box.addView(EditText(this).apply {
            setText(value)
            setTextColor(Color.WHITE)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) { set(s?.toString() ?: "") }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        })
    }
}
