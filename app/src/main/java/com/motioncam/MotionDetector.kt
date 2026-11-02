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
    private var initialised = false
    private var warmup = 0

    fun reset() { initialised = false; warmup = 8 }

    /** @return size (in blocks) of the largest connected moving area. */
    fun analyze(f: Frame, sensitivity: Int, lightFilter: Int, zones: BooleanArray?): Int {
        measure(f)
        if (!initialised) {
            System.arraycopy(m, 0, bgM, 0, N)
            System.arraycopy(q, 0, bgQ, 0, N * 4)
            initialised = true
            changed.fill(false)
            return 0
        }
        val s = sensitivity.coerceIn(1, 10)

        // relative brightness change per block
        var cnt = 0
        for (i in 0 until N) {
            rel[i] = ln((m[i] + 10f) / (bgM[i] + 10f))
            if (zones == null || zones[i]) tmp[cnt++] = rel[i]
        }
        val global = if (lightFilter >= 1 && cnt > 0) median(tmp, cnt) else 0f

        // s=10: 7 %, s=5: 22 %, s=1: 34 % brightness change (default tuned for people/animals)
        val thr = 0.07f + (10 - s) * 0.03f
        val patThr = 0.06f + (10 - s) * 0.012f
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
