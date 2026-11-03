package com.motioncam

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.util.Size
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.OverScroller
import java.util.concurrent.LinkedBlockingDeque
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Loads clip posters and individual frames in the background, most recent request first. */
class ThumbLoader(private val ctx: Context, private val onLoaded: () -> Unit) {
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 6).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val queue = LinkedBlockingDeque<Pair<String, () -> Bitmap?>>()
    private val queued = HashSet<String>()
    private val failed = HashSet<String>()
    private val main = Handler(Looper.getMainLooper())
    private var retrieverFor = -1L
    private var retriever: MediaMetadataRetriever? = null
    @Volatile private var running = true

    private val worker = Thread({
        while (running) {
            val (key, job) = try { queue.takeLast() } catch (e: InterruptedException) { break }
            val bmp = try { job() } catch (e: Exception) { null }
            synchronized(queued) {
                queued.remove(key)
                if (bmp == null) failed += key
            }
            if (bmp != null) { cache.put(key, bmp); main.post(onLoaded) }
        }
        try { retriever?.release() } catch (_: Exception) {}
    }, "thumbs").also { it.isDaemon = true; it.start() }

    private fun request(key: String, job: () -> Bitmap?): Bitmap? {
        cache.get(key)?.let { return it }
        synchronized(queued) {
            if (key in queued || key in failed) return null
            queued += key
            queue.addLast(Pair(key, job))
            while (queue.size > 48) queue.pollFirst()?.let { queued.remove(it.first) }
        }
        return null
    }

    fun poster(c: Clip): Bitmap? = request("p${c.id}") { ClipThumbs.load(ctx, c) }

    /** A frame near [tMs] (cached per second). */
    fun frame(c: Clip, tMs: Long): Bitmap? {
        val sec = tMs / 1000
        return request("f${c.id}@$sec") {
            if (retrieverFor != c.id) {
                try { retriever?.release() } catch (_: Exception) {}
                retriever = MediaMetadataRetriever().also { it.setDataSource(ctx, c.uri) }
                retrieverFor = c.id
            }
            retriever!!.getScaledFrameAtTime(sec * 1_000_000L + 500_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 240, 240)
        }
    }

    fun close() { running = false; worker.interrupt() }
}

/**
 * Samsung-style clip filmstrip. Clips sit next to each other (no time gaps); the playing clip can be
 * expanded into a strip of its own frames showing progress. The white playhead stays in the middle.
 *  - tap the current clip: expand / collapse;  tap another clip: play it
 *  - drag / fling to scrub; while expanded, scrubbing stops at the clip's end (drag again to continue)
 *  - pinch to make the strip larger or smaller
 */
class FilmstripView(ctx: Context) : View(ctx) {
    interface Listener {
        fun onScrub(index: Int, posMs: Long, final: Boolean)
        fun onTapClip(index: Int)
    }

    var listener: Listener? = null
    var clips: List<Clip> = emptyList(); set(v) { field = v; relayout() }
    var current = 0; private set
    var expanded = true; private set
    private var posMs = 0L
    private var zoom = 1f

    val thumbs = ThumbLoader(ctx) { invalidate() }
    private val d = resources.displayMetrics.density
    private var starts = FloatArray(0)
    private var widths = FloatArray(0)
    private var total = 0f
    private var scroll = 0f            // content x under the playhead
    private var touching = false
    private var flinging = false
    private var scaling = false
    private var lo = 0f
    private var hi = 0f
    private var lastScrubAt = 0L
    private var moved = false
    private val scroller = OverScroller(ctx)

    private val pTile = Paint().apply { color = 0xFF2A2D33.toInt() }
    private val pBmp = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFFFFFFFF.toInt() }
    private val pDim = Paint().apply { color = 0x66000000 }
    private val pHead = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val src = Rect()
    private val dst = RectF()

    private val gap get() = 3 * d
    private fun tileW() = 54 * d * zoom
    private fun pxPerMs() = tileW() / 2500f   // one frame per 2.5 s at normal zoom

    /** Makes [index] the current clip, positioned at [atMs] (where the user landed, not 0:00). */
    fun setCurrent(index: Int, expand: Boolean? = null, atMs: Long = 0) {
        if (index == current && (expand == null || expand == expanded)) return
        current = index
        if (expand != null) expanded = expand
        posMs = atMs
        relayout()
    }

    fun setPosition(ms: Long) {
        posMs = ms
        if (!touching && !flinging && !scaling) { scroll = contentX(current, ms); invalidate() }
    }

    private fun relayout() {
        val n = clips.size
        starts = FloatArray(n)
        widths = FloatArray(n)
        var x = 0f
        for (i in 0 until n) {
            starts[i] = x
            widths[i] = if (expanded && i == current) max(tileW() * 2, clips[i].durationMs * pxPerMs()) else tileW()
            x += widths[i] + gap
        }
        total = max(0f, x - gap)
        if (!touching && !flinging) scroll = contentX(current, posMs)
        invalidate()
    }

    private fun contentX(i: Int, ms: Long): Float {
        if (i !in clips.indices) return 0f
        val dur = max(1L, clips[i].durationMs)
        return starts[i] + widths[i] * (ms.toFloat() / dur).coerceIn(0f, 0.999f)
    }

    /** (clip index, position ms) under content x. */
    private fun hit(x: Float): Pair<Int, Long> {
        if (clips.isEmpty()) return Pair(0, 0)
        var i = 0
        while (i < clips.size - 1 && x >= starts[i] + widths[i] + gap / 2) i++
        val f = ((x - starts[i]) / widths[i]).coerceIn(0f, 0.999f)
        return Pair(i, (f * max(1L, clips[i].durationMs)).toLong())
    }

    // ------------------------------------------------------------------ drawing

    override fun onDraw(c: Canvas) {
        if (clips.isEmpty()) return
        val w = width.toFloat()
        val h = height.toFloat()
        val top = 8 * d
        val bot = h - 8 * d
        val left = scroll - w / 2
        val right = scroll + w / 2
        for (i in clips.indices) {
            val sx = starts[i]
            val ex = sx + widths[i]
            if (ex < left || sx > right) continue
            val x0 = sx - left
            val clip = clips[i]
            if (expanded && i == current) {
                val tw = tileW()
                val n = ceil(widths[i] / tw).toInt()
                c.save()
                c.clipRect(x0, top, x0 + widths[i], bot)
                for (k in 0 until n) {
                    val tx = x0 + k * tw
                    if (tx + tw < 0 || tx > w) continue
                    val tMs = ((k * tw + tw / 2) / widths[i] * clip.durationMs).toLong()
                    drawThumb(c, thumbs.frame(clip, tMs) ?: thumbs.poster(clip), tx, top, tx + tw, bot)
                }
                c.restore()
                pBorder.strokeWidth = 2.5f * d
                c.drawRoundRect(x0, top, x0 + widths[i], bot, 6 * d, 6 * d, pBorder)
            } else {
                drawThumb(c, thumbs.poster(clip), x0, top, x0 + widths[i], bot)
                if (i == current) {
                    pBorder.strokeWidth = 2f * d
                    c.drawRoundRect(x0, top, x0 + widths[i], bot, 4 * d, 4 * d, pBorder)
                } else c.drawRect(x0, top, x0 + widths[i], bot, pDim)
            }
        }
        // playhead
        c.drawRoundRect(w / 2 - 1.5f * d, 2 * d, w / 2 + 1.5f * d, h - 2 * d, 1.5f * d, 1.5f * d, pHead)
    }

    private fun drawThumb(c: Canvas, b: Bitmap?, l: Float, t: Float, r: Float, btm: Float) {
        if (b == null) { c.drawRect(l, t, r, btm, pTile); return }
        // centre-crop
        val dw = r - l
        val dh = btm - t
        val sa = b.width.toFloat() / b.height
        val da = dw / dh
        if (sa > da) {
            val sw = (b.height * da).toInt()
            src.set((b.width - sw) / 2, 0, (b.width + sw) / 2, b.height)
        } else {
            val sh = (b.width / da).toInt()
            src.set(0, (b.height - sh) / 2, b.width, (b.height + sh) / 2)
        }
        dst.set(l, t, r, btm)
        c.drawBitmap(b, src, dst, pBmp)
    }

    // ------------------------------------------------------------------ touch

    private val scaleDetector = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean { scaling = true; return true }
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val (i, p) = hit(scroll)
            zoom = (zoom * detector.scaleFactor).coerceIn(0.6f, 3.5f)
            relayout()
            scroll = contentX(i, p)
            invalidate()
            return true
        }
        override fun onScaleEnd(detector: ScaleGestureDetector) { scaling = false }
    })

    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            scroller.forceFinished(true)
            flinging = false
            moved = false
            setRange()
            return true
        }
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (scaling) return true
            moved = true
            scroll = (scroll + dx).coerceIn(lo, hi)
            scrub(false)
            invalidate()
            return true
        }
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (scaling) return true
            flinging = true
            scroller.fling(scroll.toInt(), 0, -vx.toInt(), 0, lo.toInt(), hi.toInt(), 0, 0)
            postInvalidateOnAnimation()
            return true
        }
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val (i, _) = hit(scroll + (e.x - width / 2f))
            if (i == current) {
                expanded = !expanded
                relayout()
                scroll = contentX(current, posMs)
            } else listener?.onTapClip(i)
            return true
        }
    })

    /** Allowed scrub range for this gesture: within the expanded clip, or one neighbour if already at its edge. */
    private fun setRange() {
        if (!expanded || current !in clips.indices) { lo = 0f; hi = max(0f, total - 1); return }
        val s = starts[current]
        val e = s + widths[current] - 1
        lo = s
        hi = e
        val edge = 12 * d
        if (scroll >= e - edge && current + 1 < clips.size) hi = starts[current + 1] + widths[current + 1] - 1
        if (scroll <= s + edge && current > 0) lo = starts[current - 1]
    }

    private fun scrub(final: Boolean) {
        val (i, p) = hit(scroll)
        val now = System.currentTimeMillis()
        if (final) { listener?.onScrub(i, p, true); return }
        if (i == current && now - lastScrubAt > 80) { lastScrubAt = now; listener?.onScrub(i, p, false) }
    }

    override fun computeScroll() {
        if (flinging) {
            if (scroller.computeScrollOffset()) {
                scroll = scroller.currX.toFloat().coerceIn(lo, hi)
                scrub(false)
                postInvalidateOnAnimation()
            } else {
                flinging = false
                scrub(true)
            }
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        scaleDetector.onTouchEvent(e)
        if (e.actionMasked == MotionEvent.ACTION_DOWN) touching = true
        gestures.onTouchEvent(e)
        if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
            touching = false
            if (moved && !flinging && !scaling) scrub(true)
            if (!moved && !flinging) { scroll = contentX(current, posMs); invalidate() }
        }
        return true
    }

    fun release() = thumbs.close()
}
