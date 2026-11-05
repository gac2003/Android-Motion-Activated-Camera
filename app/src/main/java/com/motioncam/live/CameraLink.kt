package com.motioncam.live

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import com.motioncam.CameraService
import com.motioncam.EngineHost
import com.motioncam.FrameHub
import com.motioncam.Lenses
import com.motioncam.Mode
import com.motioncam.MotionDetector
import com.motioncam.Prefs
import com.motioncam.Status
import com.motioncam.StatusBus
import com.motioncam.cloud.Device
import com.motioncam.cloud.SecretStore
import org.json.JSONArray
import org.json.JSONObject
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The camera phone's side of the live connection (runs while monitoring, inside the camera service):
 *  - status (monitoring, recording, battery, charging, temperature, last motion, lens, format, settings) — sent
 *    when something important changes, otherwise at most once a minute;
 *  - events (motion, clip saved, monitoring stopped and why, too hot, charger unplugged, battery) for alerts and
 *    the Activity log;
 *  - a low-rate live view, only while a phone is watching (one frame every 2 s), and a high-quality frame on request;
 *  - remote control commands, checked against the camera's PIN when it has one (wrong PINs are rate-limited).
 */
object CameraLink {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    private var app: Context? = null
    private var last: Status? = null
    private var lastPub = 0L
    private var lastLens = ""
    private var batteryBand = 3
    private var statusListener: ((Status) -> Unit)? = null
    private var watchL: ValueEventListener? = null
    private var snapL: ChildEventListener? = null
    private var cmdL: ChildEventListener? = null

    private fun me(ctx: Context) = Device.id(ctx)
    private fun path(ctx: Context, p: String) = Live.ref(ctx, "cams/${me(ctx)}/$p")

    fun start(ctx: Context) {
        val c = ctx.applicationContext
        // only a phone that agreed to be a camera on the network (and has a PIN) is visible / controllable
        if (running || !Live.enabled(c) || !Prefs(c).liveCamera || !hasPin(c)) return
        running = true
        app = c
        Live.cameraActive = true
        Live.refresh(c)
        last = null
        val l: (Status) -> Unit = { s -> try { onStatus(c, s) } catch (_: Exception) {} }
        statusListener = l
        StatusBus.add(l)
        onStatus(c, StatusBus.status)
        listen(c)
        trimEvents(c)
    }

    fun stop(ctx: Context) {
        val c = ctx.applicationContext
        if (!running) return
        running = false
        statusListener?.let { StatusBus.remove(it) }
        statusListener = null
        try { publishStatus(c, StatusBus.status.copy(monitoring = false, mode = Mode.OFF)) } catch (_: Exception) {}
        watchL?.let { path(c, "watchers")?.removeEventListener(it) }
        snapL?.let { path(c, "snapReq")?.removeEventListener(it) }
        cmdL?.let { path(c, "cmd")?.removeEventListener(it) }
        stopLive()
        Live.cameraActive = false
        Live.refresh(c)
        // no longer reachable: standby has no purpose any more
        if (CameraService.inStandby) CameraService.exit(c)
    }

    /** Monitoring stopped by the phone itself (battery, heat, storage…). */
    fun safetyStop(ctx: Context, why: String) = event(ctx, "safety_stop", why)

    fun event(ctx: Context, type: String, text: String, extra: JSONObject? = null) {
        if (!running) return
        val d = (extra ?: JSONObject()).put("type", type).put("text", text)
        path(ctx, "events")?.push()?.setValue(mapOf("t" to ServerValue.TIMESTAMP, "d" to LiveNet.seal(ctx, d.toString())))
    }

    // ------------------------------------------------------------------ status + events from state changes

    private fun onStatus(ctx: Context, s: Status) {
        val prev = last
        last = s
        if (prev != null) {
            if (prev.mode != Mode.RECORDING && s.mode == Mode.RECORDING) event(ctx, "motion", "Motion detected")
            if (prev.mode == Mode.RECORDING && s.mode != Mode.RECORDING && prev.recordingSinceWall > 0) {
                val sec = ((System.currentTimeMillis() - prev.recordingSinceWall) / 1000).coerceAtLeast(1)
                event(ctx, "clip", "Clip of ${sec}s saved")
            }
            if (!prev.monitoring && s.monitoring) event(ctx, "monitor_on", "Monitoring started")
            if (prev.mode != Mode.PAUSED_HOT && s.mode == Mode.PAUSED_HOT) event(ctx, "hot", "Paused because the phone is too hot (${"%.0f".format(s.tempC)} °C)")
            if (prev.charging && !s.charging && s.monitoring) event(ctx, "unplugged", "The camera phone is no longer charging (${s.batteryPct}%)")
            val band = when { s.batteryPct in 0..10 -> 1; s.batteryPct in 11..20 -> 2; else -> 3 }
            if (band < batteryBand && !s.charging) event(ctx, "battery", "Battery at ${s.batteryPct}%")
            batteryBand = band
        }
        val now = System.currentTimeMillis()
        val important = prev == null || prev.mode != s.mode || prev.monitoring != s.monitoring || prev.lens != s.lens || prev.format != s.format
        val minor = prev != null && (Math.abs(prev.batteryPct - s.batteryPct) >= 1 || Math.abs(prev.tempC - s.tempC) >= 1f ||
            prev.charging != s.charging || prev.lastMotionWall != s.lastMotionWall)
        if (important || (minor && now - lastPub >= 60_000) || now - lastPub >= 10 * 60_000) publishStatus(ctx, s)
    }

    fun publishStatus(ctx: Context, s: Status = StatusBus.status) {
        lastPub = System.currentTimeMillis()
        val p = Prefs(ctx)
        val e = EngineHost.peek()
        val o = JSONObject()
            .put("name", Device.name(ctx)).put("mode", s.mode.name).put("monitoring", s.monitoring).put("message", s.message)
            .put("standby", CameraService.inStandby).put("standbyOn", CameraService.standbyAllowed(ctx)).put("canStart", CameraService.inStandby || (CameraService.monitoring && !s.monitoring))
            .put("recSince", s.recordingSinceWall).put("lastMotion", s.lastMotionWall).put("battery", s.batteryPct)
            .put("charging", s.charging).put("tempC", s.tempC.toDouble()).put("lens", s.lens).put("format", s.format)
            .put("pin", pin(ctx) != null).put("pinForView", pin(ctx) != null && p.livePinForView)
            .put("settings", settings(p))
            .put("live", JSONObject().put("iso", e?.liveIso ?: 0).put("shutterNs", e?.liveShutterNs ?: 0L)
                .put("kelvin", e?.liveKelvin ?: 0).put("focus", (e?.liveFocusDiopter ?: -1f).toDouble()))
        val m = HashMap<String, Any>()
        m["at"] = ServerValue.TIMESTAMP
        m["d"] = LiveNet.seal(ctx, o.toString())
        val lensKey = p.camera
        if (lensKey != lastLens || lastLens.isEmpty()) { lastLens = lensKey; caps(ctx)?.let { m["caps"] = LiveNet.seal(ctx, it.toString()) } }
        path(ctx, "status")?.updateChildren(m)
    }

    private fun settings(p: Prefs) = JSONObject()
        .put("camera", p.camera).put("resHeight", p.resHeight).put("fps", p.fps).put("hevc", p.hevc).put("bitrateMbps", p.bitrateMbps)
        .put("audio", p.audio).put("preRecordMs", p.preRecordMs).put("stopDelaySec", p.stopDelaySec).put("maxClipMin", p.maxClipMin)
        .put("sensitivity", p.sensitivity).put("lightFilter", p.lightFilter).put("minAreaPct", p.minAreaPct.toDouble())
        .put("minMotionMs", p.minMotionMs).put("exposureAuto", p.exposureAuto).put("evIndex", p.evIndex).put("iso", p.iso)
        .put("shutterNs", p.shutterNs).put("focusAuto", p.focusAuto).put("focusPos", p.focusPos.toDouble())
        .put("wbAuto", p.wbAuto).put("wbKelvin", p.wbKelvin).put("zoom", p.zoom.toDouble())

    /** What the remote controls may offer (lenses, ranges). */
    private fun caps(ctx: Context): JSONObject? {
        val c = EngineHost.peek()?.caps ?: return null
        val lenses = JSONArray()
        for (l in Lenses.list(ctx)) lenses.put(JSONObject().put("key", l.key).put("label", l.label))
        return JSONObject().put("lenses", lenses).put("lens", c.lens.key)
            .put("evMin", c.evMin).put("evMax", c.evMax).put("evStep", c.evStep.toDouble())
            .put("isoMin", c.isoMin).put("isoMax", c.isoMax).put("expMinNs", c.expMinNs).put("expMaxNs", c.expMaxNs)
            .put("manualSensor", c.manualSensor).put("minFocus", c.minFocusDiopter.toDouble())
            .put("zoomMin", c.zoomMin.toDouble()).put("zoomMax", c.zoomMax.toDouble())
            .put("fps", JSONArray(listOf(24, 30, 60).filter { c.supportsFps(it) })).put("maxSensitivity", MotionDetector.MAX_SENSITIVITY)
    }

    /** Keeps 7 days / 500 events. */
    private fun trimEvents(ctx: Context) {
        val ref = path(ctx, "events") ?: return
        ref.orderByChild("t").addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                val cutoff = Live.now() - 7 * 86_400_000L
                val all = s.children.toList()
                val extra = (all.size - 500).coerceAtLeast(0)
                all.forEachIndexed { i, c ->
                    val t = (c.child("t").value as? Number)?.toLong() ?: 0
                    if (i < extra || t < cutoff) c.key?.let { ref.child(it).removeValue() }
                }
            }
            override fun onCancelled(e: DatabaseError) {}
        })
    }

    // ------------------------------------------------------------------ PIN

    private fun pin(ctx: Context): String? = SecretStore.get(ctx, "live.pin")?.takeIf { it.isNotEmpty() }
    fun setPin(ctx: Context, pin: String?) { SecretStore.put(ctx, "live.pin", pin?.trim()?.ifEmpty { null }); if (running) publishStatus(ctx) }
    fun hasPin(ctx: Context) = pin(ctx) != null
    fun shortPin(ctx: Context) = (pin(ctx)?.length ?: 6) < 6
    fun pinMatches(ctx: Context, entered: String) = pin(ctx)?.let { it == entered.trim() } == true

    /** Signature a viewer sends with a command: HMAC-SHA256 of the payload with a key made from the PIN and camera. */
    private val keys = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()

    /**
     * Key made from the PIN with deliberately slow hashing (PBKDF2, 150 000 rounds; computed once per camera and PIN),
     * so someone who records signed requests can't quickly try PINs offline.
     */
    private fun pinKey(cam: String, pin: String): ByteArray = keys.getOrPut("$cam|${pin.trim()}") {
        javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(javax.crypto.spec.PBEKeySpec(pin.trim().toCharArray(), "motioncam-pin:$cam".toByteArray(), 150_000, 256)).encoded
    }

    fun sign(cam: String, pin: String, payload: String): String {
        val key = pinKey(cam, pin)
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
        return mac.doFinal(payload.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private var fails = 0
    private var lockedUntil = 0L
    private var lockouts = 0

    /** Checks the PIN signature; after 5 wrong ones, refuses everything for 10 minutes. */
    private fun pinOk(ctx: Context, payload: String, sig: String?): Boolean? {
        val p = pin(ctx) ?: return true
        if (System.currentTimeMillis() < lockedUntil) return null
        if (sig != null && sig == sign(me(ctx), p, payload)) { fails = 0; lockouts = 0; return true }
        if (++fails >= 5) {
            // each lock-out doubles: 10 min, 20, 40… up to a day
            val min = (10L shl lockouts.coerceAtMost(7)).coerceAtMost(24 * 60L)
            lockouts++
            lockedUntil = System.currentTimeMillis() + min * 60_000L
            fails = 0
            event(ctx, "pin_locked", "Too many wrong PINs: remote control locked for $min minutes")
        }
        return false
    }

    // ------------------------------------------------------------------ requests from viewers

    private fun listen(ctx: Context) {
        watchL = object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                val fresh = s.children.any { c ->
                    val at = (c.child("at").value as? Number)?.toLong() ?: 0
                    at > Live.now() - 30_000 && (!Prefs(ctx).livePinForView || pinOk(ctx, "watch:${c.key}:$at", c.child("sig").value as? String) == true)
                }
                if (fresh) startLive(ctx) else stopLive()
            }
            override fun onCancelled(e: DatabaseError) {}
        }.also { path(ctx, "watchers")?.addValueEventListener(it) }
        snapL = object : ChildEventListener {
            override fun onChildAdded(s: DataSnapshot, prev: String?) { val id = s.key ?: return; Thread { snapshot(ctx, id, s) }.start() }
            override fun onChildChanged(s: DataSnapshot, prev: String?) {}
            override fun onChildRemoved(s: DataSnapshot) {}
            override fun onChildMoved(s: DataSnapshot, prev: String?) {}
            override fun onCancelled(e: DatabaseError) {}
        }.also { path(ctx, "snapReq")?.addChildEventListener(it) }
        cmdL = object : ChildEventListener {
            override fun onChildAdded(s: DataSnapshot, prev: String?) { try { command(ctx, s) } catch (_: Exception) {} }
            override fun onChildChanged(s: DataSnapshot, prev: String?) {}
            override fun onChildRemoved(s: DataSnapshot) {}
            override fun onChildMoved(s: DataSnapshot, prev: String?) {}
            override fun onCancelled(e: DatabaseError) {}
        }.also { path(ctx, "cmd")?.addChildEventListener(it) }
    }

    @Volatile private var liveThread: Thread? = null

    /** Low-rate live view while someone watches (a frame every 2 s). */
    private fun startLive(ctx: Context) {
        if (liveThread != null) return
        val t = Thread {
            FrameHub.clients++
            try {
                var seq = FrameHub.currentSeq()
                while (running && liveThread === Thread.currentThread()) {
                    val f = FrameHub.waitNext(seq, 3000)
                    if (f != null) {
                        seq = f.first
                        path(ctx, "live")?.setValue(mapOf("at" to ServerValue.TIMESTAMP, "img" to LiveNet.sealBytes(ctx, f.second)))
                    }
                    Thread.sleep(2000)
                }
            } catch (_: InterruptedException) {
            } finally { FrameHub.clients-- }
        }
        liveThread = t
        t.isDaemon = true
        t.start()
    }

    private fun stopLive() { liveThread?.interrupt(); liveThread = null }

    /** A high-quality current frame (full analysis resolution, high JPEG quality). */
    private fun snapshot(ctx: Context, id: String, s: DataSnapshot) {
        val by = s.child("by").value as? String ?: ""
        val at = (s.child("at").value as? Number)?.toLong() ?: 0
        if (Prefs(ctx).livePinForView && pinOk(ctx, "snap:$id:$at", s.child("sig").value as? String) != true) {
            path(ctx, "snap/$id")?.setValue(mapOf("at" to ServerValue.TIMESTAMP, "err" to "PIN needed"))
            path(ctx, "snapReq/$id")?.removeValue(); return
        }
        FrameHub.hq++
        FrameHub.clients++
        try {
            var seq = FrameHub.currentSeq()
            var frame: ByteArray? = null
            repeat(2) { FrameHub.waitNext(seq, 4000)?.let { seq = it.first; frame = it.second } }   // the 2nd is surely high quality
            val f = frame ?: FrameHub.latest()
            if (f != null) path(ctx, "snap/$id")?.setValue(mapOf("at" to ServerValue.TIMESTAMP, "by" to by, "img" to LiveNet.sealBytes(ctx, f)))
            else path(ctx, "snap/$id")?.setValue(mapOf("at" to ServerValue.TIMESTAMP, "err" to "No frame (camera paused?)"))
        } finally { FrameHub.hq--; FrameHub.clients-- }
        path(ctx, "snapReq/$id")?.removeValue()
    }

    private val seenCmds = HashSet<String>()
    private val PROTECTED = setOf("set", "pause", "resume", "stop", "start")

    private fun command(ctx: Context, s: DataSnapshot) {
        val id = s.key ?: return
        if (!seenCmds.add(id)) return
        val sealed = s.child("d").value as? String ?: return
        val d = LiveNet.open(ctx, sealed)?.let { JSONObject(it) } ?: return
        val type = d.optString("type")
        fun reply(ok: Boolean, msg: String, extra: JSONObject? = null) {
            path(ctx, "cmdResult/$id")?.setValue(mapOf("at" to ServerValue.TIMESTAMP,
                "d" to LiveNet.seal(ctx, (extra ?: JSONObject()).put("ok", ok).put("msg", msg).toString())))
            path(ctx, "cmd/$id")?.removeValue()
        }
        if (Math.abs(Live.now() - d.optLong("at")) > 10 * 60_000L) { reply(false, "Request too old"); return }
        if (type in PROTECTED) {
            when (pinOk(ctx, sealed, s.child("sig").value as? String)) {
                null -> { reply(false, "Locked after too many wrong PINs. Try again in a few minutes."); return }
                false -> { reply(false, "Wrong PIN"); return }
                true -> {}
            }
        }
        val who = d.optString("byName", "another phone")
        when (type) {
            "pause" -> { EngineHost.peek()?.setMonitoring(false); event(ctx, "monitor_paused", "Monitoring paused by $who"); reply(true, "Monitoring paused") }
            "resume" -> { EngineHost.peek()?.setMonitoring(true); reply(true, "Monitoring resumed") }
            "start" -> when {
                CameraService.inStandby -> {
                    reply(true, "Starting monitoring…"); main.post { CameraService.instance?.startMonitoring() }
                }
                CameraService.monitoring && EngineHost.peek()?.monitoring == false -> { EngineHost.peek()?.setMonitoring(true); reply(true, "Monitoring resumed") }
                CameraService.monitoring -> reply(true, "Already monitoring")
                else -> reply(false, "The camera phone is not in standby")
            }
            "stop" -> {
                event(ctx, "monitor_off", "Monitoring stopped by $who")
                reply(true, if (CameraService.standbyAllowed(ctx)) "Stopped · standby (you can start it again from here)" else "Stopping")
                main.post { CameraService.stop(ctx) }
            }
            "set" -> {
                val (applied, rejected) = RemoteSettings.apply(ctx, d.optJSONObject("args") ?: JSONObject())
                EngineHost.peek()?.applyConfig()
                EngineHost.peek()?.applyPro()
                RemoteSettings.changed()
                main.postDelayed({ publishStatus(ctx) }, 800)
                reply(rejected.isEmpty(), if (rejected.isEmpty()) "Applied" else "Not applied: ${rejected.joinToString()}",
                    JSONObject().put("applied", JSONArray().apply { applied.forEach { put(it) } }))
            }
            else -> reply(false, "Unknown request")
        }
    }
}
