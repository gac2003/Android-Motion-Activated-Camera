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
import android.view.ViewGroup
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
    private lateinit var bottomBox: LinearLayout
    private lateinit var strip: HorizontalScrollView
    private lateinit var stripRow: LinearLayout
    /** Landscape on a phone: control strip and a compact Start/Stop share one row (more room for the preview). */
    private var compact: Boolean? = null
    private lateinit var panel: LinearLayout
    private lateinit var valueTv: TextView
    private lateinit var seek: SeekBar
    private lateinit var autoBtn: TextView
    private val chips = LinkedHashMap<String, Chip>()
    private lateinit var chipRow: LinearLayout
    private var addrCache: List<Pair<String, String>> = emptyList()
    private var addrCacheAt = 0L
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
        prefs = Prefs(this)
        if (prefs.password.isEmpty()) {
            val chars = "abcdefghjkmnpqrstuvwxyz23456789"
            prefs.password = (1..8).map { chars.random() }.joinToString("")
        }
        buildUi()
    }

    /**
     * Keeps the screen on while you are adjusting things, but only for 3 minutes after the last touch: a static
     * picture left on an AMOLED screen for hours can burn in. Monitoring carries on with the screen off.
     */
    private val allowSleep = Runnable { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }

    private fun keepAwake() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        ui.removeCallbacks(allowSleep)
        ui.postDelayed(allowSleep, 3 * 60_000L)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) keepAwake()
        return super.dispatchTouchEvent(ev)
    }

    override fun onResume() {
        super.onResume()
        keepAwake()
        resumed = true
        StatusBus.add(statusListener)
        com.motioncam.cloud.CloudSync.kick(this)
        CameraService.ensureStandby(this)   // camera phone on the live connection: reachable while not monitoring
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
        ShortClipBus.listener = { id, len -> ui.post { offerUndo(id, len) } }
        com.motioncam.live.RemoteSettings.listeners += remoteChanged
        // the live connection icon shows who is on air (and a dot for unread alerts), kept current while this is open
        com.motioncam.live.AlertInbox.listeners += inboxChanged
        com.motioncam.live.Live.listeners += inboxChanged
        tintLiveIcon()
        Nav.cameraShown(this)
    }

    private val inboxChanged: () -> Unit = { tintLiveIcon() }

    /**
     * Camera mode's eye: shown (red) while your other phones watch this camera's live view; tap it to see who.
     */
    private fun tintLiveIcon() {
        val v = liveChip ?: return
        val watching = com.motioncam.live.CameraLink.streaming
        v.visibility = if (watching) View.VISIBLE else View.GONE
        val names = watcherNames()
        v.contentDescription = "Being watched live" + if (names.isNotEmpty()) " by " + names.joinToString() else ""
        v.tooltipText = v.contentDescription
        watchersPop?.let { if (!watching) { it.dismiss(); watchersPop = null } else fillWatchers(it) }
    }

    private fun watcherNames(): List<String> = com.motioncam.live.CameraLink.watchers.map { id ->
        com.motioncam.live.Live.phones[id]?.name?.ifEmpty { null } ?: "A phone"
    }

    private var watchersPop: android.widget.PopupWindow? = null

    /** "WATCHING LIVE" and the phones watching. */
    private fun showWatchers(anchor: View) {
        watchersPop?.dismiss()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = Ui.dp(this@MainActivity, 14)
            setPadding(p, Ui.dp(this@MainActivity, 12), p, Ui.dp(this@MainActivity, 12))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF202328.toInt()); cornerRadius = Ui.dp(this@MainActivity, 14).toFloat()
                setStroke(Ui.dp(this@MainActivity, 1), 0xFF3A3E45.toInt())
            }
            minimumWidth = Ui.dp(this@MainActivity, 220)
        }
        val pop = android.widget.PopupWindow(box, -2, -2, true).apply {
            elevation = Ui.dp(this@MainActivity, 8).toFloat()
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0))
            isOutsideTouchable = true
            setOnDismissListener { if (watchersPop === this) watchersPop = null }
        }
        watchersPop = pop
        fillWatchers(pop)
        pop.showAsDropDown(anchor, 0, Ui.dp(this, 4), Gravity.END)
    }

    private fun fillWatchers(pop: android.widget.PopupWindow) {
        val box = pop.contentView as LinearLayout
        box.removeAllViews()
        box.addView(Ui.label(this, "●  WATCHING LIVE", 13f, 0xFFFF8A85.toInt()).apply { setTypeface(typeface, android.graphics.Typeface.BOLD); letterSpacing = 0.04f })
        for (n in watcherNames()) box.addView(Ui.label(this, n, 14f).apply { setPadding(0, Ui.dp(this@MainActivity, 8), 0, 0) })
        box.addView(Ui.label(this, "Your other phones see this camera's picture while they watch.", 11f, Ui.GREY).apply {
            setPadding(0, Ui.dp(this@MainActivity, 8), 0, 0); maxWidth = Ui.dp(this@MainActivity, 240)
        })
    }

    /** Back to Home (the same Home as on every phone). Monitoring carries on. */
    private fun backHome() {
        if (isTaskRoot) startActivity(Intent(this, HomeActivity::class.java))
        finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }

    override fun onPause() {
        com.motioncam.live.AlertInbox.listeners -= inboxChanged
        com.motioncam.live.Live.listeners -= inboxChanged
        com.motioncam.live.RemoteSettings.listeners -= remoteChanged
        ShortClipBus.listener = null
        finishDiscard()   // leaving the screen: the decision stands
        resumed = false
        ui.removeCallbacks(allowSleep)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
        arrangeBottom()
        frame.post { updateGeometry() }
        Ui.statusBarForOrientation(this)
    }

    /**
     * Portrait (and tablets): top bar, then the picture, then the control strip and a full-width Start / Stop.
     * Landscape phones, like a phone's camera app: the status as small pills at the top left, and on the right a
     * see-through column with the controls (scrolling) next to ⚙, a round Start / Stop in the middle and FILL / FIT.
     */
    private fun arrangeBottom() {
        val cfg = resources.configuration
        val want = cfg.orientation == Configuration.ORIENTATION_LANDSCAPE && cfg.smallestScreenWidthDp < 600
        if (compact == want) return
        compact = want
        val d = { v: Int -> Ui.dp(this, v) }
        fun detach(v: View?) { (v?.parent as? ViewGroup)?.removeView(v) }
        listOf(strip, startBtn, stripRow, chipRow, statusTv, infoTv, liveChip, backV, gearV).forEach { detach(it) }
        val top = topBarView as LinearLayout
        if (want) {
            // the top bar's pieces become pills
            top.visibility = View.GONE
            landTop.removeAllViews()
            landTop.addView(backV, LinearLayout.LayoutParams(d(40), d(40)))
            backV.background = pillBg(true); backV.setPadding(d(9), d(9), d(9), d(9))
            statusPill.addView(statusTv, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_VERTICAL))
            landTop.addView(statusPill, LinearLayout.LayoutParams(-2, d(32)).apply { leftMargin = d(6) })
            // the eye: smaller, right beside the status
            liveChip?.let { c ->
                (c as? FrameLayout)?.getChildAt(0)?.layoutParams = FrameLayout.LayoutParams(d(30), d(30), Gravity.CENTER)
                landTop.addView(c, LinearLayout.LayoutParams(d(34), d(34)).apply { leftMargin = d(4) })
            }
            infoPill.addView(infoTv, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_VERTICAL))
            infoTv.maxLines = 1; infoTv.ellipsize = android.text.TextUtils.TruncateAt.END; infoTv.textSize = 12f
            infoTv.maxWidth = (resources.displayMetrics.widthPixels - d(LAND_COL + 300)).coerceAtLeast(d(120))
            landTop.addView(infoPill, LinearLayout.LayoutParams(-2, d(32)).apply { leftMargin = d(6) })
            landTop.visibility = View.VISIBLE
            // the controls: a scrolling column
            chipRow.orientation = LinearLayout.VERTICAL
            chipRow.setPadding(d(6), d(8), d(4), d(8))
            for (i in 0 until chipRow.childCount) chipRow.getChildAt(i).layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, d(3), 0, d(3)) }
            chipColumn.addView(chipRow)
            gearBox.addView(gearV, FrameLayout.LayoutParams(d(44), d(44), Gravity.TOP or Gravity.CENTER_HORIZONTAL))
            gearV.background = pillBg(true); gearV.setPadding(d(10), d(10), d(10), d(10))
            landCol.visibility = View.VISIBLE
            // the adjust panel floats at the bottom of the picture, left of the column
            bottomBox.setBackgroundColor(0)
            bottomBox.background = android.graphics.drawable.GradientDrawable().apply { setColor(0x99202328.toInt()); cornerRadius = d(16).toFloat() }
            bottomBox.setPadding(d(4), d(6), d(4), 0)
            bottomBox.layoutParams = FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(d(8), 0, d(LAND_COL + 8), d(8)) }
            bottomBox.visibility = if (selected != null) View.VISIBLE else View.GONE
        } else {
            landTop.visibility = View.GONE
            landCol.visibility = View.GONE
            top.visibility = View.VISIBLE
            backV.background = null; gearV.background = null
            backV.setPadding(d(12), d(12), d(12), d(12)); gearV.setPadding(d(12), d(12), d(12), d(12))
            top.addView(backV, 0, LinearLayout.LayoutParams(d(48), d(48)).apply { rightMargin = d(4) })
            textsV.addView(statusTv); textsV.addView(infoTv)
            infoTv.maxLines = 3; infoTv.ellipsize = null; infoTv.textSize = 11f; infoTv.maxWidth = Int.MAX_VALUE
            liveChip?.let { c ->
                (c as? FrameLayout)?.getChildAt(0)?.layoutParams = FrameLayout.LayoutParams(d(36), d(36), Gravity.CENTER)
                top.addView(c, LinearLayout.LayoutParams(d(48), d(48)).apply { leftMargin = d(6) })
            }
            top.addView(gearV, LinearLayout.LayoutParams(d(48), d(48)).apply { leftMargin = d(4) })
            chipRow.orientation = LinearLayout.HORIZONTAL
            chipRow.setPadding(d(8), 0, d(8), 0)
            for (i in 0 until chipRow.childCount) chipRow.getChildAt(i).layoutParams = LinearLayout.LayoutParams(-2, -2).apply { setMargins(d(3), 0, d(3), 0) }
            strip.addView(chipRow)
            bottomBox.setBackgroundColor(Ui.PANEL)
            bottomBox.setPadding(0, d(6), 0, d(8))
            bottomBox.layoutParams = FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM)
            bottomBox.visibility = View.VISIBLE
            bottomBox.addView(strip)
            bottomBox.addView(startBtn, LinearLayout.LayoutParams(-1, -2).apply { setMargins(d(12), d(8), d(12), 0) })
            startBtn.setPadding(0, d(12), 0, d(12))
            startBtn.textSize = 16f
        }
        applyFill()
        if (::statusTv.isInitialized) render(StatusBus.status)
    }

    // ------------------------------------------------------------------ landscape parts

    private lateinit var backV: ImageView
    private lateinit var gearV: ImageView
    private lateinit var textsV: LinearLayout
    private lateinit var landTop: LinearLayout
    private lateinit var statusPill: FrameLayout
    private lateinit var infoPill: FrameLayout
    private lateinit var landCol: LinearLayout
    private lateinit var chipColumn: ScrollView
    private lateinit var gearBox: FrameLayout
    private lateinit var shutter: FrameLayout
    private lateinit var shutterCore: View
    private lateinit var shutterLabel: TextView
    private lateinit var fillBtn: ImageView
    private val LAND_COL = 148
    private lateinit var stage: FrameLayout
    /** The landscape column's width in px: at least 148 dp, wider when the picture leaves room (up to 240 dp). */
    private var colPx = 0

    private fun pillBg(round: Boolean) = android.graphics.drawable.GradientDrawable().apply {
        if (round) shape = android.graphics.drawable.GradientDrawable.OVAL else cornerRadius = Ui.dp(this@MainActivity, 16).toFloat()
        setColor(0x73151719)
    }

    private fun openCameraSettings() =
        startActivity(Intent(this, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_SECTION, "camera"))

    /** Landscape: FILL = the picture fills the screen behind the controls; FIT = all of it, beside them. Remembered. */
    private fun fill() = getSharedPreferences("nav", MODE_PRIVATE).getBoolean("camFill", true)

    private fun applyFill() {
        val land = compact == true
        val f = fill()
        val d = { v: Int -> Ui.dp(this, v) }
        frame.cover = false
        if (land) {
            // the column takes the room the picture leaves at full height (its text then fits on one line)
            val dm = resources.displayMetrics
            val picW = (dm.heightPixels * (if (imageRotation() % 180 != 0) 9f / 16f else 16f / 9f)).toInt()
            colPx = (dm.widthPixels - picW).coerceIn(d(LAND_COL), d(240))
            landCol.layoutParams = FrameLayout.LayoutParams(colPx, -1, Gravity.END)
            chipColumn.layoutParams = LinearLayout.LayoutParams(colPx - d(68), -1)
            (landTop.layoutParams as FrameLayout.LayoutParams).rightMargin = colPx + d(8)
            (bottomBox.layoutParams as? FrameLayout.LayoutParams)?.rightMargin = colPx + d(8)
        }
        // FILL: the picture uses the whole screen as if the controls weren't there (never cropped);
        // FIT: it stays clear of them. Either way it is centred in its space.
        stage.layoutParams = FrameLayout.LayoutParams(-1, -1).apply { if (land && !f) rightMargin = colPx }
        landCol.setBackgroundColor(if (f) 0x6B15171B else 0xFF15171B.toInt())
        fillBtn.setImageDrawable(IconDrawable(if (f) IconDrawable.Kind.FULLSCREEN else IconDrawable.Kind.FIT_IN, if (f) Ui.ACCENT else Color.WHITE))
        fillBtn.contentDescription = if (f) "Picture uses the whole screen, behind the controls. Tap to keep it clear of them"
            else "Picture stays clear of the controls. Tap to use the whole screen"
        frame.post { updateGeometry() }
    }

    private fun buildLandscapeParts() {
        val d = { v: Int -> Ui.dp(this, v) }
        landTop = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE }
        rootLayout.addView(landTop, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(d(8), d(8), d(LAND_COL + 8), 0) })
        fun pill() = FrameLayout(this).apply { background = pillBg(false); setPadding(d(12), 0, d(12), 0); foregroundGravity = Gravity.CENTER_VERTICAL }
        statusPill = pill()
        infoPill = pill()
        // the right column: controls | ⚙ · Start/Stop · FILL/FIT
        landCol = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; visibility = View.GONE; isClickable = true }
        chipColumn = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        landCol.addView(chipColumn, LinearLayout.LayoutParams(d(80), -1))
        val ctrl = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, d(12), 0, d(12)) }
        gearBox = FrameLayout(this)
        ctrl.addView(gearBox, LinearLayout.LayoutParams(-1, d(64)))
        ctrl.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        // a round shutter: green circle to start, red square to stop (its name under it; centred on the circle)
        val sbox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, d(19), 0, 0) }
        ctrl.gravity = Gravity.CENTER_HORIZONTAL
        shutter = FrameLayout(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setStroke(d(3), Color.WHITE) }
            isClickable = true
            setOnClickListener { onStartStop() }
            pressFeedback(this)
        }
        shutterCore = View(this)
        shutter.addView(shutterCore, FrameLayout.LayoutParams(d(46), d(46), Gravity.CENTER))
        sbox.addView(shutter, LinearLayout.LayoutParams(d(60), d(60)))
        shutterLabel = Ui.label(this, "START", 11f).apply { setTypeface(typeface, android.graphics.Typeface.BOLD); gravity = Gravity.CENTER; setPadding(0, d(5), 0, 0) }
        sbox.addView(shutterLabel, LinearLayout.LayoutParams(-1, -2))
        ctrl.addView(sbox, LinearLayout.LayoutParams(-1, -2))
        ctrl.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        val fbox = FrameLayout(this)
        fillBtn = ImageView(this).apply {
            background = pillBg(true)
            setPadding(d(10), d(10), d(10), d(10))
            isClickable = true
            setOnClickListener {
                getSharedPreferences("nav", MODE_PRIVATE).edit().putBoolean("camFill", !fill()).apply()
                applyFill()
            }
            pressFeedback(this)
        }
        fbox.addView(fillBtn, FrameLayout.LayoutParams(d(40), d(40), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        ctrl.addView(fbox, LinearLayout.LayoutParams(-1, d(64)))
        landCol.addView(ctrl, LinearLayout.LayoutParams(0, -1, 1f))
        rootLayout.addView(landCol, FrameLayout.LayoutParams(d(LAND_COL), -1, Gravity.END))
    }

    /** The landscape shutter shows the state: a green circle (Start) or a red square (Stop). */
    private fun paintShutter(running: Boolean) {
        val d = { v: Int -> Ui.dp(this, v) }
        shutterCore.layoutParams = FrameLayout.LayoutParams(d(if (running) 22 else 46), d(if (running) 22 else 46), Gravity.CENTER)
        shutterCore.background = android.graphics.drawable.GradientDrawable().apply {
            if (running) cornerRadius = d(6).toFloat() else shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(if (running) Ui.RED else 0xFF2E7D32.toInt())
        }
        shutterLabel.text = if (running) "STOP" else "START"
        shutter.contentDescription = if (running) "Stop monitoring" else "Start monitoring"
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
        st?.let { givePreviewAfterDraw(it) }
    }

    /**
     * Hands the preview surface to the camera only after the TextureView has drawn once. When returning to
     * the app, TextureView rebuilds its layer on the first draw and resets the surface's frame size to the
     * view size; if the camera connected before that, it picked a non-16:9 size and the preview was stretched.
     */
    private fun givePreviewAfterDraw(s: SurfaceTexture) {
        val vto = texture.viewTreeObserver
        var done = false
        val give = Runnable { if (!done) { done = true; if (attached && st === s) engine?.setPreview(s) } }
        val listener = object : android.view.ViewTreeObserver.OnDrawListener {
            override fun onDraw() {
                // runs before the draw: post so we act after the layer has been (re)created
                texture.post {
                    if (texture.viewTreeObserver.isAlive) texture.viewTreeObserver.removeOnDrawListener(this)
                    give.run()
                }
            }
        }
        vto.addOnDrawListener(listener)
        texture.invalidate()
        texture.postDelayed(give, 600)   // safety net if no frame gets drawn
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

    private lateinit var rootLayout: FrameLayout
    private var liveChip: View? = null
    /** Settings changed from another phone (live connection): show them. */
    private val remoteChanged: () -> Unit = { ui.post { if (resumed) { refreshChips(); if (selected != null) configurePanel() } } }
    private var topBarView: View? = null

    // ------------------------------------------------------------------ short clip from a manual stop: Undo

    private var discardId = -1L
    private var undoPill: TextView? = null
    private val discardNow = Runnable { finishDiscard() }

    /**
     * Shows a small "Short clip discarded · UNDO" pill in the top-right corner of the preview for a few seconds.
     * Tapping it asks for confirmation and keeps the clip; otherwise the clip is deleted when the pill goes.
     */
    private fun offerUndo(clipId: Long, lengthMs: Long) {
        finishDiscard()                       // an earlier one still pending: its time is up
        discardId = clipId
        val pill = Ui.label(this, "Short clip (%.1f s) discarded  ·  UNDO".format(lengthMs / 1000f), 13f).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = Ui.dp(this@MainActivity, 18).toFloat(); setColor(0xE6202328.toInt()); setStroke(Ui.dp(this@MainActivity, 1), Ui.ACCENT)
            }
            val p = Ui.dp(this@MainActivity, 12)
            setPadding(p, p / 2 + 2, p, p / 2 + 2)
            elevation = Ui.dp(this@MainActivity, 4).toFloat()
            setOnClickListener { confirmKeep(clipId, lengthMs) }
        }
        rootLayout.addView(pill, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
            topMargin = (if (compact == true) landTop.bottom else topBarView?.bottom ?: 0) + Ui.dp(this@MainActivity, 8)
            rightMargin = if (compact == true) colPx + Ui.dp(this@MainActivity, 8) else Ui.dp(this@MainActivity, 8)
        })
        pill.alpha = 0f
        pill.animate().alpha(1f).setDuration(150).start()
        undoPill = pill
        ui.postDelayed(discardNow, 9000)
    }

    private fun confirmKeep(clipId: Long, lengthMs: Long) {
        ui.removeCallbacks(discardNow)       // no time limit while deciding
        hidePill()
        android.app.AlertDialog.Builder(this).setTitle("Keep this clip?")
            .setMessage("The %.1f s clip recorded just before you stopped monitoring will be kept in Clips (and backed up, if backup is on).".format(lengthMs / 1000f))
            .setPositiveButton("Keep") { _, _ ->
                if (discardId != clipId) { Toast.makeText(this, "Too late: the clip was already discarded", Toast.LENGTH_SHORT).show(); return@setPositiveButton }
                discardId = -1
                com.motioncam.cloud.CloudSync.onClipSaved(this, clipId)
                Toast.makeText(this, "Clip kept", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Discard") { _, _ -> finishDiscard() }
            .setOnCancelListener { finishDiscard() }
            .show()
    }

    /** A small corner pop-up below the top bar (like the "short clip discarded" one), gone after [ms]. */
    private fun showPill(text: String, ms: Long) {
        if (undoPill != null) return   // the Undo pill has priority
        val pill = Ui.label(this, text, 13f).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = Ui.dp(this@MainActivity, 18).toFloat(); setColor(0xE6202328.toInt()); setStroke(Ui.dp(this@MainActivity, 1), Ui.RED)
            }
            val p = Ui.dp(this@MainActivity, 12)
            setPadding(p, p / 2 + 2, p, p / 2 + 2)
            elevation = Ui.dp(this@MainActivity, 4).toFloat()
        }
        rootLayout.addView(pill, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
            topMargin = (if (compact == true) landTop.bottom else topBarView?.bottom ?: 0) + Ui.dp(this@MainActivity, 8)
            rightMargin = if (compact == true) colPx + Ui.dp(this@MainActivity, 8) else Ui.dp(this@MainActivity, 8)
        })
        pill.alpha = 0f
        pill.animate().alpha(1f).setDuration(150).start()
        pill.postDelayed({ pill.animate().alpha(0f).setDuration(200).withEndAction { rootLayout.removeView(pill) }.start() }, ms)
    }

    private fun hidePill() {
        undoPill?.let { p -> p.animate().alpha(0f).setDuration(150).withEndAction { rootLayout.removeView(p) }.start() }
        undoPill = null
    }

    /** Deletes the held short clip (if still pending). */
    private fun finishDiscard() {
        ui.removeCallbacks(discardNow)
        hidePill()
        val id = discardId
        if (id < 0) return
        discardId = -1
        val app = applicationContext
        Thread { ClipStore.get(app, id)?.let { ClipStore.delete(app, it) } }.start()
    }

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        rootLayout = root

        frame = AspectFrame(this)
        texture = TextureView(this)
        overlay = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_XY }
        frame.addView(texture, FrameLayout.LayoutParams(-1, -1))
        frame.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        // the picture is centred in its stage: the whole screen, or (landscape FIT) the space left of the controls
        stage = FrameLayout(this)
        stage.addView(frame, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        root.addView(stage, FrameLayout.LayoutParams(-1, -1))
        offLabel = Ui.label(this, "", 15f, Ui.GREY).apply { gravity = Gravity.CENTER; visibility = View.GONE }
        root.addView(offLabel, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))

        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) {
                st = s
                updateTransform()
                if (attached) givePreviewAfterDraw(s)
            }
            override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) {
                updateTransform()
                // TextureView resets the surface's frame size when it is resized: reconnect the camera
                if (attached) engine?.previewResized(s)
            }
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
            topBarView = this
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Ui.PANEL)
            val p = Ui.dp(this@MainActivity, 10)
            setPadding(Ui.dp(this@MainActivity, 2), p - 4, p, p - 4)
            gravity = Gravity.CENTER_VERTICAL
        }
        backV = iconButton(this, IconDrawable.Kind.BACK, 48, 12) { backHome() }.apply {
            contentDescription = "Back to Home"; tooltipText = "Home"
        }
        top.addView(backV, LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)).apply { rightMargin = Ui.dp(this@MainActivity, 4) })
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        textsV = texts
        statusTv = Ui.label(this, "", 15f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        infoTv = Ui.label(this, "", 11f, 0xFFB0B4BA.toInt()).apply { maxLines = 3; includeFontPadding = false }
        texts.addView(statusTv)
        texts.addView(infoTv)
        top.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        // the eye (only while other phones watch this camera): tap it for who's watching
        liveChip = FrameLayout(this).apply {
            val eyeBg = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(Ui.RED) }
            addView(ImageView(this@MainActivity).apply {
                setImageDrawable(IconDrawable(IconDrawable.Kind.EYE))
                background = eyeBg
                val p = Ui.dp(this@MainActivity, 7)
                setPadding(p, p, p, p)
            }, FrameLayout.LayoutParams(Ui.dp(this@MainActivity, 36), Ui.dp(this@MainActivity, 36), Gravity.CENTER))
            isClickable = true
            setOnClickListener { v -> showWatchers(v) }
            visibility = View.GONE
        }
        top.addView(liveChip, LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)).apply { leftMargin = Ui.dp(this@MainActivity, 6) })
        // this camera's settings: everything your other phones can change from its screen
        gearV = iconButton(this, IconDrawable.Kind.SETTINGS, 48, 12) { openCameraSettings() }.apply { contentDescription = "Camera settings"; tooltipText = "Camera settings" }
        top.addView(gearV, LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)).apply { leftMargin = Ui.dp(this@MainActivity, 4) })
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

        strip = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        chipRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(Ui.dp(this@MainActivity, 8), 0, Ui.dp(this@MainActivity, 8), 0)
        }
        buildChips()
        strip.addView(chipRow)

        startBtn = Ui.label(this, "START MONITORING", 16f).apply {
            gravity = Gravity.CENTER
            setPadding(0, Ui.dp(this@MainActivity, 12), 0, Ui.dp(this@MainActivity, 12))
            setOnClickListener { onStartStop() }
        }
        Ui.pill(startBtn, 0xFF2E7D32.toInt())
        bottomBox = bottom
        stripRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        root.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        buildLandscapeParts()
        arrangeBottom()

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) { if (fromUser && !updating) onSeek(p) }
            override fun onStartTrackingTouch(sb: SeekBar) { touching = true }
            override fun onStopTrackingTouch(sb: SeekBar) { touching = false }
        })
        autoBtn.setOnClickListener { onAuto() }
        setContentView(root)
    }

    private fun visibleChips(): List<String> =
        prefs.chipLayout.split(",").map { it.trim() }.filter { it in Prefs.ALL_CHIPS }.distinct()
            .ifEmpty { Prefs.ALL_CHIPS }

    private fun buildChips() {
        chipRow.removeAllViews()
        chips.clear()
        for (name in visibleChips()) {
            val c = Chip(this, name)
            c.view.setOnClickListener { onChip(name) }
            c.view.setOnLongClickListener { customizeChips(); true }
            chips[name] = c
            chipRow.addView(c.view, LinearLayout.LayoutParams(-2, -2).apply {
                setMargins(Ui.dp(this@MainActivity, 3), 0, Ui.dp(this@MainActivity, 3), 0)
            })
        }
        if (selected != null && selected !in chips) {
            selected = null
            panel.visibility = View.GONE
            if (compact == true) bottomBox.visibility = View.GONE
            updateFlags()
        }
        refreshChips()
    }

    private var editor: View? = null

    private val chipNames = mapOf("LENS" to "Lens", "FORMAT" to "Format", "EV" to "EV / meter", "ISO" to "ISO",
        "SPEED" to "Shutter", "FOCUS" to "Focus", "WB" to "White bal.", "ZOOM" to "Zoom", "ZONES" to "Zones")

    /**
     * Long-press a control button: Samsung-style editor. Touch and hold a button to drag it between the
     * grid (hidden) and the strip (shown), or to a new position in the strip. Tapping also moves it across.
     */
    private fun customizeChips() {
        if (editor != null) return
        val active = visibleChips().toMutableList()
        val ctx = this
        val overlay = FrameLayout(ctx).apply { setBackgroundColor(0x99000000.toInt()); isClickable = true }
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(ctx, 20), 0, Ui.dp(ctx, 12), Ui.dp(ctx, 10))
        }
        header.addView(Ui.label(ctx, "Touch and hold to move buttons.", 15f), LinearLayout.LayoutParams(0, -2, 1f))
        col.addView(header)
        fun pillButton(text: String, color: Int) = Ui.label(ctx, text, 15f).apply {
            gravity = Gravity.CENTER
            setPadding(Ui.dp(ctx, 20), Ui.dp(ctx, 9), Ui.dp(ctx, 20), Ui.dp(ctx, 9))
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = Ui.dp(ctx, 22).toFloat(); setColor(color) }
        }
        val save = pillButton("Save", 0xFF8A5A2E.toInt())

        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = Ui.dp(ctx, 14)
            setPadding(p, p, p, p)
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = Ui.dp(ctx, 26).toFloat(); setColor(0xF21B1C20.toInt()) }
            isClickable = true   // taps inside the panel must not count as "outside"
        }
        val landEd = compact == true
        // landscape: the hidden buttons in one or two wide rows, so Save / Default / Cancel stay on screen
        val grid = GridLayout(ctx).apply {
            columnCount = if (landEd) ((Ui.widthDp(ctx) - 60) / 96).toInt().coerceIn(5, 9) else if (Ui.widthDp(ctx) < 360f) 3 else 4
            minimumHeight = Ui.dp(ctx, if (landEd) 60 else 150)
        }
        panel.addView(grid, LinearLayout.LayoutParams(-1, -2))
        panel.addView(Ui.label(ctx, "· · ·", 14f, Ui.GREY).apply { gravity = Gravity.CENTER; setPadding(0, Ui.dp(ctx, 4), 0, Ui.dp(ctx, 4)) },
            LinearLayout.LayoutParams(-1, -2))
        val stripScroll = HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false }
        val stripRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumWidth = resources.displayMetrics.widthPixels - Ui.dp(ctx, 60)
            minimumHeight = Ui.dp(ctx, 64)
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = Ui.dp(ctx, 14).toFloat(); setColor(0xFF101114.toInt()) }
            setPadding(Ui.dp(ctx, 6), Ui.dp(ctx, 6), Ui.dp(ctx, 6), Ui.dp(ctx, 6))
        }
        // neighbours slide aside while a button is dragged over the strip
        stripRow.layoutTransition = android.animation.LayoutTransition().apply {
            disableTransitionType(android.animation.LayoutTransition.APPEARING)
            disableTransitionType(android.animation.LayoutTransition.DISAPPEARING)
            setDuration(150)
        }
        stripScroll.addView(stripRow)
        panel.addView(stripScroll, LinearLayout.LayoutParams(-1, -2))
        val foot = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, Ui.dp(ctx, 12), 0, 0) }
        panel.addView(foot, LinearLayout.LayoutParams(-1, -2))
        if (landEd) { header.setPadding(Ui.dp(ctx, 20), 0, Ui.dp(ctx, 12), Ui.dp(ctx, 4)); panel.setPadding(Ui.dp(ctx, 12), Ui.dp(ctx, 8), Ui.dp(ctx, 12), Ui.dp(ctx, 10)); foot.setPadding(0, Ui.dp(ctx, 8), 0, 0) }
        col.addView(panel, LinearLayout.LayoutParams(-1, -2).apply { setMargins(Ui.dp(ctx, 12), 0, Ui.dp(ctx, 12), Ui.dp(ctx, if (landEd) 8 else 24)) })
        overlay.addView(col, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        var dragging: String? = null
        var snapshot: List<String> = emptyList()
        lateinit var render: () -> Unit
        lateinit var tileFn: (String, Boolean) -> View
        fun tile(key: String, inStrip: Boolean): View {
            val c = Chip(ctx, key)
            c.set(chipNames[key] ?: key, false, false)
            c.view.tag = key
            c.view.setOnClickListener {
                if (inStrip) { if (active.size > 1) active.remove(key) } else active.add(key)
                render()
            }
            c.view.setOnLongClickListener { v ->
                dragging = key
                snapshot = active.toList()
                v.startDragAndDrop(null, View.DragShadowBuilder(v), key, 0)
                v.alpha = 0.3f
                true
            }
            return c.view
        }
        tileFn = { k, inStrip -> tile(k, inStrip) }
        render = {
            grid.removeAllViews()
            for (k in Prefs.ALL_CHIPS.filter { it !in active }) {
                grid.addView(tile(k, false), GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply {
                    width = 0
                    setMargins(Ui.dp(ctx, 4), Ui.dp(ctx, 6), Ui.dp(ctx, 4), Ui.dp(ctx, 6))
                })
            }
            stripRow.removeAllViews()
            for (k in active) stripRow.addView(tile(k, true), LinearLayout.LayoutParams(-2, -2).apply { setMargins(Ui.dp(ctx, 3), 0, Ui.dp(ctx, 3), 0) })
        }
        render()

        fun stripView(key: String): View? = (0 until stripRow.childCount).map { stripRow.getChildAt(it) }.firstOrNull { it.tag == key }
        /** Where [key] would go for a finger at [x]: number of other buttons whose centre is left of it. */
        fun slotFor(key: String, x: Float): Int {
            var idx = 0
            for (i in 0 until stripRow.childCount) {
                val ch = stripRow.getChildAt(i)
                if (ch.tag != key && (ch.left + ch.right) / 2f < x) idx++
            }
            return idx
        }
        /** Moves the dragged button's placeholder in the strip; the other buttons animate aside. */
        fun placeAt(key: String, idx: Int) {
            var v = stripView(key)
            if (v == null) {
                v = tileFn(key, true).apply { alpha = 0.3f }
                stripRow.addView(v, idx.coerceIn(0, stripRow.childCount), LinearLayout.LayoutParams(-2, -2).apply { setMargins(Ui.dp(ctx, 3), 0, Ui.dp(ctx, 3), 0) })
            } else if (stripRow.indexOfChild(v) != idx) {
                stripRow.removeView(v)
                stripRow.addView(v, idx.coerceIn(0, stripRow.childCount))
            }
            active.remove(key)
            active.add(idx.coerceIn(0, active.size), key)
        }
        stripRow.setOnDragListener { _, e ->
            val key = e.localState as? String
            when (e.action) {
                android.view.DragEvent.ACTION_DRAG_ENTERED, android.view.DragEvent.ACTION_DRAG_LOCATION -> {
                    if (key != null) placeAt(key, slotFor(key, e.x))
                    true
                }
                android.view.DragEvent.ACTION_DRAG_EXITED -> {
                    // dragged out of the strip: a button that came from the grid leaves the strip again
                    if (key != null && key !in snapshot) {
                        stripView(key)?.let { stripRow.removeView(it) }
                        active.remove(key)
                    }
                    true
                }
                android.view.DragEvent.ACTION_DROP -> {
                    if (key != null) placeAt(key, slotFor(key, e.x))
                    render()
                    true
                }
                android.view.DragEvent.ACTION_DRAG_ENDED -> {
                    if (!e.result) { active.clear(); active.addAll(snapshot) }   // dropped nowhere: undo
                    dragging = null
                    render()
                    true
                }
                else -> true
            }
        }
        grid.setOnDragListener { _, e ->
            when (e.action) {
                android.view.DragEvent.ACTION_DROP -> {
                    val key = e.localState as? String ?: return@setOnDragListener false
                    if (active.size > 1) active.remove(key)
                    render()
                    true
                }
                else -> true
            }
        }

        fun close() { (overlay.parent as? ViewGroup)?.removeView(overlay); editor = null }
        // Save on the left, Default and Cancel on the right, all in one row
        foot.addView(save)
        foot.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
        foot.addView(pillButton("Default", 0xFF33363C.toInt()).apply {
            setOnClickListener {
                AlertDialog.Builder(ctx).setTitle("Restore default buttons?")
                    .setMessage("All control buttons will be shown in their original order.")
                    .setPositiveButton("Restore") { _, _ ->
                        active.clear(); active.addAll(Prefs.ALL_CHIPS)
                        prefs.chipLayout = active.joinToString(",")
                        buildChips()
                        render()
                    }
                    .setNegativeButton("Cancel", null).show()
            }
        })
        foot.addView(pillButton("Cancel", 0xFF33363C.toInt()).apply { setOnClickListener { close() } },
            LinearLayout.LayoutParams(-2, -2).apply { leftMargin = Ui.dp(ctx, 8) })
        overlay.setOnClickListener { close() }   // tap outside the panel = cancel
        save.setOnClickListener {
            prefs.chipLayout = active.joinToString(",")
            buildChips()
            close()
        }
        rootLayout.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        editor = overlay
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val e = editor
        if (e != null) { (e.parent as? ViewGroup)?.removeView(e); editor = null; return }
        backHome()
    }

    // ------------------------------------------------------------------ start / stop / permissions

    private fun onStartStop() {
        if (CameraService.monitoring) {
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
        updateGeometry()
        if (selected != null) configurePanel()
        refreshChips()
        attach()
        if (requestCode == 1) launchService()
    }

    private fun launchService() {
        CameraService.start(this)
        startBtn.postDelayed({ render(StatusBus.status) }, 500)
    }

    // ------------------------------------------------------------------ status

    private var lastMode: Mode? = null

    private fun render(s: Status) {
        // motion just started: a short, non-blocking pop-up in the corner (the time also stays in the info line)
        if (lastMode != null && lastMode != Mode.RECORDING && s.mode == Mode.RECORDING) showPill("● Motion detected — recording", 3000)
        lastMode = s.mode
        val running = CameraService.monitoring
        startBtn.text = if (compact == true) (if (running) "STOP" else "START") else if (running) "STOP MONITORING" else "START MONITORING"
        Ui.pill(startBtn, if (running) Ui.RED else 0xFF2E7D32.toInt())
        if (::shutter.isInitialized) paintShutter(running)
        val now = System.currentTimeMillis()
        statusTv.setTextColor(if (s.mode == Mode.RECORDING) Ui.RED else Color.WHITE)
        statusTv.text = when {
            s.mode == Mode.RECORDING -> {
                val sec = (now - s.recordingSinceWall) / 1000
                "● REC %02d:%02d".format(sec / 60, sec % 60)
            }
            s.mode == Mode.WATCHING -> "Watching for motion"
            s.mode == Mode.PREVIEW -> if (CameraService.inStandby) "Preview · standby" else "Preview · not monitoring"
            s.mode == Mode.OFF -> if (running) "Starting…" else if (CameraService.inStandby) "Standby · your phones can start it" else "Not monitoring"
            else -> s.message.ifEmpty { s.mode.name }
        }
        val sb = StringBuilder()
        caps?.let { sb.append(it.lens.label.substringBefore(" [").removePrefix("Rear ").replace(" · multi-cam", "").replaceFirstChar { ch -> ch.uppercase() }).append(" · ") }
        sb.append("${if (prefs.resHeight >= 2160) "4K" else "${prefs.resHeight}p"} ${prefs.fps}fps")
        if (s.tempC > 0f) sb.append(" · ${"%.0f".format(s.tempC)}°C")
        if (s.lastMotionWall > 0 && s.monitoring) sb.append(" · motion ${ago(now - s.lastMotionWall)} ago")
        if (running && prefs.serverOn) {
            if (now - addrCacheAt > 10_000) { addrCacheAt = now; addrCache = WebServer.localAddresses() }
            if (addrCache.isNotEmpty()) sb.append("\nRemote: ").append(addrCache.joinToString("  ") { "http://${it.second}:${prefs.port}" })
        }
        if (s.message.isNotEmpty() && s.mode != Mode.RECORDING && s.mode != Mode.WATCHING && statusTv.text != s.message) sb.append("\n").append(s.message)
        infoTv.text = sb.toString()
    }

    /** Compact "time ago": 45s, 12m, 3h, 2d. */
    private fun ago(ms: Long): String {
        val sec = (ms / 1000).coerceAtLeast(0)
        return when {
            sec < 60 -> "${sec}s"
            sec < 3600 -> "${sec / 60}m"
            sec < 86400 -> "${sec / 3600}h"
            else -> "${sec / 86400}d"
        }
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
        if (prefs.exposureAuto) set("EV", evText(c, prefs.evIndex), false)
        else set("EV", meterText(if (live) e!!.liveMeterEv else Float.NaN), false)
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
        if (compact == true) bottomBox.visibility = panel.visibility
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
                setAutoBtn("AUTO", prefs.exposureAuto || !c.manualSensor)
                seek.isEnabled = c.manualSensor
                if (!prefs.exposureAuto) seek.progress = if (selected == "ISO") isoProgress(c, prefs.iso) else shutterProgress(c, prefs.shutterNs)
            }
            "FOCUS" -> {
                seek.max = 1000
                setAutoBtn("AUTO", prefs.focusAuto || !c.canManualFocus)
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
                seek.max = MotionDetector.MAX_SENSITIVITY - 1
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
            "EV" -> if (prefs.exposureAuto) text = "EV " + evText(c, prefs.evIndex) else {
                val mv = if (live) e!!.liveMeterEv else Float.NaN
                text = "Meter " + meterText(mv) + " EV"
                if (!mv.isNaN() && c.evStep > 0f) follow = ((mv / c.evStep).roundToInt().coerceIn(c.evMin, c.evMax) - c.evMin)
            }
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
            "ISO", "SPEED" -> if (!c.manualSensor) {
                Toast.makeText(this, "This lens does not allow manual ISO / shutter speed", Toast.LENGTH_SHORT).show(); return
            } else if (prefs.exposureAuto) seedManualExposure(c) else prefs.exposureAuto = true
            "FOCUS" -> if (!c.canManualFocus) {
                Toast.makeText(this, "This lens is fixed-focus (no manual focus)", Toast.LENGTH_SHORT).show(); return
            } else if (prefs.focusAuto) seedManualFocus(c) else prefs.focusAuto = true
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
        if (sel == "EV" && !prefs.exposureAuto) return   // the meter is read-only in manual exposure
        val (title, hint, cur) = when (sel) {
            "EV" -> Triple("Exposure compensation (EV)", "e.g. -0.7", "%.1f".format(prefs.evIndex * c.evStep))
            "ISO" -> Triple("ISO (${c.isoMin}–${c.isoMax})", "e.g. 400", "${if (prefs.exposureAuto) engine?.liveIso ?: prefs.iso else prefs.iso}")
            "SPEED" -> Triple("Shutter speed: 1 / …", "e.g. 60 for 1/60 s", "${(1e9 / (if (prefs.exposureAuto) engine?.liveShutterNs?.takeIf { it > 0 } ?: prefs.shutterNs else prefs.shutterNs)).roundToInt()}")
            "FOCUS" -> Triple("Focus distance in metres (empty = infinity)", "e.g. 1.5", "")
            "WB" -> Triple("White balance in Kelvin (snaps to nearest preset)", "e.g. 5200", "${prefs.wbKelvin}")
            "ZOOM" -> Triple("Zoom (%.1f–%.1f)".format(c.zoomMin, c.zoomMax), "e.g. 2.0", "%.1f".format(prefs.zoom))
            "ZONES" -> Triple("Motion sensitivity (1–${MotionDetector.MAX_SENSITIVITY})", "5", "${prefs.sensitivity}")
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
                        val d = if (v == null || v <= 0) 0f else (1.0 / v).toFloat()
                        prefs.focusPos = (1f - d / c.minFocusDiopter).coerceIn(0f, 1f)
                        prefs.focusAuto = false
                    }
                    "WB" -> if (v != null) { prefs.wbKelvin = ProCaps.wbPresets[ProCaps.nearestPreset(v.toInt())].second; prefs.wbAuto = false }
                    "ZOOM" -> if (v != null) prefs.zoom = v.toFloat().coerceIn(c.zoomMin, c.zoomMax)
                    "ZONES" -> if (v != null) prefs.sensitivity = v.toInt().coerceIn(1, MotionDetector.MAX_SENSITIVITY)
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

    /** Light-meter reading in manual exposure: how far the picture is from a normal exposure. */
    private fun meterText(v: Float): String =
        if (v.isNaN()) "—" else (if (v > 0.05f) "+" else "") + "%.1f".format(v)

    private fun evText(c: ProCaps?, idx: Int): String {
        val v = idx * (c?.evStep ?: 0.1667f)
        return (if (v > 0.001f) "+" else "") + "%.1f".format(v)
    }

    private fun shutterText(ns: Long): String {
        if (ns <= 0) return "—"
        val den = 1_000_000_000.0 / ns
        return if (den >= 1.5) "1/${den.roundToInt()}" else "%.1fs".format(ns / 1e9)
    }

    /** Focus distance, always in metres (0.63m, 2.50m, 12.5m), ∞ for infinity. */
    private fun diopterText(d: Float): String {
        if (d < 0.1f) return "∞"
        val m = 1f / d
        return if (m < 10f) "%.2fm".format(m) else "%.1fm".format(m)
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
        OptionSheet.show(this, "Camera / lens", lenses.map { l ->
            Opt(l.label.substringBefore(" ["), l.label.substringAfter(" [", "").removeSuffix("]").ifEmpty { null }, checked = l.key == cur?.key) {
                prefs.camera = l.key
                prefs.zoom = 1f
                engine?.applyConfig()
                updateGeometry()
                if (selected != null) configurePanel()
                refreshChips()
            }
        })
    }

    private fun showFormatDialog() {
        val opts = listOf(
            Triple("720p · 30 fps", "Smallest files, least heat", 720 to 30),
            Triple("1080p · 24 fps", "Slightly smaller than 30 fps", 1080 to 24),
            Triple("1080p · 30 fps", "Recommended", 1080 to 30),
            Triple("1080p · 60 fps", "Smoother motion, bigger files, warmer phone", 1080 to 60),
            Triple("4K · 30 fps", "Most detail; about 3× the size of 1080p", 2160 to 30),
        )
        OptionSheet.show(this, "Recording format", opts.map { (t, d, v) ->
            Opt(t, d, checked = v.first == prefs.resHeight && v.second == prefs.fps) {
                prefs.resHeight = v.first
                prefs.fps = v.second
                engine?.applyConfig()
                refreshChips()
            }
        })
    }
}
