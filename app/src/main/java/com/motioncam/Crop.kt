package com.motioncam

import android.graphics.RectF
import android.util.Size
import kotlin.math.roundToInt

/**
 * CROP: record only part of the picture (a door peephole, a gate). The clip is the chosen area at the camera's own
 * pixel size, not the whole picture zoomed in, so it has the same detail there with fewer pixels to encode: the
 * bitrate scales with the area, and so does the file size (a quarter of the picture ≈ a quarter of the size).
 *
 * Stored as "left,top,right,bottom", 0..1 in sensor (unrotated) image coordinates like [Zones]; empty = whole picture.
 */
object Crop {
    /** The smallest crop, as a share of each side. */
    const val MIN = 0.12f

    fun parse(s: String?): RectF? {
        if (s.isNullOrBlank()) return null
        val p = s.split(",").mapNotNull { it.trim().toFloatOrNull() }
        if (p.size != 4) return null
        val r = RectF(p[0].coerceIn(0f, 1f), p[1].coerceIn(0f, 1f), p[2].coerceIn(0f, 1f), p[3].coerceIn(0f, 1f))
        if (r.width() < MIN * 0.9f || r.height() < MIN * 0.9f) return null
        if (isWhole(r)) return null
        return r
    }

    fun format(r: RectF?): String =
        if (r == null || isWhole(r)) "" else "%.4f,%.4f,%.4f,%.4f".format(java.util.Locale.US, r.left, r.top, r.right, r.bottom)

    fun isWhole(r: RectF) = r.width() >= 0.985f && r.height() >= 0.985f

    /** Sensor rect → the rect as seen on a picture rotated clockwise by [rotation] (all 0..1). */
    fun toDisplay(r: RectF, rotation: Int): RectF {
        val a = rotate(r.left, r.top, rotation); val b = rotate(r.right, r.bottom, rotation)
        return RectF(minOf(a.first, b.first), minOf(a.second, b.second), maxOf(a.first, b.first), maxOf(a.second, b.second))
    }

    /** The other way round. */
    fun toSensor(r: RectF, rotation: Int): RectF {
        val a = Zones.unrotate(r.left, r.top, rotation); val b = Zones.unrotate(r.right, r.bottom, rotation)
        return RectF(minOf(a.first, b.first), minOf(a.second, b.second), maxOf(a.first, b.first), maxOf(a.second, b.second))
    }

    /** Inverse of [Zones.unrotate]: a sensor point on the rotated picture. */
    private fun rotate(x: Float, y: Float, rotation: Int): Pair<Float, Float> = when (rotation) {
        90 -> Pair(1f - y, x)
        180 -> Pair(1f - x, 1f - y)
        270 -> Pair(y, 1f - x)
        else -> Pair(x, y)
    }

    /**
     * What the clip is encoded at for [r] of a [full]-sized recording: the area's own pixels, rounded to what video
     * encoders take (multiples of 16), never smaller than 160 px a side.
     */
    fun encodeSize(r: RectF, full: Size): Size {
        fun side(f: Float, n: Int) = ((f * n / 16f).roundToInt() * 16).coerceIn(160, n - n % 16)
        return Size(side(r.width(), full.width), side(r.height(), full.height))
    }

    /** [r] adjusted to exactly the shape of [enc] (rounding), around the same centre and inside the picture. */
    fun fitted(r: RectF, enc: Size, full: Size): RectF {
        val w = enc.width.toFloat() / full.width; val h = enc.height.toFloat() / full.height
        val cx = r.centerX().coerceIn(w / 2, 1f - w / 2); val cy = r.centerY().coerceIn(h / 2, 1f - h / 2)
        return RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
    }

    /** Motion detection only looks inside the crop (outside isn't recorded): the detector's blocks it covers. */
    fun blockMask(r: RectF): BooleanArray = BooleanArray(MotionDetector.N) { i ->
        val bx = i % MotionDetector.GW; val by = i / MotionDetector.GW
        val x = (bx + 0.5f) / MotionDetector.GW; val y = (by + 0.5f) / MotionDetector.GH
        x >= r.left && x <= r.right && y >= r.top && y <= r.bottom
    }

    /** "720×720" for the chip, as recorded (sensor orientation turned like the picture). */
    fun label(enc: Size, rotation: Int): String =
        if (rotation % 180 != 0) "${enc.height}×${enc.width}" else "${enc.width}×${enc.height}"

    /** The shapes offered (width / height as seen on screen); 0 = free. */
    val SHAPES = listOf("Free" to 0f, "Square 1:1" to 1f, "4:3" to 4f / 3f, "3:4" to 3f / 4f, "16:9" to 16f / 9f, "9:16" to 9f / 16f)
}
