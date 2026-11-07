package com.motioncam

import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Sits between the camera and the video encoder when CROP is on: the camera fills [input] with the whole picture,
 * and each frame's [crop] part is drawn into the encoder's surface [output] (sized to the crop) with the camera's
 * own timestamp, so audio stays in sync. Runs on its own thread with the GPU; nothing is scaled up or down.
 */
class CropRenderer(inW: Int, inH: Int, private val output: Surface, private val outW: Int, private val outH: Int, crop: RectF) {
    private val thread = HandlerThread("crop").also { it.start() }
    private val h = Handler(thread.looper)
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var tex = 0
    private lateinit var st: SurfaceTexture
    /** What the camera draws into. */
    lateinit var input: Surface; private set
    private val m = FloatArray(16)
    private val quad: FloatBuffer
    private val uv: FloatBuffer
    @Volatile private var released = false

    init {
        // (x, y) corners: bottom-left, bottom-right, top-left, top-right. The camera's buffer is read as it is in
        // memory (texture t = 0 is its first row, the sensor's top) and the encoder's top row is GL's y = +1, so the
        // clip comes out exactly as the camera would have sent it (sensor orientation; the clip's rotation flag turns
        // it upright). SurfaceTexture's own transform is NOT used: for a camera it also turns the picture for the
        // screen, which the rotation flag would then turn a second time (and squeeze into the wrong shape).
        quad = buf(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
        uv = buf(floatArrayOf(crop.left, crop.bottom, crop.right, crop.bottom, crop.left, crop.top, crop.right, crop.top))
        android.opengl.Matrix.setIdentityM(m, 0)
        var err: Throwable? = null
        val done = CountDownLatch(1)
        h.post {
            try { setup(inW, inH) } catch (t: Throwable) { err = t }
            done.countDown()
        }
        if (!done.await(5, TimeUnit.SECONDS)) err = IllegalStateException("GPU setup timed out")
        err?.let { release(); throw IllegalStateException("Crop unavailable: ${it.message}", it) }
    }

    private fun buf(a: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(a); position(0) }

    private fun setup(inW: Int, inH: Int) {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val v = IntArray(2)
        check(EGL14.eglInitialize(display, v, 0, v, 1)) { "eglInitialize" }
        val attrs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE)
        val cfgs = arrayOfNulls<EGLConfig>(1); val n = IntArray(1)
        check(EGL14.eglChooseConfig(display, attrs, 0, cfgs, 0, 1, n, 0) && n[0] > 0) { "no recordable EGL config" }
        context = EGL14.eglCreateContext(display, cfgs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext" }
        surface = EGL14.eglCreateWindowSurface(display, cfgs[0], output, intArrayOf(EGL14.EGL_NONE), 0)
        check(surface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface ${EGL14.eglGetError()}" }
        check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "eglMakeCurrent" }
        program = link(VS, FS)
        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        tex = t[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        st = SurfaceTexture(tex).apply {
            setDefaultBufferSize(inW, inH)
            setOnFrameAvailableListener({ draw() }, h)
        }
        input = Surface(st)
    }

    private fun draw() {
        if (released) return
        try {
            st.updateTexImage()
            GLES20.glViewport(0, 0, outW, outH)
            GLES20.glUseProgram(program)
            val pos = GLES20.glGetAttribLocation(program, "aPos"); val tc = GLES20.glGetAttribLocation(program, "aTex")
            GLES20.glEnableVertexAttribArray(pos); GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 0, quad)
            GLES20.glEnableVertexAttribArray(tc); GLES20.glVertexAttribPointer(tc, 2, GLES20.GL_FLOAT, false, 0, uv)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uTex"), 1, false, m, 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            EGLExt.eglPresentationTimeANDROID(display, surface, st.timestamp)
            EGL14.eglSwapBuffers(display, surface)
        } catch (_: Exception) {}
    }

    /** Frees the GPU side; [output] stays the encoder's. Waits briefly so the camera can't draw into a dead surface. */
    fun release() {
        if (released) return
        released = true
        val done = CountDownLatch(1)
        h.post {
            try {
                if (::st.isInitialized) { st.setOnFrameAvailableListener(null); st.release() }
                if (::input.isInitialized) input.release()
                if (display != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
                    if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                    EGL14.eglReleaseThread()
                    EGL14.eglTerminate(display)
                }
            } catch (_: Exception) {}
            done.countDown()
        }
        try { done.await(2, TimeUnit.SECONDS) } catch (_: Exception) {}
        thread.quitSafely()
    }

    private fun link(vs: String, fs: String): Int {
        fun shader(type: Int, src: String) = GLES20.glCreateShader(type).also {
            GLES20.glShaderSource(it, src); GLES20.glCompileShader(it)
            val ok = IntArray(1); GLES20.glGetShaderiv(it, GLES20.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "shader: " + GLES20.glGetShaderInfoLog(it) }
        }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p)
        val ok = IntArray(1); GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "link: " + GLES20.glGetProgramInfoLog(p) }
        return p
    }

    private companion object {
        const val EGL_RECORDABLE_ANDROID = 0x3142
        const val VS = "attribute vec4 aPos; attribute vec4 aTex; uniform mat4 uTex; varying vec2 v;\n" +
            "void main() { gl_Position = aPos; v = (uTex * aTex).xy; }"
        const val FS = "#extension GL_OES_EGL_image_external : require\nprecision mediump float; varying vec2 v; uniform samplerExternalOES s;\n" +
            "void main() { gl_FragColor = texture2D(s, v); }"
    }
}
