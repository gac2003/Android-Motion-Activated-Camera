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
 *   cams/<device>/events/<id>   t, d = sealed {type, text…}   (kept 7 days / 500)
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
        val me = ref(app, "devices/${Device.id(app)}") ?: return
        val root = LiveNet.root(app)
        me.onDisconnect().updateChildren(mapOf("online" to false, "seen" to ServerValue.TIMESTAMP))
        me.updateChildren(mapOf("online" to true, "seen" to ServerValue.TIMESTAMP, "d" to LiveNet.seal(app, deviceInfo(app).toString())))
        presenceFor = root
        startListening(app)
    }

    private fun deviceInfo(ctx: Context): JSONObject = JSONObject()
        .put("name", Device.name(ctx)).put("camera", cameraActive).put("viewer", viewerActive)
        .put("canCamera", Prefs(ctx).liveCamera)
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
                var camera: Boolean = false, var viewer: Boolean = false, var sync: JSONObject = JSONObject(), var kicked: Boolean = false)
    class Cam(val id: String) {
        var status: JSONObject? = null
        var statusAt = 0L
        var caps: JSONObject? = null
    }

    val phones = ConcurrentHashMap<String, Phone>()
    val cams = ConcurrentHashMap<String, Cam>()
    /** Screens refresh on changes (main thread). */
    val listeners = java.util.concurrent.CopyOnWriteArraySet<() -> Unit>()
    private fun changed() = main.post { for (l in listeners) try { l() } catch (_: Exception) {} }

    private var devListener: ValueEventListener? = null
    private var syncListener: ChildEventListener? = null
    private var offsetListener: ValueEventListener? = null
    private val camListeners = HashMap<String, Pair<ValueEventListener, ChildEventListener>>()
    private var attachedAt = 0L

    private fun startListening(ctx: Context) {
        if (listening) return
        listening = true
        attachedAt = System.currentTimeMillis()
        offsetListener = object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) { serverOffset = (s.value as? Number)?.toLong() ?: 0L }
            override fun onCancelled(e: DatabaseError) {}
        }.also { LiveNet.db(ctx)?.getReference(".info/serverTimeOffset")?.addValueEventListener(it) }
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
                    LiveNet.open(ctx, c.child("d").value as? String)?.let { d ->
                        try {
                            val o = JSONObject(d)
                            p.name = o.optString("name"); p.camera = o.optBoolean("camera"); p.viewer = o.optBoolean("viewer")
                            p.sync = o.optJSONObject("sync") ?: JSONObject()
                        } catch (_: Exception) {}
                    }
                    if (id == Device.id(ctx)) { if (p.kicked) main.post { removedByOtherPhone(ctx) }; continue }
                    if (p.camera || cams.containsKey(id)) watchCamera(ctx, id)
                    if (wasOnline != p.online) Alerts.onPresence(ctx, p)
                }
                phones.keys.retainAll(seenIds)
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
        for ((id, l) in camListeners) {
            ref(ctx, "cams/$id/status")?.removeEventListener(l.first)
            ref(ctx, "cams/$id/events")?.removeEventListener(l.second)
        }
        camListeners.clear()
    }

    /** Follows a camera phone's status and new events (for screens and alerts). */
    private fun watchCamera(ctx: Context, id: String) {
        if (camListeners.containsKey(id) || !viewerActive) return
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
                if (t < attachedAt - 60_000 + serverOffset) return     // older events: only shown in Activity, not alerted
                val d = LiveNet.open(ctx, s.child("d").value as? String) ?: return
                try { Alerts.onEvent(ctx, id, t, JSONObject(d)) } catch (_: Exception) {}
            }
            override fun onChildChanged(s: DataSnapshot, prev: String?) {}
            override fun onChildRemoved(s: DataSnapshot) {}
            override fun onChildMoved(s: DataSnapshot, prev: String?) {}
            override fun onCancelled(e: DatabaseError) {}
        }
        ref(ctx, "cams/$id/status")?.addValueEventListener(st)
        ref(ctx, "cams/$id/events")?.orderByChild("t")?.limitToLast(1)?.addChildEventListener(ev)
        camListeners[id] = Pair(st, ev)
    }

    /** Another phone removed this one from the network. */
    private fun removedByOtherPhone(ctx: Context) {
        try { ref(ctx, "devices/${Device.id(ctx)}")?.removeValue() } catch (_: Exception) {}
        Prefs(ctx).liveOn = false
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
        for (k in CloudKind.values()) {
            val f = CloudConfig(ctx, k).folderId ?: continue
            if (folderKey(k, f) != key) continue
            com.motioncam.cloud.MarkSync.schedule(ctx, com.motioncam.cloud.MarkSync.backupKey(k))
            // clips deleted from the cloud on another phone: forget them as "backed up" so they can be uploaded again
            if (what == "clips") Thread { try { com.motioncam.cloud.CloudSync.reconcile(ctx, k) } catch (_: Exception) {} }.start()
        }
    }
}
