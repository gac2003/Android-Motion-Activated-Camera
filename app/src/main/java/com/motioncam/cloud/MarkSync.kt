package com.motioncam.cloud

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.HandlerThread
import com.motioncam.ClipStore
import com.motioncam.Collections
import com.motioncam.remote.RemoteRepo
import com.motioncam.remote.RemoteSources
import org.json.JSONObject
import java.io.File

/**
 * Keeps this device's protection marks in the cloud in line with what it wants kept:
 *  - on the camera phone: clips in the backup destination's protected collections (Favourites by default)
 *    plus clips protected by hand;
 *  - on a phone viewing a remote folder: clips in any of its collections for that folder (Favourites too)
 *    plus clips protected by hand.
 * Anyone can remove a protection ("override"). An override wins over collections that already held the clip;
 * adding the clip to a collection (or protecting it by hand) again afterwards protects it again.
 *
 * A "space" is a folder this device marks in: "b:<kind>" = its own backup destination, "r:<id>" = a remote folder.
 */
object MarkSync {
    fun backupKey(kind: CloudKind) = "b:${kind.key}"
    fun remoteKey(sourceId: String) = "r:$sourceId"

    /** Backup destinations that are set up (marks only matter where clips are backed up). */
    fun localSpaces(ctx: Context): List<String> = CloudKind.values()
        .filter { CloudConfig(ctx, it).let { c -> c.isConfigured && c.folderId != null } }.map { backupKey(it) }

    fun label(ctx: Context, space: String): String = when {
        space.startsWith("b:") -> CloudKind.of(space.substring(2))?.label ?: space
        else -> RemoteSources.get(ctx, space.substring(2))?.name ?: "Remote folder"
    }

    // ------------------------------------------------------------------ local records (per space)
    // manual: clips protected by hand on this device; intent: last time this device asked for protection
    // (by hand or by adding to a collection), to tell a newer request from one an override already answered.

    private fun file(ctx: Context) = File(ctx.filesDir, "marks_local.json")
    private var local: JSONObject? = null

    @Synchronized
    private fun local(ctx: Context): JSONObject {
        local?.let { return it }
        val o = try { JSONObject(file(ctx).readText()) } catch (e: Exception) { JSONObject() }
        local = o
        return o
    }

    @Synchronized
    private fun section(ctx: Context, space: String, name: String): JSONObject {
        val s = local(ctx).optJSONObject(space) ?: JSONObject().also { local(ctx).put(space, it) }
        return s.optJSONObject(name) ?: JSONObject().also { s.put(name, it) }
    }

    @Synchronized
    private fun saveLocal(ctx: Context) {
        try { val t = File(file(ctx).path + ".tmp"); t.writeText(local(ctx).toString()); t.renameTo(file(ctx)) } catch (_: Exception) {}
    }

    @Synchronized
    fun manual(ctx: Context, space: String): Set<String> = section(ctx, space, "manual").keys().asSequence().toSet()

    /** The user asked to keep these clips (added to a collection, or protected by hand). */
    @Synchronized
    fun intent(ctx: Context, space: String, names: Collection<String>, manual: Boolean = false) {
        val now = System.currentTimeMillis()
        val i = section(ctx, space, "intent")
        val m = section(ctx, space, "manual")
        for (n in names) { i.put(n, now); if (manual) m.put(n, now) }
        saveLocal(ctx)
        schedule(ctx, space)
    }

    /** Removes this device's own "protected by hand" (collections are unaffected). */
    @Synchronized
    fun unprotectManual(ctx: Context, space: String, names: Collection<String>) {
        val m = section(ctx, space, "manual")
        for (n in names) m.remove(n)
        saveLocal(ctx)
        schedule(ctx, space)
    }

    @Synchronized
    private fun intentAt(ctx: Context, space: String, name: String) = section(ctx, space, "intent").optLong(name, 0)

    /** Clips kept in the cloud for an older reason (migration): protected until someone removes it. */
    @Synchronized
    fun seedSticky(ctx: Context, space: String, names: Collection<String>, reason: String) {
        val st = section(ctx, space, "sticky")
        for (n in names) if (!st.has(n)) st.put(n, reason)
        saveLocal(ctx)
    }

    /** A clip added to a collection: record the request in the spaces that collection protects. */
    fun onCollectionAdd(ctx: Context, remoteId: String?, collectionId: String, clipIds: Collection<Long>) {
        val app = ctx.applicationContext
        Thread {
            try {
                if (remoteId != null) {
                    RemoteSources.get(app, remoteId)?.let { RemoteRepo.cached(app, it) }
                    intent(app, remoteKey(remoteId), clipIds.mapNotNull { RemoteRepo.entry(it)?.name })
                } else {
                    val names = clipIds.mapNotNull { ClipStore.get(app, it)?.name }
                    for (k in CloudKind.values()) {
                        if (collectionId in CloudConfig(app, k).protectedCollections) intent(app, backupKey(k), names)
                    }
                }
            } catch (_: Exception) {}
        }.start()
    }

    /** Which clips this device wants kept in [space], and why (manual, collection names). */
    private fun desired(ctx: Context, space: String): Map<String, Pair<Boolean, List<String>>> {
        val out = HashMap<String, Pair<Boolean, MutableList<String>>>()
        fun add(name: String, manual: Boolean, col: String?) {
            val cur = out.getOrPut(name) { Pair(false, ArrayList()) }
            out[name] = Pair(cur.first || manual, cur.second.also { if (col != null && col !in it) it += col })
        }
        for (n in manual(ctx, space)) add(n, true, null)
        if (space.startsWith("b:")) {
            val kind = CloudKind.of(space.substring(2)) ?: return emptyMap()
            val protect = CloudConfig(ctx, kind).protectedCollections
            val cols = Collections.all(ctx).filter { it.id in protect && it.clipIds.isNotEmpty() }
            val local = ClipStore.list(ctx)
            val names = local.associate { it.id to it.name }
            val onPhone = local.map { it.name }.toHashSet()
            for (c in cols) for (id in c.clipIds) names[id]?.let { add(it, false, c.name) }
            // a collection clip whose phone copy is gone (e.g. phone clean-up) stays protected in the cloud, as before:
            // remembered here until someone removes the protection
            synchronized(this) {
                val sticky = section(ctx, space, "sticky")
                for ((n, why) in out) if (why.second.isNotEmpty()) sticky.put(n, why.second.joinToString(MarkStore.SEP.toString()))
                for (n in sticky.keys().asSequence().toList()) {
                    if (n in onPhone) { if (out[n]?.second.isNullOrEmpty()) sticky.remove(n) }   // left its collections
                    else for (c in sticky.optString(n).split(MarkStore.SEP)) if (c.isNotEmpty()) add(n, false, c)
                }
                saveLocal(ctx)
            }
        } else {
            val id = space.substring(2)
            val src = RemoteSources.get(ctx, id) ?: return emptyMap()
            RemoteRepo.cached(ctx, src)
            for (c in Collections.forRemote(id).all(ctx)) for (cid in c.clipIds) RemoteRepo.entry(cid)?.name?.let { add(it, false, c.name) }
        }
        return out
    }

    // ------------------------------------------------------------------ syncing

    class Result(val marks: Map<String, ClipMarks>, val existing: Set<String>,
                 /** Clips this device wants kept but someone removed the protection after it was asked for. */
                 val overridden: Set<String>, val writable: Boolean, val at: Long = System.currentTimeMillis())

    private val cache = HashMap<String, Result>()

    /** Last known marks of a space (no network). */
    @Synchronized
    fun cached(space: String): Result? = cache[space]

    /** Clip names protected in any of [spaces] according to the last sync. */
    fun protectedNames(spaces: Collection<String>): Set<String> =
        spaces.mapNotNull { cached(it) }.flatMap { r -> r.marks.values.filter { it.protected }.map { it.clip } }.toSet()

    /**
     * Brings this device's marks in [space] up to date and returns everyone's marks (network; blocking).
     * [existing] = clip names in the folder (only clips that exist are marked).
     */
    fun sync(ctx: Context, space: String, store: MarkStore, existing: Set<String>): Result {
        val me = Device.id(ctx)
        val myName = Device.name(ctx)
        val marks = store.load().toMutableMap()
        val overridden = HashSet<String>()
        if (store.writable) {
            val want = desired(ctx, space).filterKeys { it in existing }
            val now = System.currentTimeMillis()
            for ((name, why) in want) {
                val cm = marks[name]
                val asked = intentAt(ctx, space, name)
                if (cm != null && cm.overrideAt > 0) {
                    if (cm.overrideAt >= asked) { overridden += name; continue }   // removed after we asked: respect it
                    store.clearOverride(name)                                         // asked again after the override
                    cm.overrideAt = 0; cm.overrideBy = null
                }
                val mine = cm?.marks?.firstOrNull { it.device == me }
                val m = Mark(me, myName, why.first, why.second, now)
                if (!m.sameReasons(mine) || mine?.deviceName != myName) {
                    store.put(name, me, m)
                    val c = marks.getOrPut(name) { ClipMarks(name) }
                    c.marks.removeAll { it.device == me }
                    c.marks += m
                }
            }
            for (cm in marks.values) {
                if (cm.clip in want.keys && cm.clip !in overridden) continue
                if (cm.marks.any { it.device == me }) {
                    store.put(cm.clip, me, null)
                    cm.marks.removeAll { it.device == me }
                }
            }
        }
        val r = Result(marks.filterValues { it.protected || it.overrideAt > 0 }, existing, overridden, store.writable)
        synchronized(this) { cache[space] = r }
        return r
    }

    /** Opens the space's store and existing clip names, then syncs (network; blocking). */
    fun syncNow(ctx: Context, space: String): Result {
        if (space.startsWith("b:")) {
            val kind = CloudKind.of(space.substring(2)) ?: throw CloudException("Unknown destination", CloudException.Kind.OTHER)
            val cfg = CloudConfig(ctx, kind)
            val folder = cfg.folderId ?: return Result(emptyMap(), emptySet(), emptySet(), true)
            return CloudSync.providerFor(ctx, kind).use { p ->
                sync(ctx, space, MarkStore.forProvider(p, folder), p.list(folder).filter { isOurClip(it.name) }.map { it.name }.toSet())
            }
        }
        val src = RemoteSources.get(ctx, space.substring(2)) ?: throw CloudException("Folder removed", CloudException.Kind.NOT_FOUND)
        val existing = RemoteRepo.cached(ctx, src).map { it.name }.toSet()
        return RemoteSources.reader(ctx, src).use { r -> sync(ctx, space, r.markStore(src.folderId), existing) }
    }

    /** Removes the protection of [names] for everyone (any device can). */
    fun override(ctx: Context, space: String, names: Collection<String>): Result {
        val by = Device.name(ctx)
        val now = System.currentTimeMillis()
        unprotectManualQuiet(ctx, space, names)
        val open: (MarkStore) -> Unit = { s -> for (n in names) s.override(n, by, now) }
        if (space.startsWith("b:")) {
            val kind = CloudKind.of(space.substring(2))!!
            val folder = CloudConfig(ctx, kind).folderId ?: return syncNow(ctx, space)
            CloudSync.providerFor(ctx, kind).use { p -> open(MarkStore.forProvider(p, folder)) }
        } else {
            val src = RemoteSources.get(ctx, space.substring(2)) ?: throw CloudException("Folder removed", CloudException.Kind.NOT_FOUND)
            RemoteSources.reader(ctx, src).use { r -> open(r.markStore(src.folderId)) }
        }
        return syncNow(ctx, space)
    }

    @Synchronized
    private fun unprotectManualQuiet(ctx: Context, space: String, names: Collection<String>) {
        val m = section(ctx, space, "manual")
        val st = section(ctx, space, "sticky")
        for (n in names) { m.remove(n); st.remove(n) }
        saveLocal(ctx)
    }

    // ------------------------------------------------------------------ background sync (debounced)

    private val handler by lazy { Handler(HandlerThread("marks").also { it.start() }.looper) }
    private val pending = HashSet<String>()
    /** The visible gallery redraws its badges after a background sync (called on the sync thread). */
    @Volatile var onSynced: ((String) -> Unit)? = null

    /** Syncs [space] in the background a moment from now (several changes in a row become one sync). */
    fun schedule(ctx: Context, space: String) {
        val app = ctx.applicationContext
        synchronized(pending) { if (!pending.add(space)) return }
        handler.postDelayed({
            synchronized(pending) { pending.remove(space) }
            if (!online(app)) return@postDelayed
            try {
                val r = syncNow(app, space)
                onSynced?.invoke(space)
                if (space.startsWith("r:") && r.overridden.isNotEmpty()) RemoteRepo.keepOverridden(app, space.substring(2), r.overridden)
            } catch (_: Exception) {}
        }, 2500)
    }

    /** All of this phone's backup destinations. */
    fun scheduleLocal(ctx: Context) { for (s in localSpaces(ctx)) schedule(ctx, s) }

    private fun online(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
