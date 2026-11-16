package com.motioncam.ai

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock

/**
 * Heat rules for on-device AI: AI is the first thing to give up when the phone warms, long before the camera's own
 * heat pause, and it comes back only after the phone has stayed cool for a while (so it doesn't flap).
 *
 *  - half the frames: within 2 °C of the AI temperature limit (Settings; by default 3 °C under the camera's own pause);
 *  - off: at the AI temperature limit, or when Android itself reports the phone as severely hot;
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

    /** One reading: battery temperature, Android's heat level, the AI temperature limit. */
    @Synchronized
    fun sample(ctx: Context, tempC: Float, thermal: Int, limitC: Int) {
        val now = SystemClock.elapsedRealtime()
        var reason = ""
        // your AI temperature limit decides; Android's own signal only at "severe" (one step before the camera's own pause)
        val hot = when {
            thermal >= PowerManager.THERMAL_STATUS_SEVERE -> { reason = "Android reports the phone as very hot (battery %.0f °C)".format(tempC); true }
            limitC > 0 && tempC >= limitC -> { reason = "battery %.0f °C, the AI limit is %d °C".format(tempC, limitC); true }
            else -> false
        }
        val warm = hot || when {
            limitC > 0 && tempC >= limitC - 2 -> { reason = "battery %.0f °C, nearing the AI limit of %d °C".format(tempC, limitC); true }
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
