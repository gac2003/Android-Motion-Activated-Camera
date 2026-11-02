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

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        rootLayout = root

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
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Ui.PANEL)
            val p = Ui.dp(this@MainActivity, 10)
            setPadding(p + 2, p - 4, p, p - 4)
            gravity = Gravity.CENTER_VERTICAL
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        statusTv = Ui.label(this, "", 15f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        infoTv = Ui.label(this, "", 11f, 0xFFB0B4BA.toInt()).apply { maxLines = 3; includeFontPadding = false }
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
        chipRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(Ui.dp(this@MainActivity, 8), 0, Ui.dp(this@MainActivity, 8), 0)
        }
        buildChips()
        strip.addView(chipRow)
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
        val grid = GridLayout(ctx).apply { columnCount = 4; minimumHeight = Ui.dp(ctx, 150) }
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
        col.addView(panel, LinearLayout.LayoutParams(-1, -2).apply { setMargins(Ui.dp(ctx, 12), 0, Ui.dp(ctx, 12), Ui.dp(ctx, 24)) })
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
        @Suppress("DEPRECATION") super.onBackPressed()
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
            s.mode == Mode.WATCHING -> if (s.lastMotionWall > 0) "Watching · motion ${ago(now - s.lastMotionWall)} ago" else "Watching for motion"
            s.mode == Mode.PREVIEW -> "Preview · not monitoring"
            s.mode == Mode.OFF -> if (running) "Starting…" else "Not monitoring"
            else -> s.message.ifEmpty { s.mode.name }
        }
        val sb = StringBuilder()
        caps?.let { sb.append(it.lens.label.substringBefore(" [").removePrefix("Rear ").replace(" · multi-cam", "").replaceFirstChar { ch -> ch.uppercase() }).append(" · ") }
        sb.append("${if (prefs.resHeight >= 2160) "4K" else "${prefs.resHeight}p"} ${prefs.fps}fps")
        if (s.tempC > 0f) sb.append(" · ${"%.0f".format(s.tempC)}°C")
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
