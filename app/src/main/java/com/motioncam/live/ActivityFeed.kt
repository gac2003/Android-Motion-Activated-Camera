package com.motioncam.live

import android.content.Context
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import com.motioncam.cloud.Device
import org.json.JSONObject

/** One camera event; a motion event carries the clip it recorded ([clip], the "clip saved" event) when there is one. */
class ActivityEvent(val cam: String, val t: Long, val d: JSONObject) {
    var clip: JSONObject? = null
    val type: String get() = d.optString("type")
}

object ActivityTypes {
    /**
     * What the all-cameras views show: what happened at the cameras (motion and clips, monitoring started / stopped,
     * heat, power, battery, PIN lock-outs) — not housekeeping such as settings changed or who had the remote.
     */
    val OVERVIEW = setOf("motion", "clip", "monitor_on", "monitor_off", "monitor_paused", "safety_stop", "hot", "unplugged", "battery", "pin_locked")

    fun icon(type: String) = when (type) {
        "motion" -> "🏃"; "clip" -> "🎞"; "monitor_on" -> "▶"; "monitor_off", "monitor_paused" -> "⏸"; "safety_stop" -> "⛔"
        "battery" -> "🔋"; "unplugged" -> "🔌"; "hot" -> "🌡"; "pin_locked" -> "🔒"; "remote_taken" -> "🎮"; "settings_local" -> "⚙"; else -> "•"
    }

    /**
     * Puts each "clip saved" under the motion that started it (the camera sends motion when recording starts and the
     * clip when it is saved), so one recording is one entry. Clips without a motion before them stay on their own.
     * Returns newest first.
     */
    fun pair(events: List<ActivityEvent>): List<ActivityEvent> {
        val out = ArrayList<ActivityEvent>()
        for ((_, list) in events.groupBy { it.cam }) {
            var motion: ActivityEvent? = null
            for (e0 in list.sortedBy { it.t }) {
                val e = ActivityEvent(e0.cam, e0.t, e0.d)   // a copy: pairing again (on every update) starts fresh
                when (e.type) {
                "motion" -> { motion = e; out += e }
                "clip" -> {
                    val m = motion
                    if (m != null && m.clip == null && e.t - m.t < 3 * 3_600_000L) { m.clip = e.d; motion = null } else out += e
                }
                else -> out += e
                }
            }
        }
        return out.sortedByDescending { it.t }
    }
}

/**
 * Follows the events of every camera on the live connection (the last [perCam] of each), live: [onChange] runs on
 * the main thread whenever any of them changes. Call [sync] when the phone list changes, [stop] when done.
 */
class ActivityFeed(private val ctx: Context, private val perCam: Int, private val onChange: () -> Unit) {
    private val listeners = HashMap<String, ValueEventListener>()
    private val byCam = java.util.concurrent.ConcurrentHashMap<String, List<ActivityEvent>>()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    var loaded = false; private set

    /** Every other phone that may have events (cameras come and go, so all of them). */
    fun sync() {
        val me = Device.id(ctx)
        val cams = (Live.phones.keys + Live.cams.keys).filter { it != me }.toSet()
        for (c in cams) if (!listeners.containsKey(c)) {
            val l = object : ValueEventListener {
                override fun onDataChange(s: DataSnapshot) {
                    byCam[c] = s.children.mapNotNull { e ->
                        val t = (e.child("t").value as? Number)?.toLong() ?: return@mapNotNull null
                        val d = LiveNet.open(ctx, e.child("d").value as? String) ?: return@mapNotNull null
                        try { ActivityEvent(c, t - Live.serverOffset, JSONObject(d)) } catch (_: Exception) { null }
                    }
                    loaded = true
                    // keeps "new activity" (the dot) right, also for events that arrived while this phone was offline
                    byCam[c]?.filter { it.type in ActivityTypes.OVERVIEW }?.maxOfOrNull { it.t }?.let { AlertInbox.noteEvent(ctx, c, it) }
                    main.post(onChange)
                }
                override fun onCancelled(e: DatabaseError) {}
            }
            Live.ref(ctx, "cams/$c/events")?.orderByChild("t")?.limitToLast(perCam)?.addValueEventListener(l)
            listeners[c] = l
        }
        if (cams.isEmpty() && !loaded) { loaded = true; main.post(onChange) }
    }

    fun stop() {
        for ((c, l) in listeners) Live.ref(ctx, "cams/$c/events")?.removeEventListener(l)
        listeners.clear()
    }

    /** All cameras' events (only [types] if given), each recording as one entry, newest first. */
    fun events(types: Set<String>? = ActivityTypes.OVERVIEW): List<ActivityEvent> =
        ActivityTypes.pair(byCam.values.flatten().filter { types == null || it.type in types })
}
