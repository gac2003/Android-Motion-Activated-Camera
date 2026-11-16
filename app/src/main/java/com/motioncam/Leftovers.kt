package com.motioncam

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

// ============================================================================================================
// TODO-REMOVE (AI leftovers): dead weight once every phone runs 1.1.87 or later and the camera has uploaded a clip. See TODO-REMOVE.md in the project folder for the full list and how to remove it.
// This whole file can be deleted then.
// ============================================================================================================
/**
 * Clears what the removed on-device AI builds (1.1.78 – 1.1.85) left behind on this phone, once: their files and
 * settings, the automatic tags they put on clips ("✦ person", "✦ red top"…), and saved searches that used them.
 * What they left in a backup folder (the same tags, and a ".ai" folder of small files) is hidden everywhere at once
 * (see TagFiles) and removed by the recording phone the next time it uploads (see CloudSync.cleanLeftovers).
 */
object Leftovers {
    /** The mark the automatic tags started with. */
    const val MARK = "✦ "
    private const val DONE = "aiLeftovers1"

    fun clean(ctx: Context) {
        val sp = ctx.getSharedPreferences("motioncam", Context.MODE_PRIVATE)
        if (sp.getBoolean(DONE, false)) return
        try {
            // files and settings
            for (n in listOf("ai_meaning.bin", "ai_people.bin", "ai_names.json")) File(ctx.filesDir, n).delete()
            for (n in listOf("ai", "ai_focus", "ai_clips", "ai_meaning")) try { ctx.deleteSharedPreferences(n) } catch (_: Exception) {}
            val e = sp.edit()
            for (k in listOf("aiFocus", "aiFast", "aiDescribe", "aiChargingOnly", "aiMeaning", "aiFaces", "aiMaxTempC")) e.remove(k)
            e.apply()
            // what was fetched from remote folders
            File(ctx.cacheDir, "remote").listFiles()?.forEach { d -> File(d, "meaning").deleteRecursively(); File(d, "people").deleteRecursively() }
            File(ctx.filesDir, "remote").listFiles()?.forEach { d -> File(d, "ai_names.json").delete(); stripTags(File(d, "tags.json")) }
            // the automatic tags on this phone's clips
            stripTags(File(ctx.filesDir, "tags_local.json"))
            // saved searches that used them
            val filters = ctx.getSharedPreferences("clip_filters", Context.MODE_PRIVATE)
            val fe = filters.edit()
            for ((k, v) in filters.all) {
                val o = try { JSONObject(v as? String ?: continue) } catch (_: Exception) { continue }
                var changed = false
                for (key in listOf("meaning", "meaningFor", "meaningNames")) if (o.has(key)) { o.remove(key); changed = true }
                for (key in listOf("tagsIn", "tagsOut")) {
                    val a = o.optJSONArray(key) ?: continue
                    val keep = JSONArray()
                    for (i in 0 until a.length()) if (!a.optString(i).startsWith(MARK)) keep.put(a.optString(i))
                    if (keep.length() != a.length()) { o.put(key, keep); changed = true }
                }
                if (changed) fe.putString(k, o.toString())
            }
            fe.apply()
        } catch (_: Exception) {}
        sp.edit().putBoolean(DONE, true).apply()
    }

    /** A saved tag list (clip → tags) without the automatic ones. */
    private fun stripTags(f: File) {
        try {
            if (!f.exists()) return
            val o = JSONObject(f.readText())
            val out = JSONObject()
            var changed = false
            for (k in o.keys()) {
                val a = o.getJSONArray(k)
                val keep = JSONArray()
                for (i in 0 until a.length()) if (!a.getString(i).startsWith(MARK)) keep.put(a.getString(i))
                if (keep.length() != a.length()) changed = true
                if (keep.length() > 0) out.put(k, keep)
            }
            if (changed) f.writeText(out.toString())
        } catch (_: Exception) {}
    }
}
