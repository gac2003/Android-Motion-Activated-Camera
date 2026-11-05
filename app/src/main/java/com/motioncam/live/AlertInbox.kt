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
 * Every camera alert this phone received (last 200), also when they are shown "inside MotionCam only": then
 * nothing goes to the phone's notifications; alerts appear as a small banner on the open screen and wait in this
 * list (unread count on the Cameras button).
 */
object AlertInbox {
    /** [clip]: the clip the alert is about (JSON: name, folders), so tapping it opens the clip. */
    class Item(val at: Long, val cam: String, val kind: String, var title: String, var text: String, var read: Boolean, var clip: String? = null)

    private var items: MutableList<Item>? = null
    val listeners = java.util.concurrent.CopyOnWriteArraySet<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private fun file(ctx: Context) = File(ctx.filesDir, "alert_inbox.json")

    @Synchronized
    fun all(ctx: Context): MutableList<Item> {
        items?.let { return it }
        val out = ArrayList<Item>()
        try {
            val a = JSONArray(file(ctx).readText())
            for (i in 0 until a.length()) a.getJSONObject(i).let {
                out += Item(it.getLong("at"), it.getString("cam"), it.optString("kind"), it.optString("title"), it.optString("text"), it.optBoolean("read"),
                    it.optString("clip").ifEmpty { null })
            }
        } catch (_: Exception) {}
        items = out
        return out
    }

    @Synchronized
    private fun save(ctx: Context) {
        val a = JSONArray()
        for (i in all(ctx).take(200)) a.put(JSONObject().put("at", i.at).put("cam", i.cam).put("kind", i.kind).put("title", i.title).put("text", i.text).put("read", i.read).put("clip", i.clip ?: ""))
        try { val t = File(file(ctx).path + ".tmp"); t.writeText(a.toString()); t.renameTo(file(ctx)) } catch (_: Exception) {}
    }

    /** Records an alert; an [update] of the newest one of the same camera and kind replaces its text. */
    fun add(ctx: Context, cam: String, kind: String, title: String, text: String, update: Boolean, clip: String? = null, at: Long = System.currentTimeMillis()) {
        synchronized(this) {
            val l = all(ctx)
            val last = l.firstOrNull { it.cam == cam && it.kind == kind }
            if (update && last != null) { last.title = title; last.text = text; last.read = false; if (clip != null) last.clip = clip }
            else { l.add(Item(at, cam, kind, title, text, false, clip)); l.sortByDescending { it.at } }
            while (l.size > 200) l.removeAt(l.size - 1)
            save(ctx)
        }
        main.post { for (x in listeners) try { x() } catch (_: Exception) {} }
    }

    fun unread(ctx: Context) = synchronized(this) { all(ctx).count { !it.read } }

    /** Adds alerts that happened earlier (missed while not connected), each at its own time, newest first. */
    fun addPast(ctx: Context, cam: String, past: List<Triple<Long, String, Pair<String, String>>>, clip: String? = null) {
        if (past.isEmpty()) return
        synchronized(this) {
            val l = all(ctx)
            for ((at, kind, tt) in past) l += Item(at, cam, kind, tt.first, tt.second, false, if (kind == "motion" || kind == "clip") clip else null)
            l.sortByDescending { it.at }
            while (l.size > 200) l.removeAt(l.size - 1)
            save(ctx)
        }
        main.post { for (x in listeners) try { x() } catch (_: Exception) {} }
    }

    fun markAllRead(ctx: Context) { synchronized(this) { all(ctx).forEach { it.read = true }; save(ctx) }; main.post { listeners.forEach { it() } } }
    fun clear(ctx: Context) { synchronized(this) { all(ctx).clear(); save(ctx) }; main.post { listeners.forEach { it() } } }

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
 * seconds; everything stays in the Alerts list.
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
        if (list) a.startActivity(Intent(a, AlertsInboxActivity::class.java))
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

/** All received camera alerts (newest first). */
class AlertsInboxActivity : Activity() {
    private lateinit var f: Form
    private val refresh: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.BG) }
        root.addView(topBar(this, "Alerts", Ui.chip(this, "Clear").apply { setOnClickListener { AlertInbox.clear(this@AlertsInboxActivity) } }))
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; val p = Ui.dp(this@AlertsInboxActivity, 16); setPadding(p, 0, p, p) }
        root.addView(ScrollView(this).apply { addView(box) }, LinearLayout.LayoutParams(-1, 0, 1f))
        Ui.capWidth(box)
        setContentView(root)
        f = Form(this, box)
    }

    override fun onResume() { super.onResume(); AlertInbox.listeners += refresh; render() }
    override fun onPause() { AlertInbox.listeners -= refresh; AlertInbox.markAllRead(this); super.onPause() }

    private fun render() {
        f.box.removeAllViews()
        val items = AlertInbox.all(this).toList()
        val p = Prefs(this)
        f.note(if (p.alertsInApp) "Alerts are shown inside MotionCam only (Alerts & notifications)." else "Also sent as phone notifications.")
        if (items.isEmpty()) { f.note("No alerts yet."); return }
        val day = SimpleDateFormat("EEE, MMM d", Locale.getDefault())
        var last = ""
        for (i in items) {
            val d = day.format(Date(i.at))
            if (d != last) { last = d; f.title(d) }
            val r = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, Ui.dp(this@AlertsInboxActivity, 6), 0, Ui.dp(this@AlertsInboxActivity, 6))
                setOnClickListener {
                    if (i.clip != null) OpenClipActivity.open(this@AlertsInboxActivity, i.cam, i.clip!!)
                    else startActivity(Intent(this@AlertsInboxActivity, RemoteCameraActivity::class.java).putExtra(Alerts.EXTRA_CAM, i.cam))
                }
            }
            r.addView(Ui.label(this, (if (!i.read) "● " else "") + i.title + "  ·  " + Fmt.t(p, i.at, seconds = false), 14f, if (!i.read) Ui.ACCENT else 0xFFFFFFFF.toInt()))
            r.addView(Ui.label(this, i.text, 12f, Ui.GREY))
            if (i.clip != null) r.addView(Ui.label(this, "▶ Tap to watch the clip", 12f, Ui.ACCENT))
            f.box.addView(r)
        }
    }
}
