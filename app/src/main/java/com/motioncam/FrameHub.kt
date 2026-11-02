package com.motioncam

import android.graphics.Bitmap

/** Shares the latest frames between the camera engine, the on-screen preview and the web server. */
object FrameHub {
    /** Set by the UI while it is visible; null means nobody is watching the screen. */
    @Volatile var previewSink: ((Bitmap) -> Unit)? = null

    /** Number of connected MJPEG viewers. JPEGs are only produced while this is > 0. */
    @Volatile var clients = 0

    private val lock = Object()
    private var jpeg: ByteArray? = null
    private var seq = 0L

    fun publishJpeg(bytes: ByteArray) {
        synchronized(lock) { jpeg = bytes; seq++; lock.notifyAll() }
    }

    fun latest(): ByteArray? = synchronized(lock) { jpeg }

    /** Blocks until a frame newer than [after] exists. Returns (seq, bytes) or null on timeout. */
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
