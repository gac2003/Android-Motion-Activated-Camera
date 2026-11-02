package com.motioncam

import kotlin.math.abs

/**
 * Cheap block-based motion detector working on the luma plane only.
 * The frame is split into 32x18 blocks; each block's mean brightness is compared to a slowly adapting
 * background. A global brightness shift (auto-exposure, clouds) is subtracted so it does not count as motion.
 */
class MotionDetector {
    companion object {
        const val GW = 32
        const val GH = 18
        const val N = GW * GH
    }

    private val bg = FloatArray(N)
    private val cur = IntArray(N)
    private var initialised = false
    private var warmup = 0

    /** Call after the camera session (re)starts: ignores a few frames while exposure settles. */
    fun reset() { initialised = false; warmup = 6 }

    /** @return number of changed blocks (0 during warm-up). */
    fun analyze(f: Frame, sensitivity: Int): Int {
        YuvTools.blockMeans(f, GW, GH, cur)
        if (!initialised) {
            for (i in 0 until N) bg[i] = cur[i].toFloat()
            initialised = true
            return 0
        }
        var shift = 0f
        for (i in 0 until N) shift += cur[i] - bg[i]
        shift /= N

        val thr = 4f + (10 - sensitivity.coerceIn(1, 10)) * 2f
        var changed = 0
        for (i in 0 until N) {
            if (abs(cur[i] - bg[i] - shift) > thr) changed++
        }
        val a = if (warmup > 0) 0.5f else 0.05f
        for (i in 0 until N) bg[i] += (cur[i] - bg[i]) * a
        if (warmup > 0) { warmup--; return 0 }
        return changed
    }
}
