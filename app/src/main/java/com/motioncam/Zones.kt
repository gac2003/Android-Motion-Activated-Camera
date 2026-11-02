package com.motioncam

/** Detection zones: a 16x9 grid of cells in sensor (unrotated) image coordinates. */
object Zones {
    const val CW = 16
    const val CH = 9
    const val CELLS = CW * CH

    fun parse(s: String): BooleanArray {
        val out = BooleanArray(CELLS) { true }
        if (s.length == CELLS) for (i in 0 until CELLS) out[i] = s[i] == '1'
        return out
    }

    fun format(cells: BooleanArray): String =
        if (cells.all { it }) "" else String(CharArray(CELLS) { if (cells[it]) '1' else '0' })

    /** Expands cells to the detector's block grid (each cell = 2x2 blocks). */
    fun blockMask(cells: BooleanArray): BooleanArray {
        val out = BooleanArray(MotionDetector.N)
        for (by in 0 until MotionDetector.GH) for (bx in 0 until MotionDetector.GW) {
            val cx = bx * CW / MotionDetector.GW
            val cy = by * CH / MotionDetector.GH
            out[by * MotionDetector.GW + bx] = cells[cy * CW + cx]
        }
        return out
    }

    /**
     * Maps a point on the displayed (rotated clockwise by [rotation]) image, normalised 0..1,
     * back to normalised sensor image coordinates.
     */
    fun unrotate(u: Float, v: Float, rotation: Int): Pair<Float, Float> = when (rotation) {
        90 -> Pair(v, 1f - u)
        180 -> Pair(1f - u, 1f - v)
        270 -> Pair(1f - v, u)
        else -> Pair(u, v)
    }
}

object Rot {
    /** Degrees (clockwise) to rotate a sensor image so it is upright for a display rotated by [displayDeg]. */
    fun image(sensorOrientation: Int, front: Boolean, displayDeg: Int): Int =
        if (front) (sensorOrientation + displayDeg) % 360 else (sensorOrientation - displayDeg + 360) % 360

    fun displayDegrees(rotation: Int): Int = when (rotation) {
        android.view.Surface.ROTATION_90 -> 90
        android.view.Surface.ROTATION_180 -> 180
        android.view.Surface.ROTATION_270 -> 270
        else -> 0
    }
}
