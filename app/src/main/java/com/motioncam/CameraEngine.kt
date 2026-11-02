package com.motioncam

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.display.DisplayManager
import android.media.Image
import android.media.ImageReader
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
import java.util.concurrent.Executor
import kotlin.math.ceil
import kotlin.math.max

enum class Mode { STOPPED, STARTING, WATCHING, RECORDING, PAUSED_HOT, PAUSED_STORAGE, ERROR }

data class Status(
    val mode: Mode = Mode.STOPPED,
    val message: String = "",
    val lens: String = "",
    val format: String = "",
    val recordingSinceWall: Long = 0,
    val lastMotionWall: Long = 0,
    val motionBlocks: Int = 0,
    val batteryPct: Int = -1,
    val tempC: Float = 0f,
    val charging: Boolean = false,
    val manualHint: String = "",
)

/** Global status channel (service -> UI). Listeners run on the engine thread; UI must post to main. */
object StatusBus {
    @Volatile var status = Status()
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(Status) -> Unit>()
    fun add(l: (Status) -> Unit) { listeners.addIfAbsent(l) }
    fun remove(l: (Status) -> Unit) { listeners.remove(l) }
    fun publish(s: Status) { status = s; for (l in listeners) l(s) }
}

/**
 * Owns the camera. Runs one low-resolution YUV stream permanently (for motion detection, on-screen preview and
 * the web stream) and adds the MediaRecorder surface only while a clip is being recorded.
 * Everything happens on a single handler thread.
 */
class CameraEngine(
    private val ctx: Context,
    private val prefs: Prefs,
    private val onFatal: (String) -> Unit,
) {
    private val mgr = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val thread = HandlerThread("cam-engine").also { it.start() }
    private val h = Handler(thread.looper)
    private val executor = Executor { h.post(it) }
    private val analysisThread = HandlerThread("cam-analysis").also { it.start() }
    private val ah = Handler(analysisThread.looper)

    @Volatile var mode = Mode.STOPPED; private set
    @Volatile var caps: ProCaps? = null; private set
    @Volatile var lensLabel = ""; private set
    @Volatile var lastIso = 0; private set
    @Volatile var lastShutterNs = 0L; private set
    @Volatile var lastFocusDiopter = 0f; private set

    private var lens: Lens? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var sessionGen = 0
    private var activeSig = ""
    private var message = ""
    private var released = false

    // recording
    private var recorder: MediaRecorder? = null
    private var pending: ClipStore.Pending? = null
    private var recSurface: Surface? = null
    private var recordStartedElapsed = 0L
    private var recordStartedWall = 0L
    private var recordSize = Size(1920, 1080)
    private var effectiveFps = 30

    // analysis-thread state
    private val detector = MotionDetector()
    @Volatile private var resetDetector = true
    private var consecutive = 0
    private var lastMotionRun = 0L
    private var lastPreviewRun = 0L
    private var lastJpegRun = 0L
    @Volatile private var motionBlocks = 0
    @Volatile private var lastFrameAt = SystemClock.elapsedRealtime()
    @Volatile private var lastMotionElapsed = 0L
    @Volatile private var lastMotionWall = 0L
    @Volatile private var rotationCache = 0

    // safety
    private var hotSince = 0L
    private var lastCleanup = 0L
    private var retryCount = 0

    // ------------------------------------------------------------------ public API

    fun start() = h.post { if (!released) openCamera() }

    /** Re-reads settings: restarts the camera only if the recording format/lens changed. */
    fun applyConfig() = h.post {
        if (released || mode == Mode.STOPPED) return@post
        if (signature() != activeSig) restartCamera("Applying new settings") else applyPro()
    }

    /** Re-sends the repeating request with the current pro controls (no session rebuild). */
    fun applyPro() = h.post { pushRepeating() }

    fun restart() = h.post { restartCamera("Restarting") }

    fun release() {
        h.post {
            released = true
            if (mode == Mode.RECORDING) stopRecording(restartSession = false)
            closeCamera()
            mode = Mode.STOPPED
            publish()
            thread.quitSafely()
            analysisThread.quitSafely()
        }
    }

    // ------------------------------------------------------------------ camera lifecycle

    private fun signature() =
        "${prefs.camera}|${prefs.resHeight}|${prefs.fps}|${prefs.hevc}|${prefs.bitrateMbps}|${prefs.audio}"

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        closeCamera()
        mode = Mode.STARTING
        message = "Opening camera…"
        publish()
        try {
            val l = Lenses.find(ctx, prefs.camera) ?: throw IllegalStateException("No camera found")
            lens = l
            lensLabel = l.label
            val ctrl = mgr.getCameraCharacteristics(l.logicalId)
            val lensChars = if (l.physicalId != null) {
                try { mgr.getCameraCharacteristics(l.physicalId) } catch (e: Exception) { ctrl }
            } else ctrl
            caps = ProCaps(ctrl, lensChars)
            activeSig = signature()
            mgr.openCamera(l.logicalId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (released) { camera.close(); return }
                    device = camera
                    retryCount = 0
                    startSession(recording = false)
                }
                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    if (device === camera) device = null
                    scheduleRetry("Camera disconnected")
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    if (device === camera) device = null
                    scheduleRetry("Camera error $error")
                }
            }, h)
        } catch (e: Exception) {
            scheduleRetry("Cannot open camera: ${e.message}")
        }
    }

    private fun scheduleRetry(why: String) {
        if (released) return
        if (mode == Mode.RECORDING) stopRecording(restartSession = false)
        closeCamera()
        retryCount++
        if (retryCount > 20) {
            mode = Mode.ERROR
            message = why
            publish()
            onFatal(why)
            return
        }
        mode = Mode.ERROR
        message = "$why — retrying in 5 s"
        publish()
        h.postDelayed({ if (!released && mode == Mode.ERROR) openCamera() }, 5000)
    }

    private fun restartCamera(why: String) {
        if (mode == Mode.RECORDING) stopRecording(restartSession = false)
        message = why
        openCamera()
    }

    private fun closeCamera() {
        sessionGen++
        try { session?.close() } catch (_: Exception) {}
        session = null
        try { device?.close() } catch (_: Exception) {}
        device = null
        try { reader?.close() } catch (_: Exception) {}
        reader = null
    }

    // ------------------------------------------------------------------ session

    private fun startSession(recording: Boolean) {
        val dev = device ?: return
        val c = caps ?: return
        val l = lens ?: return
        val gen = ++sessionGen
        try {
            session?.let { try { it.close() } catch (_: Exception) {} }
            session = null

            if (reader == null) {
                val sz = c.analysisSize() ?: Size(1280, 720)
                reader = ImageReader.newInstance(sz.width, sz.height, ImageFormat.YUV_420_888, 3).also {
                    it.setOnImageAvailableListener({ r -> onImage(r) }, ah)
                }
            }
            val outs = ArrayList<OutputConfiguration>()
            outs += OutputConfiguration(reader!!.surface).also { if (l.physicalId != null) it.setPhysicalCameraId(l.physicalId) }
            if (recording) {
                val rs = recSurface ?: throw IllegalStateException("recorder not ready")
                outs += OutputConfiguration(rs).also { if (l.physicalId != null) it.setPhysicalCameraId(l.physicalId) }
            }
            val cfg = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outs, executor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        if (gen != sessionGen || released) { try { s.close() } catch (_: Exception) {}; return }
                        session = s
                        resetDetector = true
                        consecutive = 0
                        lastFrameAt = SystemClock.elapsedRealtime()
                        pushRepeating()
                        if (recording) {
                            try {
                                recorder?.start()
                                recordStartedElapsed = SystemClock.elapsedRealtime()
                                recordStartedWall = System.currentTimeMillis()
                                mode = Mode.RECORDING
                                message = "Recording"
                            } catch (e: Exception) {
                                abortRecording("Recorder failed: ${e.message}")
                                return
                            }
                        } else if (mode == Mode.STARTING || mode == Mode.ERROR) {
                            mode = Mode.WATCHING
                            message = "Watching for motion"
                            h.removeCallbacks(tick)
                            h.postDelayed(tick, 2000)
                        }
                        publish()
                    }
                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        if (gen != sessionGen) return
                        if (recording) abortRecording("Could not configure recording")
                        else scheduleRetry("Could not configure camera")
                    }
                })
            dev.createCaptureSession(cfg)
        } catch (e: Exception) {
            if (recording) abortRecording("Session error: ${e.message}") else scheduleRetry("Session error: ${e.message}")
        }
    }

    private val captureCb = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, res: TotalCaptureResult) {
            res.get(CaptureResult.SENSOR_SENSITIVITY)?.let { lastIso = it }
            res.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { lastShutterNs = it }
            res.get(CaptureResult.LENS_FOCUS_DISTANCE)?.let { lastFocusDiopter = it }
        }
    }

    private fun pushRepeating() {
        val s = session ?: return
        val dev = device ?: return
        val c = caps ?: return
        val rec = mode == Mode.RECORDING || recorder != null && recSurface != null && pending != null
        try {
            val b = dev.createCaptureRequest(if (rec) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW)
            b.addTarget(reader!!.surface)
            if (rec) recSurface?.let { b.addTarget(it) }
            b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)

            // frame rate
            b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, pickFpsRange(c, rec))

            // exposure
            val fps = if (rec) effectiveFps else 15
            if (prefs.exposureAuto || !c.manualSensor) {
                b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, prefs.evIndex.coerceIn(c.evMin, c.evMax))
            } else {
                val shutter = prefs.shutterNs.coerceIn(c.expMinNs, c.expMaxNs)
                b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                b.set(CaptureRequest.SENSOR_SENSITIVITY, prefs.iso.coerceIn(c.isoMin, c.isoMax))
                b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, shutter)
                b.set(CaptureRequest.SENSOR_FRAME_DURATION, max(1_000_000_000L / fps, shutter))
            }

            // focus
            if (prefs.focusAuto || !c.canManualFocus) {
                b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            } else {
                b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                b.set(CaptureRequest.LENS_FOCUS_DISTANCE, (1f - prefs.focusPos.coerceIn(0f, 1f)) * c.minFocusDiopter)
            }

            // white balance
            if (prefs.wbAuto) {
                b.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
            } else {
                val p = ProCaps.wbPresets.minByOrNull { Math.abs(it.second - prefs.wbKelvin) }!!
                b.set(CaptureRequest.CONTROL_AWB_MODE, p.third)
            }

            // zoom
            if (c.canZoom) b.set(CaptureRequest.CONTROL_ZOOM_RATIO, prefs.zoom.coerceIn(c.zoomMin, c.zoomMax))

            s.setRepeatingRequest(b.build(), captureCb, h)
        } catch (e: Exception) {
            message = "Request error: ${e.message}"
            publish()
        }
    }

    private fun pickFpsRange(c: ProCaps, recording: Boolean): Range<Int> {
        val rs = c.fpsRanges.toList()
        if (rs.isEmpty()) return Range(15, 30)
        if (recording) {
            rs.firstOrNull { it.lower == effectiveFps && it.upper == effectiveFps }?.let { return it }
            rs.filter { it.upper == effectiveFps }.maxByOrNull { it.lower }?.let { return it }
            return rs.minByOrNull { Math.abs(it.upper - effectiveFps) * 100 - it.lower }!!
        }
        // idle: lowest frame rate that still gives a usable ~10 fps preview
        val ok = rs.filter { it.upper >= 15 }
        val minUpper = ok.minOfOrNull { it.upper } ?: return rs.maxByOrNull { it.upper }!!
        return ok.filter { it.upper == minUpper }.minByOrNull { it.lower }!!
    }

    // ------------------------------------------------------------------ frames

    private fun onImage(r: ImageReader) {
        val img: Image = try { r.acquireLatestImage() } catch (e: Exception) { null } ?: return
        try { processFrame(img) } catch (_: Exception) {} finally { img.close() }
    }

    private fun processFrame(img: Image) {
        val now = SystemClock.elapsedRealtime()
        lastFrameAt = now
        val rec = mode == Mode.RECORDING
        val wantPreview = FrameHub.previewSink != null
        val wantJpeg = FrameHub.clients > 0
        val motionDue = (mode == Mode.WATCHING || rec) && now - lastMotionRun >= (if (rec) 400 else 250)
        val previewDue = wantPreview && now - lastPreviewRun >= 100
        val jpegDue = wantJpeg && now - lastJpegRun >= 200
        if (!motionDue && !previewDue && !jpegDue) return

        val f = YuvTools.read(img, color = previewDue || jpegDue)

        if (motionDue) {
            lastMotionRun = now
            if (resetDetector) { detector.reset(); resetDetector = false; consecutive = 0 }
            val changed = detector.analyze(f, prefs.sensitivity)
            motionBlocks = changed
            val need = max(2, ceil(prefs.minAreaPct / 100f * MotionDetector.N).toInt())
            val hit = changed >= need
            consecutive = if (hit) consecutive + 1 else 0
            if (hit) {
                lastMotionElapsed = now
                lastMotionWall = System.currentTimeMillis()
                if (consecutive >= 2 && mode == Mode.WATCHING) h.post { if (mode == Mode.WATCHING) startRecording() }
            }
        }
        if (previewDue) {
            lastPreviewRun = now
            val peak = prefs.peaking && !prefs.focusAuto
            val bmp = YuvTools.toBitmap(f, 2, rotationCache, peak)
            FrameHub.previewSink?.invoke(bmp)
        }
        if (jpegDue) {
            lastJpegRun = now
            val bmp = YuvTools.toBitmap(f, if (prefs.streamHd) 1 else 2, rotationCache, false)
            val bos = ByteArrayOutputStream(64 * 1024)
            bmp.compress(Bitmap.CompressFormat.JPEG, if (prefs.streamHd) 65 else 72, bos)
            FrameHub.publishJpeg(bos.toByteArray())
        }
    }

    // ------------------------------------------------------------------ recording

    private fun effectiveRotation(): Int {
        val o = prefs.rotation
        if (o >= 0) return o
        val c = caps ?: return 0
        val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val d = dm.getDisplay(Display.DEFAULT_DISPLAY)
        val deg = when (d?.rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        return if (c.front) (c.sensorOrientation + deg) % 360 else (c.sensorOrientation - deg + 360) % 360
    }

    private fun pickRecordSize(c: ProCaps): Size {
        val sizes = c.recordSizes16x9()
        if (sizes.isEmpty()) return Size(1920, 1080)
        return sizes.lastOrNull { it.height <= prefs.resHeight } ?: sizes.first()
    }

    private fun bitrate(size: Size, fps: Int): Int {
        if (prefs.bitrateMbps > 0) return prefs.bitrateMbps * 1_000_000
        val r = size.height / 1080f
        var mbps = 8f * r * r * (fps / 30f)
        if (prefs.hevc) mbps *= 0.6f
        return (mbps.coerceIn(1.5f, 60f) * 1_000_000).toInt()
    }

    private fun startRecording() {
        if (mode != Mode.WATCHING) return
        val c = caps ?: return
        if (ClipStore.freeBytes() < 300_000_000L) {
            mode = Mode.PAUSED_STORAGE; message = "Storage almost full — not recording"; publish(); return
        }
        try {
            recordSize = pickRecordSize(c)
            effectiveFps = prefs.fps
            if (c.fpsRanges.none { it.upper == effectiveFps }) effectiveFps = 30
            val p = ClipStore.create(ctx)
            pending = p
            val rec = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else @Suppress("DEPRECATION") MediaRecorder()
            val withAudio = prefs.audio &&
                ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (withAudio) rec.setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
            rec.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setOutputFile(p.pfd.fileDescriptor)
            rec.setVideoEncodingBitRate(bitrate(recordSize, effectiveFps))
            rec.setVideoFrameRate(effectiveFps)
            rec.setVideoSize(recordSize.width, recordSize.height)
            rec.setVideoEncoder(if (prefs.hevc) MediaRecorder.VideoEncoder.HEVC else MediaRecorder.VideoEncoder.H264)
            if (withAudio) {
                rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                rec.setAudioEncodingBitRate(128_000)
                rec.setAudioSamplingRate(44_100)
            }
            rec.setOrientationHint(effectiveRotation())
            rec.prepare()
            recorder = rec
            recSurface = rec.surface
            mode = Mode.STARTING   // becomes RECORDING once the session is configured
            message = "Starting clip…"
            publish()
            startSession(recording = true)
        } catch (e: Exception) {
            abortRecording("Cannot start recording: ${e.message}")
        }
    }

    private fun abortRecording(why: String) {
        releaseRecorder(keep = false)
        message = why
        mode = Mode.WATCHING
        startSession(recording = false)
        publish()
    }

    private fun releaseRecorder(keep: Boolean) {
        val rec = recorder
        recorder = null
        recSurface = null
        var ok = keep
        if (rec != null) {
            if (keep) {
                try { rec.stop() } catch (_: Exception) { ok = false }
            }
            try { rec.reset() } catch (_: Exception) {}
            try { rec.release() } catch (_: Exception) {}
        }
        pending?.let { ClipStore.finish(ctx, it, ok) }
        pending = null
    }

    private fun stopRecording(restartSession: Boolean) {
        if (recorder == null) return
        try { session?.stopRepeating() } catch (_: Exception) {}
        try { session?.abortCaptures() } catch (_: Exception) {}
        val longEnough = SystemClock.elapsedRealtime() - recordStartedElapsed > 1500
        releaseRecorder(keep = longEnough)
        mode = Mode.WATCHING
        message = "Watching for motion"
        if (restartSession) startSession(recording = false)
        publish()
        if (SystemClock.elapsedRealtime() - lastCleanup > 60_000) {
            lastCleanup = SystemClock.elapsedRealtime()
            Thread { ClipStore.cleanup(ctx, prefs) }.start()
        }
    }

    // ------------------------------------------------------------------ periodic housekeeping

    private val tick: Runnable = object : Runnable {
        override fun run() {
            if (released) return
            try { housekeeping() } catch (_: Exception) {}
            h.postDelayed(this, 2000)
        }
    }

    private fun housekeeping() {
        val now = SystemClock.elapsedRealtime()
        rotationCache = effectiveRotation()
        val bat = batteryInfo()

        // battery protection
        if (!bat.charging && bat.pct in 0..prefs.minBattery) {
            if (mode == Mode.RECORDING) stopRecording(restartSession = false)
            closeCamera()
            mode = Mode.STOPPED
            message = "Battery at ${bat.pct}% — monitoring stopped"
            publish()
            onFatal(message)
            return
        }

        // heat protection
        val thermal = thermalStatus()
        val tooHot = bat.tempC >= prefs.maxTempC || thermal >= PowerManager.THERMAL_STATUS_SEVERE
        if (tooHot && mode != Mode.PAUSED_HOT && mode != Mode.STOPPED) {
            if (mode == Mode.RECORDING) stopRecording(restartSession = false)
            closeCamera()
            mode = Mode.PAUSED_HOT
            hotSince = now
            message = "Phone is hot (${"%.0f".format(bat.tempC)}°C) — cooling down"
            publish()
            return
        }
        if (mode == Mode.PAUSED_HOT) {
            val cooled = bat.tempC <= prefs.maxTempC - 3 && thermal < PowerManager.THERMAL_STATUS_MODERATE
            if (cooled && now - hotSince > 60_000) openCamera()
            else publish()
            return
        }

        // storage
        if (mode == Mode.PAUSED_STORAGE) {
            if (ClipStore.freeBytes() > 600_000_000L) { mode = Mode.WATCHING; message = "Watching for motion" }
        }

        // stall watchdog
        if ((mode == Mode.WATCHING || mode == Mode.RECORDING) && now - lastFrameAt > 10_000) {
            restartCamera("Camera stalled — restarting")
            return
        }

        if (mode == Mode.RECORDING) {
            val sinceMotion = now - max(lastMotionElapsed, recordStartedElapsed)
            val clipLen = now - recordStartedElapsed
            if (sinceMotion >= prefs.stopDelaySec * 1000L) {
                stopRecording(restartSession = true)
            } else if (clipLen >= prefs.maxClipMin * 60_000L) {
                // split long events into several files
                stopRecording(restartSession = false)
                if (mode == Mode.WATCHING) startRecording()
            }
        }
        publish()
    }

    private class Bat(val pct: Int, val tempC: Float, val charging: Boolean)

    private fun batteryInfo(): Bat {
        val i: Intent? = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (i == null) return Bat(-1, 0f, true)
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f
        val charging = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        return Bat(pct, temp, charging)
    }

    private fun thermalStatus(): Int =
        try { (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus } catch (_: Exception) { 0 }

    private fun publish() {
        val bat = batteryInfo()
        val c = caps
        val size = if (mode == Mode.RECORDING || c == null) recordSize else pickRecordSize(c)
        StatusBus.publish(
            Status(
                mode = mode,
                message = message,
                lens = lensLabel,
                format = "${size.width}x${size.height} @ ${if (mode == Mode.RECORDING) effectiveFps else prefs.fps} fps",
                recordingSinceWall = if (mode == Mode.RECORDING) recordStartedWall else 0,
                lastMotionWall = lastMotionWall,
                motionBlocks = motionBlocks,
                batteryPct = bat.pct,
                tempC = bat.tempC,
                charging = bat.charging,
            )
        )
    }
}
