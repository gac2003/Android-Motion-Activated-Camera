package com.motioncam.live

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.motioncam.Fmt
import com.motioncam.Prefs
import org.json.JSONObject
import java.util.Calendar

/**
 * Notifications on viewing phones, designed not to nag:
 *  - one Android channel per kind (motion, clips, camera status, connection, sync), so each can also be silenced or
 *    given its own sound in the phone's settings;
 *  - motion: at most one alert per camera every N minutes; motion in between updates the same notification
 *    silently ("3 times since 1:32");
 *  - battery: once when it drops below the threshold (again only after it recovered or charged);
 *  - camera offline: only after it stayed offline for N minutes (short drops are ignored), and only if it was
 *    monitoring; "back online" replaces the same notification;
 *  - quiet hours (silent, offline-only, or nothing) and per-camera mute (1 h / 8 h / until turned back on), also
 *    straight from the notification;
 *  - notifications of one camera are grouped together.
 */
object Alerts {
    const val CH_MOTION = "live_motion"
    const val CH_CLIP = "live_clip"
    const val CH_STATUS = "live_status"
    const val CH_CONN = "live_conn"
    const val CH_SYNC = "live_sync"
    const val CH_QUIET = "live_quiet"
    const val EXTRA_CAM = "cam"

    private val main = Handler(Looper.getMainLooper())

    fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_MOTION, "Motion on a camera", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_CLIP, "New clips", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_STATUS, "Camera battery, heat and monitoring", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(CH_CONN, "Camera offline / back online", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(CH_SYNC, "Collection sync problems", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_QUIET, "During quiet hours (silent)", NotificationManager.IMPORTANCE_LOW))
    }

    // ------------------------------------------------------------------ mute / quiet hours

    private fun muteSp(ctx: Context) = ctx.getSharedPreferences("alerts_mute", Context.MODE_PRIVATE)
    /** 0 = not muted; Long.MAX_VALUE = until turned back on. */
    fun mutedUntil(ctx: Context, cam: String) = muteSp(ctx).getLong(cam, 0).takeIf { it > System.currentTimeMillis() } ?: 0L
    fun mute(ctx: Context, cam: String, minutes: Int) =
        muteSp(ctx).edit().putLong(cam, if (minutes <= 0) Long.MAX_VALUE else System.currentTimeMillis() + minutes * 60_000L).apply()
    fun unmute(ctx: Context, cam: String) = muteSp(ctx).edit().remove(cam).apply()

    fun inQuietHours(p: Prefs): Boolean {
        if (!p.quietOn) return false
        val c = Calendar.getInstance()
        val m = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        return if (p.quietFrom <= p.quietTo) m in p.quietFrom until p.quietTo else m >= p.quietFrom || m < p.quietTo
    }

    /** null = don't show; true = show silently; false = show normally. */
    private fun gate(ctx: Context, cam: String, offlineKind: Boolean): Boolean? {
        val p = Prefs(ctx)
        if (!p.alertsOn || mutedUntil(ctx, cam) > 0) return null
        if (!inQuietHours(p)) return false
        return when (p.quietMode) {
            0 -> true
            1 -> if (offlineKind) true else null
            else -> null
        }
    }

    // ------------------------------------------------------------------ posting

    private fun camName(cam: String) = Live.phones[cam]?.name?.ifEmpty { null } ?: "Camera"
    private fun nid(cam: String, kind: String) = (cam + kind).hashCode() and 0x7fffffff

    private fun post(ctx: Context, cam: String, kind: String, channel: String, title: String, text: String, silent: Boolean, update: Boolean = false) {
        AlertInbox.add(ctx, cam, kind, title, text, update)
        if (Prefs(ctx).alertsInApp) {
            // "inside MotionCam only": a banner on the open screen (not for silent updates), nothing in the phone's notifications
            if (!update) AlertInbox.banner(cam, title, text)
            return
        }
        try {
            channels(ctx)
            val open = PendingIntent.getActivity(ctx, nid(cam, "open"),
                Intent(ctx, RemoteCameraActivity::class.java).putExtra(EXTRA_CAM, cam).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
            fun mute(min: Int, label: String) = Notification.Action.Builder(null, label, PendingIntent.getBroadcast(ctx, nid(cam, "mute$min"),
                Intent(ctx, AlertActionReceiver::class.java).putExtra(EXTRA_CAM, cam).putExtra("min", min), PendingIntent.FLAG_IMMUTABLE)).build()
            val n = Notification.Builder(ctx, if (silent) CH_QUIET else channel)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle(title).setContentText(text).setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(open).setAutoCancel(true)
                .setGroup("cam_$cam")
                .setOnlyAlertOnce(update)          // updating an alert never rings again
                .setWhen(System.currentTimeMillis()).setShowWhen(true)
                .addAction(mute(60, "Mute 1 h")).addAction(mute(8 * 60, "Mute 8 h"))
                .build()
            ctx.getSystemService(NotificationManager::class.java).notify(nid(cam, kind), n)
        } catch (_: Exception) {}
    }

    private fun cancel(ctx: Context, cam: String, kind: String) =
        try { ctx.getSystemService(NotificationManager::class.java).cancel(nid(cam, kind)) } catch (_: Exception) {}

    // ------------------------------------------------------------------ events from cameras

    private class MotionState(var lastAlert: Long = 0, var count: Int = 0, var since: Long = 0)
    private val motion = HashMap<String, MotionState>()

    fun onEvent(ctx: Context, cam: String, t: Long, d: JSONObject) {
        val p = Prefs(ctx)
        val type = d.optString("type")
        val name = camName(cam)
        when (type) {
            "motion" -> {
                if (!p.alertMotion) return
                val silent = gate(ctx, cam, false) ?: return
                val st = motion.getOrPut(cam) { MotionState() }
                val now = System.currentTimeMillis()
                if (now - st.lastAlert < p.alertMotionGapMin * 60_000L) {
                    // within the quiet gap: update the same notification, no sound
                    st.count++
                    post(ctx, cam, "motion", CH_MOTION, "Motion · $name",
                        "Motion ${st.count} times since ${Fmt.t(p, st.since, seconds = false)}", silent = true, update = true)
                } else {
                    st.lastAlert = now; st.count = 1; st.since = now
                    post(ctx, cam, "motion", CH_MOTION, "Motion · $name", "Motion detected at ${Fmt.t(p, now, seconds = false)} — recording", silent)
                }
            }
            "clip" -> if (p.alertClip) gate(ctx, cam, false)?.let { s -> post(ctx, cam, "clip", CH_CLIP, "New clip · $name", d.optString("text"), s) }
            "hot" -> if (p.alertHot) gate(ctx, cam, false)?.let { s -> post(ctx, cam, "hot", CH_STATUS, "Camera too hot · $name", d.optString("text"), s) }
            "unplugged" -> if (p.alertCharger) gate(ctx, cam, false)?.let { s -> post(ctx, cam, "power", CH_STATUS, "Charger unplugged · $name", d.optString("text"), s) }
            "monitor_off", "monitor_paused", "safety_stop" ->
                if (p.alertMonitoring) gate(ctx, cam, false)?.let { s -> post(ctx, cam, "monitor", CH_STATUS, "Monitoring stopped · $name", d.optString("text"), s) }
            "monitor_on" -> cancel(ctx, cam, "monitor")
        }
    }

    // ------------------------------------------------------------------ status (battery)

    private val batteryAlerted = HashMap<String, Boolean>()

    fun onStatus(ctx: Context, cam: String, prev: JSONObject?, cur: JSONObject) {
        val p = Prefs(ctx)
        val pct = cur.optInt("battery", -1)
        val charging = cur.optBoolean("charging")
        if (pct < 0) return
        if (charging || pct > p.alertBatteryPct + 5) { batteryAlerted.remove(cam); cancel(ctx, cam, "battery"); return }
        if (pct <= p.alertBatteryPct && batteryAlerted[cam] != true && p.alertBattery && cur.optBoolean("monitoring")) {
            batteryAlerted[cam] = true
            gate(ctx, cam, false)?.let { s -> post(ctx, cam, "battery", CH_STATUS, "Battery low · ${camName(cam)}", "Battery at $pct% and not charging.", s) }
        }
    }

    // ------------------------------------------------------------------ connection

    private val offlineTimers = HashMap<String, Runnable>()
    private val offlineShown = HashSet<String>()

    fun onPresence(ctx: Context, phone: Live.Phone) {
        val cam = phone.id
        val status = Live.cams[cam]?.status ?: return
        val p = Prefs(ctx)
        if (!phone.online) {
            // only a camera that was monitoring is "lost"; a camera stopped on purpose isn't
            if (!p.alertOffline || !status.optBoolean("monitoring")) return
            if (offlineTimers.containsKey(cam)) return
            val r = Runnable {
                offlineTimers.remove(cam)
                if (Live.phones[cam]?.online == true) return@Runnable
                val silent = gate(ctx, cam, true) ?: return@Runnable
                offlineShown += cam
                post(ctx, cam, "conn", CH_CONN, "Camera offline · ${camName(cam)}",
                    "No connection since ${Fmt.t(p, phone.seen.takeIf { it > 0 } ?: System.currentTimeMillis(), seconds = false)}. " +
                        "It may have lost internet or power; it keeps recording if it still runs.", silent)
            }
            offlineTimers[cam] = r
            main.postDelayed(r, p.alertOfflineMin.coerceAtLeast(1) * 60_000L)
        } else {
            offlineTimers.remove(cam)?.let { main.removeCallbacks(it) }
            if (offlineShown.remove(cam)) {
                if (p.alertBackOnline) post(ctx, cam, "conn", CH_CONN, "Back online · ${camName(cam)}", "The camera is connected again.", silent = true, update = true)
                else cancel(ctx, cam, "conn")
            }
        }
    }

    /** Shared collections need a decision (viewing phones). */
    fun syncConflict(ctx: Context, folder: String, count: Int) {
        if (!Prefs(ctx).alertSync) return
        post(ctx, "sync:$folder", "sync", CH_SYNC, "Collections need your decision", "$count sync conflict(s) in $folder — open Collections › Sync status.", silent = true)
    }
}

/** "Mute 1 h / 8 h" buttons on alerts. */
class AlertActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        val cam = i.getStringExtra(Alerts.EXTRA_CAM) ?: return
        Alerts.mute(ctx, cam, i.getIntExtra("min", 60))
        val nm = ctx.getSystemService(NotificationManager::class.java)
        for (k in listOf("motion", "clip", "hot", "power", "monitor", "battery", "conn")) nm.cancel((cam + k).hashCode() and 0x7fffffff)
    }
}
