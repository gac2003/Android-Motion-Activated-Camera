package com.motioncam

import kotlin.math.abs
import kotlin.math.ln

/**
 * Block-based motion detector on the luma plane, tuned to ignore lighting changes.
 *
 * The frame is split into 32x18 blocks; each block is summarised by its mean brightness and the
 * brightness pattern of its four quadrants.
 *  - Brightness is compared in the log domain (ratio), so a light getting brighter/dimmer affects
 *    every block similarly. The *median* change across the frame is subtracted, so a global light
 *    change (lamp on, cloud, auto-exposure) is ignored, even if a moving object covers part of it.
 *  - Light filter "strong" additionally looks at each moving area as a whole: it only counts if most of
 *    its blocks changed *structure* (the brightness pattern inside the block). A person changes texture
 *    throughout; a reflection or glare patch only scales brightness (and clipped white has no structure).
 *  - Only the largest *connected* group of changed blocks counts, so scattered flicker/noise
 *    does not add up to a trigger.
 */
class MotionDetector {
    companion object {
        const val GW = 32
        const val GH = 18
        const val N = GW * GH
        const val MAX_SENSITIVITY = 14

        /**
         * Minimum relative brightness change for a block to count as moving.
         * 1: 40 %, 5 (default): 28 %, 10: 13 %; levels 11-14 use smaller steps (10 %, 7 %, 5.5 %, 4 %) so the
         * most sensitive end catches low-contrast people (clothes similar to the walls) without reacting to
         * plain sensor noise.
         */
        fun threshold(s: Int): Float = when {
            s <= 10 -> 0.13f + (10 - s) * 0.03f
            else -> floatArrayOf(0.10f, 0.07f, 0.055f, 0.04f)[(s - 11).coerceIn(0, 3)]
        }

        fun patternThreshold(s: Int): Float = when {
            s <= 10 -> 0.084f + (10 - s) * 0.012f
            // levels 11-14 keep the structure test strict, so light sweeping across the scene stays ignored
            else -> 0.072f
        }
    }

    private val bgM = FloatArray(N)
    private val bgQ = FloatArray(N * 4)
    private val m = FloatArray(N)
    private val q = FloatArray(N * 4)
    private val rel = FloatArray(N)
    private val tmp = FloatArray(N)
    private val stack = IntArray(N)
    private val seen = BooleanArray(N)
    private val structural = BooleanArray(N)
    private val comp = IntArray(N)
    private val sums = IntArray(4)
    private val ns = IntArray(4)
    /** Blocks considered changed in the last analysis (for the on-screen motion view). */
    val changed = BooleanArray(N)

    // ---- choosing a clip's picture (see [thumb]): a second, more forgiving look at what differs from the empty scene
    private val bgU = FloatArray(N); private val bgV = FloatArray(N)       // the scene's colour, kept up to date while idle
    private val cu = FloatArray(N); private val cv = FloatArray(N)         // this frame's colour
    private val frozM = FloatArray(N); private val frozU = FloatArray(N); private val frozV = FloatArray(N)
    private var chromaKnown = false
    /** Off during a clip: the scene's colour is only learned while nothing is being recorded. */
    @Volatile var learnColour = true
    private var frozen = false
    private var frozenChroma = false
    private val soft = BooleanArray(N)
    private val softMembers = IntArray(N)
    /** The largest area that differs from the empty scene in the last [thumb] look: its blocks and its box (in blocks). */
    var softSize = 0; private set
    var softMinX = 0; private set
    var softMaxX = 0; private set
    var softMinY = 0; private set
    var softMaxY = 0; private set
    fun softMember(i: Int) = softMembers[i]

    /**
     * A clip starts: the scene as it was (brightness and colour of every block) is kept as "the empty scene" for
     * choosing the clip's picture. The normal background keeps adapting; this one doesn't, so a person who stands still
     * or wears something close to the wall's brightness still stands out against it.
     */
    fun freeze() {
        System.arraycopy(bgM, 0, frozM, 0, N)
        System.arraycopy(bgU, 0, frozU, 0, N); System.arraycopy(bgV, 0, frozV, 0, N)
        frozenChroma = chromaKnown
        frozen = initialised
    }

    /** The colour of every block, when the frame came with its colour planes. */
    private fun measureChroma(f: Frame): Boolean {
        val u = f.u ?: return false; val v = f.v ?: return false
        for (gy in 0 until GH) {
            val y0 = gy * f.h / GH; val y1 = (gy + 1) * f.h / GH
            for (gx in 0 until GW) {
                val x0 = gx * f.w / GW; val x1 = (gx + 1) * f.w / GW
                var su = 0; var sv = 0; var n = 0
                var y = y0
                while (y < y1) {
                    val row = (y shr 1) * f.uvRow
                    var x = x0
                    while (x < x1) {
                        val i = row + (x shr 1) * f.uvPix
                        if (i < u.size && i < v.size) { su += u[i].toInt() and 0xFF; sv += v[i].toInt() and 0xFF; n++ }
                        x += 4
                    }
                    y += 4
                }
                val bi = gy * GW + gx
                cu[bi] = if (n > 0) su.toFloat() / n else 128f
                cv[bi] = if (n > 0) sv.toFloat() / n else 128f
            }
        }
        return true
    }

    /**
     * For choosing a clip's picture (call right after [analyze], during a clip): finds the largest connected area that
     * differs from the empty scene kept by [freeze]. More forgiving than the motion trigger: half its brightness
     * threshold, and colour counts too (skin, hair and clothes differ from a white wall in colour even where the
     * brightness is the same). Light changes over the whole picture are taken out, as for motion. Fills [softSize]
     * and the area's box; returns [softSize].
     */
    fun thumb(f: Frame, sensitivity: Int, zones: BooleanArray?): Int {
        softSize = 0
        if (!frozen) return 0
        val colour = frozenChroma && measureChroma(f)
        var cnt = 0
        for (i in 0 until N) {
            rel[i] = ln((m[i] + 10f) / (frozM[i] + 10f))
            if (zones == null || zones[i]) tmp[cnt++] = rel[i]
        }
        val global = if (cnt > 0) median(tmp, cnt) else 0f
        var gu = 0f; var gv = 0f
        if (colour) {
            // a white-balance shift moves every block's colour alike: taken out like a light change
            cnt = 0; for (i in 0 until N) if (zones == null || zones[i]) tmp[cnt++] = cu[i] - frozU[i]
            gu = if (cnt > 0) median(tmp, cnt) else 0f
            cnt = 0; for (i in 0 until N) if (zones == null || zones[i]) tmp[cnt++] = cv[i] - frozV[i]
            gv = if (cnt > 0) median(tmp, cnt) else 0f
        }
        val thr = threshold(sensitivity.coerceIn(1, MAX_SENSITIVITY)) * 0.5f
        for (i in 0 until N) {
            soft[i] = (zones == null || zones[i]) && (abs(rel[i] - global) > thr ||
                (colour && abs(cu[i] - frozU[i] - gu) + abs(cv[i] - frozV[i] - gv) > 7f))
        }
        // the largest connected area
        seen.fill(false)
        for (start in 0 until N) {
            if (!soft[start] || seen[start]) continue
            var sp = 0
            stack[sp++] = start; seen[start] = true
            var members = 0
            var minX = GW; var maxX = -1; var minY = GH; var maxY = -1
            while (sp > 0) {
                val i = stack[--sp]
                comp[members++] = i
                val x = i % GW; val y = i / GW
                if (x < minX) minX = x; if (x > maxX) maxX = x; if (y < minY) minY = y; if (y > maxY) maxY = y
                if (x > 0 && soft[i - 1] && !seen[i - 1]) { seen[i - 1] = true; stack[sp++] = i - 1 }
                if (x < GW - 1 && soft[i + 1] && !seen[i + 1]) { seen[i + 1] = true; stack[sp++] = i + 1 }
                if (y > 0 && soft[i - GW] && !seen[i - GW]) { seen[i - GW] = true; stack[sp++] = i - GW }
                if (y < GH - 1 && soft[i + GW] && !seen[i + GW]) { seen[i + GW] = true; stack[sp++] = i + GW }
            }
            if (members > softSize) {
                softSize = members
                System.arraycopy(comp, 0, softMembers, 0, members)
                softMinX = minX; softMaxX = maxX; softMinY = minY; softMaxY = maxY
            }
        }
        return softSize
    }
    private var initialised = false
    private var warmup = 0

    fun reset() { initialised = false; warmup = 8; frozen = false; chromaKnown = false }

    /** @return size (in blocks) of the largest connected moving area. */
    fun analyze(f: Frame, sensitivity: Int, lightFilter: Int, zones: BooleanArray?): Int {
        measure(f)
        // the scene's colour is refreshed whenever a frame comes with its colour planes (every couple of seconds while watching)
        if (learnColour && f.u != null && measureChroma(f)) {
            if (!chromaKnown) { System.arraycopy(cu, 0, bgU, 0, N); System.arraycopy(cv, 0, bgV, 0, N); chromaKnown = true }
            else for (i in 0 until N) { bgU[i] += (cu[i] - bgU[i]) * 0.3f; bgV[i] += (cv[i] - bgV[i]) * 0.3f }
        }
        if (!initialised) {
            System.arraycopy(m, 0, bgM, 0, N)
            System.arraycopy(q, 0, bgQ, 0, N * 4)
            initialised = true
            changed.fill(false)
            return 0
        }
        val s = sensitivity.coerceIn(1, MAX_SENSITIVITY)

        // relative brightness change per block
        var cnt = 0
        for (i in 0 until N) {
            rel[i] = ln((m[i] + 10f) / (bgM[i] + 10f))
            if (zones == null || zones[i]) tmp[cnt++] = rel[i]
        }
        val global = if (lightFilter >= 1 && cnt > 0) median(tmp, cnt) else 0f

        val thr = threshold(s)
        val patThr = patternThreshold(s)
        for (i in 0 until N) {
            val c = (zones == null || zones[i]) && abs(rel[i] - global) > thr
            changed[i] = c
            structural[i] = false
            if (c && lightFilter >= 2) {
                // does the structure inside the block change, or only its brightness?
                var st = false
                if (m[i] <= 235f && bgM[i] <= 235f) {   // clipped highlights (glare) never count as structure
                    var pd = 0f
                    for (k in 0 until 4) {
                        val a = (q[i * 4 + k] + 10f) / (m[i] + 10f)
                        val b = (bgQ[i * 4 + k] + 10f) / (bgM[i] + 10f)
                        val d = abs(a - b)
                        if (d > pd) pd = d
                    }
                    st = pd >= patThr
                }
                structural[i] = st
            }
        }

        // background update: fast while warming up, slow for moving blocks
        for (i in 0 until N) {
            val a = when {
                warmup > 0 -> 0.5f
                changed[i] -> 0.01f
                else -> 0.04f
            }
            bgM[i] += (m[i] - bgM[i]) * a
            for (k in 0 until 4) bgQ[i * 4 + k] += (q[i * 4 + k] - bgQ[i * 4 + k]) * a
        }
        if (warmup > 0) { warmup--; changed.fill(false); return 0 }
        return largestCluster(lightFilter >= 2)
    }

    private fun measure(f: Frame) {
        val step = maxOf(1, f.w / 320)   // ~320 samples per row whatever the stream size
        for (gy in 0 until GH) {
            val y0 = gy * f.h / GH
            val y1 = (gy + 1) * f.h / GH
            val ym = (y0 + y1) / 2
            for (gx in 0 until GW) {
                val x0 = gx * f.w / GW
                val x1 = (gx + 1) * f.w / GW
                val xm = (x0 + x1) / 2
                sums.fill(0)
                ns.fill(0)
                var y = y0
                while (y < y1) {
                    val row = y * f.yRow
                    val qy = if (y < ym) 0 else 2
                    var x = x0
                    while (x < x1) {
                        val k = qy + if (x < xm) 0 else 1
                        sums[k] += f.y[row + x].toInt() and 0xFF
                        ns[k]++
                        x += step
                    }
                    y += step
                }
                val bi = gy * GW + gx
                var tot = 0f
                for (k in 0 until 4) {
                    val v = if (ns[k] > 0) sums[k].toFloat() / ns[k] else 0f
                    q[bi * 4 + k] = v
                    tot += v
                }
                m[bi] = tot / 4f
            }
        }
    }

    private fun median(a: FloatArray, n: Int): Float {
        java.util.Arrays.sort(a, 0, n)
        return a[n / 2]
    }

    /**
     * Size of the largest connected changed area. With [structure], an area only counts if at least half
     * of its blocks changed structure: a person/object changes texture throughout, while a light patch or
     * reflection only changes structure along its edges. Rejected areas are cleared from [changed].
     */
    private fun largestCluster(structure: Boolean): Int {
        seen.fill(false)
        var best = 0
        for (start in 0 until N) {
            if (!changed[start] || seen[start]) continue
            var sp = 0
            stack[sp++] = start
            seen[start] = true
            var size = 0
            var structCount = 0
            var members = 0
            while (sp > 0) {
                val i = stack[--sp]
                comp[members++] = i
                size++
                if (structural[i]) structCount++
                val x = i % GW
                val y = i / GW
                if (x > 0) sp = push(i - 1, sp)
                if (x < GW - 1) sp = push(i + 1, sp)
                if (y > 0) sp = push(i - GW, sp)
                if (y < GH - 1) sp = push(i + GW, sp)
            }
            if (structure && structCount * 2 < size) {
                for (k in 0 until members) changed[comp[k]] = false
                continue
            }
            if (size > best) best = size
        }
        return best
    }

    private fun push(i: Int, sp: Int): Int {
        if (changed[i] && !seen[i]) { seen[i] = true; stack[sp] = i; return sp + 1 }
        return sp
    }
}
