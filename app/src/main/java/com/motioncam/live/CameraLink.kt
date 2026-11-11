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

    fun me(ctx: Context) = Device.id(ctx)
    fun path(ctx: Context, p: String) = Live.ref(ctx, "cams/${me(ctx)}/$p")

    fun start(ctx: Context) {
        val c = ctx.applicationContext
        // only a phone that agreed to be a camera on the network (and has a PIN) is visible / controllable
        if (running || !Live.enabled(c) || !Prefs(c).liveCamera || !hasPin(c)) return
        running = true
        app = c
        Live.cameraActive = true
        Live.refresh(c)
        try { RtcCamera.start(c) } catch (_: Throwable) {}   // smooth live video, own build with a Cloudflare key
        last = null
        val l: (Status) -> Unit = { s -> try { onStatus(c, s) } catch (_: Exception) {} }
        statusListener = l
        StatusBus.add(l)
        onStatus(c, StatusBus.status)
        listen(c)
        trimEvents(c)
        main.removeCallbacks(dailyTrim)
        main.removeCallbacks(cardTick); main.postDelayed(dailyTrim, 24 * 3_600_000L)
        main.removeCallbacks(cardTick); main.postDelayed(cardTick, 60_000L)
        // settings changed on the camera itself (its screen, Settings): other phones see them within a second
        c.getSharedPreferences("motioncam", Context.MODE_PRIVATE).registerOnSharedPreferenceChangeListener(prefsL)
    }

    private val SETTING_KEYS = setOf("camera", "resHeight", "fps", "hevc", "bitrateMbps", "audio", "preRecordMs", "stopDelaySec",
        "maxClipMin", "sensitivity", "lightFilter", "minAreaPct", "minMotionMs", "exposureAuto", "evIndex", "iso", "shutterNs",
        "focusAuto", "focusPos", "wbAuto", "wbKelvin", "zoom")
    private val publishSoon = Runnable { app?.let { if (running) try { publishStatus(it) } catch (_: Exception) {} } }
    private val prefsL = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (running && key in SETTING_KEYS) {
            main.removeCallbacks(publishSoon); main.postDelayed(publishSoon, 800)
            // changed on this phone itself (not by a viewer's Save): tell the viewers, once the changes settle
            val byRemote = key in remoteKeys && System.currentTimeMillis() - remoteAt < 5000
            if (!byRemote) { localKeys += key!!; main.removeCallbacks(reportLocal); main.postDelayed(reportLocal, 2500) }
        }
    }

    /** Settings just applied for a viewer (so they aren't reported back as changed on the camera). */
    @Volatile private var remoteKeys: Set<String> = emptySet()
    @Volatile private var remoteAt = 0L
    private val localKeys = java.util.LinkedHashSet<String>()
    private val SETTING_LABELS = mapOf("camera" to "lens", "resHeight" to "resolution", "fps" to "frame rate", "hevc" to "codec",
        "bitrateMbps" to "bitrate", "audio" to "audio", "preRecordMs" to "pre-record", "stopDelaySec" to "stop delay",
        "maxClipMin" to "clip length", "sensitivity" to "sensitivity", "lightFilter" to "light filter", "minAreaPct" to "minimum area",
        "minMotionMs" to "minimum motion time", "exposureAuto" to "exposure mode", "evIndex" to "exposure compensation", "iso" to "ISO",
        "shutterNs" to "shutter speed", "focusAuto" to "focus mode", "focusPos" to "focus distance", "wbAuto" to "white balance mode",
        "wbKelvin" to "white balance", "zoom" to "zoom")
    private val reportLocal = Runnable {
        val c = app ?: return@Runnable
        if (!running || localKeys.isEmpty()) return@Runnable
        val keys = localKeys.toList(); localKeys.clear()
        val names = keys.mapNotNull { SETTING_LABELS[it] }.distinct()
        event(c, "settings_local", "Changed on the camera phone: " + names.joinToString(), JSONObject().put("keys", JSONArray(keys)))
    }

    /** Long-running cameras apply "Keep activity" once a day. */
    private val dailyTrim = object : Runnable {
        override fun run() { val c = app ?: return; if (running) { trimEvents(c); main.postDelayed(this, 24 * 3_600_000L) } }
    }

    fun stop(ctx: Context) {
        val c = ctx.applicationContext
        if (!running) return
        running = false
        try { RtcCamera.stop(c) } catch (_: Throwable) {}
        main.removeCallbacks(dailyTrim)
        c.getSharedPreferences("motioncam", Context.MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(prefsL)
        main.removeCallbacks(publishSoon)
        statusListener?.let { StatusBus.remove(it) }
        statusListener = null
        try { publishStatus(c, StatusBus.status.copy(monitoring = false, mode = Mode.OFF)) } catch (_: Exception) {}
        watchL?.let { path(c, "watchers")?.removeEventListener(it) }
        snapL?.let { path(c, "snapReq")?.removeEventListener(it) }
        cmdL?.let { path(c, "cmd")?.removeEventListener(it) }
        main.removeCallbacks(reportLocal); localKeys.clear()
        stopLive()
        watchers = emptyList()
        Live.cameraActive = false
        Live.refresh(c)
        // no longer reachable: standby has no purpose any more
        if (CameraService.inStandby) CameraService.exit(c)
    }

    @Volatile private var lastClipMs = 0L
    /** Busy cameras send at most one card picture a minute. */
    @Volatile private var lastCardAt = 0L

    /**
     * A clip was saved (and kept): tells the viewing phones its name and the backup folders it goes to, so their
     * alerts can open it (from the cloud, once uploaded).
     */
    fun clipSaved(ctx: Context, clipId: Long) {
        if (!running) return
        val c = ctx.applicationContext
        Thread {
            try {
                val clip = com.motioncam.ClipStore.get(c, clipId) ?: return@Thread
                val folders = JSONArray()
                for (k in com.motioncam.cloud.CloudSync.enabledKinds(c)) com.motioncam.cloud.CloudConfig(c, k).folderId?.let {
                    folders.put(JSONObject().put("k", k.kind.key).put("f", Live.folderKey(k.kind, it)))
                }
                val ms = clip.durationMs.takeIf { it > 0 } ?: lastClipMs
                val sec = (ms / 1000).coerceAtLeast(1)
                // its picture first (alerts and Activity on the other phones show it), then the clip's event
                val pic = try { com.motioncam.ClipThumbs.load(c, clip) } catch (_: Exception) { null }
                if (pic != null) try { ClipThumbStore.publish(c, clip.name, pic) } catch (_: Exception) {}
                event(c, "clip", "Clip of ${sec}s saved", JSONObject().put("name", clip.name).put("dur", ms).put("folders", folders))
                // the camera's card on Home: this clip's peak-motion frame
                if (System.currentTimeMillis() - lastCardAt > 60_000L) pic?.let { bmp ->
                    lastCardAt = System.currentTimeMillis()
                    val out = java.io.ByteArrayOutputStream()
                    val w = 360
                    val sc = if (bmp.width > w) android.graphics.Bitmap.createScaledBitmap(bmp, w, (bmp.height * w / bmp.width).coerceAtLeast(1), true) else bmp
                    sc.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, out)
                    cardPicture(c, out.toByteArray(), clip.startMs)
                }
            } catch (_: Exception) {}
        }.start()
    }

    /**
     * A clip finished uploading: viewing phones then know that a clip missing from the folder later was deleted (not
     * still waiting to be uploaded). Recorded on the clip's own event (no extra entry); an older clip whose event this
     * phone no longer remembers gets a separate "clip uploaded" event as before. Not shown in Activity.
     */
    fun clipUploaded(ctx: Context, name: String, kind: com.motioncam.cloud.CloudKind, folderId: String) {
        if (!running) return
        val f = Live.folderKey(kind, folderId)
        val known = synchronized(clipEvents) { clipEvents[name] }
        if (known != null) {
            val (key, d) = known
            synchronized(d) {
                val up = d.optJSONArray("up") ?: JSONArray().also { d.put("up", it) }
                if ((0 until up.length()).none { up.optString(it) == f }) up.put(f)
            }
            path(ctx, "events/$key/d")?.setValue(LiveNet.seal(ctx, d.toString()))
            return
        }
        event(ctx, "clip_up", "Clip uploaded", JSONObject().put("name", name).put("f", f))
    }

    /**
     * The picture on this camera's card on your phones' Home (cams/<me>/thumb). When the live view needs the PIN, the
     * picture is locked with it too: only phones that know the PIN see it.
     */
    fun cardPicture(ctx: Context, jpeg: ByteArray, takenAt: Long) {
        if (!running) return
        val p = pin(ctx)
        val locked = p != null && Prefs(ctx).livePinForView
        val body = if (locked) pinSeal(me(ctx), p!!, jpeg) else jpeg
        path(ctx, "thumb")?.setValue(mapOf("t" to ServerValue.TIMESTAMP, "at" to takenAt, "pin" to locked, "img" to LiveNet.sealBytes(ctx, body)))
    }

    /** AES-GCM with the key made from the PIN (same as for signed commands). */
    fun pinSeal(cam: String, pin: String, plain: ByteArray): ByteArray {
        val iv = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
        val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        c.init(javax.crypto.Cipher.ENCRYPT_MODE, SecretKeySpec(pinKey(cam, pin), "AES"), javax.crypto.spec.GCMParameterSpec(128, iv))
        return iv + c.doFinal(plain)
    }

    fun pinOpen(cam: String, pin: String, sealed: ByteArray): ByteArray? = try {
        val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        c.init(javax.crypto.Cipher.DECRYPT_MODE, SecretKeySpec(pinKey(cam, pin), "AES"), javax.crypto.spec.GCMParameterSpec(128, sealed, 0, 12))
        c.doFinal(sealed, 12, sealed.size - 12)
    } catch (_: Exception) { null }

    /** Optional: a fresh card picture every 15 minutes while the camera is open (Settings › Your phones). */
    private val cardTick = object : Runnable {
        override fun run() {
            val c = app ?: return
            if (!running) return
            if (Prefs(c).cardSnapshot && CameraService.monitoring) Thread {
                try { FrameHub.latest()?.let { cardPicture(c, it, System.currentTimeMillis()) } } catch (_: Exception) {}
            }.start()
            main.postDelayed(this, 15 * 60_000L)
        }
    }

    /** Monitoring stopped by the phone itself (battery, heat, storage…). */
    fun safetyStop(ctx: Context, why: String) = event(ctx, "safety_stop", why)

    /** The recent clips' events (name → key, data), so an upload can be noted on the clip's own event. */
    private val clipEvents = object : LinkedHashMap<String, Pair<String, JSONObject>>() {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Pair<String, JSONObject>>?) = size > 300
    }

    // a busy camera (a tree in the wind, a TV…) must not bury Activity: a motion within 5 s of the previous one is the
    // same motion; after the 20th in an hour, the rest of that hour is one entry, "Motion detected ×N", with its clips
    @Volatile private var lastMotionAt = 0L
    private var hourOf = -1L
    private var hourCount = 0
    private var burstKey: String? = null
    private var burst: JSONObject? = null
    /** The latest motion was folded into the previous one (5 s rule) or into the hour's burst: its clip goes there too. */
    @Volatile private var clipToBurst = false
    const val BURST_AFTER = 20
    private const val SAME_MOTION_MS = 5_000L

    fun event(ctx: Context, type: String, text: String, extra: JSONObject? = null) {
        if (!running) return
        val now = System.currentTimeMillis()
        if (type == "motion") {
            if (now - lastMotionAt < SAME_MOTION_MS) { lastMotionAt = now; return }   // the same motion continuing
            lastMotionAt = now
            synchronized(this) {
                val hour = now / 3_600_000L
                if (hour != hourOf) { hourOf = hour; hourCount = 0; burstKey = null; burst = null }
                hourCount++
                if (hourCount > BURST_AFTER) { clipToBurst = true; addToBurst(ctx, null); dayCount(ctx, "m"); return }
            }
            clipToBurst = false
        }
        if (type == "clip" && clipToBurst) { synchronized(this) { if (burst != null) { addToBurst(ctx, extra); return } } }
        val d = (extra ?: JSONObject()).put("type", type).put("text", text)
        val ref = path(ctx, "events")?.push() ?: return
        ref.setValue(mapOf("t" to ServerValue.TIMESTAMP, "d" to LiveNet.seal(ctx, d.toString())))
        // your other phones, also with MotionCam closed (push, rate-limited)
        try { Push.onEvent(ctx, type, d, ref.key, now + Live.serverOffset) } catch (_: Exception) {}
        if (type == "clip") d.optString("name").takeIf { it.isNotEmpty() }?.let { n -> ref.key?.let { k -> synchronized(clipEvents) { clipEvents[n] = k to d } } }
        kindLetter(type)?.let { dayCount(ctx, it) }
    }

    /**
     * An event written without the camera's live connection running (e.g. from the watchdog in a fresh process,
     * after Android stopped MotionCam): reads the phones' push addresses itself and pushes it too.
     */
    fun eventDirect(ctx: Context, type: String, text: String) {
        val app = ctx.applicationContext
        val d = JSONObject().put("type", type).put("text", text)
        val ref = path(app, "events")?.push() ?: return
        ref.setValue(mapOf("t" to ServerValue.TIMESTAMP, "d" to LiveNet.seal(app, d.toString())))
        kindLetter(type)?.let { dayCount(app, it) }
        val me = me(app)
        Live.ref(app, "devices")?.addListenerForSingleValueEvent(object : com.google.firebase.database.ValueEventListener {
            override fun onDataChange(s: com.google.firebase.database.DataSnapshot) {
                val tokens = s.children.filter { it.key != me }.mapNotNull { LiveNet.open(app, it.child("push").value as? String) }.distinct()
                if (tokens.isNotEmpty()) try { Push.onEvent(app, type, d, ref.key, System.currentTimeMillis() + Live.serverOffset, tokens) } catch (_: Exception) {}
            }
            override fun onCancelled(e: com.google.firebase.database.DatabaseError) {}
        })
    }

    /** One more motion (or, with [clip], its clip) in this hour's "Motion detected ×N" entry. */
    private fun addToBurst(ctx: Context, clip: JSONObject?) {
        val b = burst ?: JSONObject().put("type", "motion_burst").put("n", BURST_AFTER).put("from", hourOf * 3_600_000L)
            .put("clips", JSONArray()).also { burst = it }
        if (clip == null) b.put("n", b.optInt("n") + 1)
        else {
            val list = b.optJSONArray("clips") ?: JSONArray().also { b.put("clips", it) }
            if (list.length() < 400) list.put(clip) else b.put("more", b.optInt("more") + 1)
        }
        b.put("text", "Motion detected ×${b.optInt("n")}")
        val sealed = LiveNet.seal(ctx, b.toString())
        val k = burstKey
        if (k == null) {
            val ref = path(ctx, "events")?.push() ?: return
            burstKey = ref.key
            ref.setValue(mapOf("t" to ServerValue.TIMESTAMP, "d" to sealed))
        } else path(ctx, "events/$k/d")?.setValue(sealed)
    }

    /** Which Activity filter an event counts for in the per-day summary (m motion, o monitoring, b battery & heat, s settings). */
    fun kindLetter(type: String): String? = when (type) {
        "motion", "motion_burst" -> "m"
        "monitor_on", "monitor_off", "monitor_paused", "safety_stop", "pin_locked", "remote_taken" -> "o"
        "battery", "unplugged", "hot" -> "b"
        "settings_local" -> "s"
        else -> null
    }

    /** yyyyMMdd of a time on this phone's calendar. */
    fun dayKey(ms: Long): String = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date(ms))

    /**
     * Per-day counts (days/<yyyyMMdd>/<letter>, and n = entries stored), so phones can show a calendar of activity and
     * how much history is kept without downloading the events themselves.
     */
    private fun dayCount(ctx: Context, letter: String) {
        val day = dayKey(System.currentTimeMillis())
        path(ctx, "days/$day")?.updateChildren(mapOf(letter to ServerValue.increment(1), "n" to ServerValue.increment(1)))
    }

    // ------------------------------------------------------------------ status + events from state changes

    private fun onStatus(ctx: Context, s: Status) {
        val prev = last
        last = s
        if (prev != null) {
            if (prev.mode != Mode.RECORDING && s.mode == Mode.RECORDING) event(ctx, "motion", "Motion detected")
            // the "clip" event itself is sent once the clip is saved (clipSaved), with its name, so alerts can open it
            if (prev.mode == Mode.RECORDING && s.mode != Mode.RECORDING && prev.recordingSinceWall > 0)
                lastClipMs = System.currentTimeMillis() - prev.recordingSinceWall
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
        // small changes (battery, temperature, last motion): once a minute while one of your phones has MotionCam open,
        // otherwise every 5 minutes (each upload wakes the radio); important ones at once
        val me = me(ctx)
        val watched = Live.phones.values.any { it.id != me && it.online && it.viewer }
        val gap = if (watched) 60_000L else 5 * 60_000L
        if (important || (minor && now - lastPub >= gap) || now - lastPub >= 10 * 60_000) publishStatus(ctx, s)
    }

    fun publishStatus(ctx: Context, s: Status = StatusBus.status) {
        lastPub = System.currentTimeMillis()
        val p = Prefs(ctx)
        val e = EngineHost.peek()
        val o = JSONObject()
            .put("name", Device.name(ctx)).put("mode", s.mode.name).put("monitoring", s.monitoring).put("message", s.message)
            .put("remote", holderJson())
            .put("standby", CameraService.inStandby).put("standbyOn", CameraService.standbyAllowed(ctx))
            .put("offAt", CameraService.instance?.autoOffAt ?: 0L)
            .put("rtc", Rtc.enabled).put("canStart", CameraService.inStandby || (CameraService.monitoring && !s.monitoring))
            .put("recSince", s.recordingSinceWall).put("lastMotion", s.lastMotionWall).put("battery", s.batteryPct)
            .put("charging", s.charging).put("tempC", s.tempC.toDouble()).put("lens", s.lens).put("format", s.format)
            .put("pin", pin(ctx) != null).put("pinForView", pin(ctx) != null && p.livePinForView)
            .put("settings", settings(p))
            // for the CROP frame on the live picture: how the picture is turned and the full recording size
            .put("rotation", e?.recordRotation() ?: 0).put("recW", e?.fullSize?.width ?: 0).put("recH", e?.fullSize?.height ?: 0)
            .put("live", JSONObject().put("iso", e?.liveIso ?: 0).put("shutterNs", e?.liveShutterNs ?: 0L)
                .put("kelvin", e?.liveKelvin ?: 0).put("focus", (e?.liveFocusDiopter ?: -1f).toDouble())
                .put("meter", e?.liveMeterEv?.takeIf { !it.isNaN() }?.toDouble() ?: JSONObject.NULL))
        val m = HashMap<String, Any>()
        m["at"] = ServerValue.TIMESTAMP
        m["d"] = LiveNet.seal(ctx, o.toString())
        val lensKey = p.camera
        // what the controls may offer: sent once the camera knows it (in standby it may not be open yet)
        if (lensKey != lastLens || lastLens.isEmpty()) caps(ctx)?.let { m["caps"] = LiveNet.seal(ctx, it.toString()); lastLens = lensKey }
        path(ctx, "status")?.updateChildren(m)
    }

    private fun settings(p: Prefs) = JSONObject()
        .put("camera", p.camera).put("resHeight", p.resHeight).put("fps", p.fps).put("hevc", p.hevc).put("bitrateMbps", p.bitrateMbps)
        .put("audio", p.audio).put("preRecordMs", p.preRecordMs).put("stopDelaySec", p.stopDelaySec).put("maxClipMin", p.maxClipMin)
        .put("sensitivity", p.sensitivity).put("lightFilter", p.lightFilter).put("minAreaPct", p.minAreaPct.toDouble())
        .put("minMotionMs", p.minMotionMs).put("exposureAuto", p.exposureAuto).put("evIndex", p.evIndex).put("iso", p.iso)
        .put("shutterNs", p.shutterNs).put("focusAuto", p.focusAuto).put("focusPos", p.focusPos.toDouble())
        .put("wbAuto", p.wbAuto).put("wbKelvin", p.wbKelvin).put("zoom", p.zoom.toDouble()).put("crop", p.crop)

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

    /**
     * Keeps activity as long as the network's "Keep activity" setting says (forever by default). Safety net: when all
     * cameras' history together nears the database's free 1 GB, the oldest events of this camera go first (and its
     * Activity says so), so new alerts and status updates never stop for lack of room.
     */
    private fun trimEvents(ctx: Context) {
        val ref = path(ctx, "events") ?: return
        Live.ref(ctx, "settings/keepDays")?.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                val days = (s.value as? Number)?.toInt() ?: 0
                if (days > 0) removeOlder(ctx, ref, Live.now() - days * 86_400_000L)
                Thread { try { safetyNet(ctx, ref) } catch (_: Exception) {} }.start()
            }
            override fun onCancelled(e: DatabaseError) {}
        })
    }

    /** Removes this camera's events older than [cutoff], a batch at a time. */
    private fun removeOlder(ctx: Context, ref: com.google.firebase.database.DatabaseReference, cutoff: Long) {
        ref.orderByChild("t").endAt(cutoff.toDouble()).limitToFirst(1000).addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                val m = HashMap<String, Any?>()
                for (c in s.children) c.key?.let { m[it] = null }
                if (m.isEmpty()) return
                ref.updateChildren(m)
                val old = dayKey(cutoff)
                path(ctx, "days")?.orderByKey()?.endAt(old)?.addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(d: DataSnapshot) {
                        val dm = HashMap<String, Any?>(); for (c in d.children) if ((c.key ?: "") < old) c.key?.let { dm[it] = null }
                        if (dm.isNotEmpty()) path(ctx, "days")?.updateChildren(dm)
                    }
                    override fun onCancelled(e: DatabaseError) {}
                })
                if (m.size >= 1000) removeOlder(ctx, ref, cutoff)
            }
            override fun onCancelled(e: DatabaseError) {}
        })
    }

    /** About this many events fit in 90% of the free 1 GB (≈300 bytes each). */
    const val SAFE_EVENTS = 3_000_000L

    private fun safetyNet(ctx: Context, ref: com.google.firebase.database.DatabaseReference) {
        val ids = (Live.phones.keys + me(ctx)).toSet()
        val totals = java.util.concurrent.ConcurrentHashMap<String, Long>()
        val latch = java.util.concurrent.CountDownLatch(ids.size)
        for (id in ids) Live.ref(ctx, "cams/$id/days")?.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) { totals[id] = s.children.sumOf { (it.child("n").value as? Number)?.toLong() ?: 0L }; latch.countDown() }
            override fun onCancelled(e: DatabaseError) { latch.countDown() }
        }) ?: latch.countDown()
        latch.await(30, java.util.concurrent.TimeUnit.SECONDS)
        val total = totals.values.sum()
        val mine = totals[me(ctx)] ?: 0L
        if (total <= SAFE_EVENTS || mine <= 0) return
        // this camera's share of what has to go, oldest first
        val drop = ((total - SAFE_EVENTS) * mine / total + 1000).coerceAtMost(mine)
        ref.orderByChild("t").limitToFirst(drop.coerceAtMost(5000).toInt()).addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                val m = HashMap<String, Any?>()
                var newest = 0L
                for (c in s.children) { c.key?.let { m[it] = null }; newest = maxOf(newest, (c.child("t").value as? Number)?.toLong() ?: 0L) }
                if (m.isEmpty()) return
                ref.updateChildren(m)
                path(ctx, "days/${dayKey(newest - Live.serverOffset)}/n")?.setValue(ServerValue.increment(-m.size.toLong()))
                event(ctx, "trimmed", "Oldest activity removed to stay within the database's free storage")
            }
            override fun onCancelled(e: DatabaseError) {}
        })
    }

    // ------------------------------------------------------------------ PIN

    private fun pin(ctx: Context): String? = SecretStore.get(ctx, "live.pin")?.takeIf { it.isNotEmpty() }

    /** The PIN pictures of this camera are locked with (its live view needs the PIN), or null. */
    fun pinFor(ctx: Context): String? = pin(ctx)?.takeIf { Prefs(ctx).livePinForView }
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

    /** A live video request signed with this camera's PIN (when its live view needs the PIN). */
    fun verifyView(ctx: Context, sealed: String, sig: String?): Boolean = pinOk(ctx, sealed, sig) == true

    /** A remote start (by push) signed with this camera's PIN; it must have a PIN. Counts towards the lock-out. */
    fun verifyStart(ctx: Context, sealed: String, sig: String?): Boolean = hasPin(ctx) && pinOk(ctx, sealed, sig) == true

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
                val who = s.children.filter { c ->
                    val at = (c.child("at").value as? Number)?.toLong() ?: 0
                    at > Live.now() - 30_000 && (!Prefs(ctx).livePinForView || pinOk(ctx, "watch:${c.key}:$at", c.child("sig").value as? String) == true)
                }.mapNotNull { it.key }
                watchers = who
                if (who.isNotEmpty()) startLive(ctx) else stopLive()
                Live.notifyChanged()
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

    /** Phones (device ids) watching this camera's live view right now. */
    @Volatile var watchers: List<String> = emptyList()
    @Volatile private var liveThread: Thread? = null
    /** This phone is sending its live picture to another phone right now. */
    val streaming: Boolean get() = running && liveThread != null

    /** Low-rate live view while someone watches (a frame every 2 s). */
    private fun startLive(ctx: Context) {
        if (liveThread != null) return
        // in standby the camera is closed: open it while someone watches (allowed: the camera service was started from
        // the app), and close it again when they stop. While monitoring this changes nothing.
        main.post { try { EngineHost.acquire(ctx, "live") } catch (_: Exception) {} }
        val t = Thread {
            FrameHub.clients++
            try {
                var seq = FrameHub.currentSeq()
                while (running && liveThread === Thread.currentThread()) {
                    val f = FrameHub.waitNext(seq, 3000)
                    if (f != null) {
                        seq = f.first
                        path(ctx, "live")?.setValue(mapOf("at" to ServerValue.TIMESTAMP, "img" to LiveNet.sealBytes(ctx, f.second)))
                        liveValuesIfChanged(ctx)
                    }
                    Thread.sleep(2000)
                }
            } catch (_: InterruptedException) {
            } finally { FrameHub.clients-- }
        }
        liveThread = t
        t.isDaemon = true
        t.start()
        Live.notifyChanged()   // this phone's main screen shows it is live
    }

    private var lastLiveValues = ""
    private var lastLiveValuesAt = 0L

    /** While someone watches: the automatic values (ISO, shutter, white balance, focus, meter) every few seconds when they change. */
    private fun liveValuesIfChanged(ctx: Context) {
        val e = EngineHost.peek() ?: return
        val v = "${e.liveIso}|${e.liveShutterNs}|${e.liveKelvin}|${"%.1f".format(e.liveFocusDiopter)}|${"%.1f".format(e.liveMeterEv)}"
        val now = System.currentTimeMillis()
        if (v != lastLiveValues && now - lastLiveValuesAt >= 3000) { lastLiveValues = v; lastLiveValuesAt = now; main.post { publishStatus(ctx) } }
    }

    private fun stopLive() {
        val wasLive = liveThread != null
        liveThread?.interrupt(); liveThread = null
        if (wasLive) Live.notifyChanged()
        if (wasLive) main.post { try { EngineHost.release("live") } catch (_: Exception) {} }
    }

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
    private val PROTECTED = setOf("set", "pause", "resume", "stop", "start", "take", "release")
    /** Commands that change the camera: only the phone holding the remote may send them. */
    private val CONTROL = setOf("set", "pause", "resume", "stop", "start")

    // ------------------------------------------------------------------ the remote (one controlling phone at a time)

    /**
     * The remote is automatic: a viewer's change claims it, the viewer keeps it alive every few seconds while it is
     * changing things, and it frees itself 8 s after the last renewal (or when given back). No alerts: it changes hands
     * often; open camera screens show who is changing the camera.
     */
    private const val LEASE_MS = 8_000L
    @Volatile private var holderId: String? = null
    @Volatile private var holderName = ""
    @Volatile private var holderUntil = 0L

    private fun holder(): String? = holderId?.takeIf { Live.now() < holderUntil }
    private fun holderJson(): Any = holder()?.let { JSONObject().put("id", it).put("name", holderName).put("until", holderUntil) } ?: JSONObject.NULL

    /** [by] claims (or keeps) the remote; the status tells the other phones. */
    private fun giveRemote(ctx: Context, by: String, name: String) {
        holderId = by; holderName = name; holderUntil = Live.now() + LEASE_MS
        main.post { publishStatus(ctx) }
    }
    private var lastHolderName = HashMap<String, String>()
    private fun holderNameOf(id: String) = lastHolderName[id] ?: "another phone"

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
        val by = d.optString("by")
        if (by.isNotEmpty()) lastHolderName[by] = who
        // one phone changes a camera at a time: a change claims a free remote; while another phone holds it, wait
        if (type in CONTROL || type == "take") {
            val h = holder()
            if (h != null && h != by) { reply(false, "$holderName is changing this camera right now — try again in a few seconds"); return }
            if (by.isNotEmpty()) giveRemote(ctx, by, who)
        }
        when (type) {
            "take" -> reply(true, "You have the remote")
            "release" -> {
                if (holder() == by) { holderId = null; holderUntil = 0; main.post { publishStatus(ctx) } }
                reply(true, "Remote given back")
            }
            "pause" -> {
                EngineHost.peek()?.setMonitoring(false)
                CameraService.instance?.stoppedRemotely()
                val off = (CameraService.instance?.autoOffAt ?: 0L) > 0
                event(ctx, "monitor_paused", "Monitoring stopped by $who" + if (off) " · the camera turns off after ${CameraService.AUTO_OFF_MIN} min to save power" else " · camera still on")
                reply(true, "Monitoring stopped" + if (off) " · the camera turns off after ${CameraService.AUTO_OFF_MIN} min unless you start it again" else "")
                main.postDelayed({ publishStatus(ctx) }, 300)
            }
            "resume" -> { EngineHost.peek()?.setMonitoring(true); CameraService.instance?.startedAgain(); reply(true, "Monitoring started") }
            "start" -> when {
                CameraService.inStandby -> {
                    reply(true, "Starting monitoring…"); main.post { CameraService.instance?.startMonitoring() }
                }
                CameraService.monitoring && EngineHost.peek()?.monitoring == false -> { EngineHost.peek()?.setMonitoring(true); CameraService.instance?.startedAgain(); reply(true, "Monitoring started") }
                CameraService.monitoring -> reply(true, "Already monitoring")
                else -> reply(false, "The camera phone is not in standby")
            }
            "stop" -> {
                event(ctx, "monitor_off", "Camera shut off by $who")
                reply(true, if (CameraService.standbyAllowed(ctx)) "Camera shut off · standby (Start monitoring turns it back on)" else "Shutting off the camera")
                main.post { CameraService.stop(ctx) }
            }
            "set" -> {
                val args = d.optJSONObject("args") ?: JSONObject()
                remoteKeys = args.keys().asSequence().toSet() + setOf("zoom"); remoteAt = System.currentTimeMillis()
                val (applied, rejected) = RemoteSettings.apply(ctx, args)
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
