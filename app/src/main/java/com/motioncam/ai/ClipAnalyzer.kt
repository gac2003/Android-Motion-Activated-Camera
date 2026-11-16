package com.motioncam.ai

import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.PowerManager
import android.os.SystemClock
import com.motioncam.Clip
import com.motioncam.ClipStore
import com.motioncam.Diagnostics
import com.motioncam.Health
import com.motioncam.TagStore

/**
 * Describes saved clips (people, how long they stayed, colours, objects) after they were recorded. Off unless turned
 * on in Settings; only on the phone that recorded the clip.
 *
 *  - A saved clip joins a waiting list of at most [MAX_QUEUE] clips (the oldest is dropped beyond that, so a busy day
 *    never leaves hours of work).
 *  - Work happens only while the camera is open, the phone is charging (a setting), cool, and no clip is recording.
 *    It is started by the camera's own once-a-second check (no timer of its own), on a low-priority thread that ends
 *    when the list is empty or work has to stop; the model is then freed.
 *  - Per clip: about one picture a second (at most [MAX_FRAMES]), the detector on each, people followed from picture
 *    to picture. A clip interrupted three times, or that can't be read, is dropped.
 *  - The result becomes automatic tags on the clip (see [ClipFacts]); for a clip already backed up they are added to
 *    its folder too (one try now, one more after a later clip if there was no internet).
 */
object ClipAnalyzer {
    const val MAX_QUEUE = 40
    private const val MAX_FRAMES = 60
    private const val COOL_MS = 5 * 60_000L

    private fun sp(ctx: Context) = ctx.getSharedPreferences("ai_clips", Context.MODE_PRIVATE)
    private var queue: ArrayList<Long>? = null
    private var pendingPush: ArrayList<Long>? = null
    private val tries = HashMap<Long, Int>()

    /** Clips waiting (for the camera's cheap once-a-second check and Diagnostics). */
    @Volatile var waiting = -1; private set
    @Volatile var running = false; private set
    /** Why nothing is being described right now (null = it may run). */
    @Volatile var pausedWhy: String? = "not started"; private set
    @Volatile private var done = 0
    @Volatile private var dropped = 0
    @Volatile private var lastSecs = 0f
    @Volatile private var lastFrames = 0
    @Volatile private var lastWarmAt = 0L
    @Volatile private var lastLine = ""
    @Volatile private var learned = 0

    @Synchronized
    private fun load(ctx: Context) {
        if (queue != null) return
        val s = sp(ctx)
        queue = ArrayList((s.getString("queue", "") ?: "").split(',').mapNotNull { it.toLongOrNull() })
        pendingPush = ArrayList((s.getString("push", "") ?: "").split(',').mapNotNull { it.toLongOrNull() })
        waiting = queue!!.size
    }

    @Synchronized
    private fun save(ctx: Context) {
        sp(ctx).edit().putString("queue", queue!!.joinToString(",")).putString("push", pendingPush!!.joinToString(",")).apply()
        waiting = queue!!.size
    }

    /** A clip was saved and kept: it waits to be described (when the setting is on). */
    fun onClipSaved(ctx: Context, clipId: Long) {
        val p = com.motioncam.Prefs(ctx)
        if (!p.aiDescribe && !p.aiFocus) return   // person-aware focus learns from saved clips too
        enqueue(ctx.applicationContext, listOf(clipId))
    }

    @Synchronized
    private fun enqueue(ctx: Context, ids: List<Long>) {
        load(ctx)
        val q = queue!!
        for (id in ids) if (id !in q) q.add(id)
        while (q.size > MAX_QUEUE) { q.removeAt(0); dropped++ }
        save(ctx)
    }

    /** How large things appear in a clip's video (see CameraEngine.videoGeom), kept when it is saved (the newest 300). */
    @Synchronized
    fun geometry(ctx: Context, clipId: Long, g: FloatArray?) {
        if (g == null || g.size < 2) return
        val p = com.motioncam.Prefs(ctx)
        if (!p.aiDescribe && !p.aiFocus) return
        val s = sp(ctx)
        val all = (s.getString("geom", "") ?: "").split(',').filter { it.isNotEmpty() && !it.startsWith("$clipId:") }.takeLast(299)
        s.edit().putString("geom", (all + "$clipId:${g[0]}:${g[1]}").joinToString(",")).apply()
    }

    private fun geometryOf(ctx: Context, clipId: Long): FloatArray? {
        val e = (sp(ctx).getString("geom", "") ?: "").split(',').lastOrNull { it.startsWith("$clipId:") }?.split(':') ?: return null
        val a = e.getOrNull(1)?.toFloatOrNull() ?: return null; val b = e.getOrNull(2)?.toFloatOrNull() ?: return null
        return floatArrayOf(a, b)
    }

    /** Clips picked by hand (Clips › select › More › "Learn focus from these") join the list. */
    fun learnFrom(ctx: Context, ids: List<Long>) = enqueue(ctx.applicationContext, ids)

    /**
     * Settings › "Describe the last 20 clips" / "Learn from the last 20 clips": they join the list (newest last);
     * with [all] false only those without a description yet.
     */
    fun describeRecent(ctx: Context, count: Int, all: Boolean = false, doneCb: (Int) -> Unit) {
        val app = ctx.applicationContext
        Thread {
            val ids = try {
                ClipStore.list(app).sortedByDescending { it.startMs }.take(count)
                    .filter { c -> all || TagStore.tagsOf(app, null, c).none { ClipFacts.isAi(it) } }.map { it.id }.reversed()
            } catch (_: Exception) { emptyList() }
            enqueue(app, ids)
            android.os.Handler(android.os.Looper.getMainLooper()).post { doneCb(ids.size) }
        }.start()
    }

    /**
     * The camera engine's once-a-second check while the camera is open: decides whether describing may run now, and
     * starts the worker when it may and clips are waiting. Cheap when nothing waits.
     */
    fun tick(ctx: Context, on: Boolean, chargingOnly: Boolean, charging: Boolean, tempC: Float, thermal: Int, limitC: Int, recording: Boolean) {
        if (waiting < 0) load(ctx.applicationContext)
        val now = SystemClock.elapsedRealtime()
        // your AI temperature limit decides; Android's own signal only at "severe" (one step before the camera's own pause)
        val severe = thermal >= PowerManager.THERMAL_STATUS_SEVERE
        val warm = severe || (limitC > 0 && tempC >= limitC)
        if (warm) lastWarmAt = now
        val why = when {
            !on -> "turned off"
            recording -> "a clip is recording"
            chargingOnly && !charging -> "waiting for the charger"
            severe -> "Android reports the phone as very hot (battery %.0f °C)".format(tempC)
            warm -> "battery %.0f °C, the AI limit is %d °C".format(tempC, limitC)
            lastWarmAt > 0 && now - lastWarmAt < COOL_MS -> "cooling down"
            else -> null
        }
        pausedWhy = why
        if (why != null || running || waiting <= 0) return
        running = true
        val app = ctx.applicationContext
        Thread({ work(app) }, "ai-clips").apply { priority = Thread.MIN_PRIORITY + 1; isDaemon = true }.start()
    }

    /** A clip started recording: describing stops at once (recording always comes first), not at the next check. */
    fun recordingStarted() { if (running) pausedWhy = "a clip is recording" }

    /** The camera closed: whatever runs stops after the picture it is on. */
    fun cameraClosed() { pausedWhy = "the camera is closed" }

    private fun work(ctx: Context) {
        var det: AiEngine.Detector? = null
        var half: Meaning.Half? = null
        var finder: Faces.Finder? = null
        var pose: AiEngine.Pose? = null
        val prefs = com.motioncam.Prefs(ctx)
        val describeOn = prefs.aiDescribe
        try {
            det = AiEngine.Detector(ctx, "det_l_q.tflite", "cpu", 2)
            // search by meaning: the picture half of that model too (when turned on)
            // person-aware focus: body points, to measure how far away the people in a clip were
            if (prefs.aiFocus) pose = try { AiEngine.Pose(ctx) } catch (t: Throwable) { Health.log(ctx, "AI: body points couldn't start ($t)"); null }
            if (describeOn && prefs.aiMeaning) half = try { Meaning.pictureHalf(ctx) } catch (t: Throwable) { Health.log(ctx, "AI: search by meaning couldn't start ($t)"); null }
            // faces: the face finder and the fingerprint model (when turned on)
            if (describeOn && prefs.aiFaces) finder = try {
                Faces.Finder(ctx.assets.open("ai/face_d.onnx").use { it.readBytes() }, ctx.assets.open("ai/face_p.onnx").use { it.readBytes() }, 2)
            } catch (t: Throwable) { Health.log(ctx, "AI: faces couldn't start ($t)"); null }
            Diagnostics.count("AI clip descriptions: runs")
            while (pausedWhy == null) {
                val id = synchronized(this) { queue!!.firstOrNull() } ?: break
                val finished = try { describe(ctx, det, half, finder, pose, describeOn, id) } catch (t: Throwable) { Health.log(ctx, "AI: a clip couldn't be described ($t)"); true }
                synchronized(this) {
                    if (finished || (tries[id] ?: 0) >= 3) { queue!!.remove(id); tries.remove(id); if (!finished) dropped++ }
                    save(ctx)
                }
                if (!finished) break
            }
            // tags that couldn't reach a backup folder earlier (no internet): one more try
            if (pausedWhy == null) retryPush(ctx)
            // the day's fingerprints go to the backup folder once the list is empty (a few uploads a day, not one a clip)
            if (pausedWhy == null && half != null) try { Meaning.sendDays(ctx) } catch (_: Throwable) {}
            // people: namings made on other phones come in, this phone's groups go to the backup folder
            if (pausedWhy == null && finder != null) try { People.afterBatch(ctx) } catch (_: Throwable) {}
        } catch (t: Throwable) {
            Health.log(ctx, "AI: describing clips stopped ($t)")
            pausedWhy = "couldn't start (${t.javaClass.simpleName})"
        } finally {
            try { det?.close() } catch (_: Throwable) {}
            try { half?.close() } catch (_: Throwable) {}
            try { finder?.close() } catch (_: Throwable) {}
            try { pose?.close() } catch (_: Throwable) {}
            running = false
        }
    }

    /** Describes one clip. False when it had to stop part-way (it stays on the list). */
    /** A face seen on a person in one picture: how clear ([q] = how sure × size), and the face turned upright. */
    private class Shot(val q: Float, val up: IntArray)

    private fun describe(ctx: Context, det: AiEngine.Detector, half: Meaning.Half?, finder: Faces.Finder?, pose: AiEngine.Pose?,
                         describeOn: Boolean, id: Long): Boolean {
        val clip = ClipStore.get(ctx, id) ?: return true   // deleted meanwhile
        tries[id] = (tries[id] ?: 0) + 1
        val t0 = SystemClock.elapsedRealtime()
        val tracker = Tracker()
        val kinds = IntArray(ClipFacts.KINDS.size + 1)
        kinds[0] = ClipFacts.PERSON
        var ki = 1
        for (k in ClipFacts.KINDS.keys) kinds[ki++] = k
        // per object kind: pictures it was in, and where it was first and last (a parked car never moves)
        val seen = HashMap<Int, Int>(); val firstAt = HashMap<Int, FloatArray>(); val lastAt = HashMap<Int, FloatArray>()
        var frames = 0
        var colourful = 0f
        // search by meaning: fingerprints of three moments of the whole picture, and a close-up of the clearest person
        val prints = ArrayList<ByteArray>()
        var closeUp: android.graphics.Bitmap? = null
        var closeUpH = 0f
        // faces: each followed person's clearest faces (at most three), from any moment of the clip
        val shots = HashMap<Tracker.Track, ArrayList<Shot>>()
        var px: IntArray? = null
        // person-aware focus: how far away the nearest person was in each picture (diopters), with the clip's own lens
        val dists = ArrayList<Float>()
        var geom = geometryOf(ctx, id) ?: com.motioncam.EngineHost.peek()?.videoGeom()
        // faces need a larger picture than the detector (a face is small in it)
        val side = if (finder != null) 1280 else 640
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, clip.uri)
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.takeIf { it > 0 } ?: clip.durationMs.coerceAtLeast(1000L)
            val n = (dur / 1000L).toInt().coerceIn(1, MAX_FRAMES)
            val moments = setOf(n / 6, n / 2, (5 * n) / 6)
            for (i in 0 until n) {
                if (pausedWhy != null) return false
                val tMs = ((i + 0.5) * dur / n).toLong()
                // the nearest whole picture in the file (cheap to read), already turned upright and scaled down
                val bmp = try { r.getScaledFrameAtTime(tMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, side, side) } catch (_: Throwable) { null } ?: continue
                frames++
                val found = det.detectBitmap(bmp, kinds)
                val people = found.filter { it[5].toInt() == ClipFacts.PERSON }
                if (half != null) {
                    if (i in moments) try { prints.add(Meaning.fingerprint(half, bmp)) } catch (_: Throwable) {}
                    val big = people.maxByOrNull { it[3] - it[1] }
                    if (big != null && big[3] - big[1] > closeUpH) try {
                        // a square around the person, a little larger than their box, inside the picture
                        val side = (maxOf((big[2] - big[0]) * bmp.width, (big[3] - big[1]) * bmp.height) * 1.2f).toInt().coerceIn(16, minOf(bmp.width, bmp.height))
                        val x = (((big[0] + big[2]) / 2 * bmp.width).toInt() - side / 2).coerceIn(0, bmp.width - side)
                        val y = (((big[1] + big[3]) / 2 * bmp.height).toInt() - side / 2).coerceIn(0, bmp.height - side)
                        val cut = android.graphics.Bitmap.createBitmap(bmp, x, y, side, side)
                        if (cut !== bmp) { closeUp?.recycle(); closeUp = cut; closeUpH = big[3] - big[1] }
                    } catch (_: Throwable) {}
                }
                val tr = tracker.add(tMs / 1000f, people)
                if (pose != null && people.isNotEmpty()) try {
                    val bw = bmp.width; val bh = bmp.height
                    // no lens known for this clip (an old clip, camera closed): a typical phone main camera
                    val g = geom ?: (if (bw >= bh) floatArrayOf(0.75f, 0.75f * bw / bh) else floatArrayOf(0.75f * bh / bw, 0.75f)).also { geom = it }
                    val buf = px?.takeIf { it.size == bw * bh } ?: IntArray(bw * bh).also { px = it }
                    bmp.getPixels(buf, 0, bw, 0, 0, bw, bh)
                    val big = people.maxByOrNull { it[3] - it[1] }!!
                    val ok = pose.runPixels(buf, bw, bh, big)
                    val est = FocusPilot.estimate(if (ok) pose.u else null, if (ok) pose.v else null, if (ok) pose.vis else null, big[1], big[3], g[0], g[1]).first
                    if (!est.isNaN()) dists.add(est)
                } catch (_: Throwable) {}
                if (finder != null && people.isNotEmpty()) try {
                    val bw = bmp.width; val bh = bmp.height
                    val buf = px?.takeIf { it.size == bw * bh } ?: IntArray(bw * bh).also { px = it }
                    bmp.getPixels(buf, 0, bw, 0, 0, bw, bh)
                    for (j in people.indices) {
                        val b = people[j]
                        val w = b[2] - b[0]; val h = b[3] - b[1]
                        // the head is in the upper half of a person's box
                        val f = finder.find(buf, bw, bh, ((b[0] - w * 0.1f) * bw).toInt(), (b[1] * bh).toInt(), ((b[2] + w * 0.1f) * bw).toInt(), ((b[1] + h * 0.5f) * bh).toInt()) ?: continue
                        if (minOf(f.w, f.h) < 24f) continue   // too small to tell anyone apart
                        val list = shots.getOrPut(tr[j]) { ArrayList() }
                        list.add(Shot(f.score * minOf(f.w, f.h), Faces.upright(buf, bw, bh, f.pts)))
                        list.sortByDescending { it.q }
                        while (list.size > 3) list.removeAt(list.size - 1)
                    }
                } catch (_: Throwable) {}
                for (j in people.indices) {
                    val b = people[j]; val k = tr[j]
                    val w = b[2] - b[0]; val h = b[3] - b[1]
                    // chest and thighs: the middle of the box, away from head, hands and background
                    colourful = maxOf(colourful, ClipFacts.vote(bmp, b[0] + w * 0.3f, b[1] + h * 0.2f, b[2] - w * 0.3f, b[1] + h * 0.45f, k.top))
                    if (b[3] < 0.97f) ClipFacts.vote(bmp, b[0] + w * 0.3f, b[1] + h * 0.55f, b[2] - w * 0.3f, b[1] + h * 0.8f, k.bottom)
                }
                for (o in found) {
                    val kind = o[5].toInt()
                    if (kind == ClipFacts.PERSON || o[4] < 0.5f) continue
                    seen[kind] = (seen[kind] ?: 0) + 1
                    if (kind !in firstAt) firstAt[kind] = o
                    lastAt[kind] = o
                }
                bmp.recycle()
                try { Thread.sleep(100) } catch (_: InterruptedException) {}   // unhurried: this is never urgent
            }
        } finally {
            try { r.release() } catch (_: Throwable) {}
            if (pausedWhy != null) { closeUp?.recycle(); closeUp = null }
        }
        if (half != null) {
            closeUp?.let { c -> try { prints.add(Meaning.fingerprint(half, c)) } catch (_: Throwable) {}; c.recycle() }
            try { Meaning.keep(ctx, clip.name, prints) } catch (_: Throwable) {}
        }
        if (frames == 0) return true   // unreadable: nothing to say
        // the usual distance learns from this clip: the middle of where its people were
        if (dists.isNotEmpty()) try {
            dists.sort()
            FocusPilot.learnClip(ctx, clip.name, clip.startMs, dists[dists.size / 2])
            learned++
        } catch (_: Throwable) {}
        if (!describeOn) {   // only learning focus on this phone: no tags
            done++; lastFrames = frames; lastSecs = (SystemClock.elapsedRealtime() - t0) / 1000f
            lastLine = if (dists.isEmpty()) "no person to measure" else "people about %.1f m away".format(1f / dists[dists.size / 2].coerceAtLeast(0.02f))
            return true
        }

        val words = ArrayList<String>()
        val people = tracker.people()
        if (people.isNotEmpty()) {
            words += "person"
            ClipFacts.people(people.size)?.let { words += it }
            words += ClipFacts.stayed(people.maxOf { it.seconds })
            // colours only by daylight (a night picture has none), for the two people seen longest
            if (colourful > 0.08f) for (p in people.take(2)) {
                ClipFacts.winner(p.top)?.let { words += "$it top" }
                ClipFacts.winner(p.bottom)?.let { words += "$it bottom" }
            }
        }
        // who it was: each person's faces against the people known so far (a clear new face starts a new person)
        if (finder != null) for (p in people) {
            val s = shots[p] ?: continue
            try {
                val bmp = android.graphics.Bitmap.createBitmap(s[0].up, Faces.FACE, Faces.FACE, android.graphics.Bitmap.Config.ARGB_8888)
                val jpg = java.io.ByteArrayOutputStream().also { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
                bmp.recycle()
                People.assign(ctx, s.map { finder.print(it.up) }, jpg, s[0].q)?.let { words += "@$it" }
            } catch (_: Throwable) {}
        }
        for ((kind, count) in seen) {
            val a = firstAt[kind]!!; val b = lastAt[kind]!!
            val moved = Math.abs((a[0] + a[2]) - (b[0] + b[2])) / 2 + Math.abs((a[1] + a[3]) - (b[1] + b[3])) / 2
            // in almost every picture and never moving: part of the scene (a parked car), not what the clip is about
            if (frames >= 3 && count * 10 >= frames * 8 && moved < 0.02f) continue
            ClipFacts.KINDS[kind]?.let { words += it }
        }
        if (words.isEmpty()) words += "nothing recognised"
        val tags = ClipFacts.tags(words.distinct())
        val old = TagStore.tagsOf(ctx, null, clip).filter { ClipFacts.isAi(it) }.toSet()
        TagStore.change(ctx, null, listOf(clip), tags, old - tags)
        pushOrKeep(ctx, clip, tags)
        done++
        lastFrames = frames
        lastSecs = (SystemClock.elapsedRealtime() - t0) / 1000f
        lastLine = ClipFacts.line(tags)
        return true
    }

    private fun pushOrKeep(ctx: Context, clip: Clip, tags: Set<String>) {
        val ok = try { com.motioncam.cloud.CloudSync.pushTags(ctx, clip, tags) } catch (_: Throwable) { false }
        synchronized(this) {
            val p = pendingPush!!
            p.remove(clip.id)
            if (!ok) { p.add(clip.id); while (p.size > MAX_QUEUE) p.removeAt(0) }
            save(ctx)
        }
    }

    private fun retryPush(ctx: Context) {
        val ids = synchronized(this) { ArrayList(pendingPush!!) }
        for (id in ids) {
            if (pausedWhy != null) return
            val clip = ClipStore.get(ctx, id)
            val tags = clip?.let { c -> TagStore.tagsOf(ctx, null, c).filter { ClipFacts.isAi(it) }.toSet() } ?: emptySet()
            if (clip == null || tags.isEmpty()) { synchronized(this) { pendingPush!!.remove(id); save(ctx) }; continue }
            val ok = try { com.motioncam.cloud.CloudSync.pushTags(ctx, clip, tags) } catch (_: Throwable) { false }
            if (!ok) return   // still no way to the folder: next time
            synchronized(this) { pendingPush!!.remove(id); save(ctx) }
        }
    }

    /** Settings › "Delete all face data": groups, pictures and names (here and in the backup folder), and the face tags on this phone's clips. */
    fun deleteFaces(ctx: Context, done: () -> Unit) {
        val app = ctx.applicationContext
        Thread {
            try {
                People.deleteAll(app)
                for (c in ClipStore.list(app)) {
                    val t = TagStore.tagsOf(app, null, c).filter { People.isFaceTag(it) }.toSet()
                    if (t.isNotEmpty()) TagStore.change(app, null, listOf(c), emptySet(), t)
                }
            } catch (_: Throwable) {}
            android.os.Handler(android.os.Looper.getMainLooper()).post(done)
        }.start()
    }

    /** Lines for Diagnostics. */
    fun report(ctx: Context, line: (String, Any?) -> Unit) {
        if (waiting < 0) load(ctx.applicationContext)
        line("Clips waiting", "$waiting (at most $MAX_QUEUE; $dropped dropped)")
        line("Working now", if (running) "yes" else "no" + (pausedWhy?.let { " · $it" } ?: ""))
        line("Looked through since start", "$done ($learned taught the usual focus distance)")
        line("Last clip", if (done == 0) "none yet" else "%.0f s for %d pictures · %s".format(lastSecs, lastFrames, lastLine))
        line("Tags waiting for the folder", synchronized(this) { pendingPush?.size ?: 0 })
        if (com.motioncam.Prefs(ctx).aiFaces) line("Faces", "${People.localCount(ctx)} people known (${People.localSamples(ctx)} samples) · " +
            "${People.facesFound} faces since start, ${People.matched} matched someone known")
    }
}
