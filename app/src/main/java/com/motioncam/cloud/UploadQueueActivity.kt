package com.motioncam.cloud

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.util.Size
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import com.motioncam.Clip
import com.motioncam.ClipStore
import com.motioncam.Fmt
import com.motioncam.IconDrawable
import com.motioncam.Prefs
import com.motioncam.Ui
import com.motioncam.iconButton
import com.motioncam.topBar
import java.util.Date
import java.util.concurrent.Executors

/**
 * Everything waiting to be uploaded: progress, state, pause/resume, cancel, reorder (drag the ≡ handle),
 * and multi-select (hold a row, keep holding and drag to select a range), like the Clips screen.
 */
class UploadQueueActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var list: ListView
    private lateinit var summary: TextView
    private lateinit var empty: TextView
    private lateinit var normalBar: View
    private lateinit var selBar: LinearLayout
    private lateinit var selTitle: TextView
    private lateinit var pauseAll: TextView
    private var items: MutableList<CloudSync.JobView> = mutableListOf()
    private var clips: Map<Long, Clip> = emptyMap()
    private val ui = Handler(Looper.getMainLooper())
    private val pool = Executors.newFixedThreadPool(2)
    private val thumbs = object : LruCache<Long, Bitmap>((Runtime.getRuntime().maxMemory() / 10).toInt()) {
        override fun sizeOf(key: Long, value: Bitmap) = value.byteCount
    }

    // selection
    private var selecting = false
    private val selected = LinkedHashSet<String>()
    private var dragSelecting = false
    private var anchor = -1
    private var baseSel: Set<String> = emptySet()
    // reordering
    private var reordering = -1

    private val ticker = object : Runnable {
        override fun run() { if (reordering < 0 && !dragSelecting) refresh(); ui.postDelayed(this, 1000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.BG) }
        pauseAll = Ui.chip(this, "Pause all").apply {
            setOnClickListener { CloudSync.setAllPaused(this@UploadQueueActivity, !prefs.cloudPaused); refresh() }
        }
        normalBar = topBar(this, "Upload queue", pauseAll)
        root.addView(normalBar)
        selBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF2A2416.toInt())
            val p = Ui.dp(this@UploadQueueActivity, 8)
            setPadding(p, p, p, p)
            visibility = View.GONE
        }
        selBar.addView(Ui.label(this, "✕", 22f).apply {
            setPadding(Ui.dp(this@UploadQueueActivity, 12), 0, Ui.dp(this@UploadQueueActivity, 14), 0)
            setOnClickListener { exitSelection() }
        })
        selTitle = Ui.label(this, "", 17f)
        selBar.addView(selTitle, LinearLayout.LayoutParams(0, -2, 1f))
        fun act(t: String, f: () -> Unit) = Ui.chip(this, t).apply { setOnClickListener { f() } }.also {
            selBar.addView(it, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = Ui.dp(this@UploadQueueActivity, 5) })
        }
        act("All") { if (selected.size == items.size) selected.clear() else items.forEach { selected += it.key }; updateSelection() }
        act("Pause") { CloudSync.setPaused(this, selected.toList(), true); exitSelection() }
        act("Resume") { CloudSync.setPaused(this, selected.toList(), false); exitSelection() }
        lateinit var more: View
        more = iconButton(this, IconDrawable.Kind.MORE, 40, 10) {
            val pm = PopupMenu(this, more)
            pm.menu.add(0, 1, 0, "Move to top")
            pm.menu.add(0, 2, 1, "Move to bottom")
            pm.menu.add(0, 3, 2, "Retry now")
            pm.menu.add(0, 4, 3, "Cancel uploads")
            pm.setOnMenuItemClickListener {
                val keys = selected.toList()
                when (it.itemId) {
                    1 -> CloudSync.moveToTop(this, keys)
                    2 -> CloudSync.moveToBottom(this, keys)
                    3 -> CloudSync.retry(this, keys)
                    4 -> confirmCancel(keys)
                }
                if (it.itemId != 4) exitSelection()
                true
            }
            pm.show()
        }
        selBar.addView(more)
        root.addView(selBar)
        summary = Ui.label(this, "", 12f, Ui.GREY).apply { setPadding(Ui.dp(this@UploadQueueActivity, 16), 0, 0, Ui.dp(this@UploadQueueActivity, 6)) }
        root.addView(summary)
        val box = FrameLayout(this)
        list = ListView(this).apply { divider = null; setBackgroundColor(Ui.BG) }
        box.addView(list, FrameLayout.LayoutParams(-1, -1))
        empty = Ui.label(this, "Nothing waiting to upload.", 15f, Ui.GREY).apply { gravity = Gravity.CENTER; visibility = View.GONE }
        box.addView(empty, FrameLayout.LayoutParams(-1, -1))
        root.addView(box, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ -> onTap(pos) }
        list.setOnItemLongClickListener { _, v, pos, _ -> startDragSelect(pos, v); true }
    }

    override fun onResume() { super.onResume(); loadClips(); ui.post(ticker) }
    override fun onPause() { ui.removeCallbacks(ticker); super.onPause() }
    override fun onDestroy() { pool.shutdownNow(); super.onDestroy() }

    private fun loadClips() {
        Thread { val m = ClipStore.list(this).associateBy { it.id }; runOnUiThread { clips = m; refresh() } }.start()
    }

    private fun refresh() {
        items = CloudSync.snapshot(this).toMutableList()
        if (items.any { it.clipId !in clips }) loadClips()
        selected.retainAll(items.map { it.key }.toSet())
        if (selecting && selected.isEmpty() && !dragSelecting) selecting = false
        val up = items.count { it.running }
        val paused = items.count { it.paused }
        val failed = items.count { it.failed }
        summary.text = listOfNotNull(
            "${items.size} in queue",
            up.takeIf { it > 0 }?.let { "$it uploading" },
            paused.takeIf { it > 0 }?.let { "$it paused" },
            failed.takeIf { it > 0 }?.let { "$it failed" },
            if (prefs.cloudPaused) "all uploads paused" else null,
        ).joinToString(" · ")
        pauseAll.text = if (prefs.cloudPaused) "Resume all" else "Pause all"
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        updateSelection()
    }

    private fun updateSelection() {
        normalBar.visibility = if (selecting) View.GONE else View.VISIBLE
        selBar.visibility = if (selecting) View.VISIBLE else View.GONE
        selTitle.text = "${selected.size} selected"
        adapter.notifyDataSetChanged()
    }

    private fun exitSelection() { selecting = false; dragSelecting = false; selected.clear(); updateSelection() }

    private fun onTap(pos: Int) {
        val it = items.getOrNull(pos) ?: return
        if (selecting) { if (!selected.remove(it.key)) selected += it.key; updateSelection(); return }
        val opts = ArrayList<Pair<String, () -> Unit>>()
        if (it.paused) opts += "Resume" to { CloudSync.setPaused(this, listOf(it.key), false) }
        else opts += "Pause" to { CloudSync.setPaused(this, listOf(it.key), true) }
        opts += "Move to top" to { CloudSync.moveToTop(this, listOf(it.key)) }
        opts += "Move to bottom" to { CloudSync.moveToBottom(this, listOf(it.key)) }
        if (!it.running) opts += "Retry now" to { CloudSync.retry(this, listOf(it.key)) }
        opts += "Cancel upload" to { confirmCancel(listOf(it.key)) }
        AlertDialog.Builder(this).setTitle(title(it)).setMessage(it.state)
            .setItems(opts.map { o -> o.first }.toTypedArray()) { _, i -> opts[i].second(); refresh() }
            .setNegativeButton("Close", null).show()
    }

    private fun confirmCancel(keys: List<String>) {
        AlertDialog.Builder(this).setTitle("Cancel ${keys.size} upload${if (keys.size == 1) "" else "s"}?")
            .setMessage("The clips stay on the phone; they just won't be uploaded. You can queue them again with \"Upload existing clips\".")
            .setPositiveButton("Cancel uploads") { _, _ -> CloudSync.cancel(this, keys); exitSelection(); refresh() }
            .setNegativeButton("Keep", null).show()
    }

    private fun title(v: CloudSync.JobView): String =
        clips[v.clipId]?.let { Fmt.dateTime(prefs).format(Date(it.startMs)) } ?: "Clip ${v.clipId}"

    // ------------------------------------------------------------------ drag-select (like Clips)

    private fun startDragSelect(pos: Int, v: View) {
        val it = items.getOrNull(pos) ?: return
        if (!selecting) { selecting = true; selected.clear() }
        selected += it.key
        baseSel = selected.toSet()
        anchor = pos
        dragSelecting = true
        v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        updateSelection()
    }

    private fun rowAt(rawY: Float): Int {
        val loc = IntArray(2)
        list.getLocationOnScreen(loc)
        val y = (rawY - loc[1]).toInt().coerceIn(0, list.height - 1)
        return list.pointToPosition(list.width / 2, y)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (!dragSelecting) return super.dispatchTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val pos = rowAt(ev.rawY)
                if (pos != AdapterView.INVALID_POSITION && anchor >= 0) {
                    val next = LinkedHashSet(baseSel).apply { for (i in minOf(anchor, pos)..maxOf(anchor, pos)) items.getOrNull(i)?.let { add(it.key) } }
                    if (next != selected) { selected.clear(); selected.addAll(next); updateSelection() }
                }
                autoScroll(ev.rawY)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragSelecting = false
                val c = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
                super.dispatchTouchEvent(c)
                c.recycle()
            }
        }
        return true
    }

    private fun autoScroll(rawY: Float) {
        val loc = IntArray(2)
        list.getLocationOnScreen(loc)
        val y = rawY - loc[1]
        val edge = Ui.dp(this, 64)
        if (y < edge) list.scrollListBy(-Ui.dp(this, 12)) else if (y > list.height - edge) list.scrollListBy(Ui.dp(this, 12))
    }

    // ------------------------------------------------------------------ drag-to-reorder (≡ handle)

    private fun handleTouch(pos: Int, e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (selecting) return false
                reordering = pos
                list.requestDisallowInterceptTouchEvent(true)
                list.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                adapter.notifyDataSetChanged()
            }
            MotionEvent.ACTION_MOVE -> {
                if (reordering < 0) return false
                autoScroll(e.rawY)
                val target = rowAt(e.rawY)
                if (target != AdapterView.INVALID_POSITION && target != reordering && target in items.indices) {
                    val it = items.removeAt(reordering)
                    items.add(target, it)
                    reordering = target
                    adapter.notifyDataSetChanged()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (reordering >= 0) {
                    reordering = -1
                    CloudSync.reorder(this, items.map { it.key })
                    refresh()
                }
            }
        }
        return true
    }

    // ------------------------------------------------------------------ rows

    private val adapter = object : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row = (convertView as? LinearLayout) ?: makeRow()
            val v = items[position]
            val iv = row.getChildAt(0) as FrameLayout
            val thumb = iv.getChildAt(0) as ImageView
            val check = iv.getChildAt(1) as TextView
            val texts = row.getChildAt(1) as LinearLayout
            val t1 = texts.getChildAt(0) as TextView
            val t2 = texts.getChildAt(1) as TextView
            val bar = texts.getChildAt(2) as ProgressBar
            val handle = row.getChildAt(2) as TextView
            t1.text = title(v)
            t2.text = "${v.kind.label} · ${v.state}"
            t2.setTextColor(when { v.failed -> Ui.RED; v.running -> Ui.ACCENT; v.paused -> Ui.GREY; else -> 0xFFB0B4BA.toInt() })
            bar.visibility = if (v.running) View.VISIBLE else View.GONE
            bar.progress = v.progress.coerceAtLeast(0)
            bar.isIndeterminate = v.running && v.progress <= 0
            val sel = v.key in selected
            check.visibility = if (selecting) View.VISIBLE else View.GONE
            check.text = if (sel) "✓" else ""
            (check.background as GradientDrawable).setColor(if (sel) Ui.ACCENT else 0x66000000)
            row.setBackgroundColor(when { position == reordering -> 0xFF2C3036.toInt(); sel -> 0xFF2A2416.toInt(); else -> Ui.BG })
            handle.visibility = if (selecting) View.GONE else View.VISIBLE
            handle.setOnTouchListener { _, e -> handleTouch(position, e) }
            clips[v.clipId]?.let { bindThumb(thumb, it) } ?: thumb.setImageDrawable(null)
            return row
        }
    }

    private fun makeRow(): LinearLayout {
        val ctx = this
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val p = Ui.dp(ctx, 8)
            setPadding(p + 4, p, 0, p)
            val frame = FrameLayout(ctx)
            frame.addView(ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = GradientDrawable().apply { setColor(0xFF22252A.toInt()); cornerRadius = Ui.dp(ctx, 6).toFloat() }
                clipToOutline = true
            }, FrameLayout.LayoutParams(-1, -1))
            frame.addView(Ui.label(ctx, "", 12f, Color.BLACK).apply {
                gravity = Gravity.CENTER
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x66000000); setStroke(Ui.dp(ctx, 2), Color.WHITE) }
                visibility = View.GONE
            }, FrameLayout.LayoutParams(Ui.dp(ctx, 22), Ui.dp(ctx, 22), Gravity.TOP or Gravity.END).apply { setMargins(0, Ui.dp(ctx, 3), Ui.dp(ctx, 3), 0) })
            addView(frame, LinearLayout.LayoutParams(Ui.dp(ctx, 96), Ui.dp(ctx, 54)))
            val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dp(ctx, 12), 0, Ui.dp(ctx, 4), 0) }
            texts.addView(Ui.label(ctx, "", 14f))
            texts.addView(Ui.label(ctx, "", 12f, Ui.GREY).apply { maxLines = 2 })
            texts.addView(ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 },
                LinearLayout.LayoutParams(-1, Ui.dp(ctx, 6)).apply { topMargin = Ui.dp(ctx, 4) })
            addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
            addView(Ui.label(ctx, "≡", 24f, Ui.GREY).apply {
                gravity = Gravity.CENTER
                setPadding(Ui.dp(ctx, 14), Ui.dp(ctx, 8), Ui.dp(ctx, 16), Ui.dp(ctx, 8))
            })
        }
    }

    private fun bindThumb(iv: ImageView, c: Clip) {
        iv.tag = c.id
        thumbs.get(c.id)?.let { iv.setImageBitmap(it); return }
        iv.setImageDrawable(null)
        pool.execute {
            val bmp = try { contentResolver.loadThumbnail(c.uri, Size(192, 108), null) } catch (e: Exception) { null }
            if (bmp != null) thumbs.put(c.id, bmp)
            runOnUiThread { if (iv.tag == c.id) iv.setImageBitmap(bmp) }
        }
    }
}
