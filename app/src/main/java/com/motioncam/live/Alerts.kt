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

    private fun post(ctx: Context, cam: String, kind: String, channel: String, title: String, text: String, silent: Boolean, update: Boolean = false,
                     clip: JSONObject? = null, at: Long = System.currentTimeMillis()) {
        AlertInbox.add(ctx, cam, kind, title, text, update, clip?.toString(), at)
        if (Prefs(ctx).alertsInApp) {
            // "inside MotionCam only": a banner on the open screen (not for silent updates), nothing in the phone's notifications
            if (!update) AlertInbox.banner(cam, title, text)
            return
        }
        try {
            channels(ctx)
            // tapping opens the clip when the alert is about one (motion once its clip is saved, new clip), else the camera
            val open = if (clip != null) PendingIntent.getActivity(ctx, nid(cam, "clip_$kind"),
                    OpenClipActivity.intent(ctx, cam, clip.toString()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                else PendingIntent.getActivity(ctx, nid(cam, "open"),
                    Intent(ctx, RemoteCameraActivity::class.java).putExtra(EXTRA_CAM, cam).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
            fun mute(min: Int, label: String) = Notification.Action.Builder(null, label, PendingIntent.getBroadcast(ctx, nid(cam, "mute$min"),
                Intent(ctx, AlertActionReceiver::class.java).putExtra(EXTRA_CAM, cam).putExtra("min", min), PendingIntent.FLAG_IMMUTABLE)).build()
            val n = Notification.Builder(ctx, if (silent) CH_QUIET else channel)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle(title).setContentText(text).setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(open).setAutoCancel(true)
                .setGroup("cam_$cam")
                .setOnlyAlertOnce(update)          // updating an alert never rings again
                .setWhen(at).setShowWhen(true)
                .apply {
                    if (clip != null) addAction(Notification.Action.Builder(null, "Watch clip", open).build())
                    else if (kind == "motion") addAction(Notification.Action.Builder(null, "Live view", PendingIntent.getActivity(ctx, nid(cam, "live"),
                        Intent(ctx, RemoteLiveActivity::class.java).putExtra(EXTRA_CAM, cam).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)).build())
                }
                .addAction(mute(60, "Mute 1 h")).addAction(mute(8 * 60, "Mute 8 h"))
                .build()
            ctx.getSystemService(NotificationManager::class.java).notify(nid(cam, kind), n)
        } catch (_: Exception) {}
    }

    private fun cancel(ctx: Context, cam: String, kind: String) =
        try { ctx.getSystemService(NotificationManager::class.java).cancel(nid(cam, kind)) } catch (_: Exception) {}

    // ------------------------------------------------------------------ events from cameras

    private class MotionState(var lastAlert: Long = 0, var count: Int = 0, var since: Long = 0, var clip: JSONObject? = null, var silent: Boolean = false)

    /** The clip a "clip" event is about (name + backup folders), or null for cameras on older versions. */
    fun clipRef(d: JSONObject): JSONObject? {
        val name = d.optString("name").ifEmpty { return null }
        return JSONObject().put("name", name).put("dur", d.optLong("dur")).put("folders", d.optJSONArray("folders") ?: org.json.JSONArray())
    }

    private fun motionText(p: Prefs, st: MotionState): String {
        val base = if (st.count <= 1) "Motion detected at ${Fmt.t(p, st.since, seconds = false)}" else "Motion ${st.count} times since ${Fmt.t(p, st.since, seconds = false)}"
        val c = st.clip ?: return if (st.count <= 1) "$base — recording" else base
        val sec = (c.optLong("dur") / 1000).coerceAtLeast(1)
        return "$base · ${sec}s clip saved — tap to watch"
    }
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
                    post(ctx, cam, "motion", CH_MOTION, "Motion · $name", motionText(p, st), silent = true, update = true, clip = st.clip)
                } else {
                    st.lastAlert = now; st.count = 1; st.since = now; st.clip = null
                    post(ctx, cam, "motion", CH_MOTION, "Motion · $name", motionText(p, st), silent)
                }
            }
            "clip" -> {
                val ref = clipRef(d)
                // the motion alert now leads to the clip it recorded (updated silently)
                val st = motion[cam]
                if (ref != null && p.alertMotion && st != null && System.currentTimeMillis() - st.lastAlert < maxOf(p.alertMotionGapMin, 10) * 60_000L) {
                    st.clip = ref
                    gate(ctx, cam, false)?.let { post(ctx, cam, "motion", CH_MOTION, "Motion · $name", motionText(p, st), silent = true, update = true, clip = ref) }
                }
                if (p.alertClip) gate(ctx, cam, false)?.let { s -> post(ctx, cam, "clip", CH_CLIP, "New clip · $name",
                    d.optString("text") + if (ref != null) " — tap to watch" else "", s, clip = ref) }
            }
            "hot" -> if (p.alertHot) gate(ctx, cam, false)?.let { s -> post(ctx, cam, "hot", CH_STATUS, "Camera too hot · $name", d.optString("text"), s) }
            "unplugged" -> if (p.alertCharger) gate(ctx, cam, false)?.let { s -> post(ctx, cam, "power", CH_STATUS, "Charger unplugged · $name", d.optString("text"), s) }
            "monitor_off", "monitor_paused", "safety_stop" ->
                if (p.alertMonitoring) gate(ctx, cam, false)?.let { s -> post(ctx, cam, "monitor", CH_STATUS, "Monitoring stopped · $name", d.optString("text"), s) }
            "monitor_on" -> cancel(ctx, cam, "monitor")
        }
    }

    /**
     * Alerts missed while this phone wasn't connected (MotionCam closed, no "Stay connected"): the camera keeps 7 days of
     * events in the live connection, so they are read when the connection starts and added to the Alerts list at their
     * own times. Motion and clips are summarised per camera; nothing rings (they are old news), a banner or one silent
     * notification says what was missed.
     */
    fun catchUp(ctx: Context, cam: String, events: List<Pair<Long, JSONObject>>) {
        val p = Prefs(ctx)
        if (!p.alertsOn || mutedUntil(ctx, cam) > 0 || events.isEmpty()) return
        val name = camName(cam)
        fun at(t: Long) = t - Live.serverOffset
        fun tm(t: Long) = Fmt.t(p, at(t), seconds = false) + dayNote(at(t))
        val out = ArrayList<Triple<Long, String, Pair<String, String>>>()
        val motions = if (p.alertMotion) events.filter { it.second.optString("type") == "motion" }.map { it.first } else emptyList()
        if (motions.isNotEmpty()) out += Triple(at(motions.max()), "motion", "Motion · $name" to
            if (motions.size == 1) "Motion detected at ${tm(motions[0])}" else "Motion ${motions.size} times between ${tm(motions.min())} and ${tm(motions.max())}")
        val clips = if (p.alertClip) events.filter { it.second.optString("type") == "clip" }.map { it.first } else emptyList()
        if (clips.isNotEmpty()) out += Triple(at(clips.max()), "clip", "New clips · $name" to
            (if (clips.size == 1) "1 clip saved at ${tm(clips[0])}" else "${clips.size} clips saved between ${tm(clips.min())} and ${tm(clips.max())}"))
        for ((t, d) in events) {
            val (kind, title, ok) = when (d.optString("type")) {
                "hot" -> Triple("hot", "Camera too hot · $name", p.alertHot)
                "unplugged" -> Triple("power", "Charger unplugged · $name", p.alertCharger)
                "monitor_off", "monitor_paused", "safety_stop" -> Triple("monitor", "Monitoring stopped · $name", p.alertMonitoring)
                else -> Triple("", "", false)
            }
            if (ok) out += Triple(at(t), kind, title to (d.optString("text") + " (" + tm(t) + ")"))
        }
        if (out.isEmpty()) return
        val latestClip = events.lastOrNull { it.second.optString("type") == "clip" }?.let { clipRef(it.second) }
        // the 15-minute background check: these are fresh enough to alert like live ones (sound, quiet hours apply)
        if (Live.bgCheck && AlertInbox.front?.get() == null && !p.alertsInApp) {
            for ((t, kind, tt) in out) {
                val silent = gate(ctx, cam, false) ?: continue
                val ch = when (kind) { "motion" -> CH_MOTION; "clip" -> CH_CLIP; else -> CH_STATUS }
                post(ctx, cam, kind, ch, tt.first, tt.second + if (latestClip != null && (kind == "motion" || kind == "clip")) " — tap to watch the latest" else "",
                    silent || kind == "clip", clip = if (kind == "motion" || kind == "clip") latestClip else null, at = t)
            }
            return
        }
        AlertInbox.addPast(ctx, cam, out, latestClip?.toString())
        val summary = listOfNotNull(
            motions.size.takeIf { it > 0 }?.let { "motion ×$it" },
            clips.size.takeIf { it > 0 }?.let { "$it clip${if (it == 1) "" else "s"}" },
            (out.size - (if (motions.isNotEmpty()) 1 else 0) - (if (clips.isNotEmpty()) 1 else 0)).takeIf { it > 0 }?.let { "$it other" },
        ).joinToString(" · ")
        val title = "While you were away · $name"
        if (AlertInbox.front?.get() != null || p.alertsInApp) { AlertInbox.banner(cam, title, "$summary — tap to see the list", openList = true); return }
        try {
            channels(ctx)
            val open = PendingIntent.getActivity(ctx, nid(cam, "missed"),
                Intent(ctx, AlertsInboxActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
            ctx.getSystemService(NotificationManager::class.java).notify(nid(cam, "missed"), Notification.Builder(ctx, CH_QUIET)
                .setSmallIcon(android.R.drawable.ic_menu_camera).setContentTitle(title).setContentText(summary)
                .setContentIntent(open).setAutoCancel(true).setGroup("cam_$cam").build())
        } catch (_: Exception) {}
    }

    /** " (Mon)" for an earlier day, so a summary spanning days reads right. */
    private fun dayNote(ms: Long): String {
        val c = Calendar.getInstance(); val today = c.get(Calendar.DAY_OF_YEAR) to c.get(Calendar.YEAR)
        c.timeInMillis = ms
        return if ((c.get(Calendar.DAY_OF_YEAR) to c.get(Calendar.YEAR)) == today) ""
            else " (" + java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault()).format(java.util.Date(ms)) + ")"
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
