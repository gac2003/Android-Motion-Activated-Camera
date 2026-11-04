package com.motioncam.cloud

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.motioncam.Clip
import com.motioncam.ClipStore
import com.motioncam.ClipThumbs
import com.motioncam.Fmt
import com.motioncam.PlayerActivity
import com.motioncam.Prefs
import com.motioncam.Ui
import com.motioncam.remote.RemoteRepo
import com.motioncam.remote.RemoteSources
import com.motioncam.topBar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Every clip protected in a cloud folder, by any device signed in to the account, with why: protected by
 * hand, or kept because it is in a collection (and on which phone). Any device can remove protections here.
 * Opened for this phone's backup destinations or for a remote folder.
 */
class ProtectedActivity : Activity() {
    companion object {
        /** MarkSync space ("b:<kind>" or "r:<source id>"); default: this phone's backup destinations. */
        const val EXTRA_SPACE = "space"
    }

    private class Row(val name: String, val marks: ClipMarks, val clip: Clip?, val inFolder: Boolean)

    private lateinit var prefs: Prefs
    private var spaces: List<String> = emptyList()
    private var space = ""
    private var filter = 0                   // 0 all, 1 by hand, 2 by collection
    private var all: List<Row> = emptyList()
    private var rows: List<Row> = emptyList()
    private val selected = LinkedHashSet<String>()
    private var writable = true
    private lateinit var summary: TextView
    private lateinit var tabs: LinearLayout
    private lateinit var filters: LinearLayout
    private lateinit var actionBar: LinearLayout
    private lateinit var removeBtn: TextView
    private lateinit var list: ListView
    private val pool = Executors.newFixedThreadPool(2)
    private val thumbs = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 10).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val dayFmt = SimpleDateFormat("EEE, MMM d", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        spaces = intent.getStringExtra(EXTRA_SPACE)?.let { listOf(it) } ?: MarkSync.localSpaces(this)
        if (spaces.isEmpty()) {
            Toast.makeText(this, "Set up cloud backup first (Settings → Cloud backup)", Toast.LENGTH_LONG).show()
            finish(); return
        }
        space = spaces.first()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.BG) }
        val refresh = Ui.chip(this, "Refresh").apply { setOnClickListener { load() } }
        root.addView(topBar(this, "Protected in cloud", refresh))
        tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(Ui.dp(this@ProtectedActivity, 12), 0, 0, 0) }
        root.addView(tabs)
        summary = Ui.label(this, "Loading…", 13f, Ui.GREY).apply {
            setPadding(Ui.dp(this@ProtectedActivity, 16), Ui.dp(this@ProtectedActivity, 4), Ui.dp(this@ProtectedActivity, 16), Ui.dp(this@ProtectedActivity, 4))
        }
        root.addView(summary)
        filters = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(Ui.dp(this@ProtectedActivity, 12), 0, 0, Ui.dp(this@ProtectedActivity, 6)) }
        root.addView(filters)
        list = ListView(this).apply { divider = null; setBackgroundColor(Ui.BG) }
        list.adapter = adapter
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        actionBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Ui.PANEL)
            val p = Ui.dp(this@ProtectedActivity, 10)
            setPadding(p, p, p, p)
        }
        actionBar.addView(Ui.chip(this, "Select all").apply {
            setOnClickListener { if (selected.size == rows.size) selected.clear() else rows.forEach { selected += it.name }; updateSelection() }
        })
        actionBar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        removeBtn = Ui.chip(this, "Remove protection").apply { setTextColor(Ui.RED); setOnClickListener { confirmRemove() } }
        actionBar.addView(removeBtn)
        root.addView(actionBar)
        setContentView(root)
        buildTabs()
        buildFilters()
        updateSelection()
        load()
    }

    override fun onDestroy() { pool.shutdownNow(); super.onDestroy() }

    private fun buildTabs() {
        tabs.removeAllViews()
        tabs.visibility = if (spaces.size > 1) View.VISIBLE else View.GONE
        for (s in spaces) tabs.addView(Ui.chip(this, MarkSync.label(this, s)).apply {
            Ui.setChipState(this, s == space)
            setOnClickListener { if (space != s) { space = s; selected.clear(); buildTabs(); load() } }
        }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = Ui.dp(this@ProtectedActivity, 6) })
    }

    private fun buildFilters() {
        filters.removeAllViews()
        listOf("All", "By hand", "By collection").forEachIndexed { i, t ->
            filters.addView(Ui.chip(this, t).apply {
                Ui.setChipState(this, i == filter)
                setOnClickListener { filter = i; buildFilters(); applyFilter() }
            }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = Ui.dp(this@ProtectedActivity, 6) })
        }
    }

    private fun load() {
        summary.text = "Checking ${MarkSync.label(this, space)}…"
        val sp = space
        Thread {
            val res = try { Result.success(MarkSync.syncNow(this, sp)) } catch (e: Exception) { Result.failure(e) }
            val clips = clipsByName(sp)
            runOnUiThread {
                if (sp != space || isFinishing) return@runOnUiThread
                res.onFailure { e -> summary.text = "Could not read the protections: ${e.message}"; return@runOnUiThread }
                val r = res.getOrThrow()
                writable = r.writable
                all = r.marks.values.filter { it.protected }.map { Row(it.clip, it, clips[it.clip], it.clip in r.existing) }
                    .sortedByDescending { it.clip?.startMs ?: startOf(it.name) }
                selected.retainAll(all.map { it.name }.toSet())
                applyFilter()
            }
        }.start()
    }

    private fun clipsByName(sp: String): Map<String, Clip> =
        if (sp.startsWith("r:")) RemoteSources.get(this, sp.substring(2))?.let { s -> RemoteRepo.cached(this, s).associateBy { it.name } } ?: emptyMap()
        else ClipStore.list(this).associateBy { it.name }

    private fun startOf(name: String): Long = try {
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).parse(name.substring(3, 18))!!.time
    } catch (e: Exception) { 0 }

    private fun applyFilter() {
        rows = when (filter) {
            1 -> all.filter { r -> r.marks.marks.any { it.manual } }
            2 -> all.filter { r -> r.marks.marks.any { it.collections.isNotEmpty() } }
            else -> all
        }
        val bytes = all.sumOf { it.clip?.size ?: 0L }
        val byHand = all.count { r -> r.marks.marks.any { it.manual } }
        val byCol = all.count { r -> r.marks.marks.any { it.collections.isNotEmpty() } }
        summary.text = if (all.isEmpty()) "Nothing is protected in ${MarkSync.label(this, space)}. Clips in collections and clips protected by hand appear here."
        else "${all.size} protected clip${if (all.size == 1) "" else "s"} in ${MarkSync.label(this, space)}" +
            (if (bytes > 0) " · %.2f GB".format(bytes / 1e9) else "") + " · $byHand by hand, $byCol by collection" +
            if (!writable) "\nRead-only (viewer link): protections can only be changed by the account's phones." else ""
        updateSelection()
    }

    private fun updateSelection() {
        actionBar.visibility = if (writable && rows.isNotEmpty()) View.VISIBLE else View.GONE
        removeBtn.text = if (selected.isEmpty()) "Remove protection" else "Remove protection (${selected.size})"
        removeBtn.alpha = if (selected.isEmpty()) 0.4f else 1f
        adapter.notifyDataSetChanged()
    }

    private fun confirmRemove() {
        if (selected.isEmpty()) { Toast.makeText(this, "Tap clips to select them first", Toast.LENGTH_SHORT).show(); return }
        val names = selected.toList()
        val fromOthers = all.filter { it.name in names }.flatMap { it.marks.marks }.filter { it.device != Device.id(this) }.map { it.deviceName }.distinct()
        AlertDialog.Builder(this).setTitle("Remove protection from ${names.size} clip${if (names.size == 1) "" else "s"}?")
            .setMessage("They become subject to the camera phone's normal clean-up again (age limit, \"when full\") and may be deleted from " +
                "${MarkSync.label(this, space)}." +
                (if (fromOthers.isNotEmpty()) "\n\nThis also removes protections set by: ${fromOthers.joinToString()}. Their collections keep the clips " +
                    "listed, and they save a copy on their phone if that setting is on." else "") +
                "\n\nAdding a clip to a collection (or protecting it) again later protects it again.")
            .setPositiveButton("Remove protection") { _, _ -> doRemove(names) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun doRemove(names: List<String>) {
        summary.text = "Removing protection…"
        val sp = space
        Thread {
            val msg = try { MarkSync.override(this, sp, names); null } catch (e: Exception) { e.message ?: "failed" }
            runOnUiThread {
                if (msg != null) Toast.makeText(this, "Could not remove: $msg", Toast.LENGTH_LONG).show()
                selected.removeAll(names.toSet())
                load()
            }
        }.start()
    }

    // ------------------------------------------------------------------ rows

    private fun reasonText(m: Mark): String {
        val who = if (m.device == Device.id(this)) "this phone" else m.deviceName
        val parts = ArrayList<String>()
        if (m.manual) parts += "Protected by hand on $who"
        if (m.collections.isNotEmpty()) parts += "In " + m.collections.joinToString { "“$it”" } + " on $who"
        val day = if (m.at > 0) "  ·  " + dayFmt.format(Date(m.at)) else ""
        return parts.joinToString("\n") + day
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val r = rows[position]
            val v = (convertView as? LinearLayout) ?: rowView()
            val thumbBox = v.getChildAt(0) as FrameLayout
            val iv = thumbBox.getChildAt(0) as ImageView
            val texts = v.getChildAt(1) as LinearLayout
            val title = texts.getChildAt(0) as TextView
            val why = texts.getChildAt(1) as TextView
            val check = v.getChildAt(2) as TextView
            val c = r.clip
            val start = c?.startMs ?: startOf(r.name)
            title.text = (if (start > 0) dayFmt.format(Date(start)) + "  " + Fmt.t(prefs, start, seconds = false) else r.name) +
                (c?.let { "  ·  ${it.durationMs / 1000}s · %.0f MB".format(it.size / 1e6) } ?: "") +
                if (!r.inFolder) "  ·  not in the folder" else ""
            why.text = r.marks.marks.sortedByDescending { it.at }.joinToString("\n") { reasonText(it) }
            val sel = r.name in selected
            check.text = if (sel) "✓" else ""
            (check.background as GradientDrawable).setColor(if (sel) Ui.ACCENT else 0x00000000)
            check.visibility = if (writable) View.VISIBLE else View.GONE
            v.setOnClickListener {
                if (!writable) return@setOnClickListener
                if (!selected.remove(r.name)) selected += r.name
                updateSelection()
            }
            v.setOnLongClickListener { play(r); true }
            iv.tag = r.name
            val cached = thumbs.get(r.name)
            iv.setImageBitmap(cached)
            if (cached == null && c != null) pool.execute {
                if (iv.tag != r.name) return@execute
                val b = try { ClipThumbs.load(this@ProtectedActivity, c) } catch (e: Exception) { null } ?: return@execute
                thumbs.put(r.name, b)
                runOnUiThread { if (iv.tag == r.name) iv.setImageBitmap(b) }
            }
            return v
        }
    }

    /** Long-press: play the clip (on this phone, or streamed from the remote folder). */
    private fun play(r: Row) {
        val c = r.clip ?: run { Toast.makeText(this, "Not on this phone", Toast.LENGTH_SHORT).show(); return }
        startActivity(Intent(this, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_START_ID, c.id)
            .putExtra(PlayerActivity.EXTRA_REMOTE, if (space.startsWith("r:")) space.substring(2) else null))
    }

    private fun rowView(): LinearLayout {
        val ctx = this
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(ctx, 12), Ui.dp(ctx, 8), Ui.dp(ctx, 12), Ui.dp(ctx, 8))
            val w = Ui.dp(ctx, 112)
            val box = FrameLayout(ctx).apply {
                background = GradientDrawable().apply { setColor(0xFF22252A.toInt()); cornerRadius = Ui.dp(ctx, 6).toFloat() }
                clipToOutline = true
            }
            box.addView(ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }, FrameLayout.LayoutParams(-1, -1))
            addView(box, LinearLayout.LayoutParams(w, w * 9 / 16))
            val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dp(ctx, 12), 0, Ui.dp(ctx, 8), 0) }
            texts.addView(Ui.label(ctx, "", 14f))
            texts.addView(Ui.label(ctx, "", 12f, Ui.GREY))
            addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
            addView(Ui.label(ctx, "", 13f, Color.BLACK).apply {
                gravity = Gravity.CENTER
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setStroke(Ui.dp(ctx, 2), Color.WHITE) }
            }, LinearLayout.LayoutParams(Ui.dp(ctx, 24), Ui.dp(ctx, 24)))
        }
    }
}
