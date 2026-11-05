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

    /** A short banner at the top of the open MotionCam screen (tap = open the camera). */
    fun banner(cam: String, title: String, text: String, openList: Boolean = false) {
        main.post {
            val a = front?.get() ?: return@post
            if (a.isFinishing) return@post
            val root = a.window.decorView as? ViewGroup ?: return@post
            val tv = TextView(a).apply {
                this.text = "$title\n$text"
                setTextColor(0xFFFFFFFF.toInt()); textSize = 13f
                val p = Ui.dp(a, 12); setPadding(p, p * 2 / 3, p, p * 2 / 3)
                background = GradientDrawable().apply { cornerRadius = Ui.dp(a, 14).toFloat(); setColor(0xF0202328.toInt()); setStroke(Ui.dp(a, 1), Ui.ACCENT) }
                elevation = Ui.dp(a, 6).toFloat()
                setOnClickListener {
                    (parent as? ViewGroup)?.removeView(this)
                    if (openList) a.startActivity(Intent(a, AlertsInboxActivity::class.java))
                    else a.startActivity(Intent(a, RemoteCameraActivity::class.java).putExtra(Alerts.EXTRA_CAM, cam))
                }
            }
            val lp = FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = Ui.dp(a, 48) }
            root.addView(tv, lp)
            tv.alpha = 0f
            tv.animate().alpha(1f).setDuration(150).start()
            tv.postDelayed({ tv.animate().alpha(0f).setDuration(200).withEndAction { (tv.parent as? ViewGroup)?.removeView(tv) }.start() }, 4000)
        }
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
