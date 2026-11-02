package com.motioncam

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.LruCache
import android.util.Size
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** Gallery of recorded clips with thumbnails, grouped by day. Tap = play in timeline. */
class ClipsActivity : Activity() {
    private sealed class Row {
        class Header(val dayStart: Long, val clips: List<Clip>) : Row()
        class Items(val clips: List<Clip>) : Row()
    }

    private var clips: List<Clip> = emptyList()
    private var rows: List<Row> = emptyList()
    private var cols = 3
    private lateinit var list: ListView
    private lateinit var summary: TextView
    private lateinit var banner: TextView
    private lateinit var normalBar: View
    private lateinit var selBar: LinearLayout
    private lateinit var selTitle: TextView

    // ---- multi-select (Google Drive style) ----
    private var selecting = false
    private val selected = LinkedHashSet<Long>()
    private var display: List<Clip> = emptyList()          // clips in on-screen order
    private var dragSelecting = false
    private var anchorIdx = -1
    private var baseSel: Set<Long> = emptySet()
    private var lastRawX = 0f
    private var lastRawY = 0f

    /** Permission to see videos this install did not create (e.g. recorded before a reinstall). */
    private fun videoPermission() =
        if (android.os.Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_VIDEO
        else android.Manifest.permission.READ_EXTERNAL_STORAGE

    private fun canSeeAll() = checkSelfPermission(videoPermission()) == android.content.pm.PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        reload()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (!ClipDeleter.onResult(requestCode, resultCode)) @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
    }
    private val pool = Executors.newFixedThreadPool(2)
    private val thumbs = object : LruCache<Long, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: Long, value: Bitmap) = value.byteCount
    }
    private lateinit var prefs: Prefs
    private var timeFmt = SimpleDateFormat("h:mm:ss a", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.BG) }
        val playAll = Ui.chip(this, "▶ Timeline").apply {
            setOnClickListener {
                // newest day, from its first clip
                val h = rows.firstOrNull { it is Row.Header } as Row.Header?
                h?.clips?.minByOrNull { it.startMs }?.let { play(it) }
            }
        }
        normalBar = topBar(this, "Clips", playAll)
        root.addView(normalBar)
        selBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF2A2416.toInt())
            val p = Ui.dp(this@ClipsActivity, 8)
            setPadding(p, p, p, p)
            visibility = View.GONE
        }
        selBar.addView(Ui.label(this, "✕", 22f).apply {
            setPadding(Ui.dp(this@ClipsActivity, 12), 0, Ui.dp(this@ClipsActivity, 16), 0)
            setOnClickListener { exitSelection() }
        })
        selTitle = Ui.label(this, "", 18f)
        selBar.addView(selTitle, LinearLayout.LayoutParams(0, -2, 1f))
        fun selAction(t: String, click: () -> Unit) = Ui.chip(this, t).apply { setOnClickListener { click() } }.also {
            selBar.addView(it, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = Ui.dp(this@ClipsActivity, 6) })
        }
        selAction("All") { if (selected.size == display.size) selected.clear() else display.forEach { selected += it.id }; updateSelection() }
        selAction("Share") { shareSelected() }
        selAction("Delete") { deleteSelected() }
        root.addView(selBar)
        summary = Ui.label(this, "", 12f, Ui.GREY).apply { setPadding(Ui.dp(this@ClipsActivity, 52), 0, 0, Ui.dp(this@ClipsActivity, 6)) }
        root.addView(summary)
        banner = Ui.label(this, "Clips from earlier installs are hidden. Tap to show them.", 13f, Ui.ACCENT).apply {
            setPadding(Ui.dp(this@ClipsActivity, 16), Ui.dp(this@ClipsActivity, 8), Ui.dp(this@ClipsActivity, 16), Ui.dp(this@ClipsActivity, 8))
            setBackgroundColor(0xFF2A2416.toInt())
            setOnClickListener { requestPermissions(arrayOf(videoPermission()), 7) }
            visibility = View.GONE
        }
        root.addView(banner)
        list = ListView(this).apply {
            divider = null
            setBackgroundColor(Ui.BG)
        }
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        list.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        timeFmt = Fmt.time(prefs)
        banner.visibility = if (canSeeAll()) View.GONE else View.VISIBLE
        reload()
    }

    override fun onDestroy() {
        pool.shutdownNow()
        super.onDestroy()
    }

    private fun reload() {
        banner.visibility = if (canSeeAll()) View.GONE else View.VISIBLE
        Thread {
            val all = ClipStore.list(this)
            runOnUiThread {
                clips = all
                buildRows()
                val total = all.sumOf { it.size }
                summary.text = if (all.isEmpty()) "No clips yet" else "${all.size} clips · %.1f GB".format(total / 1e9)
            }
        }.start()
    }

    private fun dayStart(ms: Long): Long = Calendar.getInstance().apply {
        timeInMillis = ms
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun buildRows() {
        val wDp = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        cols = maxOf(2, (wDp / 170).toInt())
        val out = ArrayList<Row>()
        for ((day, items) in clips.groupBy { dayStart(it.startMs) }.toSortedMap(compareByDescending { it })) {
            out += Row.Header(day, items)
            items.chunked(cols).forEach { out += Row.Items(it) }
        }
        rows = out
        display = out.filterIsInstance<Row.Items>().flatMap { it.clips }
        selected.retainAll(display.map { it.id }.toSet())
        if (selecting && selected.isEmpty()) exitSelection() else updateSelection()
    }

    private fun dayLabel(day: Long): String {
        val today = dayStart(System.currentTimeMillis())
        return when (day) {
            today -> "Today"
            today - 86_400_000L -> "Yesterday"
            else -> SimpleDateFormat("EEE, MMM d yyyy", Locale.getDefault()).format(Date(day))
        }
    }

    private fun play(c: Clip) {
        startActivity(Intent(this, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_START_ID, c.id))
    }

    private fun options(c: Clip) {
        val items = arrayOf("Play in timeline", "Open in another app", "Share", "Delete")
        AlertDialog.Builder(this).setTitle("Clip at ${timeFmt.format(Date(c.startMs))}")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> play(c)
                    1 -> startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(c.uri, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                    2 -> startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("video/mp4")
                        .putExtra(Intent.EXTRA_STREAM, c.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share clip"))
                    3 -> AlertDialog.Builder(this).setTitle("Delete this clip?")
                        .setPositiveButton("Delete") { _, _ -> ClipDeleter.delete(this, listOf(c)) { reload() } }
                        .setNegativeButton("Cancel", null).show()
                }
            }.show()
    }

    private fun dayOptions(h: Row.Header) {
        AlertDialog.Builder(this).setTitle(dayLabel(h.dayStart))
            .setItems(arrayOf("Play day in timeline", "Delete all ${h.clips.size} clips of this day")) { _, which ->
                if (which == 0) h.clips.minByOrNull { it.startMs }?.let { play(it) }
                else AlertDialog.Builder(this).setTitle("Delete ${h.clips.size} clips?")
                    .setPositiveButton("Delete") { _, _ ->
                        ClipDeleter.delete(this, h.clips) { reload() }
                    }
                    .setNegativeButton("Cancel", null).show()
            }.show()
    }

    private fun bindThumb(iv: ImageView, c: Clip) {
        iv.tag = c.id
        val cached = thumbs.get(c.id)
        if (cached != null) { iv.setImageBitmap(cached); return }
        iv.setImageDrawable(null)
        pool.execute {
            val bmp = try { contentResolver.loadThumbnail(c.uri, Size(384, 216), null) } catch (e: Exception) { null }
            if (bmp != null) thumbs.put(c.id, bmp)
            runOnUiThread { if (iv.tag == c.id) iv.setImageBitmap(bmp) }
        }
    }

    private fun durText(ms: Long): String {
        val s = ms / 1000
        return if (s >= 60) "%d:%02d".format(s / 60, s % 60) else "${s}s"
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int) = if (rows[position] is Row.Header) 0 else 1

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row = rows[position]
            if (row is Row.Header) {
                val tv = (convertView as? TextView) ?: Ui.label(this@ClipsActivity, "", 15f, Ui.ACCENT).apply {
                    setPadding(Ui.dp(context, 14), Ui.dp(context, 16), Ui.dp(context, 14), Ui.dp(context, 6))
                }
                val dur = row.clips.sumOf { it.durationMs }
                tv.text = "${dayLabel(row.dayStart)}   ·   ${row.clips.size} clips · ${durText(dur)}"
                tv.setOnClickListener {
                    if (selecting) {
                        val ids = row.clips.map { it.id }
                        if (selected.containsAll(ids)) selected.removeAll(ids.toSet()) else selected.addAll(ids)
                        updateSelection()
                    } else dayOptions(row)
                }
                return tv
            }
            row as Row.Items
            val line = (convertView as? LinearLayout)?.takeIf { it.childCount == cols } ?: LinearLayout(this@ClipsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                val p = Ui.dp(context, 4)
                setPadding(p, p, p, p)
                for (i in 0 until cols) addView(cell(), LinearLayout.LayoutParams(0, -2, 1f))
            }
            for (i in 0 until cols) {
                val cell = line.getChildAt(i) as FrameLayout
                val c = row.clips.getOrNull(i)
                cell.visibility = if (c == null) View.INVISIBLE else View.VISIBLE
                if (c == null) continue
                val iv = cell.getChildAt(0) as ImageView
                val label = cell.getChildAt(1) as TextView
                val check = cell.getChildAt(2) as TextView
                bindThumb(iv, c)
                label.text = "${timeFmt.format(Date(c.startMs))}  ·  ${durText(c.durationMs)}"
                val sel = c.id in selected
                check.visibility = if (selecting) View.VISIBLE else View.GONE
                check.text = if (sel) "✓" else ""
                (check.background as GradientDrawable).setColor(if (sel) Ui.ACCENT else 0x66000000)
                cell.foreground = if (sel) selFrame else null
                iv.alpha = if (selecting && !sel) 0.75f else 1f
                cell.setOnClickListener {
                    if (selecting) { if (!selected.remove(c.id)) selected += c.id; updateSelection() } else play(c)
                }
                cell.setOnLongClickListener {
                    // hold = select; keep holding and drag across other clips to select a range
                    if (!selecting) { selecting = true; selected.clear() }
                    selected += c.id
                    baseSel = selected.toSet()
                    anchorIdx = display.indexOfFirst { it.id == c.id }
                    dragSelecting = true
                    it.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                    updateSelection()
                    true
                }
            }
            return line
        }
    }

    private fun cell(): FrameLayout {
        val ctx = this
        val w = resources.displayMetrics.widthPixels / cols - Ui.dp(ctx, 10)
        return FrameLayout(ctx).apply {
            val p = Ui.dp(ctx, 3)
            setPadding(p, p, p, p)
            val iv = ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = GradientDrawable().apply { setColor(0xFF22252A.toInt()); cornerRadius = Ui.dp(ctx, 6).toFloat() }
                clipToOutline = true
            }
            addView(iv, FrameLayout.LayoutParams(-1, w * 9 / 16))
            val tv = Ui.label(ctx, "", 11f, Color.WHITE).apply {
                setBackgroundColor(0x99000000.toInt())
                setPadding(Ui.dp(ctx, 6), Ui.dp(ctx, 2), Ui.dp(ctx, 6), Ui.dp(ctx, 2))
            }
            addView(tv, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START))
            val check = Ui.label(ctx, "", 13f, Color.BLACK).apply {
                gravity = Gravity.CENTER
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x66000000); setStroke(Ui.dp(ctx, 2), Color.WHITE) }
                visibility = View.GONE
            }
            addView(check, FrameLayout.LayoutParams(Ui.dp(ctx, 24), Ui.dp(ctx, 24), Gravity.TOP or Gravity.END).apply {
                setMargins(0, Ui.dp(ctx, 6), Ui.dp(ctx, 6), 0)
            })
        }
    }

    private val selFrame by lazy {
        GradientDrawable().apply { setColor(0x00000000); setStroke(Ui.dp(this@ClipsActivity, 3), Ui.ACCENT); cornerRadius = Ui.dp(this@ClipsActivity, 8).toFloat() }
    }

    // ------------------------------------------------------------------ multi-select

    private fun updateSelection() {
        normalBar.visibility = if (selecting) View.GONE else View.VISIBLE
        selBar.visibility = if (selecting) View.VISIBLE else View.GONE
        selTitle.text = "${selected.size} selected"
        adapter.notifyDataSetChanged()
    }

    private fun exitSelection() {
        selecting = false
        dragSelecting = false
        selected.clear()
        updateSelection()
    }

    private fun selectedClips() = display.filter { it.id in selected }

    private fun deleteSelected() {
        val list = selectedClips()
        if (list.isEmpty()) return
        AlertDialog.Builder(this).setTitle("Delete ${list.size} clip${if (list.size == 1) "" else "s"}?")
            .setPositiveButton("Delete") { _, _ -> ClipDeleter.delete(this, list) { exitSelection(); reload() } }
            .setNegativeButton("Cancel", null).show()
    }

    private fun shareSelected() {
        val list = selectedClips()
        if (list.isEmpty()) return
        val uris = ArrayList(list.map { it.uri })
        val i = Intent(Intent.ACTION_SEND_MULTIPLE).setType("video/mp4")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        i.clipData = android.content.ClipData.newRawUri("", uris[0]).also { cd -> uris.drop(1).forEach { cd.addItem(android.content.ClipData.Item(it)) } }
        startActivity(Intent.createChooser(i, "Share ${list.size} clips"))
    }

    /** The clip under a screen position, if any (nearest column within the row). */
    private fun clipAt(rawX: Float, rawY: Float): Int {
        val loc = IntArray(2)
        list.getLocationOnScreen(loc)
        val x = (rawX - loc[0]).toInt()
        val y = (rawY - loc[1]).toInt().coerceIn(0, list.height - 1)
        val pos = list.pointToPosition(x.coerceIn(0, list.width - 1), y)
        if (pos == AdapterView.INVALID_POSITION) return -1
        val row = rows.getOrNull(pos) as? Row.Items ?: return -1
        val rv = list.getChildAt(pos - list.firstVisiblePosition) ?: return -1
        val col = ((x - rv.left) * cols / maxOf(1, rv.width)).coerceIn(0, cols - 1)
        val c = row.clips.getOrNull(col) ?: row.clips.last()
        return display.indexOfFirst { it.id == c.id }
    }

    private fun dragTo(rawX: Float, rawY: Float) {
        val idx = clipAt(rawX, rawY)
        if (idx < 0 || anchorIdx < 0) return
        val range = (minOf(anchorIdx, idx)..maxOf(anchorIdx, idx)).map { display[it].id }
        val next = LinkedHashSet(baseSel).apply { addAll(range) }
        if (next != selected) { selected.clear(); selected.addAll(next); updateSelection() }
    }

    private val autoScroll = object : Runnable {
        override fun run() {
            if (!dragSelecting) return
            val loc = IntArray(2)
            list.getLocationOnScreen(loc)
            val y = lastRawY - loc[1]
            val edge = Ui.dp(this@ClipsActivity, 72)
            val step = when {
                y < edge -> -((edge - y) / 3).toInt().coerceAtLeast(4)
                y > list.height - edge -> ((y - (list.height - edge)) / 3).toInt().coerceAtLeast(4)
                else -> 0
            }
            if (step != 0) { list.scrollListBy(step); dragTo(lastRawX, lastRawY) }
            list.postOnAnimation(this)
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (!dragSelecting) return super.dispatchTouchEvent(ev)
        lastRawX = ev.rawX
        lastRawY = ev.rawY
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                list.removeCallbacks(autoScroll)
                list.postOnAnimation(autoScroll)
                dragTo(ev.rawX, ev.rawY)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragSelecting = false
                list.removeCallbacks(autoScroll)
                // end the list's own gesture without triggering a click
                val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
                super.dispatchTouchEvent(cancel)
                cancel.recycle()
            }
        }
        return true
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (selecting) { exitSelection(); return }
        @Suppress("DEPRECATION") super.onBackPressed()
    }
}
