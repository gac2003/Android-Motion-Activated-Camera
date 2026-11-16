package com.motioncam.ai

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Faces in a picture: finds a face (with its eyes, nose and mouth corners), turns it upright into a small standard
 * picture, and makes its "face fingerprint" (128 numbers; the same person's fingerprints are close, other people's
 * are far). Works on plain pixel arrays (0xRRGGBB, row by row), so it can be tested off the phone.
 */
object Faces {
    const val DIM = 128
    const val FACE = 112
    private const val IN = 640

    /** A found face: how sure (0..1), its box and its five points (eyes, nose, mouth corners), in picture pixels. */
    class Face(val score: Float, val x: Float, val y: Float, val w: Float, val h: Float, val pts: FloatArray)

    /** Both models, loaded from their files' bytes. */
    class Finder(detector: ByteArray, printer: ByteArray, threads: Int) : java.io.Closeable {
        private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
        private val det: OrtSession = env.createSession(detector, OrtSession.SessionOptions().apply { setIntraOpNumThreads(threads) })
        private val emb: OrtSession = env.createSession(printer, OrtSession.SessionOptions().apply { setIntraOpNumThreads(threads) })
        private val input = FloatArray(3 * IN * IN)

        /**
         * The clearest face inside a region of the picture ([px], [w] × [h]; region in pixels), or null. The region is
         * blown up to the detector's size, so a small face in a person's box is still seen.
         */
        fun find(px: IntArray, w: Int, h: Int, left: Int, top: Int, right: Int, bottom: Int, minScore: Float = 0.7f): Face? {
            val x0 = left.coerceIn(0, w - 1); val y0 = top.coerceIn(0, h - 1)
            val rw = (right.coerceIn(x0 + 1, w) - x0); val rh = (bottom.coerceIn(y0 + 1, h) - y0)
            if (rw < 16 || rh < 16) return null
            val s = IN.toFloat() / maxOf(rw, rh)          // region pixels → detector pixels
            val ow = (rw * s).toInt().coerceAtMost(IN); val oh = (rh * s).toInt().coerceAtMost(IN)
            val n = IN * IN
            java.util.Arrays.fill(input, 0f)
            for (oy in 0 until oh) {
                val sy = (y0 + (oy / s).toInt()).coerceAtMost(h - 1) * w
                val row = oy * IN
                for (ox in 0 until ow) {
                    val c = px[sy + (x0 + (ox / s).toInt()).coerceAtMost(w - 1)]
                    // the detector reads blue, green, red, each 0..255
                    input[row + ox] = (c and 0xFF).toFloat(); input[n + row + ox] = ((c shr 8) and 0xFF).toFloat(); input[2 * n + row + ox] = ((c shr 16) and 0xFF).toFloat()
                }
            }
            var best: Face? = null
            OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 3, IN.toLong(), IN.toLong())).use { t ->
                det.run(mapOf("input" to t)).use { r ->
                    for (stride in intArrayOf(8, 16, 32)) {
                        val cols = IN / stride
                        val cls = (r.get("cls_$stride").get() as OnnxTensor).floatBuffer
                        val obj = (r.get("obj_$stride").get() as OnnxTensor).floatBuffer
                        val box = (r.get("bbox_$stride").get() as OnnxTensor).floatBuffer
                        val kps = (r.get("kps_$stride").get() as OnnxTensor).floatBuffer
                        for (i in 0 until cols * cols) {
                            val score = sqrt(cls.get(i).coerceIn(0f, 1f) * obj.get(i).coerceIn(0f, 1f))
                            if (score < minScore) continue
                            val row = i / cols; val col = i % cols
                            val fw = exp(box.get(i * 4 + 2)) * stride / s; val fh = exp(box.get(i * 4 + 3)) * stride / s
                            // the largest clear face wins (the nearest person's)
                            val b = best
                            if (b != null && b.w * b.h * b.score >= fw * fh * score) continue
                            val cx = (col + box.get(i * 4)) * stride / s + x0; val cy = (row + box.get(i * 4 + 1)) * stride / s + y0
                            val pts = FloatArray(10)
                            for (k in 0 until 5) {
                                pts[2 * k] = (kps.get(i * 10 + 2 * k) + col) * stride / s + x0
                                pts[2 * k + 1] = (kps.get(i * 10 + 2 * k + 1) + row) * stride / s + y0
                            }
                            best = Face(score, cx - fw / 2, cy - fh / 2, fw, fh, pts)
                        }
                    }
                }
            }
            return best
        }

        /** The fingerprint of an upright face picture ([upright]: [FACE] × [FACE] pixels), length 1. */
        fun print(upright: IntArray): FloatArray {
            val n = FACE * FACE
            val x = FloatArray(3 * n)
            for (i in 0 until n) {
                val c = upright[i]
                x[i] = ((c shr 16) and 0xFF).toFloat(); x[n + i] = ((c shr 8) and 0xFF).toFloat(); x[2 * n + i] = (c and 0xFF).toFloat()
            }
            OnnxTensor.createTensor(env, FloatBuffer.wrap(x), longArrayOf(1, 3, FACE.toLong(), FACE.toLong())).use { t ->
                emb.run(mapOf("data" to t)).use { r ->
                    val out = FloatArray(DIM)
                    (r.get(0) as OnnxTensor).floatBuffer.get(out)
                    var len = 0.0
                    for (v in out) len += v * v
                    val l = sqrt(len).toFloat().coerceAtLeast(1e-6f)
                    for (i in out.indices) out[i] /= l
                    return out
                }
            }
        }

        override fun close() {
            try { det.close() } catch (_: Throwable) {}
            try { emb.close() } catch (_: Throwable) {}
        }
    }

    // where the eyes, nose and mouth corners sit in the standard upright face picture
    private val TEMPLATE = floatArrayOf(38.2946f, 51.6963f, 73.5318f, 51.5014f, 56.0252f, 71.7366f, 41.5493f, 92.3655f, 70.7299f, 92.2041f)

    /**
     * The face turned, scaled and moved so its eyes, nose and mouth land on their standard places: a [FACE] × [FACE]
     * picture the fingerprint model expects. [pts] = the face's five points in [px] ([w] × [h]).
     */
    fun upright(px: IntArray, w: Int, h: Int, pts: FloatArray): IntArray {
        // the best turn + scale + move from the face's points to the standard ones (least squares)
        var mx = 0f; var my = 0f; var tx = 0f; var ty = 0f
        for (k in 0 until 5) { mx += pts[2 * k]; my += pts[2 * k + 1]; tx += TEMPLATE[2 * k]; ty += TEMPLATE[2 * k + 1] }
        mx /= 5; my /= 5; tx /= 5; ty /= 5
        var a = 0f; var b = 0f; var d = 0f
        for (k in 0 until 5) {
            val sx = pts[2 * k] - mx; val sy = pts[2 * k + 1] - my
            val ux = TEMPLATE[2 * k] - tx; val uy = TEMPLATE[2 * k + 1] - ty
            a += sx * ux + sy * uy; b += sx * uy - sy * ux; d += sx * sx + sy * sy
        }
        if (d <= 0f) d = 1f
        val c = a / d; val s = b / d            // standard = [c -s; s c] · (face − mean) + standard mean
        val det = (c * c + s * s).coerceAtLeast(1e-9f)
        val out = IntArray(FACE * FACE)
        for (oy in 0 until FACE) for (ox in 0 until FACE) {
            // back from the standard picture to the original one
            val ux = ox - tx; val uy = oy - ty
            val fx = (c * ux + s * uy) / det + mx; val fy = (-s * ux + c * uy) / det + my
            val x0 = Math.floor(fx.toDouble()).toInt(); val y0 = Math.floor(fy.toDouble()).toInt()
            if (x0 < 0 || y0 < 0 || x0 >= w - 1 || y0 >= h - 1) continue   // outside the picture: black
            val ax = fx - x0; val ay = fy - y0
            val p00 = px[y0 * w + x0]; val p10 = px[y0 * w + x0 + 1]; val p01 = px[(y0 + 1) * w + x0]; val p11 = px[(y0 + 1) * w + x0 + 1]
            var rgb = 0
            for (shift in intArrayOf(16, 8, 0)) {
                val v = ((p00 shr shift) and 0xFF) * (1 - ax) * (1 - ay) + ((p10 shr shift) and 0xFF) * ax * (1 - ay) +
                    ((p01 shr shift) and 0xFF) * (1 - ax) * ay + ((p11 shr shift) and 0xFF) * ax * ay
                rgb = rgb or (Math.round(v).coerceIn(0, 255) shl shift)
            }
            out[oy * FACE + ox] = rgb or (0xFF shl 24)
        }
        return out
    }

    /** A fingerprint as bytes (to keep), and how alike a kept one and a fresh one are (-1..1). */
    fun pack(v: FloatArray): ByteArray = ByteArray(v.size) { Math.round(v[it] * 127f).coerceIn(-127, 127).toByte() }

    fun alike(kept: ByteArray, fresh: FloatArray): Float {
        var dot = 0f; var len = 0f
        for (i in 0 until DIM) { val x = kept[i].toFloat(); dot += x * fresh[i]; len += x * x }
        return if (len <= 0f) 0f else dot / sqrt(len)
    }

    fun alike(a: ByteArray, b: ByteArray): Float {
        var dot = 0f; var la = 0f; var lb = 0f
        for (i in 0 until DIM) { val x = a[i].toFloat(); val y = b[i].toFloat(); dot += x * y; la += x * x; lb += y * y }
        return if (la <= 0f || lb <= 0f) 0f else dot / sqrt(la * lb)
    }
}
