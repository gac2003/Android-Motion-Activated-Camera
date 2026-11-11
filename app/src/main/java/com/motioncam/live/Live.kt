package com.motioncam.live

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import com.motioncam.Prefs
import com.motioncam.cloud.CloudConfig
import com.motioncam.cloud.CloudKind
import com.motioncam.cloud.Device
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Live connection over Firebase Realtime Database (a websocket kept by the Firebase SDK; only outgoing
 * connections, so it works behind captive / isolated networks).
 *
 * Layout under the network root (see [LiveNet.root]):
 *   devices/<device>            online, seen (server time), d = sealed {name, camera, viewer, sync…}
 *   cams/<device>/status        at, d = sealed status of a camera phone
 *   cams/<device>/caps          d = sealed camera capabilities (lenses, ranges)
 *   cams/<device>/events/<id>   t, d = sealed {type, text…}   (kept as "Keep activity" says, forever by default)
 *   cams/<device>/days/<yyyyMMdd>  per-day counts (m motion, o monitoring, b battery & heat, s settings, n entries)
 *   settings/keepDays           how long activity is kept (absent = forever)
 *   cams/<device>/watchers/<v>  time a viewer last asked for the live view
 *   cams/<device>/live          at, img = sealed JPEG (low rate, only while watched)
 *   cams/<device>/snapReq/<id>, snap/<id>     high-quality frame on request
 *   cams/<device>/cmd/<id>, cmdResult/<id>     remote control (signed with the camera's PIN if it has one)
 *   sync/<folder key>           at, by, what   "something changed in this folder: sync now"
 * The cloud folder stays the only source of truth for clips, collections and protections; the live
 * connection only carries signals, so it can never disagree with it.
 */
object Live {
    private val main = Handler(Looper.getMainLooper())

    fun enabled(ctx: Context) = Prefs(ctx).liveOn && LiveNet.ready(ctx)

    fun ref(ctx: Context, path: String): DatabaseReference? {
        val root = LiveNet.root(ctx) ?: return null
        return LiveNet.db(ctx)?.getReference("$root/$path")
    }

    /** Server clock (for "is this recent?" across phones). */
    @Volatile var serverOffset = 0L
    fun now() = System.currentTimeMillis() + serverOffset

    // ------------------------------------------------------------------ this phone's presence

    @Volatile var cameraActive = false
    @Volatile var viewerActive = false
    /**
     * Camera duty: this phone is monitoring as a camera and nobody is using it as a viewer (camera mode untouched for
     * a minute, or MotionCam in the background). It then stops following the other cameras (their status every minute,
     * their events, alerts about them), which keeps its radio and CPU quiet; touching the screen or opening another
     * tab follows them again within a second.
     */
    @Volatile var cameraDuty = false

    /** Following the other cameras (for screens and alerts). */
    private fun viewing() = viewerActive && !(cameraDuty && cameraActive)
    /** The 15-minute background check is connected (missed alerts then ring like live ones). */
    @Volatile var bgCheck = false
    /** The phone list has arrived since connecting, and no missed-alert read is still running (background check). */
    @Volatile var devicesLoaded = false
    val catchUpsRunning = java.util.concurrent.atomic.AtomicInteger(0)
    private var presenceFor: String? = null
    private var listening = false

    /** Registers this phone and keeps it marked online while connected (offline is set by the server on disconnect). */
    @Synchronized
    fun refresh(ctx: Context) {
        val app = ctx.applicationContext
        val want = enabled(app) && (cameraActive || viewerActive)
        val db = LiveNet.db(app)
        if (!want) {
            if (presenceFor != null) {
                try { ref(app, "devices/${Device.id(app)}")?.updateChildren(mapOf("online" to false, "seen" to ServerValue.TIMESTAMP)) } catch (_: Exception) {}
                stopListening(app)
                try { db?.goOffline() } catch (_: Exception) {}
                presenceFor = null
            }
            return
        }
        try { db?.goOnline() } catch (_: Exception) {}
        val root = LiveNet.root(app)
        if (!announce(app)) return
        presenceFor = root
        Push.publish(app)
        startListening(app)
        if (viewing()) watchKnown(app) else unwatchCameras(app)
    }

    /** Follows the camera phones already known (after camera duty ended). */
    private fun watchKnown(ctx: Context) {
        if (camListeners.isEmpty()) attachedAt = System.currentTimeMillis()
        val me = Device.id(ctx)
        for (p in phones.values.toList()) if (p.id != me && (p.camera || cams.containsKey(p.id))) watchCamera(ctx, p.id)
    }

    private fun unwatchCameras(ctx: Context) {
        for ((id, l) in camListeners) {
            ref(ctx, "cams/$id/status")?.removeEventListener(l.first)
            ref(ctx, "cams/$id/events")?.removeEventListener(l.second)
        }
        camListeners.clear()
    }

    /**
     * Marks this phone online, and asks the server to mark it offline if the connection drops. Done on every
     * (re)connection: after a dropped connection (Wi-Fi hiccup, network switch, the server recycling it) the server has
     * marked the phone offline, and the phone must say it's back, or it looks offline to the others until something
     * else (like opening MotionCam) announces it again.
     */
    private fun announce(app: Context): Boolean {
        val me = ref(app, "devices/${Device.id(app)}") ?: return false
        me.onDisconnect().updateChildren(mapOf("online" to false, "seen" to ServerValue.TIMESTAMP))
        me.updateChildren(mapOf("online" to true, "seen" to ServerValue.TIMESTAMP, "d" to LiveNet.seal(app, deviceInfo(app).toString())))
        return true
    }

    private var connListener: ValueEventListener? = null
    @Volatile private var wasConnected = false

    /** Connected again after a drop: online again, and a camera republishes its status (it may have changed meanwhile). */
    private fun onConnection(ctx: Context, connected: Boolean) {
        val again = connected && !wasConnected
        if (connected != wasConnected && cameraActive) com.motioncam.Health.log(ctx, if (connected) "Live connection back" else "Live connection lost")
        wasConnected = connected
        if (!again) return
        synchronized(this) {
            if (presenceFor == null) return
            try { announce(ctx); Push.publish(ctx) } catch (_: Exception) {}
        }
        if (cameraActive) try { CameraLink.publishStatus(ctx) } catch (_: Exception) {}
    }

    private fun deviceInfo(ctx: Context): JSONObject = JSONObject()
        .put("name", Device.name(ctx)).put("camera", cameraActive).put("viewer", viewerActive)
        .put("canCamera", Prefs(ctx).liveCamera)
        .put("pushes", Push.canSend)
        .put("remoteStart", Push.remoteStartReady(ctx))
        .put("app", try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName } catch (_: Exception) { "" })
        .put("sync", syncHealth(ctx))

    /** Unsent changes / conflicts per remote folder, so other phones can see where a change is stuck. */
    private fun syncHealth(ctx: Context): JSONObject {
        val o = JSONObject()
        for (s in com.motioncam.remote.RemoteSources.all(ctx)) {
            val st = com.motioncam.remote.SharedSync.state(ctx, s.id)
            o.put(folderKey(s.kind, s.folderId), JSONObject().put("pending", st.outbox.size).put("conflicts", st.conflicts.size)
                .put("last", st.lastSync).put("error", st.lastError ?: ""))
        }
        return o
    }

    /** Updates this phone's entry (e.g. after a sync). */
    fun publishDevice(ctx: Context) {
        if (presenceFor == null) return
        try { ref(ctx, "devices/${Device.id(ctx)}")?.updateChildren(mapOf("d" to LiveNet.seal(ctx, deviceInfo(ctx).toString()), "seen" to ServerValue.TIMESTAMP)) } catch (_: Exception) {}
    }

    // ------------------------------------------------------------------ what other phones see (state for screens + alerts)

    class Phone(val id: String, var name: String = "", var online: Boolean = false, var seen: Long = 0,
                var camera: Boolean = false, var viewer: Boolean = false, var sync: JSONObject = JSONObject(), var kicked: Boolean = false,
                /** Its push address (to alert it while MotionCam is closed), and whether it sends pushes as a camera. */
                var push: String? = null, var pushes: Boolean = false,
                /** It allows being started remotely by push (Remote start). */
                var remoteStart: Boolean = false)
    class Cam(val id: String) {
        var status: JSONObject? = null
        var statusAt = 0L
        var caps: JSONObject? = null
    }

    val phones = ConcurrentHashMap<String, Phone>()
    val cams = ConcurrentHashMap<String, Cam>()
    /** Screens refresh on changes (main thread). */
    val listeners = java.util.concurrent.CopyOnWriteArraySet<() -> Unit>()
    /** Fresh camera events (cam, type, data), for open camera screens (main thread). */
    val eventListeners = java.util.concurrent.CopyOnWriteArraySet<(String, String, JSONObject) -> Unit>()
    private fun changed() = main.post { for (l in listeners) try { l() } catch (_: Exception) {} }
    /** Something screens show changed outside the phone list (e.g. this camera started or stopped streaming). */
    fun notifyChanged() = changed()

    private var devListener: ValueEventListener? = null
    private var syncListener: ChildEventListener? = null
    private var offsetListener: ValueEventListener? = null
    private val camListeners = HashMap<String, Pair<ValueEventListener, ChildEventListener>>()
    private var attachedAt = 0L

    private fun startListening(ctx: Context) {
        if (listening) return
        listening = true
        devicesLoaded = false
        attachedAt = System.currentTimeMillis()
        offsetListener = object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) { serverOffset = (s.value as? Number)?.toLong() ?: 0L }
            override fun onCancelled(e: DatabaseError) {}
        }.also { LiveNet.db(ctx)?.getReference(".info/serverTimeOffset")?.addValueEventListener(it) }
        wasConnected = true   // just announced
        connListener = object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) { val c = s.value == true; main.post { onConnection(ctx, c) } }
            override fun onCancelled(e: DatabaseError) {}
        }.also { LiveNet.db(ctx)?.getReference(".info/connected")?.addValueEventListener(it) }
        devListener = object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                val seenIds = HashSet<String>()
                for (c in s.children) {
                    val id = c.key ?: continue
                    seenIds += id
                    val p = phones.getOrPut(id) { Phone(id) }
                    val wasOnline = p.online
                    p.online = c.child("online").value == true
                    p.seen = (c.child("seen").value as? Number)?.toLong() ?: 0
                    p.kicked = c.child("kicked").value == true
                    p.push = LiveNet.open(ctx, c.child("push").value as? String)
                    LiveNet.open(ctx, c.child("d").value as? String)?.let { d ->
                        try {
                            val o = JSONObject(d)
                            p.name = o.optString("name"); p.camera = o.optBoolean("camera"); p.viewer = o.optBoolean("viewer")
                            p.sync = o.optJSONObject("sync") ?: JSONObject()
                            p.pushes = o.optBoolean("pushes")
                            p.remoteStart = o.optBoolean("remoteStart")
                        } catch (_: Exception) {}
                    }
                    if (id == Device.id(ctx)) { if (p.kicked) main.post { removedByOtherPhone(ctx) }; continue }
                    if (p.camera || cams.containsKey(id)) watchCamera(ctx, id)
                    if (wasOnline != p.online) Alerts.onPresence(ctx, p)
                }
                phones.keys.retainAll(seenIds)
                // every camera alerts this phone by push: no need for the 15-minute check
                val me = Device.id(ctx)
                val camsNow = phones.values.filter { it.id != me && it.camera }
                Push.setCovered(ctx, camsNow.isNotEmpty() && camsNow.all { it.pushes })
                devicesLoaded = true
                changed()
            }
            override fun onCancelled(e: DatabaseError) {}
        }.also { ref(ctx, "devices")?.addValueEventListener(it) }
        syncListener = object : ChildEventListener {
            override fun onChildAdded(s: DataSnapshot, prev: String?) = onNudge(ctx, s)
            override fun onChildChanged(s: DataSnapshot, prev: String?) = onNudge(ctx, s)
            override fun onChildRemoved(s: DataSnapshot) {}
            override fun onChildMoved(s: DataSnapshot, prev: String?) {}
            override fun onCancelled(e: DatabaseError) {}
        }.also { ref(ctx, "sync")?.addChildEventListener(it) }
    }

    private fun stopListening(ctx: Context) {
        if (!listening) return
        listening = false
        devListener?.let { ref(ctx, "devices")?.removeEventListener(it) }
        syncListener?.let { ref(ctx, "sync")?.removeEventListener(it) }
        offsetListener?.let { LiveNet.db(ctx)?.getReference(".info/serverTimeOffset")?.removeEventListener(it) }
        connListener?.let { LiveNet.db(ctx)?.getReference(".info/connected")?.removeEventListener(it) }
        connListener = null
        for ((id, l) in camListeners) {
            ref(ctx, "cams/$id/status")?.removeEventListener(l.first)
            ref(ctx, "cams/$id/events")?.removeEventListener(l.second)
        }
        camListeners.clear()
    }

    /** Follows a camera phone's status and new events (for screens and alerts). */
    private fun watchCamera(ctx: Context, id: String) {
        if (camListeners.containsKey(id) || !viewing()) return
        val cam = cams.getOrPut(id) { Cam(id) }
        val st = object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                cam.statusAt = (s.child("at").value as? Number)?.toLong() ?: 0
                val prev = cam.status
                cam.status = LiveNet.open(ctx, s.child("d").value as? String)?.let { try { JSONObject(it) } catch (_: Exception) { null } }
                cam.caps = LiveNet.open(ctx, s.child("caps").value as? String)?.let { try { JSONObject(it) } catch (_: Exception) { null } } ?: cam.caps
                cam.status?.let { Alerts.onStatus(ctx, id, prev, it) }
                changed()
            }
            override fun onCancelled(e: DatabaseError) {}
        }
        val ev = object : ChildEventListener {
            override fun onChildAdded(s: DataSnapshot, prev: String?) {
                val t = (s.child("t").value as? Number)?.toLong() ?: return
                val d0 = LiveNet.open(ctx, s.child("d").value as? String) ?: return
                // new in Activity (the dot on the Live connection icon), whatever this phone's alert settings are
                try { JSONObject(d0).optString("type").let { ty -> if (ty in ActivityTypes.OVERVIEW && ty != "clip") AlertInbox.noteEvent(ctx, id, t - serverOffset) } } catch (_: Exception) {}
                if (t < attachedAt - 60_000 + serverOffset) return     // older events: only shown in Activity, not alerted
                val d = d0
                try {
                    val o = JSONObject(d)
                    Alerts.onEvent(ctx, id, t, o, s.key)
                    main.post { for (l in eventListeners) try { l(id, o.optString("type"), o) } catch (_: Exception) {} }
                } catch (_: Exception) {}
                markSeen(ctx, id, t)
            }
            override fun onChildChanged(s: DataSnapshot, prev: String?) {}
            override fun onChildRemoved(s: DataSnapshot) {}
            override fun onChildMoved(s: DataSnapshot, prev: String?) {}
            override fun onCancelled(e: DatabaseError) {}
        }
        ref(ctx, "cams/$id/status")?.addValueEventListener(st)
        ref(ctx, "cams/$id/events")?.orderByChild("t")?.limitToLast(1)?.addChildEventListener(ev)
        camListeners[id] = Pair(st, ev)
        catchUp(ctx, id, attachedAt - 60_000 + serverOffset)
    }

    // ------------------------------------------------------------------ alerts missed while not connected

    private fun seen(ctx: Context) = ctx.getSharedPreferences("alert_seen", Context.MODE_PRIVATE)

    /** Remembers the newest camera event this phone has handled (server time). */
    /** An event of [cam] was alerted another way (a push): catching up later doesn't alert it again. */
    fun markHandled(ctx: Context, cam: String, t: Long) = markSeen(ctx, cam, t)

    private fun markSeen(ctx: Context, cam: String, t: Long) {
        val sp = seen(ctx)
        if (t > sp.getLong(cam, 0)) sp.edit().putLong(cam, t).apply()
    }

    /**
     * Reads the events a camera recorded since this phone last handled one (up to [until], where live alerts take over)
     * and alerts / marks them new in Activity. The first time, nothing old is shown.
     */
    private fun catchUp(ctx: Context, cam: String, until: Long) {
        val last = seen(ctx).getLong(cam, 0)
        if (last == 0L) { markSeen(ctx, cam, until); return }
        if (last >= until) return
        val q = ref(ctx, "cams/$cam/events")?.orderByChild("t")?.startAt((last + 1).toDouble())?.endAt((until - 1).toDouble()) ?: return
        catchUpsRunning.incrementAndGet()
        q.addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(s: DataSnapshot) {
                    try { read(s) } finally { catchUpsRunning.decrementAndGet() }
                }
                override fun onCancelled(e: DatabaseError) { catchUpsRunning.decrementAndGet() }
                private fun read(s: DataSnapshot) {
                    val list = s.children.mapNotNull { c ->
                        val t = (c.child("t").value as? Number)?.toLong() ?: return@mapNotNull null
                        val d = LiveNet.open(ctx, c.child("d").value as? String) ?: return@mapNotNull null
                        try { t to JSONObject(d) } catch (_: Exception) { null }
                    }.sortedBy { it.first }
                    markSeen(ctx, cam, until - 1)
                    try { Alerts.catchUp(ctx, cam, list) } catch (_: Exception) {}
                }
            })
    }

    /** Another phone removed this one from the network. */
    private fun removedByOtherPhone(ctx: Context) {
        try { ref(ctx, "devices/${Device.id(ctx)}")?.removeValue() } catch (_: Exception) {}
        Prefs(ctx).liveOn = false
        CameraLink.stop(ctx)
        LiveNet.signOut(ctx)
        refresh(ctx)
        android.widget.Toast.makeText(ctx, "This phone was removed from the live connection by another phone", android.widget.Toast.LENGTH_LONG).show()
    }

    /** Removes another phone (it signs itself out when it next connects; with a network code, also change the code). */
    fun removePhone(ctx: Context, id: String) {
        ref(ctx, "devices/$id/kicked")?.setValue(true)
    }

    // ------------------------------------------------------------------ sync nudges

    /** Same key on every phone for the same cloud folder (hash, so folder ids are not stored in Firebase). */
    fun folderKey(kind: CloudKind, folderId: String) = LiveNet.sha256Hex("${kind.key}:$folderId").take(24)

    /** Tells the other phones that something changed in a folder ("collections", "marks", "clips"), so they sync now. */
    private val lastNudge = ConcurrentHashMap<String, Long>()

    fun nudge(ctx: Context, kind: CloudKind, folderId: String, what: String) {
        if (!enabled(ctx) || presenceFor == null) return
        // several changes in a row: one nudge (every phone syncs the whole folder anyway)
        val k = "${folderKey(kind, folderId)}:$what"
        val now = System.currentTimeMillis()
        if (now - (lastNudge[k] ?: 0) < 10_000) return
        lastNudge[k] = now
        try {
            ref(ctx, "sync/${folderKey(kind, folderId)}")?.setValue(mapOf("at" to ServerValue.TIMESTAMP, "by" to Device.id(ctx), "what" to what))
        } catch (_: Exception) {}
        publishDevice(ctx)
    }

    private fun onNudge(ctx: Context, s: DataSnapshot) {
        val key = s.key ?: return
        if (s.child("by").value == Device.id(ctx)) return
        val what = s.child("what").value as? String ?: ""
        // a phone viewing this folder: refresh what changed right away instead of on the next timer
        for (src in com.motioncam.remote.RemoteSources.all(ctx)) {
            if (folderKey(src.kind, src.folderId) != key) continue
            com.motioncam.remote.SharedSync.schedule(ctx, src.id, 0)
            if (what == "clips") Thread { try { com.motioncam.remote.RemoteRepo.refresh(ctx, src) } catch (_: Exception) {} }.start()
        }
        // the camera phone itself: protections / collections affect its clean-up
        for (k in com.motioncam.cloud.Dest.all(ctx)) {
            val f = CloudConfig(ctx, k).folderId ?: continue
            if (folderKey(k.kind, f) != key) continue
            if (what == "settings") { com.motioncam.cloud.SharedFolderSettings.changedElsewhere(ctx, k); continue }
            com.motioncam.cloud.MarkSync.schedule(ctx, com.motioncam.cloud.MarkSync.backupKey(k))
            // clips deleted from the cloud on another phone: forget them as "backed up" so they can be uploaded again
            if (what == "clips") Thread { try { com.motioncam.cloud.CloudSync.reconcile(ctx, k) } catch (_: Exception) {} }.start()
        }
    }
}
