package com.motioncam

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import java.io.File

/**
 * Keeps a camera phone honest about whether it is really monitoring, and keeps a log of why it stopped:
 *
 * - [wanted]: monitoring was started and not stopped on purpose. If Android stops MotionCam while that is true (to
 *   free memory, a vendor's battery manager, a crash), the camera service is restarted by Android and resumes; when
 *   Android doesn't allow that, [CameraWatchdog] notices within ~15 minutes, and opening camera mode resumes it.
 *   Either way the camera phone shows a notification and your other phones get an alert, instead of the camera
 *   just looking offline.
 * - [alive]: a heartbeat every minute while monitoring, so the log can say when it last worked.
 * - [log]: the camera log (Settings → Camera → Camera log): crashes, restarts, camera errors, connection drops.
 */
object Health {
    private const val MAX_LINES = 400
    private fun sp(ctx: Context) = ctx.getSharedPreferences("health", Context.MODE_PRIVATE)
    private fun file(ctx: Context) = File(ctx.filesDir, "camera-log.txt")
    private val fmt get() = java.text.SimpleDateFormat("EEE d MMM HH:mm:ss", java.util.Locale.getDefault())

    // ------------------------------------------------------------------ the log

    @Synchronized
    fun log(ctx: Context, what: String) {
        try {
            val f = file(ctx)
            f.appendText(fmt.format(java.util.Date()) + "  " + what.replace('\n', ' ') + "\n")
            if (f.length() > 120_000) f.writeText(f.readLines().takeLast(MAX_LINES).joinToString("\n", postfix = "\n"))
        } catch (_: Exception) {}
    }

    fun text(ctx: Context): String = try { file(ctx).readText().ifEmpty { "Nothing logged yet." } } catch (_: Exception) { "Nothing logged yet." }

    fun clear(ctx: Context) { try { file(ctx).delete() } catch (_: Exception) {} }

    /** Crashes go into the log (then Android shows its usual message). */
    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val trace = e.stackTraceToString().lines().take(14).joinToString(" | ") { it.trim() }
                log(app, "CRASH in ${t.name}: $trace")
            } catch (_: Exception) {}
            prev?.uncaughtException(t, e)
        }
    }

    // ------------------------------------------------------------------ monitoring wanted / alive

    fun wanted(ctx: Context) = sp(ctx).getBoolean("wanted", false)

    fun setWanted(ctx: Context, on: Boolean) {
        if (wanted(ctx) == on) return
        sp(ctx).edit().putBoolean("wanted", on).apply()
        if (on) { alive(ctx); CameraWatchdog.schedule(ctx) } else CameraWatchdog.cancel(ctx)
    }

    fun alive(ctx: Context) = sp(ctx).edit().putLong("aliveAt", System.currentTimeMillis()).apply()
    fun aliveAt(ctx: Context) = sp(ctx).getLong("aliveAt", 0L)

    private fun lastSign(ctx: Context): String {
        val t = aliveAt(ctx)
        return if (t > 0) Fmt.t(Prefs(ctx), t, seconds = false) else "an unknown time"
    }

    /**
     * Monitoring should be on but MotionCam isn't running it (Android stopped it): told once per interruption — on
     * this phone (a notification to resume) and to your other phones (an alert, also by push).
     */
    fun reportInterrupted(ctx: Context, how: String) {
        val app = ctx.applicationContext
        val at = aliveAt(app)
        if (sp(app).getLong("reportedFor", -1L) == at) return
        sp(app).edit().putLong("reportedFor", at).apply()
        val since = lastSign(app)
        log(app, "Monitoring interrupted ($how); last sign of life $since")
        notifyHere(app, "Monitoring stopped unexpectedly",
            "Android closed MotionCam around $since. Tap to resume monitoring.")
        tellOthers(app, "Monitoring stopped unexpectedly around $since (Android closed MotionCam on the camera phone) · " +
            "open MotionCam there to resume")
    }

    /** It came back by itself after an interruption. */
    fun reportResumed(ctx: Context) {
        val since = lastSign(ctx)
        log(ctx, "Monitoring resumed by itself (last sign of life before: $since)")
        try { com.motioncam.live.CameraLink.event(ctx, "monitor_on", "Monitoring resumed by itself after Android stopped MotionCam (around $since)") } catch (_: Exception) {}
    }

    private fun notifyHere(ctx: Context, title: String, text: String) {
        try {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("camera_problems", "Camera problems", NotificationManager.IMPORTANCE_HIGH))
            val open = PendingIntent.getActivity(ctx, 77, Intent(ctx, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_RESUME, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            nm.notify(4711, Notification.Builder(ctx, "camera_problems").setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle(title).setContentText(text).setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(open).setAutoCancel(true).build())
        } catch (_: Exception) {}
    }

    /** An Activity entry (and a push) for your other phones, from a process that may not be connected yet. */
    private fun tellOthers(ctx: Context, text: String) {
        if (!com.motioncam.live.Live.enabled(ctx) || !Prefs(ctx).liveCamera) return
        try { com.motioncam.live.LiveNet.db(ctx)?.goOnline() } catch (_: Exception) {}
        try { com.motioncam.live.CameraLink.eventDirect(ctx, "safety_stop", text) } catch (_: Exception) {}
    }
}

/**
 * Every ~15 minutes while monitoring is wanted: is it really running? If Android stopped MotionCam and couldn't
 * restart it, this says so (see [Health.reportInterrupted]). Does nothing (and costs nothing) otherwise.
 */
class CameraWatchdog : JobService() {
    companion object {
        private const val ID = 4208
        fun schedule(ctx: Context) {
            val js = ctx.getSystemService(JobScheduler::class.java) ?: return
            if (js.getPendingJob(ID) != null) return
            try {
                js.schedule(JobInfo.Builder(ID, ComponentName(ctx, CameraWatchdog::class.java))
                    .setPeriodic(15 * 60_000L).setPersisted(true).build())
            } catch (_: Exception) {}
        }
        fun cancel(ctx: Context) { try { ctx.getSystemService(JobScheduler::class.java)?.cancel(ID) } catch (_: Exception) {} }
    }

    override fun onStartJob(p: JobParameters): Boolean {
        if (!Health.wanted(this)) { cancel(this); return false }
        if (CameraService.monitoring) return false
        Health.reportInterrupted(this, "found by the 15-minute check")
        // give the alert a few seconds to reach the live connection
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ jobFinished(p, false) }, 15_000)
        return true
    }

    override fun onStopJob(p: JobParameters) = false
}
