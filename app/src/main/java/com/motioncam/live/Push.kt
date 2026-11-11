package com.motioncam.live

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Base64
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.motioncam.cloud.Device
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Push alerts (Firebase Cloud Messaging): a camera tells your other phones the moment something happens, even with
 * MotionCam closed and without "Stay connected" or the 15-minute check.
 *
 * - Every phone that can receive registers its push address (token) in the network: devices/<phone>/push, sealed
 *   with the network key like everything else.
 * - A camera sends its alert-worthy events (motion, clip saved, too hot, charger unplugged, monitoring stopped)
 *   straight to those addresses through FCM's HTTP API, signed with a service account key that is only in your own
 *   build (MC_FCM_SERVICE_ACCOUNT, see SETUP.md). The event itself travels sealed: Google only sees which camera.
 * - The receiving phone opens it and alerts exactly as if it had been connected (its own alert settings, quiet
 *   hours and mutes apply), once: an event that also arrives over the live connection isn't alerted twice.
 *
 * Rate limits (per camera), so a busy scene can't flood your phones or the free quota: motion and clips at most every
 * 30 s each (a clip right after its motion alert always goes, to attach it), other kinds at most every 10 min each,
 * and never more than 120 pushes an hour. Newer pushes of a kind replace older undelivered ones (collapse key), and
 * motion pushes expire after an hour.
 */
object Push {
    private val main = Handler(Looper.getMainLooper())

    /** Firebase's sender id: the project number inside the app id ("1:<number>:android:…"). */
    private val senderId: String? get() = LiveConfig.APP_ID.split(":").getOrNull(1)?.takeIf { it.all(Char::isDigit) && it.isNotEmpty() }
    val canReceive: Boolean get() = LiveConfig.configured && senderId != null
    val canSend: Boolean get() = canReceive && com.motioncam.BuildConfig.FCM_SERVICE_ACCOUNT.isNotEmpty()

    private fun sp(ctx: Context) = ctx.getSharedPreferences("push", Context.MODE_PRIVATE)
    fun token(ctx: Context): String? = sp(ctx).getString("token", null)

    // ------------------------------------------------------------------ receiving phone: registration

    /** At app start: FCM needs Firebase's default app (the live connection uses its own, named one). */
    fun init(ctx: Context) {
        if (!canReceive) return
        try {
            if (FirebaseApp.getApps(ctx).none { it.name == FirebaseApp.DEFAULT_APP_NAME }) {
                FirebaseApp.initializeApp(ctx.applicationContext, FirebaseOptions.Builder()
                    .setApiKey(LiveConfig.API_KEY).setApplicationId(LiveConfig.APP_ID).setProjectId(LiveConfig.PROJECT_ID)
                    .setDatabaseUrl(LiveConfig.DB_URL).setGcmSenderId(senderId!!).build())
            }
        } catch (_: Exception) { return }
        if (!LiveNet.ready(ctx)) return
        try {
            FirebaseMessaging.getInstance().token
                .addOnSuccessListener { t -> if (!t.isNullOrEmpty()) saveToken(ctx, t) }
        } catch (_: Exception) {}
    }

    /** A new push address: kept, and published for the cameras. */
    fun saveToken(ctx: Context, t: String) {
        if (token(ctx) == t) { publish(ctx); return }
        sp(ctx).edit().putString("token", t).apply()
        publish(ctx)
        AlertCheckJob.schedule(ctx)
    }

    /** devices/<me>/push (when connected; [Live.refresh] publishes it on every connection too). */
    fun publish(ctx: Context) {
        val t = token(ctx) ?: return
        try { Live.ref(ctx, "devices/${Device.id(ctx)}/push")?.setValue(LiveNet.seal(ctx, t)) } catch (_: Exception) {}
    }

    /** Every camera in the network pushes, and this phone can receive: the 15-minute check isn't needed. */
    fun covers(ctx: Context): Boolean = token(ctx) != null && sp(ctx).getBoolean("covered", false)

    fun setCovered(ctx: Context, on: Boolean) {
        if (sp(ctx).getBoolean("covered", false) == on) return
        sp(ctx).edit().putBoolean("covered", on).apply()
        AlertCheckJob.schedule(ctx)
    }

    // ------------------------------------------------------------------ receiving phone: a push arrived

    fun received(ctx: Context, data: Map<String, String>) {
        if (data["x"] == "start") { remoteStartReceived(ctx, data); return }
        val cam = data["c"] ?: return
        if (cam == Device.id(ctx)) return
        val d = LiveNet.open(ctx, data["e"])?.let { try { JSONObject(it) } catch (_: Exception) { null } } ?: return   // not our network
        val t = data["t"]?.toLongOrNull() ?: return
        val key = data["k"]
        Live.phones.getOrPut(cam) { Live.Phone(cam) }.let { if (it.name.isEmpty()) it.name = data["n"] ?: "" }
        val type = d.optString("type")
        if (type in ActivityTypes.OVERVIEW && type != "clip") AlertInbox.noteEvent(ctx, cam, t - Live.serverOffset)
        Live.markHandled(ctx, cam, t)
        // the clip's picture comes over the live connection: online for a moment, offline again unless something uses it
        try { LiveNet.db(ctx)?.goOnline() } catch (_: Exception) {}
        main.postDelayed({ if (!Live.viewerActive && !Live.cameraActive) try { LiveNet.db(ctx)?.goOffline() } catch (_: Exception) {} }, 30_000)
        main.post { Alerts.onEvent(ctx, cam, t, d, key) }
    }

    // ------------------------------------------------------------------ remote start (a failsafe for a camera that isn't running)

    /** This phone can be started by push: allowed here, a PIN, the overlay permission, and a push address. */
    fun remoteStartReady(ctx: Context): Boolean {
        val p = com.motioncam.Prefs(ctx)
        return p.liveCamera && p.liveRemoteStart && canReceive && token(ctx) != null && CameraLink.hasPin(ctx) &&
            android.provider.Settings.canDrawOverlays(ctx)
    }

    /** Another phone asks this camera to start monitoring (signed with its PIN; at most 10 minutes old; once). */
    private fun remoteStartReceived(ctx: Context, data: Map<String, String>) {
        if (data["to"] != Device.id(ctx)) return
        val sealed = data["e"] ?: return
        val d = LiveNet.open(ctx, sealed)?.let { try { JSONObject(it) } catch (_: Exception) { null } } ?: return
        val who = d.optString("byName").ifEmpty { "another phone" }
        fun refuse(why: String) = com.motioncam.Health.log(ctx, "Remote start by $who refused: $why")
        if (!remoteStartReady(ctx)) { refuse("Remote start is off (or not fully set up) on this phone"); return }
        if (!CameraLink.verifyStart(ctx, sealed, data["s"])) { refuse("wrong PIN"); return }
        val at = d.optLong("at")
        if (Math.abs(System.currentTimeMillis() - at) > 10 * 60_000L) { refuse("the request is too old"); return }
        if (at <= sp(ctx).getLong("startAt", 0L)) return   // already done
        sp(ctx).edit().putLong("startAt", at).apply()
        if (com.motioncam.CameraService.monitoring) { com.motioncam.Health.log(ctx, "Remote start by $who: already monitoring"); return }
        com.motioncam.Health.log(ctx, "Remote start by $who")
        main.post {
            try {
                ctx.startActivity(Intent(ctx, com.motioncam.RemoteStartActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION).putExtra("by", who))
            } catch (e: Exception) { refuse("Android didn't let MotionCam open (${e.message})") }
        }
    }

    /**
     * Asks camera [cam] (push address [token]) to start, signed with its [pin]. [done] on the main thread: whether
     * FCM accepted it (delivery to the phone follows, usually within seconds).
     */
    fun sendStart(ctx: Context, cam: String, token: String, pin: String, done: (Boolean) -> Unit) {
        val app = ctx.applicationContext
        val payload = JSONObject().put("type", "start").put("by", Device.id(app)).put("byName", Device.name(app)).put("at", System.currentTimeMillis())
        val sealed = LiveNet.seal(app, payload.toString())
        val data = JSONObject().put("x", "start").put("to", cam).put("e", sealed).put("s", CameraLink.sign(cam, pin, sealed))
        Thread {
            val ok = try {
                val access = accessToken() ?: throw IllegalStateException("no access")
                post(access, JSONObject().put("message", JSONObject().put("token", token).put("data", data)
                    .put("android", JSONObject().put("priority", "HIGH").put("ttl", "600s")))) == 200
            } catch (_: Exception) { false }
            main.post { done(ok) }
        }.start()
    }

    private fun post(access: String, msg: JSONObject): Int {
        val c = URL("https://fcm.googleapis.com/v1/projects/${LiveConfig.PROJECT_ID}/messages:send").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 10_000; c.readTimeout = 15_000
        c.setRequestProperty("Authorization", "Bearer $access")
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        c.outputStream.use { it.write(msg.toString().toByteArray()) }
        val code = c.responseCode
        c.disconnect()
        return code
    }

    // ------------------------------------------------------------------ camera: sending

    private val PUSHED = setOf("motion", "clip", "hot", "unplugged", "monitor_off", "monitor_paused", "safety_stop")
    private val lastOf = HashMap<String, Long>()
    private var motionSince = false
    private val sent = ArrayDeque<Long>()

    /** Is a push for [type] due now? Updates the limits when it is. */
    @Synchronized
    private fun allow(type: String, now: Long): Boolean {
        while (sent.isNotEmpty() && now - sent.first() > 3_600_000L) sent.removeFirst()
        if (sent.size >= 120) return false
        val last = lastOf[type] ?: 0L
        val ok = when (type) {
            "motion" -> now - last >= 30_000L
            "clip" -> motionSince || now - last >= 30_000L
            else -> now - last >= 10 * 60_000L
        }
        if (!ok) return false
        lastOf[type] = now
        motionSince = type == "motion"
        sent.addLast(now)
        return true
    }

    /** A camera event was written ([key], at server time [t]): pushed to the other phones when worth it. */
    fun onEvent(ctx: Context, type: String, d: JSONObject, key: String?, t: Long, to: List<String>? = null) {
        if (!canSend || type !in PUSHED) return
        val me = Device.id(ctx)
        val tokens = to ?: Live.phones.values.filter { it.id != me && !it.push.isNullOrEmpty() }.mapNotNull { it.push }.distinct()
        if (tokens.isEmpty() || !allow(type, System.currentTimeMillis())) return
        val app = ctx.applicationContext
        val sealed = LiveNet.seal(app, d.toString())
        val name = Device.name(app)
        Thread {
            val access = try { accessToken() } catch (_: Exception) { null } ?: return@Thread
            for (tok in tokens) try { send(access, tok, me, name, type, sealed, key, t) } catch (_: Exception) {}
        }.start()
    }

    private fun send(access: String, token: String, cam: String, name: String, type: String, sealed: String, key: String?, t: Long) {
        val data = JSONObject().put("c", cam).put("n", name).put("t", t.toString()).put("e", sealed)
        if (key != null) data.put("k", key)
        val msg = JSONObject().put("message", JSONObject()
            .put("token", token)
            .put("data", data)
            .put("android", JSONObject().put("priority", "HIGH")
                .put("ttl", if (type == "motion" || type == "clip") "3600s" else "86400s")
                .put("collapse_key", "$cam-$type")))
        val c = URL("https://fcm.googleapis.com/v1/projects/${LiveConfig.PROJECT_ID}/messages:send").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 10_000; c.readTimeout = 15_000
        c.setRequestProperty("Authorization", "Bearer $access")
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        c.outputStream.use { it.write(msg.toString().toByteArray()) }
        c.responseCode   // 404 UNREGISTERED: that phone's address is stale (it publishes a new one when it opens)
        c.disconnect()
    }

    // ---- the service account: a short-lived access token from a signed JWT (cached ~55 min)

    @Volatile private var access: Pair<String, Long>? = null

    @Synchronized
    private fun accessToken(): String? {
        access?.let { (tok, until) -> if (System.currentTimeMillis() < until) return tok }
        val sa = JSONObject(String(Base64.decode(com.motioncam.BuildConfig.FCM_SERVICE_ACCOUNT, Base64.DEFAULT)))
        val email = sa.getString("client_email")
        val pem = sa.getString("private_key").replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "").replace(Regex("\\s"), "")
        val key = java.security.KeyFactory.getInstance("RSA").generatePrivate(java.security.spec.PKCS8EncodedKeySpec(Base64.decode(pem, Base64.DEFAULT)))
        fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val now = System.currentTimeMillis() / 1000
        val head = b64("""{"alg":"RS256","typ":"JWT"}""".toByteArray())
        val claims = b64(JSONObject().put("iss", email).put("scope", "https://www.googleapis.com/auth/firebase.messaging")
            .put("aud", "https://oauth2.googleapis.com/token").put("iat", now).put("exp", now + 3600).toString().toByteArray())
        val sig = java.security.Signature.getInstance("SHA256withRSA").run { initSign(key); update("$head.$claims".toByteArray()); sign() }
        val jwt = "$head.$claims.${b64(sig)}"
        val c = URL("https://oauth2.googleapis.com/token").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 10_000; c.readTimeout = 15_000
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        c.outputStream.use { it.write(("grant_type=" + java.net.URLEncoder.encode("urn:ietf:params:oauth:grant-type:jwt-bearer", "UTF-8") + "&assertion=" + jwt).toByteArray()) }
        if (c.responseCode != 200) { c.disconnect(); return null }
        val r = JSONObject(c.inputStream.bufferedReader().readText())
        c.disconnect()
        val tok = r.getString("access_token")
        access = tok to System.currentTimeMillis() + (r.optLong("expires_in", 3600) - 300) * 1000
        return tok
    }
}

/** Receives push alerts and new push addresses. */
class PushService : FirebaseMessagingService() {
    override fun onNewToken(token: String) { Push.saveToken(applicationContext, token) }
    override fun onMessageReceived(message: RemoteMessage) { Push.received(applicationContext, message.data) }
}
