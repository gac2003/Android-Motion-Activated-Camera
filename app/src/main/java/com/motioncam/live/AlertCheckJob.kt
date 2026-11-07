package com.motioncam.live

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.motioncam.MotionCamApp
import com.motioncam.Prefs

/**
 * Checks for camera alerts about every 15 minutes (Android's shortest repeat), without keeping a connection open:
 * connects for a few seconds, reads the events missed since the last check, alerts them, and disconnects. Android
 * decides the exact time (it batches jobs to save battery), so alerts can arrive up to ~15 minutes late; "Stay
 * connected" is the instant (but always-on) alternative.
 */
class AlertCheckJob : JobService() {
    companion object {
        private const val ID = 4207

        /** Schedules or cancels the check to match the settings. */
        fun schedule(ctx: Context) {
            val p = Prefs(ctx)
            val js = ctx.getSystemService(JobScheduler::class.java) ?: return
            val want = LiveConfig.configured && p.liveOn && p.alertsOn && p.alertsPoll && !p.liveStay
            if (!want) { js.cancel(ID); return }
            if (js.getPendingJob(ID) != null) return
            try {
                js.schedule(JobInfo.Builder(ID, ComponentName(ctx, AlertCheckJob::class.java))
                    .setPeriodic(15 * 60_000L)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true)
                    .build())
            } catch (_: Exception) {}
        }
    }

    private val ui = Handler(Looper.getMainLooper())
    private var params: JobParameters? = null
    private var started = 0L

    override fun onStartJob(p: JobParameters): Boolean {
        // already connected (MotionCam open, or Stay connected): alerts arrive live anyway
        if (Live.viewerActive || LiveService.running || !Live.enabled(this)) return false
        // a phone busy being a camera doesn't check on the other cameras (it would only wake its radio)
        if (com.motioncam.CameraService.monitoring) return false
        params = p
        started = System.currentTimeMillis()
        Live.bgCheck = true
        Live.viewerActive = true
        try { Live.refresh(this) } catch (_: Exception) { finish(); return false }
        ui.postDelayed(poll, 1000)
        return true
    }

    /** Done when the phone list arrived and all missed-alert reads finished (at least 4 s, at most 40 s). */
    private val poll = object : Runnable {
        override fun run() {
            val t = System.currentTimeMillis() - started
            val done = Live.devicesLoaded && Live.catchUpsRunning.get() == 0 && t >= 4000
            if (done || t >= 40_000) finish() else ui.postDelayed(this, 1000)
        }
    }

    private fun finish() {
        ui.removeCallbacks(poll)
        Live.bgCheck = false
        // disconnect again, unless MotionCam was opened meanwhile
        if (!MotionCamApp.foreground && !LiveService.running) { Live.viewerActive = false; try { Live.refresh(this) } catch (_: Exception) {} }
        params?.let { jobFinished(it, false) }
        params = null
    }

    override fun onStopJob(p: JobParameters): Boolean {
        ui.removeCallbacks(poll)
        Live.bgCheck = false
        if (!MotionCamApp.foreground && !LiveService.running) { Live.viewerActive = false; try { Live.refresh(this) } catch (_: Exception) {} }
        params = null
        return false
    }
}
