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
    companion object {
        /** Shared collections of a remote folder. */
        const val EXTRA_REMOTE = "remote"
    }

    private var remote: com.motioncam.remote.RemoteSource? = null
    private var store: CollectionStore = Collections
    private lateinit var box: LinearLayout
    private val pool = Executors.newFixedThreadPool(2)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent.getStringExtra(EXTRA_REMOTE)?.let { rid ->
            remote = com.motioncam.remote.RemoteSources.get(this, rid) ?: run { finish(); return }
            store = Collections.forRemote(rid)
        }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.BG) }
        val add = Ui.chip(this, "＋ New").apply {
            setOnClickListener { CollectionPicker.askName(this@CollectionsActivity, "New collection", "") { store.create(this@CollectionsActivity, it); reload() } }
        }
        root.addView(topBar(this, remote?.let { "Collections · ${it.name}" } ?: "Collections", if (canEdit()) add else null))
        box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = Ui.dp(this@CollectionsActivity, 12)
            setPadding(p, 0, p, p)
        }
        root.addView(ScrollView(this).apply { addView(box) }, LinearLayout.LayoutParams(-1, 0, 1f))
        Ui.capWidth(box)
        setContentView(root)
    }

    /** Shared collections can't be changed through a read-only Dropbox viewer link. */
    private fun canEdit() = remote?.mode != com.motioncam.remote.RemoteSources.MODE_LINK

    private val synced: (String) -> Unit = { id -> if (id == remote?.id) runOnUiThread { reload() } }

    override fun onResume() {
        super.onResume()
        reload()
        remote?.let { com.motioncam.remote.SharedSync.listeners += synced; com.motioncam.remote.SharedSync.schedule(this, it.id, 0) }
    }

    override fun onPause() { com.motioncam.remote.SharedSync.listeners -= synced; super.onPause() }

    override fun onDestroy() { pool.shutdownNow(); super.onDestroy() }

    private fun reload() {
        Thread {
            val src = remote
            val existing = if (src != null) com.motioncam.remote.RemoteRepo.cached(this, src) else ClipStore.list(this)
            val byId = existing.associateBy { it.id }
            val cols = store.all(this).map { c -> Pair(c, c.clipIds.mapNotNull { byId[it] }.sortedByDescending { it.startMs }) }
            missing = store.all(this).associate { c -> c.id to c.clipIds.count { it !in byId } }
            runOnUiThread { render(cols) }
        }.start()
    }

    private var missing: Map<String, Int> = emptyMap()

    /** Remote folders: shared-collections sync state; tap = Sync status (conflicts, waiting changes, tools). */
    private fun syncRow(): View? {
        val src = remote ?: return null
        val s = com.motioncam.remote.SharedSync.state(this, src.id)
        val waiting = s.outbox.count { !it.held }
        val parts = ArrayList<String>()
        parts += "Shared with every phone on this account"
        parts += when {
            s.lastSync == 0L && s.lastError != null -> "not synced: ${s.lastError}"
            s.lastSync == 0L -> "syncing…"
            else -> "synced " + Fmt.t(Prefs(this), s.lastSync, seconds = false) + if (s.lastError != null) " (offline now)" else ""
        }
        if (waiting > 0) parts += "$waiting change${if (waiting == 1) "" else "s"} waiting"
        val tv = Ui.label(this, parts.joinToString("  ·  ") + (if (s.conflicts.isNotEmpty()) "\n⚠ ${s.conflicts.size} sync conflict${if (s.conflicts.size == 1) "" else "s"} need" +
            "${if (s.conflicts.size == 1) "s" else ""} your decision — tap" else "  ·  Sync status ›"), 12f, if (s.conflicts.isNotEmpty()) Ui.ACCENT else Ui.GREY)
        tv.setPadding(Ui.dp(this, 4), Ui.dp(this, 6), Ui.dp(this, 4), Ui.dp(this, 6))
        tv.setOnClickListener {
            startActivity(Intent(this, com.motioncam.remote.SyncStatusActivity::class.java).putExtra(com.motioncam.remote.SyncStatusActivity.EXTRA_SOURCE, src.id))
        }
        return tv
    }

    private fun render(cols: List<Pair<ClipCollection, List<Clip>>>) {
        box.removeAllViews()
        syncRow()?.let { box.addView(it) }
        protectedRow()?.let { box.addView(it) }
        for ((c, clips) in cols) box.addView(row(c, clips))
        box.addView(Ui.label(this, (if (remote != null) "These collections are shared: every phone signed in to this account sees and can change " +
            "them (they are stored in the folder). Clips in them are protected from the camera phone's clean-up. " else "") +
            "Collections only link to clips: a clip can be in several collections, and removing it " +
            "from one never deletes it. To add clips, hold a clip in Clips to select it, then tap Add to…", 12f, Ui.GREY).apply {
            setPadding(Ui.dp(this@CollectionsActivity, 4), Ui.dp(this@CollectionsActivity, 16), 0, 0)
        })
    }

    /** "Protected in cloud": every clip any device keeps from the clean-up, and why (where cloud backup is set up). */
    private fun protectedRow(): View? {
        val space = remote?.let { com.motioncam.cloud.MarkSync.remoteKey(it.id) }
        if (space == null && com.motioncam.cloud.MarkSync.localSpaces(this).isEmpty()) return null
        val ctx = this
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, Ui.dp(ctx, 8), 0, Ui.dp(ctx, 8))
            isClickable = true
            setOnClickListener {
                startActivity(Intent(ctx, com.motioncam.cloud.ProtectedActivity::class.java).apply {
                    if (space != null) putExtra(com.motioncam.cloud.ProtectedActivity.EXTRA_SPACE, space)
                })
            }
            val coverW = Ui.dp(ctx, 112)
            addView(Ui.label(ctx, "\uD83D\uDD12", 26f).apply {
                gravity = Gravity.CENTER
                background = GradientDrawable().apply { setColor(0xFF22252A.toInt()); cornerRadius = Ui.dp(ctx, 8).toFloat() }
            }, LinearLayout.LayoutParams(coverW, coverW * 9 / 16))
            val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dp(ctx, 14), 0, 0, 0) }
            texts.addView(Ui.label(ctx, "Protected in cloud", 16f, Ui.ACCENT))
            texts.addView(Ui.label(ctx, "Clips any phone on the account keeps from clean-up, and why", 12f, Ui.GREY))
            addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        }
    }

    private fun row(c: ClipCollection, clips: List<Clip>): View {
        val ctx = this
        val r = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, Ui.dp(ctx, 8), 0, Ui.dp(ctx, 8))
            isClickable = true
            setOnClickListener {
                startActivity(Intent(ctx, ClipsActivity::class.java).putExtra(ClipsActivity.EXTRA_COLLECTION, c.id)
                    .putExtra(ClipsActivity.EXTRA_REMOTE, remote?.id))
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
                val bmp: Bitmap? = ClipThumbs.load(this, first)
                runOnUiThread { iv.setImageBitmap(bmp) }
            }
        }
        val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dp(ctx, 14), 0, 0, 0) }
        texts.addView(Ui.label(ctx, (if (c.isFavourites) "★ " else "") + c.name, 16f, if (c.isFavourites) Ui.ACCENT else android.graphics.Color.WHITE))
        val miss = missing[c.id] ?: 0
        texts.addView(Ui.label(ctx, "${clips.size} clip${if (clips.size == 1) "" else "s"}" +
            (if (remote != null && miss > 0) "  ·  ☁✕ $miss missing from the cloud" else ""), 12f, if (miss > 0 && remote != null) Ui.ACCENT else Ui.GREY))
        r.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        val canProtect = clips.isNotEmpty() && com.motioncam.cloud.CloudProtect.canChange(ctx, remote)
        if ((!c.isFavourites && canEdit()) || canProtect || clips.isNotEmpty()) {
            lateinit var more: View
            more = iconButton(ctx, IconDrawable.Kind.MORE, 44, 10) {
                val pm = PopupMenu(ctx, more)
                if (!c.isFavourites && canEdit()) {
                    pm.menu.add(0, 1, 0, "Rename")
                    pm.menu.add(0, 2, 1, "Delete collection")
                }
                if (clips.isNotEmpty()) {
                    if (remote != null) pm.menu.add(0, 5, 4, "Save all ${clips.size} to this phone")
                    else pm.menu.add(0, 6, 4, "Back up all ${clips.size} to the cloud")
                }
                if (canProtect) {
                    pm.menu.add(0, 3, 2, "Protect all ${clips.size} in cloud")
                    pm.menu.add(0, 4, 3, "Remove cloud protection from all ${clips.size}")
                }
                pm.setOnMenuItemClickListener {
                    when (it.itemId) {
                        1 -> CollectionPicker.askName(ctx, "Rename collection", c.name) { n -> store.rename(ctx, c.id, n); reload() }
                        2 -> AlertDialog.Builder(ctx).setTitle("Delete \"${c.name}\"?")
                            .setMessage("Only the collection is deleted. The ${clips.size} clips stay in Clips.")
                            .setPositiveButton("Delete") { _, _ -> store.delete(ctx, c.id); reload() }
                            .setNegativeButton("Cancel", null).show()
                        3 -> com.motioncam.cloud.CloudProtect.protect(this, remote, clips.map { cl -> cl.name })
                        4 -> com.motioncam.cloud.CloudProtect.confirmRemove(this, remote, clips.map { cl -> cl.name })
                        5 -> CollectionActions.saveToPhone(this, clips)
                        6 -> CollectionActions.backUpAll(this, clips)
                    }
                    true
                }
                pm.show()
            }
            r.addView(more)
        }
        return r
    }
}
