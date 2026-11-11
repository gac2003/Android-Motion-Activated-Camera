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
    // smooth live video (WebRTC), when this build has it: shown over the pictures once its first frame arrives
    private var renderer: org.webrtc.SurfaceViewRenderer? = null
    private var rendererBox: FrameLayout? = null
    private var rtc: RtcViewer? = null
    private var rtcOn = false
    private var rtcFailed = false
    private var rtcW = 0; private var rtcH = 0

    /** Video if possible: this build has it, it's on here, the camera supports it, and the PIN is known if needed. */
    private fun maybeStartRtc() {
        val rd = renderer ?: return
        if (rtc != null || rtcFailed || rtcChecking || !prefs.liveSmooth || !r.online()) return
        val s = status() ?: return
        if (!s.optBoolean("rtc")) { videoIssue = "the camera phone needs the latest MotionCam (with video)"; updateLiveLabel(); return }
        val pin = r.pinFor()
        if (s.optBoolean("pinForView") && pin == null) { videoIssue = "enter the camera's PIN"; return }
        rtcChecking = true
        // the relay's monthly allowance (Cloudflare has no cap of its own): over it, direct connections only
        Rtc.usage(this) { used, capGb ->
            rtcChecking = false
            if (isFinishing || rtc != null || rtcFailed || !resumedNow) return@usage
            val allowRelay = used < capGb * 1_000_000_000L
            relayNote = if (allowRelay) null else "relay limit reached this month"
            fun backToPictures() {
                rtc = null; rtcOn = false
                rd.clearImage(); image.visibility = View.VISIBLE
                if (!feed.watching) feed.start()
                updateLiveLabel()
            }
            rtc = RtcViewer(this, cam, pin, rd, allowRelay, used, capGb,
                overCap = {
                    rtcFailed = true; backToPictures()
                    Toast.makeText(this, "This month's video relay allowance ($capGb GB) is used up: back to pictures", Toast.LENGTH_LONG).show()
                },
                firstFrame = { w, h -> videoStarted(w, h) }) { why ->
                // no connection: keep (or go back to) the pictures, and say why
                rtcFailed = true; backToPictures()
                videoIssue = why
                com.motioncam.Health.log(this, "Live video from ${r.name()} didn't work: $why")
                Toast.makeText(this, "Video didn't connect ($why): showing pictures", Toast.LENGTH_LONG).show()
                updateLiveLabel()
            }.also { it.start() }
            // connected, but no picture: the camera isn't sending frames
            ui.postDelayed({
                val v = rtc ?: return@postDelayed
                if (!rtcOn && v.connected && rtcRetries < 1) {
                    // once: a fresh connection usually brings the picture
                    rtcRetries++
                    com.motioncam.Health.log(this, "Live video from ${r.name()}: connected but no picture, trying again")
                    v.stop(); rtc = null; maybeStartRtc()
                    return@postDelayed
                }
                if (!rtcOn && v.connected) {
                    v.stop(); rtcFailed = true; backToPictures()
                    videoIssue = "connected, but the camera sent no picture"
                    com.motioncam.Health.log(this, "Live video from ${r.name()}: $videoIssue")
                    Toast.makeText(this, "Video connected but no picture arrived: showing pictures", Toast.LENGTH_LONG).show()
                }
            }, 30_000)
        }
    }
    private var rtcChecking = false
    private var rtcRetries = 0
    private var relayNote: String? = null
    /** Why video didn't work (shown with the pictures). */
    private var videoIssue: String? = null
    private var resumedNow = false

    /** This connection's first video frame arrived: video replaces the pictures. */
    private fun videoStarted(w: Int, h: Int) {
        if (rtc == null) return
        rtcOn = true
        if (w > 0 && h > 0) { rtcW = w; rtcH = h }
        com.motioncam.Health.log(this, "Live video from ${r.name()}: playing")
        image.visibility = View.INVISIBLE
        if (feed.watching) feed.stop()   // the pictures aren't needed while video plays
        msgTv.visibility = View.GONE
        updateLiveLabel()
        cropView.invalidate()
    }

    private fun stopRtc() {
        rtc?.stop(); rtc = null; rtcOn = false
        renderer?.clearImage()
        if (::image.isInitialized) image.visibility = View.VISIBLE
    }
    private lateinit var cropView: com.motioncam.CropView
    private lateinit var cropBar: View

    /** Leaves crop editing; [keep]: lines up the frame as it is now. */
    private fun endCrop(keep: Boolean) {
        if (keep) stage(JSONObject().put("crop", com.motioncam.Crop.format(cropView.sensorRect())), refresh = false)
        selected = null
        panel.visibility = View.GONE
        refreshChips()
        updateSaveBar()
    }

    /** Where the live picture is drawn (it's fitted inside the screen). */
    private fun imageRect(): android.graphics.RectF? {
        // the video: fitted inside its view
        renderer?.takeIf { rtcOn && rtcW > 0 && rtcH > 0 }?.let { v ->
            val box = rendererBox ?: return@let null
            val sc = minOf(box.width.toFloat() / rtcW, box.height.toFloat() / rtcH)
            val w = rtcW * sc; val h = rtcH * sc
            val l = box.left + (box.width - w) / 2; val t = box.top + (box.height - h) / 2
            return android.graphics.RectF(l, t, l + w, t + h)
        }
        val dr = image.drawable ?: return null
        if (dr.intrinsicWidth <= 0 || dr.intrinsicHeight <= 0) return null
        val rc = android.graphics.RectF(0f, 0f, dr.intrinsicWidth.toFloat(), dr.intrinsicHeight.toFloat())
        image.imageMatrix.mapRect(rc)
        rc.offset((image.left + image.paddingLeft).toFloat(), (image.top + image.paddingTop).toFloat())
        return rc
    }

    /** The clip size a crop gives on the camera (null = whole picture). */
    private fun cropSize(rc: android.graphics.RectF?): android.util.Size? {
        rc ?: return null
        val s = status() ?: return null
        val h = st().optInt("resHeight", 1080)
        val full = android.util.Size(s.optInt("recW", h * 16 / 9).takeIf { it > 0 } ?: (h * 16 / 9), s.optInt("recH", h).takeIf { it > 0 } ?: h)
        return com.motioncam.Crop.encodeSize(rc, full)
    }

    private fun cropText(): String {
        val rc = cropView.sensorRect()
        val shape = com.motioncam.Crop.SHAPES.getOrNull(prefs.cropShape)?.first ?: "Free"
        val sz = cropSize(rc) ?: return "$shape ▾ · whole picture: drag the corners in"
        val s = status()
        val fw = s?.optInt("recW") ?: 0; val fh = s?.optInt("recH") ?: 0
        val pct = if (fw > 0 && fh > 0) (100f * sz.width * sz.height / (fw * fh)).roundToInt() else 0
        return "$shape ▾ · records ${com.motioncam.Crop.label(sz, s?.optInt("rotation") ?: 0)}" + if (pct > 0) " · ≈$pct% of the file size" else ""
    }

    private fun syncCropView() {
        if (!::cropView.isInitialized) return
        cropView.rotation = status()?.optInt("rotation") ?: 0
        if (selected != "CROP" || !draft.has("crop")) cropView.set(com.motioncam.Crop.parse(st().optString("crop")))
        val ed = selected == "CROP"
        cropView.editing = ed
        if (::cropBar.isInitialized) cropBar.visibility = if (ed) View.VISIBLE else View.GONE
        for ((n, c) in chips) c.view.alpha = if (ed && n != "CROP") 0.35f else 1f
        cropFocus(ed)
    }

    private var cropFocused = false
    private var savedBoxBg: android.graphics.drawable.Drawable? = null

    /** Cropping: only the picture, the frame and the crop's bar (the rest comes back when cropping ends). */
    private fun cropFocus(on: Boolean) {
        if (on == cropFocused || !::startBtn.isInitialized) return
        cropFocused = on
        val vis = if (on) View.GONE else View.VISIBLE
        val hide = if (compact == true) listOf<View>(landTop, landCol) else listOf<View>(top, strip, startBtn)
        for (v in hide) v.visibility = vis
        // the crop's buttons float over the picture: no panel behind them (the text gets a shadow to stay readable)
        if (on) { savedBoxBg = bottomBox.background; bottomBox.background = null; valueTv.setShadowLayer(6f, 0f, 1f, 0xFF000000.toInt()) }
        else { bottomBox.background = savedBoxBg; valueTv.setShadowLayer(0f, 0f, 0f, 0) }
        if (on) saveBar.visibility = View.GONE else updateSaveBar()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // back while cropping leaves cropping (as Cancel), not the camera
        if (selected == "CROP") { cropView.set(com.motioncam.Crop.parse(st().optString("crop"))); endCrop(false); return }
        @Suppress("DEPRECATION") super.onBackPressed()
    }
    private lateinit var msgTv: TextView
    /** Under the picture (top of the controls), in colour: why no new picture arrives; the last one stays as it is. */
    private lateinit var liveNote: TextView
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
    // landscape, like camera mode: pills at the top left, a column on the right (controls + a round Start / Stop)
    private lateinit var texts: LinearLayout
    private lateinit var backTv: TextView
    private lateinit var landTop: LinearLayout
    private lateinit var landCol: LinearLayout
    private lateinit var chipColumn: android.widget.ScrollView
    private lateinit var shutter: FrameLayout
    private lateinit var shutterCore: View
    private lateinit var shutterLabel: TextView
    private val statusPill by lazy { pill() }
    private val infoPill by lazy { pill() }
    private val livePill by lazy { pill() }
    private var colPx = 0
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
        r.busy = { draft.length() > 0 || saving }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
        // the last picture seen of this camera stays on screen (dimmed) until a new one arrives
        CamRemote.lastFrames[cam]?.let { (bmp, at) -> image.setImageBitmap(bmp); lastFrameAt = at }
        feed = r.Feed(10 * 60_000L, { bmp, at ->
            if (rtcOn) { feed.stop(); return@Feed }   // video is playing: a late picture doesn't belong on screen
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
        Live.eventListeners += events
        Live.viewerActive = true
        Live.refresh(this)
        refreshAll()
        feed.start()
        rtcFailed = false
        rtcRetries = 0
        resumedNow = true
        maybeStartRtc()
        ui.post(tick)
    }

    override fun onPause() {
        Live.listeners -= refresh
        Live.eventListeners -= events
        r.leave()   // gives the remote back
        if (::feed.isInitialized) feed.stop()
        resumedNow = false
        stopRtc()
        ui.removeCallbacks(tick)
        super.onPause()
    }

    override fun onDestroy() {
        try { renderer?.release() } catch (_: Throwable) {}
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        com.motioncam.Ui.statusBarForOrientation(this)
        arrangeBottom()
    }

    /** Settings changed on the camera phone itself: the controls already show them; say so, and keep unsent changes. */
    private val events: (String, String, JSONObject) -> Unit = { c, type, d ->
        if (c == cam && type == "settings_local") {
            val keys = d.optJSONArray("keys")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
            val clash = keys.any { draft.has(it) }
            Toast.makeText(this, "The camera phone changed its " + r.changedText(d) +
                (if (clash) ". Your unsent values for those are kept: Save to apply them, or Cancel to take the camera's." else "."),
                Toast.LENGTH_LONG).show()
            refreshAll()
        }
    }

    /** Once a second: recording time, "x s ago" of the last frame. */
    private val tick = object : Runnable {
        override fun run() {
            renderStatus(); updateLiveLabel()
            // why no new picture arrives (with a picture on screen the reason goes under it; with none, in the middle)
            val h = if (feed.watching && !rtcOn) feed.health() else null
            if (h != null && lastFrameAt > 0) {
                liveNote.text = "⚠ $h · showing the picture from " + Fmt.t(prefs, lastFrameAt); liveNote.visibility = View.VISIBLE
                msgTv.visibility = View.GONE
            } else {
                liveNote.visibility = View.GONE
                if (h != null) { msgTv.text = h; msgTv.visibility = View.VISIBLE }
            }
            ui.postDelayed(this, 1000)
        }
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
        if (Rtc.enabled && prefs.liveSmooth) try {
            // always present, under the pictures: Android only creates a video surface for a view that's shown, so a
            // hidden one never draws a frame (and video never started). The pictures step aside once video plays.
            renderer = org.webrtc.SurfaceViewRenderer(this).apply {
                init(Rtc.egl.eglBaseContext, object : org.webrtc.RendererCommon.RendererEvents {
                    override fun onFirstFrameRendered() {}   // once per screen only: see videoStarted
                    override fun onFrameResolutionChanged(w: Int, h: Int, rot: Int) {
                        runOnUiThread { if (rot % 180 != 0) { rtcW = h; rtcH = w } else { rtcW = w; rtcH = h }; cropView.invalidate() }
                    }
                })
                setScalingType(org.webrtc.RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                setOnClickListener { toggleControls() }
            }
            // the renderer sized to the video and centred in a box filling the screen: given the whole box it would
            // fill it, cropping the video
            rendererBox = FrameLayout(this).apply { addView(renderer, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER)) }
            root.addView(rendererBox, 0, FrameLayout.LayoutParams(-1, -1))
        } catch (_: Throwable) { renderer = null }
        cropView = com.motioncam.CropView(this).apply {
            picture = { imageRect() }
            shape = com.motioncam.Crop.SHAPES.getOrNull(prefs.cropShape)?.second ?: 0f
            onChange = { _, _ -> tickPanel() }
            onHold = { h -> com.motioncam.CropView.fade(bottomBox, h) }
        }
        root.addView(cropView, FrameLayout.LayoutParams(-1, -1))
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
        backTv = Ui.label(this, "‹", 30f).apply {
            setPadding(d(10), 0, d(12), d(4)); contentDescription = "Back"; setOnClickListener { finish() }
        }
        top.addView(backTv)
        texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        statusTv = Ui.label(this, "", 15f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        infoTv = Ui.label(this, "", 11f, 0xFFB0B4BA.toInt()).apply { maxLines = 3; includeFontPadding = false }
        texts.addView(statusTv); texts.addView(infoTv)
        top.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        liveTv = Ui.label(this, "", 12f, Ui.GREY).apply { setPadding(d(8), 0, 0, 0); gravity = Gravity.END }
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
        liveNote = Ui.label(this, "", 12f, Ui.ACCENT).apply { setPadding(d(12), 0, d(12), d(6)); visibility = View.GONE }
        bottomBox.addView(liveNote)
        bottomBox.addView(saveBar)
        bottomBox.addView(panel)
        // editing a crop: Cancel · Turn off crop · Done (Done lines it up; Save sends it, like the other controls)
        cropBar = com.motioncam.CropView.bar(this,
            cancel = { cropView.set(com.motioncam.Crop.parse(st().optString("crop"))); endCrop(false) },
            off = { cropView.reset(); endCrop(true) },
            done = { endCrop(true) })
        bottomBox.addView(cropBar)
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
        root.addView(bottomBox, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        buildLandscape(root)
        arrangeBottom()

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) { if (fromUser && !updating) onSeek(p) }
            override fun onStartTrackingTouch(sb: SeekBar) { touching = true }
            override fun onStopTrackingTouch(sb: SeekBar) { touching = false; updateSaveBar() }
        })
        setContentView(root)
    }

    /** The same buttons, in the same order, as on the camera phone's screen (zones excluded). */
    private fun chipNames(): List<String> {
        val all = Prefs.ALL_CHIPS.filter { it != "ZONES" }
        return prefs.chipLayout.split(",").map { it.trim() }.filter { it in all }.distinct().ifEmpty { all }
    }

    private fun pill() = FrameLayout(this).apply {
        background = pillBg(false)
        setPadding(d(12), 0, d(12), 0)
    }

    private fun pillBg(round: Boolean) = android.graphics.drawable.GradientDrawable().apply {
        if (round) shape = android.graphics.drawable.GradientDrawable.OVAL else cornerRadius = d(16).toFloat()
        setColor(0x73151719)
    }

    /** The landscape pieces (hidden in portrait): the pills' row and the right-hand column with the round Start / Stop. */
    private fun buildLandscape(root: FrameLayout) {
        landTop = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE }
        root.addView(landTop, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(d(8), d(8), 0, 0) })
        landCol = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0x99141619.toInt())
            visibility = View.GONE
        }
        chipColumn = android.widget.ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        landCol.addView(chipColumn, LinearLayout.LayoutParams(d(80), -1))
        val lane = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        shutter = FrameLayout(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setStroke(d(3), Color.WHITE) }
            isClickable = true
            setOnClickListener { onStartStop() }
            com.motioncam.pressFeedback(this)
        }
        shutterCore = View(this)
        shutter.addView(shutterCore, FrameLayout.LayoutParams(d(46), d(46), Gravity.CENTER))
        lane.addView(shutter, LinearLayout.LayoutParams(d(60), d(60)))
        shutterLabel = Ui.label(this, "START", 11f).apply { setTypeface(typeface, android.graphics.Typeface.BOLD); gravity = Gravity.CENTER; setPadding(0, d(5), 0, 0) }
        lane.addView(shutterLabel, LinearLayout.LayoutParams(-1, -2))
        landCol.addView(lane, LinearLayout.LayoutParams(0, -1, 1f))
        root.addView(landCol, FrameLayout.LayoutParams(d(148), -1, Gravity.END))
    }

    /**
     * Portrait: the top bar, then the picture, the buttons and a full-width Start / Stop. Landscape phones, like camera
     * mode: the picture beside a column on the right (the controls, scrolling, and a round Start / Stop), the status as
     * pills at the top left, and the adjust panel floating at the bottom of the picture.
     */
    private fun arrangeBottom() {
        val cfg = resources.configuration
        val want = cfg.orientation == Configuration.ORIENTATION_LANDSCAPE && cfg.smallestScreenWidthDp < 600
        if (compact == want) return
        compact = want
        fun detach(v: View?) { (v?.parent as? ViewGroup)?.removeView(v) }
        listOf(strip, startBtn, stripRow, chipRow, statusTv, infoTv, liveTv, backTv).forEach { detach(it) }
        if (want) {
            top.visibility = View.GONE
            landTop.removeAllViews()
            backTv.background = pillBg(true); backTv.gravity = Gravity.CENTER; backTv.setPadding(0, 0, 0, d(4))
            landTop.addView(backTv, LinearLayout.LayoutParams(d(40), d(40)))
            statusTv.textSize = 13f
            statusPill.removeAllViews(); statusPill.addView(statusTv, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_VERTICAL))
            landTop.addView(statusPill, LinearLayout.LayoutParams(-2, d(32)).apply { leftMargin = d(6) })
            infoTv.maxLines = 1; infoTv.ellipsize = android.text.TextUtils.TruncateAt.END; infoTv.textSize = 12f
            infoTv.maxWidth = (resources.displayMetrics.widthPixels - d(148 + 420)).coerceAtLeast(d(120))
            infoPill.removeAllViews(); infoPill.addView(infoTv, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_VERTICAL))
            landTop.addView(infoPill, LinearLayout.LayoutParams(-2, d(32)).apply { leftMargin = d(6) })
            livePill.removeAllViews(); livePill.addView(liveTv, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_VERTICAL))
            liveTv.setPadding(0, 0, 0, 0)
            landTop.addView(livePill, LinearLayout.LayoutParams(-2, d(32)).apply { leftMargin = d(6) })
            landTop.visibility = View.VISIBLE
            // the column: as wide as the picture leaves room for (148–240 dp)
            val dm = resources.displayMetrics
            val dr = image.drawable
            val aspect = if (dr != null && dr.intrinsicHeight > 0) dr.intrinsicWidth.toFloat() / dr.intrinsicHeight else 16f / 9f
            colPx = (dm.widthPixels - (dm.heightPixels * aspect).toInt()).coerceIn(d(148), d(240))
            landCol.layoutParams = FrameLayout.LayoutParams(colPx, -1, Gravity.END)
            chipColumn.layoutParams = LinearLayout.LayoutParams(colPx - d(68), -1)
            chipRow.orientation = LinearLayout.VERTICAL
            chipRow.setPadding(d(6), d(8), d(4), d(8))
            for (i in 0 until chipRow.childCount) chipRow.getChildAt(i).layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, d(3), 0, d(3)) }
            chipColumn.addView(chipRow)
            landCol.visibility = View.VISIBLE
            // the picture beside the column; the panel floats at its bottom
            image.layoutParams = FrameLayout.LayoutParams(-1, -1).apply { rightMargin = colPx }
            rendererBox?.layoutParams = FrameLayout.LayoutParams(-1, -1).apply { rightMargin = colPx }
            bottomBox.background = android.graphics.drawable.GradientDrawable().apply { setColor(0x99202328.toInt()); cornerRadius = d(16).toFloat() }
            // no padding of its own: with nothing to show (no panel, note or unsent changes) it takes no room
            bottomBox.setPadding(d(4), 0, d(4), 0)
            bottomBox.layoutParams = FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(d(8), 0, colPx + d(8), d(8)) }
        } else {
            landTop.visibility = View.GONE
            landCol.visibility = View.GONE
            top.visibility = View.VISIBLE
            backTv.background = null; backTv.gravity = Gravity.NO_GRAVITY; backTv.setPadding(d(10), 0, d(12), d(4))
            top.addView(backTv, 0)
            statusTv.textSize = 15f
            infoTv.maxLines = 3; infoTv.ellipsize = null; infoTv.textSize = 11f; infoTv.maxWidth = Int.MAX_VALUE
            texts.addView(statusTv); texts.addView(infoTv)
            liveTv.setPadding(d(8), 0, 0, 0)
            top.addView(liveTv)
            chipRow.orientation = LinearLayout.HORIZONTAL
            chipRow.setPadding(d(8), 0, d(8), 0)
            for (i in 0 until chipRow.childCount) chipRow.getChildAt(i).layoutParams = LinearLayout.LayoutParams(-2, -2).apply { setMargins(d(3), 0, d(3), 0) }
            strip.addView(chipRow)
            image.layoutParams = FrameLayout.LayoutParams(-1, -1)
            rendererBox?.layoutParams = FrameLayout.LayoutParams(-1, -1)
            bottomBox.background = null
            bottomBox.setBackgroundColor(0xCC000000.toInt() or (Ui.PANEL and 0x00FFFFFF))
            bottomBox.setPadding(0, d(6), 0, d(8))
            bottomBox.layoutParams = FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM)
            bottomBox.addView(strip)
            bottomBox.addView(startBtn, LinearLayout.LayoutParams(-1, -2).apply { setMargins(d(12), d(8), d(12), 0) })
            startBtn.setPadding(0, d(12), 0, d(12)); startBtn.textSize = 16f
        }
        updateLiveLabel()
        renderStart()
    }

    /** Top bar and controls (portrait), or the pills and the column (landscape). */
    private fun chrome(): List<View> = if (compact == true) listOf(landTop, landCol, bottomBox) else listOf(top, bottomBox)

    private fun toggleControls() {
        controlsShown = !controlsShown
        for (v in chrome()) {
            v.animate().cancel()
            if (controlsShown) { v.visibility = View.VISIBLE; v.alpha = 0f; v.animate().alpha(1f).setDuration(150).start() }
            else v.animate().alpha(0f).setDuration(150).withEndAction { v.visibility = View.GONE }.start()
        }
    }

    private fun updateLiveLabel() {
        // also called while the screen is being built, before the feed exists
        if (!::feed.isInitialized || !::liveTv.isInitialized) return
        val text = when {
            rtcOn && r.online() -> "● LIVE · video" + (relayNote?.let { " (direct only: $it)" } ?: "")
            videoIssue != null && feed.watching && lastFrameAt > 0 && r.online() -> "● LIVE · pictures (video: ${videoIssue})"
            !r.online() -> "offline"
            !feed.watching -> "paused"
            lastFrameAt <= 0 -> "connecting…"
            // portrait has room under LIVE (the top bar has two or three lines): the time goes there
            else -> "● LIVE" + (if (compact == true) " · " else "\n") + "frame sent " + Fmt.t(prefs, lastFrameAt)
        }
        liveTv.text = text
        liveTv.setTextColor(if (rtcOn || feed.watching && lastFrameAt > 0 && System.currentTimeMillis() - lastFrameAt < 8000) Ui.RED else Ui.GREY)
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
        maybeStartRtc()   // the camera's status (or the PIN) may only have arrived now
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
        s.optLong("lastMotion").takeIf { it > 0 }?.let { parts += "motion " + Fmt.t(prefs, it, seconds = false) }
        infoTv.text = android.text.SpannableStringBuilder().apply {
            parts.filter { it.isNotBlank() }.forEachIndexed { i, x -> if (i > 0) append(" · "); append(x) }
            r.lockText()?.let { append("\n").append(it) }
        }
    }

    private fun renderStart() {
        if (!::startBtn.isInitialized) return
        val s = status()
        val (text, color) = when {
            s == null || !r.online() -> "OFFLINE" to 0xFF4A4E55.toInt()
            s.optBoolean("monitoring") -> "STOP MONITORING" to Ui.RED
            s.optBoolean("standby") -> "START MONITORING" to 0xFF2E7D32.toInt()
            else -> "START MONITORING" to 0xFF2E7D32.toInt()
        }
        startBtn.text = if (compact == true) text.substringBefore(" ") else text
        Ui.pill(startBtn, color)
        // the landscape round button: a green circle to start, a red square to stop, grey when offline
        if (::shutterCore.isInitialized) {
            val running = s?.optBoolean("monitoring") == true
            val off = s == null || !r.online()
            shutterCore.layoutParams = FrameLayout.LayoutParams(d(if (running) 22 else 46), d(if (running) 22 else 46), Gravity.CENTER)
            shutterCore.background = android.graphics.drawable.GradientDrawable().apply {
                if (running) cornerRadius = d(6).toFloat() else shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(color)
            }
            shutterLabel.text = if (off) "OFFLINE" else if (running) "STOP" else "START"
            shutter.contentDescription = if (running) "Stop monitoring" else "Start monitoring"
        }
    }

    private fun onStartStop() {
        val s = status() ?: return
        if (!r.online()) { Toast.makeText(this, "The camera is offline", Toast.LENGTH_SHORT).show(); return }
        if (r.lockedOut()) { r.touch(); return }
        when {
            // stops detection and recording; the camera stays on (Shut off camera is on the camera's screen)
            s.optBoolean("monitoring") -> r.send("pause")
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
        if (st.optBoolean("exposureAuto", true)) set("EV", evText(st.optInt("evIndex")), false)
        else set("EV", (lv.opt("meter") as? Number)?.let { meterText(it.toDouble()) } ?: "—", false)
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
        set("CROP", cropSize(com.motioncam.Crop.parse(st.optString("crop")))?.let { com.motioncam.Crop.label(it, status()?.optInt("rotation") ?: 0) } ?: "off", false)
        syncCropView()
        // values changed here but not sent yet: a dot on their control (Save sends them)
        for ((n, keys) in CHIP_KEYS) chips[n]?.mark(keys.any { draft.has(it) })
    }

    private val CHIP_KEYS = mapOf("LENS" to listOf("camera"), "FORMAT" to listOf("resHeight", "fps"),
        "EV" to listOf("evIndex", "exposureAuto"), "ISO" to listOf("iso", "exposureAuto"), "SPEED" to listOf("shutterNs", "exposureAuto"),
        "FOCUS" to listOf("focusPos", "focusAuto"), "WB" to listOf("wbKelvin", "wbAuto"), "ZOOM" to listOf("zoom"), "CROP" to listOf("crop"))

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
        if (r.lockedOut()) { r.touch(); return }
        if (selected == "CROP") {
            if (name == "CROP") endCrop(true) else Toast.makeText(this, "Finish the crop first: Done or Cancel", Toast.LENGTH_SHORT).show()
            return
        }
        when (name) {
            "LENS" -> { showLensDialog(); return }
            "FORMAT" -> { showFormatDialog(); return }
        }
        selected = if (selected == name) null else name
        panel.visibility = if (selected == null) View.GONE else View.VISIBLE
        if (selected != null) configurePanel()
        refreshChips()
        syncCropView()
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
            "CROP" -> setAutoBtn("", false, false)
        }
        seek.visibility = if (selected == "CROP") View.INVISIBLE else View.VISIBLE
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
            "EV" -> if (st.optBoolean("exposureAuto", true)) "EV " + evText(st.optInt("evIndex"))
                else "Meter " + ((lv.opt("meter") as? Number)?.let { meterText(it.toDouble()) } ?: "—") + " EV"
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
            "CROP" -> cropText()
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
            "CROP" -> { cropView.reset(); configurePanel(); return }
            else -> return
        }
        apply(a)
    }

    /** Lines up a change (shown right away on the buttons and slider); sent with Save. */
    private fun apply(a: JSONObject) = stage(a, refresh = true)

    private fun stage(a: JSONObject, refresh: Boolean) {
        if (!r.touch()) { refreshAll(); return }   // claims the camera's remote (or says who is changing it)
        val server = status()?.optJSONObject("settings") ?: JSONObject()
        for (k in a.keys()) {
            // back to what the camera already has: nothing to send for it
            if (server.opt(k)?.toString() == a.opt(k)?.toString() && pending.opt(k) == null) draft.remove(k) else draft.put(k, a.get(k))
        }
        if (refresh) refreshAll()
        updateSaveBar()
    }

    private fun updateSaveBar() {
        if (!::saveBar.isInitialized || cropFocused) return
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
        if (sel == "CROP") {
            OptionSheet.show(this, "Shape of the recorded area", com.motioncam.Crop.SHAPES.mapIndexed { i, (name, _) ->
                Opt(name, if (i == 0) "Any rectangle" else "Keeps this shape while you resize it", checked = i == prefs.cropShape) {
                    prefs.cropShape = i
                    cropView.shape = com.motioncam.Crop.SHAPES[i].second
                    cropView.sensorRect()?.let { rc -> stage(JSONObject().put("crop", com.motioncam.Crop.format(rc)), refresh = false); refreshChips() }
                    tickPanel()
                }
            })
            return
        }
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
    private fun meterText(v: Double) = (if (v > 0.05) "+" else "") + "%.1f".format(v)
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
