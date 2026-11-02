package com.motioncam

import android.graphics.Bitmap
import android.media.Image
import kotlin.math.abs

/** A copied-out YUV_420_888 frame (so the camera Image can be released immediately). */
class Frame(
    val w: Int, val h: Int,
    val y: ByteArray, val yRow: Int,
    val u: ByteArray?, val v: ByteArray?, val uvRow: Int, val uvPix: Int,
)

object YuvTools {
    private fun copy(buf: java.nio.ByteBuffer): ByteArray {
        val d = buf.duplicate()
        d.rewind()
        val a = ByteArray(d.remaining())
        d.get(a)
        return a
    }

    fun read(img: Image, color: Boolean): Frame {
        val p = img.planes
        return Frame(
            img.width, img.height,
            copy(p[0].buffer), p[0].rowStride,
            if (color) copy(p[1].buffer) else null,
            if (color) copy(p[2].buffer) else null,
            p[1].rowStride, p[1].pixelStride,
        )
    }

    private const val GREEN = 0xFF20FF40.toInt()
    private const val SHADE = 0xA0000000.toInt()
    private const val RED_T = 0x70FF2020

    /**
     * Builds a bitmap from a frame, sub-sampled by [step] and rotated clockwise by [rotation].
     *  - [base]: draw the camera image (otherwise the bitmap is transparent and only overlays are drawn)
     *  - [peaking]: paint sharp edges green
     *  - [zones]: darken blocks that are excluded from detection; [motion]: tint moving blocks red
     */
    fun render(
        f: Frame, step: Int, rotation: Int, base: Boolean, peaking: Boolean,
        zones: BooleanArray? = null, motion: BooleanArray? = null,
    ): Bitmap {
        val w = f.w / step
        val h = f.h / step
        val px = IntArray(w * h)
        if (base) {
            val u = f.u!!
            val v = f.v!!
            for (y in 0 until h) {
                val sy = y * step
                val yrow = sy * f.yRow
                val crow = (sy shr 1) * f.uvRow
                for (x in 0 until w) {
                    val sx = x * step
                    val yy = f.y[yrow + sx].toInt() and 0xFF
                    val ci = crow + (sx shr 1) * f.uvPix
                    val uu = (u[ci].toInt() and 0xFF) - 128
                    val vv = (v[ci].toInt() and 0xFF) - 128
                    var r = yy + ((1436 * vv) shr 10)
                    var g = yy - ((352 * uu + 731 * vv) shr 10)
                    var b = yy + ((1815 * uu) shr 10)
                    if (r < 0) r = 0 else if (r > 255) r = 255
                    if (g < 0) g = 0 else if (g > 255) g = 255
                    if (b < 0) b = 0 else if (b > 255) b = 255
                    px[y * w + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
        }
        if (zones != null || motion != null) {
            for (y in 0 until h) {
                val by = y * MotionDetector.GH / h
                val cellEdgeY = (y * Zones.CH) % h < Zones.CH
                for (x in 0 until w) {
                    val bi = by * MotionDetector.GW + x * MotionDetector.GW / w
                    val i = y * w + x
                    if (zones != null && !zones[bi]) {
                        px[i] = if (base) darken(px[i]) else SHADE
                    } else if (motion != null && motion[bi]) {
                        px[i] = if (base) redden(px[i]) else RED_T
                    }
                    if (zones != null && (cellEdgeY || (x * Zones.CW) % w < Zones.CW)) {
                        px[i] = if (base) darken(px[i]) else 0x40FFFFFF
                    }
                }
            }
        }
        if (peaking) {
            val thr = 50
            for (y in 1 until h - 1) {
                val r0 = (y * step) * f.yRow
                val rm = ((y - 1) * step) * f.yRow
                val rp = ((y + 1) * step) * f.yRow
                for (x in 1 until w - 1) {
                    val sx = x * step
                    val gx = abs((f.y[r0 + sx + step].toInt() and 0xFF) - (f.y[r0 + sx - step].toInt() and 0xFF))
                    val gy = abs((f.y[rp + sx].toInt() and 0xFF) - (f.y[rm + sx].toInt() and 0xFF))
                    if (gx + gy > thr) px[y * w + x] = GREEN
                }
            }
        }
        return rotate(px, w, h, rotation)
    }

    private fun darken(c: Int): Int {
        val r = (c shr 16 and 0xFF) / 3
        val g = (c shr 8 and 0xFF) / 3
        val b = (c and 0xFF) / 3
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun redden(c: Int): Int {
        val r = minOf(255, (c shr 16 and 0xFF) / 2 + 128)
        val g = (c shr 8 and 0xFF) / 2
        val b = (c and 0xFF) / 2
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun rotate(px: IntArray, w: Int, h: Int, rotation: Int): Bitmap {
        val outW: Int
        val outH: Int
        val out: IntArray
        when (rotation) {
            90 -> {
                outW = h; outH = w; out = IntArray(w * h)
                for (y in 0 until h) for (x in 0 until w) out[x * outW + (h - 1 - y)] = px[y * w + x]
            }
            180 -> {
                outW = w; outH = h; out = IntArray(w * h)
                for (y in 0 until h) for (x in 0 until w) out[(h - 1 - y) * w + (w - 1 - x)] = px[y * w + x]
            }
            270 -> {
                outW = h; outH = w; out = IntArray(w * h)
                for (y in 0 until h) for (x in 0 until w) out[(w - 1 - x) * outW + y] = px[y * w + x]
            }
            else -> { outW = w; outH = h; out = px }
        }
        val bmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        bmp.setPixels(out, 0, outW, 0, 0, outW, outH)
        return bmp
    }
}
