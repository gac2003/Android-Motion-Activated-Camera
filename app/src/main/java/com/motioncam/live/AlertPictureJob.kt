package com.motioncam.live

import android.app.NotificationManager
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject

/**
 * A clip alert whose picture didn't come in time (slow network, or the phone couldn't connect while locked): one more
 * try later, once Android says there is internet. Strictly bounded, so it can never become background work of its own:
 *  - only exists after a picture failed (nothing is scheduled otherwise), one job for all of them;
 *  - at most [MAX_TRIES] tries per picture, Android's growing pauses in between (30 s, 1 min, 2 min…), and only
 *    while the database couldn't be reached (an answer without a picture ends it: nothing to wait for);
 *  - a picture is given up [KEEP_MS] after its alert, or as soon as its alert isn't shown any more (swiped away,
 *    opened): then nothing is fetched at all;
 *  - connects only for the fetch, and disconnects again unless MotionCam is using the connection.
 */
class AlertPictureJob : JobService() {
    companion object {
        const val ID = 4209
        private const val MAX_TRIES = 4
        private const val KEEP_MS = 30 * 60_000L
        private const val MAX_PENDING = 10

        private fun sp(ctx: Context) = ctx.getSharedPreferences("alert_pictures", Context.MODE_PRIVATE)

        @Synchronized private fun pending(ctx: Context): JSONArray =
            try { JSONArray(sp(ctx).getString("p", "[]")) } catch (_: Exception) { JSONArray() }

        @Synchronized private fun save(ctx: Context, a: JSONArray) { sp(ctx).edit().putString("p", a.toString()).apply() }

        /** [name]'s picture is still missing from [cam]'s alert: try again once there is internet. */
        @Synchronized fun add(ctx: Context, cam: String, name: String) {
            val now = System.currentTimeMillis()
            val a = pending(ctx)
            val out = JSONArray()
            for (i in 0 until a.length()) a.optJSONObject(i)?.let {
                if (now - it.optLong("at") < KEEP_MS && !(it.optString("c") == cam && it.optString("n") == name)) out.put(it)
            }
            out.put(JSONObject().put("c", cam).put("n", name).put("at", now).put("tries", 0))
            while (out.length() > MAX_PENDING) out.remove(0)
            save(ctx, out)
            val js = ctx.getSystemService(JobScheduler::class.java) ?: return
            if (js.getPendingJob(ID) != null) return
            try {
                js.schedule(JobInfo.Builder(ID, ComponentName(ctx, AlertPictureJob::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setMinimumLatency(20_000L)
                    .setBackoffCriteria(30_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                    .build())
            } catch (_: Exception) {}
        }

        /** Is an alert about [name] still on screen? (No point fetching a picture for one that's gone.) */
        private fun shown(ctx: Context, name: String): Boolean = try {
            ctx.getSystemService(NotificationManager::class.java).activeNotifications.any { it.notification?.extras?.getString("mc_clip") == name }
        } catch (_: Exception) { false }
    }

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var stopped = false

    override fun onStartJob(params: JobParameters): Boolean {
        val app = applicationContext
        Thread {
            var again = false
            try {
                val now = System.currentTimeMillis()
                val todo = pending(app)
                val keep = JSONArray()
                var online = false
                for (i in 0 until todo.length()) {
                    val it = todo.optJSONObject(i) ?: continue
                    val cam = it.optString("c"); val name = it.optString("n")
                    val tries = it.optInt("tries") + 1
                    if (stopped) { keep.put(it); continue }
                    if (now - it.optLong("at") >= KEEP_MS || !shown(app, name)) continue   // too old, or not shown any more
                    if (!online) { online = true; try { LiveNet.db(app)?.goOnline() } catch (_: Exception) {} }
                    val reached = java.util.concurrent.atomic.AtomicBoolean(false)
                    val pic = try { ClipThumbStore.get(app, cam, name, Alerts.PIC_WAIT_MS, forAlert = true, reached = reached) } catch (_: Exception) { null }
                    if (pic != null) main.post { Alerts.attachPicture(app, cam, name, pic) }
                    // only a connection that didn't answer is tried again (the database answering without a picture is final)
                    else if (!reached.get() && tries < MAX_TRIES) keep.put(it.put("tries", tries))
                }
                save(app, keep)
                again = keep.length() > 0
                if (online) main.postDelayed({
                    if (!Live.viewerActive && !Live.cameraActive) try { LiveNet.db(app)?.goOffline() } catch (_: Exception) {}
                }, 1500)
            } catch (_: Exception) {}
            try { jobFinished(params, again) } catch (_: Exception) {}
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean { stopped = true; return true }
}
