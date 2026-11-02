package com.motioncam

import android.content.Context
import android.content.SharedPreferences

/** All user settings in one place. Values are read live, so the service sees changes immediately. */
class Prefs(ctx: Context) {
    private val sp: SharedPreferences =
        ctx.applicationContext.getSharedPreferences("motioncam", Context.MODE_PRIVATE)

    private fun int(k: String, d: Int) = sp.getInt(k, d)
    private fun float(k: String, d: Float) = sp.getFloat(k, d)
    private fun bool(k: String, d: Boolean) = sp.getBoolean(k, d)
    private fun str(k: String, d: String) = sp.getString(k, d) ?: d

    // ---- camera / recording ----
    /** "<logicalId>" or "<logicalId>:<physicalId>". Empty = first rear camera. */
    var camera: String
        get() = str("camera", ""); set(v) = sp.edit().putString("camera", v).apply()
    var resHeight: Int
        get() = int("resHeight", 1080); set(v) = sp.edit().putInt("resHeight", v).apply()
    var fps: Int
        get() = int("fps", 30); set(v) = sp.edit().putInt("fps", v).apply()
    var hevc: Boolean
        get() = bool("hevc", false); set(v) = sp.edit().putBoolean("hevc", v).apply()
    /** 0 = automatic based on resolution/fps. */
    var bitrateMbps: Int
        get() = int("bitrateMbps", 0); set(v) = sp.edit().putInt("bitrateMbps", v).apply()
    var audio: Boolean
        get() = bool("audio", false); set(v) = sp.edit().putBoolean("audio", v).apply()
    /** -1 = follow device orientation, otherwise 0/90/180/270 clockwise rotation of the buffer. */
    var rotation: Int
        get() = int("rotation", -1); set(v) = sp.edit().putInt("rotation", v).apply()

    // ---- pro controls ----
    var exposureAuto: Boolean
        get() = bool("exposureAuto", true); set(v) = sp.edit().putBoolean("exposureAuto", v).apply()
    var evIndex: Int
        get() = int("evIndex", 0); set(v) = sp.edit().putInt("evIndex", v).apply()
    var iso: Int
        get() = int("iso", 400); set(v) = sp.edit().putInt("iso", v).apply()
    var shutterNs: Long
        get() = sp.getLong("shutterNs", 16_666_667L); set(v) = sp.edit().putLong("shutterNs", v).apply()
    var focusAuto: Boolean
        get() = bool("focusAuto", true); set(v) = sp.edit().putBoolean("focusAuto", v).apply()
    /** 0 = nearest focus, 1 = infinity. */
    var focusPos: Float
        get() = float("focusPos", 1f); set(v) = sp.edit().putFloat("focusPos", v).apply()
    var wbAuto: Boolean
        get() = bool("wbAuto", true); set(v) = sp.edit().putBoolean("wbAuto", v).apply()
    var wbKelvin: Int
        get() = int("wbKelvin", 5000); set(v) = sp.edit().putInt("wbKelvin", v).apply()
    var zoom: Float
        get() = float("zoom", 1f); set(v) = sp.edit().putFloat("zoom", v).apply()
    var peaking: Boolean
        get() = bool("peaking", true); set(v) = sp.edit().putBoolean("peaking", v).apply()

    // ---- motion ----
    /** 1 (least sensitive) .. 10 (most sensitive). */
    var sensitivity: Int
        get() = int("sensitivity", 5); set(v) = sp.edit().putInt("sensitivity", v).apply()
    /** Minimum percent of the frame that must change. */
    var minAreaPct: Float
        get() = float("minAreaPct", 1.0f); set(v) = sp.edit().putFloat("minAreaPct", v).apply()
    /** Seconds without motion before a clip ends (30..60 recommended). */
    var stopDelaySec: Int
        get() = int("stopDelaySec", 30); set(v) = sp.edit().putInt("stopDelaySec", v).apply()
    var maxClipMin: Int
        get() = int("maxClipMin", 10); set(v) = sp.edit().putInt("maxClipMin", v).apply()

    // ---- housekeeping / protection ----
    var maxStorageGb: Int
        get() = int("maxStorageGb", 20); set(v) = sp.edit().putInt("maxStorageGb", v).apply()
    var keepDays: Int
        get() = int("keepDays", 7); set(v) = sp.edit().putInt("keepDays", v).apply()
    var minBattery: Int
        get() = int("minBattery", 10); set(v) = sp.edit().putInt("minBattery", v).apply()
    var maxTempC: Int
        get() = int("maxTempC", 43); set(v) = sp.edit().putInt("maxTempC", v).apply()

    // ---- remote view ----
    var streamHd: Boolean
        get() = bool("streamHd", false); set(v) = sp.edit().putBoolean("streamHd", v).apply()
    var serverOn: Boolean
        get() = bool("serverOn", true); set(v) = sp.edit().putBoolean("serverOn", v).apply()
    var port: Int
        get() = int("port", 8080); set(v) = sp.edit().putInt("port", v).apply()
    var password: String
        get() = str("password", ""); set(v) = sp.edit().putString("password", v).apply()
}
