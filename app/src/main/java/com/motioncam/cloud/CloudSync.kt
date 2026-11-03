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
              val addedAt: Long = System.currentTimeMillis())

    class Uploaded(val clipId: Long, val kind: CloudKind, val name: String, val remoteId: String,
                   val size: Long, val at: Long, var pinned: Boolean)

    private const val JOB_ID = 4242
    private val lock = Object()
    private var jobs: MutableList<Job>? = null
    private var index: MutableList<Uploaded>? = null
    @Volatile private var worker: Thread? = null
    @Volatile private var stop = false
    @Volatile private var jobCallback: ((Boolean) -> Unit)? = null
    @Volatile private var netCallbackRegistered = false

    /** What the worker is doing right now (for the UI). */
    @Volatile var activity: String? = null; private set
    @Volatile var activityKind: CloudKind? = null; private set

    private val BACKOFF_NET = longArrayOf(5_000, 15_000, 30_000, 60_000, 120_000, 300_000, 900_000)
    private val BACKOFF_OTHER = longArrayOf(60_000, 300_000, 900_000, 1_800_000, 3_600_000)

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
                    js += Job(o.getLong("clip"), k, o.optInt("attempts"), o.optLong("nextAt"),
                        o.optString("resume").ifEmpty { null }, o.optString("error").ifEmpty { null },
                        o.optBoolean("failed"), o.optLong("added"))
                }
            }
            if (iFile(ctx).exists()) {
                val a = JSONArray(iFile(ctx).readText())
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    val k = CloudKind.of(o.getString("kind")) ?: continue
                    ix += Uploaded(o.getLong("clip"), k, o.getString("name"), o.getString("rid"), o.optLong("size"), o.optLong("at"), o.optBoolean("pinned"))
                }
            }
        } catch (_: Exception) {}
        jobs = js
        index = ix
    }

    private fun save(ctx: Context) {
        val q = JSONArray()
        for (j in jobs ?: return) q.put(JSONObject().put("clip", j.clipId).put("kind", j.kind.key).put("attempts", j.attempts)
            .put("nextAt", j.nextAt).put("resume", j.resume ?: "").put("error", j.error ?: "").put("failed", j.failed).put("added", j.addedAt))
        val ix = JSONArray()
        for (u in index ?: return) ix.put(JSONObject().put("clip", u.clipId).put("kind", u.kind.key).put("name", u.name).put("rid", u.remoteId)
            .put("size", u.size).put("at", u.at).put("pinned", u.pinned))
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
            for (k in kinds) addJobLocked(clipId, k)
            save(ctx)
        }
        kick(ctx)
    }

    private fun addJobLocked(clipId: Long, k: CloudKind): Boolean {
        if (jobs!!.any { it.clipId == clipId && it.kind == k }) return false
        if (index!!.any { it.clipId == clipId && it.kind == k }) return false
        jobs!!.add(Job(clipId, k))
        return true
    }

    /** Queues existing clips newer than [sinceMs] (0 = all). Returns how many were added. */
    fun enqueueExisting(ctx: Context, kind: CloudKind, sinceMs: Long): Int {
        val clips = ClipStore.list(ctx).filter { it.startMs >= sinceMs }.sortedBy { it.startMs }
        var n = 0
        synchronized(lock) {
            load(ctx)
            for (c in clips) if (addJobLocked(c.id, kind)) n++
            save(ctx)
        }
        kick(ctx)
        return n
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
        synchronized(lock) { load(ctx); jobs!!.removeAll { it.kind == kind }; save(ctx) }
    }

    class Counts(val queued: Int, val failed: Int, val uploaded: Int)

    fun counts(ctx: Context, kind: CloudKind): Counts = synchronized(lock) {
        load(ctx)
        Counts(jobs!!.count { it.kind == kind && !it.failed }, jobs!!.count { it.kind == kind && it.failed }, index!!.count { it.kind == kind })
    }

    /** One-line status for the settings screen. */
    fun status(ctx: Context, kind: CloudKind): String {
        val cfg = CloudConfig(ctx, kind)
        if (!cfg.isConfigured) return "Not set up"
        if (!cfg.enabled) return "Off"
        cfg.blocked?.let { return "⚠ $it" }
        val c = counts(ctx, kind)
        if (activityKind == kind && activity != null) return activity!! + if (c.queued > 1) " · ${c.queued - 1} more waiting" else ""
        val parts = ArrayList<String>()
        if (c.queued > 0) parts += "${c.queued} waiting" + (waitReason(ctx)?.let { " ($it)" } ?: "")
        if (c.failed > 0) parts += "${c.failed} failed"
        if (parts.isEmpty()) parts += "Up to date"
        if (cfg.lastOkAt > 0) parts += "last upload ${ago(System.currentTimeMillis() - cfg.lastOkAt)} ago"
        return parts.joinToString(" · ")
    }

    private fun ago(ms: Long): String {
        val s = ms / 1000
        return when { s < 60 -> "${s}s"; s < 3600 -> "${s / 60}m"; s < 86400 -> "${s / 3600}h"; else -> "${s / 86400}d" }
    }

    /** Called from the JobScheduler job: run the worker and report when idle. */
    fun runForJob(ctx: Context, finished: (Boolean) -> Unit) {
        jobCallback = finished
        kick(ctx)
    }

    fun requestStop() { stop = true; synchronized(lock) { lock.notifyAll() } }

    /** Starts the worker (or wakes it up). Cheap; call whenever something may have changed. */
    fun kick(ctx: Context) {
        val app = ctx.applicationContext
        synchronized(lock) {
            stop = false
            val w = worker
            if (w != null && w.isAlive) { lock.notifyAll(); return }
            worker = Thread({ runWorker(app) }, "cloud-sync").also { it.isDaemon = true; it.start() }
        }
    }

    // ------------------------------------------------------------------ conditions

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

    // ------------------------------------------------------------------ worker

    private fun runWorker(ctx: Context) {
        try {
            while (!stop) {
                val now = System.currentTimeMillis()
                val enabled = enabledKinds(ctx).filter { CloudConfig(ctx, it).blocked == null }
                retention(ctx, enabled)
                val next: Job?
                val waitMs: Long
                synchronized(lock) {
                    load(ctx)
                    jobs!!.removeAll { j -> CloudConfig(ctx, j.kind).let { !it.enabled || !it.isConfigured } }
                    val live = jobs!!.filter { !it.failed && it.kind in enabled }
                    next = live.filter { it.nextAt <= now }.minByOrNull { it.addedAt }
                    waitMs = live.minOfOrNull { it.nextAt - now } ?: -1
                }
                if (next == null) {
                    if (waitMs < 0) break                         // nothing left
                    scheduleJob(ctx, waitMs)                       // backstop if the process dies
                    if (waitMs > 15 * 60_000L) break               // far away: let the job wake us
                    synchronized(lock) { lock.wait(waitMs.coerceAtLeast(1000)) }
                    continue
                }
                val why = waitReason(ctx)
                if (why != null) {
                    watchNetwork(ctx)
                    scheduleJob(ctx, 0)                            // JobScheduler waits for the right network/charger
                    synchronized(lock) { lock.wait(60_000) }
                    if (waitReason(ctx) != null) break
                    continue
                }
                process(ctx, next)
            }
        } catch (_: InterruptedException) {
        } catch (_: Exception) {
        } finally {
            activity = null
            activityKind = null
            val pending = synchronized(lock) { jobs?.any { !it.failed } == true }
            if (pending) scheduleJob(ctx, 30_000)
            jobCallback?.invoke(pending)
            jobCallback = null
            synchronized(lock) { if (worker === Thread.currentThread()) worker = null }
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
    private fun folder(cfg: CloudConfig, p: CloudProvider): String {
        cfg.folderId?.let { return it }
        val existing = p.listFolders(null).firstOrNull { it.name == "MotionCam" }
        val f = existing ?: p.createFolder(null, "MotionCam")
        cfg.folderId = f.id
        cfg.folderName = f.name
        return f.id
    }

    private fun process(ctx: Context, job: Job) {
        val cfg = CloudConfig(ctx, job.kind)
        val clip = ClipStore.list(ctx).firstOrNull { it.id == job.clipId }
        if (clip == null) {   // deleted from the phone before it could be uploaded
            synchronized(lock) { jobs!!.remove(job); save(ctx) }
            return
        }
        activityKind = job.kind
        activity = "Preparing ${clip.name}"
        var p: CloudProvider? = null
        try {
            p = provider(ctx, cfg)
            val folder = folder(cfg, p)
            val src = source(ctx, clip)
            ensureSpace(ctx, p, cfg, folder, src.size)
            var sent = 0L
            var lastSave = 0L
            val rf = p.upload(folder, clip.name, src, job.resume,
                saveResume = { r -> synchronized(lock) { job.resume = r; save(ctx) } },
                progress = { n ->
                    if (stop) throw CloudException("Paused", CloudException.Kind.NETWORK)
                    sent += n
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastSave > 500) {
                        lastSave = now
                        activity = "Uploading ${clip.name} · ${(sent * 100 / src.size.coerceAtLeast(1)).coerceAtMost(100)}%"
                    }
                })
            synchronized(lock) {
                jobs!!.remove(job)
                index!!.add(Uploaded(clip.id, job.kind, rf.name, rf.id, rf.size, System.currentTimeMillis(), isProtected(ctx, cfg, clip.id)))
                save(ctx)
            }
            cfg.lastOkAt = System.currentTimeMillis()
            cfg.lastError = null
            cfg.blocked = null
            afterUpload(ctx, clip)
        } catch (e: CloudException) {
            handleError(ctx, cfg, job, e)
        } catch (e: Exception) {
            handleError(ctx, cfg, job, CloudException(e.message ?: e.javaClass.simpleName, CloudException.Kind.OTHER, e))
        } finally {
            try { p?.close() } catch (_: Exception) {}
            activity = null
            activityKind = null
        }
    }

    private fun handleError(ctx: Context, cfg: CloudConfig, job: Job, e: CloudException) {
        val now = System.currentTimeMillis()
        cfg.lastError = e.message
        synchronized(lock) {
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
                    // the backup folder was removed: make a new one next time
                    cfg.folderId = null
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

    private fun source(ctx: Context, clip: Clip): UploadSource {
        val q = Prefs(ctx).cloudQuality
        if (q > 0) {
            val dir = File(ctx.cacheDir, "cloud").apply { mkdirs() }
            val f = File(dir, "clip_${clip.id}_$q.mp4")
            if (!f.exists() || f.length() == 0L) {
                activity = "Compressing ${clip.name}"
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

    // ------------------------------------------------------------------ space & retention

    private fun isProtected(ctx: Context, cfg: CloudConfig, clipId: Long): Boolean =
        cfg.protectedCollections.any { cid -> Collections.get(ctx, cid)?.clipIds?.contains(clipId) == true }

    /** Names in the backup folder that must never be deleted (clips in protected collections). */
    private fun protectedNames(ctx: Context, cfg: CloudConfig): Set<String> = synchronized(lock) {
        load(ctx)
        for (u in index!!) if (u.kind == cfg.kind && !u.pinned && isProtected(ctx, cfg, u.clipId)) u.pinned = true
        save(ctx)
        index!!.filter { it.kind == cfg.kind && it.pinned }.map { it.name }.toSet()
    }

    /** Makes room for [need] bytes according to the policy, or throws QUOTA. */
    private fun ensureSpace(ctx: Context, p: CloudProvider, cfg: CloudConfig, folder: String, need: Long) {
        var files: List<RemoteFile>? = null
        var mustFree = 0L
        if (cfg.maxGb > 0) {
            files = p.list(folder)
            val used = files.filter { isOurClip(it.name) }.sumOf { it.size }
            mustFree = maxOf(mustFree, used + need - cfg.maxGb * 1_000_000_000L)
        }
        val free = try { p.quota(folder)?.free } catch (e: CloudException) { null }
        if (free != null) mustFree = maxOf(mustFree, need + 64_000_000L - free)
        if (mustFree <= 0) return
        if (cfg.fullPolicy == CloudConfig.PAUSE) throw CloudException("Not enough space", CloudException.Kind.QUOTA)
        activity = "Making room in ${cfg.kind.label}"
        val keep = protectedNames(ctx, cfg)
        val candidates = (files ?: p.list(folder)).filter { isOurClip(it.name) && it.name !in keep }.sortedBy { it.name }
        var freed = 0L
        for (f in candidates) {
            if (freed >= mustFree) break
            p.delete(folder, f)
            freed += f.size
            forget(ctx, cfg.kind, f.name)
        }
        if (freed < mustFree) throw CloudException("Full: only protected clips are left", CloudException.Kind.QUOTA)
    }

    private fun forget(ctx: Context, kind: CloudKind, name: String) = synchronized(lock) {
        index!!.removeAll { it.kind == kind && it.name == name }
        save(ctx)
    }

    /** Deletes cloud copies older than the configured number of days (protected clips excepted). */
    private fun retention(ctx: Context, kinds: List<CloudKind>) {
        val now = System.currentTimeMillis()
        for (k in kinds) {
            val cfg = CloudConfig(ctx, k)
            if (cfg.keepDays <= 0 || now - cfg.lastRetentionAt < 6 * 3_600_000L || waitReason(ctx) != null) continue
            cfg.lastRetentionAt = now
            var p: CloudProvider? = null
            try {
                p = provider(ctx, cfg)
                val folder = cfg.folderId ?: continue
                val keep = protectedNames(ctx, cfg)
                val cutoff = now - cfg.keepDays * 86_400_000L
                val fmt = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                for (f in p.list(folder)) {
                    if (!isOurClip(f.name) || f.name in keep) continue
                    val t = try { fmt.parse(f.name.substring(3, 18))?.time } catch (e: Exception) { null } ?: f.modifiedMs
                    if (t in 1 until cutoff) { p.delete(folder, f); forget(ctx, k, f.name) }
                }
            } catch (_: Exception) {
            } finally { try { p?.close() } catch (_: Exception) {} }
        }
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
