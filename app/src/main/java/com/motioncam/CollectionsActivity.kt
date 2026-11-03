package com.motioncam

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Size
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors

/** List of collections (Favourites + user-made). Each opens the normal clip gallery filtered to it. */
class CollectionsActivity : Activity() {
    private lateinit var box: LinearLayout
    private val pool = Executors.newFixedThreadPool(2)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.BG) }
        val add = Ui.chip(this, "＋ New").apply {
            setOnClickListener { CollectionPicker.askName(this@CollectionsActivity, "New collection", "") { Collections.create(this@CollectionsActivity, it); reload() } }
        }
        root.addView(topBar(this, "Collections", add))
        box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = Ui.dp(this@CollectionsActivity, 12)
            setPadding(p, 0, p, p)
        }
        root.addView(ScrollView(this).apply { addView(box) }, LinearLayout.LayoutParams(-1, 0, 1f))
        Ui.capWidth(box)
        setContentView(root)
    }

    override fun onResume() { super.onResume(); reload() }

    override fun onDestroy() { pool.shutdownNow(); super.onDestroy() }

    private fun reload() {
        Thread {
            val existing = ClipStore.list(this)
            val byId = existing.associateBy { it.id }
            val cols = Collections.all(this).map { c -> Pair(c, c.clipIds.mapNotNull { byId[it] }.sortedByDescending { it.startMs }) }
            runOnUiThread { render(cols) }
        }.start()
    }

    private fun render(cols: List<Pair<ClipCollection, List<Clip>>>) {
        box.removeAllViews()
        for ((c, clips) in cols) box.addView(row(c, clips))
        box.addView(Ui.label(this, "Collections only link to clips: a clip can be in several collections, and removing it " +
            "from one never deletes it. To add clips, hold a clip in Clips to select it, then tap Add to…", 12f, Ui.GREY).apply {
            setPadding(Ui.dp(this@CollectionsActivity, 4), Ui.dp(this@CollectionsActivity, 16), 0, 0)
        })
    }

    private fun row(c: ClipCollection, clips: List<Clip>): View {
        val ctx = this
        val r = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, Ui.dp(ctx, 8), 0, Ui.dp(ctx, 8))
            isClickable = true
            setOnClickListener {
                startActivity(Intent(ctx, ClipsActivity::class.java).putExtra(ClipsActivity.EXTRA_COLLECTION, c.id))
            }
        }
        val coverW = Ui.dp(ctx, 112)
        val cover = FrameLayout(ctx).apply {
            background = GradientDrawable().apply { setColor(0xFF22252A.toInt()); cornerRadius = Ui.dp(ctx, 8).toFloat() }
            clipToOutline = true
        }
        val iv = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        cover.addView(iv, FrameLayout.LayoutParams(-1, -1))
        if (clips.isEmpty()) cover.addView(Ui.label(ctx, if (c.isFavourites) "★" else "▦", 26f, Ui.GREY).apply { gravity = Gravity.CENTER },
            FrameLayout.LayoutParams(-1, -1))
        r.addView(cover, LinearLayout.LayoutParams(coverW, coverW * 9 / 16))
        clips.firstOrNull()?.let { first ->
            pool.execute {
                val bmp: Bitmap? = try { contentResolver.loadThumbnail(first.uri, Size(320, 180), null) } catch (e: Exception) { null }
                runOnUiThread { iv.setImageBitmap(bmp) }
            }
        }
        val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dp(ctx, 14), 0, 0, 0) }
        texts.addView(Ui.label(ctx, (if (c.isFavourites) "★ " else "") + c.name, 16f, if (c.isFavourites) Ui.ACCENT else android.graphics.Color.WHITE))
        texts.addView(Ui.label(ctx, "${clips.size} clip${if (clips.size == 1) "" else "s"}", 12f, Ui.GREY))
        r.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        if (!c.isFavourites) {
            lateinit var more: View
            more = iconButton(ctx, IconDrawable.Kind.MORE, 44, 10) {
                val pm = PopupMenu(ctx, more)
                pm.menu.add(0, 1, 0, "Rename")
                pm.menu.add(0, 2, 1, "Delete collection")
                pm.setOnMenuItemClickListener {
                    if (it.itemId == 1) CollectionPicker.askName(ctx, "Rename collection", c.name) { n -> Collections.rename(ctx, c.id, n); reload() }
                    else AlertDialog.Builder(ctx).setTitle("Delete \"${c.name}\"?")
                        .setMessage("Only the collection is deleted. The ${clips.size} clips stay in Clips.")
                        .setPositiveButton("Delete") { _, _ -> Collections.delete(ctx, c.id); reload() }
                        .setNegativeButton("Cancel", null).show()
                    true
                }
                pm.show()
            }
            r.addView(more)
        }
        return r
    }
}
