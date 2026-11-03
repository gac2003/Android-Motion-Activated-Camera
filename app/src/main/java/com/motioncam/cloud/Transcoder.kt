package com.motioncam.cloud

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Makes a smaller copy of a clip for upload: hardware decode -> GPU scale -> hardware H.264 encode.
 * Audio is copied as-is. Returns false if the clip is already small enough or anything fails
 * (the caller then uploads the original).
 */
object Transcoder {
    fun shrink(ctx: Context, src: Uri, dst: File, shortSide: Int, bitrate: Int): Boolean {
        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var input: InputSurface? = null
        var output: OutputSurface? = null
        try {
            extractor = MediaExtractor().apply { setDataSource(ctx, src, null) }
            var vIdx = -1
            var aIdx = -1
            for (i in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") && vIdx < 0) vIdx = i
                if (mime.startsWith("audio/") && aIdx < 0) aIdx = i
            }
            if (vIdx < 0) return false
            val vf = extractor.getTrackFormat(vIdx)
            val w = vf.getInteger(MediaFormat.KEY_WIDTH)
            val h = vf.getInteger(MediaFormat.KEY_HEIGHT)
            if (minOf(w, h) <= shortSide) return false   // already small enough
            val rotation = if (vf.containsKey(MediaFormat.KEY_ROTATION)) vf.getInteger(MediaFormat.KEY_ROTATION) else 0
            val scale = shortSide.toFloat() / minOf(w, h)
            val ow = ((w * scale).toInt() / 16) * 16
            val oh = ((h * scale).toInt() / 16) * 16
            val fps = if (vf.containsKey(MediaFormat.KEY_FRAME_RATE)) vf.getInteger(MediaFormat.KEY_FRAME_RATE) else 30

            val ef = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, ow, oh).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            }
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            encoder.configure(ef, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            input = InputSurface(encoder.createInputSurface())
            input.makeCurrent()
            encoder.start()
            output = OutputSurface()
            decoder = MediaCodec.createDecoderByType(vf.getString(MediaFormat.KEY_MIME)!!)
            decoder.configure(vf, output.surface, null, 0)
            decoder.start()

            muxer = MediaMuxer(dst.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer.setOrientationHint(rotation)
            val aTrack = if (aIdx >= 0) muxer.addTrack(extractor.getTrackFormat(aIdx)) else -1
            var vTrack = -1
            var muxing = false

            extractor.selectTrack(vIdx)
            val info = MediaCodec.BufferInfo()
            var inDone = false
            var decDone = false
            var encDone = false
            val deadline = System.currentTimeMillis() + 15 * 60_000L
            while (!encDone) {
                if (System.currentTimeMillis() > deadline) throw IllegalStateException("transcode timeout")
                if (!inDone) {
                    val ii = decoder.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val buf = decoder.getInputBuffer(ii)!!
                        val n = extractor.readSampleData(buf, 0)
                        if (n < 0) {
                            decoder.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inDone = true
                        } else {
                            decoder.queueInputBuffer(ii, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                if (!decDone) {
                    val oi = decoder.dequeueOutputBuffer(info, 10_000)
                    if (oi >= 0) {
                        val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        val render = info.size > 0
                        decoder.releaseOutputBuffer(oi, render)
                        if (render) {
                            output.awaitNewImage()
                            output.drawImage()
                            input.setPresentationTime(info.presentationTimeUs * 1000)
                            input.swapBuffers()
                        }
                        if (eos) { encoder.signalEndOfInputStream(); decDone = true }
                    }
                }
                // drain encoder
                while (true) {
                    val ei = encoder.dequeueOutputBuffer(info, if (decDone) 10_000 else 0)
                    if (ei == MediaCodec.INFO_TRY_AGAIN_LATER) break
                    if (ei == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        vTrack = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                        muxing = true
                        continue
                    }
                    if (ei < 0) continue
                    val eb = encoder.getOutputBuffer(ei)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && muxing) {
                        eb.position(info.offset); eb.limit(info.offset + info.size)
                        muxer.writeSampleData(vTrack, eb, info)
                    }
                    encoder.releaseOutputBuffer(ei, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) { encDone = true; break }
                }
            }
            // copy audio unchanged
            if (aTrack >= 0 && muxing) {
                val ax = MediaExtractor().apply { setDataSource(ctx, src, null); selectTrack(aIdx) }
                val buf = ByteBuffer.allocate(256 * 1024)
                val ai = MediaCodec.BufferInfo()
                while (true) {
                    val n = ax.readSampleData(buf, 0)
                    if (n < 0) break
                    ai.set(0, n, ax.sampleTime, if (ax.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                    muxer.writeSampleData(aTrack, buf, ai)
                    ax.advance()
                }
                ax.release()
            }
            muxer.stop()
            return muxing && dst.length() > 0
        } catch (e: Exception) {
            dst.delete()
            return false
        } finally {
            try { decoder?.stop() } catch (_: Exception) {}
            try { decoder?.release() } catch (_: Exception) {}
            try { encoder?.stop() } catch (_: Exception) {}
            try { encoder?.release() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
            try { extractor?.release() } catch (_: Exception) {}
            output?.release()
            input?.release()
        }
    }

    /** EGL window surface on the encoder's input. */
    private class InputSurface(private val surface: Surface) {
        private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var context: EGLContext = EGL14.EGL_NO_CONTEXT
        private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

        init {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val ver = IntArray(2)
            check(EGL14.eglInitialize(display, ver, 0, ver, 1)) { "eglInitialize" }
            val attribs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE)
            val configs = arrayOfNulls<EGLConfig>(1)
            val num = IntArray(1)
            check(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0) && num[0] > 0) { "eglChooseConfig" }
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            eglSurface = EGL14.eglCreateWindowSurface(display, configs[0], surface, intArrayOf(EGL14.EGL_NONE), 0)
        }

        fun makeCurrent() = check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) { "eglMakeCurrent" }
        fun swapBuffers() = EGL14.eglSwapBuffers(display, eglSurface)
        fun setPresentationTime(ns: Long) = EGLExt.eglPresentationTimeANDROID(display, eglSurface, ns)

        fun release() {
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroySurface(display, eglSurface)
                EGL14.eglDestroyContext(display, context)
                EGL14.eglReleaseThread()
                EGL14.eglTerminate(display)
            }
            surface.release()
            display = EGL14.EGL_NO_DISPLAY
        }

        companion object { const val EGL_RECORDABLE_ANDROID = 0x3142 }
    }

    /** Decoder output -> external OES texture, drawn full-screen into the current EGL surface. */
    private class OutputSurface {
        private val tex: Int
        private val st: SurfaceTexture
        val surface: Surface
        private val lock = Object()
        private var available = false
        private val program: Int
        private val matrix = FloatArray(16)
        private val quad: FloatBuffer = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            .apply { put(floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f)); position(0) }

        init {
            val t = IntArray(1)
            GLES20.glGenTextures(1, t, 0)
            tex = t[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            st = SurfaceTexture(tex)
            st.setOnFrameAvailableListener { synchronized(lock) { available = true; lock.notifyAll() } }
            surface = Surface(st)
            program = buildProgram(
                "uniform mat4 uTex;attribute vec4 aPos;attribute vec4 aUv;varying vec2 vUv;" +
                    "void main(){gl_Position=aPos;vUv=(uTex*aUv).xy;}",
                "#extension GL_OES_EGL_image_external : require\nprecision mediump float;varying vec2 vUv;" +
                    "uniform samplerExternalOES sTex;void main(){gl_FragColor=texture2D(sTex,vUv);}")
        }

        fun awaitNewImage() {
            synchronized(lock) {
                val end = System.currentTimeMillis() + 2500
                while (!available) {
                    val left = end - System.currentTimeMillis()
                    if (left <= 0) throw IllegalStateException("frame wait timed out")
                    lock.wait(left)
                }
                available = false
            }
            st.updateTexImage()
        }

        fun drawImage() {
            st.getTransformMatrix(matrix)
            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
            val aPos = GLES20.glGetAttribLocation(program, "aPos")
            val aUv = GLES20.glGetAttribLocation(program, "aUv")
            quad.position(0)
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quad)
            GLES20.glEnableVertexAttribArray(aPos)
            quad.position(2)
            GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 16, quad)
            GLES20.glEnableVertexAttribArray(aUv)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uTex"), 1, false, matrix, 0)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        fun release() {
            surface.release()
            st.release()
        }

        private fun buildProgram(vs: String, fs: String): Int {
            fun shader(type: Int, src: String) = GLES20.glCreateShader(type).also { GLES20.glShaderSource(it, src); GLES20.glCompileShader(it) }
            val p = GLES20.glCreateProgram()
            GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, vs))
            GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, fs))
            GLES20.glLinkProgram(p)
            return p
        }
    }
}
