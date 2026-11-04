package com.motioncam.cloud

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.security.SecureRandom

/**
 * "Protected in the cloud" marks: clips any device signed in to the account wants kept, so the camera
 * phone's clean-up (retention, "delete oldest when full") never deletes them. Every device can see all marks
 * and anyone can remove them ("override"), including marks that come from another phone's collection.
 *
 * Google Drive keeps the marks as hidden app properties on the clip itself. Dropbox, FTP and SFTP keep them as
 * tiny files in a ".keep" subfolder whose names carry the details.
 */

/** One device's reasons for keeping a clip. [collections] = names of its collections the clip is in. */
class Mark(val device: String, val deviceName: String, val manual: Boolean, val collections: List<String>, val at: Long) {
    fun sameReasons(o: Mark?) = o != null && o.manual == manual && o.collections == collections
}

/** Protection state of one clip. */
class ClipMarks(val clip: String) {
    val marks = ArrayList<Mark>()
    /** When someone last removed the protection (0 = never) and who. */
    var overrideAt = 0L
    var overrideBy: String? = null
    val protected get() = marks.isNotEmpty()
}

/** This device, as shown to the other devices. */
object Device {
    fun id(ctx: Context): String {
        val sp = ctx.getSharedPreferences("device", Context.MODE_PRIVATE)
        sp.getString("id", null)?.let { return it }
        val id = ByteArray(5).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        sp.edit().putString("id", id).apply()
        return id
    }

    /** The name set in the phone's settings ("Galaxy S20 FE"), else the model. */
    fun name(ctx: Context): String = try {
        android.provider.Settings.Global.getString(ctx.contentResolver, "device_name")
    } catch (e: Exception) { null }?.takeIf { it.isNotBlank() } ?: android.os.Build.MODEL ?: "Phone"
}

/** Where a folder's marks live. All calls block (network). */
interface MarkStore {
    /** False for a read-only Dropbox viewer link: marks can be seen but not changed. */
    val writable: Boolean
    /** Every clip that has marks or an override. */
    fun load(): Map<String, ClipMarks>
    /** Sets this device's mark on [clip] (null = remove it). */
    fun put(clip: String, device: String, mark: Mark?)
    /** Removes every device's marks on [clip] and records the override. */
    fun override(clip: String, byName: String, at: Long)
    /** Someone protected the clip again after an override. */
    fun clearOverride(clip: String)
    /** The clip itself was deleted: drop what is left about it. */
    fun forget(clip: String) {}

    companion object {
        fun forProvider(p: CloudProvider, folder: String): MarkStore = when (p) {
            is DriveProvider -> DriveMarks(p, folder)
            else -> FileMarks(p, folder, writable = p !is DropboxLinkProvider)
        }

        // shared encoding of a mark's details
        internal const val SEP = '\u001F'
        internal fun b64(s: String) = Base64.encodeToString(s.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        internal fun unb64(s: String) = try { String(Base64.decode(s, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING), Charsets.UTF_8) } catch (e: Exception) { "" }

        /** Keeps a string within [max] UTF-8 bytes (cutting whole characters). */
        internal fun fit(s: String, max: Int): String {
            if (s.toByteArray(Charsets.UTF_8).size <= max) return s
            var t = s
            while (t.isNotEmpty() && t.toByteArray(Charsets.UTF_8).size > max) t = t.dropLast(1)
            return t
        }

        /** "name␟col1␟col2…" within [max] bytes (drops collections that don't fit, shortens long names). */
        internal fun details(m: Mark, max: Int): String {
            val parts = ArrayList<String>()
            parts += fit(m.deviceName, 40)
            for (c in m.collections) {
                val next = (parts + fit(c, 40)).joinToString(SEP.toString())
                if (next.toByteArray(Charsets.UTF_8).size > max) break
                parts += fit(c, 40)
            }
            return parts.joinToString(SEP.toString())
        }

        internal fun parseDetails(s: String): Pair<String, List<String>> {
            val p = s.split(SEP)
            return Pair(p.firstOrNull() ?: "", p.drop(1))
        }
    }
}

/**
 * Google Drive: app properties on the clip file (hidden, only visible to MotionCam, covered by drive.file):
 * "mk_<device>" = "<time>|<m or ->|<device name␟collections…>" and "ovr" = "<time>|<device name>".
 * Each property is limited to 124 bytes (key + value).
 */
class DriveMarks(private val p: DriveProvider, private val folder: String) : MarkStore {
    override val writable = true
    private var ids = HashMap<String, String>()   // clip name -> file id

    override fun load(): Map<String, ClipMarks> {
        val out = HashMap<String, ClipMarks>()
        val ids2 = HashMap<String, String>()
        for (f in p.list(folder)) {
            if (!isOurClip(f.name)) continue
            ids2[f.name] = f.id
            if (f.props.isEmpty()) continue
            val cm = ClipMarks(f.name)
            for ((k, v) in f.props) {
                if (k.startsWith("mk_")) {
                    val parts = v.split("|", limit = 3)
                    if (parts.size < 3) continue
                    val (name, cols) = MarkStore.parseDetails(parts[2])
                    cm.marks += Mark(k.removePrefix("mk_"), name, parts[1] == "m", cols, parts[0].toLongOrNull() ?: 0)
                } else if (k == "ovr") {
                    cm.overrideAt = v.substringBefore("|").toLongOrNull() ?: 0
                    cm.overrideBy = v.substringAfter("|", "").ifEmpty { null }
                }
            }
            if (cm.marks.isNotEmpty() || cm.overrideAt > 0) out[f.name] = cm
        }
        ids = ids2
        return out
    }

    private fun id(clip: String): String? {
        ids[clip]?.let { return it }
        load()
        return ids[clip]
    }

    override fun put(clip: String, device: String, mark: Mark?) {
        val fid = id(clip) ?: return
        val key = "mk_$device"
        val v: Any = if (mark == null) JSONObject.NULL else {
            val head = "${mark.at}|${if (mark.manual) "m" else "-"}|"
            head + MarkStore.details(mark, 124 - key.length - head.length)
        }
        p.setAppProperties(fid, JSONObject().put(key, v))
    }

    override fun override(clip: String, byName: String, at: Long) {
        val fid = id(clip) ?: return
        val o = JSONObject()
        val current = p.appProperties(fid)
        for (k in current.keys()) if (k.startsWith("mk_")) o.put(k, JSONObject.NULL)
        o.put("ovr", "$at|" + MarkStore.fit(byName, 100))
        p.setAppProperties(fid, o)
    }

    override fun clearOverride(clip: String) {
        val fid = id(clip) ?: return
        p.setAppProperties(fid, JSONObject().put("ovr", JSONObject.NULL))
    }
}

/**
 * Dropbox / FTP / SFTP: one tiny file per mark in "<folder>/.keep":
 *   mark:     <clip without .mp4>~m~<device>~<time>~<m or ->~<base64 details>
 *   override: <clip without .mp4>~o~<time>~<base64 device name>
 */
class FileMarks(private val p: CloudProvider, private val folder: String, override val writable: Boolean) : MarkStore {
    private var keepFolder: String? = null
    private var files: MutableList<RemoteFile>? = null

    private fun keep(create: Boolean): String? {
        keepFolder?.let { return it }
        val f = p.listFolders(folder).firstOrNull { it.name == KEEP }
            ?: if (create && writable) p.createFolder(folder, KEEP) else return null
        keepFolder = f.id
        return f.id
    }

    private fun files(): MutableList<RemoteFile> {
        files?.let { return it }
        val k = keep(false)
        val l = if (k == null) ArrayList() else p.list(k).toMutableList()
        files = l
        return l
    }

    override fun load(): Map<String, ClipMarks> {
        files = null
        val out = HashMap<String, ClipMarks>()
        for (f in files()) {
            val parts = f.name.split("~")
            if (parts.size < 4) continue
            val clip = parts[0] + ".mp4"
            val cm = out.getOrPut(clip) { ClipMarks(clip) }
            when {
                parts[1] == "m" && parts.size >= 6 -> {
                    val (name, cols) = MarkStore.parseDetails(MarkStore.unb64(parts[5]))
                    cm.marks += Mark(parts[2], name, parts[4] == "m", cols, parts[3].toLongOrNull() ?: 0)
                }
                parts[1] == "o" -> {
                    val at = parts[2].toLongOrNull() ?: 0
                    if (at > cm.overrideAt) { cm.overrideAt = at; cm.overrideBy = MarkStore.unb64(parts[3]).ifEmpty { null } }
                }
            }
        }
        // two writes racing could leave two marks of one device: the newest counts
        for (cm in out.values) {
            val newest = cm.marks.groupBy { it.device }.mapValues { (_, v) -> v.maxByOrNull { it.at }!! }.values
            cm.marks.clear(); cm.marks.addAll(newest)
        }
        return out.filterValues { it.marks.isNotEmpty() || it.overrideAt > 0 }
    }

    private fun base(clip: String) = clip.removeSuffix(".mp4")

    private fun remove(predicate: (RemoteFile) -> Boolean) {
        val k = keep(false) ?: return
        val l = files()
        for (f in l.filter(predicate)) {
            try { p.delete(k, f) } catch (e: CloudException) { if (e.kind != CloudException.Kind.NOT_FOUND) throw e }
            l.remove(f)
        }
    }

    private fun add(name: String) {
        val k = keep(true) ?: return
        files().add(p.upload(k, name, bytesSource(byteArrayOf(1)), null, {}, {}))
    }

    override fun put(clip: String, device: String, mark: Mark?) {
        check(writable) { "read-only" }
        val prefix = "${base(clip)}~m~$device~"
        remove { it.name.startsWith(prefix) }
        if (mark != null) {
            val head = "$prefix${mark.at}~${if (mark.manual) "m" else "-"}~"
            // file names: keep well under the usual 255-byte limit
            val budget = ((230 - head.toByteArray(Charsets.UTF_8).size) * 3 / 4).coerceAtLeast(16)
            add(head + MarkStore.b64(MarkStore.details(mark, budget)))
        }
    }

    override fun override(clip: String, byName: String, at: Long) {
        check(writable) { "read-only" }
        val b = base(clip)
        remove { it.name.startsWith("$b~m~") || it.name.startsWith("$b~o~") }
        add("$b~o~$at~" + MarkStore.b64(MarkStore.fit(byName, 60)))
    }

    override fun clearOverride(clip: String) {
        check(writable) { "read-only" }
        val b = base(clip)
        remove { it.name.startsWith("$b~o~") }
    }

    override fun forget(clip: String) {
        if (!writable) return
        val b = base(clip)
        remove { it.name.startsWith("$b~") }
    }

    companion object { const val KEEP = ".keep" }
}
