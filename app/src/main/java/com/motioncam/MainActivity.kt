package com.motioncam

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var preview: ImageView
    private lateinit var statusTv: TextView
    private lateinit var infoTv: TextView
    private lateinit var startBtn: TextView
    private lateinit var panel: LinearLayout
    private lateinit var valueTv: TextView
    private lateinit var seek: SeekBar
    private lateinit var autoBtn: TextView
    private val chips = LinkedHashMap<String, TextView>()
    private var selected: String? = null
    private var updating = false

    private val statusListener: (Status) -> Unit = { s -> runOnUiThread { render(s) } }

    private val engine get() = CameraService.instance?.engine
    private val caps get() = engine?.caps

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        prefs = Prefs(this)
        if (prefs.password.isEmpty()) {
            val chars = "abcdefghjkmnpqrstuvwxyz23456789"
            prefs.password = (1..8).map { chars.random() }.joinToString("")
        }
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        StatusBus.add(statusListener)
        FrameHub.previewSink = { bmp -> preview.post { preview.setImageBitmap(bmp) } }
        render(StatusBus.status)
        refreshChips()
    }

    override fun onPause() {
        FrameHub.previewSink = null
        StatusBus.remove(statusListener)
        super.onPause()
    }

    // ------------------------------------------------------------------ UI construction

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        preview = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        root.addView(preview, FrameLayout.LayoutParams(-1, -1))

        // top bar
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Ui.PANEL)
            setPadding(Ui.dp(this@MainActivity, 12), Ui.dp(this@MainActivity, 8), Ui.dp(this@MainActivity, 12), Ui.dp(this@MainActivity, 8))
            gravity = Gravity.CENTER_VERTICAL
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        statusTv = Ui.label(this, "Stopped", 15f)
        infoTv = Ui.label(this, "", 11f, 0xFFB0B4BA.toInt())
        texts.addView(statusTv)
        texts.addView(infoTv)
        top.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        val clipsBtn = Ui.chip(this, "Clips").apply { setOnClickListener { showClips() } }
        val setBtn = Ui.chip(this, "Settings").apply { setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) } }
        top.addView(clipsBtn)
        top.addView(Space(this), Ui.dp(this, 6), 1)
        top.addView(setBtn)
        root.addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        // bottom pro panel
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.PANEL)
            setPadding(0, Ui.dp(this@MainActivity, 6), 0, Ui.dp(this@MainActivity, 8))
        }
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(this@MainActivity, 12), 0, Ui.dp(this@MainActivity, 12), Ui.dp(this@MainActivity, 6))
            visibility = View.GONE
        }
        valueTv = Ui.label(this, "", 14f, Ui.ACCENT).apply { minWidth = Ui.dp(this@MainActivity, 96) }
        seek = SeekBar(this)
        autoBtn = Ui.chip(this, "AUTO")
        panel.addView(valueTv)
        panel.addView(seek, LinearLayout.LayoutParams(0, -2, 1f))
        panel.addView(autoBtn)
        bottom.addView(panel)

        val strip = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(Ui.dp(this@MainActivity, 8), 0, Ui.dp(this@MainActivity, 8), 0)
        }
        for (name in listOf("LENS", "FORMAT", "EV", "ISO", "SPEED", "FOCUS", "WB", "ZOOM", "PEAK")) {
            val c = Ui.chip(this, name)
            c.setOnClickListener { onChip(name) }
            chips[name] = c
            val lp = LinearLayout.LayoutParams(-2, -2).apply { setMargins(Ui.dp(this@MainActivity, 3), 0, Ui.dp(this@MainActivity, 3), 0) }
            row.addView(c, lp)
        }
        strip.addView(row)
        bottom.addView(strip)

        startBtn = Ui.label(this, "START", 16f).apply {
            gravity = Gravity.CENTER
            setPadding(0, Ui.dp(this@MainActivity, 12), 0, Ui.dp(this@MainActivity, 12))
            setOnClickListener { onStartStop() }
        }
        Ui.pill(startBtn, 0xFF2E7D32.toInt())
        val sp = LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(Ui.dp(this@MainActivity, 12), Ui.dp(this@MainActivity, 8), Ui.dp(this@MainActivity, 12), 0)
        }
        bottom.addView(startBtn, sp)
        root.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser && !updating) onSeek(p)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
        autoBtn.setOnClickListener { onAuto() }
        setContentView(root)
    }

    // ------------------------------------------------------------------ start / stop / permissions

    private fun onStartStop() {
        if (CameraService.instance != null) {
            CameraService.stop(this)
            startBtn.postDelayed({ render(StatusBus.status) }, 400)
            return
        }
        val need = ArrayList<String>()
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) need += Manifest.permission.CAMERA
        if (prefs.audio && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) need += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) need += Manifest.permission.POST_NOTIFICATIONS
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 1) else launchService()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) launchService()
        else Toast.makeText(this, "Camera permission is required", Toast.LENGTH_LONG).show()
    }

    private fun launchService() {
        CameraService.start(this)
        startBtn.postDelayed({ render(StatusBus.status); refreshChips() }, 800)
    }

    // ------------------------------------------------------------------ status

    private fun render(s: Status) {
        val running = CameraService.instance != null
        startBtn.text = if (running) "STOP MONITORING" else "START MONITORING"
        Ui.pill(startBtn, if (running) Ui.RED else 0xFF2E7D32.toInt())
        if (!running) {
            statusTv.text = "Stopped"
            infoTv.text = "Pick your lens and focus below, then start. Lock the phone afterwards — it keeps running."
            return
        }
        val now = System.currentTimeMillis()
        statusTv.text = when (s.mode) {
            Mode.RECORDING -> {
                val sec = (now - s.recordingSinceWall) / 1000
                "● REC %02d:%02d".format(sec / 60, sec % 60)
            }
            Mode.WATCHING -> "Watching for motion"
            else -> s.message.ifEmpty { s.mode.name }
        }
        statusTv.setTextColor(if (s.mode == Mode.RECORDING) Ui.RED else Color.WHITE)
        val sb = StringBuilder()
        sb.append(s.lens).append(" · ").append(s.format)
        if (s.batteryPct >= 0) sb.append("\nBattery ${s.batteryPct}%${if (s.charging) " ⚡" else ""} · ${"%.0f".format(s.tempC)}°C")
        if (s.lastMotionWall > 0) sb.append(" · last motion ${(now - s.lastMotionWall) / 1000}s ago")
        if (prefs.serverOn) {
            val addrs = WebServer.localAddresses()
            if (addrs.isNotEmpty()) sb.append("\nRemote: ").append(addrs.joinToString("  ") { "http://${it.second}:${prefs.port} (${it.first})" })
        }
        infoTv.text = sb.toString()
        if (selected != null) configurePanel()
        refreshChips()
    }

    // ------------------------------------------------------------------ pro chips

    private fun refreshChips() {
        val c = caps
        fun set(name: String, line2: String) { chips[name]?.text = "$name\n$line2" }
        val lensShort = Lenses.find(this, prefs.camera)?.let {
            it.label.substringBefore(" [").replace("Rear ", "").replace(" · multi-cam", "")
        } ?: "—"
        set("LENS", lensShort)
        set("FORMAT", "${prefs.resHeight}p${prefs.fps}")
        set("EV", if (!prefs.exposureAuto) "off" else evText(c, prefs.evIndex))
        set("ISO", if (prefs.exposureAuto) "AUTO" else "${prefs.iso}")
        set("SPEED", if (prefs.exposureAuto) "AUTO" else shutterText(prefs.shutterNs))
        set("FOCUS", if (prefs.focusAuto) "AF" else focusText(c, prefs.focusPos))
        set("WB", if (prefs.wbAuto) "AUTO" else "${ProCaps.wbPresets.minByOrNull { Math.abs(it.second - prefs.wbKelvin) }!!.second}K")
        set("ZOOM", "%.1fx".format(prefs.zoom))
        set("PEAK", if (prefs.peaking) "on" else "off")
        for ((k, v) in chips) Ui.setChipState(v, k == selected || (k == "PEAK" && prefs.peaking))
    }

    private fun onChip(name: String) {
        when (name) {
            "LENS" -> { showLensDialog(); return }
            "FORMAT" -> { showFormatDialog(); return }
            "PEAK" -> { prefs.peaking = !prefs.peaking; refreshChips(); return }
        }
        selected = if (selected == name) null else name
        panel.visibility = if (selected == null) View.GONE else View.VISIBLE
        if (selected != null) configurePanel()
        refreshChips()
    }

    private fun configurePanel() {
        val c = caps
        updating = true
        autoBtn.visibility = View.VISIBLE
        seek.isEnabled = true
        if (c == null) {
            valueTv.text = "—"
            seek.isEnabled = false
            autoBtn.visibility = View.GONE
            updating = false
            return
        }
        when (selected) {
            "EV" -> {
                autoBtn.visibility = View.GONE
                seek.max = max(1, c.evMax - c.evMin)
                seek.progress = prefs.evIndex.coerceIn(c.evMin, c.evMax) - c.evMin
                valueTv.text = "EV " + evText(c, prefs.evIndex)
            }
            "ISO" -> {
                seek.max = 1000
                seek.progress = logToProgress(prefs.iso.toDouble(), c.isoMin.toDouble(), c.isoMax.toDouble())
                valueTv.text = if (prefs.exposureAuto) "ISO AUTO" else "ISO ${prefs.iso}"
                setAuto(prefs.exposureAuto)
                if (!c.manualSensor) { seek.isEnabled = false; autoBtn.visibility = View.GONE; valueTv.text = "ISO: not supported" }
            }
            "SPEED" -> {
                val (lo, hi) = shutterRange(c)
                seek.max = 1000
                seek.progress = logToProgress(prefs.shutterNs.toDouble(), lo, hi)
                valueTv.text = if (prefs.exposureAuto) "AUTO" else shutterText(prefs.shutterNs)
                setAuto(prefs.exposureAuto)
                if (!c.manualSensor) { seek.isEnabled = false; autoBtn.visibility = View.GONE; valueTv.text = "Not supported" }
            }
            "FOCUS" -> {
                seek.max = 1000
                seek.progress = (prefs.focusPos * 1000).roundToInt()
                valueTv.text = if (prefs.focusAuto) "AF" else focusText(c, prefs.focusPos)
                setAuto(prefs.focusAuto)
                if (!c.canManualFocus) { seek.isEnabled = false; autoBtn.visibility = View.GONE; valueTv.text = "Fixed focus" }
            }
            "WB" -> {
                seek.max = ProCaps.wbPresets.size - 1
                seek.progress = wbIndex()
                valueTv.text = if (prefs.wbAuto) "AUTO" else ProCaps.wbPresets[wbIndex()].let { "${it.first} ${it.second}K" }
                setAuto(prefs.wbAuto)
            }
            "ZOOM" -> {
                autoBtn.visibility = View.GONE
                seek.max = 1000
                seek.isEnabled = c.canZoom
                seek.progress = ((prefs.zoom - c.zoomMin) / max(0.01f, c.zoomMax - c.zoomMin) * 1000).roundToInt().coerceIn(0, 1000)
                valueTv.text = "%.1fx".format(prefs.zoom)
            }
        }
        updating = false
    }

    private fun setAuto(on: Boolean) { Ui.setChipState(autoBtn, on) }

    private fun onAuto() {
        val c = caps ?: return
        val e = engine
        when (selected) {
            "ISO", "SPEED" -> {
                if (prefs.exposureAuto) { seedManualExposure(c); prefs.exposureAuto = false }
                else prefs.exposureAuto = true
            }
            "FOCUS" -> {
                if (prefs.focusAuto) {
                    val d = e?.lastFocusDiopter ?: 0f
                    prefs.focusPos = if (c.minFocusDiopter > 0f) (1f - d / c.minFocusDiopter).coerceIn(0f, 1f) else 1f
                    prefs.focusAuto = false
                } else prefs.focusAuto = true
            }
            "WB" -> prefs.wbAuto = !prefs.wbAuto
        }
        e?.applyPro()
        configurePanel()
        refreshChips()
    }

    private fun seedManualExposure(c: ProCaps) {
        val e = engine
        val iso = e?.lastIso ?: 0
        val sh = e?.lastShutterNs ?: 0L
        if (iso > 0) prefs.iso = iso.coerceIn(c.isoMin, c.isoMax)
        if (sh > 0) prefs.shutterNs = sh
    }

    private fun onSeek(p: Int) {
        val c = caps ?: return
        when (selected) {
            "EV" -> { prefs.exposureAuto = true; prefs.evIndex = p + c.evMin }
            "ISO" -> {
                if (prefs.exposureAuto) { seedManualExposure(c); prefs.exposureAuto = false }
                prefs.iso = progressToLog(p, c.isoMin.toDouble(), c.isoMax.toDouble()).roundToInt()
            }
            "SPEED" -> {
                if (prefs.exposureAuto) { seedManualExposure(c); prefs.exposureAuto = false }
                val (lo, hi) = shutterRange(c)
                prefs.shutterNs = progressToLog(p, lo, hi).toLong()
            }
            "FOCUS" -> {
                if (prefs.focusAuto) prefs.focusAuto = false
                prefs.focusPos = p / 1000f
            }
            "WB" -> {
                prefs.wbAuto = false
                prefs.wbKelvin = ProCaps.wbPresets[p].second
            }
            "ZOOM" -> prefs.zoom = c.zoomMin + (c.zoomMax - c.zoomMin) * p / 1000f
        }
        engine?.applyPro()
        configurePanelLabelOnly()
        refreshChips()
    }

    private fun configurePanelLabelOnly() {
        val c = caps ?: return
        valueTv.text = when (selected) {
            "EV" -> "EV " + evText(c, prefs.evIndex)
            "ISO" -> "ISO ${prefs.iso}"
            "SPEED" -> shutterText(prefs.shutterNs)
            "FOCUS" -> focusText(c, prefs.focusPos)
            "WB" -> ProCaps.wbPresets[wbIndex()].let { "${it.first} ${it.second}K" }
            "ZOOM" -> "%.1fx".format(prefs.zoom)
            else -> ""
        }
        when (selected) {
            "ISO", "SPEED" -> setAuto(prefs.exposureAuto)
            "FOCUS" -> setAuto(prefs.focusAuto)
            "WB" -> setAuto(prefs.wbAuto)
        }
    }

    // ------------------------------------------------------------------ value helpers

    private fun wbIndex(): Int {
        val i = ProCaps.wbPresets.indices.minByOrNull { Math.abs(ProCaps.wbPresets[it].second - prefs.wbKelvin) } ?: 0
        return i
    }

    private fun evText(c: ProCaps?, idx: Int): String {
        val step = c?.evStep ?: 0.1667f
        val v = idx * step
        return (if (v > 0.001f) "+" else "") + "%.1f".format(v)
    }

    private fun shutterText(ns: Long): String {
        if (ns <= 0) return "—"
        val den = (1_000_000_000.0 / ns).roundToInt()
        return if (den >= 2) "1/$den" else "%.1fs".format(ns / 1e9)
    }

    private fun focusText(c: ProCaps?, pos: Float): String {
        val min = c?.minFocusDiopter ?: 10f
        val d = (1f - pos) * min
        if (d < 0.1f) return "∞"
        val cm = 100f / d
        return if (cm >= 100f) "%.1f m".format(cm / 100f) else "%.0f cm".format(cm)
    }

    private fun shutterRange(c: ProCaps): Pair<Double, Double> {
        val lo = max(c.expMinNs, 125_000L).toDouble()
        var hi = min(c.expMaxNs, 1_000_000_000L / max(24, prefs.fps)).toDouble()
        if (hi <= lo * 1.5) hi = lo * 2
        return Pair(lo, hi)
    }

    private fun logToProgress(v: Double, lo: Double, hi: Double): Int =
        (ln(v.coerceIn(lo, hi) / lo) / ln(hi / lo) * 1000).roundToInt().coerceIn(0, 1000)

    private fun progressToLog(p: Int, lo: Double, hi: Double): Double = lo * exp(ln(hi / lo) * p / 1000.0)

    // ------------------------------------------------------------------ dialogs

    private fun showLensDialog() {
        val lenses = Lenses.list(this)
        if (lenses.isEmpty()) { Toast.makeText(this, "No cameras found", Toast.LENGTH_SHORT).show(); return }
        val cur = Lenses.find(this, prefs.camera)
        AlertDialog.Builder(this)
            .setTitle("Choose camera / lens")
            .setSingleChoiceItems(lenses.map { it.label }.toTypedArray(), lenses.indexOfFirst { it.key == cur?.key }) { d, which ->
                prefs.camera = lenses[which].key
                CameraService.instance?.onSettingsChanged()
                refreshChips()
                d.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showFormatDialog() {
        val opts = listOf(
            Triple("720p · 30 fps", 720, 30),
            Triple("1080p · 24 fps", 1080, 24),
            Triple("1080p · 30 fps (recommended)", 1080, 30),
            Triple("1080p · 60 fps", 1080, 60),
            Triple("4K · 30 fps", 2160, 30),
        )
        val cur = opts.indexOfFirst { it.second == prefs.resHeight && it.third == prefs.fps }
        AlertDialog.Builder(this)
            .setTitle("Recording format")
            .setSingleChoiceItems(opts.map { it.first }.toTypedArray(), cur) { d, which ->
                prefs.resHeight = opts[which].second
                prefs.fps = opts[which].third
                CameraService.instance?.onSettingsChanged()
                refreshChips()
                d.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showClips() {
        Thread {
            val clips = ClipStore.list(this)
            runOnUiThread {
                if (clips.isEmpty()) { Toast.makeText(this, "No clips yet", Toast.LENGTH_SHORT).show(); return@runOnUiThread }
                val df = java.text.SimpleDateFormat("MMM d HH:mm:ss", java.util.Locale.getDefault())
                val labels = clips.map { "${df.format(java.util.Date(it.addedSec * 1000))}   ${it.durationMs / 1000}s   ${it.size / 1_000_000} MB" }
                val lv = ListView(this)
                lv.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
                val dlg = AlertDialog.Builder(this).setTitle("Clips (tap = play, hold = delete)").setView(lv)
                    .setNegativeButton("Close", null).create()
                lv.setOnItemClickListener { _, _, i, _ ->
                    startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(clips[i].uri, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                }
                lv.setOnItemLongClickListener { _, _, i, _ ->
                    AlertDialog.Builder(this).setTitle("Delete this clip?")
                        .setPositiveButton("Delete") { _, _ -> ClipStore.delete(this, clips[i]); dlg.dismiss() }
                        .setNegativeButton("Cancel", null).show()
                    true
                }
                dlg.show()
            }
        }.start()
    }
}
