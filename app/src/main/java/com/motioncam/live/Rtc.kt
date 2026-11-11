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
        try {
            val c = URL("https://rtc.live.cloudflare.com/v1/turn/keys/${com.motioncam.BuildConfig.CF_TURN_KEY_ID}/credentials/generate-ice-servers")
                .openConnection() as HttpURLConnection
            c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 10_000; c.readTimeout = 10_000
            c.setRequestProperty("Authorization", "Bearer ${com.motioncam.BuildConfig.CF_TURN_API_TOKEN}")
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write("""{"ttl": 86400}""".toByteArray()) }
            if (c.responseCode in 200..299) {
                val r = JSONObject(c.inputStream.bufferedReader().readText())
                // an array of servers (current API) or a single object (older one)
                val arr = r.optJSONArray("iceServers") ?: JSONArray().apply { r.optJSONObject("iceServers")?.let { put(it) } }
                for (i in 0 until arr.length()) {
                    val s = arr.getJSONObject(i)
                    val urls = s.opt("urls").let { u -> if (u is JSONArray) (0 until u.length()).map { u.getString(it) } else listOf(u.toString()) }
                    val b = PeerConnection.IceServer.builder(urls)
                    s.optString("username").takeIf { it.isNotEmpty() }?.let { b.setUsername(it) }
                    s.optString("credential").takeIf { it.isNotEmpty() }?.let { b.setPassword(it) }
                    out += b.createIceServer()
                }
            }
            c.disconnect()
        } catch (_: Exception) {}
        if (out.isEmpty()) out += PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer()
        else ice = out to System.currentTimeMillis() + 20 * 3_600_000L
        return out
    }

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
                val f = VideoFrame(buf, rot, ts)
                src.capturerObserver.onFrameCaptured(f)
                f.release()
            } catch (_: Exception) { buf.release() }
        }
    }

    private fun offer(ctx: Context, id: String, s: DataSnapshot) {
        val sealed = s.child("offer/d").value as? String ?: return
        val o = LiveNet.open(ctx, sealed)?.let { try { JSONObject(it) } catch (_: Exception) { null } } ?: return
        if (System.currentTimeMillis() - o.optLong("at") > 5 * 60_000L) return
        // the live view needs the PIN on this camera: the offer must be signed with it
        if (CameraLink.pinFor(ctx) != null && !CameraLink.verifyView(ctx, sealed, s.child("offer/sig").value as? String)) return
        val f = Rtc.factory(ctx)
        val servers = Rtc.iceServers()
        main.post {
            if (sessions.containsKey(id) || sessions.size >= 3) return@post
            ensureTrack(ctx, f)
            val ref = CameraLink.path(ctx, "rtc/$id") ?: return@post
            val pc = f.createPeerConnection(Rtc.config(servers), object : Rtc.PcObserver() {
                override fun onIceCandidate(c: IceCandidate) { ref.child("c").push().setValue(LiveNet.seal(ctx, Rtc.candJson(c))) }
                override fun onIceConnectionChange(st: PeerConnection.IceConnectionState?) {
                    if (st == PeerConnection.IceConnectionState.FAILED || st == PeerConnection.IceConnectionState.CLOSED) main.post { close(ctx, id) }
                }
            }) ?: return@post
            pc.addTransceiver(track!!, RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY))
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
            pc.setRemoteDescription(object : Rtc.Sdp() {
                override fun onSetSuccess() {
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
                private val failed: () -> Unit) {
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
            val servers = Rtc.iceServers()
            main.post { if (!stopped && f != null) connect(f, servers) else if (f == null) failed() }
        }.start()
        main.postDelayed({ if (!stopped && pc?.iceConnectionState() != PeerConnection.IceConnectionState.CONNECTED &&
            pc?.iceConnectionState() != PeerConnection.IceConnectionState.COMPLETED) { stop(); failed() } }, 20_000)
    }

    private fun connect(f: PeerConnectionFactory, servers: List<PeerConnection.IceServer>) {
        val r = ref ?: run { failed(); return }
        r.onDisconnect().removeValue()
        val p = f.createPeerConnection(Rtc.config(servers), object : Rtc.PcObserver() {
            override fun onIceCandidate(c: IceCandidate) { ref?.child("v")?.push()?.setValue(LiveNet.seal(ctx, Rtc.candJson(c))) }
            override fun onTrack(t: RtpTransceiver?) { (t?.receiver?.track() as? VideoTrack)?.let { v -> main.post { if (!stopped) v.addSink(renderTo) } } }
            override fun onIceConnectionChange(st: PeerConnection.IceConnectionState?) {
                if (st == PeerConnection.IceConnectionState.FAILED) main.post { if (!stopped) { stop(); failed() } }
            }
        }) ?: run { failed(); return }
        pc = p
        p.addTransceiver(org.webrtc.MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO, RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY))
        p.createOffer(Rtc.Sdp { d ->
            d ?: return@Sdp
            main.post {
                if (stopped) return@post
                p.setLocalDescription(Rtc.Sdp(), d)
                val sealed = LiveNet.seal(ctx, JSONObject().put("sdp", d.description).put("by", Device.id(ctx)).put("byName", Device.name(ctx))
                    .put("at", System.currentTimeMillis()).toString())
                val offer = HashMap<String, Any>(); offer["d"] = sealed
                pin?.let { offer["sig"] = CameraLink.sign(cam, it, sealed) }
                r.child("offer").setValue(offer)
                answerL = object : ValueEventListener {
                    override fun onDataChange(s: DataSnapshot) {
                        val sdp = LiveNet.open(ctx, s.child("d").value as? String) ?: return
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
            }
        }, MediaConstraints())
    }

    fun stop() {
        if (stopped) return
        stopped = true
        val r = ref
        answerL?.let { r?.child("answer")?.removeEventListener(it) }
        candL?.let { r?.child("c")?.removeEventListener(it) }
        try { pc?.dispose() } catch (_: Exception) {}
        pc = null
        r?.removeValue()
    }
}
