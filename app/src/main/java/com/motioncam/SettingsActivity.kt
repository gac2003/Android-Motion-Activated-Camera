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

        title("Cloud backup", Prefs.CLOUD_KEYS)
        note("Copies every new clip to the cloud or your own server, so footage survives if the phone is damaged or stolen. " +
            "Uploads wait and resume automatically if the internet drops.")
        for (k in com.motioncam.cloud.CloudKind.values()) {
            val st = com.motioncam.cloud.CloudSync.status(this, k)
            box.addView(Ui.chip(this, "${k.label}\n$st").apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setOnClickListener {
                    startActivity(Intent(this@SettingsActivity, com.motioncam.cloud.CloudActivity::class.java)
                        .putExtra(com.motioncam.cloud.CloudActivity.EXTRA_KIND, k.key))
                }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(this@SettingsActivity, 8) })
        }
        val q = com.motioncam.cloud.CloudSync.counts(this, null)
        box.addView(Ui.chip(this, "Upload queue  ·  ${q.queued} waiting" + if (q.failed > 0) " · ${q.failed} failed" else "").apply {
            setOnClickListener { startActivity(Intent(this@SettingsActivity, com.motioncam.cloud.UploadQueueActivity::class.java)) }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(this@SettingsActivity, 8) })
        toggle("Upload only on Wi-Fi (not mobile data)", prefs.cloudWifiOnly) { prefs.cloudWifiOnly = it; com.motioncam.cloud.CloudSync.kick(this) }
        toggle("Upload only while charging", prefs.cloudChargingOnly) { prefs.cloudChargingOnly = it; com.motioncam.cloud.CloudSync.kick(this) }
        choice("Upload quality", listOf("Original (largest, best quality)", "720p (about 1/3 the size)", "480p (smallest)"), prefs.cloudQuality) {
            prefs.cloudQuality = it
        }
        note("Smaller copies are made on the phone before uploading (uses some extra power; original stays on the phone).")
        toggle("Delete the phone copy after it is backed up everywhere (clips in collections are kept)", prefs.cloudDeleteLocal) { prefs.cloudDeleteLocal = it }
        toggle("Upload thumbnails for other devices", prefs.cloudThumbs) { prefs.cloudThumbs = it; com.motioncam.cloud.CloudSync.kick(this) }
        note("A small peak-motion picture (about 20 KB) per clip, in a \".thumbnails\" subfolder, so another device viewing " +
            "this folder under Clips → Remote folders sees the right frame and clip length without downloading videos.")

        title("Remote folders", Prefs.REMOTE_FOLDER_KEYS)
        note("View clips another phone backed up (Clips → cloud icon). Read-only: nothing in those folders is ever changed.")
        toggle("Allow FTP / SFTP servers as remote folders", prefs.remoteFtp) { prefs.remoteFtp = it }
        note("When off, FTP/SFTP folders are hidden and cannot be added. Clips from servers are downloaded fully before " +
            "they play (cloud folders stream).")
        val cacheMb = com.motioncam.remote.StreamCache.cacheBytes(this) / 1_000_000
        box.addView(Ui.chip(this, "Clear downloaded remote clips  ·  $cacheMb MB").apply {
            setOnClickListener {
                com.motioncam.remote.StreamCache.clearAll(this@SettingsActivity)
                Toast.makeText(this@SettingsActivity, "Cleared", Toast.LENGTH_SHORT).show()
                recreate()
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(this@SettingsActivity, 8) })
        note("Played remote clips are kept (up to 1.5 GB) so replaying and scrubbing are instant. Android may also clear them when storage is low.")

        title("Reset", null)
        box.addView(Ui.chip(this, "Reset ALL settings to default").apply {
            setTextColor(Ui.RED)
            setOnClickListener {
                confirmReset("Reset all settings?", "Every setting, camera control, detection zone and button layout goes back to " +
                    "its default. Recorded clips and the web viewer password are kept.", Prefs.ALL_KEYS)
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(this@SettingsActivity, 8) })
    }

    private fun confirmReset(title: String, msg: String, keys: List<String>) {
        android.app.AlertDialog.Builder(this).setTitle(title).setMessage(msg)
            .setPositiveButton("Reset") { _, _ ->
                prefs.reset(keys)
                EngineHost.peek()?.applyConfig()
                EngineHost.peek()?.applyPro()
                Toast.makeText(this, "Reset to defaults", Toast.LENGTH_SHORT).show()
                recreate()
            }
            .setNegativeButton("Cancel", null).show()
    }

    override fun onPause() {
        super.onPause()
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
                setOnClickListener { confirmReset("Reset \"$s\"?", "Settings in this section go back to their defaults.", keys) }
            })
        }
        box.addView(row)
    }

    private fun note(s: String) {
        box.addView(Ui.label(this, s, 12f, 0xFFB0B4BA.toInt()).apply { setPadding(0, Ui.dp(this@SettingsActivity, 6), 0, Ui.dp(this@SettingsActivity, 6)) })
    }

    private fun toggle(label: String, value: Boolean, set: (Boolean) -> Unit) {
        box.addView(Switch(this).apply {
            text = label
            setTextColor(Color.WHITE)
            isChecked = value
            setOnCheckedChangeListener { _, b -> set(b) }
            setPadding(0, Ui.dp(this@SettingsActivity, 8), 0, Ui.dp(this@SettingsActivity, 8))
        })
    }

    private fun button(label: String, click: () -> Unit) {
        box.addView(Ui.chip(this, label).apply { setOnClickListener { click() } },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(this@SettingsActivity, 8) })
    }

    private fun numberField(initial: String, decimal: Boolean): EditText = EditText(this).apply {
        setText(initial)
        setTextColor(Color.WHITE)
        gravity = Gravity.END
        inputType = if (decimal) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL else InputType.TYPE_CLASS_NUMBER
        imeOptions = EditorInfo.IME_ACTION_DONE
        setSingleLine()
        setSelectAllOnFocus(true)
        minWidth = Ui.dp(this@SettingsActivity, 72)
    }

    /**
     * Integer setting with slider + exact text entry. If [offLabel] is given, 0 means "off" and
     * values between 1 and [minActive]-1 are raised to [minActive].
     */
    private fun number(
        label: String, unit: String, min: Int, max: Int, value: Int,
        offLabel: String? = null, minActive: Int = 1, slider: Boolean = true, set: (Int) -> Unit,
    ) {
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val t = Ui.label(this, label, 14f)
        val u = Ui.label(this, unit, 13f, Ui.GREY)
        val field = numberField(if (offLabel != null && value == 0) "" else "$value", false)
        if (offLabel != null) field.hint = offLabel
        head.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(field)
        head.addView(u)
        head.setPadding(0, Ui.dp(this, 10), 0, 0)
        box.addView(head)

        var sb: SeekBar? = null
        var syncing = false
        fun normalise(v: Int): Int {
            if (offLabel != null && v <= 0) return 0
            return v.coerceIn(maxOf(min, if (offLabel != null) minActive else min), max)
        }
        fun show(v: Int) {
            syncing = true
            field.setText(if (offLabel != null && v == 0) "" else "$v")
            sb?.progress = (if (offLabel != null && v == 0) min else v) - min
            syncing = false
        }
        if (slider) {
            sb = SeekBar(this).apply {
                this.max = max - min
                progress = (value - min).coerceIn(0, max - min)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                        if (!fromUser || syncing) return
                        val v = normalise(p + min)
                        set(v)
                        syncing = true
                        field.setText(if (offLabel != null && v == 0) "" else "$v")
                        syncing = false
                    }
                    override fun onStartTrackingTouch(s: SeekBar) {}
                    override fun onStopTrackingTouch(s: SeekBar) {}
                })
            }
            box.addView(sb)
        }
        fun commit() {
            if (syncing) return
            val v = normalise(field.text.toString().trim().toIntOrNull() ?: if (offLabel != null) 0 else value)
            set(v)
            show(v)
        }
        field.setOnEditorActionListener { _, _, _ -> commit(); field.clearFocus(); false }
        field.setOnFocusChangeListener { _, has -> if (!has) commit() }
    }

    /** Decimal setting (one decimal place) with slider + exact text entry. */
    private fun decimal(label: String, unit: String, min: Float, max: Float, value: Float, set: (Float) -> Unit) {
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val field = numberField("%.1f".format(value), true)
        head.addView(Ui.label(this, label, 14f), LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(field)
        head.addView(Ui.label(this, unit, 13f, Ui.GREY))
        head.setPadding(0, Ui.dp(this, 10), 0, 0)
        box.addView(head)
        var syncing = false
        val sb = SeekBar(this).apply {
            this.max = ((max - min) * 10).roundToInt()
            progress = ((value - min) * 10).roundToInt().coerceIn(0, this.max)
        }
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                if (!fromUser || syncing) return
                val v = min + p / 10f
                set(v)
                syncing = true; field.setText("%.1f".format(v)); syncing = false
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
        box.addView(sb)
        fun commit() {
            if (syncing) return
            val v = (field.text.toString().trim().replace(',', '.').toFloatOrNull() ?: value).coerceIn(min, max)
            set(v)
            syncing = true
            field.setText("%.1f".format(v))
            sb.progress = ((v - min) * 10).roundToInt()
            syncing = false
        }
        field.setOnEditorActionListener { _, _, _ -> commit(); field.clearFocus(); false }
        field.setOnFocusChangeListener { _, has -> if (!has) commit() }
    }

    private fun choice(label: String, options: List<String>, current: Int, set: (Int) -> Unit) {
        box.addView(Ui.label(this, label, 14f).apply { setPadding(0, Ui.dp(this@SettingsActivity, 10), 0, 0) })
        val sp = Spinner(this)
        sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, options)
        sp.setSelection(current)
        var first = true
        sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (first) { first = false; return }
                set(pos)
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        box.addView(sp)
    }

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
