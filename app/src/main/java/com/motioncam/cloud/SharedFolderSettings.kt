package com.motioncam.cloud

import android.content.Context

/**
 * Backup settings that belong to the folder, not to a phone — the clean-up rules every phone backing up there applies:
 * delete cloud copies after N days, the size cap and what to do when full. They are kept in the folder itself, so
 * every phone uses the same ones:
 *
 *   <folder>/.settings/<key>~<value>~<time>~<base64 device name>
 *
 * One small file per change (the newest per key wins), like shared collections: two phones changing them at the same
 * time never overwrite each other's file. Each phone keeps a copy (its [CloudConfig]) with the time of the value it has,
 * reads the folder's before cleaning up (at most every 10 minutes, or at once when another phone says it changed) and
 * writes its own changes there. Which collections a phone protects, and its upload rules, stay per phone.
 */
object SharedFolderSettings {
    private const val DIR = ".settings"
    val KEYS = listOf("keepDays", "maxGb", "fullPolicy")
    private val lastPull = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun dir(p: CloudProvider, folder: String, create: Boolean): String? =
        p.listFolders(folder).firstOrNull { it.name == DIR }?.id ?: if (create) p.createFolder(folder, DIR).id else null

    private class Entry(val key: String, val value: Int, val at: Long, val file: RemoteFile)

    private fun entries(p: CloudProvider, d: String): List<Entry> = p.list(d).mapNotNull { f ->
        val parts = f.name.split("~")
        if (parts.size < 3 || parts[0] !in KEYS) return@mapNotNull null
        val v = parts[1].toIntOrNull() ?: return@mapNotNull null
        Entry(parts[0], v, parts[2].toLongOrNull() ?: 0L, f)
    }

    private fun value(cfg: CloudConfig, key: String): Int = when (key) { "keepDays" -> cfg.keepDays; "maxGb" -> cfg.maxGb; else -> cfg.fullPolicy }
    private fun setValue(cfg: CloudConfig, key: String, v: Int) { when (key) { "keepDays" -> cfg.keepDays = v; "maxGb" -> cfg.maxGb = v; else -> cfg.fullPolicy = v } }

    /**
     * Takes the folder's settings where they are newer than this phone's copy. A setting the folder doesn't have yet
     * (set up before settings were shared) is written there from this phone. Returns true if anything changed here.
     * Network; skipped if done in the last 10 minutes unless [force].
     */
    fun pull(ctx: Context, cfg: CloudConfig, p: CloudProvider, folder: String, force: Boolean = false): Boolean {
        val now = System.currentTimeMillis()
        if (!force && now - (lastPull[cfg.dest.key] ?: 0) < 10 * 60_000L) return false
        lastPull[cfg.dest.key] = now
        val d = dir(p, folder, false)
        val newest = (if (d != null) entries(p, d) else emptyList()).groupBy { it.key }.mapValues { (_, l) -> l.maxByOrNull { it.at }!! }
        var changed = false
        for (k in KEYS) {
            val e = newest[k]
            val mine = cfg.sharedAt(k)
            if (e != null && e.at > mine) { setValue(cfg, k, e.value); cfg.setSharedAt(k, e.at); changed = true }
            else if (e == null && mine == 0L && value(cfg, k) != default(k)) {
                // this phone's value from before the folder kept settings: becomes the folder's
                try { write(ctx, p, folder, k, value(cfg, k), now); cfg.setSharedAt(k, now) } catch (_: Exception) {}
            }
        }
        return changed
    }

    private fun default(k: String) = when (k) { "fullPolicy" -> CloudConfig.DELETE_OLDEST; else -> 0 }

    private fun write(ctx: Context, p: CloudProvider, folder: String, key: String, v: Int, at: Long) {
        val d = dir(p, folder, true) ?: return
        val name = "$key~$v~$at~" + MarkStore.b64(Device.name(ctx))
        p.upload(d, name, bytesSource(byteArrayOf(1)), null, {}, {})
        // older values of this key are no longer needed
        for (e in entries(p, d)) if (e.key == key && e.at < at) try { p.delete(d, e.file) } catch (_: Exception) {}
    }

    /**
     * A setting changed on this phone: kept here at once, written to the folder in the background, and the other phones
     * are told so they read it now.
     */
    fun set(ctx: Context, dest: Dest, key: String, v: Int) {
        val app = ctx.applicationContext
        val cfg = CloudConfig(app, dest)
        val at = System.currentTimeMillis()
        setValue(cfg, key, v)
        cfg.setSharedAt(key, at)
        val folder = cfg.folderId ?: return
        // typing a number: written once it stops changing for a moment
        val id = dest.key + "/" + key
        pending.remove(id)?.let { main.removeCallbacks(it) }
        val r = Runnable { pending.remove(id); write(app, dest, cfg, folder, key, v, at) }
        pending[id] = r
        main.postDelayed(r, 1500)
    }

    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val pending = HashMap<String, Runnable>()

    private fun write(app: Context, dest: Dest, cfg: CloudConfig, folder: String, key: String, v: Int, at: Long) {
        Thread {
            try {
                CloudSync.providerFor(app, dest).use { p -> write(app, p, folder, key, v, at) }
                com.motioncam.live.Live.nudge(app, cfg.kind, folder, "settings")
            } catch (_: Exception) {}
        }.start()
    }

    /** Another phone changed the folder's settings: read them before the next clean-up. */
    fun changedElsewhere(ctx: Context, dest: Dest) {
        lastPull.remove(dest.key)
        val app = ctx.applicationContext
        Thread {
            try {
                val cfg = CloudConfig(app, dest)
                val folder = cfg.folderId ?: return@Thread
                CloudSync.providerFor(app, dest).use { p -> pull(app, cfg, p, folder, force = true) }
            } catch (_: Exception) {}
        }.start()
    }

    /** Reads the folder's settings now (for the settings screen); [done] runs on a background thread with whether anything changed. */
    fun refresh(ctx: Context, dest: Dest, done: (Boolean) -> Unit) {
        val app = ctx.applicationContext
        Thread {
            val changed = try {
                val cfg = CloudConfig(app, dest)
                val folder = cfg.folderId
                if (folder == null || !cfg.isConfigured) false
                else CloudSync.providerFor(app, dest).use { p -> pull(app, cfg, p, folder, force = true) }
            } catch (_: Exception) { false }
            done(changed)
        }.start()
    }
}
