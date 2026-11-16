package com.motioncam.ai

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock

/**
 * Heat rules for on-device AI: AI is the first thing to give up when the phone warms, long before the camera's own
 * heat pause, and it comes back only after the phone has stayed cool for a while (so it doesn't flap).
 *
 *  - half the frames: Android's heat level "light", within 5 °C of your temperature limit, or almost no thermal
 *    headroom left;
 *  - off: Android's heat level "moderate" or worse, or within 3 °C of your limit;
 *  - back to half 5 minutes after the last "off" reading, back to full 2 minutes after the last "half" reading.
 *
 * Fed by the camera engine's once-a-second housekeeping, only while the AI model is in memory.
 */
object AiGovernor {
    const val FULL = 0
    const val REDUCED = 1
    const val OFF = 2
    private const val OFF_HOLD_MS = 5 * 60_000L
    private const val REDUCED_HOLD_MS = 2 * 60_000L

    @Volatile var level = FULL; private set
    @Volatile var why = ""; private set
    @Volatile var reducedMs = 0L; private set
    @Volatile var offMs = 0L; private set
    private var lastHotAt = 0L
    private var lastWarmAt = 0L
    private var lastSample = 0L
    private var lastHeadroomAt = 0L
    private var headroom = Float.NaN

    /** One reading: battery temperature, Android's heat level, your temperature limit (0 = none). */
    @Synchronized
    fun sample(ctx: Context, tempC: Float, thermal: Int, limitC: Int) {
        val now = SystemClock.elapsedRealtime()
        // how much room is left before Android calls the phone "severely" hot (1.0); asked at most every 10 s
        if (now - lastHeadroomAt > 10_000L) {
            lastHeadroomAt = now
            headroom = try { (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).getThermalHeadroom(10) } catch (_: Throwable) { Float.NaN }
        }
        val hr = headroom
        var reason = ""
        val hot = when {
            thermal >= PowerManager.THERMAL_STATUS_MODERATE -> { reason = "Android reports the phone as warm"; true }
            limitC > 0 && tempC >= limitC - 3 -> { reason = "battery %.0f °C, close to your limit of %d °C".format(tempC, limitC); true }
            else -> false
        }
        val warm = hot || when {
            thermal >= PowerManager.THERMAL_STATUS_LIGHT -> { reason = "Android reports the phone as slightly warm"; true }
            limitC > 0 && tempC >= limitC - 5 -> { reason = "battery %.0f °C, nearing your limit of %d °C".format(tempC, limitC); true }
            !hr.isNaN() && hr >= 0.9f -> { reason = "little room left before the phone is hot"; true }
            else -> false
        }
        if (hot) lastHotAt = now
        if (warm) lastWarmAt = now
        val l = when {
            hot || (lastHotAt > 0 && now - lastHotAt < OFF_HOLD_MS) -> OFF
            warm || (lastWarmAt > 0 && now - lastWarmAt < REDUCED_HOLD_MS) -> REDUCED
            else -> FULL
        }
        // time spent throttled, for Diagnostics (readings come about once a second; long gaps aren't counted)
        val dt = if (lastSample > 0) (now - lastSample).coerceIn(0L, 3000L) else 0L
        lastSample = now
        if (level == REDUCED) reducedMs += dt else if (level == OFF) offMs += dt
        if (l != FULL && reason.isEmpty()) reason = "cooling down"
        why = if (l == FULL) "" else reason
        level = l
    }

    fun levelText(): String = when (level) { FULL -> "full"; REDUCED -> "half (${why})"; else -> "off (${why})" }
}
