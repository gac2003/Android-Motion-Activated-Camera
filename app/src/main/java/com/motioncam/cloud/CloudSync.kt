package com.motioncam.cloud

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.SystemClock
import com.motioncam.Clip
import com.motioncam.ClipStore
import com.motioncam.ClipThumbs
import com.motioncam.Collections
import com.motioncam.Prefs
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.InputStream

/**
 * Cloud backup queue. Every finished clip gets one job per enabled destination. A single background worker
 * uploads them one at a time, resuming interrupted uploads and retrying with back-off, so short internet
 * drops only delay uploads. State is saved to disk and survives restarts; a JobScheduler job resumes the work
 * when the app is not running.
 */
object CloudSync {
    class Job(val clipId: Long, val kind: CloudKind, var attempts: Int = 0, var nextAt: Long = 0,
              var resume: String? = null, var error: String? = null, var failed: Boolean = false,
              val addedAt: Long = System.currentTimeMillis(), var seq: Long = addedAt, var paused: Boolean = false) {
        val key get() = "$clipId:${kind.key}"
        // live state (not saved)
        @Volatile var running = false
        @Volatile var abort = false
        @Volatile var progress = -1
        @Volatile var phase: String? = null
    }

    class Uploaded(val clipId: Long, val kind: CloudKind, val name: String, val remoteId: String,
                   val size: Long, val at: Long, var pinned: Boolean, var thumb: Boolean = false)

    /** Read-only view of a job for the queue screen. */
    class JobView(val key: String, val clipId: Long, val kind: CloudKind, val state: String, val progress: Int,
                  val running: Boolean, val paused: Boolean, val failed: Boolean, val error: String?)

    private const val JOB_ID = 4242
    private val lock = Object()
    private var jobs: MutableList<Job>? = null
    private var index: MutableList<Uploaded>? = null
    private val lanes = HashMap<CloudKind, Int>()
    @Volatile private var stop = false
    @Volatile private var jobCallback: ((Boolean) -> Unit)? = null
    @Volatile private var netCallbackRegistered = false
    private val timer by lazy { android.os.Handler(android.os.HandlerThread("cloud-timer").also { it.start() }.looper) }
    private var timerCtx: Context? = null
    private val wake = Runnable { timerCtx?.let { kick(it) } }

    /** Cached listing of each backup folder (for the duplicate check and size cap). */
    private class Listing(val at: Long, val files: java.util.concurrent.ConcurrentHashMap<String, RemoteFile>)
    private val listings = HashMap<CloudKind, Listing>()
    private class FreeSpace(val at: Long, var free: Long?)
    private val freeSpace = HashMap<CloudKind, FreeSpace>()
    private val spaceLocks = CloudKind.values().associateWith { Object() }

    private val BACKOFF_NET = longArrayOf(5_000, 15_000, 30_000, 60_000, 120_000, 300_000, 900_000)
    private val BACKOFF_OTHER = longArrayOf(60_000, 300_000, 900_000, 1_800_000, 3_600_000)

    private fun laneLimit(k: CloudKind) = if (k == CloudKind.FTP || k == CloudKind.SFTP) 2 else 3

    // ------------------------------------------------------------------ persistence

    private fun qFile(ctx: Context) = File(ctx.filesDir, "cloud_queue.json")
    private fun iFile(ctx: Context) = File(ctx.filesDir, "cloud_index.json")

    private fun load(ctx: Context) {
        if (jobs != null) return
        val js = ArrayList<Job>()
        val ix = ArrayList<Uploaded>()
        try {
            if (qFile(ctx).exists()) {
                val a = JSONArray(qFile(ctx).readText())
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    val k = CloudKind.of(o.getString("kind")) ?: continue
                    val added = o.optLong("added")
                    js += Job(o.getLong("clip"), k, o.optInt("attempts"), o.optLong("nextAt"),
                        o.optString("resume").ifEmpty { null }, o.optString("error").ifEmpty { null },
                        o.optBoolean("failed"), added, o.optLong("seq", added), o.optBoolean("paused"))
                }
            }
            if (iFile(ctx).exists()) {
                val a = JSONArray(iFile(ctx).readText())
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    val k = CloudKind.of(o.getString("kind")) ?: continue
                    ix += Uploaded(o.getLong("clip"), k, o.getString("name"), o.getString("rid"), o.optLong("size"), o.optLong("at"), o.optBoolean("pinned"), o.optBoolean("thumb"))
                }
            }
        } catch (_: Exception) {}
        jobs = js
        index = ix
    }

    private fun save(ctx: Context) {
        val q = JSONArray()
        for (j in jobs ?: return) q.put(JSONObject().put("clip", j.clipId).put("kind", j.kind.key).put("attempts", j.attempts)
            .put("nextAt", j.nextAt).put("resume", j.resume ?: "").put("error", j.error ?: "").put("failed", j.failed)
            .put("added", j.addedAt).put("seq", j.seq).put("paused", j.paused))
        val ix = JSONArray()
        for (u in index ?: return) ix.put(JSONObject().put("clip", u.clipId).put("kind", u.kind.key).put("name", u.name).put("rid", u.remoteId)
            .put("size", u.size).put("at", u.at).put("pinned", u.pinned).put("thumb", u.thumb))
        writeAtomic(qFile(ctx), q.toString())
        writeAtomic(iFile(ctx), ix.toString())
    }

    private fun writeAtomic(f: File, s: String) {
        try { val t = File(f.path + ".tmp"); t.writeText(s); t.renameTo(f) } catch (_: Exception) {}
    }

    // ------------------------------------------------------------------ public API

    fun enabledKinds(ctx: Context) = CloudKind.values().filter { CloudConfig(ctx, it).let { c -> c.enabled && c.isConfigured } }

    /** Called when a clip has been saved: queue it for every enabled destination. */
    fun onClipSaved(ctx: Context, clipId: Long) {
        val kinds = enabledKinds(ctx)
        if (kinds.isEmpty()) return
        synchronized(lock) {
            load(ctx)
            for (k in kinds) addJobLocked(ctx, clipId, k)
            save(ctx)
        }
        kick(ctx)
    }

    private fun addJobLocked(ctx: Context, clipId: Long, k: CloudKind): Boolean {
        // a copy saved from someone's remote folder is already in the cloud: never upload it
        if (ClipStore.isSavedCopy(ctx, clipId)) return false
        if (jobs!!.any { it.clipId == clipId && it.kind == k }) return false
        if (index!!.any { it.clipId == clipId && it.kind == k }) return false
        jobs!!.add(Job(clipId, k))
        return true
    }

    /**
     * Brings "what is backed up" in line with what is really in the backup folder: clips deleted from the cloud
     * (e.g. "Delete from cloud" on another phone) are forgotten, so they can be uploaded again. Network; returns how
     * many were forgotten (0 if the folder could not be read — nothing is changed then).
     */
    fun reconcile(ctx: Context, kind: CloudKind): Int {
        val cfg = CloudConfig(ctx, kind)
        val folder = cfg.folderId ?: return 0
        if (!cfg.isConfigured) return 0
        val files = try { providerFor(ctx, kind).use { p -> p.list(folder) } } catch (e: Exception) { return 0 }
        val names = files.map { it.name }.toHashSet()
        synchronized(lock) {
            load(ctx)
            listings[kind] = Listing(System.currentTimeMillis(), java.util.concurrent.ConcurrentHashMap(files.associateBy { it.name }))
            val before = index!!.size
            index!!.removeAll { it.kind == kind && it.name !in names }
            val gone = before - index!!.size
            if (gone > 0) save(ctx)
            return gone
        }
    }

    /** [reconcile] for every set-up destination, in the background. */
    fun reconcileAsync(ctx: Context) {
        val app = ctx.applicationContext
        Thread { for (k in CloudKind.values()) try { reconcile(app, k) } catch (_: Exception) {} }.start()
    }

    /** Queues existing clips newer than [sinceMs] (0 = all), oldest first. Returns how many were added. */
    fun enqueueExisting(ctx: Context, kind: CloudKind, sinceMs: Long): Int {
        reconcile(ctx, kind)   // clips deleted from the cloud meanwhile are uploaded again
        val clips = ClipStore.list(ctx).filter { it.startMs >= sinceMs }.sortedBy { it.startMs }
        var n = 0
        synchronized(lock) {
            load(ctx)
            var seq = (jobs!!.maxOfOrNull { it.seq } ?: System.currentTimeMillis()) + 1
            for (c in clips) if (addJobLocked(ctx, c.id, kind)) { jobs!!.last().seq = seq++; n++ }
            save(ctx)
        }
        kick(ctx)
        return n
    }

    class EnqueueResult(val queued: Int, val already: Int, val savedCopies: Int)

    /** Queues these clips for every enabled destination ("Back up all"); already backed up / queued ones are skipped. */
    fun enqueueClips(ctx: Context, clipIds: List<Long>): EnqueueResult {
        val kinds = enabledKinds(ctx)
        for (k in kinds) reconcile(ctx, k)   // clips deleted from the cloud meanwhile are uploaded again
        var queued = 0; var already = 0; var saved = 0
        synchronized(lock) {
            load(ctx)
            var seq = (jobs!!.maxOfOrNull { it.seq } ?: System.currentTimeMillis()) + 1
            for (id in clipIds) {
                if (ClipStore.isSavedCopy(ctx, id)) { saved++; continue }
                var any = false
                for (k in kinds) if (addJobLocked(ctx, id, k)) { jobs!!.last().seq = seq++; any = true }
                if (any) queued++ else already++
            }
            save(ctx)
        }
        kick(ctx)
        return EnqueueResult(queued, already, saved)
    }

    /** Clears failures / waits and tries again now. */
    fun retryNow(ctx: Context, kind: CloudKind? = null) {
        synchronized(lock) {
            load(ctx)
            for (j in jobs!!) if (kind == null || j.kind == kind) { j.failed = false; j.nextAt = 0; j.attempts = 0 }
            save(ctx)
        }
        for (k in CloudKind.values()) if (kind == null || k == kind) CloudConfig(ctx, k).blocked = null
        kick(ctx)
    }

    /** Removes queued jobs for a destination (when it is disabled or disconnected). */
    fun dropJobs(ctx: Context, kind: CloudKind) {
        synchronized(lock) {
            load(ctx)
            jobs!!.filter { it.kind == kind }.forEach { it.abort = true }
            jobs!!.removeAll { it.kind == kind }
            save(ctx)
        }
    }

    // ---- queue screen

    fun snapshot(ctx: Context): List<JobView> = synchronized(lock) {
        load(ctx)
        val now = System.currentTimeMillis()
        val globalPause = Prefs(ctx).cloudPaused
        jobs!!.sortedWith(compareBy({ !it.running }, { it.seq })).map { j ->
            val state = when {
                j.running -> j.phase ?: "Uploading"
                j.failed -> "Failed — ${j.error ?: "error"}"
                j.paused -> "Paused"
                globalPause -> "Paused (all uploads)"
                CloudConfig(ctx, j.kind).blocked != null -> CloudConfig(ctx, j.kind).blocked!!
                j.nextAt > now -> "Retrying in ${ago(j.nextAt - now)}" + (j.error?.let { " — $it" } ?: "")
                else -> "Waiting"
            }
            JobView(j.key, j.clipId, j.kind, state, if (j.running) j.progress else -1, j.running, j.paused, j.failed, j.error)
        }
    }

    /** Queue order (by key) for display/reordering, highest priority first. */
    fun order(ctx: Context): List<String> = synchronized(lock) { load(ctx); jobs!!.sortedBy { it.seq }.map { it.key } }

    fun setPaused(ctx: Context, keys: Collection<String>, paused: Boolean) {
        synchronized(lock) {
            load(ctx)
            for (j in jobs!!) if (j.key in keys) {
                j.paused = paused
                if (paused && j.running) j.abort = true      // stops after the current chunk; resumes later
                if (!paused) { j.nextAt = 0; j.failed = false; j.attempts = 0 }
            }
            save(ctx)
        }
        kick(ctx)
    }

    fun cancel(ctx: Context, keys: Collection<String>) {
        val removed = synchronized(lock) {
            load(ctx)
            val r = jobs!!.filter { it.key in keys }
            r.forEach { it.abort = true }
            jobs!!.removeAll(r.toSet())
            save(ctx)
            r
        }
        // tidy up partial uploads on servers that keep them (FTP/SFTP ".part")
        Thread {
            for (j in removed) {
                val cfg = CloudConfig(ctx, j.kind)
                val folder = cfg.folderId ?: continue
                val clip = ClipStore.get(ctx, j.clipId) ?: continue
                try { provider(ctx, cfg).use { it.discardPartial(folder, clip.name) } } catch (_: Exception) {}
            }
        }.start()
    }

    fun retry(ctx: Context, keys: Collection<String>) {
        synchronized(lock) {
            load(ctx)
            for (j in jobs!!) if (j.key in keys) { j.failed = false; j.paused = false; j.nextAt = 0; j.attempts = 0 }
            save(ctx)
        }
        kick(ctx)
    }

    /** Applies a new order (keys highest priority first); jobs not listed keep their relative order after. */
    fun reorder(ctx: Context, keysInOrder: List<String>) {
        synchronized(lock) {
            load(ctx)
            val pos = keysInOrder.withIndex().associate { it.value to it.index.toLong() }
            val rest = jobs!!.filter { it.key !in pos }.sortedBy { it.seq }
            for (j in jobs!!) pos[j.key]?.let { j.seq = it }
            rest.forEachIndexed { i, j -> j.seq = keysInOrder.size.toLong() + i }
            save(ctx)
        }
    }

    fun moveToTop(ctx: Context, keys: Collection<String>) {
        val o = order(ctx)
        reorder(ctx, o.filter { it in keys } + o.filter { it !in keys })
    }

    fun moveToBottom(ctx: Context, keys: Collection<String>) {
        val o = order(ctx)
        reorder(ctx, o.filter { it !in keys } + o.filter { it in keys })
    }

    fun setAllPaused(ctx: Context, paused: Boolean) {
        Prefs(ctx).cloudPaused = paused
        if (paused) synchronized(lock) { load(ctx); jobs!!.filter { it.running }.forEach { it.abort = true } }
        kick(ctx)
    }

    class Counts(val queued: Int, val failed: Int, val uploaded: Int)

    fun counts(ctx: Context, kind: CloudKind?): Counts = synchronized(lock) {
        load(ctx)
        val js = jobs!!.filter { kind == null || it.kind == kind }
        Counts(js.count { !it.failed }, js.count { it.failed }, index!!.count { kind == null || it.kind == kind })
    }

    /** One-line status for the settings screens. */
    fun status(ctx: Context, kind: CloudKind): String {
        val cfg = CloudConfig(ctx, kind)
        if (!cfg.isConfigured) return "Not set up"
        if (!cfg.enabled) return "Off"
        cfg.blocked?.let { return "⚠ $it" }
        if (Prefs(ctx).cloudPaused) return "Paused (all uploads)"
        val c = counts(ctx, kind)
        val running = synchronized(lock) { jobs!!.count { it.kind == kind && it.running } }
        val parts = ArrayList<String>()
        if (running > 0) parts += "Uploading $running"
        val waiting = c.queued - running
        if (waiting > 0) parts += "$waiting waiting" + (if (running == 0) waitReason(ctx)?.let { " ($it)" } ?: "" else "")
        if (c.failed > 0) parts += "${c.failed} failed"
        if (parts.isEmpty()) parts += "Up to date"
        if (cfg.lastOkAt > 0) parts += "last upload ${ago(System.currentTimeMillis() - cfg.lastOkAt)} ago"
        return parts.joinToString(" · ")
    }

    private fun ago(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return when { s < 60 -> "${s}s"; s < 3600 -> "${s / 60}m"; s < 86400 -> "${s / 3600}h"; else -> "${s / 86400}d" }
    }

    /** Called from the JobScheduler job: run the workers and report when idle. */
    fun runForJob(ctx: Context, finished: (Boolean) -> Unit) {
        jobCallback = finished
        kick(ctx)
        synchronized(lock) { if (lanes.values.sum() == 0) { jobCallback = null; finished(jobs?.any { !it.failed && !it.paused } == true) } }
    }

    fun requestStop() { stop = true; synchronized(lock) { jobs?.filter { it.running }?.forEach { it.abort = true } } }

    // ------------------------------------------------------------------ scheduling

    /**
     * Starts upload lanes where there is work (up to 3 per cloud service, 2 per FTP/SFTP server).
     * Cheap; call whenever something may have changed.
     */
    fun kick(ctx: Context) {
        val app = ctx.applicationContext
        if (Prefs(app).cloudPaused) return
        val now = System.currentTimeMillis()
        val enabled = enabledKinds(app).filter { CloudConfig(app, it).blocked == null }
        synchronized(lock) {
            load(app)
            stop = false
            var nextWake = Long.MAX_VALUE
            for (k in enabled) {
                val pending = jobs!!.filter { it.kind == k && !it.failed && !it.paused && !it.running }
                val due = pending.count { it.nextAt <= now }
                pending.filter { it.nextAt > now }.minOfOrNull { it.nextAt }?.let { nextWake = minOf(nextWake, it) }
                val retentionDue = retentionDue(app, k) || thumbsDue(app, k)
                val active = lanes[k] ?: 0
                val want = minOf(laneLimit(k), due + active).coerceAtLeast(if (retentionDue && active == 0) 1 else 0)
                for (i in active until want) {
                    lanes[k] = (lanes[k] ?: 0) + 1
                    Thread({ lane(app, k) }, "cloud-${k.key}-$i").apply { isDaemon = true }.start()
                }
            }
            if (nextWake != Long.MAX_VALUE) {
                val delay = (nextWake - now).coerceAtLeast(1000)
                timerCtx = app
                timer.removeCallbacks(wake)
                timer.postDelayed(wake, delay)
                scheduleJob(app, delay)   // backstop if the process is killed
            }
        }
    }

    private fun waitReason(ctx: Context): String? {
        val p = Prefs(ctx)
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        if (caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return "no internet"
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return "internet not working yet (Wi-Fi login page?)"
        if (p.cloudWifiOnly && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) return "waiting for Wi-Fi"
        if (p.cloudChargingOnly && !charging(ctx)) return "waiting for charger"
        return null
    }

    private fun charging(ctx: Context): Boolean =
        (ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0

    private fun watchNetwork(ctx: Context) {
        if (netCallbackRegistered) return
        netCallbackRegistered = true
        try {
            ctx.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(n: Network, c: NetworkCapabilities) { if (c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) kick(ctx) }
                override fun onAvailable(n: Network) { kick(ctx) }
            })
        } catch (_: Exception) { netCallbackRegistered = false }
    }

    private fun scheduleJob(ctx: Context, delayMs: Long) {
        try {
            val p = Prefs(ctx)
            val info = JobInfo.Builder(JOB_ID, ComponentName(ctx, CloudJobService::class.java))
                .setRequiredNetworkType(if (p.cloudWifiOnly) JobInfo.NETWORK_TYPE_UNMETERED else JobInfo.NETWORK_TYPE_ANY)
                .setRequiresCharging(p.cloudChargingOnly)
                .setMinimumLatency(delayMs.coerceAtLeast(0))
                .setPersisted(true)
                .build()
            ctx.getSystemService(JobScheduler::class.java).schedule(info)
        } catch (_: Exception) {}
    }

    // ------------------------------------------------------------------ worker lanes

    /** Next job for this service: due, not paused, not taken by another lane; highest priority first. */
    private fun claim(ctx: Context, k: CloudKind): Job? = synchronized(lock) {
        load(ctx)
        if (Prefs(ctx).cloudPaused || CloudConfig(ctx, k).blocked != null) return null
        val now = System.currentTimeMillis()
        val j = jobs!!.filter { it.kind == k && !it.failed && !it.paused && !it.running && it.nextAt <= now }.minByOrNull { it.seq }
        j?.running = true
        j?.abort = false
        j?.progress = 0
        j
    }

    private fun lane(ctx: Context, k: CloudKind) {
        var p: CloudProvider? = null
        try {
            val cfg = CloudConfig(ctx, k)
            if (retentionDue(ctx, k)) { p = provider(ctx, cfg); retention(ctx, cfg, p) }
            while (!stop) {
                if (waitReason(ctx) != null) { watchNetwork(ctx); scheduleJob(ctx, 0); break }
                val job = claim(ctx, k)
                if (job == null) {
                    // nothing to upload: add thumbnails for clips backed up before thumbnails existed
                    if (!thumbsDue(ctx, k)) break
                    if (p == null) p = try { provider(ctx, cfg) } catch (e: Exception) { break }
                    if (!backfillThumb(ctx, cfg, p!!)) break
                    continue
                }
                if (p == null) {
                    p = try { provider(ctx, cfg) } catch (e: Exception) {
                        val ce = e as? CloudException ?: CloudException(e.message ?: "Cannot connect", CloudException.Kind.OTHER, e)
                        handleError(ctx, cfg, job, ce)
                        job.running = false
                        break
                    }
                }
                val healthy = process(ctx, job, cfg, p!!)
                if (!healthy) { try { p.close() } catch (_: Exception) {}; p = null }   // reconnect next time
            }
        } catch (_: Exception) {
        } finally {
            try { p?.close() } catch (_: Exception) {}
            val idle = synchronized(lock) {
                lanes[k] = ((lanes[k] ?: 1) - 1).coerceAtLeast(0)
                lanes.values.sum() == 0
            }
            if (idle) {
                val pending = synchronized(lock) { jobs?.any { !it.failed && !it.paused } == true }
                jobCallback?.invoke(pending)
                jobCallback = null
            }
            if (!stop) kick(ctx)   // schedules the next wake-up / more lanes if needed
        }
    }

    private fun provider(ctx: Context, cfg: CloudConfig): CloudProvider = when (cfg.kind) {
        CloudKind.DRIVE -> DriveProvider({ GoogleAuth.token(ctx, cfg.account) }, { GoogleAuth.invalidate(ctx, it) })
        CloudKind.DROPBOX -> DropboxProvider(DropboxProvider.APP_KEY,
            cfg.refreshToken ?: throw CloudException("Dropbox not connected", CloudException.Kind.AUTH))
        CloudKind.FTP -> FtpProvider(cfg.host, cfg.port, cfg.user, cfg.password ?: "", cfg.tls)
        CloudKind.SFTP -> SftpProvider(cfg.host, cfg.port, cfg.user,
            if (cfg.useKey) null else cfg.password, if (cfg.useKey) cfg.privateKey else null, cfg.passphrase,
            cfg.hostKey) { cfg.hostKey = it }
    }

    /** Provider for a destination with the current settings (used by the settings screen too). */
    fun providerFor(ctx: Context, kind: CloudKind): CloudProvider = provider(ctx, CloudConfig(ctx, kind))

    /** Backup folder, created ("MotionCam") on first use if the user did not pick one. */
    private fun folder(cfg: CloudConfig, p: CloudProvider): String = synchronized(spaceLocks[cfg.kind]!!) {
        cfg.folderId?.let { return it }
        val existing = p.listFolders(null).firstOrNull { it.name == "MotionCam" }
        val f = existing ?: p.createFolder(null, "MotionCam")
        cfg.folderId = f.id
        cfg.folderName = f.name
        f.id
    }

    /** Folder contents, fetched at most every 5 minutes and kept up to date as we upload/delete. */
    private fun listing(cfg: CloudConfig, p: CloudProvider, folder: String): MutableMap<String, RemoteFile> {
        synchronized(lock) {
            listings[cfg.kind]?.let { if (System.currentTimeMillis() - it.at < 5 * 60_000L) return it.files }
        }
        // network call outside the queue lock (the queue screen must never wait for it)
        val files = java.util.concurrent.ConcurrentHashMap(p.list(folder).associateBy { it.name })
        synchronized(lock) { listings[cfg.kind] = Listing(System.currentTimeMillis(), files) }
        return files
    }

    /** Uploads one clip. Returns false if the connection should be re-made. */
    private fun process(ctx: Context, job: Job, cfg: CloudConfig, p: CloudProvider): Boolean {
        val clip = ClipStore.get(ctx, job.clipId)
        if (clip == null) {   // deleted from the phone before it could be uploaded
            synchronized(lock) { jobs!!.remove(job); save(ctx) }
            return true
        }
        job.phase = "Preparing"
        try {
            val folder = folder(cfg, p)
            // already in the cloud folder (e.g. uploaded before a reinstall)? then don't upload it again
            val existing = listing(cfg, p, folder)[clip.name]
            if (existing != null && existing.size > 0) {
                done(ctx, job, cfg, clip, existing, uploadThumb(ctx, cfg, p, folder, clip))
                return true
            }
            val src = source(ctx, clip, job)
            job.phase = "Uploading"
            ensureSpace(ctx, p, cfg, folder, src.size)
            var sent = 0L
            val rf = p.upload(folder, clip.name, src, job.resume,
                saveResume = { r -> synchronized(lock) { job.resume = r; save(ctx) } },
                progress = { n ->
                    if (stop || job.abort) throw CloudException("Paused", CloudException.Kind.NETWORK)
                    sent += n
                    job.progress = (sent * 100 / src.size.coerceAtLeast(1)).toInt().coerceIn(0, 100)
                })
            job.phase = "Thumbnail"
            done(ctx, job, cfg, clip, rf, uploadThumb(ctx, cfg, p, folder, clip))
            synchronized(lock) { freeSpace[cfg.kind]?.let { fs -> fs.free = fs.free?.minus(rf.size) } }
            return true
        } catch (e: CloudException) {
            handleError(ctx, cfg, job, e)
            return e.kind != CloudException.Kind.NETWORK
        } catch (e: Exception) {
            handleError(ctx, cfg, job, CloudException(e.message ?: e.javaClass.simpleName, CloudException.Kind.OTHER, e))
            return false
        } finally {
            job.running = false
            job.phase = null
            job.progress = -1
        }
    }

    private fun done(ctx: Context, job: Job, cfg: CloudConfig, clip: Clip, rf: RemoteFile, thumb: Boolean) {
        synchronized(lock) {
            jobs!!.remove(job)
            index!!.removeAll { it.clipId == clip.id && it.kind == job.kind }
            index!!.add(Uploaded(clip.id, job.kind, rf.name, rf.id, rf.size, System.currentTimeMillis(), isProtected(ctx, cfg, clip.id), thumb))
            listings[cfg.kind]?.files?.put(rf.name, rf)
            save(ctx)
        }
        cfg.lastOkAt = System.currentTimeMillis()
        cfg.lastError = null
        cfg.blocked = null
        // a clip that should be kept (protected collection / by hand): mark it in the cloud right away
        val space = MarkSync.backupKey(cfg.kind)
        if (isProtected(ctx, cfg, clip.id) || clip.name in MarkSync.manual(ctx, space)) MarkSync.schedule(ctx, space)
        cfg.folderId?.let { com.motioncam.live.Live.nudge(ctx, cfg.kind, it, "clips") }   // viewing phones refresh now
        afterUpload(ctx, clip)
    }

    private fun handleError(ctx: Context, cfg: CloudConfig, job: Job, e: CloudException) {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            if (job !in jobs!!) return       // cancelled while uploading
            if (job.abort && (job.paused || stop || Prefs(ctx).cloudPaused)) { save(ctx); return }   // paused by the user
            cfg.lastError = e.message
            job.error = e.message
            when (e.kind) {
                CloudException.Kind.AUTH -> {
                    cfg.blocked = "Sign-in problem — reconnect in Settings → Cloud backup"
                    job.nextAt = now + 30 * 60_000L
                    notify(ctx, cfg.kind, "${cfg.kind.label}: sign-in needed", e.message ?: "")
                }
                CloudException.Kind.QUOTA -> {
                    cfg.blocked = "${cfg.kind.label} is full" + if (cfg.fullPolicy == CloudConfig.PAUSE) " — uploads paused" else " and nothing more can be deleted"
                    job.nextAt = now + 60 * 60_000L
                    notify(ctx, cfg.kind, "${cfg.kind.label} is full", "New clips are waiting on the phone. Free up space or change the backup settings.")
                }
                CloudException.Kind.NOT_FOUND -> {
                    cfg.folderId = null            // the backup folder was removed: make a new one next time
                    listings.remove(cfg.kind)
                    job.nextAt = now + 5_000
                    job.attempts++
                }
                CloudException.Kind.NETWORK -> {
                    job.nextAt = now + BACKOFF_NET[job.attempts.coerceAtMost(BACKOFF_NET.size - 1)]
                    job.attempts++
                }
                CloudException.Kind.OTHER -> {
                    job.nextAt = now + BACKOFF_OTHER[job.attempts.coerceAtMost(BACKOFF_OTHER.size - 1)]
                    job.attempts++
                    if (job.attempts >= 8) job.failed = true
                }
            }
            save(ctx)
        }
    }

    // ------------------------------------------------------------------ sources & compression

    private fun source(ctx: Context, clip: Clip, job: Job): UploadSource {
        val q = Prefs(ctx).cloudQuality
        if (q > 0) {
            val dir = File(ctx.cacheDir, "cloud").apply { mkdirs() }
            val f = File(dir, "clip_${clip.id}_$q.mp4")
            if (!f.exists() || f.length() == 0L) {
                job.phase = "Compressing"
                val ok = Transcoder.shrink(ctx, clip.uri, f, if (q == 1) 720 else 480, if (q == 1) 2_500_000 else 1_200_000)
                if (!ok) f.delete()
            }
            if (f.exists() && f.length() > 0) return fileSource(f)
        }
        val size = ctx.contentResolver.openFileDescriptor(clip.uri, "r")?.use { it.statSize }
            ?: throw CloudException("Cannot read clip", CloudException.Kind.OTHER)
        return object : UploadSource {
            override val size = size
            override fun open(offset: Long): InputStream {
                val pfd = ctx.contentResolver.openFileDescriptor(clip.uri, "r") ?: throw CloudException("Cannot read clip", CloudException.Kind.OTHER)
                val fis = FileInputStream(pfd.fileDescriptor)
                fis.channel.position(offset)
                return object : FilterInputStream(fis) { override fun close() { super.close(); pfd.close() } }
            }
        }
    }

    private fun fileSource(f: File) = object : UploadSource {
        override val size = f.length()
        override fun open(offset: Long): InputStream = FileInputStream(f).also { it.channel.position(offset) }
    }

    /** Clean-up after a successful upload: compressed copy, and optionally the phone copy. */
    private fun afterUpload(ctx: Context, clip: Clip) {
        val stillQueued = synchronized(lock) { jobs!!.any { it.clipId == clip.id } }
        if (stillQueued) return
        File(ctx.cacheDir, "cloud").listFiles()?.filter { it.name.startsWith("clip_${clip.id}_") }?.forEach { it.delete() }
        if (!Prefs(ctx).cloudDeleteLocal) return
        val everywhere = enabledKinds(ctx).all { k -> synchronized(lock) { index!!.any { it.clipId == clip.id && it.kind == k } } }
        val inCollection = Collections.all(ctx).any { clip.id in it.clipIds }
        if (everywhere && !inCollection) ClipStore.delete(ctx, clip)
    }

    // ------------------------------------------------------------------ thumbnails for viewers

    /** Per destination: thumbnail folder id and its contents (clip name -> thumbnail file). */
    private class Thumbs(val parent: String, val folder: String, val at: Long, val byClip: java.util.concurrent.ConcurrentHashMap<String, RemoteFile>)
    private val thumbs = HashMap<CloudKind, Thumbs>()
    /** Index entries tried this session by the back-fill (so a failing one is not retried in a loop). */
    private val thumbTried = HashSet<String>()

    private fun thumbsFor(cfg: CloudConfig, p: CloudProvider, folder: String, create: Boolean): Thumbs? {
        synchronized(lock) {
            thumbs[cfg.kind]?.let { if (it.parent == folder && System.currentTimeMillis() - it.at < 30 * 60_000L) return it }
        }
        val f = p.listFolders(folder).firstOrNull { it.name == THUMB_FOLDER }
            ?: if (create) p.createFolder(folder, THUMB_FOLDER) else return null
        val map = java.util.concurrent.ConcurrentHashMap<String, RemoteFile>()
        for (rf in p.list(f.id)) parseThumbName(rf.name)?.let { map[it.first] = rf }
        val t = Thumbs(folder, f.id, System.currentTimeMillis(), map)
        synchronized(lock) { thumbs[cfg.kind] = t }
        return t
    }

    /**
     * Uploads the clip's peak-motion thumbnail (a ~20 KB JPEG) into the ".thumbnails" subfolder, so devices
     * viewing this folder as a remote folder see the right frame without downloading the video. The clip's
     * length is part of the name. Best effort: returns false if it could not be done now.
     */
    private fun uploadThumb(ctx: Context, cfg: CloudConfig, p: CloudProvider, folder: String, clip: Clip): Boolean {
        if (!Prefs(ctx).cloudThumbs) return false
        return try {
            val t = synchronized(spaceLocks[cfg.kind]!!) { thumbsFor(cfg, p, folder, create = true) } ?: return false
            if (t.byClip.containsKey(clip.name)) return true
            val bmp = ClipThumbs.load(ctx, clip) ?: return false
            val bytes = java.io.ByteArrayOutputStream().also { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
            val dur = clip.durationMs.takeIf { it > 0 } ?: probeDuration(ctx, clip)
            val rf = p.upload(t.folder, thumbName(clip.name, dur), bytesSource(bytes), null, {}, {})
            t.byClip[clip.name] = rf
            true
        } catch (e: Exception) { false }
    }

    private fun probeDuration(ctx: Context, clip: Clip): Long {
        val r = android.media.MediaMetadataRetriever()
        return try { r.setDataSource(ctx, clip.uri); r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L }
        catch (e: Exception) { 0L } finally { try { r.release() } catch (_: Exception) {} }
    }

    private fun thumbsDue(ctx: Context, k: CloudKind): Boolean {
        if (!Prefs(ctx).cloudThumbs) return false
        val cfg = CloudConfig(ctx, k)
        if (!cfg.enabled || !cfg.isConfigured || cfg.folderId == null) return false
        return synchronized(lock) { load(ctx); index!!.any { it.kind == k && !it.thumb && "${it.clipId}:${k.key}" !in thumbTried } }
    }

    /** One thumbnail for a clip uploaded earlier without one. Returns false when there is nothing to do. */
    private fun backfillThumb(ctx: Context, cfg: CloudConfig, p: CloudProvider): Boolean {
        val u = synchronized(lock) {
            index!!.firstOrNull { it.kind == cfg.kind && !it.thumb && "${it.clipId}:${cfg.kind.key}" !in thumbTried }
                ?.also { thumbTried += "${it.clipId}:${cfg.kind.key}" }
        } ?: return false
        val folder = cfg.folderId ?: return false
        val clip = ClipStore.get(ctx, u.clipId)
        val ok = if (clip == null) true   // no longer on the phone: nothing to make it from
        else uploadThumb(ctx, cfg, p, folder, clip)
        if (ok) synchronized(lock) { u.thumb = true; save(ctx) }
        return true
    }

    private fun deleteThumb(cfg: CloudConfig, p: CloudProvider, folder: String, clipName: String) {
        try {
            val t = thumbsFor(cfg, p, folder, create = false) ?: return
            val rf = t.byClip.remove(clipName) ?: return
            p.delete(t.folder, rf)
        } catch (_: Exception) {}
    }

    /** The backup folder (created if needed), for sharing it from the settings screen. */
    fun backupFolder(ctx: Context, kind: CloudKind): RemoteFolder {
        val cfg = CloudConfig(ctx, kind)
        val id = providerFor(ctx, kind).use { folder(cfg, it) }
        return RemoteFolder(id, cfg.folderName ?: "MotionCam")
    }

    // ------------------------------------------------------------------ space & retention

    private fun isProtected(ctx: Context, cfg: CloudConfig, clipId: Long): Boolean =
        cfg.protectedCollections.any { cid -> Collections.get(ctx, cid)?.clipIds?.contains(clipId) == true }

    /** Names in the backup folder that must never be deleted (clips in protected collections). */
    /**
     * Names in the backup folder that must never be deleted: clips protected in the cloud by any device (this
     * phone's protected collections and hand-picked clips, other phones' collections and picks), minus those
     * whose protection someone removed. Brings this phone's own marks up to date first. If the marks cannot be
     * read, this throws, so nothing is deleted.
     */
    private fun protectedNames(ctx: Context, cfg: CloudConfig, p: CloudProvider, folder: String, shared: CollectionFiles): Set<String> {
        migratePinned(ctx, cfg)
        val existing = listing(cfg, p, folder).keys.filter { isOurClip(it) }.toSet()
        // clips in the shared collections other phones keep in this folder count as protected too
        val r = MarkSync.sync(ctx, MarkSync.backupKey(cfg.kind), MarkStore.forProvider(p, folder), existing, MarkSync.sharedMarks(shared.load()))
        // a clip whose protection was just removed is kept 24 h more, so phones that had it in a collection
        // have time to save a copy before the clean-up may delete it
        val grace = System.currentTimeMillis() - 24 * 3_600_000L
        return r.marks.values.filter { it.protected || it.overrideAt > grace }.map { it.clip }.toSet()
    }

    /** Clips pinned by protected collections before cloud marks existed stay protected (now as marks). */
    private fun migratePinned(ctx: Context, cfg: CloudConfig) {
        val sp = ctx.getSharedPreferences("cloud_${cfg.kind.key}", Context.MODE_PRIVATE)
        if (sp.getBoolean("marksMigrated", false)) return
        val names = synchronized(lock) { load(ctx); index!!.filter { it.kind == cfg.kind && it.pinned }.map { it.name } }
        MarkSync.seedSticky(ctx, MarkSync.backupKey(cfg.kind), names, "Protected collection")
        sp.edit().putBoolean("marksMigrated", true).apply()
    }

    /**
     * Makes room for [need] bytes according to the policy, or throws QUOTA. Serialised per destination so
     * parallel uploads never delete more than necessary. Free space is cached for a minute.
     */
    private fun ensureSpace(ctx: Context, p: CloudProvider, cfg: CloudConfig, folder: String, need: Long) =
        synchronized(spaceLocks[cfg.kind]!!) {
            var mustFree = 0L
            if (cfg.maxGb > 0) {
                val used = listing(cfg, p, folder).values.filter { isOurClip(it.name) }.sumOf { it.size }
                mustFree = maxOf(mustFree, used + need - cfg.maxGb * 1_000_000_000L)
            }
            val now = System.currentTimeMillis()
            val cached = synchronized(lock) { freeSpace[cfg.kind]?.takeIf { now - it.at < 60_000L } }
            val free = cached?.free ?: (try { p.quota(folder)?.free } catch (e: CloudException) { null }).also {
                synchronized(lock) { freeSpace[cfg.kind] = FreeSpace(now, it) }
            }
            if (free != null) mustFree = maxOf(mustFree, need + 64_000_000L - free)
            if (mustFree <= 0) return@synchronized
            if (cfg.fullPolicy == CloudConfig.PAUSE) throw CloudException("Not enough space", CloudException.Kind.QUOTA)
            val shared = CollectionFiles(p, folder, true)
            val keep = protectedNames(ctx, cfg, p, folder, shared)
            val marks = MarkStore.forProvider(p, folder)
            val candidates = listing(cfg, p, folder).values.toList()
                .filter { isOurClip(it.name) && it.name !in keep }.sortedBy { it.name }
            var freed = 0L
            for (f in candidates) {
                if (freed >= mustFree) break
                p.delete(folder, f)
                freed += f.size
                forget(ctx, cfg.kind, f.name)
                deleteThumb(cfg, p, folder, f.name)
                try { marks.forget(f.name) } catch (_: Exception) {}
                try { shared.forgetClip(f.name) } catch (_: Exception) {}   // no collection entries pointing at nothing
            }
            synchronized(lock) { freeSpace.remove(cfg.kind) }
            if (freed < mustFree) throw CloudException("Full: only protected clips are left", CloudException.Kind.QUOTA)
        }

    private fun forget(ctx: Context, kind: CloudKind, name: String) = synchronized(lock) {
        index!!.removeAll { it.kind == kind && it.name == name }
        listings[kind]?.files?.remove(name)
        save(ctx)
    }

    private fun retentionDue(ctx: Context, k: CloudKind): Boolean {
        val cfg = CloudConfig(ctx, k)
        return cfg.enabled && cfg.isConfigured && cfg.keepDays > 0 && cfg.folderId != null &&
            System.currentTimeMillis() - cfg.lastRetentionAt > 6 * 3_600_000L
    }

    /** Deletes cloud copies older than the configured number of days (protected clips excepted). */
    private fun retention(ctx: Context, cfg: CloudConfig, p: CloudProvider) {
        val now = System.currentTimeMillis()
        if (waitReason(ctx) != null) return
        cfg.lastRetentionAt = now
        try {
            val folder = cfg.folderId ?: return
            val shared = CollectionFiles(p, folder, true)
            val keep = protectedNames(ctx, cfg, p, folder, shared)
            val marks = MarkStore.forProvider(p, folder)
            val cutoff = now - cfg.keepDays * 86_400_000L
            val fmt = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
            synchronized(spaceLocks[cfg.kind]!!) {
                for (f in p.list(folder)) {
                    if (!isOurClip(f.name) || f.name in keep) continue
                    val t = try { fmt.parse(f.name.substring(3, 18))?.time } catch (e: Exception) { null } ?: f.modifiedMs
                    if (t in 1 until cutoff) {
                        p.delete(folder, f); forget(ctx, cfg.kind, f.name); deleteThumb(cfg, p, folder, f.name)
                        try { marks.forget(f.name) } catch (_: Exception) {}
                        try { shared.forgetClip(f.name) } catch (_: Exception) {}
                    }
                }
            }
        } catch (_: Exception) {}
    }

    // ------------------------------------------------------------------ notifications

    private fun notify(ctx: Context, kind: CloudKind, title: String, text: String) {
        try {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("cloud", "Cloud backup", NotificationManager.IMPORTANCE_DEFAULT))
            val open = PendingIntent.getActivity(ctx, 40 + kind.ordinal,
                Intent(ctx, CloudActivity::class.java).putExtra(CloudActivity.EXTRA_KIND, kind.key), PendingIntent.FLAG_IMMUTABLE)
            nm.notify(300 + kind.ordinal, Notification.Builder(ctx, "cloud")
                .setSmallIcon(android.R.drawable.stat_notify_error).setContentTitle(title).setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text)).setContentIntent(open).setAutoCancel(true).build())
        } catch (_: Exception) {}
    }
}
