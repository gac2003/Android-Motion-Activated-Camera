package com.motioncam

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.RggbChannelVector
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.MediaRecorder
import android.util.Range
import android.util.Size
import kotlin.math.abs

/** Capabilities of one selected lens. Can be read without opening the camera. */
class ProCaps(val lens: Lens, val ctrl: CameraCharacteristics, val lensChars: CameraCharacteristics) {
    val evMin: Int
    val evMax: Int
    val evStep: Float
    val isoMin: Int
    val isoMax: Int
    val expMinNs: Long
    val expMaxNs: Long
    val manualSensor: Boolean
    val minFocusDiopter: Float
    val zoomMin: Float
    val zoomMax: Float
    val fpsRanges: Array<Range<Int>>
    val sensorOrientation: Int
    val front: Boolean
    val map: StreamConfigurationMap?

    init {
        val ev = ctrl.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        evMin = ev?.lower ?: 0
        evMax = ev?.upper ?: 0
        evStep = ctrl.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 0.1667f
        val iso = ctrl.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        isoMin = iso?.lower ?: 100
        isoMax = iso?.upper ?: 800
        val exp = ctrl.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        expMinNs = exp?.lower ?: 100_000L
        expMaxNs = exp?.upper ?: 33_000_000L
        val caps = ctrl.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        manualSensor = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) && iso != null && exp != null
        // some multi-camera phones report 0 on one of the two descriptions: take whichever has a value
        minFocusDiopter = maxOf(
            lensChars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f,
            ctrl.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f,
        )
        val z = ctrl.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
        zoomMin = z?.lower ?: 1f
        zoomMax = minOf(z?.upper ?: 1f, 10f)
        fpsRanges = ctrl.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: emptyArray()
        sensorOrientation = ctrl.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        front = ctrl.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        map = ctrl.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
    }

    /** Camera frame timestamps use elapsedRealtime (needed to line audio up with video in pre-record mode). */
    val realtimeTimestamps: Boolean =
        ctrl.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) == CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME

    val canManualFocus get() = minFocusDiopter > 0f
    val canZoom get() = zoomMax > zoomMin + 0.01f

    /** Longest shutter allowed at [fps] (cannot exceed the frame time). */
    fun shutterRange(fps: Int): Pair<Long, Long> {
        val lo = maxOf(expMinNs, 125_000L)
        var hi = minOf(expMaxNs, 1_000_000_000L / maxOf(fps, 1))
        if (hi <= lo) hi = lo * 2
        return Pair(lo, hi)
    }

    fun recordSize(height: Int): Size {
        val sizes = (map?.getOutputSizes(MediaRecorder::class.java) ?: emptyArray())
            .filter { it.width * 9 == it.height * 16 }.sortedBy { it.height }
        if (sizes.isEmpty()) return Size(1920, 1080)
        return sizes.lastOrNull { it.height <= height } ?: sizes.first()
    }

    private fun closest16x9(sizes: Array<Size>?, width: Int): Size? =
        sizes?.filter { it.width * 9 == it.height * 16 }?.minByOrNull { abs(it.width - width) }

    fun analysisSize(width: Int): Size = closest16x9(map?.getOutputSizes(ImageFormat.YUV_420_888), width) ?: Size(1280, 720)
    fun previewSize(width: Int): Size = closest16x9(map?.getOutputSizes(SurfaceTexture::class.java), width) ?: Size(1920, 1080)

    fun supportsFps(fps: Int) = fpsRanges.any { it.upper == fps }

    /**
     * Approximate colour temperature of the current auto white balance. Uses the sensor's colour calibration
     * when the phone exposes it, otherwise a typical-sensor curve of the red/blue gain ratio.
     */
    fun estimateKelvin(g: RggbChannelVector): Int? = calibratedKelvin(g) ?: gainCurveKelvin(g)

    private fun gainCurveKelvin(g: RggbChannelVector): Int? {
        if (g.red <= 0f || g.blue <= 0f) return null
        val rb = Math.log((g.blue / g.red).toDouble())
        // (ln(B/R gain), kelvin) for a typical phone sensor
        val pts = listOf(Math.log(2.3) to 2400.0, Math.log(2.08) to 2850.0, Math.log(1.25) to 4000.0,
            Math.log(0.82) to 5200.0, Math.log(0.66) to 6500.0, Math.log(0.57) to 7500.0, Math.log(0.45) to 9500.0)
        val k = when {
            rb >= pts.first().first -> pts.first().second
            rb <= pts.last().first -> pts.last().second
            else -> {
                var r = 5200.0
                for (i in 0 until pts.size - 1) {
                    val (x0, k0) = pts[i]
                    val (x1, k1) = pts[i + 1]
                    if (rb <= x0 && rb >= x1) { r = k0 + (k1 - k0) * (rb - x0) / (x1 - x0); break }
                }
                r
            }
        }
        return (k / 50).toInt() * 50
    }

    private fun calibratedKelvin(g: RggbChannelVector): Int? {
        val cm1 = ctrl.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1) ?: return null
        val cm2 = ctrl.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2) ?: cm1
        val t1 = illuminantK(ctrl.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1)) ?: 2856.0
        val t2 = illuminantK(ctrl.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2)?.toInt()) ?: 6504.0
        val gg = (g.greenEven + g.greenOdd) / 2f
        if (g.red <= 0f || g.blue <= 0f || gg <= 0f) return null
        val neutral = doubleArrayOf(1.0 / g.red, 1.0 / gg, 1.0 / g.blue)
        var t = 5000.0
        repeat(4) {
            val w = if (t1 == t2) 1.0 else ((1 / t - 1 / t2) / (1 / t1 - 1 / t2)).coerceIn(0.0, 1.0)
            val cm = DoubleArray(9) { i -> w * el(cm1, i) + (1 - w) * el(cm2, i) }
            val inv = invert(cm) ?: return null
            val x = inv[0] * neutral[0] + inv[1] * neutral[1] + inv[2] * neutral[2]
            val y = inv[3] * neutral[0] + inv[4] * neutral[1] + inv[5] * neutral[2]
            val z = inv[6] * neutral[0] + inv[7] * neutral[1] + inv[8] * neutral[2]
            val s = x + y + z
            if (s <= 0) return null
            val cx = x / s
            val cy = y / s
            val n = (cx - 0.3320) / (0.1858 - cy)
            t = 449 * n * n * n + 3525 * n * n + 6823.3 * n + 5520.33
            if (t.isNaN() || t < 1500 || t > 15000) return null
        }
        return ((t / 50).toInt() * 50)
    }

    private fun el(m: ColorSpaceTransform, i: Int): Double = m.getElement(i % 3, i / 3).toDouble()

    private fun invert(m: DoubleArray): DoubleArray? {
        val a = m[0]; val b = m[1]; val c = m[2]
        val d = m[3]; val e = m[4]; val f = m[5]
        val g = m[6]; val h = m[7]; val i = m[8]
        val det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
        if (abs(det) < 1e-12) return null
        return doubleArrayOf(
            (e * i - f * h) / det, (c * h - b * i) / det, (b * f - c * e) / det,
            (f * g - d * i) / det, (a * i - c * g) / det, (c * d - a * f) / det,
            (d * h - e * g) / det, (b * g - a * h) / det, (a * e - b * d) / det,
        )
    }

    private fun illuminantK(v: Int?): Double? = when (v) {
        CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_STANDARD_A, CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_TUNGSTEN -> 2856.0
        CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_D50 -> 5003.0
        CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_D55 -> 5503.0
        CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_D65, CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_DAYLIGHT -> 6504.0
        CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_D75 -> 7504.0
        CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_COOL_WHITE_FLUORESCENT -> 4150.0
        CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_FLUORESCENT -> 4200.0
        else -> null
    }

    companion object {
        /** White-balance presets: (name, kelvin, Camera2 AWB mode). Presets work on every phone. */
        val wbPresets: List<Triple<String, Int, Int>> = listOf(
            Triple("Incandescent", 2850, CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT),
            Triple("Warm fluorescent", 3000, CameraMetadata.CONTROL_AWB_MODE_WARM_FLUORESCENT),
            Triple("Fluorescent", 4000, CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT),
            Triple("Daylight", 5200, CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT),
            Triple("Cloudy", 6000, CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT),
            Triple("Shade", 7500, CameraMetadata.CONTROL_AWB_MODE_SHADE),
        )

        fun nearestPreset(kelvin: Int): Int = wbPresets.indices.minByOrNull { abs(wbPresets[it].second - kelvin) } ?: 3

        private val cache = HashMap<String, ProCaps>()

        /** Caps for the lens selected in [prefs]; cached, no camera open needed. */
        @Synchronized
        fun forPrefs(ctx: Context, prefs: Prefs): ProCaps? {
            val l = Lenses.find(ctx, prefs.camera) ?: return null
            cache[l.key]?.let { return it }
            val mgr = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            // Without the CAMERA permission Android hides several keys (e.g. minimum focus distance, colour
            // calibration), so only remember the result once the permission has been granted.
            val granted = ctx.checkSelfPermission(android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED
            return try {
                val ctrl = mgr.getCameraCharacteristics(l.logicalId)
                val lc = l.physicalId?.let { try { mgr.getCameraCharacteristics(it) } catch (e: Exception) { null } } ?: ctrl
                ProCaps(l, ctrl, lc).also { if (granted) cache[l.key] = it }
            } catch (e: Exception) { null }
        }
    }
}
