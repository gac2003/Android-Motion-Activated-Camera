package com.motioncam.ai

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.motioncam.Diagnostics
import com.motioncam.Frame
import com.motioncam.Health
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * On-device AI for the camera phone (person-aware focus). Off unless turned on in Settings.
 *
 * Runs only while motion is recording a clip:
 *  - the camera's analysis thread hands over a copy of the newest small frame, at most a few times a second and only
 *    when the AI is free (one frame at a time, never a queue; a busy AI simply misses frames);
 *  - on its own thread ("ai"): a person detector, then body points for the nearest person, then [FocusPilot] decides
 *    where the lens goes;
 *  - the models are loaded when the first clip starts and freed (with the thread) 3 minutes after the last clip ended.
 *    With no motion there is no model in memory, no thread and no timer.
 *
 * First use on a phone: each way of running the detector that works there (AI chip, graphics chip, processor) is
 * timed once, the fastest is kept, and the frame rate and detector size follow from it. A phone that is too slow keeps
 * the feature off and says so. A way that crashed MotionCam while being tried is never tried again.
 */
object AiEngine {
    /** What the camera engine passes with each frame. */
    class Info(val rotation: Int, val fu: Float, val fv: Float, val maxDiopter: Float, val takenAt: Long)

    private const val UNLOAD_AFTER_MS = 3 * 60_000L
    private const val BENCH_VERSION = 1
    private const val PERSON = 0
    private const val MIN_SCORE = 0.45f

    private var thread: HandlerThread? = null
    @Volatile private var handler: Handler? = null
    private val busy = AtomicBoolean(false)
    private var frame: Frame? = null
    @Volatile private var lastTake = 0L
    @Volatile private var inClip = false
    /** Called (AI thread) when the focus target changed: the engine re-sends its camera request. */
    @Volatile var onFocusChanged: (() -> Unit)? = null

    // ---- models (AI thread only)
    private var small: Detector? = null
    private var large: Detector? = null
    private var pose: Pose? = null

    // ---- state for Settings / Diagnostics
    @Volatile var loaded = false; private set
    @Volatile var backend = ""; private set
    @Volatile var disabledWhy: String? = null; private set
    @Volatile private var intervalMs = 250L
    @Volatile private var useLarge = false
    @Volatile private var loadMs = 0L
    @Volatile private var frames = 0L
    @Volatile private var persons = 0L
    @Volatile private var msDetAvg = 0f
    @Volatile private var msPoseAvg = 0f
    @Volatile private var skippedBusy = 0L

    private fun sp(ctx: Context) = ctx.getSharedPreferences("ai", Context.MODE_PRIVATE)

    // ================================================================== from the camera engine

    /** A clip started (engine thread): the AI thread and models come up if they aren't. */
    @Synchronized
    fun clipStart(ctx: Context) {
        val app = ctx.applicationContext
        inClip = true
        var h = handler
        if (h == null) {
            val t = HandlerThread("ai", android.os.Process.THREAD_PRIORITY_BACKGROUND + 1).also { it.start() }
            thread = t
            h = Handler(t.looper)
            handler = h
        }
        h.removeCallbacks(unloadTask)   // only the pending unload: a frame being handed over must still run
        h.post {
            try { FocusPilot.clipStart(); ensureLoaded(app) } catch (t: Throwable) { disabledWhy = "couldn't start (${t.javaClass.simpleName})"; Health.log(app, "AI: couldn't start: $t") }
        }
    }

    /** The clip ended (engine thread): focus back to rest; models freed after [UNLOAD_AFTER_MS] without another clip. */
    @Synchronized
    fun clipEnd(ctx: Context) {
        val app = ctx.applicationContext
        inClip = false
        val h = handler ?: return
        h.post { try { FocusPilot.clipEnd(app) } catch (_: Throwable) {}; onFocusChanged?.invoke() }
        h.removeCallbacks(unloadTask)
        h.postDelayed(unloadTask, UNLOAD_AFTER_MS)   // once; cancelled by the next clip
    }

    /** The engine stopped (camera closed, feature turned off): everything is freed now. */
    @Synchronized
    fun shutdown() {
        inClip = false
        val h = handler ?: return
        h.removeCallbacks(unloadTask)
        h.post(unloadTask)
    }

    /** Cheap check on the analysis thread (every camera frame during a clip): is a frame wanted right now? */
    fun due(now: Long): Boolean {
        if (handler == null || !inClip || !loaded || disabledWhy != null || busy.get()) return false
        val lvl = AiGovernor.level
        if (lvl == AiGovernor.OFF) return false
        return now - lastTake >= (if (lvl == AiGovernor.REDUCED) intervalMs * 2 else intervalMs)
    }

    /**
     * The analysis thread offers the current frame: taken only when a clip is recording, the AI is free, the heat
     * rules allow it and the next frame is due. [copy] copies the camera image into the buffer it is given (or a new one).
     */
    fun offer(now: Long, info: Info, copy: (Frame?) -> Frame) {
        val h = handler ?: return
        if (!inClip || !loaded || disabledWhy != null) return
        val lvl = AiGovernor.level
        if (lvl == AiGovernor.OFF) return
        val every = if (lvl == AiGovernor.REDUCED) intervalMs * 2 else intervalMs
        if (now - lastTake < every) return
        if (!busy.compareAndSet(false, true)) { skippedBusy++; return }
        lastTake = now
        val f = try { copy(frame) } catch (_: Throwable) { busy.set(false); return }
        frame = f
        val posted = h.post {
            try { process(f, info) } catch (_: Throwable) {} finally { busy.set(false) }
        }
        if (!posted) busy.set(false)
    }

    // ================================================================== AI thread

    private fun process(f: Frame, info: Info) {
        val det = (if (useLarge && AiGovernor.level == AiGovernor.FULL) large else null) ?: small ?: return
        if (!inClip) return
        val t0 = SystemClock.elapsedRealtime()
        val boxes = det.detect(f, info.rotation)
        val t1 = SystemClock.elapsedRealtime()
        msDetAvg = if (frames == 0L) (t1 - t0).toFloat() else msDetAvg * 0.9f + (t1 - t0) * 0.1f
        frames++
        // the nearest person: the tallest box
        var best: FloatArray? = null
        for (b in boxes) if (best == null || b[3] - b[1] > best[3] - best[1]) best = b
        if (best == null) {
            FocusPilot.noPerson(t1)
            if (FocusPilot.target.isNaN()) onFocusChanged?.invoke()
            return
        }
        persons++
        val p = pose
        var ok = false
        if (p != null) {
            ok = p.run(f, info.rotation, best)
            val t2 = SystemClock.elapsedRealtime()
            msPoseAvg = if (msPoseAvg == 0f) (t2 - t1).toFloat() else msPoseAvg * 0.9f + (t2 - t1) * 0.1f
        }
        val (est, how) = FocusPilot.estimate(if (ok) p!!.u else null, if (ok) p!!.v else null, if (ok) p!!.vis else null, best[1], best[3], info.fu, info.fv)
        if (est.isNaN()) return
        // sharpness inside the person: the chest when the body points are known, else the middle of the box
        var u0 = best[0] + (best[2] - best[0]) * 0.25f; var u1 = best[2] - (best[2] - best[0]) * 0.25f
        var v0 = best[1] + (best[3] - best[1]) * 0.15f; var v1 = best[1] + (best[3] - best[1]) * 0.6f
        if (ok && p != null && p.vis[11] > 0.6f && p.vis[12] > 0.6f) {
            val sl = minOf(p.u[11], p.u[12]); val sr = maxOf(p.u[11], p.u[12])
            val w = (sr - sl).coerceAtLeast(0.03f)
            val top = (p.v[11] + p.v[12]) / 2
            u0 = sl; u1 = sr; v0 = top - w * 0.6f; v1 = top + w * 0.9f   // face to chest
        }
        val sharp = AiImage.sharpness(f, info.rotation, u0, v0, u1, v1)
        if (FocusPilot.person(est, how, sharp, info.maxDiopter, info.takenAt, SystemClock.elapsedRealtime())) onFocusChanged?.invoke()
    }

    private fun ensureLoaded(ctx: Context) {
        if (loaded || disabledWhy != null) return
        val s = sp(ctx)
        // a way of running the model that took MotionCam down while it was being tried: never again
        val trying = s.getString("trying", "") ?: ""
        val bad = HashSet(s.getStringSet("bad", emptySet()) ?: emptySet())
        if (trying.isNotEmpty()) {
            bad += trying
            s.edit().putStringSet("bad", bad).putString("trying", "").commit()
            Health.log(ctx, "AI: '$trying' stopped MotionCam last time; it won't be used on this phone")
        }
        val key = "$BENCH_VERSION|${Build.FINGERPRINT}"
        if (s.getString("benchKey", "") != key || (s.getString("backend", "") ?: "") in bad || s.getString("backend", "").isNullOrEmpty()) benchmark(ctx, bad, key)
        val b = s.getString("backend", "") ?: ""
        val msSmall = s.getFloat("msSmall", 9999f); val msLarge = s.getFloat("msLarge", 9999f); val msPose = s.getFloat("msPose", 9999f)
        if (b.isEmpty() || msSmall + msPose > 400f) {
            disabledWhy = if (b.isEmpty()) "the detector couldn't be run on this phone" else "this phone is too slow for it (%.0f ms a frame)".format(msSmall + msPose)
            Health.log(ctx, "AI: off on this phone: $disabledWhy")
            return
        }
        // the larger detector where the phone is fast enough; fewer frames a second where it's slow
        useLarge = msLarge + msPose <= 110f
        val per = (if (useLarge) msLarge else msSmall) + msPose
        intervalMs = if (per <= 150f) 250L else if (per <= 300f) 500L else 1000L
        val t0 = SystemClock.elapsedRealtime()
        s.edit().putString("trying", b).commit()
        try {
            small = Detector(ctx, if (b == "gpu") "det_s_f.tflite" else "det_s_q.tflite", b)
            if (useLarge) large = Detector(ctx, if (b == "gpu") "det_l_f.tflite" else "det_l_q.tflite", b)
            pose = Pose(ctx)
            small!!.warm(); large?.warm(); pose!!.warm()
        } catch (t: Throwable) {
            closeModels()
            // it worked in the timing run but not now: time everything again next clip
            s.edit().putString("trying", "").putString("benchKey", "").commit()
            Health.log(ctx, "AI: couldn't load the models ($t)")
            return
        }
        s.edit().putString("trying", "").commit()
        backend = b
        loadMs = SystemClock.elapsedRealtime() - t0
        loaded = true
        Diagnostics.count("AI models loaded")
    }

    /** Times the small detector on each way that works here, then the large one and the body points on the winner. */
    private fun benchmark(ctx: Context, bad: Set<String>, key: String) {
        val s = sp(ctx)
        var best = ""; var bestMs = Float.MAX_VALUE
        val notes = ArrayList<String>()
        for (b in listOf("cpu", "gpu", "nnapi")) {
            if (b in bad) { notes += "$b: not used (it stopped MotionCam before)"; continue }
            s.edit().putString("trying", b).commit()
            val ms = try {
                val d = Detector(ctx, if (b == "gpu") "det_s_f.tflite" else "det_s_q.tflite", b)
                try { d.time() } finally { d.close() }
            } catch (t: Throwable) { notes += "$b: doesn't work here"; -1f }
            s.edit().putString("trying", "").commit()
            if (ms > 0) { notes += "$b: %.0f ms".format(ms); if (ms < bestMs) { bestMs = ms; best = b } }
        }
        var msLarge = 9999f; var msPose = 9999f
        if (best.isNotEmpty()) {
            s.edit().putString("trying", best).commit()
            try {
                val d = Detector(ctx, if (best == "gpu") "det_l_f.tflite" else "det_l_q.tflite", best)
                try { msLarge = d.time() } finally { d.close() }
            } catch (_: Throwable) {}
            try {
                val p = Pose(ctx)
                try { msPose = p.time() } finally { p.close() }
            } catch (_: Throwable) {}
            s.edit().putString("trying", "").commit()
        }
        s.edit().putString("benchKey", key).putString("backend", best).putFloat("msSmall", bestMs).putFloat("msLarge", msLarge)
            .putFloat("msPose", msPose).putString("benchNotes", notes.joinToString(" · ")).commit()
        Health.log(ctx, "AI speed test: " + notes.joinToString(", ") + (if (best.isNotEmpty()) " → $best; large detector %.0f ms, body points %.0f ms".format(msLarge, msPose) else " → nothing works"))
    }

    private fun closeModels() {
        try { small?.close() } catch (_: Throwable) {}
        try { large?.close() } catch (_: Throwable) {}
        try { pose?.close() } catch (_: Throwable) {}
        small = null; large = null; pose = null
    }

    private val unloadTask = Runnable { unload() }

    /** Frees the models and ends the AI thread (3 minutes after the last clip, or when the camera closes). */
    private fun unload() {
        if (inClip) return
        closeModels()
        if (loaded) Diagnostics.count("AI models freed")
        loaded = false
        frame = null
        synchronized(this) {
            if (inClip) return
            thread?.quitSafely()
            thread = null
            handler = null
        }
    }

    // ================================================================== Settings / Diagnostics

    /** Forget the speed test (Settings › "Test this phone's speed again"): it runs again with the next clip. */
    fun retest(ctx: Context) {
        sp(ctx).edit().remove("benchKey").remove("bad").apply()
        disabledWhy = null
    }

    fun backendText(b: String) = when (b) { "cpu" -> "processor"; "gpu" -> "graphics chip"; "nnapi" -> "AI chip"; else -> "—" }

    /** One paragraph for Settings: what the feature is doing on this phone. */
    fun settingsNote(ctx: Context): String {
        val s = sp(ctx)
        disabledWhy?.let { return "Off on this phone: $it." }
        val b = s.getString("backend", "") ?: ""
        val speed = if (b.isEmpty()) "This phone's speed is tested with the first clip after turning this on."
            else "Runs on this phone's ${backendText(b)}: about %.0f ms a frame.".format(
                (if (s.getFloat("msLarge", 9999f) + s.getFloat("msPose", 9999f) <= 110f) s.getFloat("msLarge", 0f) else s.getFloat("msSmall", 0f)) + s.getFloat("msPose", 0f))
        val u = FocusPilot.usual(ctx)
        val learned = if (u.isNaN()) "Learning the usual distance: ${FocusPilot.clipsLearned(ctx)} of ${FocusPilot.USUAL_MIN} clips with a person. Until then the lens rests at your manual focus distance."
            else "Usual distance: " + (if (u < 0.1f) "far (∞)" else "%.1f m".format(1 / u)) + " (from ${FocusPilot.clipsLearned(ctx)} clips)."
        return "$speed $learned"
    }

    /** Lines for Diagnostics. */
    fun report(ctx: Context, line: (String, Any?) -> Unit) {
        val s = sp(ctx)
        line("Models in memory", if (loaded) "yes (loaded in $loadMs ms)" else "no")
        line("Runs on", backendText(s.getString("backend", "") ?: "") + (disabledWhy?.let { " · off: $it" } ?: ""))
        line("Speed test", s.getString("benchNotes", "")?.ifEmpty { "not run yet" })
        line("Detector / body points", if (frames == 0L) "no frames yet" else "%.0f ms / %.0f ms a frame (%s detector)".format(msDetAvg, msPoseAvg, if (useLarge) "large" else "small"))
        line("Frames looked at", "$frames ($persons with a person, $skippedBusy missed while busy)")
        line("Frames a second (target)", "%.1f".format(1000f / intervalMs))
        line("Heat", AiGovernor.levelText() + " · half for ${AiGovernor.reducedMs / 60_000} min, off for ${AiGovernor.offMs / 60_000} min")
        val u = FocusPilot.usual(ctx)
        line("Usual distance", (if (u.isNaN()) "not learned yet" else if (u < 0.1f) "far (∞)" else "%.1f m".format(1 / u)) + " · ${FocusPilot.clipsLearned(ctx)} clips, ${FocusPilot.correctionsLearned(ctx)} corrections")
        val e = FocusPilot.lastEst
        line("Last person", if (e.isNaN()) "none yet" else (if (e < 0.05f) "far" else "%.1f m".format(1 / e)) + " (by ${FocusPilot.lastHow})")
        line("Focus moves / sharpness tests", "${FocusPilot.moves} / ${FocusPilot.searches}")
    }

    // ================================================================== the models

    private fun asset(ctx: Context, name: String): ByteBuffer {
        val bytes = ctx.assets.open("ai/$name").use { it.readBytes() }
        return ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply { put(bytes); rewind() }
    }

    /** One loaded model, on the processor, the graphics chip or the AI chip. */
    private class Net(ctx: Context, name: String, backend: String, threads: Int) {
        private var gpu: org.tensorflow.lite.gpu.GpuDelegate? = null
        private var nn: org.tensorflow.lite.nnapi.NnApiDelegate? = null
        val tfl: Interpreter

        init {
            val o = Interpreter.Options()
            when (backend) {
                "gpu" -> { val d = org.tensorflow.lite.gpu.GpuDelegate(); gpu = d; o.addDelegate(d) }
                "nnapi" -> { val d = org.tensorflow.lite.nnapi.NnApiDelegate(); nn = d; o.addDelegate(d) }
                else -> o.setNumThreads(threads)
            }
            tfl = try { Interpreter(asset(ctx, name), o) } catch (t: Throwable) { closeDelegates(); throw t }
        }

        private fun closeDelegates() {
            try { gpu?.close() } catch (_: Throwable) {}
            try { nn?.close() } catch (_: Throwable) {}
            gpu = null; nn = null
        }

        fun close() {
            try { tfl.close() } catch (_: Throwable) {}
            closeDelegates()
        }
    }

    private fun direct(bytes: Int): ByteBuffer = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())

    /** The person detector: a square picture in, a score and a box for each of its fixed "anchor" positions out. */
    internal class Detector(ctx: Context, name: String, backend: String, threads: Int = 4) {
        private val net = Net(ctx, name, backend, threads)
        private val n: Int = net.tfl.getInputTensor(0).shape()[1]
        private val quant = net.tfl.getInputTensor(0).dataType() == DataType.UINT8
        private val rgb = ByteArray(n * n * 3)
        private val fl = if (quant) FloatArray(0) else FloatArray(n * n * 3)
        private val input = direct(if (quant) n * n * 3 else n * n * 3 * 4)
        private val scoreIdx: Int
        private val boxIdx: Int
        private val count: Int
        private val classes: Int
        private val scoreBuf: ByteBuffer
        private val boxBuf: ByteBuffer
        private val scores: FloatBuffer
        private val boxes: FloatBuffer
        private val anchors: FloatArray
        private val outputs = HashMap<Int, Any>()

        init {
            var si = -1; var bi = -1
            for (i in 0 until net.tfl.outputTensorCount) {
                val sh = net.tfl.getOutputTensor(i).shape()
                if (sh.size == 3 && sh[2] == 4) bi = i else if (sh.size == 3) si = i
            }
            if (si < 0 || bi < 0) throw IllegalStateException("unexpected detector outputs")
            scoreIdx = si; boxIdx = bi
            val ss = net.tfl.getOutputTensor(si).shape()
            count = ss[1]; classes = ss[2]
            scoreBuf = direct(count * classes * 4); boxBuf = direct(count * 4 * 4)
            scores = scoreBuf.asFloatBuffer(); boxes = boxBuf.asFloatBuffer()
            outputs[scoreIdx] = scoreBuf; outputs[boxIdx] = boxBuf
            // the anchors, in the model's order: levels 3..7, rows, columns, 3 sizes, 3 shapes (centre y, x, height, width)
            val a = ArrayList<Float>(count * 4)
            for (lvl in 3..7) {
                val stride = 1 shl lvl
                val fs = ceil(n / stride.toDouble()).toInt()
                for (y in 0 until fs) for (x in 0 until fs) for (o in 0..2) for (ar in floatArrayOf(1f, 2f, 0.5f)) {
                    val base = 3f * stride * 2.0.pow(o / 3.0).toFloat()
                    val r = sqrt(ar)
                    a += (y + 0.5f) * stride; a += (x + 0.5f) * stride; a += base / r; a += base * r
                }
            }
            if (a.size != count * 4) throw IllegalStateException("unexpected detector size")
            anchors = a.toFloatArray()
        }

        private fun infer() {
            input.rewind()
            if (quant) input.put(rgb) else {
                for (i in rgb.indices) fl[i] = ((rgb[i].toInt() and 0xFF) - 127f) / 128f
                input.asFloatBuffer().put(fl)
            }
            input.rewind(); scoreBuf.rewind(); boxBuf.rewind()
            net.tfl.runForMultipleInputsOutputs(arrayOf<Any>(input), outputs)
        }

        fun warm() { infer() }

        /** Average time of a run (ms), after two warm-up runs. */
        fun time(): Float {
            infer(); infer()
            val t0 = SystemClock.elapsedRealtimeNanos()
            for (i in 0 until 5) infer()
            return (SystemClock.elapsedRealtimeNanos() - t0) / 5e6f
        }

        /** People in the upright picture: [left, top, right, bottom, score], 0..1, best first, overlaps removed. */
        fun detect(f: Frame, rotation: Int): List<FloatArray> {
            AiImage.sample(f, rotation, 0f, 0f, 1f, 1f, n, rgb)
            infer()
            val found = ArrayList<FloatArray>()
            for (i in 0 until count) {
                val s = scores.get(i * classes + PERSON)
                if (s < MIN_SCORE) continue
                val ay = anchors[i * 4]; val ax = anchors[i * 4 + 1]; val ah = anchors[i * 4 + 2]; val aw = anchors[i * 4 + 3]
                val yc = boxes.get(i * 4) * ah + ay; val xc = boxes.get(i * 4 + 1) * aw + ax
                val h = exp(boxes.get(i * 4 + 2)) * ah; val w = exp(boxes.get(i * 4 + 3)) * aw
                found.add(floatArrayOf(((xc - w / 2) / n).coerceIn(0f, 1f), ((yc - h / 2) / n).coerceIn(0f, 1f),
                    ((xc + w / 2) / n).coerceIn(0f, 1f), ((yc + h / 2) / n).coerceIn(0f, 1f), s))
            }
            found.sortByDescending { it[4] }
            val keep = ArrayList<FloatArray>()
            for (b in found) {
                var dup = false
                for (k in keep) {
                    val ix = (minOf(b[2], k[2]) - maxOf(b[0], k[0])).coerceAtLeast(0f)
                    val iy = (minOf(b[3], k[3]) - maxOf(b[1], k[1])).coerceAtLeast(0f)
                    val inter = ix * iy
                    val union = (b[2] - b[0]) * (b[3] - b[1]) + (k[2] - k[0]) * (k[3] - k[1]) - inter
                    if (union > 0f && inter / union > 0.5f) { dup = true; break }
                }
                if (!dup) keep.add(b)
                if (keep.size >= 5) break
            }
            return keep
        }

        /**
         * Things of the given [kinds] in a picture (already upright): [left, top, right, bottom, score, kind], 0..1,
         * best first, overlaps of the same kind removed. For describing saved clips.
         */
        fun detectBitmap(bmp: android.graphics.Bitmap, kinds: IntArray): List<FloatArray> {
            val scaled = if (bmp.width == n && bmp.height == n) bmp else android.graphics.Bitmap.createScaledBitmap(bmp, n, n, true)
            val px = IntArray(n * n)
            scaled.getPixels(px, 0, n, 0, 0, n, n)
            if (scaled !== bmp) scaled.recycle()
            for (i in px.indices) { val c = px[i]; rgb[i * 3] = (c shr 16).toByte(); rgb[i * 3 + 1] = (c shr 8).toByte(); rgb[i * 3 + 2] = c.toByte() }
            infer()
            val found = ArrayList<FloatArray>()
            for (i in 0 until count) for (kind in kinds) {
                val s = scores.get(i * classes + kind)
                if (s < MIN_SCORE) continue
                val ay = anchors[i * 4]; val ax = anchors[i * 4 + 1]; val ah = anchors[i * 4 + 2]; val aw = anchors[i * 4 + 3]
                val yc = boxes.get(i * 4) * ah + ay; val xc = boxes.get(i * 4 + 1) * aw + ax
                val h = exp(boxes.get(i * 4 + 2)) * ah; val w = exp(boxes.get(i * 4 + 3)) * aw
                found.add(floatArrayOf(((xc - w / 2) / n).coerceIn(0f, 1f), ((yc - h / 2) / n).coerceIn(0f, 1f),
                    ((xc + w / 2) / n).coerceIn(0f, 1f), ((yc + h / 2) / n).coerceIn(0f, 1f), s, kind.toFloat()))
            }
            found.sortByDescending { it[4] }
            val keep = ArrayList<FloatArray>()
            for (b in found) {
                var dup = false
                for (k in keep) {
                    if (k[5] != b[5]) continue
                    val ix = (minOf(b[2], k[2]) - maxOf(b[0], k[0])).coerceAtLeast(0f)
                    val iy = (minOf(b[3], k[3]) - maxOf(b[1], k[1])).coerceAtLeast(0f)
                    val inter = ix * iy
                    val union = (b[2] - b[0]) * (b[3] - b[1]) + (k[2] - k[0]) * (k[3] - k[1]) - inter
                    if (union > 0f && inter / union > 0.5f) { dup = true; break }
                }
                if (!dup) keep.add(b)
                if (keep.size >= 20) break
            }
            return keep
        }

        fun close() = net.close()
    }

    /** Body points of one person: 33 points (nose, eyes, ears, shoulders, hips, knees, ankles…) from a square cut-out. */
    private class Pose(ctx: Context) {
        private val net = Net(ctx, "pose.tflite", "cpu", 2)
        private val n: Int = net.tfl.getInputTensor(0).shape()[1]
        private val rgb = ByteArray(n * n * 3)
        private val fl = FloatArray(n * n * 3)
        private val input = direct(n * n * 3 * 4)
        private val lmBuf: ByteBuffer
        private val presBuf = direct(4)
        private val outputs = HashMap<Int, Any>()
        private val points: Int
        val u = FloatArray(33); val v = FloatArray(33); val vis = FloatArray(33)

        init {
            var li = -1; var pi = -1; var len = 0
            for (i in 0 until net.tfl.outputTensorCount) {
                val sh = net.tfl.getOutputTensor(i).shape()
                if (sh.size == 2 && sh[1] == 195) { li = i; len = sh[1] } else if (sh.size == 2 && sh[1] == 1) pi = i
            }
            if (li < 0 || pi < 0) throw IllegalStateException("unexpected body-point outputs")
            points = len / 5
            lmBuf = direct(len * 4)
            outputs[li] = lmBuf; outputs[pi] = presBuf
        }

        private fun infer() {
            for (i in rgb.indices) fl[i] = (rgb[i].toInt() and 0xFF) / 255f
            input.rewind(); input.asFloatBuffer().put(fl); input.rewind()
            lmBuf.rewind(); presBuf.rewind()
            net.tfl.runForMultipleInputsOutputs(arrayOf<Any>(input), outputs)
        }

        fun warm() { infer() }

        fun time(): Float {
            infer(); infer()
            val t0 = SystemClock.elapsedRealtimeNanos()
            for (i in 0 until 5) infer()
            return (SystemClock.elapsedRealtimeNanos() - t0) / 5e6f
        }

        /** Fills [u], [v], [vis] for the person in [box] (upright picture, 0..1). False when no body was found there. */
        fun run(f: Frame, rotation: Int, box: FloatArray): Boolean {
            // a square around the person, a quarter larger than the box (in real pixels, so it isn't stretched)
            val turned = rotation == 90 || rotation == 270
            val pw = (if (turned) f.h else f.w).toFloat(); val ph = (if (turned) f.w else f.h).toFloat()
            val cx = (box[0] + box[2]) / 2; val cy = (box[1] + box[3]) / 2
            val side = maxOf((box[2] - box[0]) * pw, (box[3] - box[1]) * ph) * 1.25f
            val u0 = cx - side / 2 / pw; val u1 = cx + side / 2 / pw
            val v0 = cy - side / 2 / ph; val v1 = cy + side / 2 / ph
            AiImage.sample(f, rotation, u0, v0, u1, v1, n, rgb)
            infer()
            lmBuf.rewind(); presBuf.rewind()
            val lm = lmBuf.asFloatBuffer()
            if (presBuf.asFloatBuffer().get(0) < 0.5f) return false
            for (i in 0 until 33) {
                u[i] = u0 + lm.get(i * 5) / n * (u1 - u0)
                v[i] = v0 + lm.get(i * 5 + 1) / n * (v1 - v0)
                vis[i] = 1f / (1f + exp(-lm.get(i * 5 + 3)))
            }
            return true
        }

        fun close() = net.close()
    }
}

/** Picture helpers for the AI: cut-outs of the camera's small analysis frame, turned upright. */
object AiImage {
    /**
     * Fills [out] (n × n × 3 bytes, RGB) with the region [u0, v0]..[u1, v1] of the upright picture (0..1; parts
     * outside the picture are black). [rotation] is how far the camera's frame must be turned clockwise to be upright.
     */
    fun sample(f: Frame, rotation: Int, u0: Float, v0: Float, u1: Float, v1: Float, n: Int, out: ByteArray) {
        val fu = f.u; val fv = f.v
        val w = f.w; val h = f.h
        var o = 0
        for (oy in 0 until n) {
            val v = v0 + (oy + 0.5f) / n * (v1 - v0)
            for (ox in 0 until n) {
                val u = u0 + (ox + 0.5f) / n * (u1 - u0)
                // upright (u, v) → the camera frame's own (x, y)
                val sx: Float; val sy: Float
                when (rotation) {
                    90 -> { sx = v; sy = 1f - u }
                    180 -> { sx = 1f - u; sy = 1f - v }
                    270 -> { sx = 1f - v; sy = u }
                    else -> { sx = u; sy = v }
                }
                val px = (sx * w).toInt(); val py = (sy * h).toInt()
                if (sx < 0f || sy < 0f || px >= w || py >= h || fu == null || fv == null) {
                    out[o] = 0; out[o + 1] = 0; out[o + 2] = 0
                } else {
                    val yy = f.y[py * f.yRow + px].toInt() and 0xFF
                    val ci = (py shr 1) * f.uvRow + (px shr 1) * f.uvPix
                    val uu = (fu[ci].toInt() and 0xFF) - 128
                    val vv = (fv[ci].toInt() and 0xFF) - 128
                    var r = yy + ((1436 * vv) shr 10)
                    var g = yy - ((352 * uu + 731 * vv) shr 10)
                    var b = yy + ((1815 * uu) shr 10)
                    if (r < 0) r = 0 else if (r > 255) r = 255
                    if (g < 0) g = 0 else if (g > 255) g = 255
                    if (b < 0) b = 0 else if (b > 255) b = 255
                    out[o] = r.toByte(); out[o + 1] = g.toByte(); out[o + 2] = b.toByte()
                }
                o += 3
            }
        }
    }

    /**
     * How sharp the picture is inside a region of the upright picture (higher = sharper): the strength of fine detail
     * in the brightness, relative to how bright it is. NaN when the region is too small.
     */
    fun sharpness(f: Frame, rotation: Int, u0: Float, v0: Float, u1: Float, v1: Float): Float {
        // the region's corners in the camera frame
        fun sx(u: Float, v: Float) = when (rotation) { 90 -> v; 180 -> 1f - u; 270 -> 1f - v; else -> u }
        fun sy(u: Float, v: Float) = when (rotation) { 90 -> 1f - u; 180 -> 1f - v; 270 -> u; else -> v }
        val xa = sx(u0, v0); val xb = sx(u1, v1); val ya = sy(u0, v0); val yb = sy(u1, v1)
        val x0 = (minOf(xa, xb) * f.w).toInt().coerceIn(1, f.w - 2); val x1 = (maxOf(xa, xb) * f.w).toInt().coerceIn(1, f.w - 2)
        val y0 = (minOf(ya, yb) * f.h).toInt().coerceIn(1, f.h - 2); val y1 = (maxOf(ya, yb) * f.h).toInt().coerceIn(1, f.h - 2)
        if (x1 - x0 < 8 || y1 - y0 < 8) return Float.NaN
        var sum = 0.0; var sum2 = 0.0; var bright = 0.0; var cnt = 0
        val row = f.yRow
        for (y in y0..y1) {
            val r = y * row
            for (x in x0..x1) {
                val c = f.y[r + x].toInt() and 0xFF
                val l = 4 * c - (f.y[r + x - 1].toInt() and 0xFF) - (f.y[r + x + 1].toInt() and 0xFF) -
                    (f.y[r - row + x].toInt() and 0xFF) - (f.y[r + row + x].toInt() and 0xFF)
                sum += l; sum2 += l.toDouble() * l; bright += c; cnt++
            }
        }
        if (cnt < 64) return Float.NaN
        val mean = sum / cnt
        val variance = sum2 / cnt - mean * mean
        return (variance / (bright / cnt + 8.0)).toFloat()
    }
}
