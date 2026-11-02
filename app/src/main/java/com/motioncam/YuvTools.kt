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

    /** Mean luma of each cell of a gridW x gridH grid, sampling every 4th pixel. */
    fun blockMeans(f: Frame, gridW: Int, gridH: Int, out: IntArray) {
        val step = 4
        for (gy in 0 until gridH) {
            val y0 = gy * f.h / gridH
            val y1 = (gy + 1) * f.h / gridH
            for (gx in 0 until gridW) {
                val x0 = gx * f.w / gridW
                val x1 = (gx + 1) * f.w / gridW
                var sum = 0
                var n = 0
                var y = y0
                while (y < y1) {
                    val row = y * f.yRow
                    var x = x0
                    while (x < x1) {
                        sum += f.y[row + x].toInt() and 0xFF
                        n++
                        x += step
                    }
                    y += step
                }
                out[gy * gridW + gx] = if (n > 0) sum / n else 0
            }
        }
    }

    /**
     * Converts to an ARGB bitmap, sub-sampling by [step] (1 = full size, 2 = half size) and rotating
     * clockwise by [rotation] degrees. Optionally paints sharp edges green (focus peaking).
     */
    fun toBitmap(f: Frame, step: Int, rotation: Int, peaking: Boolean): Bitmap {
        val w = f.w / step
        val h = f.h / step
        val px = IntArray(w * h)
        val ys = if (peaking) IntArray(w * h) else null
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
                if (ys != null) ys[y * w + x] = yy
            }
        }
        if (ys != null) {
            val thr = 55
            for (y in 1 until h - 1) {
                for (x in 1 until w - 1) {
                    val i = y * w + x
                    val gr = abs(ys[i + 1] - ys[i - 1]) + abs(ys[i + w] - ys[i - w])
                    if (gr > thr) px[i] = 0xFF00FF40.toInt()
                }
            }
        }
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
