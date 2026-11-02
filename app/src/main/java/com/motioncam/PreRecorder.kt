package com.motioncam

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import java.io.FileDescriptor
import java.nio.ByteBuffer
import kotlin.math.abs

/**
 * Pre-recording: the hardware encoder runs continuously while monitoring and its output is kept in a small
 * rolling memory buffer (the last [preMs] + up to one key-frame interval). Nothing is written to storage until
 * a clip starts; then the buffer goes first, followed by the live stream. Audio (optional) is encoded the same
 * way on the camera's clock so both stay in sync.
 */
class PreRecorder(
    width: Int, height: Int, fps: Int, bitrate: Int, hevc: Boolean,
    private val preMs: Int,
    private val withAudio: Boolean,
    /** true if camera timestamps use elapsedRealtime (SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME). */
    private val realtimeClock: Boolean,
) {
    private class Pkt(val data: ByteArray, val ptsUs: Long, val flags: Int) {
        val key get() = flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
    }

    private val lock = Object()
    private val vq = ArrayDeque<Pkt>()
    private val aq = ArrayDeque<Pkt>()
    private var videoFormat: MediaFormat? = null
    @Volatile private var audioFormat: MediaFormat? = null
    private val encThread = HandlerThread("pre-encoder").also { it.start() }
    private val encoder: MediaCodec
    /** Persistent encoder input surface: survives camera-session rebuilds. */
    val surface: Surface = MediaCodec.createPersistentInputSurface()
    @Volatile var failed = false; private set

    // clip state (guarded by lock)
    private var muxer: MediaMuxer? = null
    private var wantStart = false
    private var startFromLatestKey = false
    private var pendingFd: FileDescriptor? = null
    private var pendingRot = 0
    private var vTrack = -1
    private var aTrack = -1
    private var clipStartPts = -1L
    private var lastVPts = -1L
    private var lastAPts = -1L
    private var videoSamples = 0
    @Volatile var bytesWritten = 0L; private set
    private val info = MediaCodec.BufferInfo()

    // audio
    @Volatile private var capturing = false
    private var audioThread: Thread? = null

    init {
        val mime = if (hevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
        val f = MediaFormat.createVideoFormat(mime, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            // a key frame is needed to start a clip; 0.5 s pre-record needs them twice as often
            setFloat(MediaFormat.KEY_I_FRAME_INTERVAL, if (preMs <= 500) 0.5f else 1f)
        }
        encoder = MediaCodec.createEncoderByType(mime)
        encoder.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {}
            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, bi: MediaCodec.BufferInfo) {
                try {
                    if (bi.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && bi.size > 0) {
                        val buf = codec.getOutputBuffer(index)!!
                        val data = ByteArray(bi.size)
                        buf.position(bi.offset)
                        buf.get(data, 0, bi.size)
                        onVideo(Pkt(data, bi.presentationTimeUs, bi.flags))
                    }
                } catch (_: Exception) {
                } finally {
                    try { codec.releaseOutputBuffer(index, false) } catch (_: Exception) {}
                }
            }
            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) { failed = true }
            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) { synchronized(lock) { videoFormat = format } }
        }, Handler(encThread.looper))
        encoder.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.setInputSurface(surface)
        encoder.start()
    }

    private fun nowNs() = if (realtimeClock) SystemClock.elapsedRealtimeNanos() else System.nanoTime()

    /** Buffering (and microphone capture) only while monitoring; clears stale frames when starting. */
    fun setCapturing(on: Boolean) {
        if (on == capturing) return
        capturing = on
        synchronized(lock) { vq.clear(); aq.clear() }
        if (on && withAudio) {
            audioThread = Thread({ audioLoop() }, "pre-audio").also { it.start() }
        } else if (!on) {
            audioThread?.let { try { it.join(1500) } catch (_: Exception) {} }
            audioThread = null
        }
    }

    // ------------------------------------------------------------------ clips

    /**
     * Starts a clip. With [preRoll] the buffered seconds come first; without it (continuing a split clip)
     * it starts at the most recent key frame.
     */
    fun startClip(fd: FileDescriptor, rotation: Int, preRoll: Boolean) = synchronized(lock) {
        closeMuxerLocked()
        pendingFd = fd
        pendingRot = rotation
        startFromLatestKey = !preRoll
        wantStart = true
        tryStartLocked()
    }

    private fun tryStartLocked() {
        val vf = videoFormat ?: return
        val fd = pendingFd ?: return
        val m = MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        m.setOrientationHint(pendingRot)
        vTrack = m.addTrack(vf)
        aTrack = audioFormat?.let { m.addTrack(it) } ?: -1
        m.start()
        muxer = m
        wantStart = false
        pendingFd = null
        clipStartPts = -1L
        lastVPts = -1L
        lastAPts = -1L
        videoSamples = 0
        bytesWritten = 0L
        // write what is buffered, starting at a key frame
        val keys = vq.withIndex().filter { it.value.key }.map { it.index }
        val from = if (startFromLatestKey) keys.lastOrNull() else keys.firstOrNull()
        if (from != null) {
            clipStartPts = vq.elementAt(from).ptsUs
            for (i in from until vq.size) writeLocked(vq.elementAt(i), true)
            for (p in aq) if (p.ptsUs >= clipStartPts) writeLocked(p, false)
        }
    }

    /** @return true if the clip contains usable video (> 1 s). */
    fun stopClip(): Boolean = synchronized(lock) {
        wantStart = false
        pendingFd = null
        val ok = videoSamples > 0 && lastVPts - clipStartPts > 1_000_000L
        closeMuxerLocked() && ok
    }

    private fun closeMuxerLocked(): Boolean {
        val m = muxer ?: return false
        muxer = null
        var ok = true
        try { m.stop() } catch (_: Exception) { ok = false }
        try { m.release() } catch (_: Exception) {}
        return ok
    }

    private fun writeLocked(p: Pkt, video: Boolean) {
        val m = muxer ?: return
        if (video) {
            if (clipStartPts < 0) { if (!p.key) return; clipStartPts = p.ptsUs }   // must begin on a key frame
            if (p.ptsUs <= lastVPts) return
            lastVPts = p.ptsUs
            videoSamples++
        } else {
            if (aTrack < 0 || clipStartPts < 0 || p.ptsUs < clipStartPts || p.ptsUs <= lastAPts) return
            lastAPts = p.ptsUs
        }
        info.set(0, p.data.size, p.ptsUs, p.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME)
        try {
            m.writeSampleData(if (video) vTrack else aTrack, ByteBuffer.wrap(p.data), info)
            bytesWritten += p.data.size
        } catch (_: Exception) {}
    }

    private fun onVideo(p: Pkt) = synchronized(lock) {
        if (wantStart && muxer == null) tryStartLocked()
        if (muxer != null) writeLocked(p, true)
        if (!capturing) return@synchronized
        vq.addLast(p)
        // keep from the newest key frame that is at least preMs old
        val cutoff = p.ptsUs - preMs * 1000L
        var keep = 0
        for ((i, pk) in vq.withIndex()) { if (pk.ptsUs > cutoff) break; if (pk.key) keep = i }
        repeat(keep) { vq.removeFirst() }
        while (vq.size > 400) vq.removeFirst()   // hard memory cap
        val first = vq.firstOrNull()?.ptsUs ?: return@synchronized
        while (aq.isNotEmpty() && aq.first().ptsUs < first - 200_000L) aq.removeFirst()
    }

    private fun onAudio(p: Pkt) = synchronized(lock) {
        if (muxer != null) writeLocked(p, false)
        aq.addLast(p)
        while (aq.size > 600) aq.removeFirst()
    }

    // ------------------------------------------------------------------ audio

    private fun audioLoop() {
        val sr = 44_100
        var rec: AudioRecord? = null
        var enc: MediaCodec? = null
        try {
            val min = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            @Suppress("MissingPermission")
            rec = AudioRecord(MediaRecorder.AudioSource.CAMCORDER, sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 16_384))
            if (rec.state != AudioRecord.STATE_INITIALIZED) return
            val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sr, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 96_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 8192)
            }
            enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            enc.start()
            rec.startRecording()
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) return   // mic busy (e.g. a call)
            val pcm = ByteArray(2048)
            val bi = MediaCodec.BufferInfo()
            var anchorNs = -1L
            var frames = 0L
            while (capturing) {
                val n = rec.read(pcm, 0, pcm.size)
                if (n <= 0) { if (n < 0) break else continue }
                val now = nowNs()
                val samples = n / 2
                val expected = now - samples * 1_000_000_000L / sr
                val predicted = anchorNs + frames * 1_000_000_000L / sr
                // re-anchor to the system clock if the audio clock drifted (keeps lip-sync over hours)
                if (anchorNs < 0 || abs(predicted - expected) > 100_000_000L) anchorNs = expected - frames * 1_000_000_000L / sr
                val ptsUs = (anchorNs + frames * 1_000_000_000L / sr) / 1000
                frames += samples
                val ii = enc.dequeueInputBuffer(10_000)
                if (ii >= 0) {
                    val ib = enc.getInputBuffer(ii)!!
                    ib.clear()
                    ib.put(pcm, 0, n)
                    enc.queueInputBuffer(ii, 0, n, ptsUs, 0)
                }
                while (true) {
                    val oi = enc.dequeueOutputBuffer(bi, 0)
                    if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { audioFormat = enc.outputFormat; continue }
                    if (oi < 0) break
                    if (bi.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && bi.size > 0) {
                        val ob = enc.getOutputBuffer(oi)!!
                        val data = ByteArray(bi.size)
                        ob.position(bi.offset)
                        ob.get(data, 0, bi.size)
                        onAudio(Pkt(data, bi.presentationTimeUs, bi.flags))
                    }
                    enc.releaseOutputBuffer(oi, false)
                }
            }
        } catch (_: Exception) {
        } finally {
            try { rec?.stop() } catch (_: Exception) {}
            try { rec?.release() } catch (_: Exception) {}
            try { enc?.stop() } catch (_: Exception) {}
            try { enc?.release() } catch (_: Exception) {}
        }
    }

    fun release() {
        setCapturing(false)
        synchronized(lock) { closeMuxerLocked() }
        try { encoder.stop() } catch (_: Exception) {}
        try { encoder.release() } catch (_: Exception) {}
        try { surface.release() } catch (_: Exception) {}
        encThread.quitSafely()
    }
}
