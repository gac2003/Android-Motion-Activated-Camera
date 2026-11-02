package com.motioncam

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Time formatting that follows the 12/24-hour setting. */
object Fmt {
    fun time(prefs: Prefs, seconds: Boolean = true): SimpleDateFormat {
        val p = if (prefs.use24h) (if (seconds) "HH:mm:ss" else "HH:mm") else (if (seconds) "h:mm:ss a" else "h:mm a")
        return SimpleDateFormat(p, Locale.getDefault())
    }

    fun dateTime(prefs: Prefs): SimpleDateFormat =
        SimpleDateFormat("EEE, MMM d  " + time(prefs).toPattern(), Locale.getDefault())

    fun t(prefs: Prefs, ms: Long, seconds: Boolean = true): String = time(prefs, seconds).format(Date(ms))
}
