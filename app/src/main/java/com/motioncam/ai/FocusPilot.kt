package com.motioncam.ai

import android.content.Context
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Person-aware focus: where the lens should be, in diopters (1 / metres; 0 = infinity), from what the AI sees.
 *
 *  - Resting focus is the "usual distance": the middle of where people were in past clips once enough clips are
 *    known ([USUAL_MIN] of them), until then your manual focus setting.
 *  - A person's distance is estimated from their size in the picture (full height when the feet are visible, else
 *    shoulders-to-hips, else head-to-shoulders, else the box), and the lens is set there directly: no hunting.
 *  - For a close person (where focus matters), three settings around the estimate are tried once and the sharpest
 *    (measured inside the person) is kept. The difference to the estimate is remembered per distance, so the next
 *    time the lens goes to the right setting at once, even on phones whose focus scale isn't calibrated.
 *
 * All of it runs on the AI thread; the camera engine only reads [target].
 */
object FocusPilot {
    /** Where the lens should be now (diopters), or NaN = the resting focus. Read by the camera engine. */
    @Volatile var target = Float.NaN; private set

    private const val STEP = 0.4f              // the sharpness test's step (the small analysis picture can't tell less)
    private const val SEARCH_FROM = 0.4f       // only closer than 2.5 m: farther away everything is in focus anyway
    private const val SETTLE_MS = 180L         // the lens needs a moment to arrive before sharpness means anything
    private const val LOST_MS = 2000L
    private const val BIN = 0.25f
    private const val BINS = 17
    const val USUAL_MIN = 20
    private const val USUAL_KEEP = 200

    // ---- what was learned (kept in a small preferences file)
    private var loaded = false
    private val offSum = FloatArray(BINS)
    private val offN = IntArray(BINS)
    private val usual = ArrayList<Float>()
    private var dirty = false

    // ---- this clip
    private var phase = 0                      // 0 = follow the estimate, 1 = testing sharpness
    private val probes = FloatArray(3)
    private val scores = FloatArray(3)
    private var probeIdx = 0
    private var searchEst = Float.NaN
    private var lastSearchEst = Float.NaN
    private var clipOffset = 0f
    private var setAt = 0L
    private var lastPersonAt = 0L
    private var clipSum = 0f
    private var clipN = 0

    // ---- counters for Diagnostics
    @Volatile var moves = 0L; private set
    @Volatile var searches = 0L; private set
    @Volatile var lastEst = Float.NaN; private set
    @Volatile var lastHow = ""; private set

    private fun sp(ctx: Context) = ctx.getSharedPreferences("ai_focus", Context.MODE_PRIVATE)

    @Synchronized
    private fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        try {
            val s = sp(ctx)
            s.getString("offsets", "")!!.split(',').forEachIndexed { i, e ->
                val p = e.split(':')
                if (i < BINS && p.size == 2) { offSum[i] = p[0].toFloatOrNull() ?: 0f; offN[i] = p[1].toIntOrNull() ?: 0 }
            }
            s.getString("usual", "")!!.split(',').mapNotNull { it.toFloatOrNull() }.forEach { usual += it }
        } catch (_: Exception) {}
    }

    @Synchronized
    private fun save(ctx: Context) {
        if (!dirty) return
        dirty = false
        try {
            sp(ctx).edit()
                .putString("offsets", (0 until BINS).joinToString(",") { "${offSum[it]}:${offN[it]}" })
                .putString("usual", usual.joinToString(","))
                .apply()
        } catch (_: Exception) {}
    }

    /** Forgets everything learned (Settings). */
    @Synchronized
    fun forget(ctx: Context) {
        load(ctx)
        offSum.fill(0f); offN.fill(0); usual.clear()
        dirty = false
        try { sp(ctx).edit().clear().apply() } catch (_: Exception) {}
    }

    /** The learned usual distance in diopters, or NaN until [USUAL_MIN] clips with a person are known. */
    @Synchronized
    fun usual(ctx: Context): Float {
        load(ctx)
        if (usual.size < USUAL_MIN) return Float.NaN
        return usual.sorted()[usual.size / 2]
    }

    @Synchronized fun clipsLearned(ctx: Context): Int { load(ctx); return usual.size }
    @Synchronized fun correctionsLearned(ctx: Context): Int { load(ctx); return offN.sum() }

    /** Resting focus (diopters): the usual distance once learned, else [manual] (your manual focus setting). */
    fun resting(ctx: Context, manual: Float, maxD: Float): Float {
        val u = usual(ctx)
        return (if (u.isNaN()) manual else u).coerceIn(0f, maxD)
    }

    private fun bin(est: Float) = (est / BIN).toInt().coerceIn(0, BINS - 1)

    /** The estimate, corrected by what was learned for that distance. */
    @Synchronized
    private fun corrected(est: Float): Float {
        val b = bin(est)
        return if (offN[b] >= 3) est + offSum[b] / offN[b] else est
    }

    @Synchronized
    private fun learn(est: Float, best: Float) {
        val b = bin(est)
        // a running average that keeps following (old samples fade once there are 30)
        if (offN[b] >= 30) { offSum[b] = offSum[b] / offN[b] * 29f; offN[b] = 29 }
        offSum[b] += best - est
        offN[b]++
        dirty = true
    }

    private fun set(d: Float, maxD: Float, now: Long, force: Boolean = false) {
        val v = d.coerceIn(0f, maxD)
        val cur = target
        if (!force && !cur.isNaN() && abs(v - cur) < 0.08f) return
        target = v
        setAt = now
        moves++
    }

    /** A clip started: the lens is at the resting focus (the engine does that); nothing is known yet. */
    fun clipStart() {
        phase = 0
        lastSearchEst = Float.NaN
        clipOffset = 0f
        clipSum = 0f; clipN = 0
        lastPersonAt = 0L
        target = Float.NaN
    }

    /** The clip ended: back to the resting focus; the clip's typical distance joins what is known. */
    fun clipEnd(ctx: Context) {
        target = Float.NaN
        phase = 0
        if (clipN >= 3) synchronized(this) {
            load(ctx)
            usual += clipSum / clipN
            while (usual.size > USUAL_KEEP) usual.removeAt(0)
            dirty = true
        }
        clipSum = 0f; clipN = 0
        save(ctx)
    }

    /** No person in this frame. After a moment without one, the lens goes back to the resting focus. */
    fun noPerson(now: Long) {
        if (lastPersonAt > 0 && now - lastPersonAt > LOST_MS && !target.isNaN()) {
            target = Float.NaN
            phase = 0
            lastSearchEst = Float.NaN
            clipOffset = 0f
        }
    }

    /**
     * A person in the frame taken at [takenAt]: [est] = estimated distance in diopters, [sharp] = sharpness inside the
     * person (NaN if it couldn't be measured), [maxD] = the lens's closest focus. Returns true when [target] changed.
     */
    fun person(est: Float, how: String, sharp: Float, maxD: Float, takenAt: Long, now: Long): Boolean {
        val before = target
        lastPersonAt = now
        lastEst = est
        lastHow = how
        val base = corrected(est).coerceIn(0f, maxD)
        if (phase == 1) {
            // the frame shows the lens at probes[probeIdx] only if it was taken after the lens had time to arrive
            if (takenAt - setAt >= SETTLE_MS && !sharp.isNaN()) {
                scores[probeIdx] = sharp
                probeIdx++
                if (probeIdx < 3) set(probes[probeIdx], maxD, now, force = true)
                else {
                    var best = 0
                    for (i in 1..2) if (scores[i] > scores[best]) best = i
                    // only a clear winner moves the lens off the estimate (people move, so sharpness is noisy)
                    if (best != 0 && scores[best] < scores[0] * 1.10f) best = 0
                    val lo = minOf(scores[0], scores[1], scores[2]); val hi = maxOf(scores[0], scores[1], scores[2])
                    if (hi > lo * 1.10f) learn(searchEst, probes[best])
                    clipOffset = probes[best] - probes[0]
                    lastSearchEst = searchEst
                    phase = 0
                    set(probes[best], maxD, now, force = true)
                }
            }
        } else {
            val near = est >= SEARCH_FROM
            if (near && !sharp.isNaN() && (lastSearchEst.isNaN() || abs(est - lastSearchEst) > 0.5f)) {
                // try the estimate, then one step closer and one step farther
                searchEst = est
                probes[0] = base
                probes[1] = (base + STEP).coerceAtMost(maxD)
                probes[2] = (base - STEP).coerceAtLeast(0f)
                probeIdx = 0
                phase = 1
                searches++
                set(probes[0], maxD, now, force = true)
            } else {
                // keep what the sharpness test found for this person while they stay about as far away
                val off = if (!lastSearchEst.isNaN() && abs(est - lastSearchEst) <= 0.5f) clipOffset else 0f
                set(base + off, maxD, now)
            }
        }
        val t = target
        if (!t.isNaN()) { clipSum += t; clipN++ }
        return t != before && !(t.isNaN() && before.isNaN())
    }

    /**
     * Distance in diopters from body points (33 points as u, v in the upright picture, 0..1, with visibility), or from
     * the person's box. [fu], [fv] = the lens's focal length in units of the picture's width and height. Returns the
     * estimate and which measure was used; NaN when the person can't be measured.
     */
    fun estimate(u: FloatArray?, v: FloatArray?, vis: FloatArray?, boxTop: Float, boxBottom: Float, fu: Float, fv: Float): Pair<Float, String> {
        fun len(u0: Float, v0: Float, u1: Float, v1: Float): Float {
            val a = (u1 - u0) / fu; val b = (v1 - v0) / fv
            return sqrt(a * a + b * b)
        }
        if (u != null && v != null && vis != null) {
            fun ok(i: Int) = vis[i] > 0.6f && u[i] in 0.01f..0.99f && v[i] in 0.01f..0.99f
            val shoulders = ok(11) && ok(12)
            val su = (u[11] + u[12]) / 2; val sv = (v[11] + v[12]) / 2
            // whole body: eyes to ankles, about 1.58 m on a 1.7 m adult
            if (ok(2) && ok(5) && ok(27) && ok(28)) {
                val l = len((u[2] + u[5]) / 2, (v[2] + v[5]) / 2, (u[27] + u[28]) / 2, (v[27] + v[28]) / 2)
                if (l > 0.02f) return Pair(l / 1.58f, "height")
            }
            // feet cut off: shoulders to hips, about 0.50 m
            if (shoulders && ok(23) && ok(24)) {
                val l = len(su, sv, (u[23] + u[24]) / 2, (v[23] + v[24]) / 2)
                if (l > 0.01f) return Pair(l / 0.50f, "torso")
            }
            // only head and shoulders: nose to shoulder line, about 0.22 m
            if (shoulders && ok(0)) {
                val l = len(u[0], v[0], su, sv)
                if (l > 0.005f) return Pair(l / 0.22f, "head")
            }
        }
        // no usable body points: the box, if the person isn't cut off at the top or bottom
        if (boxTop > 0.03f && boxBottom < 0.97f) {
            val l = (boxBottom - boxTop) / fv
            if (l > 0.02f) return Pair(l / 1.7f, "box")
        }
        return Pair(Float.NaN, "")
    }
}
