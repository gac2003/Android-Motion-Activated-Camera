package com.motioncam.live

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A small picture of each clip (its peak-motion frame, ~240 px, ~10 KB) for the viewing phones' alerts and Activity:
 *
 *   cams/<camera>/clipThumbs/<clip name without .mp4> = { t, pin, img }
 *
 * The camera writes one per saved clip (before announcing the clip, so it's there when the alert arrives) and keeps
 * 7 days of them, like its events. Viewing phones fetch one only when they show it, and keep it in their cache, so a
 * picture is downloaded once per phone. Locked with the camera's PIN when its live view needs the PIN.
 */
object ClipThumbStore {
    private const val KEEP_DAYS = 7
    private val mem = LruCache<String, Bitmap>(80)
    private val missing = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val main = Handler(Looper.getMainLooper())
    /** Downloads at most 3 at a time (a long Activity list doesn't fire off a request per row at once). */
    private val pool = java.util.concurrent.Executors.newFixedThreadPool(3)
    /** Pictures are small and opaque: half the memory of the default format. */
    private val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }

    private fun key(name: String) = name.removeSuffix(".mp4").replace('.', '_')

    // ------------------------------------------------------------------ camera side

    /** The last few clips' pictures at push size (the push carries a small copy: see [inline]). */
    private val forPush = object : LinkedHashMap<String, Bitmap>() {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Bitmap>?) = size > 4
    }

    /**
     * [name]'s picture as small as needed to fit in a push ([budget] characters once sealed; tries smaller sizes and
     * lower quality until it fits), sealed like the stored one, or null (none, or it won't fit). Locked with the PIN
     * when the stored pictures are. A locked phone shows it without connecting at all.
     */
    fun inline(ctx: Context, name: String, budget: Int): String? {
        val bmp = synchronized(forPush) { forPush[name] } ?: return null
        val pin = CameraLink.pinFor(ctx)
        for (w in intArrayOf(192, 160, 128, 96)) {
            val sc = if (bmp.width > w) Bitmap.createScaledBitmap(bmp, w, (bmp.height * w / bmp.width).coerceAtLeast(1), true) else bmp
            for (q in intArrayOf(50, 38)) {
                val out = java.io.ByteArrayOutputStream()
                sc.compress(Bitmap.CompressFormat.JPEG, q, out)
                val body = if (pin != null) CameraLink.pinSeal(CameraLink.me(ctx), pin, out.toByteArray()) else out.toByteArray()
                val sealed = LiveNet.sealBytes(ctx, body)
                if (sealed.length <= budget) return sealed
            }
        }
        return null
    }

    /** A picture that came inside the push ([inline]), or null (none, or locked with a PIN this phone doesn't know). */
    fun openInline(ctx: Context, cam: String, sealed: String?, pinned: Boolean): Bitmap? = try {
        var bytes = LiveNet.openBytes(ctx, sealed)
        if (bytes != null && pinned) bytes = com.motioncam.cloud.SecretStore.get(ctx, "live.pin.$cam")?.let { CameraLink.pinOpen(cam, it, bytes!!) }
        bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size, opts) }
    } catch (_: Exception) { null }

    /** Uploads [bmp] as [name]'s picture and drops pictures older than a week (network; background thread). */
    fun publish(ctx: Context, name: String, bmp: Bitmap) {
        val out = java.io.ByteArrayOutputStream()
        val w = 240
        val sc = if (bmp.width > w) Bitmap.createScaledBitmap(bmp, w, (bmp.height * w / bmp.width).coerceAtLeast(1), true) else bmp
        try {
            val pw = 192
            val small = if (sc.width > pw) Bitmap.createScaledBitmap(sc, pw, (sc.height * pw / sc.width).coerceAtLeast(1), true) else sc
            synchronized(forPush) { forPush[name] = small }
        } catch (_: Exception) {}
        sc.compress(Bitmap.CompressFormat.JPEG, 60, out)
        val pin = CameraLink.pinFor(ctx)
        val body = if (pin != null) CameraLink.pinSeal(CameraLink.me(ctx), pin, out.toByteArray()) else out.toByteArray()
        val ref = CameraLink.path(ctx, "clipThumbs") ?: return
        val task = ref.child(key(name)).setValue(mapOf("t" to ServerValue.TIMESTAMP, "pin" to (pin != null), "img" to LiveNet.sealBytes(ctx, body)))
        // wait so the picture is there before the clip's alert goes out (on a slow upload up to 10 s; the phones also wait for a late one)
        try { com.google.android.gms.tasks.Tasks.await(task, 10, TimeUnit.SECONDS) } catch (_: Exception) {}
        // clip names start with their date and time, so older keys sort first
        val cut = "MC_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
            .format(java.util.Date(System.currentTimeMillis() - KEEP_DAYS * 86_400_000L))
        ref.orderByKey().endAt(cut).limitToFirst(50).addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) { for (c in s.children) c.key?.let { ref.child(it).removeValue() } }
            override fun onCancelled(e: DatabaseError) {}
        })
    }

    // ------------------------------------------------------------------ viewing side

    private fun file(ctx: Context, cam: String, name: String) =
        File(File(ctx.cacheDir, "clipthumbs").apply { mkdirs() }, (cam + "_" + key(name)).replace(Regex("[^A-Za-z0-9_\\-]"), "_") + ".jpg")

    /** Already on this phone (memory or cache), without the network. */
    fun cached(ctx: Context, cam: String, name: String): Bitmap? {
        val k = "$cam/$name"
        mem.get(k)?.let { return it }
        val f = file(ctx, cam, name)
        if (!f.exists()) return null
        return BitmapFactory.decodeFile(f.path, opts)?.also { mem.put(k, it) }
    }

    /**
     * The picture, fetched if needed; waits up to [waitMs]. Background threads only. Null when the camera didn't make
     * one (older version, older than a week) or this phone doesn't know the PIN it is locked with. [forAlert]: waits
     * for a picture still on its way instead of one read; [reached] is set once the database answered at all.
     */
    fun get(ctx: Context, cam: String, name: String, waitMs: Long = 8000, forAlert: Boolean = false,
            reached: java.util.concurrent.atomic.AtomicBoolean? = null): Bitmap? {
        cached(ctx, cam, name)?.let { return it }
        val k = "$cam/$name"
        // don't ask again and again for a picture that isn't there (an alert asks anyway: it may just be late)
        if (!forAlert && System.currentTimeMillis() - (missing[k] ?: 0L) < 5 * 60_000L) return null
        val ref = Live.ref(ctx, "cams/$cam/clipThumbs/${key(name)}") ?: return null
        var snap: DataSnapshot? = null
        val done = CountDownLatch(1)
        // an alert waits for the picture to arrive (the camera's upload of it may still be on its way); else one read
        val l = object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) { reached?.set(true); if (!forAlert || s.child("img").exists()) { snap = s; done.countDown() } }
            override fun onCancelled(e: DatabaseError) { done.countDown() }
        }
        if (forAlert) ref.addValueEventListener(l) else ref.addListenerForSingleValueEvent(l)
        try { done.await(waitMs, TimeUnit.MILLISECONDS) } catch (_: Exception) {}
        if (forAlert) try { ref.removeEventListener(l) } catch (_: Exception) {}
        val s = snap ?: return null
        val sealed = s.child("img").value as? String
        if (sealed == null) { missing[k] = System.currentTimeMillis(); return null }
        missing.remove(k)
        var bytes = LiveNet.openBytes(ctx, sealed) ?: return null
        if (s.child("pin").value == true) {
            val pin = com.motioncam.cloud.SecretStore.get(ctx, "live.pin.$cam") ?: return null
            bytes = CameraLink.pinOpen(cam, pin, bytes) ?: return null
        }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        try { file(ctx, cam, name).writeBytes(bytes) } catch (_: Exception) {}
        mem.put(k, bmp)
        return bmp
    }

    /** [get] in the background; [done] on the main thread (only when there is a picture). */
    fun load(ctx: Context, cam: String, name: String, done: (Bitmap) -> Unit) {
        mem.get("$cam/$name")?.let { done(it); return }
        val app = ctx.applicationContext
        pool.execute { get(app, cam, name)?.let { b -> main.post { done(b) } } }
    }

    /**
     * [load], but only once [row] is on screen: a long Activity list only fetches the pictures you scroll to
     * (from this phone's cache when it has them, else a few KB each, 3 at a time).
     */
    fun loadWhenShown(row: android.view.View, cam: String, name: String, done: (Bitmap) -> Unit) {
        mem.get("$cam/$name")?.let { done(it); return }
        var started = false
        val rect = android.graphics.Rect()
        lateinit var check: () -> Unit
        val onScroll = android.view.ViewTreeObserver.OnScrollChangedListener { check() }
        val onLayout = android.view.ViewTreeObserver.OnGlobalLayoutListener { check() }
        fun unhook() {
            if (!row.viewTreeObserver.isAlive) return
            row.viewTreeObserver.removeOnScrollChangedListener(onScroll)
            row.viewTreeObserver.removeOnGlobalLayoutListener(onLayout)
        }
        check = {
            if (!started && row.isShown && row.getGlobalVisibleRect(rect)) {
                started = true
                unhook()
                load(row.context, cam, name, done)
            }
        }
        row.addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: android.view.View) {
                if (started) return
                row.viewTreeObserver.addOnScrollChangedListener(onScroll)
                row.viewTreeObserver.addOnGlobalLayoutListener(onLayout)
                row.post { check() }
            }
            override fun onViewDetachedFromWindow(v: android.view.View) = unhook()
        })
    }
}
