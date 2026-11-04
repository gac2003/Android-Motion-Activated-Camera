package com.motioncam

import android.graphics.Bitmap

/** Shares frames/flags between the camera engine, the on-screen UI and the web server. */
object FrameHub {
    /** Set by the UI while visible. Receives overlay bitmaps (null = clear the overlay). */
    @Volatile var overlaySink: ((Bitmap?) -> Unit)? = null
    /** UI wants focus peaking (FOCUS control open). */
    @Volatile var peaking = false
    /** UI wants the zone/motion view (ZONES control open). */
    @Volatile var showMotion = false

    /** Number of connected MJPEG viewers. JPEGs are only produced while this is > 0. */
    @Volatile var clients = 0
    /** A high-quality frame was asked for (live connection): full analysis resolution, high JPEG quality. */
    @Volatile var hq = 0

    private val lock = Object()
    private var jpeg: ByteArray? = null
    private var seq = 0L

    fun publishJpeg(bytes: ByteArray) {
        synchronized(lock) { jpeg = bytes; seq++; lock.notifyAll() }
    }

    fun latest(): ByteArray? = synchronized(lock) { jpeg }

    fun waitNext(after: Long, timeoutMs: Long): Pair<Long, ByteArray>? {
        val end = System.currentTimeMillis() + timeoutMs
        synchronized(lock) {
            while (seq <= after || jpeg == null) {
                val left = end - System.currentTimeMillis()
                if (left <= 0) return null
                lock.wait(left)
            }
            return Pair(seq, jpeg!!)
        }
    }

    fun currentSeq(): Long = synchronized(lock) { seq }
}
