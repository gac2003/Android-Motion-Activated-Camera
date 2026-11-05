package com.motioncam.live

import android.content.Context
import com.motioncam.EngineHost
import com.motioncam.Lenses
import com.motioncam.MotionDetector
import com.motioncam.Prefs
import org.json.JSONObject

/**
 * Settings another phone may change on the camera phone, each checked against what the camera supports.
 * (The web password, cloud accounts and the like are deliberately not remote-controllable.)
 */
object RemoteSettings {
    /** The camera phone's own screen refreshes its controls when they were changed remotely. */
    val listeners = java.util.concurrent.CopyOnWriteArraySet<() -> Unit>()
    fun changed() { for (l in listeners) try { l() } catch (_: Exception) {} }

    /** Applies what is valid; returns (applied keys, rejected "key (reason)"). */
    fun apply(ctx: Context, args: JSONObject): Pair<List<String>, List<String>> {
        val p = Prefs(ctx)
        val c = EngineHost.peek()?.caps
        val ok = ArrayList<String>()
        val bad = ArrayList<String>()
        for (k in args.keys()) {
            try {
                val v = args.get(k)
                fun int() = (v as Number).toInt()
                fun bool() = v as Boolean
                fun dbl() = (v as Number).toDouble()
                fun range(x: Number, lo: Number, hi: Number) = x.toDouble() >= lo.toDouble() && x.toDouble() <= hi.toDouble()
                val good = when (k) {
                    "audio" -> { p.audio = bool(); true }
                    "hevc" -> { p.hevc = bool(); true }
                    "exposureAuto" -> { p.exposureAuto = bool(); true }
                    "focusAuto" -> { p.focusAuto = bool(); true }
                    "wbAuto" -> { p.wbAuto = bool(); true }
                    "fps" -> int().let { f -> if (f in listOf(24, 30, 60) && (c == null || c.supportsFps(f))) { p.fps = f; true } else false }
                    "resHeight" -> int().let { h -> if (h in listOf(720, 1080, 2160)) { p.resHeight = h; true } else false }
                    "bitrateMbps" -> int().let { b -> if (b in 0..100) { p.bitrateMbps = b; true } else false }
                    "preRecordMs" -> int().let { m -> if (m in listOf(0, 500, 1000, 2000, 3000)) { p.preRecordMs = m; true } else false }
                    "stopDelaySec" -> int().let { s -> if (s in 0..600) { p.stopDelaySec = s; true } else false }
                    "maxClipMin" -> int().let { s -> if (s in 0..120) { p.maxClipMin = s; true } else false }
                    "sensitivity" -> int().let { s -> if (s in 1..MotionDetector.MAX_SENSITIVITY) { p.sensitivity = s; true } else false }
                    "lightFilter" -> int().let { s -> if (s in 0..2) { p.lightFilter = s; true } else false }
                    "minAreaPct" -> dbl().let { s -> if (s in 0.2..20.0) { p.minAreaPct = s.toFloat(); true } else false }
                    "minMotionMs" -> int().let { s -> if (s in 0..5000) { p.minMotionMs = s; true } else false }
                    "evIndex" -> int().let { s -> if (c == null || range(s, c.evMin, c.evMax)) { p.evIndex = s; true } else false }
                    "iso" -> int().let { s -> if (c == null || range(s, c.isoMin, c.isoMax)) { p.iso = s; true } else false }
                    "shutterNs" -> (v as Number).toLong().let { s -> if (c == null || range(s, c.expMinNs, c.expMaxNs)) { p.shutterNs = s; true } else false }
                    "focusPos" -> dbl().let { s -> if (s in 0.0..1.0) { p.focusPos = s.toFloat(); true } else false }   // 1 = far … 0 = closest
                    "wbKelvin" -> int().let { s -> if (s in 2000..10000) { p.wbKelvin = s; true } else false }
                    "zoom" -> dbl().let { s -> if (c == null || range(s, c.zoomMin, c.zoomMax)) { p.zoom = s.toFloat(); true } else false }
                    "camera" -> (v as String).let { key -> if (Lenses.list(ctx).any { it.key == key }) { p.camera = key; p.zoom = 1f; true } else false }
                    else -> false
                }
                if (good) ok += k else bad += k
            } catch (e: Exception) { bad += k }
        }
        return Pair(ok, bad)
    }
}
