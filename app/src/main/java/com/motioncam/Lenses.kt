package com.motioncam

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import kotlin.math.hypot

/** One selectable camera. [physicalId] is set only for lenses that are hidden behind a logical multi-camera. */
data class Lens(
    val key: String,
    val logicalId: String,
    val physicalId: String?,
    val label: String,
    val isFront: Boolean,
)

object Lenses {
    private const val FULL_FRAME_DIAG_MM = 43.27f

    @Volatile private var cached: List<Lens>? = null

    fun list(ctx: Context): List<Lens> = cached ?: scan(ctx).also { if (it.isNotEmpty()) cached = it }

    private fun scan(ctx: Context): List<Lens> {
        val mgr = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val ids = try { mgr.cameraIdList.toList() } catch (e: Exception) { emptyList() }
        val out = ArrayList<Lens>()
        val topLevel = ids.toSet()
        for (id in ids) {
            val c = try { mgr.getCameraCharacteristics(id) } catch (e: Exception) { continue }
            val front = c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
            val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
            val logical = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)
            out += Lens(id, id, null, describe(id, c, front, logical), front)
            if (logical) {
                for (pid in c.physicalCameraIds) {
                    if (pid in topLevel) continue // already listed on its own
                    val pc = try { mgr.getCameraCharacteristics(pid) } catch (e: Exception) { null }
                    out += Lens("$id:$pid", id, pid, describe(pid, pc ?: c, front, false) + " (via $id)", front)
                }
            }
        }
        // rear cameras first, then by focal length
        return out.sortedWith(compareBy({ it.isFront }, { it.logicalId.toIntOrNull() ?: 99 }))
    }

    fun find(ctx: Context, key: String): Lens? {
        val all = list(ctx)
        return all.firstOrNull { it.key == key } ?: all.firstOrNull { !it.isFront } ?: all.firstOrNull()
    }

    fun focal35(c: CameraCharacteristics): Float? {
        val f = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: return null
        val size = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return null
        val diag = hypot(size.width, size.height)
        if (diag <= 0f) return null
        return f * FULL_FRAME_DIAG_MM / diag
    }

    private fun describe(id: String, c: CameraCharacteristics, front: Boolean, logical: Boolean): String {
        val eq = focal35(c)
        val kind = when {
            front -> "Front"
            eq == null -> "Rear"
            eq < 20f -> "Rear ultra-wide"
            eq < 40f -> "Rear wide"
            else -> "Rear telephoto"
        }
        val mm = if (eq != null) " ${Math.round(eq)}mm" else ""
        val tag = if (logical) " · multi-cam" else ""
        return "$kind$mm$tag [id $id]"
    }
}
