package com.motioncam

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.display.DisplayManager
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaRecorder
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.SystemClock
import android.util.Range
import android.util.Size
import android.view.Display
import android.view.Surface
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executor
import kotlin.math.ceil
import kotlin.math.max

enum class Mode { OFF, STARTING, PREVIEW, WATCHING, RECORDING, PAUSED_HOT, PAUSED_STORAGE, ERROR }

data class Status(
    val mode: Mode = Mode.OFF,
    val monitoring: Boolean = false,
    val message: String = "",
    val lens: String = "",
    val format: String = "",
    val recordingSinceWall: Long = 0,
    val lastMotionWall: Long = 0,
    val motionBlocks: Int = 0,
    val batteryPct: Int = -1,
    val tempC: Float = 0f,
    val charging: Boolean = false,
)

/** Status channel (engine -> UI/service). Listeners run on the engine thread. */
object StatusBus {
    @Volatile var status = Status()
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(Status) -> Unit>()
    fun add(l: (Status) -> Unit) { listeners.addIfAbsent(l) }
    fun remove(l: (Status) -> Unit) { listeners.remove(l) }
    fun publish(s: Status) { status = s; for (l in listeners) l(s) }
}

/**
 * One camera engine per process, shared by the on-screen preview and the monitoring service, so camera
 * settings never get out of sync. The camera is open while anyone holds it.
 */
object EngineHost {
    private var engine: CameraEngine? = null
    private val holders = HashSet<String>()

    @Synchronized fun get(ctx: Context): CameraEngine =
        engine ?: CameraEngine(ctx.applicationContext, Prefs(ctx)).also { engine = it }

    @Synchronized fun peek(): CameraEngine? = engine

    @Synchronized fun acquire(ctx: Context, who: String): CameraEngine {
        holders += who
        return get(ctx).also { it.setActive(true) }
    }

    @Synchronized fun release(who: String) {
        holders -= who
        if (holders.isEmpty()) engine?.setActive(false)
    }
}

/**
 * Owns the camera. The capture session always contains:
 *  - a YUV stream (motion detection, focus peaking, web stream),
 *  - a persistent encoder surface (a MediaRecorder is attached to it per clip),
 *  - the on-screen preview surface when the UI is visible.
 * Recording therefore starts by simply adding the encoder surface to the repeating request, using a
 * MediaRecorder that was prepared in advance ("armed"). No session rebuild, very little latency.
 * Everything runs on one handler thread; frame analysis runs on a second one.
 */
class CameraEngine(private val ctx: Context, private val prefs: Prefs) {
    private val mgr = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val h = Handler(HandlerThread("cam-engine").also { it.start() }.looper)
    private val executor = Executor { h.post(it) }
    private val ah = Handler(HandlerThread("cam-analysis").also { it.start() }.looper)
    private val display: Display? = (ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY)

    /** Called (engine thread) when monitoring had to stop, e.g. low battery. */
    @Volatile var onMonitoringStopped: ((String) -> Unit)? = null

    @Volatile var mode = Mode.OFF; private set
    @Volatile var monitoring = false; private set
    @Volatile var caps: ProCaps? = null; private set

    // live values from the camera (for showing what AUTO is doing)
    @Volatile var liveIso = 0; private set
    @Volatile var liveShutterNs = 0L; private set
    @Volatile var liveFocusDiopter = -1f; private set
    @Volatile var liveKelvin = 0; private set
    /** Exposure meter (EV vs mid-grey) of the current picture; shown on the EV button in manual exposure. */
    @Volatile var liveMeterEv = Float.NaN; private set
    private var lastMeter = 0L
    private var frameBuf: Frame? = null
    private var lastFreeCheck = 0L
    private var freeCache = Long.MAX_VALUE
    private var lastKelvinCalc = 0L

    private var active = false
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var persistent: Surface? = null
    private var recordSize = Size(1920, 1080)
    private var effectiveFps = 30
    private var sessionGen = 0
    private var comboIndex = 0
    @Volatile private var hwPreview = false
    private var activeSig = ""
    private var message = ""
    private var retryCount = 0

    // preview surfaces (owned by the UI's TextureView)
    @Volatile private var previewSt: SurfaceTexture? = null
    @Volatile private var sessionSt: SurfaceTexture? = null
    private val surfaces = HashMap<SurfaceTexture, Surface>()
    private val deferred = ArrayList<Pair<SurfaceTexture, () -> Unit>>()
    private var rebuildPending = false

    // recording
    private class Armed(val rec: MediaRecorder, val pending: ClipStore.Pending, val rotation: Int, val audio: Boolean)
    private var armFailedAt = 0L
    @Volatile private var hitSizeLimit = false
    private var armed: Armed? = null
    private var recorder: MediaRecorder? = null
    /** Pre-record mode: continuous encoder + memory buffer instead of a per-clip MediaRecorder. */
    private var pre: PreRecorder? = null
    private var preClip: ClipStore.Pending? = null
    private var pending: ClipStore.Pending? = null
    private var recordStartedElapsed = 0L
    private var recordStartedWall = 0L

    // analysis-thread state
    private val detector = MotionDetector()
    @Volatile private var resetDetector = true
    private var consecutive = 0
    private var lastMotionRun = 0L
    private var lastOverlayRun = 0L
    private var lastJpegRun = 0L
    private var overlayShown = false
    private var zonesSrc: String? = null
    private var zoneBlocks: BooleanArray? = null
    @Volatile private var motionBlocks = 0
    @Volatile private var lastFrameAt = SystemClock.elapsedRealtime()
    @Volatile private var lastMotionElapsed = 0L
    @Volatile private var lastMotionWall = 0L

    // protection
    private var hotSince = 0L
    private var lastCleanup = 0L
    @Volatile private var cleaning = false

    // ================================================================== public API (any thread)

    fun setActive(on: Boolean) = h.post {
        if (on && !active) {
            active = true
            openCamera()
            h.removeCallbacks(tick); h.postDelayed(tick, 1000)
        } else if (!on && active) {
            active = false
            h.removeCallbacks(tick)
            if (mode == Mode.RECORDING) stopRecording(rearm = false)
            closeCamera()
            mode = Mode.OFF
            message = ""
            publish()
        }
    }

    fun setMonitoring(on: Boolean) = h.post { setMonitoringInternal(on) }

    /** UI preview surface (null = UI hidden). The engine never releases it. */
    fun setPreview(st: SurfaceTexture?) = h.post {
        if (previewSt === st) return@post
        previewSt = st
        rebuildSession()
    }

    /** The UI's surface is being destroyed; [done] runs once the camera no longer uses it. */
    fun detachPreview(st: SurfaceTexture, done: () -> Unit) = h.post {
        val inUse = sessionSt === st && device != null
        if (previewSt === st) {
            previewSt = null
            rebuildSession()
        }
        if (inUse) deferred += Pair(st, done) else { surfaces.remove(st)?.release(); done() }
    }

    /**
     * Rebuilds the session for a changed preview surface. Never during a clip: the recording keeps
     * running untouched and the rebuild happens when the clip ends (the UI meanwhile gets a CPU preview).
     */
    private fun rebuildSession() {
        if (device == null) return
        if (mode == Mode.RECORDING) { rebuildPending = true; return }
        rebuildPending = false
        comboIndex = 0
        startSession()
    }

    /** The UI's preview surface was resized (its frame size reset): reconnect it with the right size. */
    fun previewResized(st: SurfaceTexture) = h.post { if (sessionSt === st) rebuildSession() }

    /** Re-reads settings: restarts the camera only if the lens/format changed. */
    fun applyConfig() = h.post {
        if (!active) return@post
        if (signature() != activeSig) restartCamera("Applying new settings") else pushRepeating()
    }

    /** Re-sends the repeating request with the current pro controls (no interruption). */
    fun applyPro() = h.post { pushRepeating() }

    // ================================================================== state

    private fun setMonitoringInternal(on: Boolean) {
        if (on == monitoring) return
        monitoring = on
        if (on) {
            ClipStore.deleteOrphans(ctx)
            pre?.setCapturing(true)
            retryCount = 0
            if (mode == Mode.PREVIEW) {
                mode = Mode.WATCHING
                message = "Watching for motion"
                resetDetector = true
                arm()
                pushRepeating()
            }
        } else {
            if (mode == Mode.RECORDING) stopRecording(rearm = false)
            disarm()
            pre?.setCapturing(false)
            when (mode) {
                Mode.WATCHING, Mode.PAUSED_STORAGE -> { mode = Mode.PREVIEW; message = "" }
                Mode.PAUSED_HOT -> if (active) openCamera()
                else -> {}
            }
            pushRepeating()
        }
        publish()
    }

    private fun stopMonitoringBecause(why: String) {
        setMonitoringInternal(false)
        message = why
        publish()
        onMonitoringStopped?.invoke(why)
    }

    // ================================================================== camera lifecycle

    private fun signature() =
        "${prefs.camera}|${prefs.resHeight}|${prefs.fps}|${prefs.hevc}|${prefs.bitrateMbps}|${prefs.audio}|${prefs.preRecordMs}"

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        closeCamera()
        if (!active) return
        if (ctx.checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            mode = Mode.ERROR; message = "Camera permission needed"; publish(); return
        }
        mode = Mode.STARTING
        message = "Opening camera…"
        publish()
        try {
            val c = ProCaps.forPrefs(ctx, prefs) ?: throw IllegalStateException("No camera found")
            caps = c
            activeSig = signature()
            effectiveFps = if (c.supportsFps(prefs.fps)) prefs.fps else 30
            recordSize = c.recordSize(prefs.resHeight)
            if (prefs.preRecordMs > 0) {
                pre = try {
                    PreRecorder(recordSize.width, recordSize.height, effectiveFps, bitrate(), prefs.hevc,
                        prefs.preRecordMs, audioAllowed())
                } catch (e: Exception) { message = "Pre-record unavailable: ${e.message}"; null }
            }
            persistent = pre?.surface ?: createPersistentSurface()
            comboIndex = 0
            mgr.openCamera(c.lens.logicalId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (!active) { camera.close(); return }
                    device = camera
                    startSession()
                }
                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    if (device === camera) { device = null; scheduleRetry("Camera disconnected (another app may be using it)") }
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    if (device === camera || device == null) { device = null; scheduleRetry("Camera error $error") }
                }
            }, h)
        } catch (e: Exception) {
            scheduleRetry("Cannot open camera: ${e.message}")
        }
    }

    private fun scheduleRetry(why: String) {
        if (!active) return
        if (mode == Mode.RECORDING) stopRecording(rearm = false)
        closeCamera()
        retryCount++
        mode = Mode.ERROR
        // keep trying (e.g. another app borrowed the camera); back off from 5 s to 60 s to save power
        val delay = (5000L * retryCount).coerceAtMost(60_000L)
        message = "$why — retrying in ${delay / 1000} s"
        publish()
        h.postDelayed({ if (active && mode == Mode.ERROR) openCamera() }, delay)
    }

    private fun restartCamera(why: String) {
        if (mode == Mode.RECORDING) stopRecording(rearm = false)
        message = why
        openCamera()
    }

    private fun closeCamera() {
        disarm()
        sessionGen++
        try { session?.close() } catch (_: Exception) {}
        session = null
        try { device?.close() } catch (_: Exception) {}
        device = null
        try { reader?.close() } catch (_: Exception) {}
        reader = null
        if (pre != null) {
            preClip?.let { ClipStore.finish(ctx, it, pre?.stopClip() == true, recordStartedWall) }
            preClip = null
            pre?.release()   // also releases its surface
            pre = null
        } else {
            try { persistent?.release() } catch (_: Exception) {}
        }
        persistent = null
        sessionSt = null
        hwPreview = false
        rebuildPending = false
        runDeferred(keep = null)
    }

    private fun runDeferred(keep: SurfaceTexture?) {
        val it = deferred.iterator()
        while (it.hasNext()) {
            val (st, done) = it.next()
            if (st === keep) continue
            it.remove()
            surfaces.remove(st)?.release()
            try { done() } catch (_: Exception) {}
        }
    }

    // ================================================================== recorder

    private fun newRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else @Suppress("DEPRECATION") MediaRecorder()

    private fun bitrate(): Int {
        if (prefs.bitrateMbps > 0) return prefs.bitrateMbps * 1_000_000
        val r = recordSize.height / 1080f
        var mbps = 8f * r * r * (effectiveFps / 30f)
        if (prefs.hevc) mbps *= 0.6f
        return (mbps.coerceIn(1.5f, 60f) * 1_000_000).toInt()
    }

    private fun configure(rec: MediaRecorder, audio: Boolean, surface: Surface, output: (MediaRecorder) -> Unit) {
        if (audio) rec.setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
        rec.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        output(rec)
        rec.setVideoEncodingBitRate(bitrate())
        rec.setVideoFrameRate(effectiveFps)
        rec.setVideoSize(recordSize.width, recordSize.height)
        rec.setVideoEncoder(if (prefs.hevc) MediaRecorder.VideoEncoder.HEVC else MediaRecorder.VideoEncoder.H264)
        if (audio) {
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setAudioEncodingBitRate(128_000)
            rec.setAudioSamplingRate(44_100)
        }
        rec.setInputSurface(surface)
    }

    /** Encoder input surface that stays in the camera session; a throw-away recorder sets its size. */
    private fun createPersistentSurface(): Surface {
        val s = MediaCodec.createPersistentInputSurface()
        val f = File(ctx.cacheDir, "init.mp4")
        val r = newRecorder()
        try {
            configure(r, false, s) { it.setOutputFile(f.path) }
            r.prepare()
        } finally {
            try { r.release() } catch (_: Exception) {}
            f.delete()
        }
        return s
    }

    private fun audioAllowed() = prefs.audio &&
        ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Prepares the next clip's recorder in advance so motion only has to call start(). */
    private fun arm(noAudio: Boolean = false) {
        if (pre != null) return   // pre-record mode needs no per-clip recorder
        if (armed != null || !monitoring || mode == Mode.PAUSED_STORAGE) return
        if (SystemClock.elapsedRealtime() - armFailedAt < 10_000 && !noAudio) return   // don't hammer a failing encoder
        val ps = persistent ?: return
        var p: ClipStore.Pending? = null
        var rec: MediaRecorder? = null
        try {
            p = ClipStore.create(ctx)
            rec = newRecorder()
            val pend = p
            val audio = audioAllowed() && !noAudio
            configure(rec, audio, ps) { it.setOutputFile(pend.pfd.fileDescriptor) }
            val rot = recordRotation()
            rec.setOrientationHint(rot)
            // MP4 files over 4 GB are unreliable, and never let a clip eat the last of the phone's storage
            val cap = minOf(3_800_000_000L, ClipStore.freeBytes() - 400_000_000L).coerceAtLeast(50_000_000L)
            rec.setMaxFileSize(cap)
            val r = rec
            rec.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) h.post { onSizeLimit(r) }
            }
            rec.prepare()
            armed = Armed(rec, p, rot, audio)
            armFailedAt = 0L
        } catch (e: Exception) {
            try { rec?.release() } catch (_: Exception) {}
            p?.let { ClipStore.finish(ctx, it, false, 0) }
            armFailedAt = SystemClock.elapsedRealtime()
            message = "Cannot prepare recorder: ${e.message}"
        }
    }

    /** The recorder stopped itself at the size cap: keep that file and continue in a new one if space allows. */
    private fun onSizeLimit(rec: MediaRecorder) {
        if (recorder !== rec) return
        hitSizeLimit = true
        stopRecording(rearm = false)
        freeCache = ClipStore.freeBytes()
        lastFreeCheck = SystemClock.elapsedRealtime()
        if (freeCache > 600_000_000L) { arm(); startRecording() } else enterStoragePause()
    }

    private fun disarm() {
        val a = armed ?: return
        armed = null
        try { a.rec.reset() } catch (_: Exception) {}
        try { a.rec.release() } catch (_: Exception) {}
        ClipStore.finish(ctx, a.pending, false, 0)
    }

    private fun startRecording(preRoll: Boolean = true) {
        if (!monitoring || mode != Mode.WATCHING) return
        val pr = pre
        if (pr != null) {
            val p = try { ClipStore.create(ctx) } catch (e: Exception) { message = "Cannot create clip: ${e.message}"; publish(); return }
            pr.startClip(p.pfd.fileDescriptor, recordRotation(), preRoll)
            preClip = p
            recordStartedElapsed = SystemClock.elapsedRealtime()
            recordStartedWall = System.currentTimeMillis() - if (preRoll) prefs.preRecordMs else 0
            mode = Mode.RECORDING
            message = "Recording"
            pushRepeating()
            publish()
            return
        }
        if (armed == null) arm()
        var a = armed ?: return
        armed = null
        try {
            a.rec.start()
        } catch (e: Exception) {
            try { a.rec.release() } catch (_: Exception) {}
            ClipStore.finish(ctx, a.pending, false, 0)
            // the microphone may be busy (phone call): record without sound rather than lose the clip
            var ok = false
            if (a.audio) {
                arm(noAudio = true)
                val b = armed
                if (b != null) {
                    armed = null
                    try { b.rec.start(); a = b; ok = true } catch (_: Exception) {
                        try { b.rec.release() } catch (_: Exception) {}
                        ClipStore.finish(ctx, b.pending, false, 0)
                    }
                }
            }
            if (!ok) {
                message = "Recorder failed: ${e.message}"
                publish()
                h.postDelayed({ arm() }, 500)
                return
            }
        }
        hitSizeLimit = false
        recorder = a.rec
        pending = a.pending
        recordStartedElapsed = SystemClock.elapsedRealtime()
        recordStartedWall = System.currentTimeMillis()
        mode = Mode.RECORDING
        message = "Recording"
        pushRepeating()
        publish()
    }

    private fun stopRecording(rearm: Boolean) {
        val pc = preClip
        if (pc != null) {
            preClip = null
            mode = if (monitoring) Mode.WATCHING else Mode.PREVIEW
            message = if (monitoring) "Watching for motion" else ""
            pushRepeating()
            val ok = pre?.stopClip() == true
            ClipStore.finish(ctx, pc, ok, recordStartedWall)
            publish()
            if (rebuildPending) rebuildSession()
            maybeCleanup(force = false)
            return
        }
        val rec = recorder ?: return
        recorder = null
        mode = if (monitoring) Mode.WATCHING else Mode.PREVIEW
        message = if (monitoring) "Watching for motion" else ""
        pushRepeating()   // stop feeding the encoder first
        var ok = SystemClock.elapsedRealtime() - recordStartedElapsed > 1000
        try { rec.stop() } catch (_: Exception) { if (!hitSizeLimit) ok = false }   // after a size cap the file is already finalised
        hitSizeLimit = false
        try { rec.release() } catch (_: Exception) {}
        pending?.let { ClipStore.finish(ctx, it, ok, recordStartedWall) }
        pending = null
        publish()
        if (rebuildPending) rebuildSession()
        if (rearm && monitoring) h.post { arm() }
        maybeCleanup(force = false)
    }

    private fun maybeCleanup(force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (cleaning || (!force && now - lastCleanup < 10 * 60_000L)) return
        if (prefs.keepDays <= 0 && prefs.maxStorageGb <= 0 && !prefs.autoDeleteLow) return
        lastCleanup = now
        cleaning = true
        Thread { try { ClipStore.cleanup(ctx, prefs) } finally { cleaning = false } }.start()
    }

    // ================================================================== session

    private class Combo(val preview: Size?, val yuv: Size)

    private fun combos(c: ProCaps): List<Combo> {
        val list = ArrayList<Combo>()
        if (previewSt != null) {
            list += Combo(c.previewSize(1920), c.analysisSize(1280))
            list += Combo(c.previewSize(1280), c.analysisSize(640))
        }
        // without the on-screen preview a small stream is enough for motion detection (less ISP/CPU work)
        val big = prefs.serverOn && prefs.streamHd
        list += Combo(null, c.analysisSize(if (big) 1280 else 640))
        list += Combo(null, c.analysisSize(if (big) 640 else 320))
        return list
    }

    private fun ensureReader(size: Size) {
        val r = reader
        if (r != null && r.width == size.width && r.height == size.height) return
        try { r?.close() } catch (_: Exception) {}
        reader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 3).also {
            it.setOnImageAvailableListener({ rr -> onImage(rr) }, ah)
        }
    }

    private fun surfaceFor(st: SurfaceTexture): Surface = surfaces.getOrPut(st) { Surface(st) }

    private fun startSession() {
        val dev = device ?: return
        val c = caps ?: return
        val ps = persistent ?: return
        val phys = c.lens.physicalId
        try { session?.close() } catch (_: Exception) {}
        session = null
        val list = combos(c)
        while (comboIndex < list.size) {
            val combo = list[comboIndex]
            try {
                ensureReader(combo.yuv)
                fun oc(s: Surface) = OutputConfiguration(s).also { if (phys != null) it.setPhysicalCameraId(phys) }
                val outs = arrayListOf(oc(reader!!.surface), oc(ps))
                var st: SurfaceTexture? = null
                if (combo.preview != null) {
                    st = previewSt!!
                    st.setDefaultBufferSize(combo.preview.width, combo.preview.height)
                    outs += oc(surfaceFor(st))
                }
                val gen = ++sessionGen
                val usedSt = st
                val cfg = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outs, executor,
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) = onSessionConfigured(gen, s, usedSt)
                        override fun onConfigureFailed(s: CameraCaptureSession) {
                            if (gen != sessionGen) return
                            comboIndex++
                            startSession()
                        }
                    })
                val supported = try { dev.isSessionConfigurationSupported(cfg) } catch (e: Exception) { true }
                if (!supported) { comboIndex++; continue }
                sessionSt = usedSt
                hwPreview = usedSt != null
                dev.createCaptureSession(cfg)
                return
            } catch (e: Exception) {
                comboIndex++
            }
        }
        scheduleRetry("No working camera stream combination")
    }

    private fun onSessionConfigured(gen: Int, s: CameraCaptureSession, st: SurfaceTexture?) {
        if (gen != sessionGen || !active) { try { s.close() } catch (_: Exception) {}; return }
        session = s
        runDeferred(keep = st)
        resetDetector = true
        lastFrameAt = SystemClock.elapsedRealtime()
        retryCount = 0
        if (mode == Mode.STARTING || mode == Mode.ERROR || mode == Mode.OFF || mode == Mode.PAUSED_HOT) {
            mode = if (monitoring) Mode.WATCHING else Mode.PREVIEW
            message = if (monitoring) "Watching for motion" else ""
        }
        pushRepeating()
        pre?.setCapturing(monitoring)
        if (monitoring && mode == Mode.WATCHING) arm()
        publish()
    }

    private val captureCb = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, res: TotalCaptureResult) {
            try { readResult(res) } catch (_: Exception) {}
        }

        private fun readResult(res: TotalCaptureResult) {
            res.get(CaptureResult.SENSOR_SENSITIVITY)?.let { liveIso = it }
            res.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { liveShutterNs = it }
            res.get(CaptureResult.LENS_FOCUS_DISTANCE)?.let { liveFocusDiopter = it }
            val now = SystemClock.elapsedRealtime()
            if (now - lastKelvinCalc > 500 && FrameHub.overlaySink != null) {
                lastKelvinCalc = now
                val g = res.get(CaptureResult.COLOR_CORRECTION_GAINS)
                liveKelvin = if (g != null) caps?.estimateKelvin(g) ?: 0 else 0
            }
        }
    }

    private fun lowPowerIdle() = pre == null && monitoring && mode != Mode.RECORDING && sessionSt == null && FrameHub.overlaySink == null && prefs.lowPowerIdle

    private fun pushRepeating() {
        val s = session ?: return
        val dev = device ?: return
        val c = caps ?: return
        val rd = reader ?: return
        try {
            val b = dev.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            b.addTarget(rd.surface)
            sessionSt?.let { b.addTarget(surfaceFor(it)) }
            // pre-record mode feeds the encoder the whole time we are monitoring
            if (mode == Mode.RECORDING || (pre != null && monitoring)) persistent?.let { b.addTarget(it) }
            b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)

            val low = lowPowerIdle()
            b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, pickFpsRange(c, low))
            val fps = if (low) 10 else effectiveFps

            if (prefs.exposureAuto || !c.manualSensor) {
                b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, prefs.evIndex.coerceIn(c.evMin, c.evMax))
            } else {
                val (lo, hi) = c.shutterRange(effectiveFps)
                val shutter = prefs.shutterNs.coerceIn(lo, hi)
                b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                b.set(CaptureRequest.SENSOR_SENSITIVITY, prefs.iso.coerceIn(c.isoMin, c.isoMax))
                b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, shutter)
                b.set(CaptureRequest.SENSOR_FRAME_DURATION, max(1_000_000_000L / fps, shutter))
            }

            if (prefs.focusAuto || !c.canManualFocus) {
                b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            } else {
                b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                b.set(CaptureRequest.LENS_FOCUS_DISTANCE, (1f - prefs.focusPos.coerceIn(0f, 1f)) * c.minFocusDiopter)
            }

            if (prefs.wbAuto) {
                b.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
            } else {
                b.set(CaptureRequest.CONTROL_AWB_MODE, ProCaps.wbPresets[ProCaps.nearestPreset(prefs.wbKelvin)].third)
            }

            if (c.canZoom) b.set(CaptureRequest.CONTROL_ZOOM_RATIO, prefs.zoom.coerceIn(c.zoomMin, c.zoomMax))

            // per-frame results are only needed to show live values on screen; skip them otherwise (saves CPU)
            val wantResults = FrameHub.overlaySink != null || previewSt != null
            s.setRepeatingRequest(b.build(), if (wantResults) captureCb else null, h)
        } catch (e: Exception) {
            message = "Request error: ${e.message}"
            publish()
        }
    }

    private fun pickFpsRange(c: ProCaps, low: Boolean): Range<Int> {
        val rs = c.fpsRanges.toList()
        if (rs.isEmpty()) return Range(15, 30)
        if (low) {
            val ok = rs.filter { it.upper >= 10 }
            val minUpper = ok.minOfOrNull { it.upper } ?: return rs.maxByOrNull { it.upper }!!
            return ok.filter { it.upper == minUpper }.minByOrNull { it.lower }!!
        }
        rs.firstOrNull { it.lower == effectiveFps && it.upper == effectiveFps }?.let { return it }
        rs.filter { it.upper == effectiveFps }.maxByOrNull { it.lower }?.let { return it }
        return rs.minByOrNull { Math.abs(it.upper - effectiveFps) * 100 - it.lower }!!
    }

    // ================================================================== rotation

    private fun displayDeg(): Int = Rot.displayDegrees(display?.rotation ?: Surface.ROTATION_0)

    /** Rotation that makes the image upright on the current screen. */
    fun displayRotation(): Int {
        val c = caps ?: return 0
        return Rot.image(c.sensorOrientation, c.front, displayDeg())
    }

    /** Rotation stored in recordings / used for the web stream. */
    fun recordRotation(): Int = if (prefs.rotation >= 0) prefs.rotation else displayRotation()

    // ================================================================== frames (analysis thread)

    private fun onImage(r: ImageReader) {
        val img: Image = try { r.acquireLatestImage() } catch (e: Exception) { null } ?: return
        try { processFrame(img) } catch (_: Exception) {} finally { img.close() }
    }

    private fun processFrame(img: Image) {
        val now = SystemClock.elapsedRealtime()
        lastFrameAt = now
        val m = mode
        val monitoringNow = m == Mode.WATCHING || m == Mode.RECORDING
        val sink = FrameHub.overlaySink
        val showMotion = sink != null && FrameHub.showMotion
        val peaking = sink != null && FrameHub.peaking
        // the hardware preview is only on screen if the session uses the UI's *current* surface
        val hw = hwPreview && sessionSt != null && sessionSt === previewSt
        val motionDue = (monitoringNow || showMotion) && now - lastMotionRun >= (if (m == Mode.RECORDING && !showMotion) 250 else 125)
        val meterDue = sink != null && now - lastMeter >= 250
        val overlayWanted = sink != null && (!hw || peaking || showMotion)
        val overlayDue = overlayWanted && now - lastOverlayRun >= (if (hw) 100 else 66)
        val jpegDue = FrameHub.clients > 0 && now - lastJpegRun >= 200

        if (sink != null && !overlayWanted && overlayShown) { overlayShown = false; sink(null) }
        if (!motionDue && !overlayDue && !jpegDue && !meterDue) return

        val f = YuvTools.read(img, color = (overlayDue && !hw) || jpegDue, reuse = frameBuf)
        frameBuf = f

        if (meterDue) { lastMeter = now; liveMeterEv = YuvTools.meterEv(f) }

        if (motionDue) {
            lastMotionRun = now
            if (resetDetector) { detector.reset(); resetDetector = false; consecutive = 0 }
            val z = prefs.zones
            if (z != zonesSrc) { zonesSrc = z; zoneBlocks = if (z.isEmpty()) null else Zones.blockMask(Zones.parse(z)) }
            val largest = detector.analyze(f, prefs.sensitivity, prefs.lightFilter, zoneBlocks)
            motionBlocks = largest
            val need = max(1, ceil(prefs.minAreaPct / 100f * MotionDetector.N).toInt())
            val hit = largest >= need
            consecutive = if (hit) consecutive + 1 else 0
            if (monitoringNow && hit) {
                val needHits = max(1, ceil(prefs.minMotionMs / 125f).toInt())
                if (m == Mode.RECORDING || consecutive >= needHits) {
                    lastMotionElapsed = now
                    lastMotionWall = System.currentTimeMillis()
                    if (m == Mode.WATCHING) h.post { startRecording() }
                }
            }
        }
        if (overlayDue) {
            lastOverlayRun = now
            val bmp = YuvTools.render(
                f, 2, displayRotation(), base = !hw, peaking = peaking,
                zones = if (showMotion) (zoneBlocks ?: ALL_BLOCKS) else null,
                motion = if (showMotion) detector.changed else null,
            )
            overlayShown = true
            FrameHub.overlaySink?.invoke(bmp)
        }
        if (jpegDue) {
            lastJpegRun = now
            val bmp = YuvTools.render(f, if (prefs.streamHd) 1 else 2, recordRotation(), base = true, peaking = false)
            val bos = ByteArrayOutputStream(64 * 1024)
            bmp.compress(Bitmap.CompressFormat.JPEG, if (prefs.streamHd) 65 else 72, bos)
            FrameHub.publishJpeg(bos.toByteArray())
        }
    }

    // ================================================================== housekeeping (every second)

    private val tick: Runnable = object : Runnable {
        override fun run() {
            if (!active) return
            try { housekeeping() } catch (_: Exception) {}
            h.postDelayed(this, 1000)
        }
    }

    private fun housekeeping() {
        val now = SystemClock.elapsedRealtime()
        val bat = batteryInfo()

        if (monitoring) {
            // battery
            if (prefs.minBattery > 0 && !bat.charging && bat.pct in 0..prefs.minBattery) {
                stopMonitoringBecause("Battery at ${bat.pct}% — monitoring stopped")
                return
            }
            // heat
            if (prefs.maxTempC > 0) {
                val thermal = thermalStatus()
                val tooHot = bat.tempC >= prefs.maxTempC || thermal >= PowerManager.THERMAL_STATUS_SEVERE
                if (tooHot && mode != Mode.PAUSED_HOT) {
                    if (mode == Mode.RECORDING) stopRecording(rearm = false)
                    closeCamera()
                    mode = Mode.PAUSED_HOT
                    hotSince = now
                    message = "Phone is hot (${"%.0f".format(bat.tempC)}°C) — camera paused to cool down"
                    publish()
                    return
                }
                if (mode == Mode.PAUSED_HOT) {
                    val cooled = bat.tempC <= prefs.maxTempC - 3 && thermal < PowerManager.THERMAL_STATUS_MODERATE
                    if (cooled && now - hotSince > 60_000) openCamera() else publish()
                    return
                }
            }
            // storage: checked every 30 s, every 5 s while recording, every second when low
            val every = if (freeCache < 1_000_000_000L) 1_000L else if (mode == Mode.RECORDING) 5_000L else 30_000L
            if (now - lastFreeCheck >= every) { lastFreeCheck = now; freeCache = ClipStore.freeBytes() }
            val free = freeCache
            if (free < 300_000_000L && prefs.autoDeleteLow) maybeCleanup(force = true)
            if (free < 200_000_000L && (mode == Mode.WATCHING || mode == Mode.RECORDING)) {
                // never fill the phone completely: stop, keep what was recorded, wait for space
                if (mode == Mode.RECORDING) stopRecording(rearm = false)
                enterStoragePause()
                publish(bat)
                return
            } else if (mode == Mode.PAUSED_STORAGE && free > 600_000_000L) {
                mode = Mode.WATCHING; message = "Watching for motion"; arm()
            }
            maybeCleanup(force = false)
        } else if (mode == Mode.PAUSED_HOT) {
            openCamera()
            return
        }

        if (pre?.failed == true) { restartCamera("Encoder error — restarting"); return }

        // stall watchdog
        if ((mode == Mode.WATCHING || mode == Mode.RECORDING || mode == Mode.PREVIEW) && now - lastFrameAt > 10_000) {
            restartCamera("Camera stalled — restarting")
            return
        }

        if (mode == Mode.RECORDING) {
            val sinceMotion = now - max(lastMotionElapsed, recordStartedElapsed)
            val clipLen = now - recordStartedElapsed
            if (sinceMotion >= prefs.stopDelaySec * 1000L) {
                stopRecording(rearm = true)
            } else if (clipLen >= prefs.maxClipMin * 60_000L || (pre?.bytesWritten ?: 0L) > 3_800_000_000L) {
                // split long events: stop and immediately continue in a new file
                stopRecording(rearm = false)
                arm()
                startRecording(preRoll = false)
            }
        } else if (mode == Mode.WATCHING) {
            val a = armed
            if (a != null && a.rotation != recordRotation()) { disarm(); arm() }   // phone was rotated
            if (armed == null) arm()
        }
        publish(bat)
    }

    private fun enterStoragePause() {
        disarm()
        mode = Mode.PAUSED_STORAGE
        message = if (prefs.autoDeleteLow) "Phone storage full (no deletable clips) — not recording"
        else "Phone storage full — not recording"
    }

    private class Bat(val pct: Int, val tempC: Float, val charging: Boolean)

    private fun batteryInfo(): Bat {
        val i: Intent = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return Bat(-1, 0f, true)
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        return Bat(
            if (level >= 0 && scale > 0) level * 100 / scale else -1,
            i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f,
            i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0,
        )
    }

    private fun thermalStatus(): Int =
        try { (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus } catch (_: Exception) { 0 }

    private fun publish(bat: Bat = batteryInfo()) = try {
        StatusBus.publish(
            Status(
                mode = mode,
                monitoring = monitoring,
                message = message,
                lens = caps?.lens?.label ?: "",
                format = "${recordSize.width}x${recordSize.height} @ $effectiveFps fps",
                recordingSinceWall = if (mode == Mode.RECORDING) recordStartedWall else 0,
                lastMotionWall = lastMotionWall,
                motionBlocks = motionBlocks,
                batteryPct = bat.pct,
                tempC = bat.tempC,
                charging = bat.charging,
            )
        )
    } catch (_: Exception) {}

    companion object {
        private val ALL_BLOCKS = BooleanArray(MotionDetector.N) { true }
    }
}
