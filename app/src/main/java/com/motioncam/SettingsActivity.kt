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

class SettingsActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var box: LinearLayout

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
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.BG) }
        root.addView(topBar(this, "Settings"))
        val scroll = ScrollView(this)
        box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = Ui.dp(this@SettingsActivity, 16)
            setPadding(p, 0, p, p * 2)
        }
        scroll.addView(box)
        Ui.capWidth(box)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        title("Preview & display", Prefs.PREVIEW_KEYS)
        toggle("Show live camera preview", prefs.previewOn) { prefs.previewOn = it }
        toggle("Focus peaking (green edges) while adjusting FOCUS", prefs.peaking) { prefs.peaking = it }
        toggle("Low-power standby while monitoring with the screen off", prefs.lowPowerIdle) { prefs.lowPowerIdle = it }
        note("Between clips the camera only watches for motion at a reduced frame rate. As soon as motion starts a clip, " +
            "it switches to your recording frame rate, so clips are always recorded at the fps chosen below.")
        toggle("24-hour clock (off = AM/PM)", prefs.use24h) { prefs.use24h = it }

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

        title("Motion detection", Prefs.MOTION_KEYS)
        number("Sensitivity (1 = least, 14 = most; default 5)", "", 1, MotionDetector.MAX_SENSITIVITY, prefs.sensitivity) { prefs.sensitivity = it }
        choice("Light filter", listOf("Off (any brightness change)", "Normal — ignore lights turning on/off, exposure changes", "Strong — also ignore glare and reflections (peepholes, windows)"), prefs.lightFilter) {
            prefs.lightFilter = it
        }
        decimal("Minimum moving area", "% of frame", 0.2f, 20f, prefs.minAreaPct) { prefs.minAreaPct = it }
        number("Motion must last at least", "ms", 0, 3000, prefs.minMotionMs) { prefs.minMotionMs = it }
        number("Stop recording after no motion for (0 = as soon as motion ends)", "s", 0, 600, prefs.stopDelaySec) { prefs.stopDelaySec = it }
        number("Split long events into clips of", "min", 1, 60, prefs.maxClipMin) { prefs.maxClipMin = it }
        note("Draw detection zones with the ZONES button on the main screen: red = moving, dark = ignored. " +
            "For a peephole, mask everything outside the lens circle and pick the Strong light filter.")

        title("Player", Prefs.PLAYER_KEYS)
        val skipOpts = listOf(0, 500, 1000, 2000, 5000, 10_000)
        choice("Double-tap skip (left / right of the video)", listOf("Frame by frame", "0.5 s", "1 s (default)", "2 s", "5 s", "10 s"),
            skipOpts.indexOf(prefs.skipMs).coerceAtLeast(0)) { prefs.skipMs = skipOpts[it] }
        note("Also changeable in the player with the ± button next to the speed. Frame by frame pauses and steps one frame " +
            "per tap. Pinch the filmstrip to show more or fewer frames of the playing clip.")

        title("Storage", Prefs.STORAGE_KEYS)
        number("Delete clips older than", "days", 0, 365, prefs.keepDays, offLabel = "never") { prefs.keepDays = it }
        number("Storage limit for clips", "GB", 0, 500, prefs.maxStorageGb, offLabel = "no limit") { prefs.maxStorageGb = it }
        toggle("When the phone is nearly full, delete the oldest clips (otherwise recording pauses)", prefs.autoDeleteLow) { prefs.autoDeleteLow = it }

        title("Protection", Prefs.PROTECTION_KEYS)
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

        title("Live connection", Prefs.LIVE_KEYS + Prefs.ALERT_KEYS)
        note("Your phones connected through the internet (works on captive / isolated networks): camera status and alerts, " +
            "a live view, remote control with an optional PIN, and instant sync between phones. Off by default.")
        box.addView(Ui.chip(this, "Live connection" + if (prefs.liveOn) "  ·  on" else "  ·  off").apply {
            setOnClickListener { startActivity(Intent(this@SettingsActivity, com.motioncam.live.LiveActivity::class.java)) }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(this@SettingsActivity, 8) })
        if (prefs.liveOn) box.addView(Ui.chip(this, "Alerts & notifications").apply {
            setOnClickListener { startActivity(Intent(this@SettingsActivity, com.motioncam.live.AlertSettingsActivity::class.java)) }
            val chip = this
            live += {
                val n = com.motioncam.live.AlertInbox.unread(this@SettingsActivity)
                chip.text = "Alerts & notifications" + if (n > 0) "  ·  $n unread" else ""
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(this@SettingsActivity, 8) })

        // cloud folders: a link to the folders themselves, and their settings right here (same code as Remote Folders → ⚙)
        title("Remote folders (cloud backup)", Prefs.CLOUD_KEYS + Prefs.REMOTE_FOLDER_KEYS)
        note("The folders themselves (the one this phone backs up to, other phones' folders, the upload queue) are in " +
            "Clips → Remote Folders.")
        box.addView(Ui.chip(this, "").apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            val chip = this
            val update = {
                val on = com.motioncam.cloud.CloudSync.enabledKinds(this@SettingsActivity)
                val q = com.motioncam.cloud.CloudSync.counts(this@SettingsActivity, null)
                val t = "Open Remote Folders\n" + (if (on.isEmpty()) "Backup is off" else "Backing up to " + (if (on.size == 1) on[0].label else "${on.size} folders")) +
                    (if (q.queued > 0) " · ${q.queued} waiting" else "") + (if (q.failed > 0) " · ${q.failed} failed" else "")
                if (chip.text != t) chip.text = t
            }
            update()
            live += update
            setOnClickListener { startActivity(Intent(this@SettingsActivity, com.motioncam.remote.RemoteFoldersActivity::class.java)) }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(this@SettingsActivity, 8) })
        com.motioncam.remote.FolderSettings.storageRows(this, box, live)
        com.motioncam.remote.FolderSettings.build(this, form, { s ->
            box.addView(Ui.label(this, s, 14f, Ui.ACCENT).apply { setPadding(0, Ui.dp(this@SettingsActivity, 14), 0, 0) })
        }, { note(it) })

        title("Reset", null)
        box.addView(Ui.chip(this, "Reset ALL settings to default").apply {
            setTextColor(Ui.RED)
            setOnClickListener {
                confirmReset("Reset all settings?", "Every setting, camera control, detection zone and button layout goes back to " +
                    "its default, including cloud clean-up rules, alerts and the live connection (turned off).\n\nKept: recorded clips, " +
                    "collections, cloud accounts and backup folders (and whether backup is on), remote folders, live-connection " +
                    "sign-in and PINs, and the web viewer password.", Prefs.ALL_KEYS)
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(this@SettingsActivity, 8) })
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
        box.addView(Ui.label(this, s, 12f, 0xFFB0B4BA.toInt()).apply { setPadding(0, Ui.dp(this@SettingsActivity, 6), 0, Ui.dp(this@SettingsActivity, 6)) })
    }

    /** Rows shared with a camera's remote settings (same look, slider + exact value, knob-only sliders). */
    private val form by lazy { SettingsForm(this, box) }

    private fun toggle(label: String, value: Boolean, set: (Boolean) -> Unit) { form.toggle(label, value) { set(it) } }

    private fun button(label: String, click: () -> Unit) {
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
