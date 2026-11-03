package com.motioncam

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import java.util.Date

/**
 * Continuous playback of motion clips in chronological order. Two players alternate: while one plays,
 * the next clip is prepared on the other and chained with setNextMediaPlayer, so events play back
 * seamlessly like one video. A Samsung-style filmstrip below shows the clips.
 *  - tap video: play / pause;  double-tap left / right third: back / forward 5 s
 */
class PlayerActivity : Activity() {
    companion object {
        const val EXTRA_START_ID = "startId"
        /** Play only the clips of this collection. */
        const val EXTRA_COLLECTION = "collection"
        /** Play a remote folder (read-only). */
        const val EXTRA_REMOTE = "remote"
    }

    private var remote: com.motioncam.remote.RemoteSource? = null
    private var store: CollectionStore = Collections

    private inner class Slot(val tv: TextureView) {
        var surface: Surface? = null
        var mp: MediaPlayer? = null
        var index = -1
        var prepared = false
        var vw = 0
        var vh = 0
        fun release() {
            try { mp?.setNextMediaPlayer(null) } catch (_: Exception) {}
            try { mp?.release() } catch (_: Exception) {}
            mp = null; prepared = false; index = -1
        }
    }

    private lateinit var prefs: Prefs
    private var clips: MutableList<Clip> = mutableListOf()   // oldest first
    private lateinit var video: FrameLayout
    private lateinit var a: Slot
    private lateinit var b: Slot
    private lateinit var cur: Slot
    private lateinit var nxt: Slot
    private lateinit var strip: FilmstripView
    private lateinit var stamp: TextView
    private lateinit var info: TextView
    private lateinit var bigPlay: android.widget.ImageView
    private lateinit var topBar: LinearLayout
    private lateinit var bar: LinearLayout
    private lateinit var zoomReset: android.widget.ImageView
    private var vScale = 1f
    private var vTx = 0f
    private var vTy = 0f
    private var pinching = false
    private lateinit var skipL: TextView
    private lateinit var skipR: TextView
    private lateinit var speedBtn: TextView
    private lateinit var skipBtn: TextView
    private lateinit var muteBtn: android.widget.ImageView
    private var playing = true
    private var muted = false
    private var speed = 1f
    private var pendingStart: Pair<Int, Int>? = null
    private val ui = Handler(Looper.getMainLooper())
    private val stampFmt by lazy { Fmt.dateTime(prefs) }

    private val ticker = object : Runnable {
        override fun run() {
            updatePosition()
            ui.postDelayed(this, if (playing) 50 else 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        intent.getStringExtra(EXTRA_REMOTE)?.let { rid ->
            remote = com.motioncam.remote.RemoteSources.get(this, rid) ?: run { finish(); return }
            store = Collections.forRemote(rid)
        }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }

        // ---- video area
        video = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        a = Slot(TextureView(this))
        b = Slot(TextureView(this))
        for (s in listOf(a, b)) {
            video.addView(s.tv, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
            s.tv.alpha = 0f
            s.tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                    s.surface = Surface(st)
                    s.mp?.setSurface(s.surface)
                    maybeBegin()
                }
                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                    try { s.mp?.setSurface(null) } catch (_: Exception) {}
                    s.surface?.release(); s.surface = null
                    return true
                }
                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
            }
        }
        cur = a; nxt = b
        video.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> layoutSlot(a); layoutSlot(b) }

        bigPlay = android.widget.ImageView(this).apply {
            setImageDrawable(IconDrawable(IconDrawable.Kind.PLAY))
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x4D000000) }
            val p = Ui.dp(this@PlayerActivity, 10)
            setPadding(p, p, p, p)
            visibility = View.GONE
        }
        val bp = Ui.dp(this, 38)
        video.addView(bigPlay, FrameLayout.LayoutParams(bp, bp, Gravity.CENTER))
        skipL = skipLabel("").also { video.addView(it, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_VERTICAL or Gravity.START).apply { leftMargin = Ui.dp(this@PlayerActivity, 40) }) }
        skipR = skipLabel("").also { video.addView(it, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_VERTICAL or Gravity.END).apply { rightMargin = Ui.dp(this@PlayerActivity, 40) }) }

        // top overlay: back, time stamp, menu
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x99000000.toInt(), 0))
            val p = Ui.dp(this@PlayerActivity, 6)
            setPadding(p, p, p, p * 3)
        }
        topBar.addView(iconButton(this, IconDrawable.Kind.BACK) { finish() })
        stamp = Ui.label(this, "", 15f).apply { setShadowLayer(4f, 0f, 0f, Color.BLACK); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        topBar.addView(stamp, LinearLayout.LayoutParams(0, -2, 1f))
        lateinit var menuBtn: View
        menuBtn = iconButton(this, IconDrawable.Kind.MORE) { showMenu(menuBtn) }
        topBar.addView(menuBtn)
        video.addView(topBar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        zoomReset = iconButton(this, IconDrawable.Kind.ZOOM_RESET, 40, 10) { resetZoom() }.apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x66000000) }
            visibility = View.GONE
        }
        video.addView(zoomReset, FrameLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44), Gravity.BOTTOM or Gravity.END).apply {
            setMargins(0, 0, Ui.dp(this@PlayerActivity, 14), Ui.dp(this@PlayerActivity, 14))
        })

        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                if (vScale > 1.01f && !pinching) { vTx -= dx; vTy -= dy; applyZoom() }
                return true
            }
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean { togglePlay(); return true }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val w = video.width
                when {
                    e.x < w / 3f -> skip(-1)
                    e.x > w * 2f / 3f -> skip(1)
                    else -> togglePlay()
                }
                return true
            }
        })
        val sd = android.view.ScaleGestureDetector(this, object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(d: android.view.ScaleGestureDetector): Boolean { pinching = true; return true }
            override fun onScale(d: android.view.ScaleGestureDetector): Boolean {
                val ns = (vScale * d.scaleFactor).coerceIn(1f, 6f)
                // zoom around the fingers
                val cx = d.focusX - video.width / 2f
                val cy = d.focusY - video.height / 2f
                vTx = cx - (cx - vTx) * ns / vScale
                vTy = cy - (cy - vTy) * ns / vScale
                vScale = ns
                applyZoom()
                return true
            }
            override fun onScaleEnd(d: android.view.ScaleGestureDetector) { pinching = false; if (vScale < 1.03f) resetZoom() }
        })
        var tapSkipping = false
        video.setOnTouchListener { _, e ->
            sd.onTouchEvent(e)
            // after a double-tap skip, each further quick tap on that side skips again (like YouTube)
            if (e.actionMasked == MotionEvent.ACTION_DOWN && vScale <= 1.01f) {
                val side = when { e.x < video.width / 3f -> -1; e.x > video.width * 2f / 3f -> 1; else -> 0 }
                if (side != 0 && side == lastSkipDir && System.currentTimeMillis() - lastSkipAt < 700) {
                    tapSkipping = true
                    skip(side)
                    val cancel = MotionEvent.obtain(e).apply { action = MotionEvent.ACTION_CANCEL }
                    gd.onTouchEvent(cancel)   // reset the double-tap detector
                    cancel.recycle()
                    return@setOnTouchListener true
                }
            }
            if (tapSkipping) {
                if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) tapSkipping = false
                return@setOnTouchListener true
            }
            if (!pinching && e.pointerCount == 1) gd.onTouchEvent(e)
            true
        }
        root.addView(video, LinearLayout.LayoutParams(-1, 0, 1f))

        // ---- filmstrip + compact controls
        strip = FilmstripView(this)
        strip.listener = object : FilmstripView.Listener {
            override fun onScrub(index: Int, posMs: Long, final: Boolean) = scrubTo(index, posMs, final)
            override fun onTapClip(index: Int) { startAt(index, 0); strip.setCurrent(index, true) }
        }
        root.addView(strip, LinearLayout.LayoutParams(-1, Ui.dp(this, 84)))

        bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val p = Ui.dp(this@PlayerActivity, 6)
            setPadding(p * 2, p, p * 2, p * 2)
        }
        bar.addView(iconButton(this, IconDrawable.Kind.PREV, 48, 12) { prev() })
        bar.addView(iconButton(this, IconDrawable.Kind.NEXT, 48, 12) { next() })
        speedBtn = Ui.label(this, "1x", 16f).apply {
            gravity = Gravity.CENTER
            minWidth = Ui.dp(this@PlayerActivity, 48)
            setOnClickListener { showSpeedMenu(this) }
        }
        bar.addView(speedBtn)
        skipBtn = Ui.label(this, "", 15f).apply {
            gravity = Gravity.CENTER
            minWidth = Ui.dp(this@PlayerActivity, 52)
            contentDescription = "Double-tap skip length"
            setOnClickListener { showSkipMenu(this) }
        }
        bar.addView(skipBtn)
        updateSkipLabels()
        muteBtn = iconButton(this, IconDrawable.Kind.VOLUME, 48, 12) { toggleMute() }
        bar.addView(muteBtn)
        info = Ui.label(this, "", 12f, Ui.GREY).apply { gravity = Gravity.END; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        bar.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(bar)
        setContentView(root)

        val startId = intent.getLongExtra(EXTRA_START_ID, -1L)
        Thread {
            val cid = intent.getStringExtra(EXTRA_COLLECTION)
            val inCollection = cid?.let { store.get(this, it)?.clipIds }
            val src = remote
            // remote clips: lengths come from the uploaded thumbnails (or an estimate, corrected when each one loads)
            val all = if (src != null) (try { com.motioncam.remote.RemoteRepo.clips(this, src) } catch (e: Exception) { emptyList() }) else ClipStore.list(this)
            val list = all.filter { inCollection == null || it.id in inCollection }.sortedBy { it.startMs }.map { c ->
                if (c.durationMs > 0 || src != null) c else c.copy(durationMs = probeDuration(c))
            }.toMutableList()
            runOnUiThread {
                clips = list
                if (list.isEmpty()) { Toast.makeText(this, "No clips", Toast.LENGTH_SHORT).show(); finish(); return@runOnUiThread }
                val idx = list.indexOfFirst { it.id == startId }.let { if (it < 0) 0 else it }
                strip.clips = list
                strip.setCurrent(idx, true)   // open expanded, like Samsung's player
                pendingStart = Pair(idx, 0)
                maybeBegin()
            }
        }.start()
    }

    private fun skipLabel(t: String) = Ui.label(this, t, 16f).apply {
        setShadowLayer(6f, 0f, 0f, Color.BLACK)
        background = GradientDrawable().apply { cornerRadius = Ui.dp(this@PlayerActivity, 20).toFloat(); setColor(0x66000000) }
        setPadding(Ui.dp(this@PlayerActivity, 14), Ui.dp(this@PlayerActivity, 8), Ui.dp(this@PlayerActivity, 14), Ui.dp(this@PlayerActivity, 8))
        alpha = 0f
    }

    private fun flash(v: View) {
        v.animate().cancel()
        v.alpha = 1f
        v.animate().alpha(0f).setStartDelay(450).setDuration(250).start()
    }

    private fun probeDuration(c: Clip): Long = try {
        val r = MediaMetadataRetriever()
        r.setDataSource(this, c.uri)
        val d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        r.release()
        d
    } catch (e: Exception) { 0L }

    override fun onResume() {
        super.onResume()
        if (playing) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        ui.post(ticker)
    }

    override fun onPause() {
        ui.removeCallbacks(ticker)
        if (playing) togglePlay()
        super.onPause()
    }

    override fun onDestroy() {
        a.release(); b.release()
        strip.release()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ playback

    private fun maybeBegin() {
        val p = pendingStart ?: return
        if (a.surface == null || b.surface == null || clips.isEmpty()) return
        pendingStart = null
        startAt(p.first, p.second)
    }

    private fun startAt(index: Int, offsetMs: Int) {
        if (index !in clips.indices) return
        stepPos = -1
        a.release(); b.release()
        cur = a; nxt = b
        holdAt(offsetMs.toLong())
        strip.setCurrent(index, atMs = offsetMs.toLong())
        load(cur, index, offsetMs)
    }

    private fun load(slot: Slot, index: Int, offsetMs: Int = 0) {
        slot.release()
        if (index !in clips.indices) return
        val mp = MediaPlayer()
        slot.mp = mp
        slot.index = index
        try {
            slot.surface?.let { mp.setSurface(it) }
            val uri = clips[index].uri
            if (uri.scheme == "http") mp.setDataSource(uri.toString()) else mp.setDataSource(this, uri)
            if (muted) mp.setVolume(0f, 0f)
            mp.setOnVideoSizeChangedListener { _, w, h -> slot.vw = w; slot.vh = h; layoutSlot(slot) }
            mp.setOnPreparedListener {
                if (slot.mp !== mp) return@setOnPreparedListener
                slot.prepared = true
                fixDuration(index, mp)
                if (slot === cur) {
                    // previewing under the finger: go to where the finger is now (it may have moved while loading)
                    val off = if (scrubbing && previewIndex == index) previewPos.toInt().coerceAtMost(lastFrameMs(index)) else offsetMs
                    // paused (or scrubbing), the player draws nothing until it seeks: always seek then, even to 0:00,
                    // so the new clip's frame replaces the previous clip's picture
                    if (off > 0 || !playing || scrubbing) {
                        mp.setOnSeekCompleteListener { releaseHoldIfReached(mp) }
                        mp.seekTo(off.toLong(), MediaPlayer.SEEK_CLOSEST)
                    } else holdMs = -1
                    if (playing && !scrubbing) { mp.start(); applySpeed(mp) }
                    show(cur)
                    load(nxt, index + 1)
                } else link()
            }
            mp.setOnCompletionListener {
                if (slot !== cur) return@setOnCompletionListener
                // the finger is still on the strip: never move on to another clip, stay on the last frame
                if (scrubbing) try { mp.seekTo(lastFrameMs(slot.index).toLong(), MediaPlayer.SEEK_CLOSEST) } catch (_: Exception) {}
                else onCurrentCompleted()
            }
            mp.setOnErrorListener { _, _, _ ->
                if (slot === cur && remote != null) Toast.makeText(this, "Could not load this clip (no connection?)", Toast.LENGTH_SHORT).show()
                if (slot === cur) ui.post { advanceTo(slot.index + 1) } else slot.release()
                true
            }
            mp.prepareAsync()
        } catch (e: Exception) {
            slot.release()
            if (slot === cur) ui.post { advanceTo(index + 1) }
        }
    }

    /** Remote clips start with an estimated length; use the real one once the player knows it. */
    private fun fixDuration(index: Int, mp: MediaPlayer) {
        if (remote == null || index !in clips.indices) return
        val d = try { mp.duration.toLong() } catch (_: Exception) { return }
        val c = clips[index]
        if (d <= 0 || Math.abs(d - c.durationMs) < 300) return
        clips[index] = c.copy(durationMs = d)
        com.motioncam.remote.RemoteRepo.setDuration(this, c.id, d)
        if (scrubbing) { stripStale = true; return }   // don't re-layout the strip under the finger
        val curIdx = cur.index
        val pos = curPos().toLong()
        strip.clips = clips
        if (curIdx in clips.indices) strip.setCurrent(curIdx, strip.expanded, atMs = pos)
    }

    /** Chains the prepared next clip so it starts the instant the current one ends. */
    private fun link() {
        if (scrubbing) return   // re-chained when the finger lifts
        val cm = cur.mp ?: return
        val nm = nxt.mp ?: return
        if (cur.prepared && nxt.prepared && nxt.index == cur.index + 1) {
            try { cm.setNextMediaPlayer(nm) } catch (_: Exception) {}
        }
    }

    private fun onCurrentCompleted() {
        val old = cur
        if (nxt.prepared && nxt.index == old.index + 1) {
            cur = nxt
            nxt = old
            val m = cur.mp!!
            // the chained player starts by itself; keep it paused if playback is paused (or the user is scrubbing)
            if (!playing || scrubbing) try { m.pause(); m.seekTo(0, MediaPlayer.SEEK_CLOSEST) } catch (_: Exception) {}
            else if (!m.isPlaying) m.start()
            if (playing && !scrubbing) applySpeed(m)
            show(cur)
            strip.setCurrent(cur.index)
            old.release()
            load(nxt, cur.index + 1)
        } else if (old.index + 1 < clips.size) {
            advanceTo(old.index + 1)
        } else {
            setPlaying(false)
        }
    }

    private fun advanceTo(index: Int) { if (index in clips.indices) startAt(index, 0) else setPlaying(false) }

    private fun show(s: Slot) {
        s.tv.alpha = 1f
        (if (s === a) b else a).tv.alpha = 0f
    }

    private fun layoutSlot(s: Slot) {
        val cw = video.width
        val ch = video.height
        if (cw == 0 || ch == 0 || s.vw == 0 || s.vh == 0) return
        val va = s.vw.toFloat() / s.vh
        var w = cw
        var h = (cw / va).toInt()
        if (h > ch) { h = ch; w = (ch * va).toInt() }
        val lp = s.tv.layoutParams as FrameLayout.LayoutParams
        if (lp.width != w || lp.height != h) {
            lp.width = w; lp.height = h; lp.gravity = Gravity.CENTER
            s.tv.layoutParams = lp
        }
    }

    private fun applySpeed(mp: MediaPlayer) {
        try { mp.playbackParams = mp.playbackParams.setSpeed(speed) } catch (_: Exception) {}
    }

    private fun setPlaying(p: Boolean) {
        playing = p
        // screen stays on only while a video is actually playing
        if (p) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        bigPlay.animate().cancel()
        if (vScale > 1.03f) {
            // zoomed: only a brief indicator, like the 5 s skip labels
            bigPlay.setImageDrawable(IconDrawable(if (p) IconDrawable.Kind.PAUSE else IconDrawable.Kind.PLAY))
            bigPlay.visibility = View.VISIBLE
            bigPlay.alpha = 1f
            bigPlay.animate().alpha(0f).setStartDelay(450).setDuration(250).withEndAction { bigPlay.visibility = View.GONE }.start()
            return
        }
        if (p) { bigPlay.setImageDrawable(IconDrawable(IconDrawable.Kind.PAUSE)); bigPlay.visibility = View.VISIBLE; bigPlay.alpha = 1f; bigPlay.animate().alpha(0f).setStartDelay(350).setDuration(250).withEndAction { bigPlay.visibility = View.GONE }.start() }
        else { bigPlay.setImageDrawable(IconDrawable(IconDrawable.Kind.PLAY)); bigPlay.alpha = 1f; bigPlay.visibility = View.VISIBLE }
    }

    private fun togglePlay() {
        setPlaying(!playing)
        if (playing) stepPos = -1
        val m = cur.mp
        if (m == null || !cur.prepared) return
        try {
            if (playing) { m.start(); applySpeed(m) } else m.pause()
        } catch (_: Exception) {}
    }

    private fun curPos(): Int = try { if (cur.prepared) cur.mp?.currentPosition ?: 0 else 0 } catch (_: Exception) { 0 }

    // ------------------------------------------------------------------ double-tap skip

    private val skipOptions = listOf(0, 500, 1000, 2000, 5000, 10_000)   // 0 = one frame

    private fun skipText(ms: Int) = when {
        ms == 0 -> "1 frame"
        ms < 1000 -> "%.1fs".format(ms / 1000f)
        else -> "${ms / 1000}s"
    }

    private fun updateSkipLabels() {
        val t = skipText(prefs.skipMs)
        skipL.text = "«  $t"
        skipR.text = "$t  »"
        skipBtn.text = "±" + if (prefs.skipMs == 0) "1 fr" else t
    }

    /** Skip length for double-taps (kept for all clips and next time): a time, or frame by frame. */
    private fun showSkipMenu(anchor: View) {
        val pm = PopupMenu(this, anchor)
        skipOptions.forEachIndexed { i, v ->
            pm.menu.add(0, i, i, (if (v == 0) "Frame by frame" else "Skip " + skipText(v)) + if (v == 1000) "  (default)" else "").apply {
                isCheckable = true
                isChecked = v == prefs.skipMs
            }
        }
        pm.menu.setGroupCheckable(0, true, true)
        pm.setOnMenuItemClickListener { item -> prefs.skipMs = skipOptions[item.itemId]; updateSkipLabels(); true }
        pm.show()
    }

    /** Frame length per clip (ms), measured once per clip; the recording frame rate until then. */
    private val frameMs = HashMap<Long, Float>()

    private fun frameLen(c: Clip): Float {
        frameMs[c.id]?.let { return it }
        frameMs[c.id] = 1000f / prefs.fps.coerceAtLeast(1)
        Thread {
            val r = MediaMetadataRetriever()
            try {
                ClipThumbs.source(r, this, c.uri)
                val n = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toLongOrNull() ?: 0
                val d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
                if (n > 1 && d > 0) runOnUiThread { frameMs[c.id] = d.toFloat() / n }
            } catch (_: Exception) {} finally { try { r.release() } catch (_: Exception) {} }
        }.start()
        return frameMs[c.id]!!
    }

    /** Double-tap: one step back (-1) or forward (+1) by the chosen length; frame steps pause playback. */
    private var lastSkipAt = 0L
    private var lastSkipDir = 0

    private fun skip(dir: Int) {
        val i = cur.index
        if (i !in clips.indices) return
        lastSkipAt = System.currentTimeMillis()
        lastSkipDir = dir
        val ms = prefs.skipMs
        if (ms == 0) {
            if (playing) togglePlay()   // frame by frame is done paused
            val f = frameLen(clips[i])
            // step from the last frame we stepped to (the player may report a nearby key frame instead)
            val now = System.currentTimeMillis()
            val base = if (stepPos >= 0 && stepIndex == i && now - stepAt < 60_000) stepPos else curPos()
            stepPos = seekBy(Math.round(dir * f).let { if (it == 0) dir else it }, base)
            stepIndex = i
            stepAt = now
        } else { stepPos = -1; seekBy(dir * ms) }
    }

    /**
     * Skip within the current clip: with less than the skip length left it goes to the clip's start / end
     * instead of into the neighbouring clip.
     */
    private var stepPos = -1
    private var stepIndex = -1
    private var stepAt = 0L

    /** Returns the position it went to (or -1). */
    private fun seekBy(delta: Int, from: Int = -1): Int {
        flash(if (delta < 0) skipL else skipR)
        val i = cur.index
        if (i !in clips.indices || !cur.prepared) return -1
        val p = ((if (from >= 0) from else curPos()) + delta).coerceIn(0, lastFrameMs(i))
        holdAt(p.toLong())
        cur.mp?.let { m -> m.setOnSeekCompleteListener { releaseHoldIfReached(m) } }
        try { cur.mp?.seekTo(p.toLong(), MediaPlayer.SEEK_CLOSEST) } catch (_: Exception) {}
        return p
    }

    /** Just before the end of clip [i]: seeking exactly to the end would count as "finished" and jump to the next clip. */
    private fun lastFrameMs(i: Int): Int {
        val d = (if (i == cur.index && cur.prepared) try { cur.mp?.duration } catch (_: Exception) { null } else null)
            ?: clips[i].durationMs.toInt()
        return maxOf(0, d - 100)
    }

    private var scrubbing = false
    private var previewIndex = -1
    private var stripStale = false
    private var previewPos = 0L
    private var lastPreviewSwitch = 0L
    private val previewSwitch = Runnable { switchPreview() }

    /** Loads the clip under the finger (paused) so its frames show while scrubbing across clips. */
    private fun switchPreview() {
        val i = previewIndex
        if (i !in clips.indices || !scrubbing || i == cur.index) return
        lastPreviewSwitch = System.currentTimeMillis()
        a.release(); b.release()
        cur = a; nxt = b
        holdAt(previewPos)
        load(cur, i, previewPos.toInt())
    }

    /**
     * Filmstrip scrubbing, like YouTube/Samsung: playback pauses while the finger is down and every preview
     * frame is the exact one under the playhead (previously it kept playing and snapped back to the previous
     * key frame, which looked like jitter). Playback resumes on release if it was playing.
     */
    private fun scrubTo(index: Int, posMs: Long, final: Boolean) {
        stepPos = -1
        if (!final && !scrubbing) {
            scrubbing = true
            if (playing) try { cur.mp?.pause() } catch (_: Exception) {}
            // unchain the next clip while scrubbing, so it can never start by itself under the finger
            try { cur.mp?.setNextMediaPlayer(null) } catch (_: Exception) {}
        }
        if (!final && index != cur.index) {
            // dragging over another clip: show that clip's frame (loaded paused; switching is rate-limited)
            previewIndex = index
            previewPos = posMs
            ui.removeCallbacks(previewSwitch)
            val wait = 120 - (System.currentTimeMillis() - lastPreviewSwitch)
            if (wait <= 0) switchPreview() else ui.postDelayed(previewSwitch, wait)
            return
        }
        if (!final && !cur.prepared) { previewIndex = index; previewPos = posMs; return }   // applied once it has loaded
        if (final) {
            ui.removeCallbacks(previewSwitch)
            previewIndex = -1
            scrubbing = false
            if (stripStale) { stripStale = false; strip.clips = clips }
            if (index in clips.indices && strip.current != index) strip.setCurrent(index, atMs = posMs)
            link()   // re-chain for seamless playback
        }
        if (index == cur.index && cur.prepared) {
            val m = cur.mp ?: return
            // stop just short of the end, so dragging to the end shows the last frame instead of finishing the clip
            val pos = posMs.coerceAtMost(lastFrameMs(index).toLong())
            // released at the end (the strip snaps there): stay on the last frame, paused, rather than resuming
            // straight into the next clip
            if (final && playing && posMs >= lastFrameMs(index) - 200) setPlaying(false)
            if (final) {
                holdAt(pos)
                m.setOnSeekCompleteListener {
                    releaseHoldIfReached(m)
                    if (playing && !scrubbing) try { if (!m.isPlaying) { m.start(); applySpeed(m) } } catch (_: Exception) {}
                }
            }
            try { m.seekTo(pos, MediaPlayer.SEEK_CLOSEST) } catch (_: Exception) {}
        } else if (final) startAt(index, posMs.toInt())
    }

    // ------------------------------------------------------------------ pinch zoom

    private fun applyZoom() {
        val maxX = (vScale - 1f) * video.width / 2f
        val maxY = (vScale - 1f) * video.height / 2f
        vTx = vTx.coerceIn(-maxX, maxX)
        vTy = vTy.coerceIn(-maxY, maxY)
        for (s in listOf(a, b)) {
            s.tv.scaleX = vScale; s.tv.scaleY = vScale
            s.tv.translationX = vTx; s.tv.translationY = vTy
        }
        val zoomed = vScale > 1.03f
        // like Samsung's player: zooming in hides the controls; a small button restores everything
        val v = if (zoomed) View.GONE else View.VISIBLE
        if (topBar.visibility != v) {
            topBar.visibility = v
            strip.visibility = v
            bar.visibility = v
        }
        zoomReset.visibility = if (zoomed) View.VISIBLE else View.GONE
        if (zoomed) { bigPlay.animate().cancel(); bigPlay.visibility = View.GONE }
        else if (!playing) { bigPlay.setImageDrawable(IconDrawable(IconDrawable.Kind.PLAY)); bigPlay.alpha = 1f; bigPlay.visibility = View.VISIBLE }
    }

    private fun resetZoom() {
        vScale = 1f; vTx = 0f; vTy = 0f
        applyZoom()
    }

    // ------------------------------------------------------------------ position hold

    private var holdMs = -1L
    private var holdSince = 0L

    private fun holdAt(ms: Long) { holdMs = ms; holdSince = System.currentTimeMillis() }

    /** The player reports a position again once it has really arrived where we asked. */
    private fun releaseHoldIfReached(m: MediaPlayer) {
        val p = try { m.currentPosition.toLong() } catch (_: Exception) { return }
        if (holdMs < 0 || Math.abs(p - holdMs) < 1500) holdMs = -1
    }

    private fun prev() { if (curPos() > 3000) startAt(cur.index, 0) else startAt(maxOf(0, cur.index - 1), 0) }
    private fun next() { if (cur.index + 1 < clips.size) startAt(cur.index + 1, 0) }

    private fun speedLabel(v: Float) = (if (v == v.toInt().toFloat()) "${v.toInt()}" else "$v".trimEnd('0')) + "x"

    /** Playback speed dropdown (0.25x – 8x). */
    private fun showSpeedMenu(anchor: View) {
        val speeds = listOf(0.25f, 0.5f, 0.75f, 1f, 2f, 4f, 8f)
        val pm = PopupMenu(this, anchor)
        speeds.forEachIndexed { i, v ->
            pm.menu.add(0, i, i, speedLabel(v) + if (v == 1f) "  (normal)" else "").apply {
                isCheckable = true
                isChecked = v == speed
            }
        }
        pm.menu.setGroupCheckable(0, true, true)
        pm.setOnMenuItemClickListener { item ->
            speed = speeds[item.itemId]
            speedBtn.text = speedLabel(speed)
            cur.mp?.let { if (playing && cur.prepared) applySpeed(it) }
            true
        }
        pm.show()
    }

    private fun toggleMute() {
        muted = !muted
        muteBtn.setImageDrawable(IconDrawable(if (muted) IconDrawable.Kind.MUTE else IconDrawable.Kind.VOLUME))
        val v = if (muted) 0f else 1f
        for (s in listOf(a, b)) try { s.mp?.setVolume(v, v) } catch (_: Exception) {}
    }

    // ------------------------------------------------------------------ menu

    private fun showMenu(anchor: View) {
        val i = cur.index
        if (i !in clips.indices) return
        val c = clips[i]
        val pm = PopupMenu(this, anchor)
        val fav = store.isFavourite(this, c.id)
        pm.menu.add(0, 4, 0, if (fav) "Remove from Favourites" else "Add to Favourites")
        pm.menu.add(0, 5, 1, "Add to collection…")
        if (remote != null) pm.menu.add(0, 6, 2, "Save to phone")
        else {
            pm.menu.add(0, 1, 2, "Open in another app")
            pm.menu.add(0, 2, 3, "Share")
            pm.menu.add(0, 3, 4, "Delete clip")
        }
        pm.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                4 -> {
                    if (fav) store.remove(this, Collections.FAVOURITES, listOf(c.id)) else store.add(this, Collections.FAVOURITES, listOf(c.id))
                    Toast.makeText(this, if (fav) "Removed from Favourites" else "Added to Favourites", Toast.LENGTH_SHORT).show()
                }
                5 -> CollectionPicker.show(this, listOf(c.id), store) {}
                6 -> saveCurrent(c)
                1 -> startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(c.uri, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                2 -> startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("video/mp4")
                    .putExtra(Intent.EXTRA_STREAM, c.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share clip"))
                3 -> AlertDialog.Builder(this).setTitle("Delete this clip?")
                    .setMessage(Fmt.dateTime(prefs).format(Date(c.startMs)))
                    .setPositiveButton("Delete") { _, _ -> deleteCurrent(c) }
                    .setNegativeButton("Cancel", null).show()
            }
            true
        }
        pm.show()
    }

    /** Remote clip → the phone's own Clips (marked never-upload). Usually instant: it is already cached. */
    private fun saveCurrent(c: Clip) {
        Toast.makeText(this, "Saving to this phone…", Toast.LENGTH_SHORT).show()
        Thread {
            val r = com.motioncam.remote.RemoteRepo.saveToPhone(this, listOf(c), { _, _, _ -> }, { false })
            runOnUiThread {
                val msg = when {
                    r.saved > 0 -> "Saved to Clips on this phone (it will not be uploaded to backup)"
                    r.skipped > 0 -> "This clip is already on this phone"
                    else -> "Could not save: ${r.lastError ?: "unknown error"}"
                }
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun deleteCurrent(c: Clip) {
        ClipDeleter.delete(this, listOf(c)) { deleted ->
            if (!deleted) return@delete
            val i = clips.indexOfFirst { it.id == c.id }
            a.release(); b.release()
            if (i >= 0) clips.removeAt(i)
            if (clips.isEmpty()) { finish(); return@delete }
            strip.clips = clips
            val ni = i.coerceIn(0, clips.size - 1)
            strip.setCurrent(ni, strip.expanded)
            startAt(ni, 0)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (!ClipDeleter.onResult(requestCode, resultCode)) @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
    }

    private fun updatePosition() {
        val i = cur.index
        if (i !in clips.indices) return
        // while a jump is still loading/seeking, keep showing where the user landed (no jitter)
        if (holdMs >= 0 && System.currentTimeMillis() - holdSince > 2500) holdMs = -1
        val pos = if (holdMs >= 0) holdMs.toInt() else curPos()
        strip.setPosition(pos.toLong())
        stamp.text = stampFmt.format(Date(clips[i].startMs + pos))
        val c = clips[i]
        info.text = "${i + 1}/${clips.size} · ${fmtDur(pos.toLong())}/${fmtDur(c.durationMs)}"   // compact: shares the bar
    }

    private fun fmtDur(ms: Long): String { val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60) }
}
