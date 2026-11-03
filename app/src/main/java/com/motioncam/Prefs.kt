package com.motioncam

import android.content.Context
import android.content.SharedPreferences

/** All user settings. Values are read live, so the engine sees changes immediately. */
class Prefs(ctx: Context) {
    private val sp: SharedPreferences =
        ctx.applicationContext.getSharedPreferences("motioncam", Context.MODE_PRIVATE)

    init { migrate() }

    /** Keeps the user's chosen behaviour when the meaning of a stored value changes between versions. */
    private fun migrate() {
        var v = sp.getInt("prefsVersion", 1)
        if (v >= 3) return
        val e = sp.edit()
        if (sp.contains("sensitivity")) {
            var sens = sp.getInt("sensitivity", 5)
            if (v < 2) {
                // v1 threshold: 0.05 + (10-s)*0.024 ; v2: 0.07 + (10-s)*0.03  -> same threshold
                val thr = 0.05f + (10 - sens) * 0.024f
                sens = Math.round(10 - (thr - 0.07f) / 0.03f).coerceIn(1, 10)
            }
            // v3 shifted the scale by two levels (new 5 = old 3): keep the same behaviour
            sens = (sens + 2).coerceIn(1, 10)
            e.putInt("sensitivity", sens)
        }
        v = 3
        e.putInt("prefsVersion", v).apply()
    }

    private fun i(k: String, d: Int) = sp.getInt(k, d)
    private fun f(k: String, d: Float) = sp.getFloat(k, d)
    private fun b(k: String, d: Boolean) = sp.getBoolean(k, d)
    private fun s(k: String, d: String) = sp.getString(k, d) ?: d
    private fun put(k: String, v: Int) = sp.edit().putInt(k, v).apply()
    private fun put(k: String, v: Float) = sp.edit().putFloat(k, v).apply()
    private fun put(k: String, v: Boolean) = sp.edit().putBoolean(k, v).apply()
    private fun put(k: String, v: String) = sp.edit().putString(k, v).apply()

    // ---- preview ----
    var previewOn: Boolean get() = b("previewOn", true); set(v) = put("previewOn", v)
    /** Focus peaking while the FOCUS control is open. */
    var peaking: Boolean get() = b("peaking", true); set(v) = put("peaking", v)
    /** Drop to a lower camera frame rate while monitoring with nobody looking at the screen. */
    var lowPowerIdle: Boolean get() = b("lowPowerIdle", true); set(v) = put("lowPowerIdle", v)
    /** false = 12-hour clock with AM/PM (default). */
    var use24h: Boolean get() = b("use24h", false); set(v) = put("use24h", v)
    /** Visible Pro-mode buttons in order, comma separated. */
    var chipLayout: String get() = s("chipLayout", ALL_CHIPS.joinToString(",")); set(v) = put("chipLayout", v)

    // ---- camera / recording ----
    /** "<logicalId>" or "<logicalId>:<physicalId>". Empty = first rear camera. */
    var camera: String get() = s("camera", ""); set(v) = put("camera", v)
    var resHeight: Int get() = i("resHeight", 1080); set(v) = put("resHeight", v)
    var fps: Int get() = i("fps", 30); set(v) = put("fps", v)
    var hevc: Boolean get() = b("hevc", false); set(v) = put("hevc", v)
    /** 0 = automatic. */
    var bitrateMbps: Int get() = i("bitrateMbps", 0); set(v) = put("bitrateMbps", v)
    var audio: Boolean get() = b("audio", true); set(v) = put("audio", v)
    /** Seconds of video kept before motion is detected (0 = off). Keeps the encoder running while monitoring. */
    var preRecordMs: Int get() = i("preRecordMs", 0); set(v) = put("preRecordMs", v)
    /** -1 = follow device orientation, otherwise 0/90/180/270. */
    var rotation: Int get() = i("rotation", -1); set(v) = put("rotation", v)

    // ---- pro controls ----
    var exposureAuto: Boolean get() = b("exposureAuto", true); set(v) = put("exposureAuto", v)
    var evIndex: Int get() = i("evIndex", 0); set(v) = put("evIndex", v)
    var iso: Int get() = i("iso", 400); set(v) = put("iso", v)
    var shutterNs: Long
        get() = sp.getLong("shutterNs", 16_666_667L)
        set(v) = sp.edit().putLong("shutterNs", v).apply()
    var focusAuto: Boolean get() = b("focusAuto", true); set(v) = put("focusAuto", v)
    /** 0 = nearest focus, 1 = infinity. */
    var focusPos: Float get() = f("focusPos", 1f); set(v) = put("focusPos", v)
    var wbAuto: Boolean get() = b("wbAuto", true); set(v) = put("wbAuto", v)
    var wbKelvin: Int get() = i("wbKelvin", 5200); set(v) = put("wbKelvin", v)
    var zoom: Float get() = f("zoom", 1f); set(v) = put("zoom", v)

    // ---- motion ----
    /** 1 (least) .. 10 (most sensitive). */
    var sensitivity: Int get() = i("sensitivity", 5); set(v) = put("sensitivity", v)
    /** 0 = off, 1 = normal (ignore brightness shifts), 2 = strong (also ignore glare/reflections). */
    var lightFilter: Int get() = i("lightFilter", 2); set(v) = put("lightFilter", v)
    /** Minimum size of one connected moving area, percent of the frame. */
    var minAreaPct: Float get() = f("minAreaPct", 5.0f); set(v) = put("minAreaPct", v)
    /** Motion must persist this long before a clip starts (filters flashes). */
    var minMotionMs: Int get() = i("minMotionMs", 250); set(v) = put("minMotionMs", v)
    var stopDelaySec: Int get() = i("stopDelaySec", 30); set(v) = put("stopDelaySec", v)
    var maxClipMin: Int get() = i("maxClipMin", 10); set(v) = put("maxClipMin", v)
    /** Detection zones, 16x9 cells as '1'/'0'. Empty = whole frame. */
    var zones: String get() = s("zones", ""); set(v) = put("zones", v)

    // ---- storage / protection (0 = off) ----
    var maxStorageGb: Int get() = i("maxStorageGb", 0); set(v) = put("maxStorageGb", v)
    var keepDays: Int get() = i("keepDays", 0); set(v) = put("keepDays", v)
    /** Delete the oldest clips when the phone itself is nearly full. */
    var autoDeleteLow: Boolean get() = b("autoDeleteLow", true); set(v) = put("autoDeleteLow", v)
    var minBattery: Int get() = i("minBattery", 10); set(v) = put("minBattery", v)
    var maxTempC: Int get() = i("maxTempC", 46); set(v) = put("maxTempC", v)

    // ---- cloud backup (per-destination settings live in cloud/CloudConfig) ----
    var cloudWifiOnly: Boolean get() = b("cloudWifiOnly", true); set(v) = put("cloudWifiOnly", v)
    var cloudChargingOnly: Boolean get() = b("cloudChargingOnly", false); set(v) = put("cloudChargingOnly", v)
    /** 0 = original, 1 = 720p, 2 = 480p copies. */
    var cloudQuality: Int get() = i("cloudQuality", 0); set(v) = put("cloudQuality", v)
    /** Delete the phone copy once a clip is in every enabled destination (clips in collections are kept). */
    var cloudDeleteLocal: Boolean get() = b("cloudDeleteLocal", false); set(v) = put("cloudDeleteLocal", v)

    // ---- remote view ----
    var serverOn: Boolean get() = b("serverOn", false); set(v) = put("serverOn", v)
    var streamHd: Boolean get() = b("streamHd", false); set(v) = put("streamHd", v)
    var port: Int get() = i("port", 8080); set(v) = put("port", v)
    var password: String get() = s("password", ""); set(v) = put("password", v)

    // ---- reset ----
    fun reset(keys: List<String>) {
        val e = sp.edit()
        for (k in keys) e.remove(k)
        e.apply()
    }

    companion object {
        val ALL_CHIPS = listOf("LENS", "FORMAT", "EV", "ISO", "SPEED", "FOCUS", "WB", "ZOOM", "ZONES")

        val PREVIEW_KEYS = listOf("previewOn", "peaking", "lowPowerIdle", "use24h")
        val RECORDING_KEYS = listOf("resHeight", "fps", "hevc", "bitrateMbps", "audio", "rotation", "preRecordMs")
        val MOTION_KEYS = listOf("sensitivity", "lightFilter", "minAreaPct", "minMotionMs", "stopDelaySec", "maxClipMin", "zones")
        val STORAGE_KEYS = listOf("maxStorageGb", "keepDays", "autoDeleteLow")
        val PROTECTION_KEYS = listOf("minBattery", "maxTempC")
        val REMOTE_KEYS = listOf("serverOn", "streamHd", "port")
        val CLOUD_KEYS = listOf("cloudWifiOnly", "cloudChargingOnly", "cloudQuality", "cloudDeleteLocal")
        val CAMERA_KEYS = listOf("camera", "exposureAuto", "evIndex", "iso", "shutterNs", "focusAuto", "focusPos",
            "wbAuto", "wbKelvin", "zoom", "chipLayout")
        /** Everything except the web password (so you are not locked out of the viewer). */
        val ALL_KEYS = PREVIEW_KEYS + RECORDING_KEYS + MOTION_KEYS + STORAGE_KEYS + PROTECTION_KEYS + REMOTE_KEYS + CLOUD_KEYS + CAMERA_KEYS
    }
}
