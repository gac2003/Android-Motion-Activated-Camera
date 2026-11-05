package com.motioncam.cloud

/**
 * Tags of a folder's clips, stored in the folder so every phone sees the same ones:
 *
 *   <folder>/.tags/<clip without .mp4>~<base64 tag>~<device>~<time>
 *
 * One small file per (clip, tag) entry, never a list file: two phones tagging at the same time each add or remove their
 * own files, so nothing overwrites anything. Removing a tag removes the entries this phone had seen, so a tag someone
 * added at the same moment survives ("add wins") — the same rules as shared collections.
 */
class TagFiles(private val p: CloudProvider, private val folder: String, private val writable: Boolean) {
    class Entry(val clip: String, val tag: String, val file: RemoteFile)

    private var root: String? = null
    private var entries: MutableList<Entry>? = null

    private fun root(create: Boolean): String? {
        root?.let { return it }
        val f = p.listFolders(folder).firstOrNull { it.name == ROOT } ?: if (create && writable) p.createFolder(folder, ROOT) else return null
        root = f.id
        return f.id
    }

    /** Every tag entry in the folder (network). */
    fun load(): MutableList<Entry> {
        val r = root(false) ?: return ArrayList<Entry>().also { entries = it }
        val out = ArrayList<Entry>()
        for (f in p.list(r)) {
            val parts = f.name.split("~")
            if (parts.size < 4 || !parts[0].startsWith("MC_")) continue
            val tag = MarkStore.unb64(parts[1]).trim()
            if (tag.isEmpty()) continue
            out += Entry(parts[0] + ".mp4", tag, f)
        }
        entries = out
        return out
    }

    /** clip name → its tags. */
    fun map(): Map<String, Set<String>> = (entries ?: load()).groupBy { it.clip }.mapValues { (_, l) -> l.map { it.tag }.toSortedSet(String.CASE_INSENSITIVE_ORDER) }

    fun add(clip: String, tag: String, device: String) {
        if (!writable) return
        val all = entries ?: load()
        if (all.any { it.clip == clip && it.tag.equals(tag, true) }) return
        val r = root(true) ?: return
        val name = clip.removeSuffix(".mp4") + "~" + MarkStore.b64(tag) + "~" + device + "~" + System.currentTimeMillis()
        val f = p.upload(r, name, bytesSource(byteArrayOf(1)), null, {}, {})
        all += Entry(clip, tag, f)
    }

    fun remove(clip: String, tag: String) {
        if (!writable) return
        val all = entries ?: load()
        val r = root(false) ?: return
        val gone = all.filter { it.clip == clip && it.tag.equals(tag, true) }
        for (e in gone) try { p.delete(r, e.file) } catch (_: Exception) {}
        all.removeAll(gone)
    }

    companion object { const val ROOT = ".tags" }
}
