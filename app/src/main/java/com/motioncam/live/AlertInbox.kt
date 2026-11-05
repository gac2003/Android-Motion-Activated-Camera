package com.motioncam.live

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.motioncam.Fmt
import com.motioncam.Prefs
import com.motioncam.Ui
import com.motioncam.topBar
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Camera alerts on this phone and what's new in Activity. The history itself is the cameras' Activity (the same on
 * every phone, kept 7 days); alerts are only how this phone is told (phone notifications or in-app cards). This keeps
 * track of what this phone has not seen yet: the newest notable event of each camera vs. when Activity (or the
 * Live connection screen) was last looked at — the dot on the Live connection icon and the "new" marks in Activity.
 */
object AlertInbox {
    val listeners = java.util.concurrent.CopyOnWriteArraySet<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private fun sp(ctx: Context) = ctx.getSharedPreferences("activity_seen", Context.MODE_PRIVATE)
    private fun changed() = main.post { for (x in listeners) try { x() } catch (_: Exception) {} }

    /**
     * Notable camera events (local times) arrived: each camera's recent ones are remembered (the last 30), to count
     * what is new. A clip saved after a motion isn't counted on its own (callers pass motion, not clip times).
     */
    fun noteEvents(ctx: Context, cam: String, times: Collection<Long>) {
        if (times.isEmpty()) return
        val sp = sp(ctx)
        val old = sp.getString("times:$cam", "")!!.split(',').mapNotNull { it.toLongOrNull() }.toSet()
        val all = (old + times).sorted().takeLast(30)
        if (all.toSet() == old) return
        sp.edit().putString("times:$cam", all.joinToString(",")).putLong("latest:$cam", all.last()).apply()
        if (times.any { it > seenAt(ctx, cam) }) changed()
    }

    fun noteEvent(ctx: Context, cam: String, t: Long) = noteEvents(ctx, cam, listOf(t))

    /** How many camera events this phone has not seen yet (for the number on the Live connection icon). */
    fun newCount(ctx: Context): Int {
        val all = sp(ctx).all
        return all.keys.filter { it.startsWith("times:") }.sumOf { k ->
            val cam = k.removePrefix("times:")
            val seen = seenAt(ctx, cam)
            (all[k] as? String ?: "").split(',').mapNotNull { it.toLongOrNull() }.count { it > seen }
        }
    }

    /** When activity was last looked at: all cameras ([cam] null), or this camera (whichever is later). */
    fun seenAt(ctx: Context, cam: String?): Long {
        val sp = sp(ctx)
        val all = sp.getLong("seen", 0)
        return if (cam == null) all else maxOf(all, sp.getLong("seen:$cam", 0))
    }

    /** Activity was looked at: everything until now is seen (all cameras, or one). */
    fun markSeen(ctx: Context, cam: String?) {
        sp(ctx).edit().putLong(if (cam == null) "seen" else "seen:$cam", System.currentTimeMillis()).apply()
        changed()
    }

    /** Some camera has activity this phone has not seen yet. */
    fun hasNew(ctx: Context): Boolean {
        val all = sp(ctx).all
        return all.keys.filter { it.startsWith("latest:") }.any { k ->
            val cam = k.removePrefix("latest:")
            ((all[k] as? Long) ?: 0L) > seenAt(ctx, cam)
        }
    }

    // ------------------------------------------------------------------ in-app banner

    /** The screen in front (set by the app), for in-app banners. */
    @Volatile var front: WeakReference<Activity>? = null

    /** An alert shown on the open MotionCam screen (see [BannerStack]). */
    fun banner(cam: String, title: String, text: String, openList: Boolean = false) {
        main.post {
            val a = front?.get() ?: return@post
            if (a.isFinishing) return@post
            BannerStack.push(a, BannerStack.Card(cam, title, text, openList))
        }
    }
}

/**
 * In-app alerts, like the phone's own heads-up notifications: one compact card under the status bar; more alerts stack
 * behind it (offset layers and "+N more") instead of piling up over the screen. Swipe the card sideways to dismiss it
 * (the next one comes forward), swipe up to dismiss them all, tap to open. The stack hides by itself after a few
 * seconds; everything is in Activity.
 */
object BannerStack {
    class Card(val cam: String, val title: String, val text: String, val openList: Boolean)

    private const val SHOW_MS = 6000L
    private const val TAG = "motioncam.bannerStack"

    private class Host(val a: Activity, val root: FrameLayout) {
        val cards = ArrayList<Card>()          // newest first
        val layers = ArrayList<android.view.View>()
        var front: LinearLayout? = null
        val hide = Runnable { dismissAll(this) }
    }

    private fun host(a: Activity): Host? {
        val decor = a.window.decorView as? ViewGroup ?: return null
        (decor.getTag(TAG.hashCode()) as? Host)?.let { if (it.root.parent != null) return it }
        val root = FrameLayout(a).apply { clipChildren = false; clipToPadding = false }
        val top = (a.window.decorView.rootWindowInsets?.getInsets(android.view.WindowInsets.Type.statusBars())?.top ?: Ui.dp(a, 24)) + Ui.dp(a, 6)
        val w = minOf(a.resources.displayMetrics.widthPixels - Ui.dp(a, 24), Ui.dp(a, 520))
        decor.addView(root, FrameLayout.LayoutParams(w, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = top })
        return Host(a, root).also { decor.setTag(TAG.hashCode(), it) }
    }

    fun push(a: Activity, c: Card) {
        val h = host(a) ?: return
        h.cards.add(0, c)
        while (h.cards.size > 20) h.cards.removeAt(h.cards.size - 1)
        render(h, animateIn = true)
    }

    private fun bg(a: Activity, alpha: Int, stroke: Boolean) = GradientDrawable().apply {
        cornerRadius = Ui.dp(a, 14).toFloat()
        setColor((alpha shl 24) or 0x202328)
        if (stroke) setStroke(Ui.dp(a, 1), Ui.ACCENT)
    }

    private fun render(h: Host, animateIn: Boolean) {
        val a = h.a
        h.root.removeAllViews(); h.layers.clear()
        val main = Handler(Looper.getMainLooper())
        main.removeCallbacks(h.hide)
        if (h.cards.isEmpty()) return
        val c = h.cards[0]
        val more = h.cards.size - 1
        // layers behind: one per further card (up to two), each a little lower and narrower
        for (i in minOf(more, 2) downTo 1) {
            val v = android.view.View(a).apply {
                background = bg(a, 0xE6 - i * 0x20, false)
                elevation = Ui.dp(a, 6 - i).toFloat()
                translationY = Ui.dp(a, 7 * i).toFloat()
                scaleX = 1f - 0.05f * i
            }
            h.root.addView(v, FrameLayout.LayoutParams(-1, -1))
            h.layers += v
        }
        val card = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            val p = Ui.dp(a, 12); setPadding(p, p * 2 / 3, p, p * 2 / 3)
            background = bg(a, 0xF2, true)
            elevation = Ui.dp(a, 7).toFloat()
        }
        val head = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(TextView(a).apply {
            text = c.title; setTextColor(0xFFFFFFFF.toInt()); textSize = 13f; maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END; setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (more > 0) head.addView(TextView(a).apply {
            text = "+$more more"; setTextColor(Ui.ACCENT); textSize = 12f; setPadding(Ui.dp(a, 8), 0, 0, 0)
        })
        card.addView(head)
        card.addView(TextView(a).apply {
            text = c.text; setTextColor(0xFFC8CCD2.toInt()); textSize = 12f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        })
        h.root.addView(card, FrameLayout.LayoutParams(-1, -2))
        h.front = card
        // layers take the card's height once it is measured
        card.post { for (v in h.layers) v.layoutParams = FrameLayout.LayoutParams(-1, card.height); h.root.requestLayout() }
        swipe(h, card, c)
        if (animateIn) { card.alpha = 0f; card.translationY = -Ui.dp(a, 12).toFloat(); card.animate().alpha(1f).translationY(0f).setDuration(160).start() }
        main.postDelayed(h.hide, SHOW_MS)
    }

    /** Sideways = dismiss this one, up = dismiss all, tap = open. */
    private fun swipe(h: Host, card: android.view.View, c: Card) {
        val a = h.a
        val slop = android.view.ViewConfiguration.get(a).scaledTouchSlop
        var x0 = 0f; var y0 = 0f; var moved = false
        val main = Handler(Looper.getMainLooper())
        card.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> { x0 = e.rawX; y0 = e.rawY; moved = false; main.removeCallbacks(h.hide) }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - x0; val dy = e.rawY - y0
                    if (Math.abs(dx) > slop || Math.abs(dy) > slop) moved = true
                    if (Math.abs(dx) >= Math.abs(dy)) { v.translationX = dx; v.alpha = 1f - minOf(1f, Math.abs(dx) / v.width) }
                    else if (dy < 0) v.translationY = dy
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    val dx = e.rawX - x0; val dy = e.rawY - y0
                    when {
                        !moved && e.actionMasked == android.view.MotionEvent.ACTION_UP -> { open(h, c) }
                        Math.abs(dx) > v.width / 3f -> v.animate().translationX(if (dx > 0) v.width.toFloat() else -v.width.toFloat()).alpha(0f)
                            .setDuration(140).withEndAction { h.cards.remove(c); render(h, animateIn = false) }.start()
                        dy < -Ui.dp(a, 40) -> dismissAll(h)
                        else -> { v.animate().translationX(0f).translationY(0f).alpha(1f).setDuration(140).start(); main.postDelayed(h.hide, SHOW_MS) }
                    }
                }
            }
            true
        }
    }

    private fun open(h: Host, c: Card) {
        val a = h.a
        // several alerts waiting: the list shows them all; one alert: its camera
        val list = c.openList || h.cards.size > 1
        dismissAll(h)
        if (list) a.startActivity(Intent(a, ActivityLogActivity::class.java))
        else a.startActivity(Intent(a, RemoteCameraActivity::class.java).putExtra(Alerts.EXTRA_CAM, c.cam))
    }

    private fun dismissAll(h: Host) {
        Handler(Looper.getMainLooper()).removeCallbacks(h.hide)
        val views = (0 until h.root.childCount).map { h.root.getChildAt(it) }
        if (views.isEmpty()) { h.cards.clear(); return }
        h.root.animate().alpha(0f).translationY(-Ui.dp(h.a, 16).toFloat()).setDuration(160).withEndAction {
            h.cards.clear(); h.root.removeAllViews(); h.root.alpha = 1f; h.root.translationY = 0f
        }.start()
    }
}
