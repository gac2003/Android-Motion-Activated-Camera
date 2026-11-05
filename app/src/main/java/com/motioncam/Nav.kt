package com.motioncam

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The bottom tabs (Home · Activity · Camera · Clips · Settings) and where the app reopens. Camera, in the middle, opens
 * Camera mode full screen (no tab bar there); Back returns to the tab it was opened from.
 *
 * Each tab is its own screen kept alive behind the others: switching tabs brings the tab's screen back to the front
 * as it was left (scroll position, open section…), without an animation, like any app with bottom tabs. Back on a
 * tab goes to Home; Back on Home leaves the app (everything stays as it was). Camera mode is a screen of its own,
 * opened from Home; the app reopens where it was left: Home (and its last tab) or Camera mode.
 */
object Nav {
    enum class Tab { HOME, ACTIVITY, CAMERA, CLIPS, SETTINGS }

    private fun sp(ctx: Context) = ctx.getSharedPreferences("nav", Context.MODE_PRIVATE)

    /** "camera" while Camera mode was the last screen in front, else "home". */
    fun lastPlace(ctx: Context): String = sp(ctx).getString("place", "home") ?: "home"
    fun lastTab(ctx: Context): Tab = try { Tab.valueOf(sp(ctx).getString("tab", "HOME") ?: "HOME").takeIf { it != Tab.CAMERA } ?: Tab.HOME } catch (_: Exception) { Tab.HOME }
    /** The Clips tab shows its remote folders (true) or this phone's clips. */
    /**
     * The Clips tab's inner screens (a remote folder, the collections, one collection): at most one of each kind, each
     * remembering the screen it was opened from. Returning to the tab shows the deepest one on the side last shown.
     */
    private val inner = HashMap<Class<*>, java.lang.ref.WeakReference<Activity>>()
    private val parents = java.util.WeakHashMap<Activity, java.lang.ref.WeakReference<Activity>>()
    private val quiet = java.util.Collections.newSetFromMap(java.util.WeakHashMap<Activity, Boolean>())
    /** The Clips tab screen last in front (the parent of the next inner screen). */
    var clipsTop: java.lang.ref.WeakReference<Activity>? = null
    private val INNER = listOf(CollectionTabActivity::class.java, CollectionsTabActivity::class.java, RemoteClipsTabActivity::class.java)

    private fun alive(a: Activity?) = a != null && !a.isFinishing && !a.isDestroyed
    /** Which side of the Clips tab an inner screen belongs to (true = remote folders). */
    private fun remoteSide(a: Activity) = (a as? ClipsSide)?.onRemoteSide == true

    fun innerOpened(a: Activity) {
        // the other side's screen of this kind is replaced (with the screens opened from it)
        inner[a.javaClass]?.get()?.takeIf { it !== a && alive(it) }?.let { old ->
            val side = remoteSide(old)
            for (c in INNER.takeWhile { it != a.javaClass }) inner[c]?.get()?.takeIf { alive(it) && remoteSide(it) == side }?.let { quiet += it; it.finish(); inner.remove(c) }
            quiet += old; old.finish()
        }
        inner[a.javaClass] = java.lang.ref.WeakReference(a)
        clipsTop?.get()?.takeIf { it !== a }?.let { parents[a] = java.lang.ref.WeakReference(it) }
        clipsTop = java.lang.ref.WeakReference(a)
    }

    /** An inner screen closed (Back): show the screen it was opened from. */
    fun innerClosed(a: Activity) {
        if (inner[a.javaClass]?.get() === a) inner.remove(a.javaClass)
        if (quiet.remove(a)) return
        val p = parents[a]?.get()
        noWindowAnim(a)
        if (alive(p) && !a.isTaskRoot) { popping = p!!.javaClass; open(a, p.javaClass) }
    }

    /** The deepest open screen of one side of the Clips tab, or its first screen. */
    fun clipsScreen(ctx: Context, remote: Boolean): Class<out Activity> =
        INNER.mapNotNull { inner[it]?.get() }.firstOrNull { alive(it) && remoteSide(it) == remote }?.javaClass
            ?: if (remote) RemoteTabActivity::class.java else ClipsTabActivity::class.java

    /** Back to a side's first screen (its inner screens close). */
    fun popClips(a: Activity, remote: Boolean) {
        for (c in INNER) inner[c]?.get()?.takeIf { alive(it) && remoteSide(it) == remote }?.let { quiet += it; it.finish(); inner.remove(c) }
        open(a, if (remote) RemoteTabActivity::class.java else ClipsTabActivity::class.java)
    }

    /** No window animation for the screen just started or finished (the Clips tab slides only its content). */
    @Suppress("DEPRECATION")
    fun noWindowAnim(a: Activity) = a.overridePendingTransition(0, 0)

    /** The screen to slide back in when it returns (an inner screen above it was closed). */
    private var popping: Class<*>? = null

    /**
     * Inside the Clips tab only the content moves: the "Clips" title, the switch and the tab bar stay where they are.
     * [from] is where the content starts, as a share of the width (1 = from the right, negative = from the left).
     */
    fun slideContent(a: Activity, from: Float) {
        val wrap = a.findViewById<android.view.ViewGroup>(android.R.id.content)?.getChildAt(0) as? android.view.ViewGroup ?: return
        val content = wrap.getChildAt(0) as? android.view.ViewGroup ?: return
        val views = ArrayList<View>()
        for (i in 0 until content.childCount) {
            val v = content.getChildAt(i)
            if (v.tag == "clipsHeader") (v as? android.view.ViewGroup)?.let { g -> for (j in 0 until g.childCount) g.getChildAt(j).takeIf { it.tag == "slides" }?.let { views += it } }
            else views += v
        }
        val w = (content.width.takeIf { it > 0 } ?: a.resources.displayMetrics.widthPixels).toFloat()
        for (v in views) {
            v.animate().cancel()
            v.translationX = w * from
            v.alpha = if (from < 0) 0.5f else 1f
            v.animate().translationX(0f).alpha(1f).setDuration(240).setInterpolator(android.view.animation.DecelerateInterpolator(1.6f)).start()
        }
    }

    /** A Clips tab screen comes to the front: slide its content back in if an inner screen above it was just closed. */
    fun clipsResumed(a: Activity) {
        if (popping == a.javaClass) { popping = null; slideContent(a, -0.3f) }
    }

    /** Being closed because it was replaced or the side went back to its first screen (not by the user's Back). */
    fun quietly(a: Activity) = a in quiet

    fun folderOpen() = inner[RemoteClipsTabActivity::class.java]?.get().let { alive(it) }
    fun lastFolder(ctx: Context): String? = sp(ctx).getString("clipsFolder", null)
    fun setLastFolder(ctx: Context, id: String?) = sp(ctx).edit().putString("clipsFolder", id).apply()
    fun clipsRemote(ctx: Context): Boolean = sp(ctx).getBoolean("clipsRemote", false)
    fun setClipsRemote(ctx: Context, remote: Boolean) = sp(ctx).edit().putBoolean("clipsRemote", remote).apply()

    fun cameraShown(ctx: Context) = sp(ctx).edit().putString("place", "camera").apply()
    fun tabShown(ctx: Context, tab: Tab) = sp(ctx).edit().putString("place", "home").putString("tab", tab.name).apply()

    fun cls(ctx: Context, tab: Tab): Class<out Activity> = when (tab) {
        Tab.HOME -> HomeActivity::class.java
        Tab.ACTIVITY -> ActivityTabActivity::class.java
        Tab.CAMERA -> MainActivity::class.java
        Tab.CLIPS -> clipsScreen(ctx, clipsRemote(ctx))
        Tab.SETTINGS -> SettingsTabActivity::class.java
    }

    /** Shows a tab as it was left (or opens it the first time). */
    fun go(a: Activity, tab: Tab) = if (tab == Tab.CAMERA) camera(a) else open(a, cls(a, tab))

    /** Brings a tab screen to the front (or opens it), without an animation. */
    fun open(a: Activity, c: Class<out Activity>) {
        if (a.javaClass == c) return
        a.startActivity(Intent(a, c).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        @Suppress("DEPRECATION") a.overridePendingTransition(0, 0)
    }

    /** Opens Camera mode (this phone as a camera). */
    fun camera(a: Activity) {
        a.startActivity(Intent(a, MainActivity::class.java))
    }

    /** A tab screen's content with the tab bar under it. */
    fun wrap(a: Activity, content: View, tab: Tab): View = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Ui.BG)
        (content.parent as? android.view.ViewGroup)?.removeView(content)
        addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(bar(a, tab).apply { tag = "tabbar" }, LinearLayout.LayoutParams(-1, -2))
    }

    /** The tab bar; the Activity tab shows how many camera events haven't been looked at yet. */
    fun bar(a: Activity, current: Tab): View {
        val ctx: Context = a
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFF1A1C20.toInt())
            setPadding(Ui.dp(ctx, 4), Ui.dp(ctx, 4), Ui.dp(ctx, 4), Ui.dp(ctx, 8))
        }
        var badge: TextView? = null
        var camDot: View? = null
        for (t in Tab.values()) {
            val on = t == current
            val color = if (on) Ui.ACCENT else 0xFFC4C8CE.toInt()
            val (kind, label) = when (t) {
                Tab.HOME -> IconDrawable.Kind.HOME to "Home"
                Tab.ACTIVITY -> IconDrawable.Kind.PULSE to "Activity"
                Tab.CAMERA -> IconDrawable.Kind.VIDEOCAM to "Camera"
                Tab.CLIPS -> IconDrawable.Kind.CLIPS to "Clips"
                Tab.SETTINGS -> IconDrawable.Kind.SETTINGS to "Settings"
            }
            val item = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, Ui.dp(ctx, 6), 0, Ui.dp(ctx, 4))
                isClickable = true
                contentDescription = label + if (on) " (current tab)" else ""
                val tv = android.util.TypedValue()
                ctx.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
                if (tv.resourceId != 0) setBackgroundResource(tv.resourceId)
                setOnClickListener { if (!on) go(a, t) else onReselect(a) }
            }
            val iconBox = FrameLayout(ctx)
            iconBox.addView(ImageView(ctx).apply { setImageDrawable(IconDrawable(kind, color)) },
                FrameLayout.LayoutParams(Ui.dp(ctx, 24), Ui.dp(ctx, 24), Gravity.CENTER))
            if (t == Tab.CAMERA) {
                camDot = View(ctx).apply { visibility = View.GONE }
                iconBox.addView(camDot, FrameLayout.LayoutParams(Ui.dp(ctx, 9), Ui.dp(ctx, 9), Gravity.TOP or Gravity.END).apply {
                    rightMargin = Ui.dp(ctx, 6) })
            }
            if (t == Tab.ACTIVITY) {
                badge = Ui.label(ctx, "", 11f).apply {
                    setTypeface(typeface, Typeface.BOLD)
                    setShadowLayer(3f, 0f, 0f, 0xFF000000.toInt())
                }
                iconBox.addView(badge, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END))
            }
            item.addView(iconBox, LinearLayout.LayoutParams(Ui.dp(ctx, 44), Ui.dp(ctx, 26)))
            item.addView(Ui.label(ctx, label, 12f, color).apply {
                gravity = Gravity.CENTER
                if (on) setTypeface(typeface, Typeface.BOLD)
                setPadding(0, Ui.dp(ctx, 2), 0, 0)
            })
            bar.addView(item, LinearLayout.LayoutParams(0, -2, 1f))
        }
        val line = View(ctx).apply { setBackgroundColor(0xFF2A2D33.toInt()) }
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        box.addView(line, LinearLayout.LayoutParams(-1, Ui.dp(ctx, 1)))
        box.addView(bar)
        val dot = camDot
        if (dot != null) {
            // this phone as a camera: green while monitoring, red while another phone watches it
            val update = {
                val watched = com.motioncam.live.CameraLink.streaming
                val on = CameraService.monitoring
                dot.visibility = if (watched || on) View.VISIBLE else View.GONE
                dot.background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(if (watched) Ui.RED else 0xFF4CAF50.toInt())
                    setStroke(Ui.dp(ctx, 1), 0xFF1A1C20.toInt())
                }
                (dot.parent?.parent as? View)?.contentDescription = "Camera" + when { watched -> " (monitoring, being watched)"; on -> " (monitoring)"; else -> "" }
            }
            val l: () -> Unit = { update() }
            val sl: (Status) -> Unit = { box.post { update() } }
            box.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) { com.motioncam.live.Live.listeners += l; StatusBus.add(sl); update() }
                override fun onViewDetachedFromWindow(v: View) { com.motioncam.live.Live.listeners -= l; StatusBus.remove(sl) }
            })
            update()
        }
        val b = badge
        if (b != null) {
            val update = {
                val n = com.motioncam.live.AlertInbox.newCount(ctx)
                b.text = if (n <= 0 || current == Tab.ACTIVITY) "" else if (n > 9) "9+" else n.toString()
                b.contentDescription = if (n > 0) "$n new" else null
            }
            val l: () -> Unit = { update() }
            box.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) { com.motioncam.live.AlertInbox.listeners += l; update() }
                override fun onViewDetachedFromWindow(v: View) { com.motioncam.live.AlertInbox.listeners -= l }
            })
            update()
        }
        return box
    }

    /** Tapping the tab you're on: back to its top (screens that scroll implement [Reselect]). */
    private fun onReselect(a: Activity) { (a as? Reselect)?.onTabReselected() }

    interface Reselect { fun onTabReselected() }

    /**
     * Leaving a tab screen (Back, or its own back arrow): Home instead, so the tab stays as it was; leaving Home
     * puts the app in the background. Returns true when handled.
     */
    fun leaveTab(a: Activity, tab: Tab): Boolean {
        if (tab == Tab.HOME) { a.moveTaskToBack(true); return true }
        go(a, Tab.HOME)
        return true
    }
}

/** The Clips tab's switch between this phone's clips and the remote folders (it remembers which was shown). */
object ClipsSwitch {
    fun view(a: Activity, remote: Boolean): View {
        val ctx: Context = a
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(Ui.dp(ctx, 3), Ui.dp(ctx, 3), Ui.dp(ctx, 3), Ui.dp(ctx, 3))
            background = android.graphics.drawable.GradientDrawable().apply { setColor(0xFF1E2126.toInt()); cornerRadius = Ui.dp(ctx, 22).toFloat() }
        }
        for ((label, isRemote) in listOf("On this phone" to false, "Remote folders" to true)) {
            val on = isRemote == remote
            box.addView(Ui.label(ctx, label, 14f, if (on) 0xFF15171B.toInt() else 0xFFFFFFFF.toInt()).apply {
                gravity = Gravity.CENTER
                setPadding(0, Ui.dp(ctx, 8), 0, Ui.dp(ctx, 8))
                if (on) {
                    setTypeface(typeface, Typeface.BOLD)
                    background = android.graphics.drawable.GradientDrawable().apply { setColor(Ui.ACCENT); cornerRadius = Ui.dp(ctx, 20).toFloat() }
                }
                isClickable = true
                contentDescription = label + if (on) " (shown)" else ""
                setOnClickListener {
                    // the side already shown: back to its first screen (folders / this phone's clips)
                    if (on) { if (a !is ClipsTabActivity && a !is RemoteTabActivity) { if (isRemote) Nav.setLastFolder(a, null); Nav.popClips(a, isRemote) }; return@setOnClickListener }
                    Nav.setClipsRemote(a, isRemote)
                    Nav.open(a, Nav.clipsScreen(a, isRemote))
                }
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        return box
    }
}

/** A Clips tab screen: which side of the switch it belongs to. */
interface ClipsSide { val onRemoteSide: Boolean }

/** "‹ name" (with a smaller [sub] after it): where you are inside the Clips tab; tap to go back one step. */
fun backRow(a: Activity, name: String, sub: String?, back: () -> Unit): View = LinearLayout(a).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    setPadding(0, Ui.dp(a, 10), 0, 0)
    isClickable = true
    contentDescription = "Back from $name"
    setOnClickListener { back() }
    addView(Ui.label(a, "‹", 24f).apply { setPadding(0, 0, Ui.dp(a, 10), Ui.dp(a, 2)) })
    addView(Ui.label(a, "", 16f).apply {
        maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        text = android.text.SpannableStringBuilder(name).apply {
            setSpan(android.text.style.StyleSpan(Typeface.BOLD), 0, length, 0)
            if (sub != null) { append("  ·  "); val st = length; append(sub)
                setSpan(android.text.style.ForegroundColorSpan(Ui.GREY), st, length, 0); setSpan(android.text.style.RelativeSizeSpan(0.85f), st, length, 0) }
        }
    }, LinearLayout.LayoutParams(0, -2, 1f))
}
