package com.motioncam.live

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import com.motioncam.cloud.Device
import com.motioncam.cloud.SecretStore
import org.json.JSONObject

/**
 * Talking to one camera phone from a viewing phone: its status and capabilities, the remembered PIN, signed commands
 * with their answers, and the low-rate live view. Shared by the camera screen and the full-screen live view.
 */
class CamRemote(private val a: Activity, val cam: String) {
    companion object {
        /** The last live picture of each camera (and when it was taken), kept while MotionCam runs. */
        val lastFrames = java.util.concurrent.ConcurrentHashMap<String, Pair<Bitmap, Long>>()
    }
    private val ui = Handler(Looper.getMainLooper())

    /**
     * Why live view can't show a picture right now (null = it can): offline, or MotionCam's camera not running on
     * that phone (not monitoring and not in standby).
     */
    fun liveProblem(): String? {
        val p = Live.phones[cam]
        if (p == null || !p.online) return "The camera phone is offline"
        val s = status()
        if (!p.camera || (s != null && !s.optBoolean("monitoring") && !s.optBoolean("standby") && s.optString("mode") == "OFF"))
            return "MotionCam's camera isn't running on that phone (not monitoring, standby off). Open MotionCam there or " +
                "turn on Standby in its Live connection settings."
        return null
    }

    /** Names of settings in a "settings_local" event, for messages. */
    fun changedText(d: JSONObject): String = d.optString("text").removePrefix("Changed on the camera phone: ").ifEmpty { "settings" }

    fun status(): JSONObject? = Live.cams[cam]?.status
    fun caps(): JSONObject? = Live.cams[cam]?.caps
    fun online() = Live.phones[cam]?.online == true
    fun name() = Live.phones[cam]?.name?.ifEmpty { null } ?: status()?.optString("name")?.ifEmpty { null } ?: "Camera"

    fun pinFor(): String? = SecretStore.get(a, "live.pin.$cam")

    // ------------------------------------------------------------------ the remote (one controlling phone at a time)

    /** The phone holding this camera's remote (id, name, until), or null when it's free. */
    fun holder(): JSONObject? = status()?.optJSONObject("remote")?.takeIf { it.optLong("until") > Live.now() && it.optString("id").isNotEmpty() }
    fun iHold() = holder()?.optString("id") == Device.id(a)
    /** Another phone holds the remote: this one can watch but not change anything. */
    fun lockedOut() = holder()?.let { it.optString("id") != Device.id(a) } == true

    /**
     * The remote is automatic: the first change made here claims it (when nobody else is changing this camera), it is
     * kept alive while changes keep coming or are waiting to be saved ([busy]), and given back about 4 s after the last
     * one, or when the screen is left ([leave]). Returns false (and says who) when another phone is changing it.
     */
    fun touch(): Boolean {
        val h = holder()
        if (h != null && h.optString("id") != Device.id(a)) {
            Toast.makeText(a, "${h.optString("name").ifEmpty { "Another phone" }} is changing this camera right now — try again in a few seconds",
                Toast.LENGTH_SHORT).show()
            return false
        }
        val now = System.currentTimeMillis()
        lastTouch = now
        if (now - lastTake > 2500) { lastTake = now; claim() }
        if (!holding) { holding = true; ui.postDelayed(keep, 3000) }
        return true
    }

    /** The screen has unsent or sending changes: keep the remote meanwhile. */
    var busy: () -> Boolean = { false }
    private var lastTouch = 0L
    private var lastTake = 0L
    private var holding = false
    private fun claim() { if (online()) send("take", quiet = true, silent = true) }
    private val keep = object : Runnable {
        override fun run() {
            if (!holding) return
            if (busy() || System.currentTimeMillis() - lastTouch < 4000) { lastTake = System.currentTimeMillis(); claim(); ui.postDelayed(this, 3000) }
            else { holding = false; if (online()) send("release", quiet = true, silent = true) }
        }
    }

    /** Leaving the camera's screen gives the remote back. */
    fun leave() {
        ui.removeCallbacks(keep)
        if (holding) { holding = false; if (online()) send("release", quiet = true, silent = true) }
    }

    /** One line when another phone is changing this camera (null otherwise). */
    fun lockText(): String? = holder()?.takeIf { it.optString("id") != Device.id(a) }?.let {
        "🔒 ${it.optString("name").ifEmpty { "Another phone" }} is changing this camera — controls unlock when they're done"
    }

    fun askPin(then: () -> Unit, cancelled: () -> Unit = {}) {
        val input = EditText(a).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD; hint = "PIN" }
        AlertDialog.Builder(a).setTitle("PIN of this camera").setView(input)
            .setMessage("Set on the camera phone (Live connection → Remote control PIN). It is remembered on this phone.")
            .setPositiveButton("OK") { _, _ -> SecretStore.put(a, "live.pin.$cam", input.text.toString().trim()); then() }
            .setNegativeButton("Cancel") { _, _ -> cancelled() }.setOnCancelListener { cancelled() }.show()
    }

    /**
     * Sends a signed command; [done] gets the camera's answer. [quiet] = only failures are shown (for quick controls).
     * [onFail] runs when it could not be sent (offline, PIN prompt cancelled) or got no answer within 20 s.
     */
    fun send(type: String, args: JSONObject? = null, quiet: Boolean = false, onFail: () -> Unit = {}, silent: Boolean = false,
             done: (Boolean, JSONObject?) -> Unit = { _, _ -> }) {
        if (!online()) { if (!silent) Toast.makeText(a, "The camera is offline", Toast.LENGTH_SHORT).show(); onFail(); return }
        if (status()?.optBoolean("pin") == true && pinFor() == null) { askPin({ send(type, args, quiet, onFail, silent, done) }, onFail); return }
        val ref = Live.ref(a, "cams/$cam/cmd")?.push() ?: run { onFail(); return }
        val id = ref.key ?: run { onFail(); return }
        val d = JSONObject().put("type", type).put("by", Device.id(a)).put("byName", Device.name(a)).put("at", Live.now())
        if (args != null) d.put("args", args)
        val sealed = LiveNet.seal(a, d.toString())
        val m = HashMap<String, Any>(); m["d"] = sealed
        pinFor()?.let { m["sig"] = CameraLink.sign(cam, it, sealed) }
        val res = Live.ref(a, "cams/$cam/cmdResult/$id") ?: run { onFail(); return }
        var answered = false
        val l = object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                if (!s.exists()) return
                answered = true
                res.removeEventListener(this); res.removeValue()
                val r = LiveNet.open(a, s.child("d").value as? String)?.let { JSONObject(it) } ?: return
                val msg = r.optString("msg")
                val ok = r.optBoolean("ok")
                if (msg == "Wrong PIN") SecretStore.put(a, "live.pin.$cam", null)
                if (!silent && (!quiet || !ok)) Toast.makeText(a, msg, Toast.LENGTH_SHORT).show()
                done(ok, r)
            }
            override fun onCancelled(e: DatabaseError) {}
        }
        res.addValueEventListener(l)
        ref.setValue(m)
        ui.postDelayed({
            if (!answered) {
                res.removeEventListener(l)
                if (!silent) Toast.makeText(a, "No answer from the camera", Toast.LENGTH_SHORT).show()
                onFail()
            }
        }, 20_000)
    }

    // ------------------------------------------------------------------ live view

    /**
     * Low-rate live view: tells the camera someone is watching (refreshed every 15 s; the camera stops sending when
     * nobody does) and delivers each frame. Stops by itself after [maxMs], so it never streams forever by accident.
     */
    inner class Feed(private val maxMs: Long, private val onFrame: (Bitmap, Long) -> Unit, private val onState: (String) -> Unit) {
        var watching = false; private set
        private var started = 0L
        /** When the last picture arrived here (0 = none since watching started). */
        var lastFrame = 0L; private set

        /**
         * What to tell the viewer about the picture (null = live and fresh): starting, or why nothing new arrives. The
         * last picture stays on screen either way.
         */
        fun health(): String? {
            if (!watching) return null
            liveProblem()?.let { return it }
            val now = System.currentTimeMillis()
            val since = if (lastFrame > 0) now - lastFrame else now - started
            return when {
                lastFrame == 0L && since < 8000 -> if (status()?.optBoolean("standby") == true) "Waking the camera…" else "Starting live view…"
                lastFrame == 0L -> "No picture from the camera yet (it may be busy, or its camera can't be opened)"
                since > 8000 -> "Live view stalled — no new picture for ${since / 1000} s"
                else -> null
            }
        }
        private var liveL: ValueEventListener? = null
        private val heartbeat = object : Runnable {
            override fun run() {
                if (!watching) return
                if (System.currentTimeMillis() - started > maxMs) {
                    stop(); onState("Live view paused after ${maxMs / 60_000} minutes — tap to continue"); return
                }
                val at = Live.now()
                val m = HashMap<String, Any>(); m["at"] = at
                pinFor()?.let { m["sig"] = CameraLink.sign(cam, it, "watch:${Device.id(a)}:$at") }
                Live.ref(a, "cams/$cam/watchers/${Device.id(a)}")?.setValue(m)
                ui.postDelayed(this, 15_000)
            }
        }

        fun start() {
            if (watching) return
            if (!online()) { onState("The camera is offline"); return }
            if (status()?.optBoolean("pinForView") == true && pinFor() == null) { askPin({ start() }); return }
            watching = true
            started = System.currentTimeMillis()
            lastFrame = 0L
            onState("Starting live view…")
            Live.ref(a, "cams/$cam/watchers/${Device.id(a)}")?.onDisconnect()?.removeValue()
            heartbeat.run()
            liveL = object : ValueEventListener {
                override fun onDataChange(s: DataSnapshot) {
                    val bytes = LiveNet.openBytes(a, s.child("img").value as? String) ?: return
                    val at = (s.child("at").value as? Number)?.toLong() ?: 0
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let {
                        lastFrame = System.currentTimeMillis()
                        lastFrames[cam] = it to (at - Live.serverOffset)
                        onFrame(it, at - Live.serverOffset)
                    }
                }
                override fun onCancelled(e: DatabaseError) {}
            }.also { Live.ref(a, "cams/$cam/live")?.addValueEventListener(it) }
        }

        fun stop() {
            if (!watching) return
            watching = false
            ui.removeCallbacks(heartbeat)
            liveL?.let { Live.ref(a, "cams/$cam/live")?.removeEventListener(it) }
            liveL = null
            Live.ref(a, "cams/$cam/watchers/${Device.id(a)}")?.removeValue()
        }
    }
}
