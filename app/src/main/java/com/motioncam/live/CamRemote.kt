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
    private val ui = Handler(Looper.getMainLooper())

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

    /** Takes the remote; asks first when another phone has it (that phone is told). */
    fun take(done: () -> Unit = {}) {
        val h = holder()
        if (h != null && h.optString("id") != Device.id(a)) {
            AlertDialog.Builder(a).setTitle("Take over the remote?")
                .setMessage("${h.optString("name").ifEmpty { "Another phone" }} has the remote of ${name()}. If you take it, that phone can only " +
                    "watch until it takes it back, and it is told.")
                .setPositiveButton("Take over") { _, _ -> send("take") { ok, _ -> if (ok) done() } }
                .setNegativeButton("Cancel", null).show()
        } else send("take") { ok, _ -> if (ok) done() }
    }

    fun release(done: () -> Unit = {}) = send("release", quiet = true) { _, _ -> done() }

    /** One line about the remote, for status areas. */
    fun remoteText(prefs: com.motioncam.Prefs): String {
        val h = holder() ?: return "Remote: free — your first change takes it"
        val until = com.motioncam.Fmt.t(prefs, h.optLong("until") - Live.serverOffset, seconds = false)
        return if (iHold()) "You have the remote (until $until unless you keep using it)"
            else "${h.optString("name").ifEmpty { "Another phone" }} has the remote — you can watch, not change"
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
    fun send(type: String, args: JSONObject? = null, quiet: Boolean = false, onFail: () -> Unit = {},
             done: (Boolean, JSONObject?) -> Unit = { _, _ -> }) {
        if (!online()) { Toast.makeText(a, "The camera is offline", Toast.LENGTH_SHORT).show(); onFail(); return }
        if (status()?.optBoolean("pin") == true && pinFor() == null) { askPin({ send(type, args, quiet, onFail, done) }, onFail); return }
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
                if (!quiet || !ok) Toast.makeText(a, msg, Toast.LENGTH_SHORT).show()
                done(ok, r)
            }
            override fun onCancelled(e: DatabaseError) {}
        }
        res.addValueEventListener(l)
        ref.setValue(m)
        ui.postDelayed({
            if (!answered) {
                res.removeEventListener(l)
                Toast.makeText(a, "No answer from the camera", Toast.LENGTH_SHORT).show()
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
            onState("Starting live view…")
            Live.ref(a, "cams/$cam/watchers/${Device.id(a)}")?.onDisconnect()?.removeValue()
            heartbeat.run()
            liveL = object : ValueEventListener {
                override fun onDataChange(s: DataSnapshot) {
                    val bytes = LiveNet.openBytes(a, s.child("img").value as? String) ?: return
                    val at = (s.child("at").value as? Number)?.toLong() ?: 0
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { onFrame(it, at - Live.serverOffset) }
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
