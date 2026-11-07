package com.motioncam.remote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import com.motioncam.Clip
import com.motioncam.TagStore
import com.motioncam.cloud.CloudKind
import com.motioncam.cloud.CloudProvider
import com.motioncam.cloud.RemoteFile
import com.motioncam.cloud.THUMB_FOLDER
import com.motioncam.cloud.UploadSource
import com.motioncam.cloud.thumbName
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream

/**
 * Moving and copying clips between remote folders, in the background (like the upload queue):
 *  - **On the service** when both folders are in the same Google Drive or Dropbox account: nothing is downloaded.
 *  - Otherwise each clip **streams** from one folder straight into the other (no full download first), 3 at a time.
 * Batches are saved, so they continue after the app is closed or the phone restarts (when MotionCam is next opened).
 * A Move deletes the originals once its batch is done (protected clips are copied and kept). Shown in Transfers and in
 * a notification with progress.
 */
object TransferQueue {
    const val WORKERS = 3

    /** [rename]: the user chose to copy it even though that name is taken there (it gets a free name, e.g. "…_2"). */
    class Item(val clip: String, var state: String = WAIT, var error: String? = null, @Volatile var progress: Int = 0,
               val rename: Boolean = false, var dstName: String? = null)
    class Batch(val id: String, val src: String, val dst: String, val move: Boolean, val created: Long,
                var paused: Boolean = false, var finishedAt: Long = 0, var kept: Int = 0, var note: String? = null,
                val items: MutableList<Item> = ArrayList()) {
        val total get() = items.size
        fun count(st: String) = items.count { it.state == st }
        val active get() = finishedAt == 0L
    }

    const val WAIT = "wait"
    const val RUN = "run"
    const val DONE = "done"       // copied (or already there)
    const val FAIL = "fail"
    const val SKIP = "skip"       // a clip with that name is already there (kept where it is, not deleted by a Move)

    private val batches = ArrayList<Batch>()
    private var loaded = false
    private val lock = Object()
    /** Something changed (main thread not guaranteed). */
    val listeners = java.util.concurrent.CopyOnWriteArraySet<() -> Unit>()
    private fun changed() { for (l in listeners) try { l() } catch (_: Exception) {} }

    private fun file(ctx: Context) = File(ctx.filesDir, "transfers.json")

    private fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        try {
            val a = JSONArray(file(ctx).takeIf { it.exists() }?.readText() ?: "[]")
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val b = Batch(o.getString("id"), o.getString("src"), o.getString("dst"), o.getBoolean("move"), o.getLong("created"),
                    o.optBoolean("paused"), o.optLong("finishedAt"), o.optInt("kept"), o.optString("note").ifEmpty { null })
                val its = o.getJSONArray("items")
                for (j in 0 until its.length()) its.getJSONObject(j).let {
                    // one that was running when the app stopped starts again
                    b.items += Item(it.getString("c"), it.optString("s", WAIT).let { s -> if (s == RUN) WAIT else s }, it.optString("e").ifEmpty { null },
                        rename = it.optBoolean("r"), dstName = it.optString("n").ifEmpty { null })
                }
                batches += b
            }
        } catch (_: Exception) {}
    }

    private fun save(ctx: Context) {
        val a = JSONArray()
        for (b in batches) a.put(JSONObject().put("id", b.id).put("src", b.src).put("dst", b.dst).put("move", b.move).put("created", b.created)
            .put("paused", b.paused).put("finishedAt", b.finishedAt).put("kept", b.kept).put("note", b.note ?: "")
            .put("items", JSONArray().apply { for (it in b.items) put(JSONObject().put("c", it.clip).put("s", it.state).put("e", it.error ?: "").put("r", it.rename).put("n", it.dstName ?: "")) }))
        try { val t = File(file(ctx).path + ".tmp"); t.writeText(a.toString()); t.renameTo(file(ctx)) } catch (_: Exception) {}
    }

    // ------------------------------------------------------------------ the queue

    /** Adds clips to move or copy; returns how many were added (clips already waiting for the same move are skipped). */
    fun enqueue(ctx: Context, src: RemoteSource, dst: RemoteSource, clips: List<Clip>, move: Boolean, rename: Set<String> = emptySet()): Int {
        val app = ctx.applicationContext
        val n = synchronized(lock) {
            load(app)
            val queued = batches.filter { it.active && it.src == src.id && it.dst == dst.id }
                .flatMap { b -> b.items.filter { it.state == WAIT || it.state == RUN }.map { it.clip } }.toHashSet()
            val add = clips.map { it.name }.filter { it !in queued }.distinct()
            if (add.isEmpty()) return@synchronized 0
            val b = Batch("t" + System.currentTimeMillis().toString(36), src.id, dst.id, move, System.currentTimeMillis())
            add.mapTo(b.items) { Item(it, rename = it in rename) }
            batches += b
            save(app)
            add.size
        }
        changed()
        if (n > 0) start(app)
        return n
    }

    class View(val b: Batch, val srcName: String, val dstName: String, val done: Int, val failed: Int, val skipped: Int, val running: Int,
               val waiting: Int, val progress: Int)

    fun views(ctx: Context): List<View> = synchronized(lock) {
        load(ctx)
        batches.sortedByDescending { it.created }.map { b ->
            View(b, RemoteSources.get(ctx, b.src)?.name ?: "a removed folder", RemoteSources.get(ctx, b.dst)?.name ?: "a removed folder",
                b.count(DONE), b.count(FAIL), b.count(SKIP), b.count(RUN), b.count(WAIT),
                b.items.filter { it.state == RUN }.sumOf { it.progress })
        }
    }

    /** Waiting or running items (for the Transfers row and the notification). */
    fun pending(ctx: Context): Int = synchronized(lock) { load(ctx); batches.filter { it.active }.sumOf { it.count(WAIT) + it.count(RUN) } }

    fun setPaused(ctx: Context, id: String, paused: Boolean) {
        synchronized(lock) { batches.firstOrNull { it.id == id }?.paused = paused; save(ctx) }
        changed()
        if (!paused) start(ctx)
    }

    /** Stops a batch: waiting clips are dropped (a Move still deletes the ones already copied). */
    fun cancel(ctx: Context, id: String) {
        synchronized(lock) {
            batches.firstOrNull { it.id == id }?.let { b -> b.items.removeAll { it.state == WAIT }; b.paused = false; b.note = "Stopped" }
            save(ctx)
        }
        changed()
        start(ctx)   // finishes the batch (deleting the moved ones) if nothing is running
    }

    fun retry(ctx: Context, id: String) {
        synchronized(lock) {
            batches.firstOrNull { it.id == id }?.let { b -> b.items.filter { it.state == FAIL }.forEach { it.state = WAIT; it.error = null }; b.finishedAt = 0 }
            save(ctx)
        }
        changed()
        start(ctx)
    }

    fun remove(ctx: Context, id: String) { synchronized(lock) { batches.removeAll { it.id == id && !it.active }; save(ctx) }; changed() }
    fun clearFinished(ctx: Context) { synchronized(lock) { batches.removeAll { !it.active }; save(ctx) }; changed() }

    /** Starts the background service when there is work (call when the app opens, too). */
    fun start(ctx: Context) {
        val work = synchronized(lock) { load(ctx); batches.any { it.active } }
        if (!work) return
        try { ctx.startForegroundService(Intent(ctx, TransferService::class.java)) } catch (_: Exception) {}
    }

    // ------------------------------------------------------------------ workers

    @Volatile private var workers = 0

    internal fun runWorkers(ctx: Context, service: TransferService) {
        synchronized(lock) {
            while (workers < WORKERS) {
                workers++
                Thread({ worker(ctx, service) }, "transfer-$workers").apply { isDaemon = true }.start()
            }
        }
    }

    private class Job(val b: Batch, val item: Item)

    private fun claim(): Job? = synchronized(lock) {
        for (b in batches.sortedBy { it.created }) {
            if (!b.active || b.paused) continue
            val it = b.items.firstOrNull { it.state == WAIT } ?: continue
            it.state = RUN; it.progress = 0
            return Job(b, it)
        }
        null
    }

    /** What a worker keeps between clips: open connections and each batch's target listing. */
    private class Conn {
        val readers = HashMap<String, RemoteReader>()
        val writers = HashMap<String, CloudProvider>()
        fun close() { readers.values.forEach { try { it.close() } catch (_: Exception) {} }; writers.values.forEach { try { it.close() } catch (_: Exception) {} } }
    }
    private val targetNames = java.util.concurrent.ConcurrentHashMap<String, MutableMap<String, Long>>()
    private val thumbFolders = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun worker(ctx: Context, service: TransferService) {
        val conn = Conn()
        try {
            while (true) {
                val j = claim() ?: break
                try { transfer(ctx, conn, j) }
                catch (e: Exception) { synchronized(lock) { j.item.state = FAIL; j.item.error = e.message ?: e.javaClass.simpleName } }
                synchronized(lock) { save(ctx) }
                changed()
                service.update()
                finishBatches(ctx)
            }
        } finally {
            conn.close()
            val last = synchronized(lock) { workers--; workers == 0 }
            if (last) {
                finishBatches(ctx)
                // more work came in meanwhile (or a paused batch is waiting): keep going, else stop
                val more = synchronized(lock) { batches.any { b -> b.active && !b.paused && b.items.any { it.state == WAIT } } }
                if (more) runWorkers(ctx, service) else service.done()
            }
        }
    }

    private fun sameAccount(a: RemoteSource, b: RemoteSource) =
        a.kind == b.kind && (a.kind == CloudKind.DRIVE || a.kind == CloudKind.DROPBOX) && a.mode != RemoteSources.MODE_LINK &&
            !a.account.isNullOrEmpty() && a.account == b.account

    private fun transfer(ctx: Context, conn: Conn, j: Job) {
        val src = RemoteSources.get(ctx, j.b.src) ?: throw IllegalStateException("The folder was removed")
        val dst = RemoteSources.get(ctx, j.b.dst) ?: throw IllegalStateException("The other folder was removed")
        val clip = RemoteRepo.cached(ctx, src).firstOrNull { it.name == j.item.clip } ?: throw IllegalStateException("Not in the folder any more")
        val e = RemoteRepo.entry(clip.id) ?: throw IllegalStateException("Not in the folder any more")
        val w = conn.writers.getOrPut(dst.id) { RemoteSources.writer(ctx, dst) }
        // what's there already (read once per target)
        val names = targetNames.getOrPut(dst.id) {
            java.util.concurrent.ConcurrentHashMap(w.list(dst.folderId).associate { it.name to it.size })
        }
        // a clip with that name already there: left alone unless the user chose to copy it under a new name
        var name = e.name
        if (names.containsKey(name)) {
            if (!j.item.rename) { synchronized(lock) { j.item.state = SKIP; j.item.error = "Already there" }; return }
            name = freeName(e.name, names)
        }
        val same = sameAccount(src, dst)
        var copied: RemoteFile? = null
        if (same) copied = try { w.serverCopy(e.file, dst.folderId, name) } catch (_: Exception) { null }
        if (copied == null) {
            // stream it across: read from the folder while uploading (resumes from where it stopped)
            val r = conn.readers.getOrPut(src.id) { RemoteSources.reader(ctx, src) }
            val size = e.file.size.coerceAtLeast(1)
            val source = object : UploadSource {
                override val size = e.file.size
                override fun open(offset: Long): InputStream = r.open(e.file, offset)
            }
            copied = w.upload(dst.folderId, name, source, null, {}) { sent ->
                if (j.b.paused) throw java.io.IOException("Paused")
                j.item.progress = (sent * 100 / size).toInt().coerceIn(0, 100)
            }
        }
        names[name] = copied.size.takeIf { it > 0 } ?: e.file.size
        j.item.dstName = name
        // its thumbnail, so the other folder shows the right picture without downloading the clip
        try {
            val tf = thumbFolders.getOrPut(dst.id) {
                (w.listFolders(dst.folderId).firstOrNull { it.name == THUMB_FOLDER } ?: w.createFolder(dst.folderId, THUMB_FOLDER)).id
            }
            val tn = thumbName(name, e.durationMs)
            val thumb = e.thumb
            if (same && thumb != null) w.serverCopy(thumb, tf, tn)
            else RemoteRepo.thumbJpeg(ctx, clip.id)?.let { jpg ->
                w.upload(tf, tn, object : UploadSource {
                    override val size = jpg.length()
                    override fun open(offset: Long): InputStream = java.io.FileInputStream(jpg).also { it.channel.position(offset) }
                }, null, {}, {})
            }
        } catch (_: Exception) {}
        synchronized(lock) { j.item.state = DONE; j.item.progress = 100 }
    }

    /** "MC_…_2.mp4", "MC_…_3.mp4"… (the date and time at the start of the name stay the clip's). */
    private fun freeName(name: String, taken: Map<String, Long>): String {
        val base = name.removeSuffix(".mp4")
        var i = 2
        while (taken.containsKey("${base}_$i.mp4")) i++
        return "${base}_$i.mp4"
    }

    /** A batch with nothing left to do: its tags, the Move's deletions, refreshed listings and a notification. */
    private fun finishBatches(ctx: Context) {
        val ready = synchronized(lock) {
            batches.filter { b -> b.active && b.items.none { it.state == WAIT || it.state == RUN } }.onEach { it.finishedAt = -1 }
        }
        for (b in ready) {
            val src = RemoteSources.get(ctx, b.src)
            val dst = RemoteSources.get(ctx, b.dst)
            val okItems = synchronized(lock) { b.items.filter { it.state == DONE }.toList() }
            val ok = okItems.map { it.clip }.toHashSet()
            val newName = okItems.associate { it.clip to (it.dstName ?: it.clip) }
            if (src != null && dst != null && ok.isNotEmpty()) {
                val clips = RemoteRepo.cached(ctx, src).filter { it.name in ok }
                // tags go along (written together, one listing of the folder's tags)
                try { RemoteSources.writer(ctx, dst).use { w ->
                    TagStore.writeTo(ctx, w, dst.folderId, TagStore.byName(ctx, src.id, clips).mapKeys { (k, _) -> newName[k] ?: k })
                } } catch (_: Exception) {}
                if (b.move) try {
                    val d = RemoteRepo.deleteFromCloud(ctx, src, clips) { _, _ -> }
                    b.kept = d.protectedKept
                    if (d.failed > 0) b.note = "Couldn't delete ${d.failed} from ${src.name}: ${d.lastError}"
                } catch (ex: Exception) { b.note = "Couldn't delete from ${src.name}: ${ex.message}" }
                try { RemoteRepo.refresh(ctx, dst) } catch (_: Exception) {}
                com.motioncam.live.Live.nudge(ctx, dst.kind, dst.folderId, "clips")
                TagStore.syncAsync(ctx, dst)
            }
            targetNames.remove(b.dst); thumbFolders.remove(b.dst)
            synchronized(lock) { b.finishedAt = System.currentTimeMillis(); save(ctx) }
            notifyDone(ctx, b, src?.name ?: "the folder", dst?.name ?: "the other folder")
            changed()
        }
    }

    // ------------------------------------------------------------------ notifications

    private const val CHANNEL = "transfers"
    internal const val ONGOING_ID = 4100

    private fun channel(ctx: Context) = ctx.getSystemService(NotificationManager::class.java)
        .createNotificationChannel(NotificationChannel(CHANNEL, "Moving and copying clips", NotificationManager.IMPORTANCE_LOW))

    private fun openTransfers(ctx: Context) = PendingIntent.getActivity(ctx, 4100,
        Intent(ctx, TransfersActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)

    internal fun ongoing(ctx: Context): Notification {
        channel(ctx)
        val vs = views(ctx).filter { it.b.active }
        val total = vs.sumOf { it.b.total }
        val done = vs.sumOf { it.done + it.failed + it.skipped }
        val first = vs.lastOrNull()
        val verb = if (vs.all { it.b.move }) "Moving" else if (vs.none { it.b.move }) "Copying" else "Moving and copying"
        val text = if (first == null) "Finishing…" else "$done of $total · ${first.srcName} → ${first.dstName}" +
            (if (vs.any { it.b.paused }) " · paused" else "")
        return Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload).setContentTitle("$verb clips").setContentText(text)
            .setProgress(total.coerceAtLeast(1), done, total == 0).setOngoing(true).setOnlyAlertOnce(true)
            .setContentIntent(openTransfers(ctx)).build()
    }

    private fun notifyDone(ctx: Context, b: Batch, srcName: String, dstName: String) {
        try {
            channel(ctx)
            val done = b.count(DONE); val failed = b.count(FAIL); val skipped = b.count(SKIP)
            val title = (if (b.move) "Moved " else "Copied ") + "$done clip${if (done == 1) "" else "s"} to $dstName"
            val text = listOfNotNull(
                if (b.move && b.kept > 0) "${b.kept} protected clip${if (b.kept == 1) "" else "s"} also kept in $srcName" else null,
                if (failed > 0) "$failed failed" else null,
                if (skipped > 0) "$skipped already there (left alone)" else null,
                b.note,
            ).joinToString(" · ").ifEmpty { "From $srcName" }
            ctx.getSystemService(NotificationManager::class.java).notify(4200 + (b.id.hashCode() and 0xfff), Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_upload_done).setContentTitle(title).setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text)).setContentIntent(openTransfers(ctx)).setAutoCancel(true).build())
        } catch (_: Exception) {}
    }
}

/** Keeps transfers running with the app in the background, with a progress notification. */
class TransferService : Service() {
    private val ui = android.os.Handler(android.os.Looper.getMainLooper())
    private var lastUpdate = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try { startForeground(TransferQueue.ONGOING_ID, TransferQueue.ongoing(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) }
        catch (_: Exception) { stopSelf(); return START_NOT_STICKY }
        TransferQueue.runWorkers(applicationContext, this)
        ui.post(ticker)
        return START_STICKY
    }

    private val ticker = object : Runnable {
        override fun run() { update(); ui.postDelayed(this, 1500) }
    }

    /** Refreshes the progress notification (at most a few times a second). */
    fun update() {
        val now = System.currentTimeMillis()
        if (now - lastUpdate < 700) return
        lastUpdate = now
        try { getSystemService(NotificationManager::class.java).notify(TransferQueue.ONGOING_ID, TransferQueue.ongoing(this)) } catch (_: Exception) {}
    }

    /** Nothing left to do. */
    fun done() = ui.post {
        ui.removeCallbacks(ticker)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

}
