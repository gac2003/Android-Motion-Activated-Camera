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
    companion object { const val EXTRA_START_ID = "startId" }

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
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
            val p = Ui.dp(this@PlayerActivity, 20)
            setPadding(p, p, p, p)
            visibility = View.GONE
        }
        val bp = Ui.dp(this, 72)
        video.addView(bigPlay, FrameLayout.LayoutParams(bp, bp, Gravity.CENTER))
        skipL = skipLabel("«  5s").also { video.addView(it, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_VERTICAL or Gravity.START).apply { leftMargin = Ui.dp(this@PlayerActivity, 40) }) }
        skipR = skipLabel("5s  »").also { video.addView(it, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_VERTICAL or Gravity.END).apply { rightMargin = Ui.dp(this@PlayerActivity, 40) }) }

        // top overlay: back, time stamp, menu
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x99000000.toInt(), 0))
            val p = Ui.dp(this@PlayerActivity, 6)
            setPadding(p, p, p, p * 3)
        }
        topBar.addView(iconButton(this, IconDrawable.Kind.BACK) { finish() })
        stamp = Ui.label(this, "", 15f).apply { setShadowLayer(4f, 0f, 0f, Color.BLACK) }
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
                    e.x < w / 3f -> seekBy(-5000)
                    e.x > w * 2f / 3f -> seekBy(5000)
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
        video.setOnTouchListener { _, e ->
            sd.onTouchEvent(e)
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
            setOnClickListener { cycleSpeed() }
        }
        bar.addView(speedBtn)
        muteBtn = iconButton(this, IconDrawable.Kind.VOLUME, 48, 12) { toggleMute() }
        bar.addView(muteBtn)
        info = Ui.label(this, "", 12f, Ui.GREY).apply { gravity = Gravity.END }
        bar.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(bar)
        setContentView(root)

        val startId = intent.getLongExtra(EXTRA_START_ID, -1L)
        Thread {
            val list = ClipStore.list(this).sortedBy { it.startMs }.map { c ->
                if (c.durationMs > 0) c else c.copy(durationMs = probeDuration(c))
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

    override fun onResume() { super.onResume(); ui.post(ticker) }

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
        a.release(); b.release()
        cur = a; nxt = b
        strip.setCurrent(index)
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
            mp.setDataSource(this, clips[index].uri)
            if (muted) mp.setVolume(0f, 0f)
            mp.setOnVideoSizeChangedListener { _, w, h -> slot.vw = w; slot.vh = h; layoutSlot(slot) }
            mp.setOnPreparedListener {
                if (slot.mp !== mp) return@setOnPreparedListener
                slot.prepared = true
                if (slot === cur) {
                    if (offsetMs > 0) mp.seekTo(offsetMs.toLong(), MediaPlayer.SEEK_CLOSEST)
                    if (playing) { mp.start(); applySpeed(mp) }
                    show(cur)
                    load(nxt, index + 1)
                } else link()
            }
            mp.setOnCompletionListener { if (slot === cur) onCurrentCompleted() }
            mp.setOnErrorListener { _, _, _ ->
                if (slot === cur) ui.post { advanceTo(slot.index + 1) } else slot.release()
                true
            }
            mp.prepareAsync()
        } catch (e: Exception) {
            slot.release()
            if (slot === cur) ui.post { advanceTo(index + 1) }
        }
    }

    /** Chains the prepared next clip so it starts the instant the current one ends. */
    private fun link() {
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
            if (playing && !m.isPlaying) m.start()
            if (playing) applySpeed(m)
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
        bigPlay.animate().cancel()
        if (vScale > 1.03f) { bigPlay.visibility = View.GONE; return }   // keep a zoomed picture clean
        if (p) { bigPlay.setImageDrawable(IconDrawable(IconDrawable.Kind.PAUSE)); bigPlay.visibility = View.VISIBLE; bigPlay.alpha = 1f; bigPlay.animate().alpha(0f).setStartDelay(350).setDuration(250).withEndAction { bigPlay.visibility = View.GONE }.start() }
        else { bigPlay.setImageDrawable(IconDrawable(IconDrawable.Kind.PLAY)); bigPlay.alpha = 1f; bigPlay.visibility = View.VISIBLE }
    }

    private fun togglePlay() {
        setPlaying(!playing)
        val m = cur.mp
        if (m == null || !cur.prepared) return
        try {
            if (playing) { m.start(); applySpeed(m) } else m.pause()
        } catch (_: Exception) {}
    }

    private fun curPos(): Int = try { if (cur.prepared) cur.mp?.currentPosition ?: 0 else 0 } catch (_: Exception) { 0 }

    /** YouTube-style double-tap skip; crosses into the previous / next clip when needed. */
    private fun seekBy(delta: Int) {
        flash(if (delta < 0) skipL else skipR)
        val i = cur.index
        if (i !in clips.indices) return
        val dur = clips[i].durationMs.toInt()
        val p = curPos() + delta
        when {
            p >= dur && i + 1 < clips.size -> startAt(i + 1, minOf(p - dur, clips[i + 1].durationMs.toInt() - 500).coerceAtLeast(0))
            p < 0 && i > 0 -> startAt(i - 1, (clips[i - 1].durationMs.toInt() + p).coerceAtLeast(0))
            else -> try { cur.mp?.seekTo(p.coerceIn(0, maxOf(0, dur - 200)).toLong(), MediaPlayer.SEEK_CLOSEST) } catch (_: Exception) {}
        }
    }

    private fun scrubTo(index: Int, posMs: Long, final: Boolean) {
        if (index == cur.index && cur.prepared) {
            try { cur.mp?.seekTo(posMs, if (final) MediaPlayer.SEEK_CLOSEST else MediaPlayer.SEEK_PREVIOUS_SYNC) } catch (_: Exception) {}
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

    private fun prev() { if (curPos() > 3000) startAt(cur.index, 0) else startAt(maxOf(0, cur.index - 1), 0) }
    private fun next() { if (cur.index + 1 < clips.size) startAt(cur.index + 1, 0) }

    private fun cycleSpeed() {
        val speeds = listOf(1f, 2f, 4f, 8f)
        speed = speeds[(speeds.indexOf(speed) + 1) % speeds.size]
        speedBtn.text = "${speed.toInt()}x"
        cur.mp?.let { if (playing && cur.prepared) applySpeed(it) }
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
        pm.menu.add(0, 1, 0, "Open in another app")
        pm.menu.add(0, 2, 1, "Share")
        pm.menu.add(0, 3, 2, "Delete clip")
        pm.setOnMenuItemClickListener { item ->
            when (item.itemId) {
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

    private fun deleteCurrent(c: Clip) {
        val i = clips.indexOfFirst { it.id == c.id }
        a.release(); b.release()
        ClipStore.delete(this, c)
        if (i >= 0) clips.removeAt(i)
        if (clips.isEmpty()) { finish(); return }
        strip.clips = clips
        val ni = i.coerceIn(0, clips.size - 1)
        strip.setCurrent(ni, strip.expanded)
        startAt(ni, 0)
    }

    private fun updatePosition() {
        val i = cur.index
        if (i !in clips.indices) return
        val pos = curPos()
        strip.setPosition(pos.toLong())
        stamp.text = stampFmt.format(Date(clips[i].startMs + pos))
        val c = clips[i]
        info.text = "${i + 1} / ${clips.size}  ·  ${fmtDur(pos.toLong())} / ${fmtDur(c.durationMs)}"
    }

    private fun fmtDur(ms: Long): String { val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60) }
}
