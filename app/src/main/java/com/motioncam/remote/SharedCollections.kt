package com.motioncam.remote

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import com.motioncam.ClipCollection
import com.motioncam.CollectionStore
import com.motioncam.cloud.CloudException
import com.motioncam.cloud.CollectionFiles
import com.motioncam.cloud.Device
import com.motioncam.cloud.Mark
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Shared collections of a remote folder: the same collections on every phone signed in to the account, stored in
 * the folder (see [CollectionFiles]).
 *
 * How it stays in sync:
 *  - Every change is applied on this phone at once and queued as an "operation" (outbox, saved to disk). A sync
 *    sends the queued operations, then reads the cloud again. Operations are safe to repeat, so a sync cut off
 *    half-way just continues next time.
 *  - What is shown = the last cloud state + the queued operations.
 *  - Different phones' changes merge by themselves (separate files per entry). Where intent really clashes
 *    (renamed on two phones, deleted on one while added to on another, the same name created twice, an operation
 *    that keeps failing) the operation is held and a conflict is shown, for the user to decide (Sync status).
 *  - Clips missing from the folder are shown as missing; their entries are removed automatically only after 7 days
 *    and 3 successful listings without them (one bad listing never empties a collection), or right away by the user.
 */
object SharedSync {
    const val MISSING_DAYS = 7
    private const val MISSING_CHECKS = 3

    // ------------------------------------------------------------------ state (per remote folder)

    class EntryS(val clip: String, val file: String, val device: String, val at: Long, val deviceName: String)
    class ColS(val id: String, var name: String, var nameAt: Long, var created: Long, var deletedAt: Long = 0, var deletedBy: String? = null) {
        val entries = ArrayList<EntryS>()
    }

    class Op(val id: String, val type: String, var col: String, val o: JSONObject) {
        var held: Boolean get() = o.optBoolean("held"); set(v) { o.put("held", v) }
        var fails: Int get() = o.optInt("fails"); set(v) { o.put("fails", v) }
        val at get() = o.optLong("at")
        fun json(): JSONObject = o.put("id", id).put("t", type).put("col", col)
    }

    class Conflict(val id: String, val kind: String, val col: String, val o: JSONObject) {
        fun json(): JSONObject = o.put("id", id).put("kind", kind).put("col", col)
    }

    class State {
        val cols = LinkedHashMap<String, ColS>()
        val outbox = ArrayList<Op>()
        val conflicts = ArrayList<Conflict>()
        val missing = HashMap<String, Pair<Long, Int>>()   // clip -> (missing since, listings without it)
        val dismissed = HashSet<String>()                  // duplicate-name conflicts the user chose to keep
        var lastSync = 0L
        var lastError: String? = null
        var migrated = false
        var loadedOnce = false
    }

    private val states = HashMap<String, State>()
    private fun file(ctx: Context, id: String) = File(RemoteRepo.dataDir(ctx, id), "shared_collections.json")

    @Synchronized
    fun state(ctx: Context, id: String): State = states.getOrPut(id) {
        val s = State()
        try {
            val f = file(ctx, id)
            if (f.exists()) {
                val o = JSONObject(f.readText())
                val cols = o.optJSONObject("cols") ?: JSONObject()
                for (k in cols.keys()) {
                    val c = cols.getJSONObject(k)
                    val cs = ColS(k, c.optString("name"), c.optLong("nameAt"), c.optLong("created"), c.optLong("deletedAt"), c.optString("deletedBy").ifEmpty { null })
                    val es = c.optJSONArray("entries") ?: JSONArray()
                    for (i in 0 until es.length()) es.getJSONObject(i).let {
                        cs.entries += EntryS(it.getString("clip"), it.getString("file"), it.optString("dev"), it.optLong("at"), it.optString("dn"))
                    }
                    s.cols[k] = cs
                }
                val ob = o.optJSONArray("outbox") ?: JSONArray()
                for (i in 0 until ob.length()) ob.getJSONObject(i).let { s.outbox += Op(it.getString("id"), it.getString("t"), it.getString("col"), it) }
                val cf = o.optJSONArray("conflicts") ?: JSONArray()
                for (i in 0 until cf.length()) cf.getJSONObject(i).let { s.conflicts += Conflict(it.getString("id"), it.getString("kind"), it.getString("col"), it) }
                val ms = o.optJSONObject("missing") ?: JSONObject()
                for (k in ms.keys()) ms.getJSONArray(k).let { s.missing[k] = Pair(it.getLong(0), it.getInt(1)) }
                val dm = o.optJSONArray("dismissed") ?: JSONArray()
                for (i in 0 until dm.length()) s.dismissed += dm.getString(i)
                s.lastSync = o.optLong("lastSync")
                s.lastError = o.optString("lastError").ifEmpty { null }
                s.migrated = o.optBoolean("migrated")
                s.loadedOnce = o.optBoolean("loadedOnce")
            }
        } catch (_: Exception) {}
        s
    }

    @Synchronized
    fun save(ctx: Context, id: String) {
        val s = state(ctx, id)
        val cols = JSONObject()
        for (c in s.cols.values) cols.put(c.id, JSONObject().put("name", c.name).put("nameAt", c.nameAt).put("created", c.created)
            .put("deletedAt", c.deletedAt).put("deletedBy", c.deletedBy ?: "")
            .put("entries", JSONArray().apply { for (e in c.entries) put(JSONObject().put("clip", e.clip).put("file", e.file).put("dev", e.device).put("at", e.at).put("dn", e.deviceName)) }))
        val o = JSONObject().put("cols", cols)
            .put("outbox", JSONArray().apply { s.outbox.forEach { put(it.json()) } })
            .put("conflicts", JSONArray().apply { s.conflicts.forEach { put(it.json()) } })
            .put("missing", JSONObject().apply { for ((k, v) in s.missing) put(k, JSONArray().put(v.first).put(v.second)) })
            .put("dismissed", JSONArray(s.dismissed.toList()))
            .put("lastSync", s.lastSync).put("lastError", s.lastError ?: "").put("migrated", s.migrated).put("loadedOnce", s.loadedOnce)
        try { val f = file(ctx, id); val t = File(f.path + ".tmp"); t.writeText(o.toString()); t.renameTo(f) } catch (_: Exception) {}
    }

    private fun newId(prefix: String) = prefix + System.currentTimeMillis().toString(36) + (Math.random() * 1e6).toLong().toString(36)

    // ------------------------------------------------------------------ the view (cloud state + queued changes)

    /** Collections as this phone shows them: last known cloud state with the queued changes applied. */
    @Synchronized
    fun view(ctx: Context, id: String): List<ColS> {
        val s = state(ctx, id)
        val v = LinkedHashMap<String, ColS>()
        for (c in s.cols.values) v[c.id] = ColS(c.id, c.name, c.nameAt, c.created, c.deletedAt, c.deletedBy).also { it.entries.addAll(c.entries) }
        if (CollectionFiles.FAVOURITES !in v) v[CollectionFiles.FAVOURITES] = ColS(CollectionFiles.FAVOURITES, "Favourites", 0, 0)
        for (op in s.outbox) apply(v, op)
        return v.values.filter { it.deletedAt == 0L }
            .sortedWith(compareBy({ it.id != CollectionFiles.FAVOURITES }, { it.created }))
    }

    private fun apply(v: MutableMap<String, ColS>, op: Op) {
        when (op.type) {
            "create" -> v.getOrPut(op.col) { ColS(op.col, op.o.optString("name"), op.at, op.at) }
            "rename" -> v[op.col]?.let { it.name = op.o.optString("name"); it.nameAt = op.at }
            "delete" -> v[op.col]?.deletedAt = op.at
            "add" -> {
                val c = v.getOrPut(op.col) { ColS(op.col, op.o.optString("name", "Collection"), op.at, op.at) }
                // an add to a collection deleted elsewhere is held in a conflict; still show it until decided
                if (op.held) c.deletedAt = 0
                c.entries += EntryS(op.o.getString("clip"), op.o.getString("entry"), op.o.optString("dev"), op.at, op.o.optString("dn"))
            }
            "remove" -> {
                val files = op.o.getJSONArray("entries").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
                v[op.col]?.entries?.removeAll { it.file in files }
            }
        }
    }

    // ------------------------------------------------------------------ local changes

    private fun enqueue(ctx: Context, id: String, type: String, col: String, o: JSONObject) {
        synchronized(this) {
            state(ctx, id).outbox += Op(newId("o"), type, col, o.put("at", o.optLong("at", System.currentTimeMillis())))
            save(ctx, id)
        }
        schedule(ctx, id)
    }

    fun create(ctx: Context, id: String, name: String): String {
        val col = newId("c")
        enqueue(ctx, id, "create", col, JSONObject().put("name", name.trim().ifEmpty { "Untitled" }))
        return col
    }

    fun rename(ctx: Context, id: String, col: String, name: String) {
        val base = view(ctx, id).firstOrNull { it.id == col }?.nameAt ?: 0
        enqueue(ctx, id, "rename", col, JSONObject().put("name", name.trim()).put("base", base))
    }

    fun delete(ctx: Context, id: String, col: String) {
        if (col == CollectionFiles.FAVOURITES) return
        enqueue(ctx, id, "delete", col, JSONObject())
    }

    fun add(ctx: Context, id: String, col: String, clips: Collection<String>) {
        val c = view(ctx, id).firstOrNull { it.id == col }
        val have = c?.entries?.map { it.clip }?.toSet() ?: emptySet()
        val dev = Device.id(ctx)
        val dn = Device.name(ctx)
        for (clip in clips.distinct()) {
            if (clip in have) continue
            val at = System.currentTimeMillis()
            val entry = CollectionFilesNames.entry(clip, dev, at, dn)
            enqueue(ctx, id, "add", col, JSONObject().put("clip", clip).put("entry", entry).put("dev", dev).put("dn", dn).put("at", at)
                .put("name", c?.name ?: "Collection"))
        }
    }

    /** Removes clips from a collection: exactly the entries this phone knows of (a simultaneous re-add survives). */
    fun remove(ctx: Context, id: String, col: String, clips: Collection<String>) {
        val set = clips.toSet()
        synchronized(this) {
            val s = state(ctx, id)
            // entries still waiting to be sent are simply dropped
            s.outbox.removeAll { it.type == "add" && it.col == col && it.o.optString("clip") in set && !it.held }
            save(ctx, id)
        }
        val known = state(ctx, id).cols[col]?.entries?.filter { it.clip in set }?.map { it.file } ?: emptyList()
        if (known.isNotEmpty()) enqueue(ctx, id, "remove", col, JSONObject().put("entries", JSONArray(known)))
        else schedule(ctx, id)
    }

    /** Removes clips from every collection (missing clips the user removes, or after 7 days). */
    fun removeEverywhere(ctx: Context, id: String, clips: Collection<String>) {
        for (c in view(ctx, id)) if (c.entries.any { it.clip in clips }) remove(ctx, id, c.id, clips)
        synchronized(this) { val s = state(ctx, id); for (c in clips) s.missing.remove(c); save(ctx, id) }
    }

    // ------------------------------------------------------------------ missing clips

    /** After a successful listing of the folder: track clips that are in collections but not in the folder. */
    fun noteListing(ctx: Context, id: String, existing: Set<String>) {
        val now = System.currentTimeMillis()
        val expired = ArrayList<String>()
        synchronized(this) {
            val s = state(ctx, id)
            val inCollections = view(ctx, id).flatMap { c -> c.entries.map { it.clip } }.toSet()
            for (clip in inCollections) {
                if (clip in existing) { s.missing.remove(clip); continue }
                val (since, seen) = s.missing[clip] ?: Pair(now, 0)
                s.missing[clip] = Pair(since, seen + 1)
                if (now - since >= MISSING_DAYS * 86_400_000L && seen + 1 >= MISSING_CHECKS) expired += clip
            }
            s.missing.keys.retainAll(inCollections)
            save(ctx, id)
        }
        if (expired.isNotEmpty()) removeEverywhere(ctx, id, expired)
    }

    fun missingSince(ctx: Context, id: String, clip: String): Long? = synchronized(this) { state(ctx, id).missing[clip]?.first }

    // ------------------------------------------------------------------ protection (read by MarkSync)

    /** "Kept because it is in a shared collection", per clip (newest entry per collection). */
    fun sharedMarks(cols: Collection<ColS>): Map<String, List<Mark>> {
        val out = HashMap<String, MutableList<Mark>>()
        for (c in cols) {
            if (c.deletedAt > 0) continue
            for ((clip, es) in c.entries.groupBy { it.clip }) {
                val e = es.maxByOrNull { it.at }!!
                out.getOrPut(clip) { ArrayList() } += Mark("shared:${c.id}", e.deviceName, false, listOf(c.name), e.at)
            }
        }
        return out
    }

    /** Clips in collections whose entries are not in the cloud yet (keep the old per-phone mark meanwhile). */
    fun pendingClips(ctx: Context, id: String): Map<String, List<String>> = synchronized(this) {
        val s = state(ctx, id)
        s.outbox.filter { it.type == "add" }.groupBy { it.o.optString("clip") }.mapValues { (_, ops) -> ops.map { it.o.optString("name", "Collection") } }
    }

    // ------------------------------------------------------------------ syncing

    private val handler by lazy { Handler(HandlerThread("shared-collections").also { it.start() }.looper) }
    private val pending = HashSet<String>()
    /** Visible screens refresh after a sync (called on the sync thread with the folder id). */
    val listeners = java.util.concurrent.CopyOnWriteArraySet<(String) -> Unit>()

    fun schedule(ctx: Context, id: String, delayMs: Long = 1500) {
        val app = ctx.applicationContext
        synchronized(pending) { if (!pending.add(id)) return }
        try {
            handler.postDelayed({
                synchronized(pending) { pending.remove(id) }
                try { syncNow(app, id) } catch (_: Exception) {}
            }, delayMs)
        } catch (_: Throwable) { synchronized(pending) { pending.remove(id) } }
    }

    /** Sends queued changes and reads the cloud (blocking; network). */
    fun syncNow(ctx: Context, id: String) {
        val src = RemoteSources.get(ctx, id) ?: return
        try {
            RemoteSources.reader(ctx, src).use { r ->
                val files = r.collectionFiles(src.folderId)
                migrate(ctx, id)
                var cloud = files.load()
                detectConflicts(ctx, id, cloud)
                if (files.writable && push(ctx, id, files)) cloud = files.load()
                synchronized(this) {
                    val s = state(ctx, id)
                    s.cols.clear()
                    for (c in cloud.values) {
                        val cs = ColS(c.id, c.name, c.names.maxOfOrNull { it.first } ?: 0, c.created, c.deletedAt, c.deletedBy)
                        c.entries.forEach { e -> cs.entries += EntryS(e.clip, e.file, e.device, e.at, e.deviceName) }
                        s.cols[c.id] = cs
                    }
                    detectDuplicates(s)
                    s.lastSync = System.currentTimeMillis()
                    s.lastError = null
                    s.loadedOnce = true
                    save(ctx, id)
                }
            }
        } catch (e: Exception) {
            synchronized(this) { state(ctx, id).lastError = e.message ?: e.javaClass.simpleName; save(ctx, id) }
        }
        com.motioncam.cloud.MarkSync.schedule(ctx, com.motioncam.cloud.MarkSync.remoteKey(id))
        for (l in listeners) try { l(id) } catch (_: Exception) {}
    }

    /** Holds queued changes that clash with what other phones did, and records a conflict for the user. */
    private fun detectConflicts(ctx: Context, id: String, cloud: Map<String, CollectionFiles.Col>) = synchronized(this) {
        val s = state(ctx, id)
        val me = Device.id(ctx)
        for (op in s.outbox) {
            if (op.held) continue
            val c = cloud[op.col]
            when (op.type) {
                "rename" -> {
                    val newest = c?.names?.maxByOrNull { it.first }
                    if (newest != null && newest.first > op.o.optLong("base") && newest.second != op.o.optString("name")) {
                        op.held = true
                        s.conflicts += Conflict(newId("k"), "rename", op.col, JSONObject().put("op", op.id)
                            .put("mine", op.o.optString("name")).put("theirs", newest.second).put("theirsAt", newest.first))
                    }
                }
                "add" -> if (c != null && c.deletedAt > 0) {
                    op.held = true
                    val k = s.conflicts.firstOrNull { it.kind == "deleted" && it.col == op.col }
                        ?: Conflict(newId("k"), "deleted", op.col, JSONObject().put("ops", JSONArray()).put("name", c.name)
                            .put("by", c.deletedBy ?: "another phone").put("at", c.deletedAt)).also { s.conflicts += it }
                    k.o.getJSONArray("ops").put(op.id)
                }
                "delete" -> {
                    val newer = c?.entries?.filter { it.at > op.at && it.device != me } ?: emptyList()
                    if (newer.isNotEmpty()) {
                        op.held = true
                        s.conflicts += Conflict(newId("k"), "deleteVsAdd", op.col, JSONObject().put("op", op.id).put("name", c!!.name)
                            .put("count", newer.size).put("by", newer.map { it.deviceName }.distinct().joinToString()))
                    }
                }
            }
        }
        save(ctx, id)
    }

    /** Two live collections with the same name (created on two phones): ask whether to merge. */
    private fun detectDuplicates(s: State) {
        val live = s.cols.values.filter { it.deletedAt == 0L }
        for ((_, group) in live.groupBy { it.name.trim().lowercase() }) {
            if (group.size < 2) continue
            val key = group.map { it.id }.sorted().joinToString("+")
            if (key in s.dismissed || s.conflicts.any { it.kind == "duplicate" && it.o.optString("key") == key }) continue
            s.conflicts += Conflict(newId("k"), "duplicate", group.first().id, JSONObject().put("key", key)
                .put("ids", JSONArray(group.map { it.id })).put("name", group.first().name))
        }
        // forget duplicate conflicts that no longer apply
        s.conflicts.removeAll { k ->
            k.kind == "duplicate" && k.o.getJSONArray("ids").let { a -> (0 until a.length()).count { live.any { c -> c.id == a.getString(it) } } < 2 }
        }
    }

    /** Sends the queued changes in order. Returns whether anything was sent. */
    private fun push(ctx: Context, id: String, files: CollectionFiles): Boolean {
        var sent = false
        while (true) {
            val op = synchronized(this) { state(ctx, id).outbox.firstOrNull { !it.held } } ?: break
            try {
                when (op.type) {
                    "create" -> files.setName(op.col, op.o.optString("name"), op.at)
                    "rename" -> files.setName(op.col, op.o.optString("name"), op.at)
                    "delete" -> files.delete(op.col, op.at, Device.name(ctx))
                    "add" -> files.addEntry(op.col, op.o.getString("entry"), op.o.optString("name", "Collection"), op.at)
                    "remove" -> files.removeEntries(op.col, op.o.getJSONArray("entries").let { a -> (0 until a.length()).map { a.getString(it) } })
                }
                synchronized(this) { state(ctx, id).outbox.remove(op); save(ctx, id) }
                sent = true
            } catch (e: CloudException) {
                if (e.kind == CloudException.Kind.NETWORK) throw e          // try again later, keep the order
                synchronized(this) {
                    op.fails = op.fails + 1
                    if (op.fails >= 3) {
                        op.held = true
                        state(ctx, id).conflicts += Conflict(newId("k"), "failed", op.col, JSONObject().put("op", op.id)
                            .put("what", describe(ctx, id, op)).put("error", e.message ?: "error"))
                    }
                    save(ctx, id)
                }
                if (op.fails < 3) throw e
            }
        }
        return sent
    }

    /** A short description of a queued change, for the Sync status screen. */
    fun describe(ctx: Context, id: String, op: Op): String {
        val name = view(ctx, id).firstOrNull { it.id == op.col }?.name ?: state(ctx, id).cols[op.col]?.name ?: op.o.optString("name", "a collection")
        return when (op.type) {
            "create" -> "Create “${op.o.optString("name")}”"
            "rename" -> "Rename “$name” to “${op.o.optString("name")}”"
            "delete" -> "Delete “$name”"
            "add" -> "Add ${clipLabel(op.o.optString("clip"))} to “$name”"
            "remove" -> "Remove ${op.o.getJSONArray("entries").length()} entr${if (op.o.getJSONArray("entries").length() == 1) "y" else "ies"} from “$name”"
            else -> op.type
        }
    }

    private fun clipLabel(clip: String) = try {
        val t = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).parse(clip.substring(3, 18))!!
        "the clip of " + java.text.SimpleDateFormat("MMM d, HH:mm:ss", java.util.Locale.getDefault()).format(t)
    } catch (e: Exception) { clip }

    // ------------------------------------------------------------------ resolving conflicts (Sync status screen)

    private fun opById(s: State, opId: String) = s.outbox.firstOrNull { it.id == opId }

    fun resolve(ctx: Context, id: String, conflictId: String, choice: String, newName: String? = null) {
        synchronized(this) {
            val s = state(ctx, id)
            val k = s.conflicts.firstOrNull { it.id == conflictId } ?: return
            when (k.kind) {
                "rename" -> {
                    val op = opById(s, k.o.optString("op"))
                    if (op != null) {
                        if (choice == "mine") { op.o.put("base", Long.MAX_VALUE / 2).put("at", System.currentTimeMillis()); op.held = false }
                        else s.outbox.remove(op)
                    }
                }
                "deleted" -> {
                    val ops = k.o.getJSONArray("ops").let { a -> (0 until a.length()).mapNotNull { opById(s, a.getString(it)) } }
                    if (choice == "restore") {
                        // a new collection with the same name holds this phone's additions
                        val col = newId("c")
                        s.outbox.add(s.outbox.indexOf(ops.firstOrNull() ?: return).coerceAtLeast(0),
                            Op(newId("o"), "create", col, JSONObject().put("name", k.o.optString("name")).put("at", System.currentTimeMillis())))
                        for (op in ops) { op.col = col; op.held = false }
                    } else s.outbox.removeAll(ops.toSet())
                }
                "deleteVsAdd" -> {
                    val op = opById(s, k.o.optString("op"))
                    if (op != null) {
                        if (choice == "delete") { op.o.put("at", System.currentTimeMillis()); op.held = false } else s.outbox.remove(op)
                    }
                }
                "duplicate" -> {
                    val ids = k.o.getJSONArray("ids").let { a -> (0 until a.length()).map { a.getString(it) } }
                    when (choice) {
                        "merge" -> {
                            val keep = ids.firstOrNull { it == CollectionFiles.FAVOURITES } ?: ids.minByOrNull { s.cols[it]?.created ?: Long.MAX_VALUE }!!
                            val have = s.cols[keep]?.entries?.map { it.clip }?.toMutableSet() ?: HashSet()
                            val dev = Device.id(ctx); val dn = Device.name(ctx)
                            for (other in ids.filter { it != keep }) {
                                for (e in s.cols[other]?.entries ?: emptyList<EntryS>()) {
                                    if (!have.add(e.clip)) continue
                                    val at = System.currentTimeMillis()
                                    s.outbox += Op(newId("o"), "add", keep, JSONObject().put("clip", e.clip).put("entry", CollectionFilesNames.entry(e.clip, dev, at, dn))
                                        .put("dev", dev).put("dn", dn).put("at", at).put("name", s.cols[keep]?.name ?: ""))
                                }
                                s.outbox += Op(newId("o"), "delete", other, JSONObject().put("at", System.currentTimeMillis()))
                            }
                        }
                        "rename" -> if (!newName.isNullOrBlank()) {
                            val target = ids.last()
                            s.outbox += Op(newId("o"), "rename", target, JSONObject().put("name", newName.trim())
                                .put("base", s.cols[target]?.nameAt ?: 0).put("at", System.currentTimeMillis()))
                            s.dismissed += k.o.optString("key")
                        }
                        else -> s.dismissed += k.o.optString("key")   // keep both
                    }
                }
                "failed" -> {
                    val op = opById(s, k.o.optString("op"))
                    if (op != null) { if (choice == "retry") { op.fails = 0; op.held = false } else s.outbox.remove(op) }
                }
            }
            s.conflicts.remove(k)
            save(ctx, id)
        }
        schedule(ctx, id, 200)
    }

    /** Last resort: drop every change not sent yet (and their conflicts). */
    fun discardUnsent(ctx: Context, id: String) {
        synchronized(this) { val s = state(ctx, id); s.outbox.clear(); s.conflicts.removeAll { it.kind != "duplicate" }; save(ctx, id) }
        schedule(ctx, id, 200)
    }

    fun discardOp(ctx: Context, id: String, opId: String) {
        synchronized(this) {
            val s = state(ctx, id)
            s.outbox.removeAll { it.id == opId }
            s.conflicts.removeAll { k -> k.o.optString("op") == opId }
            save(ctx, id)
        }
        schedule(ctx, id, 200)
    }

    /** Last resort: forget this phone's copy and unsent changes, read everything from the cloud again. */
    fun redownload(ctx: Context, id: String) {
        synchronized(this) {
            val s = state(ctx, id)
            s.outbox.clear(); s.conflicts.clear(); s.cols.clear(); s.missing.clear(); s.dismissed.clear()
            save(ctx, id)
        }
        schedule(ctx, id, 0)
    }

    /**
     * Last resort when the cloud lost collections (e.g. the folder was cleaned by hand): send this phone's copy
     * again. It only adds (merges); nothing in the cloud is removed.
     */
    fun reupload(ctx: Context, id: String) {
        val v = view(ctx, id)
        synchronized(this) {
            val s = state(ctx, id)
            val dev = Device.id(ctx); val dn = Device.name(ctx)
            for (c in v) {
                val at = System.currentTimeMillis()
                s.outbox += Op(newId("o"), "rename", c.id, JSONObject().put("name", c.name).put("base", Long.MAX_VALUE / 2).put("at", maxOf(at, c.nameAt)))
                for (e in c.entries) s.outbox += Op(newId("o"), "add", c.id, JSONObject().put("clip", e.clip).put("entry", e.file)
                    .put("dev", dev).put("dn", dn).put("at", e.at).put("name", c.name))
            }
            save(ctx, id)
        }
        schedule(ctx, id, 0)
    }

    // ------------------------------------------------------------------ migration of per-phone collections

    /** Earlier versions kept remote collections on each phone: move them to the shared ones (same names merge). */
    private fun migrate(ctx: Context, id: String) {
        if (state(ctx, id).migrated) return
        val old = File(ctx.filesDir, "remote_${id}_collections.json")
        if (old.exists()) {
            val src = RemoteSources.get(ctx, id)
            if (src != null) RemoteRepo.cached(ctx, src)
            val legacy = CollectionStore("remote_${id}_collections.json").all(ctx)
            for (c in legacy) {
                val names = c.clipIds.mapNotNull { RemoteRepo.entry(it)?.name }
                if (names.isEmpty() && c.isFavourites) continue
                val col = if (c.isFavourites) CollectionFiles.FAVOURITES else "m" + Integer.toHexString(c.name.trim().lowercase().hashCode())
                if (!c.isFavourites) enqueue(ctx, id, "rename", col, JSONObject().put("name", c.name).put("base", Long.MAX_VALUE / 2).put("at", c.created.coerceAtLeast(1)))
                add(ctx, id, col, names)
            }
            old.renameTo(File(old.path + ".migrated"))
        }
        synchronized(this) { state(ctx, id).migrated = true; save(ctx, id) }
    }

    fun forget(ctx: Context, id: String) {
        synchronized(this) { states.remove(id) }
        file(ctx, id).delete()
    }
}

/** Entry file names (shared with [CollectionFiles]). */
object CollectionFilesNames {
    fun entry(clip: String, device: String, at: Long, deviceName: String) =
        "${clip.removeSuffix(".mp4")}~$device~$at~${com.motioncam.cloud.MarkStore.b64(com.motioncam.cloud.MarkStore.fit(deviceName, 30))}"
}

/**
 * The collections API the gallery uses, backed by the shared collections of a remote folder. Ids of clips are the
 * remote clip ids (derived from the clip name), so collections keep working across refreshes and devices.
 */
class SharedCollectionStore(private val sourceId: String) : CollectionStore("remote_${sourceId}_shared.json", sourceId) {
    private fun names(ctx: Context, clipIds: Collection<Long>): List<String> {
        val byId = HashMap<Long, String>()
        for (c in SharedSync.view(ctx, sourceId)) for (e in c.entries) byId[RemoteRepo.idFor(sourceId, e.clip)] = e.clip
        return clipIds.mapNotNull { RemoteRepo.entry(it)?.name ?: byId[it] }
    }

    fun canEdit(ctx: Context) = RemoteSources.get(ctx, sourceId)?.mode != RemoteSources.MODE_LINK

    override fun all(ctx: Context): List<ClipCollection> = SharedSync.view(ctx, sourceId).map { c ->
        ClipCollection(c.id, c.name, LinkedHashSet(c.entries.sortedBy { it.at }.map { RemoteRepo.idFor(sourceId, it.clip) }), c.created)
    }

    override fun get(ctx: Context, id: String): ClipCollection? = all(ctx).firstOrNull { it.id == id }

    /** Clip names in a collection (including clips no longer in the folder). */
    fun clipNames(ctx: Context, id: String): List<String> =
        SharedSync.view(ctx, sourceId).firstOrNull { it.id == id }?.entries?.map { it.clip }?.distinct() ?: emptyList()

    override fun create(ctx: Context, name: String): ClipCollection {
        val id = if (canEdit(ctx)) SharedSync.create(ctx, sourceId, name) else "none"
        return ClipCollection(id, name.trim().ifEmpty { "Untitled" }, LinkedHashSet(), System.currentTimeMillis())
    }

    // a read-only viewer link can't change the shared collections
    override fun rename(ctx: Context, id: String, name: String) { if (canEdit(ctx) && name.isNotBlank()) SharedSync.rename(ctx, sourceId, id, name) }
    override fun delete(ctx: Context, id: String) { if (canEdit(ctx)) SharedSync.delete(ctx, sourceId, id) }
    override fun add(ctx: Context, id: String, clips: Collection<Long>) { if (canEdit(ctx)) SharedSync.add(ctx, sourceId, id, names(ctx, clips)) }
    override fun remove(ctx: Context, id: String, clips: Collection<Long>) { if (canEdit(ctx)) SharedSync.remove(ctx, sourceId, id, names(ctx, clips)) }
    override fun prune(ctx: Context, existing: Set<Long>) {}
}
