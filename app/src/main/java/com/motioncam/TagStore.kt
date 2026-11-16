package com.motioncam

import android.content.Context
import com.motioncam.cloud.CloudProvider
import com.motioncam.cloud.Device
import com.motioncam.cloud.TagFiles
import com.motioncam.remote.RemoteRepo
import com.motioncam.remote.RemoteSource
import com.motioncam.remote.RemoteSources
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Tags (labels) on clips, for finding them again with Search.
 *  - A remote folder's tags live in the folder ([TagFiles]), so every phone sees the same ones. This phone keeps the
 *    last copy (works offline) and an outbox of its own changes, sent at the next sync (right away when online).
 *  - Clips on this phone have their own tags, kept on the phone.
 *  - Tags travel with a clip: saving a clip to the phone, backing it up, copying or moving it keeps them.
 * Tags compare without case ("Garage" = "garage"); the first spelling is shown.
 */
object TagStore {
    /** Tags changed for a scope (null = this phone's clips, else a remote folder id); main thread. */
    val listeners = java.util.concurrent.CopyOnWriteArraySet<(String?) -> Unit>()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private fun changed(scope: String?) = main.post { for (l in listeners) try { l(scope) } catch (_: Exception) {} }

    fun clean(tag: String) = tag.trim().replace(Regex("\\s+"), " ").removePrefix("#").take(40)

    // ------------------------------------------------------------------ this phone's clips

    private var local: MutableMap<Long, MutableSet<String>>? = null
    private fun localFile(ctx: Context) = File(ctx.filesDir, "tags_local.json")

    private fun localMap(ctx: Context): MutableMap<Long, MutableSet<String>> {
        local?.let { return it }
        val m = HashMap<Long, MutableSet<String>>()
        try {
            val f = localFile(ctx)
            if (f.exists()) {
                val o = JSONObject(f.readText())
                for (k in o.keys()) { val a = o.getJSONArray(k); m[k.toLong()] = (0 until a.length()).map { a.getString(it) }.toCollection(sortedSet()) }
            }
        } catch (_: Exception) {}
        local = m
        return m
    }

    private fun sortedSet() = java.util.TreeSet(String.CASE_INSENSITIVE_ORDER)

    private fun saveLocal(ctx: Context) {
        val o = JSONObject()
        for ((k, v) in localMap(ctx)) if (v.isNotEmpty()) o.put(k.toString(), JSONArray(v.toList()))
        write(localFile(ctx), o.toString())
    }

    private fun write(f: File, s: String) { try { val t = File(f.path + ".tmp"); t.writeText(s); t.renameTo(f) } catch (_: Exception) {} }

    /** A saved copy gets the tags it had in its folder. */
    @Synchronized
    fun setLocal(ctx: Context, clipId: Long, tags: Set<String>) {
        if (tags.isEmpty()) return
        localMap(ctx).getOrPut(clipId) { sortedSet() }.addAll(tags)
        saveLocal(ctx)
        changed(null)
    }

    // ------------------------------------------------------------------ remote folders

    private val remote = HashMap<String, MutableMap<String, MutableSet<String>>>()
    private fun remoteFile(ctx: Context, src: String) = File(RemoteRepo.dataDir(ctx, src), "tags.json")
    private fun outboxFile(ctx: Context, src: String) = File(RemoteRepo.dataDir(ctx, src), "tags_outbox.json")

    private fun remoteMap(ctx: Context, src: String): MutableMap<String, MutableSet<String>> = remote.getOrPut(src) {
        val m = HashMap<String, MutableSet<String>>()
        try {
            val f = remoteFile(ctx, src)
            if (f.exists()) { val o = JSONObject(f.readText()); for (k in o.keys()) { val a = o.getJSONArray(k); m[k] = (0 until a.length()).map { a.getString(it) }.toCollection(sortedSet()) } }
        } catch (_: Exception) {}
        m
    }

    private fun saveRemote(ctx: Context, src: String) {
        val o = JSONObject()
        for ((k, v) in remoteMap(ctx, src)) if (v.isNotEmpty()) o.put(k, JSONArray(v.toList()))
        write(remoteFile(ctx, src), o.toString())
    }

    private fun outbox(ctx: Context, src: String): JSONArray = try {
        outboxFile(ctx, src).takeIf { it.exists() }?.readText()?.let { JSONArray(it) } ?: JSONArray()
    } catch (_: Exception) { JSONArray() }

    // ------------------------------------------------------------------ reading

    /** A clip's tags ([scope] null = this phone's clips, else the remote folder id). */
    @Synchronized
    fun tagsOf(ctx: Context, scope: String?, clip: Clip): Set<String> =
        if (scope == null) localMap(ctx)[clip.id] ?: emptySet() else remoteMap(ctx, scope)[clip.name] ?: emptySet()

    /** Every tag used in a scope with how many of [clips] have it (most used first). */
    @Synchronized
    fun all(ctx: Context, scope: String?, clips: List<Clip>): List<Pair<String, Int>> {
        val count = java.util.TreeMap<String, Int>(String.CASE_INSENSITIVE_ORDER)
        for (c in clips) for (t in tagsOf(ctx, scope, c)) count[t] = (count[t] ?: 0) + 1
        return count.entries.map { it.key to it.value }.sortedWith(compareBy({ -it.second }, { it.first.lowercase() }))
    }

    // ------------------------------------------------------------------ changing

    /** Adds and removes tags on clips; a remote folder's change is sent to the folder (now, or at the next sync). */
    fun change(ctx: Context, scope: String?, clips: List<Clip>, add: Set<String>, remove: Set<String>) {
        val a = add.map { clean(it) }.filter { it.isNotEmpty() }.toSet()
        val r = remove.map { clean(it) }.filter { it.isNotEmpty() }.toSet()
        if (a.isEmpty() && r.isEmpty()) return
        synchronized(this) {
            if (scope == null) {
                val m = localMap(ctx)
                for (c in clips) { val s = m.getOrPut(c.id) { sortedSet() }; s.removeAll(r); s.addAll(a); if (s.isEmpty()) m.remove(c.id) }
                saveLocal(ctx)
            } else {
                val m = remoteMap(ctx, scope)
                val box = outbox(ctx, scope)
                for (c in clips) {
                    val s = m.getOrPut(c.name) { sortedSet() }
                    for (t in r) { s.remove(t); box.put(JSONObject().put("op", "del").put("clip", c.name).put("tag", t)) }
                    for (t in a) { s.add(t); box.put(JSONObject().put("op", "add").put("clip", c.name).put("tag", t)) }
                    if (s.isEmpty()) m.remove(c.name)
                }
                saveRemote(ctx, scope)
                write(outboxFile(ctx, scope), box.toString())
            }
        }
        changed(scope)
        if (scope != null) RemoteSources.get(ctx, scope)?.let { syncAsync(ctx, it) }
    }

    /** Sends this phone's waiting changes to the folder and reads its tags (network; off the main thread). */
    fun sync(ctx: Context, src: RemoteSource) {
        val box = synchronized(this) { outbox(ctx, src.id) }
        val sent = box.length()
        val map = RemoteSources.reader(ctx, src).use { r ->
            val tf = r.tagFiles(src.folderId)
            val dev = Device.id(ctx)
            for (i in 0 until box.length()) {
                val o = box.optJSONObject(i) ?: continue
                if (o.optString("op") == "add") tf.add(o.optString("clip"), o.optString("tag"), dev) else tf.remove(o.optString("clip"), o.optString("tag"))
            }
            tf.map()
        }
        synchronized(this) {
            // changes made while this ran stay waiting for the next sync
            val now = outbox(ctx, src.id)
            val rest = JSONArray(); for (i in sent until now.length()) rest.put(now.get(i))
            write(outboxFile(ctx, src.id), rest.toString())
            val m = remoteMap(ctx, src.id)
            m.clear()
            for ((k, v) in map) m[k] = v.toCollection(sortedSet())
            for (i in 0 until rest.length()) rest.optJSONObject(i)?.let { o ->
                val s = m.getOrPut(o.optString("clip")) { sortedSet() }
                if (o.optString("op") == "add") s.add(o.optString("tag")) else s.remove(o.optString("tag"))
            }
            saveRemote(ctx, src.id)
        }
        changed(src.id)
        if (sent > 0) com.motioncam.live.Live.nudge(ctx, src.kind, src.folderId, "tags")
    }

    private val syncing = java.util.Collections.synchronizedSet(HashSet<String>())

    private val lastSync = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Syncs in the background, unless it was done less than [maxAgeMs] ago. */
    fun syncAsync(ctx: Context, src: RemoteSource, maxAgeMs: Long = 0) {
        val app = ctx.applicationContext
        if (maxAgeMs > 0 && System.currentTimeMillis() - (lastSync[src.id] ?: 0) < maxAgeMs) return
        if (!syncing.add(src.id)) return
        Thread {
            try { sync(app, src); lastSync[src.id] = System.currentTimeMillis() } catch (_: Exception) {} finally { syncing.remove(src.id) }
        }.start()
    }

    /** Writes tags for clips straight into a folder (backing up, copying or moving clips there). Network. */
    fun writeTo(ctx: Context, p: CloudProvider, folder: String, tags: Map<String, Set<String>>) {
        if (tags.values.all { it.isEmpty() }) return
        val tf = TagFiles(p, folder, true)
        val dev = Device.id(ctx)
        for ((clip, ts) in tags) for (t in ts) try { tf.add(clip, t, dev) } catch (_: Exception) {}
    }

    /** Drops this phone's tags of clips that no longer exist (only call with the full list of clips). */
    fun prune(ctx: Context, ids: Set<Long>) {
        synchronized(this) {
            val m = localMap(ctx)
            if (!m.keys.removeAll { it !in ids }) return
            saveLocal(ctx)
        }
        changed(null)
    }

    /** Clips that are gone (deleted): their tags go too. The folder's tag files were already removed. */
    fun forget(ctx: Context, scope: String?, clips: List<Clip>) {
        if (clips.isEmpty()) return
        synchronized(this) {
            if (scope == null) { val m = localMap(ctx); if (clips.map { m.remove(it.id) != null }.any { it }) saveLocal(ctx) }
            else { val m = remoteMap(ctx, scope); clips.forEach { m.remove(it.name) }; saveRemote(ctx, scope) }
        }
        changed(scope)
    }

    /** The tags of these clips in their scope, by clip name (to carry them along). */
    @Synchronized
    fun byName(ctx: Context, scope: String?, clips: List<Clip>): Map<String, Set<String>> =
        clips.associate { it.name to tagsOf(ctx, scope, it) }.filterValues { it.isNotEmpty() }
}
