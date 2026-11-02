package com.motioncam

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.*
import kotlin.math.roundToInt

class SettingsActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var box: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        val scroll = ScrollView(this).apply { setBackgroundColor(Ui.BG) }
        box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = Ui.dp(this@SettingsActivity, 16)
            setPadding(p, p, p, p * 2)
        }
        scroll.addView(box)
        setContentView(scroll)

        title("Recording")
        choice("Resolution", listOf("720p", "1080p (FHD)", "4K"), listOf(720, 1080, 2160).indexOf(prefs.resHeight).coerceAtLeast(1)) {
            prefs.resHeight = listOf(720, 1080, 2160)[it]
        }
        choice("Frame rate", listOf("24 fps", "30 fps", "60 fps"), listOf(24, 30, 60).indexOf(prefs.fps).coerceAtLeast(1)) {
            prefs.fps = listOf(24, 30, 60)[it]
        }
        choice("Codec", listOf("H.264 (compatible)", "HEVC / H.265 (smaller files)"), if (prefs.hevc) 1 else 0) { prefs.hevc = it == 1 }
        seek("Bitrate (0 = automatic)", 0, 50, prefs.bitrateMbps, { if (it == 0) "auto" else "$it Mbps" }) { prefs.bitrateMbps = it }
        toggle("Record audio", prefs.audio) { prefs.audio = it }
        choice("Video rotation", listOf("Auto (follow phone)", "0°", "90°", "180°", "270°"), listOf(-1, 0, 90, 180, 270).indexOf(prefs.rotation).coerceAtLeast(0)) {
            prefs.rotation = listOf(-1, 0, 90, 180, 270)[it]
        }
        note("Tip: for a phone fixed on a stand, set the rotation explicitly so clips are always upright, even with the screen locked.")

        title("Motion detection")
        seek("Sensitivity", 1, 10, prefs.sensitivity, { "$it" }) { prefs.sensitivity = it }
        seek("Minimum moving area", 2, 100, (prefs.minAreaPct * 10).roundToInt(), { "%.1f %% of frame".format(it / 10f) }) { prefs.minAreaPct = it / 10f }
        seek("Stop recording after no motion for", 10, 120, prefs.stopDelaySec, { "$it s" }) { prefs.stopDelaySec = it }
        seek("Split long events into clips of", 1, 60, prefs.maxClipMin, { "$it min" }) { prefs.maxClipMin = it }

        title("Storage and protection")
        seek("Storage limit", 1, 200, prefs.maxStorageGb, { "$it GB" }) { prefs.maxStorageGb = it }
        seek("Delete clips older than", 1, 60, prefs.keepDays, { "$it days" }) { prefs.keepDays = it }
        seek("Stop if battery (unplugged) falls below", 5, 50, prefs.minBattery, { "$it %" }) { prefs.minBattery = it }
        seek("Pause camera if battery temperature above", 35, 50, prefs.maxTempC, { "$it °C" }) { prefs.maxTempC = it }
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

        title("Remote viewing")
        toggle("Enable web viewer", prefs.serverOn) { prefs.serverOn = it }
        edit("Port", prefs.port.toString(), InputType.TYPE_CLASS_NUMBER) { it.toIntOrNull()?.let { p -> if (p in 1024..65535) prefs.port = p } }
        edit("Password (user name is: admin)", prefs.password, InputType.TYPE_CLASS_TEXT) { if (it.length >= 4) prefs.password = it }
        toggle("Higher quality stream (uses more data)", prefs.streamHd) { prefs.streamHd = it }
        val addrs = WebServer.localAddresses()
        note(
            "Open one of these in a browser on the device you view from:\n" +
                (if (addrs.isEmpty()) "(no network link right now)\n" else addrs.joinToString("\n") { "  http://${it.second}:${prefs.port}    [${it.first}]" } + "\n") +
                "\nNo Wi-Fi needed:\n" +
                "• USB: turn on Settings → Connections → Mobile Hotspot and Tethering → USB tethering, then open the phone's tether address above on the PC.\n" +
                "• USB debugging: run  adb forward tcp:${prefs.port} tcp:${prefs.port}  then open http://localhost:${prefs.port} on the PC.\n" +
                "• Bluetooth: pair, enable Bluetooth tethering; low frame rate but works.\n" +
                "• Or join the phone's own hotspot from your laptop."
        )
    }

    override fun onPause() {
        super.onPause()
        CameraService.instance?.onSettingsChanged()
    }

    // ------------------------------------------------------------------ row builders

    private fun title(s: String) {
        val t = Ui.label(this, s.uppercase(), 13f, Ui.ACCENT)
        t.setPadding(0, Ui.dp(this, 22), 0, Ui.dp(this, 6))
        box.addView(t)
    }

    private fun note(s: String) {
        val t = Ui.label(this, s, 12f, 0xFFB0B4BA.toInt())
        t.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6))
        box.addView(t)
    }

    private fun toggle(label: String, value: Boolean, set: (Boolean) -> Unit) {
        val s = Switch(this)
        s.text = label
        s.setTextColor(Color.WHITE)
        s.isChecked = value
        s.setOnCheckedChangeListener { _, b -> set(b) }
        s.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8))
        box.addView(s)
    }

    private fun button(label: String, click: () -> Unit) {
        val b = Ui.chip(this, label)
        b.setOnClickListener { click() }
        val lp = LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(this@SettingsActivity, 8) }
        box.addView(b, lp)
    }

    private fun seek(label: String, min: Int, max: Int, value: Int, fmt: (Int) -> String, set: (Int) -> Unit) {
        val t = Ui.label(this, "$label: ${fmt(value)}", 14f)
        t.setPadding(0, Ui.dp(this, 10), 0, 0)
        val sb = SeekBar(this)
        sb.max = max - min
        sb.progress = (value - min).coerceIn(0, max - min)
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                t.text = "$label: ${fmt(p + min)}"
                if (fromUser) set(p + min)
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
        box.addView(t)
        box.addView(sb)
    }

    private fun choice(label: String, options: List<String>, current: Int, set: (Int) -> Unit) {
        val t = Ui.label(this, label, 14f)
        t.setPadding(0, Ui.dp(this, 10), 0, 0)
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
        box.addView(t)
        box.addView(sp)
    }

    private fun edit(label: String, value: String, inputType: Int, set: (String) -> Unit) {
        val t = Ui.label(this, label, 14f)
        t.setPadding(0, Ui.dp(this, 10), 0, 0)
        val e = EditText(this)
        e.setText(value)
        e.setTextColor(Color.WHITE)
        e.inputType = inputType
        e.setSingleLine()
        e.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { set(s?.toString() ?: "") }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        box.addView(t)
        box.addView(e)
    }
}
