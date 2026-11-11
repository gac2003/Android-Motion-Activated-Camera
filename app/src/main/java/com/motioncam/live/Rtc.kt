package com.motioncam.live

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import com.motioncam.cloud.Device
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.net.HttpURLConnection
import java.net.URL

/**
 * Smooth live video (WebRTC), in your own build with a Cloudflare TURN key (MC_CF_TURN_KEY_ID / MC_CF_TURN_API_TOKEN,
 * SETUP.md). Without the key it's off and the live view stays as before (a picture every 2 s over the live
 * connection); every viewing phone can also turn it off (Settings › Your phones › Smooth live video). The picture
 * goes straight between the phones when the network allows it, otherwise through Cloudflare's relay (TURN).
 *
 * The phones agree on the connection through the live connection, sealed with the network key like everything else:
 *   cams/<camera>/rtc/<session>/offer  {d: sealed {sdp, by, byName, at}, sig (PIN, when the live view needs it)}
 *   cams/<camera>/rtc/<session>/answer {d: sealed sdp}
 *   cams/<camera>/rtc/<session>/v/<id>, c/<id>   ICE candidates from the viewer / the camera (sealed)
 * The viewer removes the session when it leaves (also if it drops: onDisconnect); the camera then closes it.
 */
object Rtc {
    val enabled: Boolean get() = com.motioncam.BuildConfig.CF_TURN_KEY_ID.isNotEmpty() && com.motioncam.BuildConfig.CF_TURN_API_TOKEN.isNotEmpty()

    // ------------------------------------------------------------------ ICE servers (Cloudflare STUN + TURN)

    @Volatile private var ice: Pair<List<PeerConnection.IceServer>, Long>? = null

    /** Short-lived TURN credentials from Cloudflare (cached most of a day). Network; background threads only. */
    @Synchronized
    fun iceServers(): List<PeerConnection.IceServer> {
        ice?.let { (l, until) -> if (System.currentTimeMillis() < until) return l }
        val out = ArrayList<PeerConnection.IceServer>()
        // Cloudflare's current endpoint, then the older one (their answers differ slightly; both are read)
        for (path in listOf("generate-ice-servers", "generate")) {
            try {
                val c = URL("https://rtc.live.cloudflare.com/v1/turn/keys/${com.motioncam.BuildConfig.CF_TURN_KEY_ID}/credentials/$path")
                    .openConnection() as HttpURLConnection
                c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 10_000; c.readTimeout = 10_000
                c.setRequestProperty("Authorization", "Bearer ${com.motioncam.BuildConfig.CF_TURN_API_TOKEN}")
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write("""{"ttl": 86400}""".toByteArray()) }
                val code = c.responseCode
                if (code in 200..299) {
                    val r = JSONObject(c.inputStream.bufferedReader().readText())
                    // an array of servers (current API) or a single object (older one)
                    val arr = r.optJSONArray("iceServers") ?: JSONArray().apply { r.optJSONObject("iceServers")?.let { put(it) } }
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        val urls = o.opt("urls").let { u -> if (u is JSONArray) (0 until u.length()).map { u.getString(it) } else listOf(u.toString()) }
                            .filter { it.isNotEmpty() }
                        if (urls.isEmpty()) continue
                        val b = PeerConnection.IceServer.builder(urls)
                        o.optString("username").takeIf { it.isNotEmpty() }?.let { b.setUsername(it) }
                        o.optString("credential").takeIf { it.isNotEmpty() }?.let { b.setPassword(it) }
                        out += b.createIceServer()
                        if (urls.any { it.startsWith("turn") }) relayReady = true
                    }
                    issue = if (relayReady) null else "Cloudflare gave no relay addresses"
                } else issue = "Cloudflare refused the relay key (HTTP $code): check MC_CF_TURN_KEY_ID / MC_CF_TURN_API_TOKEN"
                c.disconnect()
            } catch (e: Exception) { issue = "Couldn't reach Cloudflare (${e.javaClass.simpleName})" }
            if (relayReady) break
        }
        if (out.isEmpty()) out += PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer()
        if (relayReady) ice = out to System.currentTimeMillis() + 20 * 3_600_000L
        return out
    }

    /** The relay (TURN) answered with credentials this time. */
    @Volatile var relayReady = false; private set
    /** What went wrong getting the relay, if anything (shown when video can't connect). */
    @Volatile var issue: String? = null; private set

    // ------------------------------------------------------------------ the relay's monthly allowance (Cloudflare has no cap)
    //
    // Only video through the relay costs anything (Cloudflare bills what it sends out). Each viewing phone measures the
    // video it receives through the relay (plus 10% for overhead) and adds it to one counter for the whole network:
    // rtcUsage/<yyyyMM, UTC> = bytes. The cap (settings/relayCapGb, default 975 GB of Cloudflare's free 1,000) is the
    // same for every phone. Once it's reached, live video only uses direct connections (free) until next month, and
    // falls back to pictures where that isn't possible.

    const val DEFAULT_CAP_GB = 975
    private fun month() = java.text.SimpleDateFormat("yyyyMM", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date())

    /** This month's relayed bytes and the cap (GB) on [done] (main thread). */
    fun usage(ctx: Context, done: (used: Long, capGb: Int) -> Unit) {
        val m = Handler(Looper.getMainLooper())
        val u = Live.ref(ctx, "rtcUsage/${month()}"); val c = Live.ref(ctx, "settings/relayCapGb")
        if (u == null || c == null) { done(0, DEFAULT_CAP_GB); return }
        u.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                val used = (s.value as? Number)?.toLong() ?: 0L
                c.addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(cs: DataSnapshot) { val cap = (cs.value as? Number)?.toInt() ?: DEFAULT_CAP_GB; m.post { done(used, cap) } }
                    override fun onCancelled(e: DatabaseError) { m.post { done(used, DEFAULT_CAP_GB) } }
                })
            }
            override fun onCancelled(e: DatabaseError) { m.post { done(0, DEFAULT_CAP_GB) } }
        })
    }

    fun setCap(ctx: Context, gb: Int) { Live.ref(ctx, "settings/relayCapGb")?.setValue(gb) }

    fun addUsage(ctx: Context, bytes: Long) { if (bytes > 0) Live.ref(ctx, "rtcUsage/${month()}")?.setValue(ServerValue.increment(bytes)) }

    /** STUN only: direct connections, no relay (free). */
    val stunOnly: List<PeerConnection.IceServer> get() = listOf(PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer())

    // ------------------------------------------------------------------ WebRTC itself (one factory per process)

    val egl: EglBase by lazy { EglBase.create() }
    @Volatile private var factory: PeerConnectionFactory? = null

    @Synchronized
    fun factory(ctx: Context): PeerConnectionFactory {
        factory?.let { return it }
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(ctx.applicationContext).createInitializationOptions())
        return PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory().also { factory = it }
    }

    fun config(servers: List<PeerConnection.IceServer>) = PeerConnection.RTCConfiguration(servers).apply {
        sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
    }

    fun candJson(c: IceCandidate) = JSONObject().put("m", c.sdpMid).put("i", c.sdpMLineIndex).put("s", c.sdp).toString()
    fun candOf(j: JSONObject) = IceCandidate(j.optString("m"), j.optInt("i"), j.optString("s"))

    open class Sdp(private val ok: (SessionDescription?) -> Unit = {}) : SdpObserver {
        override fun onCreateSuccess(d: SessionDescription?) = ok(d)
        override fun onSetSuccess() {}
        override fun onCreateFailure(e: String?) {}
        override fun onSetFailure(e: String?) {}
    }

    abstract class PcObserver : PeerConnection.Observer {
        override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
        override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {}
        override fun onIceConnectionReceivingChange(b: Boolean) {}
        override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {}
        override fun onIceCandidatesRemoved(c: Array<out IceCandidate>?) {}
        override fun onAddStream(s: MediaStream?) {}
        override fun onRemoveStream(s: MediaStream?) {}
        override fun onDataChannel(d: org.webrtc.DataChannel?) {}
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(r: RtpReceiver?, s: Array<out MediaStream>?) {}
    }
}

/**
 * The camera side: answers viewers' offers with the camera picture (the motion-detection stream, up to 15 frames a
 * second), keeps the camera open while someone watches, and closes each connection when its viewer leaves.
 */
object RtcCamera {
    private val main = Handler(Looper.getMainLooper())
    private class Session(val id: String, val pc: PeerConnection, val vListener: ChildEventListener, val by: String)
    private val sessions = HashMap<String, Session>()
    private var listener: ChildEventListener? = null
    private var source: VideoSource? = null
    private var track: VideoTrack? = null
    private var app: Context? = null
    @Volatile private var lastFrameAt = 0L
    @Volatile private var framesSent = 0L
    private var holding = false

    fun start(ctx: Context) {
        if (!Rtc.enabled || listener != null) return
        val c = ctx.applicationContext
        app = c
        listener = object : ChildEventListener {
            override fun onChildAdded(s: DataSnapshot, prev: String?) { s.key?.let { k -> Thread { try { offer(c, k, s) } catch (_: Exception) {} }.start() } }
            override fun onChildRemoved(s: DataSnapshot) { s.key?.let { k -> main.post { close(c, k) } } }
            override fun onChildChanged(s: DataSnapshot, prev: String?) {}
            override fun onChildMoved(s: DataSnapshot, prev: String?) {}
            override fun onCancelled(e: DatabaseError) {}
        }.also { CameraLink.path(c, "rtc")?.addChildEventListener(it) }
    }

    fun stop(ctx: Context) {
        listener?.let { CameraLink.path(ctx, "rtc")?.removeEventListener(it) }
        listener = null
        main.post { for (k in sessions.keys.toList()) close(ctx, k) }
    }

    /** A frame from the camera (analysis thread): sent to the viewers, at most 15 a second. */
    private val sink: (Int, Int, (org.webrtc.JavaI420Buffer) -> Unit, Int, Long) -> Unit = { w, h, fill, rot, ts ->
        val now = System.currentTimeMillis()
        val src = source
        if (src != null && now - lastFrameAt >= 66) {
            lastFrameAt = now
            val buf = org.webrtc.JavaI420Buffer.allocate(w, h)
            try {
                fill(buf)
                // WebRTC's own clock (the camera's timestamps can be on a different one, which makes frames look stale)
                val f = VideoFrame(buf, rot, System.nanoTime())
                src.capturerObserver.onFrameCaptured(f)
                framesSent++
                f.release()
            } catch (_: Exception) { buf.release() }
        }
    }

    private fun offer(ctx: Context, id: String, s: DataSnapshot) {
        val sealed = s.child("offer/d").value as? String ?: return
        val o = LiveNet.open(ctx, sealed)?.let { try { JSONObject(it) } catch (_: Exception) { null } } ?: return
        if (System.currentTimeMillis() - o.optLong("at") > 5 * 60_000L) return
        // the live view needs the PIN on this camera: the offer must be signed with it
        if (CameraLink.pinFor(ctx) != null && !CameraLink.verifyView(ctx, sealed, s.child("offer/sig").value as? String)) {
            com.motioncam.Health.log(ctx, "Live video: refused a request (wrong or missing PIN)"); return
        }
        val f = Rtc.factory(ctx)
        // the month's relay allowance is used up: direct connections only (the viewer says so)
        val servers = if (o.optBoolean("relay", true)) Rtc.iceServers() else Rtc.stunOnly
        main.post {
            if (sessions.containsKey(id) || sessions.size >= 3) return@post
            ensureTrack(ctx, f)
            val ref = CameraLink.path(ctx, "rtc/$id") ?: return@post
            val pc = f.createPeerConnection(Rtc.config(servers), object : Rtc.PcObserver() {
                override fun onIceCandidate(c: IceCandidate) { ref.child("c").push().setValue(LiveNet.seal(ctx, Rtc.candJson(c))) }
                override fun onIceConnectionChange(st: PeerConnection.IceConnectionState?) {
                    if (st == PeerConnection.IceConnectionState.CONNECTED || st == PeerConnection.IceConnectionState.FAILED)
                        com.motioncam.Health.log(ctx, "Live video: connection ${st.name.lowercase()}" + if (st == PeerConnection.IceConnectionState.CONNECTED) " (sending ${framesSent} frames so far)" else "")
                    if (st == PeerConnection.IceConnectionState.CONNECTED) {
                        val before = framesSent
                        main.postDelayed({ com.motioncam.Health.log(ctx, "Live video: ${framesSent - before} frames in the first 10 s") }, 10_000)
                    }
                    if (st == PeerConnection.IceConnectionState.FAILED || st == PeerConnection.IceConnectionState.CLOSED) main.post { close(ctx, id) }
                }
            }) ?: return@post
            val vl = object : ChildEventListener {
                override fun onChildAdded(c: DataSnapshot, prev: String?) {
                    LiveNet.open(ctx, c.value as? String)?.let { try { pc.addIceCandidate(Rtc.candOf(JSONObject(it))) } catch (_: Exception) {} }
                }
                override fun onChildRemoved(c: DataSnapshot) {}
                override fun onChildChanged(c: DataSnapshot, prev: String?) {}
                override fun onChildMoved(c: DataSnapshot, prev: String?) {}
                override fun onCancelled(e: DatabaseError) {}
            }
            sessions[id] = Session(id, pc, vl, o.optString("byName"))
            holdCamera(ctx)
            com.motioncam.Health.log(ctx, "Live video: request from ${o.optString("byName").ifEmpty { "a phone" }} (relay " +
                (if (!o.optBoolean("relay", true)) "off: monthly limit" else if (Rtc.relayReady) "ready" else "unavailable: " + (Rtc.issue ?: "?")) + ")")
            pc.setRemoteDescription(object : Rtc.Sdp() {
                override fun onSetSuccess() {
                    // the camera's video goes on the video channel the viewer asked for (a channel of its own would never
                    // be agreed on: the connection came up, but no video flowed)
                    val t = pc.transceivers.firstOrNull { it.mediaType == org.webrtc.MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO }
                    if (t == null) { com.motioncam.Health.log(ctx, "Live video: the request had no video channel"); return }
                    t.setDirection(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY)
                    t.sender.setTrack(track, false)
                    pc.createAnswer(Rtc.Sdp { d ->
                        d ?: return@Sdp
                        pc.setLocalDescription(Rtc.Sdp(), d)
                        ref.child("answer").setValue(mapOf("d" to LiveNet.seal(ctx, d.description), "at" to ServerValue.TIMESTAMP))
                    }, MediaConstraints())
                }
            }, SessionDescription(SessionDescription.Type.OFFER, o.getString("sdp")))
            ref.child("v").addChildEventListener(vl)
            com.motioncam.Health.log(ctx, "Live video to ${o.optString("byName").ifEmpty { "a phone" }}")
        }
    }

    private fun ensureTrack(ctx: Context, f: PeerConnectionFactory) {
        if (track != null) return
        val src = f.createVideoSource(false)
        src.capturerObserver.onCapturerStarted(true)
        source = src
        track = f.createVideoTrack("camera", src)
    }

    private fun close(ctx: Context, id: String) {
        val s = sessions.remove(id) ?: return
        CameraLink.path(ctx, "rtc/$id/v")?.removeEventListener(s.vListener)
        try { s.pc.dispose() } catch (_: Exception) {}
        CameraLink.path(ctx, "rtc/$id")?.removeValue()
        if (sessions.isEmpty()) releaseCamera()
    }

    /** Someone watches: the camera stays open and sends frames (closed again when the last viewer leaves). */
    private fun holdCamera(ctx: Context) {
        if (holding) return
        holding = true
        com.motioncam.FrameHub.rtcSink = sink
        try { com.motioncam.EngineHost.acquire(ctx, "rtc").applyPro() } catch (_: Exception) {}
    }

    private fun releaseCamera() {
        if (!holding) return
        holding = false
        com.motioncam.FrameHub.rtcSink = null
        try { com.motioncam.EngineHost.release("rtc") } catch (_: Exception) {}
        try { track?.dispose() } catch (_: Exception) {}
        try { source?.dispose() } catch (_: Exception) {}
        track = null; source = null
    }
}

/**
 * The viewing side of one live view: offers to receive the camera's video, shows it in [renderTo], and tidies up on
 * [stop] (or if the phone drops: the session is removed by the server). [failed] when no connection comes up.
 */
class RtcViewer(private val ctx: Context, private val cam: String, private val pin: String?, private val renderTo: VideoSink,
                private val allowRelay: Boolean, private val usedBefore: Long, private val capGb: Int,
                private val overCap: () -> Unit, private val failed: (why: String) -> Unit) {
    @Volatile private var answered = false
    /** The video connection is up (frames should follow). */
    @Volatile var connected = false; private set
    private val main = Handler(Looper.getMainLooper())
    private val id = Device.id(ctx) + "-" + System.currentTimeMillis()
    private var pc: PeerConnection? = null
    private var stopped = false
    private var answerL: ValueEventListener? = null
    private var candL: ChildEventListener? = null
    private val ref get() = Live.ref(ctx, "cams/$cam/rtc/$id")

    fun start() {
        Thread {
            val f = try { Rtc.factory(ctx) } catch (_: Exception) { null }
            val servers = if (allowRelay) Rtc.iceServers() else Rtc.stunOnly
            main.post { if (!stopped && f != null) connect(f, servers) else if (f == null) failed("video isn't supported on this phone") }
        }.start()
        main.postDelayed({
            if (stopped || connected) return@postDelayed
            stop()
            failed(when {
                !answered -> "the camera didn't answer (it needs the same MotionCam version, with video)"
                allowRelay && !Rtc.relayReady -> "couldn't connect, and no relay: " + (Rtc.issue ?: "Cloudflare didn't answer")
                else -> "couldn't connect through this network"
            })
        }, 20_000)
    }

    private fun connect(f: PeerConnectionFactory, servers: List<PeerConnection.IceServer>) {
        val r = ref ?: run { failed("not connected to your phones"); return }
        r.onDisconnect().removeValue()
        val p = f.createPeerConnection(Rtc.config(servers), object : Rtc.PcObserver() {
            override fun onIceCandidate(c: IceCandidate) { ref?.child("v")?.push()?.setValue(LiveNet.seal(ctx, Rtc.candJson(c))) }
            override fun onTrack(t: RtpTransceiver?) { (t?.receiver?.track() as? VideoTrack)?.let { v -> main.post { if (!stopped) v.addSink(renderTo) } } }
            override fun onIceConnectionChange(st: PeerConnection.IceConnectionState?) {
                if (st == PeerConnection.IceConnectionState.CONNECTED || st == PeerConnection.IceConnectionState.COMPLETED) connected = true
                if (st == PeerConnection.IceConnectionState.FAILED) main.post {
                    if (!stopped) { stop(); failed(if (allowRelay && !Rtc.relayReady) "connection failed, and no relay: " + (Rtc.issue ?: "Cloudflare didn't answer") else "connection failed") }
                }
            }
        }) ?: run { failed("couldn't start video"); return }
        pc = p
        p.addTransceiver(org.webrtc.MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO, RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY))
        p.createOffer(Rtc.Sdp { d ->
            d ?: return@Sdp
            main.post {
                if (stopped) return@post
                p.setLocalDescription(Rtc.Sdp(), d)
                val sealed = LiveNet.seal(ctx, JSONObject().put("sdp", d.description).put("by", Device.id(ctx)).put("byName", Device.name(ctx))
                    .put("at", System.currentTimeMillis()).put("relay", allowRelay).toString())
                val offer = HashMap<String, Any>(); offer["d"] = sealed
                pin?.let { offer["sig"] = CameraLink.sign(cam, it, sealed) }
                r.child("offer").setValue(offer)
                answerL = object : ValueEventListener {
                    override fun onDataChange(s: DataSnapshot) {
                        val sdp = LiveNet.open(ctx, s.child("d").value as? String) ?: return
                        answered = true
                        if (p.remoteDescription == null) p.setRemoteDescription(Rtc.Sdp(), SessionDescription(SessionDescription.Type.ANSWER, sdp))
                    }
                    override fun onCancelled(e: DatabaseError) {}
                }.also { r.child("answer").addValueEventListener(it) }
                candL = object : ChildEventListener {
                    override fun onChildAdded(c: DataSnapshot, prev: String?) {
                        LiveNet.open(ctx, c.value as? String)?.let { try { p.addIceCandidate(Rtc.candOf(JSONObject(it))) } catch (_: Exception) {} }
                    }
                    override fun onChildRemoved(c: DataSnapshot) {}
                    override fun onChildChanged(c: DataSnapshot, prev: String?) {}
                    override fun onChildMoved(c: DataSnapshot, prev: String?) {}
                    override fun onCancelled(e: DatabaseError) {}
                }.also { r.child("c").addChildEventListener(it) }
                main.postDelayed(meter, 10_000)
            }
        }, MediaConstraints())
    }

    // ---- relay metering: every 10 s, what came through the relay since last time goes to the month's counter
    private var lastBytes = 0L
    private var relayed = 0L
    private val meter: Runnable = object : Runnable {
        override fun run() {
            val p = pc ?: return
            if (stopped) return
            p.getStats { rep ->
                val m = rep.statsMap
                val pair = m.values.firstOrNull { it.type == "transport" }?.members?.get("selectedCandidatePairId")?.let { m[it.toString()] }
                    ?: m.values.firstOrNull { it.type == "candidate-pair" && it.members["nominated"] == true && it.members["state"] == "succeeded" }
                if (pair != null) {
                    val bytes = (pair.members["bytesReceived"] as? Number)?.toLong() ?: (pair.members["bytesReceived"]?.toString()?.toLongOrNull() ?: 0L)
                    fun type(id: Any?) = m[id?.toString()]?.members?.get("candidateType")?.toString()
                    val relay = type(pair.members["localCandidateId"]) == "relay" || type(pair.members["remoteCandidateId"]) == "relay"
                    val delta = (bytes - lastBytes).coerceAtLeast(0L)
                    lastBytes = bytes
                    if (relay && delta > 0) {
                        val billed = delta + delta / 10   // + TURN overhead
                        relayed += billed
                        Rtc.addUsage(ctx, billed)
                        if (usedBefore + relayed >= capGb * 1_000_000_000L) main.post { if (!stopped) { stop(); overCap() } }
                    }
                }
            }
            main.postDelayed(this, 10_000)
        }
    }

    fun stop() {
        if (stopped) return
        stopped = true
        main.removeCallbacks(meter)
        val r = ref
        answerL?.let { r?.child("answer")?.removeEventListener(it) }
        candL?.let { r?.child("c")?.removeEventListener(it) }
        try { pc?.dispose() } catch (_: Exception) {}
        pc = null
        r?.removeValue()
    }
}

/**
 * Smooth live video in a small picture (the camera's screen): a video surface placed under [under] in [frame], the
 * relay allowance checked first, and a fallback to the pictures with a reason. [playing] when video shows.
 */
class LiveVideo(private val a: android.app.Activity, private val cam: String, private val r: CamRemote,
                frame: android.widget.FrameLayout, private val under: android.view.View,
                private val playing: () -> Unit, private val fallback: (why: String) -> Unit) {
    private val renderer: org.webrtc.SurfaceViewRenderer? = if (!Rtc.enabled) null else try {
        org.webrtc.SurfaceViewRenderer(a).apply {
            init(Rtc.egl.eglBaseContext, object : org.webrtc.RendererCommon.RendererEvents {
                override fun onFirstFrameRendered() {
                    a.runOnUiThread {
                        if (viewer == null) return@runOnUiThread
                        on = true
                        under.visibility = android.view.View.INVISIBLE
                        com.motioncam.Health.log(a, "Live video from ${r.name()}: playing")
                        playing()
                    }
                }
                override fun onFrameResolutionChanged(w: Int, h: Int, rot: Int) {}
            })
            setScalingType(org.webrtc.RendererCommon.ScalingType.SCALE_ASPECT_FIT)
            setOnClickListener { under.performClick() }   // same as tapping the picture (full screen)
        }.also { frame.addView(it, 0, android.widget.FrameLayout.LayoutParams(-1, -1)) }   // under the pictures (see RemoteLiveActivity)
    } catch (_: Throwable) { null }

    private var viewer: RtcViewer? = null
    /** Video is showing. */
    var on = false; private set
    /** Video was tried (and is connecting or showing). */
    val active get() = viewer != null

    /** Tries video; false when it can't be used here (then the pictures are the live view). */
    fun start(): Boolean {
        val rd = renderer ?: return false
        if (viewer != null) return true
        if (!com.motioncam.Prefs(a).liveSmooth || !r.online()) return false
        val s = r.status() ?: return false
        if (!s.optBoolean("rtc")) return false
        val pin = r.pinFor()
        if (s.optBoolean("pinForView") && pin == null) return false
        Rtc.usage(a) { used, capGb ->
            if (a.isFinishing || viewer != null) return@usage
            viewer = RtcViewer(a, cam, pin, rd, used < capGb * 1_000_000_000L, used, capGb,
                overCap = { done("this month's relay allowance is used up") }) { why -> done(why) }.also { it.start() }
        }
        return true
    }

    private fun done(why: String) {
        stop()
        com.motioncam.Health.log(a, "Live video from ${r.name()} didn't work: $why")
        fallback(why)
    }

    fun stop() {
        viewer?.stop(); viewer = null; on = false
        renderer?.clearImage()
        under.visibility = android.view.View.VISIBLE
    }

    fun release() { stop(); try { renderer?.release() } catch (_: Throwable) {} }
}
