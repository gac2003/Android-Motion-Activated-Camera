package com.motioncam.live

import android.app.Activity
import android.app.AlertDialog
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.motioncam.Chip
import com.motioncam.Fmt
import com.motioncam.Opt
import com.motioncam.OptionSheet
import com.motioncam.Prefs
import com.motioncam.ProCaps
import com.motioncam.Ui
import org.json.JSONObject
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Full-screen live view of a camera phone, laid out like MotionCam's own camera screen: the picture fills the screen,
 * status at the top, and at the bottom the same control buttons (lens, format, EV, ISO, shutter, focus, white balance,
 * zoom) with their slider, plus Start / Stop monitoring. Tap the picture to hide or show the controls.
 * Changes are lined up and sent together with Save (a remote change shows in the picture a few seconds later); the picture shows the
 * result within a couple of seconds. Zones aren't offered: they are drawn on the camera phone itself.
 */
class RemoteLiveActivity : Activity() {
    private lateinit var cam: String
    private lateinit var prefs: Prefs
    private lateinit var r: CamRemote
    private lateinit var feed: CamRemote.Feed
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var image: ImageView
    private lateinit var msgTv: TextView
    private lateinit var top: LinearLayout
    private lateinit var statusTv: TextView
    private lateinit var infoTv: TextView
    private lateinit var liveTv: TextView
    private lateinit var bottomBox: LinearLayout
    private lateinit var panel: LinearLayout
    private lateinit var valueTv: TextView
    private lateinit var seek: SeekBar
    private lateinit var autoBtn: TextView
    private lateinit var strip: HorizontalScrollView
    private lateinit var chipRow: LinearLayout
    private lateinit var startBtn: TextView
    private lateinit var stripRow: LinearLayout
    private var compact: Boolean? = null
    private val chips = LinkedHashMap<String, Chip>()
    private var selected: String? = null
    private var updating = false
    private var touching = false
    private var controlsShown = true
    private var lastFrameAt = 0L

    /** Values sent but not yet confirmed by the camera's status (shown meanwhile so controls don't flick back). */
    private val pending = JSONObject()
    private var pendingAt = 0L
    /** Changes made here but not sent yet: the camera gets them all at once with Save (remote changes show a few seconds
     *  later in the picture, so they are lined up first, then sent). */
    private val draft = JSONObject()
    private var saving = false
    private var leaveOk = false
    private lateinit var saveBar: LinearLayout
    private lateinit var saveInfo: TextView
    private lateinit var saveBtn: TextView

    private val refresh: () -> Unit = { runOnUiThread { refreshAll() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        cam = intent.getStringExtra(Alerts.EXTRA_CAM) ?: run { finish(); return }
        r = CamRemote(this, cam)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
        feed = r.Feed(10 * 60_000L, { bmp, at ->
            image.setImageBitmap(bmp)
            lastFrameAt = at
            msgTv.visibility = View.GONE
            updateLiveLabel()
        }) { msg ->
            msgTv.text = msg
            msgTv.visibility = if (feed.watching && lastFrameAt > 0) View.GONE else View.VISIBLE
            updateLiveLabel()
        }
    }

    override fun onResume() {
        super.onResume()
        Live.listeners += refresh
        Live.viewerActive = true
        Live.refresh(this)
        refreshAll()
        feed.start()
        ui.post(tick)
    }

    override fun onPause() {
        Live.listeners -= refresh
        if (::feed.isInitialized) feed.stop()
        ui.removeCallbacks(tick)
        super.onPause()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        arrangeBottom()
    }

    /** Once a second: recording time, "x s ago" of the last frame. */
    private val tick = object : Runnable {
        override fun run() { renderStatus(); updateLiveLabel(); ui.postDelayed(this, 1000) }
    }

    // ------------------------------------------------------------------ UI

    private fun d(v: Int) = Ui.dp(this, v)

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Live view (tap to show or hide the controls)"
            setOnClickListener { if (!feed.watching) feed.start() else toggleControls() }
        }
        root.addView(image, FrameLayout.LayoutParams(-1, -1))
        msgTv = Ui.label(this, "Starting live view…", 14f).apply {
            gravity = Gravity.CENTER
            setPadding(d(16), d(10), d(16), d(10))
            background = android.graphics.drawable.GradientDrawable().apply { setColor(0x99000000.toInt()); cornerRadius = d(10).toFloat() }
            setOnClickListener { feed.start() }
        }
        root.addView(msgTv, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))

        // top: back, camera name + status, live indicator
        top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xCC000000.toInt() or (Ui.PANEL and 0x00FFFFFF))
            setPadding(d(4), d(6), d(12), d(6))
        }
        top.addView(Ui.label(this, "‹", 30f).apply {
            setPadding(d(10), 0, d(12), d(4)); contentDescription = "Back"; setOnClickListener { finish() }
        })
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        statusTv = Ui.label(this, "", 15f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        infoTv = Ui.label(this, "", 11f, 0xFFB0B4BA.toInt()).apply { maxLines = 3; includeFontPadding = false }
        texts.addView(statusTv); texts.addView(infoTv)
        top.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        liveTv = Ui.label(this, "", 12f, Ui.GREY).apply { setPadding(d(8), 0, 0, 0) }
        top.addView(liveTv)
        root.addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        // bottom: slider panel, control buttons, start / stop (like the main screen)
        bottomBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xCC000000.toInt() or (Ui.PANEL and 0x00FFFFFF))
            setPadding(0, d(6), 0, d(8))
        }
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(d(12), 0, d(12), d(6))
            visibility = View.GONE
        }
        valueTv = Ui.label(this, "", 14f).apply { minWidth = d(104); setOnClickListener { askExact() } }
        seek = SeekBar(this)
        autoBtn = Ui.chip(this, "AUTO").apply { setOnClickListener { onAuto() } }
        panel.addView(valueTv)
        panel.addView(seek, LinearLayout.LayoutParams(0, -2, 1f))
        panel.addView(autoBtn)
        saveBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(d(12), d(2), d(12), d(8))
            visibility = View.GONE
        }
        saveInfo = Ui.label(this, "", 13f, Ui.ACCENT)
        saveBar.addView(saveInfo, LinearLayout.LayoutParams(0, -2, 1f))
        saveBar.addView(Ui.chip(this, "Cancel").apply { setOnClickListener { cancelDraft() } })
        saveBtn = Ui.chip(this, "Save").apply { Ui.setChipState(this, true); setOnClickListener { saveDraft() } }
        saveBar.addView(saveBtn, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = d(8) })
        bottomBox.addView(saveBar)
        bottomBox.addView(panel)
        strip = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        chipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(d(8), 0, d(8), 0) }
        for (name in chipNames()) {
            val c = Chip(this, name)
            c.view.setOnClickListener { onChip(name) }
            chips[name] = c
            chipRow.addView(c.view, LinearLayout.LayoutParams(-2, -2).apply { setMargins(d(3), 0, d(3), 0) })
        }
        strip.addView(chipRow)
        startBtn = Ui.label(this, "", 16f).apply { gravity = Gravity.CENTER; setOnClickListener { onStartStop() } }
        stripRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        arrangeBottom()
        root.addView(bottomBox, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) { if (fromUser && !updating) onSeek(p) }
            override fun onStartTrackingTouch(sb: SeekBar) { touching = true }
            override fun onStopTrackingTouch(sb: SeekBar) { touching = false; updateSaveBar() }
        })
        setContentView(root)
    }

    /** The same buttons, in the same order, as on this phone's camera screen (zones excluded). */
    private fun chipNames(): List<String> {
        val all = Prefs.ALL_CHIPS.filter { it != "ZONES" }
        return prefs.chipLayout.split(",").map { it.trim() }.filter { it in all }.distinct().ifEmpty { all }
    }

    /** Portrait: buttons, then a full-width Start/Stop. Landscape phones: one compact row. */
    private fun arrangeBottom() {
        val cfg = resources.configuration
        val want = cfg.orientation == Configuration.ORIENTATION_LANDSCAPE && cfg.smallestScreenWidthDp < 600
        if (compact == want) return
        compact = want
        (strip.parent as? ViewGroup)?.removeView(strip)
        (startBtn.parent as? ViewGroup)?.removeView(startBtn)
        (stripRow.parent as? ViewGroup)?.removeView(stripRow)
        if (want) {
            stripRow.addView(strip, LinearLayout.LayoutParams(0, -2, 1f))
            stripRow.addView(startBtn, LinearLayout.LayoutParams(-2, -2).apply { setMargins(d(6), 0, d(12), 0) })
            bottomBox.addView(stripRow)
            startBtn.setPadding(d(18), d(10), d(18), d(10)); startBtn.textSize = 14f
        } else {
            bottomBox.addView(strip)
            bottomBox.addView(startBtn, LinearLayout.LayoutParams(-1, -2).apply { setMargins(d(12), d(8), d(12), 0) })
            startBtn.setPadding(0, d(12), 0, d(12)); startBtn.textSize = 16f
        }
        renderStart()
    }

    private fun toggleControls() {
        controlsShown = !controlsShown
        for (v in listOf(top, bottomBox)) {
            v.animate().cancel()
            if (controlsShown) { v.visibility = View.VISIBLE; v.alpha = 0f; v.animate().alpha(1f).setDuration(150).start() }
            else v.animate().alpha(0f).setDuration(150).withEndAction { v.visibility = View.GONE }.start()
        }
    }

    private fun updateLiveLabel() {
        val text = when {
            !r.online() -> "offline"
            !feed.watching -> "paused"
            lastFrameAt <= 0 -> "connecting…"
            else -> "● LIVE · " + Fmt.t(prefs, lastFrameAt)
        }
        liveTv.text = text
        liveTv.setTextColor(if (feed.watching && lastFrameAt > 0 && System.currentTimeMillis() - lastFrameAt < 8000) Ui.RED else Ui.GREY)
    }

    // ------------------------------------------------------------------ the camera's state

    private fun status() = r.status()
    private fun caps() = r.caps() ?: JSONObject()
    private fun live() = status()?.optJSONObject("live") ?: JSONObject()

    /** The camera's settings with values sent from here but not yet confirmed on top. */
    private fun st(): JSONObject {
        val server = status()?.optJSONObject("settings") ?: JSONObject()
        val m = JSONObject(server.toString())
        if (System.currentTimeMillis() - pendingAt > 15_000) pending.keys().asSequence().toList().forEach { pending.remove(it) }
        for (k in pending.keys().asSequence().toList()) {
            if (server.opt(k)?.toString() == pending.opt(k)?.toString()) pending.remove(k) else m.put(k, pending.get(k))
        }
        for (k in draft.keys()) m.put(k, draft.get(k))
        return m
    }

    private fun refreshAll() {
        if (isFinishing) return
        renderStatus()
        refreshChips()
        if (selected != null && !touching) configurePanel()
        renderStart()
        updateLiveLabel()
    }

    private fun renderStatus() {
        val s = status()
        val now = System.currentTimeMillis()
        val mode = s?.optString("mode") ?: ""
        statusTv.setTextColor(if (mode == "RECORDING" && r.online()) Ui.RED else Color.WHITE)
        statusTv.text = when {
            s == null -> r.name() + " · connecting…"
            !r.online() -> r.name() + " · offline"
            mode == "RECORDING" -> { val sec = ((now - s.optLong("recSince")) / 1000).coerceAtLeast(0); "● REC %02d:%02d · %s".format(sec / 60, sec % 60, r.name()) }
            s.optBoolean("monitoring") -> r.name() + " · watching for motion"
            s.optBoolean("standby") -> r.name() + " · standby"
            else -> r.name() + " · not monitoring"
        }
        if (s == null) { infoTv.text = ""; return }
        val parts = ArrayList<CharSequence>()
        parts += s.optString("lens").substringBefore(" [").removePrefix("Rear ").replace(" · multi-cam", "").replaceFirstChar { it.uppercase() }
        parts += s.optString("format")
        parts += "%.0f°C".format(s.optDouble("tempC"))
        if (s.optInt("battery", -1) >= 0) parts += com.motioncam.BatteryDrawable.text(infoTv, s.optInt("battery"), s.optBoolean("charging"))
        s.optLong("lastMotion").takeIf { it > 0 }?.let { parts += "motion " + Fmt.t(prefs, it, seconds = false) }
        infoTv.text = android.text.SpannableStringBuilder().apply {
            parts.filter { it.isNotBlank() }.forEachIndexed { i, x -> if (i > 0) append(" · "); append(x) }
            if (r.holder() != null) append("\n").append(r.remoteText(prefs))
        }
    }

    private fun renderStart() {
        if (!::startBtn.isInitialized) return
        val s = status()
        val (text, color) = when {
            s == null || !r.online() -> "OFFLINE" to 0xFF4A4E55.toInt()
            s.optBoolean("monitoring") -> "STOP MONITORING" to Ui.RED
            s.optBoolean("standby") -> "START MONITORING" to 0xFF2E7D32.toInt()
            else -> "RESUME MONITORING" to 0xFF2E7D32.toInt()
        }
        startBtn.text = if (compact == true) text.substringBefore(" ") else text
        Ui.pill(startBtn, color)
    }

    private fun onStartStop() {
        val s = status() ?: return
        if (!r.online()) { Toast.makeText(this, "The camera is offline", Toast.LENGTH_SHORT).show(); return }
        if (r.lockedOut()) { r.take { refreshAll() }; return }
        when {
            s.optBoolean("monitoring") -> AlertDialog.Builder(this).setTitle("Stop monitoring?")
                .setMessage(if (s.optBoolean("standbyOn")) "The camera closes and the phone goes to standby: you can start monitoring again from here."
                    else "The camera phone stops completely and goes offline. It can only be started again on the phone itself.")
                .setPositiveButton("Stop") { _, _ -> r.send("stop") }.setNegativeButton("Cancel", null).show()
            s.optBoolean("standby") -> r.send("start")
            else -> r.send("resume")
        }
    }

    // ------------------------------------------------------------------ control buttons

    private fun refreshChips() {
        val st = st(); val c = caps(); val lv = live()
        fun set(n: String, v: String, auto: Boolean, enabled: Boolean = true) = chips[n]?.set(v, auto, n == selected, enabled)
        set("LENS", lensLabel(st, c), false)
        val h = st.optInt("resHeight", 1080)
        set("FORMAT", "${if (h >= 2160) "4K" else "${h}p"}${st.optInt("fps", 30)}", false)
        if (st.optBoolean("exposureAuto", true)) set("EV", evText(st.optInt("evIndex")), false) else set("EV", "—", false, enabled = false)
        if (st.optBoolean("exposureAuto", true)) {
            set("ISO", lv.optInt("iso").takeIf { it > 0 }?.toString() ?: "AUTO", true)
            set("SPEED", lv.optLong("shutterNs").takeIf { it > 0 }?.let { shutterText(it) } ?: "AUTO", true)
        } else {
            set("ISO", "${st.optInt("iso", 400)}", false)
            set("SPEED", shutterText(st.optLong("shutterNs", 16_666_666L)), false)
        }
        if (st.optBoolean("focusAuto", true)) set("FOCUS", lv.optDouble("focus", -1.0).takeIf { it >= 0 }?.let { diopterText(it) } ?: "AF", true)
        else set("FOCUS", focusText(st.optDouble("focusPos", 1.0)), false)
        if (st.optBoolean("wbAuto", true)) set("WB", lv.optInt("kelvin").takeIf { it > 0 }?.let { "${it}K" } ?: "AUTO", true)
        else set("WB", "${ProCaps.wbPresets[ProCaps.nearestPreset(st.optInt("wbKelvin", 5200))].second}K", false)
        set("ZOOM", "%.1fx".format(st.optDouble("zoom", 1.0)), false)
    }

    private fun lensLabel(st: JSONObject, c: JSONObject): String {
        val lenses = c.optJSONArray("lenses")
        val label = (0 until (lenses?.length() ?: 0)).map { lenses!!.getJSONObject(it) }
            .firstOrNull { it.optString("key") == st.optString("camera") }?.optString("label") ?: status()?.optString("lens") ?: "—"
        return label.substringBefore(" [").replace("Rear ", "").replace(" · multi-cam", "").ifEmpty { "—" }
    }

    private fun onChip(name: String) {
        // never act on defaults: wait until the camera has said what its settings are
        if (status()?.has("settings") != true) { Toast.makeText(this, "Loading the camera's settings…", Toast.LENGTH_SHORT).show(); return }
        // another phone has the remote: offer to take it over (it is told) instead of changing anything
        if (r.lockedOut()) { r.take { refreshAll() }; return }
        when (name) {
            "LENS" -> { showLensDialog(); return }
            "FORMAT" -> { showFormatDialog(); return }
        }
        selected = if (selected == name) null else name
        panel.visibility = if (selected == null) View.GONE else View.VISIBLE
        if (selected != null) configurePanel()
        refreshChips()
    }

    private fun setAutoBtn(text: String, on: Boolean, visible: Boolean = true) {
        autoBtn.visibility = if (visible) View.VISIBLE else View.GONE
        autoBtn.text = text
        Ui.setChipState(autoBtn, on)
    }

    private val manualSensor get() = caps().optBoolean("manualSensor", true)
    private val minFocus get() = caps().optDouble("minFocus", 0.0)
    private val zMin get() = caps().optDouble("zoomMin", 1.0)
    private val zMax get() = caps().optDouble("zoomMax", 1.0)
    private val isoLo get() = caps().optInt("isoMin", 50).toDouble()
    private val isoHi get() = caps().optInt("isoMax", 3200).toDouble()
    private fun shutterRange(st: JSONObject): Pair<Double, Double> {
        val c = caps()
        val lo = max(c.optLong("expMinNs", 100_000L), 125_000L).toDouble()
        var hi = minOf(c.optLong("expMaxNs", 250_000_000L), 1_000_000_000L / max(st.optInt("fps", 30), 1)).toDouble()
        if (hi <= lo) hi = lo * 2
        return lo to hi
    }

    private fun configurePanel() {
        val st = st(); val c = caps()
        updating = true
        seek.isEnabled = r.online()
        when (selected) {
            "EV" -> {
                setAutoBtn("", false, false)
                val lo = c.optInt("evMin", -8); val hi = c.optInt("evMax", 8)
                seek.max = max(1, hi - lo)
                seek.progress = st.optInt("evIndex").coerceIn(lo, hi) - lo
                seek.isEnabled = seek.isEnabled && st.optBoolean("exposureAuto", true)
            }
            "ISO", "SPEED" -> {
                seek.max = 1000
                setAutoBtn("AUTO", st.optBoolean("exposureAuto", true) || !manualSensor)
                seek.isEnabled = seek.isEnabled && manualSensor
                if (!st.optBoolean("exposureAuto", true)) {
                    val (lo, hi) = shutterRange(st)
                    seek.progress = if (selected == "ISO") toLog(st.optInt("iso", 400).toDouble(), isoLo, isoHi)
                        else toLog(st.optLong("shutterNs", 16_666_666L).toDouble(), lo, hi)
                }
            }
            "FOCUS" -> {
                seek.max = 1000
                setAutoBtn("AUTO", st.optBoolean("focusAuto", true) || minFocus <= 0)
                seek.isEnabled = seek.isEnabled && minFocus > 0
                if (!st.optBoolean("focusAuto", true)) seek.progress = (st.optDouble("focusPos", 1.0) * 1000).roundToInt()
            }
            "WB" -> {
                seek.max = ProCaps.wbPresets.size - 1
                setAutoBtn("AUTO", st.optBoolean("wbAuto", true))
                if (!st.optBoolean("wbAuto", true)) seek.progress = ProCaps.nearestPreset(st.optInt("wbKelvin", 5200))
            }
            "ZOOM" -> {
                val can = zMax > zMin + 0.01
                setAutoBtn("1x", false, can)
                seek.max = 1000
                seek.isEnabled = seek.isEnabled && can
                seek.progress = ((st.optDouble("zoom", 1.0) - zMin) / max(0.01, zMax - zMin) * 1000).roundToInt().coerceIn(0, 1000)
            }
        }
        updating = false
        tickPanel(st)
    }

    /** Value label; while a control is on AUTO the slider follows the camera's live value. */
    private fun tickPanel(st: JSONObject = st()) {
        val sel = selected ?: return
        val lv = live()
        var auto = false
        var follow = -1
        val text = when (sel) {
            "EV" -> if (st.optBoolean("exposureAuto", true)) "EV " + evText(st.optInt("evIndex")) else "EV (manual exposure)"
            "ISO" -> if (st.optBoolean("exposureAuto", true)) {
                auto = true
                val v = lv.optInt("iso")
                if (v > 0) follow = toLog(v.toDouble(), isoLo, isoHi)
                if (v > 0) "ISO $v" else "ISO AUTO"
            } else "ISO ${st.optInt("iso", 400)}"
            "SPEED" -> if (st.optBoolean("exposureAuto", true)) {
                auto = true
                val v = lv.optLong("shutterNs")
                if (v > 0) shutterRange(st).let { (lo, hi) -> follow = toLog(v.toDouble(), lo, hi) }
                if (v > 0) shutterText(v) else "SPEED AUTO"
            } else shutterText(st.optLong("shutterNs", 16_666_666L))
            "FOCUS" -> if (st.optBoolean("focusAuto", true)) {
                auto = true
                val dpt = lv.optDouble("focus", -1.0)
                if (dpt >= 0 && minFocus > 0) follow = ((1 - dpt / minFocus).coerceIn(0.0, 1.0) * 1000).roundToInt()
                if (dpt >= 0) "AF " + diopterText(dpt) else "AF"
            } else "MF " + focusText(st.optDouble("focusPos", 1.0))
            "WB" -> if (st.optBoolean("wbAuto", true)) {
                auto = true
                val k = lv.optInt("kelvin")
                if (k > 0) follow = ProCaps.nearestPreset(k)
                if (k > 0) "AWB ≈${k}K" else "AWB"
            } else ProCaps.wbPresets[ProCaps.nearestPreset(st.optInt("wbKelvin", 5200))].let { "${it.first} ${it.second}K" }
            "ZOOM" -> "Zoom %.1fx".format(st.optDouble("zoom", 1.0))
            else -> ""
        }
        if (valueTv.text != text) valueTv.text = text
        valueTv.setTextColor(if (auto) Ui.ACCENT else Color.WHITE)
        if (follow >= 0 && !touching) { updating = true; seek.progress = follow; updating = false }
    }

    /** Dragging: shown right away, sent when the finger lifts. */
    private fun onSeek(p: Int) {
        val st = st(); val lv = live(); val c = caps()
        val a = JSONObject()
        when (selected) {
            "EV" -> a.put("evIndex", p + c.optInt("evMin", -8))
            "ISO" -> {
                if (st.optBoolean("exposureAuto", true)) seedExposure(a, st, lv)
                a.put("iso", fromLog(p, isoLo, isoHi).roundToInt())
            }
            "SPEED" -> {
                if (st.optBoolean("exposureAuto", true)) seedExposure(a, st, lv)
                val (lo, hi) = shutterRange(st)
                a.put("shutterNs", fromLog(p, lo, hi).toLong())
            }
            "FOCUS" -> a.put("focusAuto", false).put("focusPos", p / 1000.0)
            "WB" -> a.put("wbAuto", false).put("wbKelvin", ProCaps.wbPresets[p.coerceIn(0, ProCaps.wbPresets.size - 1)].second)
            "ZOOM" -> a.put("zoom", ((zMin + (zMax - zMin) * p / 1000.0) * 10).roundToInt() / 10.0)
        }
        stage(a, refresh = false)
        val now = st()
        when (selected) {
            "ISO", "SPEED" -> setAutoBtn("AUTO", now.optBoolean("exposureAuto", true))
            "FOCUS" -> setAutoBtn("AUTO", now.optBoolean("focusAuto", true))
            "WB" -> setAutoBtn("AUTO", now.optBoolean("wbAuto", true))
        }
        tickPanel(now)
        refreshChips()
    }

    /** Switching to manual keeps exactly what AUTO was using, so the picture does not jump. */
    private fun seedExposure(a: JSONObject, st: JSONObject, lv: JSONObject) {
        a.put("exposureAuto", false)
        a.put("iso", lv.optInt("iso").takeIf { it > 0 } ?: st.optInt("iso", 400))
        val (lo, hi) = shutterRange(st)
        a.put("shutterNs", (lv.optLong("shutterNs").takeIf { it > 0 } ?: st.optLong("shutterNs", 16_666_666L)).toDouble().coerceIn(lo, hi).toLong())
    }

    private fun onAuto() {
        val st = st(); val lv = live()
        val a = JSONObject()
        when (selected) {
            "ISO", "SPEED" -> if (!manualSensor) {
                Toast.makeText(this, "This lens does not allow manual ISO / shutter speed", Toast.LENGTH_SHORT).show(); return
            } else if (st.optBoolean("exposureAuto", true)) seedExposure(a, st, lv) else a.put("exposureAuto", true)
            "FOCUS" -> if (minFocus <= 0) {
                Toast.makeText(this, "This lens is fixed-focus (no manual focus)", Toast.LENGTH_SHORT).show(); return
            } else if (st.optBoolean("focusAuto", true)) {
                val dpt = lv.optDouble("focus", -1.0)
                a.put("focusAuto", false)
                if (dpt >= 0) a.put("focusPos", (1 - dpt / minFocus).coerceIn(0.0, 1.0))
            } else a.put("focusAuto", true)
            "WB" -> if (st.optBoolean("wbAuto", true)) {
                val k = lv.optInt("kelvin")
                a.put("wbAuto", false)
                if (k > 0) a.put("wbKelvin", ProCaps.wbPresets[ProCaps.nearestPreset(k)].second)
            } else a.put("wbAuto", true)
            "ZOOM" -> a.put("zoom", 1.0.coerceIn(zMin, zMax))
            else -> return
        }
        apply(a)
    }

    /** Lines up a change (shown right away on the buttons and slider); sent with Save. */
    private fun apply(a: JSONObject) = stage(a, refresh = true)

    private fun stage(a: JSONObject, refresh: Boolean) {
        val server = status()?.optJSONObject("settings") ?: JSONObject()
        for (k in a.keys()) {
            // back to what the camera already has: nothing to send for it
            if (server.opt(k)?.toString() == a.opt(k)?.toString() && pending.opt(k) == null) draft.remove(k) else draft.put(k, a.get(k))
        }
        if (refresh) refreshAll()
        updateSaveBar()
    }

    private fun updateSaveBar() {
        if (!::saveBar.isInitialized) return
        val n = draft.length()
        val show = n > 0 || saving
        saveInfo.text = if (saving) "Sending…" else "$n change${if (n == 1) "" else "s"} not sent yet"
        saveBtn.isEnabled = !saving; saveBtn.alpha = if (saving) 0.5f else 1f
        saveBar.visibility = if (show) View.VISIBLE else View.GONE
    }

    /** Sends every lined-up change at once; shown until the camera confirms, taken back if it refuses. */
    private fun saveDraft() {
        if (draft.length() == 0 || saving) return
        val a = JSONObject(draft.toString())
        for (k in a.keys()) pending.put(k, a.get(k))
        pendingAt = System.currentTimeMillis()
        draft.keys().asSequence().toList().forEach { draft.remove(it) }
        saving = true; updateSaveBar(); refreshAll()
        fun undo(keys: Collection<String>) { keys.forEach { if (pending.opt(it)?.toString() == a.opt(it)?.toString()) pending.remove(it) }; refreshAll() }
        r.send("set", a, quiet = false, onFail = {
            saving = false
            // not sent: put the changes back so nothing is lost
            for (k in a.keys()) if (!draft.has(k)) draft.put(k, a.get(k))
            undo(a.keys().asSequence().toList()); updateSaveBar()
        }) { ok, res ->
            saving = false
            if (!ok) {
                val applied = res?.optJSONArray("applied")?.let { arr -> (0 until arr.length()).map { arr.getString(it) } } ?: emptyList()
                undo(a.keys().asSequence().toList() - applied.toSet())
            } else pendingAt = System.currentTimeMillis()
            updateSaveBar()
        }
    }

    private fun cancelDraft() {
        draft.keys().asSequence().toList().forEach { draft.remove(it) }
        updateSaveBar(); refreshAll()
    }

    /** Leaving with changes not sent asks first. */
    override fun finish() {
        if (draft.length() == 0 || leaveOk) { super.finish(); return }
        AlertDialog.Builder(this).setTitle("Changes not sent")
            .setMessage("${draft.length()} change${if (draft.length() == 1) "" else "s"} made here ha${if (draft.length() == 1) "s" else "ve"} not been sent to the camera.")
            .setPositiveButton("Save") { _, _ -> saveDraft() }
            .setNegativeButton("Discard") { _, _ -> leaveOk = true; finish() }
            .setNeutralButton("Keep editing", null).show()
    }

    /** Tap the value: type the exact number (as on the camera screen). */
    private fun askExact() {
        val sel = selected ?: return
        val st = st(); val c = caps(); val lv = live()
        if (sel == "EV" && !st.optBoolean("exposureAuto", true)) return
        val evStep = c.optDouble("evStep", 0.1667)
        val (title, cur) = when (sel) {
            "EV" -> "Exposure compensation (EV)" to "%.1f".format(st.optInt("evIndex") * evStep)
            "ISO" -> "ISO (${isoLo.roundToInt()}–${isoHi.roundToInt()})" to "${lv.optInt("iso").takeIf { it > 0 && st.optBoolean("exposureAuto", true) } ?: st.optInt("iso", 400)}"
            "SPEED" -> "Shutter speed: 1 / …" to "${(1e9 / (lv.optLong("shutterNs").takeIf { it > 0 && st.optBoolean("exposureAuto", true) } ?: st.optLong("shutterNs", 16_666_666L))).roundToInt()}"
            "FOCUS" -> "Focus distance in metres (empty = infinity)" to ""
            "WB" -> "White balance in Kelvin (snaps to the nearest preset)" to "${st.optInt("wbKelvin", 5200)}"
            "ZOOM" -> "Zoom (%.1f–%.1f)".format(zMin, zMax) to "%.1f".format(st.optDouble("zoom", 1.0))
            else -> return
        }
        val input = android.widget.EditText(this).apply {
            setText(cur); setSelectAllOnFocus(true)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
        }
        AlertDialog.Builder(this).setTitle(title).setView(input).setPositiveButton("Set") { _, _ ->
            val t = input.text.toString().trim().replace(',', '.')
            val v = t.toDoubleOrNull()
            val a = JSONObject()
            when (sel) {
                "EV" -> v?.let { a.put("evIndex", (it / evStep).roundToInt().coerceIn(c.optInt("evMin", -12), c.optInt("evMax", 12))) }
                "ISO" -> v?.let { if (st.optBoolean("exposureAuto", true)) seedExposure(a, st, lv); a.put("iso", it.coerceIn(isoLo, isoHi).roundToInt()) }
                "SPEED" -> v?.takeIf { it > 0 }?.let {
                    if (st.optBoolean("exposureAuto", true)) seedExposure(a, st, lv)
                    val (lo, hi) = shutterRange(st); a.put("shutterNs", (1e9 / it).coerceIn(lo, hi).toLong())
                }
                "FOCUS" -> if (minFocus > 0) {
                    if (t.isEmpty()) a.put("focusAuto", false).put("focusPos", 1.0)
                    else v?.takeIf { it > 0 }?.let { a.put("focusAuto", false).put("focusPos", (1 - (1 / it) / minFocus).coerceIn(0.0, 1.0)) }
                }
                "WB" -> v?.let { a.put("wbAuto", false).put("wbKelvin", ProCaps.wbPresets[ProCaps.nearestPreset(it.roundToInt())].second) }
                "ZOOM" -> v?.let { a.put("zoom", it.coerceIn(zMin, zMax)) }
            }
            if (a.length() > 0) apply(a) else Toast.makeText(this, "Not a valid number", Toast.LENGTH_SHORT).show()
        }.setNegativeButton("Cancel", null).show()
    }

    private fun showLensDialog() {
        val lenses = caps().optJSONArray("lenses")
        val list = (0 until (lenses?.length() ?: 0)).map { lenses!!.getJSONObject(it) }
        if (list.isEmpty()) { Toast.makeText(this, "The camera hasn't shared its lenses yet", Toast.LENGTH_SHORT).show(); return }
        val cur = st().optString("camera")
        OptionSheet.show(this, "Camera / lens", list.map { l ->
            val label = l.optString("label")
            Opt(label.substringBefore(" ["), label.substringAfter(" [", "").removeSuffix("]").ifEmpty { null }, checked = l.optString("key") == cur) {
                apply(JSONObject().put("camera", l.optString("key")).put("zoom", 1.0))
            }
        })
    }

    private fun showFormatDialog() {
        val fpsOk = caps().optJSONArray("fps")?.let { a -> (0 until a.length()).map { a.getInt(it) } } ?: listOf(24, 30, 60)
        val opts = listOf(
            Triple("720p · 30 fps", "Smallest files, least heat", 720 to 30),
            Triple("1080p · 24 fps", "Slightly smaller than 30 fps", 1080 to 24),
            Triple("1080p · 30 fps", "Recommended", 1080 to 30),
            Triple("1080p · 60 fps", "Smoother motion, bigger files, warmer phone", 1080 to 60),
            Triple("4K · 30 fps", "Most detail; about 3× the size of 1080p", 2160 to 30),
        ).filter { it.third.second in fpsOk }
        val st = st()
        OptionSheet.show(this, "Recording format", opts.map { (t, desc, v) ->
            Opt(t, desc, checked = v.first == st.optInt("resHeight", 1080) && v.second == st.optInt("fps", 30)) {
                apply(JSONObject().put("resHeight", v.first).put("fps", v.second))
            }
        }, subtitle = "The camera restarts briefly to apply it.")
    }

    // ------------------------------------------------------------------ conversions (as on the camera screen)

    private fun toLog(v: Double, lo: Double, hi: Double) = (ln(v.coerceIn(lo, hi) / lo) / ln(hi / lo) * 1000).roundToInt().coerceIn(0, 1000)
    private fun fromLog(p: Int, lo: Double, hi: Double) = lo * exp(ln(hi / lo) * p / 1000.0)
    private fun evText(idx: Int): String {
        val v = idx * caps().optDouble("evStep", 0.1667)
        return (if (v > 0.001) "+" else "") + "%.1f".format(v)
    }
    private fun shutterText(ns: Long): String {
        if (ns <= 0) return "—"
        val den = 1_000_000_000.0 / ns
        return if (den >= 1.5) "1/${den.roundToInt()}" else "%.1fs".format(ns / 1e9)
    }
    /** Focus distance, always in metres (as on the camera screen), ∞ for infinity. */
    private fun diopterText(dpt: Double): String {
        if (dpt < 0.1) return "∞"
        val m = 1 / dpt
        return if (m < 10) "%.2fm".format(m) else "%.1fm".format(m)
    }
    private fun focusText(pos: Double) = diopterText((1 - pos) * (if (minFocus > 0) minFocus else 10.0))
}
