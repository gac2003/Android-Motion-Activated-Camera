package com.motioncam

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A named group of clips. Clips are referenced by id ("soft links"): a clip can be in many collections. */
class ClipCollection(val id: String, var name: String, val clipIds: LinkedHashSet<Long>, val created: Long) {
    val isFavourites get() = id == Collections.FAVOURITES
}

/** Stores collections in a small JSON file in the app's private storage. */
object Collections {
    const val FAVOURITES = "favourites"
    private var cache: MutableList<ClipCollection>? = null

    private fun file(ctx: Context) = File(ctx.filesDir, "collections.json")

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
        if (out.none { it.isFavourites }) out.add(0, ClipCollection(FAVOURITES, "Favourites", LinkedHashSet(), 0))
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
    }

    /** Deletes the collection only; the clips themselves are untouched. Favourites cannot be deleted. */
    @Synchronized
    fun delete(ctx: Context, id: String) {
        if (id == FAVOURITES) return
        load(ctx).removeAll { it.id == id }
        save(ctx)
    }

    @Synchronized
    fun add(ctx: Context, id: String, clips: Collection<Long>) {
        get(ctx, id)?.let { it.clipIds.addAll(clips); save(ctx) }
    }

    @Synchronized
    fun remove(ctx: Context, id: String, clips: Collection<Long>) {
        get(ctx, id)?.let { it.clipIds.removeAll(clips.toSet()); save(ctx) }
    }

    @Synchronized
    fun isFavourite(ctx: Context, clipId: Long) = get(ctx, FAVOURITES)?.clipIds?.contains(clipId) == true

    @Synchronized
    fun favouriteIds(ctx: Context): Set<Long> = get(ctx, FAVOURITES)?.clipIds?.toSet() ?: emptySet()

    /** Drops references to clips that no longer exist (deleted from the phone). */
    @Synchronized
    fun prune(ctx: Context, existing: Set<Long>) {
        var changed = false
        for (c in load(ctx)) if (c.clipIds.retainAll(existing)) changed = true
        if (changed) save(ctx)
    }
}
