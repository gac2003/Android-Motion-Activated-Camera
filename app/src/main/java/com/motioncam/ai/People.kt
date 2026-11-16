package com.motioncam.ai

import android.content.Context
import com.motioncam.cloud.Device
import com.motioncam.cloud.MarkStore
import com.motioncam.cloud.bytesSource
import com.motioncam.remote.RemoteRepo
import com.motioncam.remote.RemoteSource
import com.motioncam.remote.RemoteSources
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * The people a camera has seen: groups of similar faces, each with a few sample "face fingerprints" and its best
 * face picture, and the names you give them.
 *
 *  - The recording phone builds the groups while describing clips ([assign]) and tags each clip with the groups seen
 *    in it (an automatic tag "@<group>"). A clip's tags go with the clip; the groups stay, because they are what
 *    recognises the same person in later clips.
 *  - Names are separate, so naming or merging never has to touch the clips: a tag shows as the group's name wherever
 *    it is read ([resolve]); unnamed groups show nothing.
 *  - In the backup folder (".ai"): one file with this phone's groups ("p_<phone>.bin"), and one tiny file per naming
 *    ("n~<group>~<name>~<time>", the newest wins), so any phone viewing the folder can show and name the people; the
 *    recording phone picks names and merges up after its next batch of clips.
 *
 * A name starting with "=" means "this group is the same person as that group" (a merge), "!" means removed.
 */
object People {
    const val TAG = ClipFacts.MARK + "@"
    private const val MAGIC = 0x4D435031   // "MCP1"
    private const val SAME = 0.40f         // fingerprints this alike are the same person (the model's own figure is 0.36)
    private const val MAX_SAMPLES = 8
    private const val MAX_GROUPS = 300
    private const val NEW_GROUP_QUALITY = 36f   // a new person needs a clear face (how sure × face size in pixels)

    class Group(val id: String, var seen: Int, var last: Long, var thumb: ByteArray, var thumbQ: Float, val samples: ArrayList<ByteArray>)
    private class Name(val value: String, val at: Long)

    fun isFaceTag(tag: String) = tag.startsWith(TAG)
    @Volatile var facesFound = 0L; private set
    @Volatile var matched = 0L; private set

    // ================================================================== names (per place: this phone, or a remote folder)

    private val names = HashMap<String, HashMap<String, Name>>()
    private fun key(scope: String?) = scope ?: ""
    private fun namesFile(ctx: Context, scope: String?) =
        if (scope == null) File(ctx.filesDir, "ai_names.json") else File(RemoteRepo.dataDir(ctx, scope), "ai_names.json")

    @Synchronized
    private fun nameMap(ctx: Context, scope: String?): HashMap<String, Name> = names.getOrPut(key(scope)) {
        val m = HashMap<String, Name>()
        try {
            val f = namesFile(ctx, scope)
            if (f.exists()) { val o = JSONObject(f.readText()); for (k in o.keys()) { val e = o.getJSONObject(k); m[k] = Name(e.optString("v"), e.optLong("t")) } }
        } catch (_: Exception) {}
        m
    }

    @Synchronized
    private fun saveNames(ctx: Context, scope: String?) {
        val o = JSONObject()
        for ((k, v) in nameMap(ctx, scope)) o.put(k, JSONObject().put("v", v.value).put("t", v.at))
        try { val f = namesFile(ctx, scope); val t = File(f.path + ".tmp"); t.writeText(o.toString()); t.renameTo(f) } catch (_: Exception) {}
    }

    /** Takes in namings read from a folder (file names "n~group~name~time"); the newest of each group wins. True if anything changed. */
    @Synchronized
    private fun takeNames(ctx: Context, scope: String?, fileNames: Collection<String>): Boolean {
        val m = nameMap(ctx, scope)
        var changed = false
        for (n in fileNames) {
            val p = n.split("~")
            if (p.size != 4 || p[0] != "n") continue
            val at = p[3].toLongOrNull() ?: continue
            val old = m[p[1]]
            if (old == null || at > old.at) { m[p[1]] = Name(MarkStore.unb64(p[2]), at); changed = true }
        }
        if (changed) saveNames(ctx, scope)
        return changed
    }

    /** The group a group really is, after merges. */
    @Synchronized
    fun canon(ctx: Context, scope: String?, id: String): String {
        val m = nameMap(ctx, scope)
        var cur = id
        for (i in 0 until 6) { val v = m[cur]?.value ?: break; if (v.startsWith("=") && v.length > 1) cur = v.substring(1) else break }
        return cur
    }

    /** The name of a group (after merges), or null when it has none or was removed. */
    @Synchronized
    fun nameOf(ctx: Context, scope: String?, id: String): String? =
        nameMap(ctx, scope)[canon(ctx, scope, id)]?.value?.takeIf { it.isNotEmpty() && !it.startsWith("=") && it != "!" }

    @Synchronized
    private fun removed(ctx: Context, scope: String?, id: String) = nameMap(ctx, scope)[id]?.value.let { it == "!" || (it != null && it.startsWith("=")) }

    /** A clip's tags as shown and searched: face tags become the person's name, unnamed ones disappear. */
    fun resolve(ctx: Context, scope: String?, tags: Set<String>): Set<String> {
        if (tags.none { isFaceTag(it) }) return tags
        val out = java.util.TreeSet(String.CASE_INSENSITIVE_ORDER)
        for (t in tags) {
            if (!isFaceTag(t)) { out.add(t); continue }
            nameOf(ctx, scope, t.removePrefix(TAG))?.let { out.add(ClipFacts.MARK + it) }
        }
        return out
    }

    /**
     * Names a group ([value]: the name, "" for none, "=<other group>" to merge into another, "!" to remove), here and
     * in the folder so every phone sees it. Returns null, or why it couldn't be sent (it still applies on this phone).
     */
    fun setName(ctx: Context, scope: String?, id: String, value: String): String? {
        val app = ctx.applicationContext
        val now = System.currentTimeMillis()
        synchronized(this) {
            nameMap(app, scope)[id] = Name(value, now)
            saveNames(app, scope)
            if (scope == null) applyNamesToGroups(app)
        }
        val file = "n~$id~${MarkStore.b64(value)}~$now"
        return try {
            if (scope == null) { if (!com.motioncam.cloud.CloudSync.pushMeaning(app, file, byteArrayOf(1))) "It will reach the backup folder with the next batch of clips" else null }
            else {
                val src = RemoteSources.get(app, scope) ?: return "The folder is gone"
                RemoteSources.writer(app, src).use { p ->
                    val ai = p.listFolders(src.folderId).firstOrNull { it.name == Meaning.FOLDER } ?: p.createFolder(src.folderId, Meaning.FOLDER)
                    p.upload(ai.id, file, bytesSource(byteArrayOf(1)), null, {}, {})
                }
                com.motioncam.live.Live.nudge(app, src.kind, src.folderId, "tags")
                null
            }
        } catch (e: Exception) { e.message ?: "Couldn't reach the folder" }
    }

    // ================================================================== this phone's groups (the recording phone)

    private var groups: LinkedHashMap<String, Group>? = null
    private var dirty = false
    private fun groupsFile(ctx: Context) = File(ctx.filesDir, "ai_people.bin")

    private fun read(bytes: ByteArray, into: MutableMap<String, Group>) {
        try {
            val d = DataInputStream(bytes.inputStream())
            if (d.readInt() != MAGIC) return
            while (d.available() > 0) {
                val id = d.readUTF(); val seen = d.readInt(); val last = d.readLong(); val q = d.readFloat()
                val thumb = ByteArray(d.readInt()); d.readFully(thumb)
                val n = d.readUnsignedByte()
                val samples = ArrayList<ByteArray>(n)
                for (i in 0 until n) { val s = ByteArray(Faces.DIM); d.readFully(s); samples.add(s) }
                val old = into[id]
                if (old == null || last >= old.last) into[id] = Group(id, seen, last, thumb, q, samples)
            }
        } catch (_: Exception) {}
    }

    private fun write(gs: Collection<Group>): ByteArray {
        val bos = ByteArrayOutputStream()
        val d = DataOutputStream(bos)
        d.writeInt(MAGIC)
        for (g in gs) {
            d.writeUTF(g.id); d.writeInt(g.seen); d.writeLong(g.last); d.writeFloat(g.thumbQ)
            d.writeInt(g.thumb.size); d.write(g.thumb)
            d.writeByte(g.samples.size); for (s in g.samples) d.write(s)
        }
        d.flush()
        return bos.toByteArray()
    }

    @Synchronized
    private fun local(ctx: Context): LinkedHashMap<String, Group> {
        groups?.let { return it }
        val m = LinkedHashMap<String, Group>()
        try { val f = groupsFile(ctx); if (f.exists()) read(f.readBytes(), m) } catch (_: Exception) {}
        groups = m
        return m
    }

    @Synchronized
    private fun saveLocal(ctx: Context) {
        try { val f = groupsFile(ctx); val t = File(f.path + ".tmp"); t.writeBytes(write(local(ctx).values)); t.renameTo(f) } catch (_: Exception) {}
        dirty = true
    }

    /** Merges and removals said by names take effect on the groups themselves (so later faces go to the right one). */
    @Synchronized
    private fun applyNamesToGroups(ctx: Context) {
        val m = local(ctx)
        var changed = false
        for (id in m.keys.toList()) {
            val v = nameMap(ctx, null)[id]?.value ?: continue
            if (v == "!") { m.remove(id); changed = true }
            else if (v.startsWith("=") && v.length > 1) {
                val g = m.remove(id) ?: continue
                val into = m[canon(ctx, null, id)]
                if (into != null) {
                    into.seen += g.seen; into.last = maxOf(into.last, g.last)
                    for (s in g.samples) if (into.samples.size < MAX_SAMPLES) into.samples.add(s)
                    if (g.thumbQ > into.thumbQ) { into.thumb = g.thumb; into.thumbQ = g.thumbQ }
                }
                changed = true
            }
        }
        if (changed) saveLocal(ctx)
    }

    /**
     * A person seen in a clip: [prints] = the fingerprints of their best faces, [thumb] = the best face as a small
     * JPEG, [quality] = how clear it was. Returns the group they belong to (an existing one, or a new one for a clear
     * face), or null when the face is too poor to say.
     */
    @Synchronized
    fun assign(ctx: Context, prints: List<FloatArray>, thumb: ByteArray, quality: Float): String? {
        if (prints.isEmpty()) return null
        facesFound++
        val m = local(ctx)
        var best: Group? = null; var bestScore = -1f
        for (g in m.values) for (s in g.samples) for (p in prints) { val a = Faces.alike(s, p); if (a > bestScore) { bestScore = a; best = g } }
        val now = System.currentTimeMillis()
        val g: Group
        if (best != null && bestScore >= SAME) {
            g = best
            matched++
            g.seen++; g.last = now
            // another look of the same person is kept as a further sample when it differs enough from the ones held
            for (p in prints) {
                if (g.samples.size >= MAX_SAMPLES) break
                if (g.samples.all { Faces.alike(it, p) < 0.85f }) g.samples.add(Faces.pack(p))
            }
            if (quality > g.thumbQ) { g.thumb = thumb; g.thumbQ = quality }
        } else {
            if (quality < NEW_GROUP_QUALITY) return null
            val id = java.util.UUID.randomUUID().toString().replace("-", "").take(10)
            g = Group(id, 1, now, thumb, quality, ArrayList(prints.take(3).map { Faces.pack(it) }))
            m[id] = g
            // keep the list bounded: unnamed people seen once go first, oldest first
            if (m.size > MAX_GROUPS) m.values.filter { it.seen <= 1 && nameOf(ctx, null, it.id) == null }.sortedBy { it.last }
                .take(m.size - MAX_GROUPS).forEach { m.remove(it.id) }
        }
        saveLocal(ctx)
        return g.id
    }

    /**
     * After a batch of clips (the recording phone; network): namings made on other phones are taken in, and this
     * phone's groups are sent to the backup folder if they changed.
     */
    fun afterBatch(ctx: Context) {
        val listed = try { com.motioncam.cloud.CloudSync.aiFileNames(ctx) } catch (_: Throwable) { null }
        if (listed != null) {
            if (takeNames(ctx, null, listed)) applyNamesToGroups(ctx)
            // namings made here that never reached the folder (it was offline then)
            val have = listed.toSet()
            val mine = synchronized(this) { nameMap(ctx, null).map { (id, n) -> "n~$id~${MarkStore.b64(n.value)}~${n.at}" } }
            for (f in mine) if (f !in have) try { com.motioncam.cloud.CloudSync.pushMeaning(ctx, f, byteArrayOf(1)) } catch (_: Throwable) {}
        }
        val bytes = synchronized(this) { if (!dirty) null else write(local(ctx).values) } ?: return
        val ok = try { com.motioncam.cloud.CloudSync.pushMeaning(ctx, "p_${Device.id(ctx).take(8)}.bin", bytes) } catch (_: Throwable) { false }
        if (ok) synchronized(this) { dirty = false }
    }

    @Synchronized fun localCount(ctx: Context): Int = local(ctx).size
    @Synchronized fun localSamples(ctx: Context): Int = local(ctx).values.sumOf { it.samples.size }

    /** Forgets every face on this phone (groups, pictures, names) and takes them out of the backup folder. Network. */
    fun deleteAll(ctx: Context) {
        synchronized(this) {
            local(ctx).clear(); nameMap(ctx, null).clear()
            try { groupsFile(ctx).delete(); namesFile(ctx, null).delete() } catch (_: Exception) {}
            dirty = false
        }
        try { com.motioncam.cloud.CloudSync.clearFaces(ctx, "p_${Device.id(ctx).take(8)}.bin") } catch (_: Throwable) {}
    }

    // ================================================================== a remote folder's people (viewing phones)

    /** Reads the folder's namings (a folder listing, nothing downloaded). Network. */
    fun syncNames(ctx: Context, src: RemoteSource) { sync(ctx, src, false) }

    private fun sync(ctx: Context, src: RemoteSource, withGroups: Boolean) {
        val dir = File(RemoteRepo.cacheDir(ctx, src.id), "people").apply { mkdirs() }
        RemoteSources.reader(ctx, src).use { r ->
            val folder = r.listFolders(src.folderId).firstOrNull { it.name == Meaning.FOLDER } ?: return
            val files = r.list(folder.id)
            takeNames(ctx, src.id, files.map { it.name })
            if (!withGroups) return
            val want = HashSet<String>()
            for (f in files) {
                if (!f.name.startsWith("p_") || !f.name.endsWith(".bin")) continue
                val local = File(dir, f.name.removeSuffix(".bin") + "~" + f.size + "~" + f.modifiedMs + ".bin")
                want += local.name
                if (!local.exists()) r.open(f, 0).use { ins -> local.writeBytes(ins.readBytes()) }
            }
            dir.listFiles()?.forEach { if (it.name !in want) it.delete() }
        }
    }

    /**
     * The people to show for a place, named ones first, then the most seen: this phone's own ([scope] null), or a
     * remote folder's ([refresh] = fetch them now; network).
     */
    fun list(ctx: Context, scope: String?, refresh: Boolean): List<Group> {
        val all: Collection<Group> = if (scope == null) synchronized(this) { ArrayList(local(ctx).values) } else {
            val src = RemoteSources.get(ctx, scope) ?: return emptyList()
            if (refresh) try { sync(ctx, src, true) } catch (_: Exception) {}
            val m = LinkedHashMap<String, Group>()
            File(RemoteRepo.cacheDir(ctx, src.id), "people").listFiles()?.forEach { f -> try { read(f.readBytes(), m) } catch (_: Exception) {} }
            m.values
        }
        return all.filter { !removed(ctx, scope, it.id) }
            .sortedWith(compareBy({ if (nameOf(ctx, scope, it.id) != null) 0 else 1 }, { -it.seen }, { -it.last }))
    }
}
