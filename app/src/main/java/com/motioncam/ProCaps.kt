package com.motioncam

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.params.StreamConfigurationMap
import android.util.Range
import android.util.Size

/** Ranges the UI sliders need, read from the selected camera. */
class ProCaps(val ctrl: CameraCharacteristics, val lens: CameraCharacteristics) {
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
        val st = ctrl.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
        evStep = st?.toFloat() ?: 0.1667f
        val iso = ctrl.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        isoMin = iso?.lower ?: 100
        isoMax = iso?.upper ?: 800
        val exp = ctrl.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        expMinNs = exp?.lower ?: 100_000L
        expMaxNs = exp?.upper ?: 33_000_000L
        val caps = ctrl.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        manualSensor = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) &&
            iso != null && exp != null
        minFocusDiopter = lens.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
            ?: ctrl.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        val z = ctrl.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
        zoomMin = z?.lower ?: 1f
        zoomMax = minOf(z?.upper ?: 1f, 10f)
        fpsRanges = ctrl.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: emptyArray()
        sensorOrientation = ctrl.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        front = ctrl.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        map = ctrl.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
    }

    val canManualFocus get() = minFocusDiopter > 0f
    val canZoom get() = zoomMax > zoomMin + 0.01f

    /** 16:9 recording sizes (MediaRecorder-compatible) available on this camera. */
    fun recordSizes16x9(): List<Size> =
        (map?.getOutputSizes(android.media.MediaRecorder::class.java) ?: emptyArray())
            .filter { it.width * 9 == it.height * 16 }
            .sortedBy { it.height }

    fun analysisSize(): Size? {
        val sizes = (map?.getOutputSizes(android.graphics.ImageFormat.YUV_420_888) ?: return null)
            .filter { it.width * 9 == it.height * 16 }
        return sizes.minByOrNull { Math.abs(it.width - 1280) }
    }

    companion object {
        /** White-balance presets: kelvin to Camera2 AWB mode. Presets work on every device, unlike raw gains. */
        val wbPresets: List<Triple<String, Int, Int>> = listOf(
            Triple("Incandescent", 2850, CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT),
            Triple("Warm fluorescent", 3000, CameraMetadata.CONTROL_AWB_MODE_WARM_FLUORESCENT),
            Triple("Fluorescent", 4000, CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT),
            Triple("Daylight", 5200, CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT),
            Triple("Cloudy", 6000, CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT),
            Triple("Shade", 7500, CameraMetadata.CONTROL_AWB_MODE_SHADE),
        )
    }
}
