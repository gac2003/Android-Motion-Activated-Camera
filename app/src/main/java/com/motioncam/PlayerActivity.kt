package com.motioncam

import android.app.Activity
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Continuous playback of motion clips in chronological order. Two players alternate: while one plays,
 * the next clip is prepared on the other and chained with setNextMediaPlayer, so events play back
 * seamlessly like one video. The timeline below shows when each clip happened.
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

    private var clips: List<Clip> = emptyList()   // oldest first
    private lateinit var container: FrameLayout
    private lateinit var a: Slot
    private lateinit var b: Slot
    private lateinit var cur: Slot
    private lateinit var nxt: Slot
    private lateinit var timeline: TimelineView
    private lateinit var stamp: TextView
    private lateinit var info: TextView
    private lateinit var playBtn: TextView
    private lateinit var speedBtn: TextView
    private lateinit var zoomBtn: TextView
    private var playing = true
    private var speed = 1f
    private var pendingStart: Pair<Int, Int>? = null
    private val ui = Handler(Looper.getMainLooper())
    private val stampFmt = SimpleDateFormat("EEE d MMM yyyy  HH:mm:ss", Locale.getDefault())
    private val zooms = listOf(15 * 60_000L to "15m", 3_600_000L to "1h", 6 * 3_600_000L to "6h", 24 * 3_600_000L to "24h")
    private var zoomIdx = 1

    private val ticker = object : Runnable {
        override fun run() {
            updatePosition()
            ui.postDelayed(this, 200)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }

        container = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        a = Slot(TextureView(this))
        b = Slot(TextureView(this))
        for (s in listOf(a, b)) {
            container.addView(s.tv, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
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
        stamp = Ui.label(this, "", 15f).apply { setShadowLayer(4f, 0f, 0f, Color.BLACK); setPadding(24, 16, 24, 16) }
        container.addView(stamp, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START))
        container.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> layoutSlot(a); layoutSlot(b) }
        container.setOnClickListener { togglePlay() }
        root.addView(container, LinearLayout.LayoutParams(-1, 0, 1f))

        timeline = TimelineView(this).apply { onSeek = { t -> seekToTime(t) }; windowMs = zooms[zoomIdx].first }
        root.addView(timeline, LinearLayout.LayoutParams(-1, Ui.dp(this, 64)))

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Ui.PANEL)
            val p = Ui.dp(this@PlayerActivity, 8)
            setPadding(p, p, p, p)
        }
        fun btn(t: String, click: () -> Unit) = Ui.chip(this, t).apply { setOnClickListener { click() } }.also {
            bar.addView(it, LinearLayout.LayoutParams(-2, -2).apply { setMargins(Ui.dp(this@PlayerActivity, 3), 0, Ui.dp(this@PlayerActivity, 3), 0) })
        }
        btn("⏮") { prev() }
        playBtn = btn("⏸") { togglePlay() }
        btn("⏭") { next() }
        speedBtn = btn("1x") { cycleSpeed() }
        zoomBtn = btn(zooms[zoomIdx].second) { cycleZoom() }
        info = Ui.label(this, "", 12f, Ui.GREY).apply { gravity = Gravity.END }
        bar.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(bar)
        setContentView(root)

        val startId = intent.getLongExtra(EXTRA_START_ID, -1L)
        Thread {
            val list = ClipStore.list(this).sortedBy { it.startMs }.map { c ->
                if (c.durationMs > 0) c else c.copy(durationMs = probeDuration(c))
            }
            runOnUiThread {
                clips = list
                timeline.clips = list
                if (list.isEmpty()) { Toast.makeText(this, "No clips", Toast.LENGTH_SHORT).show(); finish(); return@runOnUiThread }
                val idx = list.indexOfFirst { it.id == startId }.let { if (it < 0) 0 else it }
                pendingStart = Pair(idx, 0)
                maybeBegin()
            }
        }.start()
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
        ui.post(ticker)
    }

    override fun onPause() {
        ui.removeCallbacks(ticker)
        if (playing) togglePlay()
        super.onPause()
    }

    override fun onDestroy() {
        a.release(); b.release()
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
            old.release()
            load(nxt, cur.index + 1)
        } else if (old.index + 1 < clips.size) {
            advanceTo(old.index + 1)
        } else {
            playing = false
            playBtn.text = "▶"
        }
    }

    private fun advanceTo(index: Int) { if (index in clips.indices) startAt(index, 0) else { playing = false; playBtn.text = "▶" } }

    private fun show(s: Slot) {
        s.tv.alpha = 1f
        (if (s === a) b else a).tv.alpha = 0f
    }

    private fun layoutSlot(s: Slot) {
        val cw = container.width
        val ch = container.height
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

    private fun togglePlay() {
        val m = cur.mp
        playing = !playing
        playBtn.text = if (playing) "⏸" else "▶"
        if (m == null || !cur.prepared) return
        try {
            if (playing) { m.start(); applySpeed(m) } else m.pause()
        } catch (_: Exception) {}
    }

    private fun prev() {
        val m = cur.mp
        val pos = try { m?.currentPosition ?: 0 } catch (_: Exception) { 0 }
        if (pos > 3000) startAt(cur.index, 0) else startAt(maxOf(0, cur.index - 1), 0)
    }

    private fun next() { if (cur.index + 1 < clips.size) startAt(cur.index + 1, 0) }

    private fun cycleSpeed() {
        val speeds = listOf(1f, 2f, 4f, 8f)
        speed = speeds[(speeds.indexOf(speed) + 1) % speeds.size]
        speedBtn.text = "${speed.toInt()}x"
        cur.mp?.let { if (playing && cur.prepared) applySpeed(it) }
    }

    private fun cycleZoom() {
        zoomIdx = (zoomIdx + 1) % zooms.size
        timeline.windowMs = zooms[zoomIdx].first
        zoomBtn.text = zooms[zoomIdx].second
    }

    private fun seekToTime(t: Long) {
        if (clips.isEmpty()) return
        val inside = clips.indexOfFirst { t >= it.startMs && t < it.endMs }
        if (inside >= 0) { startAt(inside, (t - clips[inside].startMs).toInt()); return }
        val after = clips.indexOfFirst { it.startMs > t }
        startAt(if (after >= 0) after else clips.size - 1, 0)
    }

    private fun updatePosition() {
        val i = cur.index
        if (i !in clips.indices) return
        val pos = try { if (cur.prepared) cur.mp?.currentPosition ?: 0 else 0 } catch (_: Exception) { 0 }
        val t = clips[i].startMs + pos
        stamp.text = stampFmt.format(Date(t))
        timeline.playhead = t
        val c = clips[i]
        info.text = "clip ${i + 1}/${clips.size} · ${pos / 1000}/${c.durationMs / 1000}s"
    }
}
