package com.motioncam

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A named group of clips. Clips are referenced by id ("soft links"): a clip can be in many collections. */
class ClipCollection(val id: String, var name: String, val clipIds: LinkedHashSet<Long>, val created: Long) {
    val isFavourites get() = id == Collections.FAVOURITES
}

/**
 * Stores collections in a small JSON file in the app's private storage. [Collections] holds the phone's own
 * clips; each remote folder has its own store ([Collections.forRemote]) so the two never mix. Remote
 * collections live only on this device and are never written to the shared folder.
 */
open class CollectionStore(private val fileName: String, private val remoteId: String? = null) {
    private var cache: MutableList<ClipCollection>? = null

    private fun file(ctx: Context) = File(ctx.filesDir, fileName)

    @Synchronized
    private fun load(ctx: Context): MutableList<ClipCollection> {
        cache?.let { return it }
        val out = ArrayList<ClipCollection>()
        try {
            val f = file(ctx)
            if (f.exists()) {
                val arr = JSONArray(f.readText())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val ids = LinkedHashSet<Long>()
                    val a = o.getJSONArray("clips")
                    for (j in 0 until a.length()) ids += a.getLong(j)
                    out += ClipCollection(o.getString("id"), o.getString("name"), ids, o.optLong("created"))
                }
            }
        } catch (_: Exception) {}
        if (out.none { it.isFavourites }) out.add(0, ClipCollection(Collections.FAVOURITES, "Favourites", LinkedHashSet(), 0))
        cache = out
        return out
    }

    @Synchronized
    private fun save(ctx: Context) {
        val arr = JSONArray()
        for (c in load(ctx)) {
            arr.put(JSONObject().apply {
                put("id", c.id)
                put("name", c.name)
                put("created", c.created)
                put("clips", JSONArray().apply { c.clipIds.forEach { put(it) } })
            })
        }
        try {
            val f = file(ctx)
            val tmp = File(f.path + ".tmp")
            tmp.writeText(arr.toString())
            tmp.renameTo(f)
        } catch (_: Exception) {}
    }

    /** Favourites first, then by creation time. */
    @Synchronized
    fun all(ctx: Context): List<ClipCollection> = load(ctx).sortedWith(compareBy({ !it.isFavourites }, { it.created }))

    @Synchronized
    fun get(ctx: Context, id: String): ClipCollection? = load(ctx).firstOrNull { it.id == id }

    @Synchronized
    fun create(ctx: Context, name: String): ClipCollection {
        val c = ClipCollection("c" + System.currentTimeMillis(), name.trim().ifEmpty { "Untitled" }, LinkedHashSet(), System.currentTimeMillis())
        load(ctx) += c
        save(ctx)
        return c
    }

    @Synchronized
    fun rename(ctx: Context, id: String, name: String) {
        get(ctx, id)?.let { it.name = name.trim().ifEmpty { it.name }; save(ctx) }
        resync(ctx)   // marks show collection names
    }

    /** Deletes the collection only; the clips themselves are untouched. Favourites cannot be deleted. */
    @Synchronized
    fun delete(ctx: Context, id: String) {
        if (id == Collections.FAVOURITES) return
        load(ctx).removeAll { it.id == id }
        save(ctx)
        resync(ctx)
    }

    @Synchronized
    fun add(ctx: Context, id: String, clips: Collection<Long>) {
        get(ctx, id)?.let { it.clipIds.addAll(clips); save(ctx) }
        // adding to a collection asks for the clips to be kept in the cloud
        com.motioncam.cloud.MarkSync.onCollectionAdd(ctx, remoteId, id, clips)
    }

    @Synchronized
    fun remove(ctx: Context, id: String, clips: Collection<Long>) {
        get(ctx, id)?.let { it.clipIds.removeAll(clips.toSet()); save(ctx) }
        resync(ctx)
    }

    /** Collections changed: update this device's protection marks in the background. */
    protected fun resync(ctx: Context) {
        if (remoteId != null) com.motioncam.cloud.MarkSync.schedule(ctx, com.motioncam.cloud.MarkSync.remoteKey(remoteId))
        else com.motioncam.cloud.MarkSync.scheduleLocal(ctx)
    }

    @Synchronized
    fun isFavourite(ctx: Context, clipId: Long) = get(ctx, Collections.FAVOURITES)?.clipIds?.contains(clipId) == true

    @Synchronized
    fun favouriteIds(ctx: Context): Set<Long> = get(ctx, Collections.FAVOURITES)?.clipIds?.toSet() ?: emptySet()

    /** Drops references to clips that no longer exist (deleted from the phone). */
    @Synchronized
    fun prune(ctx: Context, existing: Set<Long>) {
        var changed = false
        for (c in load(ctx)) if (c.clipIds.retainAll(existing)) changed = true
        if (changed) save(ctx)
    }
}

/** The phone's own collections (Favourites + user-made). */
object Collections : CollectionStore("collections.json") {
    const val FAVOURITES = "favourites"
    private val remote = HashMap<String, CollectionStore>()

    /** Collections of a remote folder, kept on this device only. */
    @Synchronized
    fun forRemote(sourceId: String): CollectionStore = remote.getOrPut(sourceId) { CollectionStore("remote_${sourceId}_collections.json", sourceId) }

    /** Removes a remote folder's collections (when the folder is removed from this device). */
    @Synchronized
    fun dropRemote(ctx: Context, sourceId: String) {
        remote.remove(sourceId)
        File(ctx.filesDir, "remote_${sourceId}_collections.json").delete()
    }

    /** The store for a remote source id, or the phone's own when null. */
    fun of(sourceId: String?): CollectionStore = if (sourceId == null) this else forRemote(sourceId)
}
