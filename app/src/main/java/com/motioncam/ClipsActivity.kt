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
        root.addView(topBar(this, "Clips", playAll))
        summary = Ui.label(this, "", 12f, Ui.GREY).apply { setPadding(Ui.dp(this@ClipsActivity, 52), 0, 0, Ui.dp(this@ClipsActivity, 6)) }
        root.addView(summary)
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
        reload()
    }

    override fun onDestroy() {
        pool.shutdownNow()
        super.onDestroy()
    }

    private fun reload() {
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
        adapter.notifyDataSetChanged()
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
                        .setPositiveButton("Delete") { _, _ -> ClipStore.delete(this, c); reload() }
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
                        Thread { h.clips.forEach { ClipStore.delete(this, it) }; runOnUiThread { reload() } }.start()
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
                tv.setOnClickListener { dayOptions(row) }
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
                bindThumb(iv, c)
                label.text = "${timeFmt.format(Date(c.startMs))}  ·  ${durText(c.durationMs)}"
                cell.setOnClickListener { play(c) }
                cell.setOnLongClickListener { options(c); true }
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
        }
    }
}
