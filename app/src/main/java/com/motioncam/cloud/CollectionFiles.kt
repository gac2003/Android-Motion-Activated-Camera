package com.motioncam.cloud

/**
 * Shared collections stored in the backup folder, so every phone on the account sees the same ones:
 *
 *   <folder>/.collections/<collection id>/
 *       _name~<time>~<base64 name>            the name (several after concurrent renames: the newest wins)
 *       _deleted~<time>~<base64 device name>  the collection was deleted (wins over late additions)
 *       <clip without .mp4>~<device>~<time>~<base64 device name>   one file per clip entry
 *
 * One small file per entry, never one list file: two phones changing a collection at the same time each
 * add or remove their own files, so no change overwrites another. An entry is identified by its file name
 * (clip + who + when), so a removal only removes the entries the phone had seen and a simultaneous
 * re-add survives ("add wins").
 */
class CollectionFiles(private val p: CloudProvider, private val folder: String, val writable: Boolean) {

    class Entry(val clip: String, val device: String, val at: Long, val deviceName: String, val file: String)
    class Col(val id: String, var folderId: String?) {
        val names = ArrayList<Triple<Long, String, String>>()   // time, name, file name
        var deletedAt = 0L
        var deletedBy: String? = null
        val entries = ArrayList<Entry>()
        val name get() = names.maxByOrNull { it.first }?.second ?: if (id == FAVOURITES) "Favourites" else "Collection"
        val created get() = names.minOfOrNull { it.first } ?: 0L
    }

    private var root: String? = null
    private var cols: MutableMap<String, Col>? = null
    private val files = HashMap<String, MutableList<RemoteFile>>()   // collection id -> its files

    private fun root(create: Boolean): String? {
        root?.let { return it }
        val f = p.listFolders(folder).firstOrNull { it.name == ROOT } ?: if (create && writable) p.createFolder(folder, ROOT) else return null
        root = f.id
        return f.id
    }

    /** Everything in the cloud (network). */
    fun load(): Map<String, Col> {
        files.clear()
        val out = LinkedHashMap<String, Col>()
        val r = root(false)
        if (r != null) for (cf in p.listFolders(r)) {
            if (cf.name.startsWith(".")) continue
            val c = Col(cf.name, cf.id)
            val fl = p.list(cf.id).toMutableList()
            files[c.id] = fl
            for (f in fl) parse(c, f.name)
            out[c.id] = c
        }
        cols = out
        return out
    }

    private fun parse(c: Col, n: String) {
        val parts = n.split("~")
        when {
            parts[0] == "_name" && parts.size >= 3 -> c.names += Triple(parts[1].toLongOrNull() ?: 0, MarkStore.unb64(parts[2]), n)
            parts[0] == "_deleted" && parts.size >= 2 -> {
                val at = parts[1].toLongOrNull() ?: 0
                if (at >= c.deletedAt) { c.deletedAt = at; c.deletedBy = parts.getOrNull(2)?.let { MarkStore.unb64(it) } }
            }
            parts.size >= 4 && parts[0].startsWith("MC_") ->
                c.entries += Entry(parts[0] + ".mp4", parts[1], parts[2].toLongOrNull() ?: 0, MarkStore.unb64(parts[3]), n)
        }
    }

    private fun col(id: String, create: Boolean): Col? {
        val all = cols ?: load()
        all[id]?.let { c ->
            if (c.folderId == null && create) c.folderId = p.createFolder(root(true)!!, id).id
            return c
        }
        if (!create) return null
        val c = Col(id, p.createFolder(root(true) ?: return null, id).id)
        (cols ?: HashMap<String, Col>().also { cols = it })[id] = c
        files[id] = ArrayList()
        return c
    }

    private fun put(c: Col, name: String) {
        val rf = p.upload(c.folderId!!, name, bytesSource(byteArrayOf(1)), null, {}, {})
        files.getOrPut(c.id) { ArrayList() }.add(rf)
        parse(c, name)
    }

    private fun del(c: Col, name: String) {
        val fl = files[c.id] ?: return
        val f = fl.firstOrNull { it.name == name } ?: return
        try { p.delete(c.folderId!!, f) } catch (e: CloudException) { if (e.kind != CloudException.Kind.NOT_FOUND) throw e }
        fl.remove(f)
    }

    // ------------------------------------------------------------------ changes (each safe to repeat)

    fun setName(id: String, name: String, at: Long) {
        check(writable)
        val c = col(id, true) ?: return
        val file = "_name~$at~" + MarkStore.b64(MarkStore.fit(name.trim(), 120))
        if (c.names.none { it.third == file }) put(c, file)
        // older names are no longer needed
        for (old in c.names.filter { it.first < at }.toList()) { del(c, old.third); c.names.remove(old) }
    }

    /** Adds an entry; [nameIfNew] names the collection if it does not exist in the cloud yet. */
    fun addEntry(id: String, entry: String, nameIfNew: String? = null, at: Long = System.currentTimeMillis()) {
        check(writable)
        val existed = (cols ?: load()).containsKey(id)
        val c = col(id, true) ?: return
        if (!existed && nameIfNew != null) setName(id, if (id == FAVOURITES) "Favourites" else nameIfNew, at)
        if (c.deletedAt > 0) return                       // deleted meanwhile: deletion wins
        if (files[id]?.any { it.name == entry } == true) return
        put(c, entry)
    }

    /** Removes exactly these entries (by file name), so entries added elsewhere meanwhile survive. */
    fun removeEntries(id: String, entries: Collection<String>) {
        check(writable)
        val c = col(id, false) ?: return
        for (e in entries) { del(c, e); c.entries.removeAll { it.file == e } }
    }

    fun delete(id: String, at: Long, byName: String) {
        check(writable)
        if (id == FAVOURITES) return
        val c = col(id, true) ?: return
        if (c.deletedAt == 0L) put(c, "_deleted~$at~" + MarkStore.b64(MarkStore.fit(byName, 40)))
        for (e in c.entries.toList()) del(c, e.file)       // tidy up; the marker keeps it deleted
        c.entries.clear()
    }

    /** The camera phone deleted a clip: drop it from every collection (no dangling entries). */
    fun forgetClip(clip: String) {
        if (!writable) return
        for (c in (cols ?: load()).values) for (e in c.entries.filter { it.clip == clip }) { del(c, e.file); c.entries.remove(e) }
    }

    companion object {
        const val ROOT = ".collections"
        const val FAVOURITES = "favourites"
    }
}
