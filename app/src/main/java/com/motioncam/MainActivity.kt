package com.motioncam

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.*
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt

class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var frame: AspectFrame
    private lateinit var texture: TextureView
    private lateinit var overlay: ImageView
    private lateinit var offLabel: TextView
    private lateinit var statusTv: TextView
    private lateinit var infoTv: TextView
    private lateinit var startBtn: TextView
    private lateinit var panel: LinearLayout
    private lateinit var valueTv: TextView
    private lateinit var seek: SeekBar
    private lateinit var autoBtn: TextView
    private val chips = LinkedHashMap<String, Chip>()
    private var selected: String? = null
    private var updating = false
    private var touching = false
    private var resumed = false
    private var attached = false
    private var askedPermission = false
    private var st: SurfaceTexture? = null
    private var paintValue = true
    private val ui = Handler(Looper.getMainLooper())

    private val statusListener: (Status) -> Unit = { s -> runOnUiThread { render(s) } }
    private val caps get() = ProCaps.forPrefs(this, prefs)
    private val engine get() = EngineHost.peek()

    private val ticker = object : Runnable {
        override fun run() {
            refreshChips()
            tickPanel()
            ui.postDelayed(this, 250)
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) {}
        override fun onDisplayRemoved(id: Int) {}
        override fun onDisplayChanged(id: Int) { frame.post { updateGeometry() } }
    }

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
        resumed = true
        StatusBus.add(statusListener)
        updateFlags()
        (getSystemService(DISPLAY_SERVICE) as DisplayManager).registerDisplayListener(displayListener, ui)
        updateGeometry()
        if (!hasCamera() && !askedPermission) {
            askedPermission = true
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 2)
        }
        attach()
        render(StatusBus.status)
        if (selected != null) configurePanel()
        ui.post(ticker)
    }

    override fun onPause() {
        resumed = false
        ui.removeCallbacks(ticker)
        (getSystemService(DISPLAY_SERVICE) as DisplayManager).unregisterDisplayListener(displayListener)
        FrameHub.overlaySink = null
        FrameHub.peaking = false
        FrameHub.showMotion = false
        overlay.setImageDrawable(null)
        detach()
        StatusBus.remove(statusListener)
        super.onPause()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        frame.post { updateGeometry() }
    }

    private fun hasCamera() = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    // ------------------------------------------------------------------ camera attach / preview

    private fun attach() {
        offLabel.visibility = if (prefs.previewOn && hasCamera()) View.GONE else View.VISIBLE
        offLabel.text = if (!hasCamera()) "Camera permission needed" else "Preview is off (Settings).\nControls still apply to recordings."
        if (attached || !resumed || !prefs.previewOn || !hasCamera()) return
        attached = true
        FrameHub.overlaySink = { bmp -> overlay.post { overlay.setImageBitmap(bmp) } }
        val e = EngineHost.acquire(this, "ui")
        st?.let { e.setPreview(it) }
    }

    private fun detach() {
        if (!attached) return
        attached = false
        FrameHub.overlaySink = null
        engine?.setPreview(null)
        EngineHost.release("ui")
    }

    private fun displayRot(): Int = display?.rotation ?: Surface.ROTATION_0

    private fun imageRotation(): Int {
        val c = caps ?: return 90
        return Rot.image(c.sensorOrientation, c.front, Rot.displayDegrees(displayRot()))
    }

    private fun updateGeometry() {
        frame.aspect = if (imageRotation() % 180 != 0) 9f / 16f else 16f / 9f
        updateTransform()
    }

    /** Standard Camera2 TextureView transform: compensates for display rotation. */
    private fun updateTransform() {
        val w = texture.width.toFloat()
        val h = texture.height.toFloat()
        if (w <= 0f || h <= 0f) return
        val rot = displayRot()
        val m = Matrix()
        val cx = w / 2f
        val cy = h / 2f
        if (rot == Surface.ROTATION_90 || rot == Surface.ROTATION_270) {
            val pW = 1920f
            val pH = 1080f
            val viewRect = RectF(0f, 0f, w, h)
            val bufferRect = RectF(0f, 0f, pH, pW)
            bufferRect.offset(cx - bufferRect.centerX(), cy - bufferRect.centerY())
            m.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
            val scale = max(h / pH, w / pW)
            m.postScale(scale, scale, cx, cy)
            m.postRotate(90f * (rot - 2), cx, cy)
        } else if (rot == Surface.ROTATION_180) {
            m.postRotate(180f, cx, cy)
        }
        texture.setTransform(m)
    }

    private fun updateFlags() {
        FrameHub.peaking = resumed && prefs.peaking && selected == "FOCUS"
        FrameHub.showMotion = resumed && selected == "ZONES"
    }

    // ------------------------------------------------------------------ UI construction

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        frame = AspectFrame(this)
        texture = TextureView(this)
        overlay = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_XY }
        frame.addView(texture, FrameLayout.LayoutParams(-1, -1))
        frame.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        root.addView(frame, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        offLabel = Ui.label(this, "", 15f, Ui.GREY).apply { gravity = Gravity.CENTER; visibility = View.GONE }
        root.addView(offLabel, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))

        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) {
                st = s
                updateTransform()
                if (attached) engine?.setPreview(s)
            }
            override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) { updateTransform() }
            override fun onSurfaceTextureDestroyed(s: SurfaceTexture): Boolean {
                if (st === s) st = null
                val e = engine ?: return true
                e.detachPreview(s) { s.release() }   // release only once the camera has let go of it
                return false
            }
            override fun onSurfaceTextureUpdated(s: SurfaceTexture) {}
        }
        overlay.setOnTouchListener { v, ev -> onOverlayTouch(v, ev) }

        // top bar
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Ui.PANEL)
            val p = Ui.dp(this@MainActivity, 10)
            setPadding(p + 2, p - 2, p + 2, p - 2)
            gravity = Gravity.CENTER_VERTICAL
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        statusTv = Ui.label(this, "", 15f)
        infoTv = Ui.label(this, "", 11f, 0xFFB0B4BA.toInt())
        texts.addView(statusTv)
        texts.addView(infoTv)
        top.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(Ui.chip(this, "Clips").apply { setOnClickListener { startActivity(Intent(this@MainActivity, ClipsActivity::class.java)) } })
        top.addView(Space(this), Ui.dp(this, 6), 1)
        top.addView(Ui.chip(this, "Settings").apply { setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) } })
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
        valueTv = Ui.label(this, "", 14f, Color.WHITE).apply {
            minWidth = Ui.dp(this@MainActivity, 104)
            setOnClickListener { askExact() }
        }
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
        for (name in listOf("LENS", "FORMAT", "EV", "ISO", "SPEED", "FOCUS", "WB", "ZOOM", "ZONES")) {
            val c = Chip(this, name)
            c.view.setOnClickListener { onChip(name) }
            chips[name] = c
            row.addView(c.view, LinearLayout.LayoutParams(-2, -2).apply {
                setMargins(Ui.dp(this@MainActivity, 3), 0, Ui.dp(this@MainActivity, 3), 0)
            })
        }
        strip.addView(row)
        bottom.addView(strip)

        startBtn = Ui.label(this, "START MONITORING", 16f).apply {
            gravity = Gravity.CENTER
            setPadding(0, Ui.dp(this@MainActivity, 12), 0, Ui.dp(this@MainActivity, 12))
            setOnClickListener { onStartStop() }
        }
        Ui.pill(startBtn, 0xFF2E7D32.toInt())
        bottom.addView(startBtn, LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(Ui.dp(this@MainActivity, 12), Ui.dp(this@MainActivity, 8), Ui.dp(this@MainActivity, 12), 0)
        })
        root.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) { if (fromUser && !updating) onSeek(p) }
            override fun onStartTrackingTouch(sb: SeekBar) { touching = true }
            override fun onStopTrackingTouch(sb: SeekBar) { touching = false }
        })
        autoBtn.setOnClickListener { onAuto() }
        setContentView(root)
    }

    // ------------------------------------------------------------------ start / stop / permissions

    private fun onStartStop() {
        if (CameraService.instance != null) {
            CameraService.stop(this)
            startBtn.postDelayed({ render(StatusBus.status) }, 300)
            return
        }
        val need = ArrayList<String>()
        if (!hasCamera()) need += Manifest.permission.CAMERA
        if (prefs.audio && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) need += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) need += Manifest.permission.POST_NOTIFICATIONS
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 1) else launchService()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (!hasCamera()) { Toast.makeText(this, "Camera permission is required", Toast.LENGTH_LONG).show(); return }
        attach()
        if (requestCode == 1) launchService()
    }

    private fun launchService() {
        CameraService.start(this)
        startBtn.postDelayed({ render(StatusBus.status) }, 500)
    }

    // ------------------------------------------------------------------ status

    private fun render(s: Status) {
        val running = CameraService.instance != null
        startBtn.text = if (running) "STOP MONITORING" else "START MONITORING"
        Ui.pill(startBtn, if (running) Ui.RED else 0xFF2E7D32.toInt())
        val now = System.currentTimeMillis()
        statusTv.setTextColor(if (s.mode == Mode.RECORDING) Ui.RED else Color.WHITE)
        statusTv.text = when {
            s.mode == Mode.RECORDING -> {
                val sec = (now - s.recordingSinceWall) / 1000
                "● REC %02d:%02d".format(sec / 60, sec % 60)
            }
            s.mode == Mode.WATCHING -> "Watching for motion"
            s.mode == Mode.PREVIEW -> "Preview · not monitoring"
            s.mode == Mode.OFF -> if (running) "Starting…" else "Not monitoring"
            else -> s.message.ifEmpty { s.mode.name }
        }
        val sb = StringBuilder()
        caps?.let { sb.append(it.lens.label).append(" · ") }
        sb.append(s.format.ifEmpty { "${prefs.resHeight}p ${prefs.fps} fps" })
        if (s.batteryPct >= 0) sb.append("\nBattery ${s.batteryPct}%${if (s.charging) " ⚡" else ""} · ${"%.0f".format(s.tempC)}°C")
        if (running && s.lastMotionWall > 0) sb.append(" · last motion ${(now - s.lastMotionWall) / 1000}s ago")
        if (running && prefs.serverOn) {
            val addrs = WebServer.localAddresses()
            if (addrs.isNotEmpty()) sb.append("\nRemote: ").append(addrs.joinToString("  ") { "http://${it.second}:${prefs.port}" })
        }
        if (s.message.isNotEmpty() && s.mode != Mode.RECORDING && s.mode != Mode.WATCHING && statusTv.text != s.message) sb.append("\n").append(s.message)
        infoTv.text = sb.toString()
    }

    // ------------------------------------------------------------------ chips (with live values)

    private fun fpsNow(c: ProCaps) = if (c.supportsFps(prefs.fps)) prefs.fps else 30
    private fun liveOk(): Boolean { val e = engine ?: return false; return e.mode != Mode.OFF && e.mode != Mode.STARTING && e.mode != Mode.ERROR }

    private fun refreshChips() {
        val c = caps
        val e = engine
        val live = liveOk() && e != null
        fun set(n: String, v: String, auto: Boolean, enabled: Boolean = true) = chips[n]?.set(v, auto, n == selected, enabled)
        set("LENS", c?.lens?.label?.substringBefore(" [")?.replace("Rear ", "")?.replace(" · multi-cam", "") ?: "—", false)
        set("FORMAT", "${if (prefs.resHeight >= 2160) "4K" else "${prefs.resHeight}p"}${prefs.fps}", false)
        set("EV", evText(c, prefs.evIndex), false, enabled = prefs.exposureAuto)
        if (prefs.exposureAuto) {
            set("ISO", if (live && e!!.liveIso > 0) "${e.liveIso}" else "AUTO", true)
            set("SPEED", if (live && e!!.liveShutterNs > 0) shutterText(e.liveShutterNs) else "AUTO", true)
        } else {
            set("ISO", "${prefs.iso}", false)
            set("SPEED", shutterText(prefs.shutterNs), false)
        }
        if (prefs.focusAuto) set("FOCUS", if (live && e!!.liveFocusDiopter >= 0f) diopterText(e.liveFocusDiopter) else "AF", true)
        else set("FOCUS", focusText(c, prefs.focusPos), false)
        if (prefs.wbAuto) set("WB", if (live && e!!.liveKelvin > 0) "${e.liveKelvin}K" else "AUTO", true)
        else set("WB", "${ProCaps.wbPresets[ProCaps.nearestPreset(prefs.wbKelvin)].second}K", false)
        set("ZOOM", "%.1fx".format(prefs.zoom), false)
        set("ZONES", if (prefs.zones.isEmpty()) "all" else "custom", false)
    }

    private fun onChip(name: String) {
        when (name) {
            "LENS" -> { showLensDialog(); return }
            "FORMAT" -> { showFormatDialog(); return }
        }
        selected = if (selected == name) null else name
        panel.visibility = if (selected == null) View.GONE else View.VISIBLE
        updateFlags()
        if (selected != null) configurePanel()
        refreshChips()
    }

    private fun setAutoBtn(text: String, on: Boolean, visible: Boolean = true) {
        autoBtn.visibility = if (visible) View.VISIBLE else View.GONE
        autoBtn.text = text
        Ui.setChipState(autoBtn, on)
    }

    /** Sets slider range and position for the selected control. */
    private fun configurePanel() {
        val c = caps
        updating = true
        seek.isEnabled = true
        if (c == null) {
            valueTv.text = "No camera"
            seek.isEnabled = false
            setAutoBtn("AUTO", false, false)
            updating = false
            return
        }
        when (selected) {
            "EV" -> {
                setAutoBtn("", false, false)
                seek.max = max(1, c.evMax - c.evMin)
                seek.progress = prefs.evIndex.coerceIn(c.evMin, c.evMax) - c.evMin
                seek.isEnabled = prefs.exposureAuto
            }
            "ISO", "SPEED" -> {
                seek.max = 1000
                setAutoBtn("AUTO", prefs.exposureAuto, c.manualSensor)
                seek.isEnabled = c.manualSensor
                if (!prefs.exposureAuto) seek.progress = if (selected == "ISO") isoProgress(c, prefs.iso) else shutterProgress(c, prefs.shutterNs)
            }
            "FOCUS" -> {
                seek.max = 1000
                setAutoBtn("AUTO", prefs.focusAuto, c.canManualFocus)
                seek.isEnabled = c.canManualFocus
                if (!prefs.focusAuto) seek.progress = (prefs.focusPos * 1000).roundToInt()
            }
            "WB" -> {
                seek.max = ProCaps.wbPresets.size - 1
                setAutoBtn("AUTO", prefs.wbAuto)
                if (!prefs.wbAuto) seek.progress = ProCaps.nearestPreset(prefs.wbKelvin)
            }
            "ZOOM" -> {
                setAutoBtn("1x", false, c.canZoom)
                seek.max = 1000
                seek.isEnabled = c.canZoom
                seek.progress = ((prefs.zoom - c.zoomMin) / max(0.01f, c.zoomMax - c.zoomMin) * 1000).roundToInt().coerceIn(0, 1000)
            }
            "ZONES" -> {
                setAutoBtn("RESET", false)
                seek.max = 9
                seek.progress = prefs.sensitivity - 1
            }
        }
        updating = false
        tickPanel()
    }

    /** Updates the value label; while a control is on AUTO the slider follows the live value. */
    private fun tickPanel() {
        val c = caps ?: return
        val sel = selected ?: return
        val e = engine
        val live = liveOk() && e != null
        var auto = false
        val text: String
        var follow = -1
        when (sel) {
            "EV" -> text = if (prefs.exposureAuto) "EV " + evText(c, prefs.evIndex) else "EV: needs AUTO ISO/SPEED"
            "ISO" -> if (prefs.exposureAuto) {
                auto = true
                val v = if (live) e!!.liveIso else 0
                text = if (v > 0) "ISO $v" else "ISO AUTO"
                if (v > 0) follow = isoProgress(c, v)
            } else text = "ISO ${prefs.iso}"
            "SPEED" -> if (prefs.exposureAuto) {
                auto = true
                val v = if (live) e!!.liveShutterNs else 0L
                text = if (v > 0) shutterText(v) else "SPEED AUTO"
                if (v > 0) follow = shutterProgress(c, v)
            } else text = shutterText(prefs.shutterNs)
            "FOCUS" -> if (prefs.focusAuto) {
                auto = true
                val d = if (live) e!!.liveFocusDiopter else -1f
                text = if (d >= 0f) "AF " + diopterText(d) else "AF"
                if (d >= 0f && c.minFocusDiopter > 0f) follow = ((1f - d / c.minFocusDiopter).coerceIn(0f, 1f) * 1000).roundToInt()
            } else text = "MF " + focusText(c, prefs.focusPos)
            "WB" -> if (prefs.wbAuto) {
                auto = true
                val k = if (live) e!!.liveKelvin else 0
                text = if (k > 0) "AWB ≈${k}K" else "AWB"
                if (k > 0) follow = ProCaps.nearestPreset(k)
            } else text = ProCaps.wbPresets[ProCaps.nearestPreset(prefs.wbKelvin)].let { "${it.first} ${it.second}K" }
            "ZOOM" -> text = "Zoom %.1fx".format(prefs.zoom)
            "ZONES" -> text = "Sensitivity ${prefs.sensitivity} · drag on image to mask areas"
            else -> text = ""
        }
        if (valueTv.text != text) valueTv.text = text
        valueTv.setTextColor(if (auto) Ui.ACCENT else Color.WHITE)
        if (follow >= 0 && !touching) { updating = true; seek.progress = follow; updating = false }
    }

    private fun onAuto() {
        val c = caps ?: return
        when (selected) {
            "ISO", "SPEED" -> if (prefs.exposureAuto) seedManualExposure(c) else prefs.exposureAuto = true
            "FOCUS" -> if (prefs.focusAuto) seedManualFocus(c) else prefs.focusAuto = true
            "WB" -> if (prefs.wbAuto) seedManualWb() else prefs.wbAuto = true
            "ZOOM" -> prefs.zoom = 1f.coerceIn(c.zoomMin, c.zoomMax)
            "ZONES" -> prefs.zones = ""
        }
        engine?.applyPro()
        configurePanel()
        refreshChips()
    }

    /** Switching to manual keeps exactly what AUTO was using, so the picture does not jump. */
    private fun seedManualExposure(c: ProCaps) {
        val e = engine
        val (lo, hi) = c.shutterRange(fpsNow(c))
        if (e != null && e.liveIso > 0) prefs.iso = e.liveIso.coerceIn(c.isoMin, c.isoMax)
        if (e != null && e.liveShutterNs > 0) prefs.shutterNs = e.liveShutterNs.coerceIn(lo, hi)
        else prefs.shutterNs = prefs.shutterNs.coerceIn(lo, hi)
        prefs.exposureAuto = false
    }

    private fun seedManualFocus(c: ProCaps) {
        val d = engine?.liveFocusDiopter ?: -1f
        if (d >= 0f && c.minFocusDiopter > 0f) prefs.focusPos = (1f - d / c.minFocusDiopter).coerceIn(0f, 1f)
        prefs.focusAuto = false
    }

    private fun seedManualWb() {
        val k = engine?.liveKelvin ?: 0
        if (k > 0) prefs.wbKelvin = ProCaps.wbPresets[ProCaps.nearestPreset(k)].second
        prefs.wbAuto = false
    }

    private fun onSeek(p: Int) {
        val c = caps ?: return
        when (selected) {
            "EV" -> prefs.evIndex = p + c.evMin
            "ISO" -> { if (prefs.exposureAuto) seedManualExposure(c); prefs.iso = isoFromProgress(c, p) }
            "SPEED" -> { if (prefs.exposureAuto) seedManualExposure(c); prefs.shutterNs = shutterFromProgress(c, p) }
            "FOCUS" -> { if (prefs.focusAuto) prefs.focusAuto = false; prefs.focusPos = p / 1000f }
            "WB" -> { prefs.wbAuto = false; prefs.wbKelvin = ProCaps.wbPresets[p].second }
            "ZOOM" -> prefs.zoom = c.zoomMin + (c.zoomMax - c.zoomMin) * p / 1000f
            "ZONES" -> prefs.sensitivity = p + 1
        }
        engine?.applyPro()
        when (selected) {
            "ISO", "SPEED" -> setAutoBtn("AUTO", prefs.exposureAuto)
            "FOCUS" -> setAutoBtn("AUTO", prefs.focusAuto)
            "WB" -> setAutoBtn("AUTO", prefs.wbAuto)
        }
        tickPanel()
        refreshChips()
    }

    // ------------------------------------------------------------------ exact value entry

    private fun askExact() {
        val c = caps ?: return
        val sel = selected ?: return
        val (title, hint, cur) = when (sel) {
            "EV" -> Triple("Exposure compensation (EV)", "e.g. -0.7", "%.1f".format(prefs.evIndex * c.evStep))
            "ISO" -> Triple("ISO (${c.isoMin}–${c.isoMax})", "e.g. 400", "${if (prefs.exposureAuto) engine?.liveIso ?: prefs.iso else prefs.iso}")
            "SPEED" -> Triple("Shutter speed: 1 / …", "e.g. 60 for 1/60 s", "${(1e9 / (if (prefs.exposureAuto) engine?.liveShutterNs?.takeIf { it > 0 } ?: prefs.shutterNs else prefs.shutterNs)).roundToInt()}")
            "FOCUS" -> Triple("Focus distance in cm (empty = infinity)", "e.g. 150", "")
            "WB" -> Triple("White balance in Kelvin (snaps to nearest preset)", "e.g. 5200", "${prefs.wbKelvin}")
            "ZOOM" -> Triple("Zoom (%.1f–%.1f)".format(c.zoomMin, c.zoomMax), "e.g. 2.0", "%.1f".format(prefs.zoom))
            "ZONES" -> Triple("Motion sensitivity (1–10)", "5", "${prefs.sensitivity}")
            else -> return
        }
        val input = EditText(this).apply {
            setText(cur)
            this.hint = hint
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            setSelectAllOnFocus(true)
        }
        AlertDialog.Builder(this).setTitle(title).setView(input)
            .setPositiveButton("Set") { _, _ ->
                val v = input.text.toString().trim().replace(',', '.').removePrefix("1/").toDoubleOrNull()
                when (sel) {
                    "EV" -> if (v != null) prefs.evIndex = (v / c.evStep).roundToInt().coerceIn(c.evMin, c.evMax)
                    "ISO" -> if (v != null) { if (prefs.exposureAuto) seedManualExposure(c); prefs.iso = v.toInt().coerceIn(c.isoMin, c.isoMax) }
                    "SPEED" -> if (v != null && v > 0) {
                        if (prefs.exposureAuto) seedManualExposure(c)
                        val (lo, hi) = c.shutterRange(fpsNow(c))
                        prefs.shutterNs = (1e9 / v).toLong().coerceIn(lo, hi)
                    }
                    "FOCUS" -> if (c.canManualFocus) {
                        val d = if (v == null || v <= 0) 0f else (100.0 / v).toFloat()
                        prefs.focusPos = (1f - d / c.minFocusDiopter).coerceIn(0f, 1f)
                        prefs.focusAuto = false
                    }
                    "WB" -> if (v != null) { prefs.wbKelvin = ProCaps.wbPresets[ProCaps.nearestPreset(v.toInt())].second; prefs.wbAuto = false }
                    "ZOOM" -> if (v != null) prefs.zoom = v.toFloat().coerceIn(c.zoomMin, c.zoomMax)
                    "ZONES" -> if (v != null) prefs.sensitivity = v.toInt().coerceIn(1, 10)
                }
                engine?.applyPro()
                configurePanel()
                refreshChips()
            }
            .setNegativeButton("Cancel", null).show()
    }

    // ------------------------------------------------------------------ zones editor

    private fun onOverlayTouch(v: View, ev: MotionEvent): Boolean {
        if (selected != "ZONES") return false
        if (v.width == 0 || v.height == 0) return true
        val u = ev.x / v.width
        val w = ev.y / v.height
        if (u < 0f || u >= 1f || w < 0f || w >= 1f) return true
        val (xn, yn) = Zones.unrotate(u, w, imageRotation())
        val cx = (xn * Zones.CW).toInt().coerceIn(0, Zones.CW - 1)
        val cy = (yn * Zones.CH).toInt().coerceIn(0, Zones.CH - 1)
        val cells = Zones.parse(prefs.zones)
        val idx = cy * Zones.CW + cx
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) paintValue = !cells[idx]
        if (cells[idx] != paintValue) {
            cells[idx] = paintValue
            prefs.zones = Zones.format(cells)
        }
        return true
    }

    // ------------------------------------------------------------------ conversions

    private fun isoProgress(c: ProCaps, iso: Int) = logToProgress(iso.toDouble(), c.isoMin.toDouble(), c.isoMax.toDouble())
    private fun isoFromProgress(c: ProCaps, p: Int) = progressToLog(p, c.isoMin.toDouble(), c.isoMax.toDouble()).roundToInt()
    private fun shutterProgress(c: ProCaps, ns: Long): Int {
        val (lo, hi) = c.shutterRange(fpsNow(c))
        return logToProgress(ns.toDouble(), lo.toDouble(), hi.toDouble())
    }
    private fun shutterFromProgress(c: ProCaps, p: Int): Long {
        val (lo, hi) = c.shutterRange(fpsNow(c))
        return progressToLog(p, lo.toDouble(), hi.toDouble()).toLong()
    }

    private fun evText(c: ProCaps?, idx: Int): String {
        val v = idx * (c?.evStep ?: 0.1667f)
        return (if (v > 0.001f) "+" else "") + "%.1f".format(v)
    }

    private fun shutterText(ns: Long): String {
        if (ns <= 0) return "—"
        val den = 1_000_000_000.0 / ns
        return if (den >= 1.5) "1/${den.roundToInt()}" else "%.1fs".format(ns / 1e9)
    }

    private fun diopterText(d: Float): String {
        if (d < 0.1f) return "∞"
        val cm = 100f / d
        return if (cm >= 100f) "%.1fm".format(cm / 100f) else "%.0fcm".format(cm)
    }

    private fun focusText(c: ProCaps?, pos: Float): String = diopterText((1f - pos) * (c?.minFocusDiopter ?: 10f))

    private fun logToProgress(v: Double, lo: Double, hi: Double): Int =
        (ln(v.coerceIn(lo, hi) / lo) / ln(hi / lo) * 1000).roundToInt().coerceIn(0, 1000)

    private fun progressToLog(p: Int, lo: Double, hi: Double): Double = lo * exp(ln(hi / lo) * p / 1000.0)

    // ------------------------------------------------------------------ dialogs

    private fun showLensDialog() {
        val lenses = Lenses.list(this)
        if (lenses.isEmpty()) { Toast.makeText(this, "No cameras found", Toast.LENGTH_SHORT).show(); return }
        val cur = caps?.lens
        AlertDialog.Builder(this)
            .setTitle("Choose camera / lens")
            .setSingleChoiceItems(lenses.map { it.label }.toTypedArray(), lenses.indexOfFirst { it.key == cur?.key }) { d, which ->
                prefs.camera = lenses[which].key
                prefs.zoom = 1f
                engine?.applyConfig()
                updateGeometry()
                if (selected != null) configurePanel()
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
                engine?.applyConfig()
                refreshChips()
                d.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
