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
import com.motioncam.remote.RemoteRepo
import com.motioncam.remote.RemoteSource
import com.motioncam.remote.RemoteSources

/** Gallery of recorded clips with thumbnails, grouped by day. Tap = play in timeline. */
class ClipsActivity : Activity() {
    companion object {
        /** Open showing only the clips of this collection. */
        const val EXTRA_COLLECTION = "collection"
        /** Open a remote folder (read-only) instead of the phone's own clips. */
        const val EXTRA_REMOTE = "remote"
    }

    private var collectionId: String? = null
    private var remote: RemoteSource? = null
    private var store: CollectionStore = Collections
    private val ui = android.os.Handler(android.os.Looper.getMainLooper())
    private var refreshing = false
    private var lastError: String? = null
    private val autoRefresh = object : Runnable {
        override fun run() { refreshRemote(quiet = true); ui.postDelayed(this, 5 * 60_000L) }
    }
    private var missing = 0
    private lateinit var actionRow: LinearLayout
    private var swipe: androidx.swiperefreshlayout.widget.SwipeRefreshLayout? = null
    private var favIds: Set<Long> = emptySet()
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
    private lateinit var pinned: LinearLayout
    private var pinnedDay = Long.MIN_VALUE
    /** Days the user collapsed (session only). */
    private val collapsed = HashSet<Long>()
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
    /** Newest request first, so the tiles on screen are done before ones already scrolled past. */
    private class LifoQueue : java.util.concurrent.LinkedBlockingDeque<Runnable>() {
        override fun offer(e: Runnable) = offerFirst(e)
    }
    private fun lifoPool(threads: Int, priority: Int) = java.util.concurrent.ThreadPoolExecutor(threads, threads, 30, java.util.concurrent.TimeUnit.SECONDS, LifoQueue()) { r ->
        Thread(r).apply { this.priority = priority; isDaemon = true }
    }
    private val pool = lifoPool(2, Thread.NORM_PRIORITY)          // placeholders / cached thumbnails
    private val bestPool = lifoPool(3, Thread.MIN_PRIORITY + 1)    // finding the best frame (old clips)
    private val placeholders = object : LruCache<Long, Bitmap>((Runtime.getRuntime().maxMemory() / 16).toInt()) {
        override fun sizeOf(key: Long, value: Bitmap) = value.byteCount
    }
    private val thumbs = object : LruCache<Long, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: Long, value: Bitmap) = value.byteCount
    }
    private lateinit var prefs: Prefs
    private var timeFmt = SimpleDateFormat("h:mm:ss a", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        collectionId = intent.getStringExtra(EXTRA_COLLECTION)
        intent.getStringExtra(EXTRA_REMOTE)?.let { rid ->
            remote = RemoteSources.get(this, rid) ?: run { finish(); return }
            store = Collections.forRemote(rid)
        }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.BG) }
        // actions as a labelled row under the title, so the title keeps the whole top bar
        actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val p = Ui.dp(this@ClipsActivity, 8)
            setPadding(p, 0, p, Ui.dp(this@ClipsActivity, 4))
            fun add(kind: IconDrawable.Kind, label: String, tint: Int = 0xFFFFFFFF.toInt(), click: () -> Unit) =
                addView(labeledButton(this@ClipsActivity, kind, label, tint, click), LinearLayout.LayoutParams(0, -2, 1f))
            if (remote != null) add(IconDrawable.Kind.REFRESH, "Refresh") { refreshRemote(quiet = false) }
            if (collectionId == null && remote == null) add(IconDrawable.Kind.CLOUD, "Remote folders") {
                startActivity(Intent(this@ClipsActivity, com.motioncam.remote.RemoteFoldersActivity::class.java))
            }
            if (collectionId == null) add(IconDrawable.Kind.FOLDER, "Collections") {
                startActivity(Intent(this@ClipsActivity, CollectionsActivity::class.java).putExtra(CollectionsActivity.EXTRA_REMOTE, remote?.id))
            }
            // a collection: get all of it here (remote) or into the cloud (phone)
            if (collectionId != null && remote != null) add(IconDrawable.Kind.DOWNLOAD, "Save all") {
                CollectionActions.saveToPhone(this@ClipsActivity, clips.filter { it.id !in missingIds })
            }
            if (collectionId != null && remote == null) add(IconDrawable.Kind.CLOUD, "Back up all") {
                CollectionActions.backUpAll(this@ClipsActivity, clips)
            }
            add(IconDrawable.Kind.CALENDAR, "Calendar") { showCalendar() }
            add(IconDrawable.Kind.PLAY, "Timeline", Ui.ACCENT) {
                // newest day, from its first clip
                val h = rows.firstOrNull { it is Row.Header } as Row.Header?
                h?.clips?.minByOrNull { it.startMs }?.let { play(it) }
            }
        }
        val title = collectionId?.let { store.get(this, it)?.name } ?: remote?.name ?: "Clips"
        normalBar = topBar(this, title)
        root.addView(normalBar)
        val fit = FitRow(this).apply {
            setBackgroundColor(0xFF2A2416.toInt())
            val p = Ui.dp(this@ClipsActivity, 8)
            setPadding(p, p, p, p)
            visibility = View.GONE
        }
        selBar = fit
        selBar.addView(Ui.label(this, "✕", 22f).apply {
            setPadding(Ui.dp(this@ClipsActivity, 12), 0, Ui.dp(this@ClipsActivity, 16), 0)
            setOnClickListener { exitSelection() }
        })
        selTitle = Ui.label(this, "", 18f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        selBar.addView(selTitle, LinearLayout.LayoutParams(0, -2, 1f))
        fit.title = selTitle
        // label -> action, for buttons that may move into the ⋮ menu on narrow screens
        val selActions = LinkedHashMap<View, Pair<String, () -> Unit>>()
        fun selAction(t: String, click: () -> Unit) = Ui.chip(this, t).apply { setOnClickListener { click() } }.also {
            selBar.addView(it, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = Ui.dp(this@ClipsActivity, 6) })
            selActions[it] = Pair(t, click)
        }
        val allBtn = selAction("All") { if (selected.size == clips.size) selected.clear() else clips.forEach { selected += it.id }; updateSelection() }
        val addBtn = selAction("Add to…") { addSelectedTo() }.apply { if (!canEditShared()) visibility = View.GONE }
        val delBtn = when {
            collectionId != null -> selAction("Remove") { removeSelectedFromCollection() }.apply { if (!canEditShared()) visibility = View.GONE }
            remote != null -> selAction("Save to phone") { saveToPhone(selectedClips()) }
            else -> selAction("Delete") { deleteSelected() }
        }
        fit.collapsible += listOf(addBtn, delBtn, allBtn)   // "All" is the first to move into ⋮ when space runs out
        lateinit var more: View
        more = iconButton(this, IconDrawable.Kind.MORE, 40, 10) {
            val pm = PopupMenu(this, more)
            val overflow = fit.hidden.mapNotNull { selActions[it] }
            overflow.forEachIndexed { i, a -> pm.menu.add(0, 100 + i, i, a.first) }
            if (remote == null) pm.menu.add(0, 1, 50, "Share")
            if (collectionId != null && remote == null) pm.menu.add(0, 2, 51, "Delete from phone")
            if (collectionId != null && remote != null) pm.menu.add(0, 3, 51, "Save to phone")
            if (canMark()) {
                pm.menu.add(0, 4, 52, "Protect in cloud")
                pm.menu.add(0, 5, 53, "Remove cloud protection")
                pm.menu.add(0, 6, 54, "Protected in cloud…")
            }
            pm.setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> shareSelected()
                    2 -> deleteSelected()
                    3 -> saveToPhone(selectedClips())
                    4 -> protect(selectedClips(), true)
                    5 -> protect(selectedClips(), false)
                    6 -> openProtected()
                    else -> overflow.getOrNull(it.itemId - 100)?.second?.invoke()
                }
                true
            }
            pm.show()
        }
        selBar.addView(more)
        root.addView(selBar)
        summary = Ui.label(this, "", 12f, Ui.GREY).apply { setPadding(Ui.dp(this@ClipsActivity, 52), 0, Ui.dp(this@ClipsActivity, 12), Ui.dp(this@ClipsActivity, 4)) }
        root.addView(summary)
        root.addView(actionRow)
        banner = Ui.label(this, "Clips from earlier installs are hidden. Tap to show them.", 13f, Ui.ACCENT).apply {
            setPadding(Ui.dp(this@ClipsActivity, 16), Ui.dp(this@ClipsActivity, 8), Ui.dp(this@ClipsActivity, 16), Ui.dp(this@ClipsActivity, 8))
            setBackgroundColor(0xFF2A2416.toInt())
            setOnClickListener { if (remote != null) removeMissing() else requestPermissions(arrayOf(videoPermission()), 7) }
            visibility = View.GONE
        }
        if (remote != null) summary.setOnClickListener { lastError?.let { AlertDialog.Builder(this).setTitle("Could not refresh").setMessage(it).setPositiveButton("OK", null).show() } }
        root.addView(banner)
        list = ListView(this).apply {
            divider = null
            setBackgroundColor(Ui.BG)
        }
        val listBox = FrameLayout(this)
        listBox.addView(list, FrameLayout.LayoutParams(-1, -1))
        pinned = headerView().apply { setBackgroundColor(Ui.BG); visibility = View.GONE; elevation = Ui.dp(this@ClipsActivity, 2).toFloat() }
        listBox.addView(pinned, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        if (remote != null) {
            // pull down at the top of the list to check for new clips
            swipe = androidx.swiperefreshlayout.widget.SwipeRefreshLayout(this).apply {
                setColorSchemeColors(Ui.ACCENT)
                setProgressBackgroundColorSchemeColor(0xFF2A2D33.toInt())
                setOnChildScrollUpCallback { _, _ -> list.canScrollVertically(-1) }
                setOnRefreshListener { refreshRemote(quiet = true, pulled = true) }
                addView(listBox, ViewGroup.LayoutParams(-1, -1))
            }
            root.addView(swipe, LinearLayout.LayoutParams(-1, 0, 1f))
        } else root.addView(listBox, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        list.adapter = adapter
        list.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(v: AbsListView?, state: Int) {}
            override fun onScroll(v: AbsListView?, first: Int, visible: Int, total: Int) = updatePinned()
        })
    }

    override fun onResume() {
        super.onResume()
        timeFmt = Fmt.time(prefs)
        banner.visibility = if (canSeeAll() || remote != null) View.GONE else View.VISIBLE
        reload()
        // protection badges: redraw after this device's marks were synced in the background
        com.motioncam.cloud.MarkSync.onSynced = { _ -> runOnUiThread { adapter.notifyDataSetChanged(); if (remote == null) reload() } }
        remote?.let { com.motioncam.remote.SharedSync.listeners += sharedSynced }
        if (remote == null) com.motioncam.cloud.MarkSync.scheduleLocal(this)
        // remote folder: check for new clips now (if the list is a few minutes old) and every 5 minutes
        if (remote != null) {
            ui.postDelayed(autoRefresh, if (System.currentTimeMillis() - (remote?.lastRefresh ?: 0) > 2 * 60_000L) 0 else 5 * 60_000L)
        }
    }

    /** Shared collections changed (another phone, or our changes were sent): show the new state. */
    private val sharedSynced: (String) -> Unit = { id -> if (id == remote?.id && collectionId != null) runOnUiThread { reload() } }

    override fun onPause() {
        com.motioncam.remote.SharedSync.listeners -= sharedSynced
        com.motioncam.cloud.MarkSync.onSynced = null
        ui.removeCallbacks(autoRefresh)
        super.onPause()
    }

    override fun onDestroy() {
        pool.shutdownNow()
        bestPool.shutdownNow()
        super.onDestroy()
    }

    private fun reload() {
        val src = remote
        if (src != null) {
            Thread {
                val list = RemoteRepo.cached(this, src)
                runOnUiThread { showRemote(list) }
            }.start()
            return
        }
        banner.visibility = if (canSeeAll()) View.GONE else View.VISIBLE
        Thread {
            var all = ClipStore.list(this)
            // only prune collections when every clip is visible (otherwise hidden clips would be dropped)
            if (canSeeAll() || android.os.Build.VERSION.SDK_INT < 30) Collections.prune(this, all.map { it.id }.toSet())
            val cid = collectionId
            if (cid != null) {
                val ids = Collections.get(this, cid)?.clipIds ?: emptySet<Long>()
                all = all.filter { it.id in ids }
            }
            val favs = Collections.favouriteIds(this)
            runOnUiThread {
                favIds = favs
                clips = all
                buildRows()
                val total = all.sumOf { it.size }
                summary.text = when {
                    all.isEmpty() && collectionId != null -> "No clips in this collection yet. In Clips, hold a clip to select it, then Add to…"
                    all.isEmpty() -> "No clips yet"
                    else -> "${all.size} clips · %.1f GB".format(total / 1e9) + legend()
                }
            }
        }.start()
    }

    // ------------------------------------------------------------------ cloud protection (marks)

    /** Mark spaces this gallery shows: the remote folder, or this phone's backup destinations. */
    private fun markSpaces(): List<String> = remote?.let { listOf(com.motioncam.cloud.MarkSync.remoteKey(it.id)) }
        ?: com.motioncam.cloud.MarkSync.localSpaces(this)

    /** Protection can be changed here (not for a read-only Dropbox viewer link). */
    private fun canMark() = markSpaces().isNotEmpty() && remote?.mode != RemoteSources.MODE_LINK

    /**
     * Badge for a clip: 🔒 protected in the cloud; ⚠ someone removed the protection although it is in a collection
     * here; ☁✕ (collections on the camera phone) not in the cloud.
     */
    private fun startOfName(n: String): Long = try {
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).parse(n.substring(3, 18))!!.time
    } catch (e: Exception) { 0 }

    /** Shared collections can be changed (not through a read-only Dropbox viewer link). */
    private fun canEditShared() = remote?.mode != RemoteSources.MODE_LINK

    /** A missing clip was tapped: explain, offer to remove it (or play this phone's saved copy). */
    private fun missingOptions(c: Clip) {
        val since = remote?.let { com.motioncam.remote.SharedSync.missingSince(this, it.id, c.name) }
        val local = ClipStore.list(this).firstOrNull { it.name == c.name }
        val b = AlertDialog.Builder(this).setTitle("Missing from the cloud")
            .setMessage("This clip (${Fmt.dateTime(prefs).format(Date(c.startMs))}) is in the collection but no longer in the folder" +
                (since?.let { " (noticed " + SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(it)) + ")" } ?: "") +
                ". It was deleted from the cloud, or the folder changed.\n\nIt is removed from collections automatically after " +
                "${com.motioncam.remote.SharedSync.MISSING_DAYS} days." + if (local != null) "\n\nA copy is saved on this phone." else "")
        if (canEditShared()) b.setPositiveButton("Remove from collections") { _, _ ->
            remote?.let { com.motioncam.remote.SharedSync.removeEverywhere(this, it.id, listOf(c.name)) }
            reload()
        }
        if (local != null) b.setNeutralButton("Play saved copy") { _, _ ->
            startActivity(Intent(this, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_START_ID, local.id))
        }
        b.setNegativeButton("Close", null).show()
    }

    private fun removeMissing() {
        val src = remote ?: return
        val names = clips.filter { it.id in missingIds }.map { it.name }
        if (names.isEmpty()) return
        AlertDialog.Builder(this).setTitle("Remove ${names.size} missing clip${if (names.size == 1) "" else "s"}?")
            .setMessage("They are no longer in the cloud. This removes them from all shared collections (on every phone).")
            .setPositiveButton("Remove") { _, _ -> com.motioncam.remote.SharedSync.removeEverywhere(this, src.id, names); reload() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun badge(c: Clip): String? {
        if (c.id in missingIds) return "☁✕ Missing"
        val spaces = markSpaces()
        if (spaces.isEmpty()) return null
        val res = spaces.mapNotNull { com.motioncam.cloud.MarkSync.cached(it) }
        if (res.isEmpty()) return null
        if (res.any { c.name in it.overridden }) return "⚠"
        if (res.any { r -> r.marks[c.name]?.protected == true }) return "\uD83D\uDD12"
        if (collectionId != null && remote == null && res.none { c.name in it.existing }) return "☁✕"
        return null
    }

    private fun legend(): String =
        if (collectionId != null && markSpaces().isNotEmpty())
            "\n\uD83D\uDD12 protected in cloud  ·  ⚠ protection removed by someone  ·  ☁✕ not in the cloud"
        else ""

    /** Protect / remove protection for many clips at once (selection or a whole day). */
    private fun protect(list: List<Clip>, on: Boolean) {
        val names = list.map { it.name }
        if (on) com.motioncam.cloud.CloudProtect.protect(this, remote, names) { adapter.notifyDataSetChanged() }
        else com.motioncam.cloud.CloudProtect.confirmRemove(this, remote, names) { adapter.notifyDataSetChanged() }
        if (selecting) exitSelection()
    }

    private fun openProtected() {
        val i = Intent(this, com.motioncam.cloud.ProtectedActivity::class.java)
        remote?.let { i.putExtra(com.motioncam.cloud.ProtectedActivity.EXTRA_SPACE, com.motioncam.cloud.MarkSync.remoteKey(it.id)) }
        startActivity(i)
    }

    // ------------------------------------------------------------------ remote folder

    /** Lists the remote folder again; [quiet] = no toast (automatic refresh). */
    private fun refreshRemote(quiet: Boolean, pulled: Boolean = false) {
        val src = remote ?: return
        if (refreshing) { if (pulled) swipe?.isRefreshing = true; return }
        refreshing = true
        // the spinner shows for refreshes the user asked for (pull or button), not for the automatic checks
        if (!quiet || pulled) swipe?.isRefreshing = true
        Thread {
            val result = try { Result.success(RemoteRepo.refresh(this, src)) } catch (e: Exception) { Result.failure(e) }
            runOnUiThread {
                refreshing = false
                swipe?.isRefreshing = false
                if (isFinishing) return@runOnUiThread
                result.onSuccess { lastError = null; showRemote(it) }
                result.onFailure { e ->
                    lastError = e.message ?: e.javaClass.simpleName
                    showRemote(clips.takeIf { collectionId == null } ?: RemoteRepo.cached(this, src))
                    if (!quiet || pulled) Toast.makeText(this, "Could not refresh: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    /** Clips of a shared collection that are no longer in the cloud (shown as "missing" tiles). */
    private var missingIds: Set<Long> = emptySet()

    private fun showRemote(list: List<Clip>) {
        val src = remote ?: return
        var all = list
        missing = 0
        missingIds = emptySet()
        val cid = collectionId
        if (cid != null) {
            val names = (store as? com.motioncam.remote.SharedCollectionStore)?.clipNames(this, cid) ?: emptyList()
            val byName = list.associateBy { it.name }
            all = names.mapNotNull { byName[it] }
            if (list.isNotEmpty() || src.lastRefresh > 0) {
                // entries pointing at clips that are gone: keep them visible (and removable) instead of hiding them
                val gone = names.filter { it !in byName }.map { n ->
                    Clip(com.motioncam.remote.RemoteRepo.idFor(src.id, n), android.net.Uri.EMPTY, n, 0, startOfName(n), 0)
                }
                missingIds = gone.map { it.id }.toSet()
                missing = gone.size
                all = (all + gone).sortedByDescending { it.startMs }
            }
        }
        banner.visibility = if (missing > 0 && canEditShared()) View.VISIBLE else View.GONE
        banner.text = "☁✕ $missing clip${if (missing == 1) " is" else "s are"} missing from the cloud (deleted from the folder). " +
            "They are removed from collections automatically after ${com.motioncam.remote.SharedSync.MISSING_DAYS} days. Tap to remove now."
        favIds = store.favouriteIds(this)
        clips = all
        buildRows()
        updateRemoteSummary()
    }

    private fun updateRemoteSummary() {
        val src = remote ?: return
        val all = clips
        val cid = collectionId
        val total = all.sumOf { it.size }
        // the clock time of the last check (it changes visibly with every automatic check, unlike "just now")
        val ago = if (src.lastRefresh == 0L) "" else " · checked " + checkedAt(src.lastRefresh)
        summary.text = when {
            all.isEmpty() && lastError == null && (refreshing || src.lastRefresh == 0L) -> "Loading…"
            all.isEmpty() && cid != null -> "No clips in this collection yet. Hold a clip to select it, then Add to…"
            all.isEmpty() -> if (lastError != null) "Could not load this folder (tap for details)" else "No MotionCam clips in this folder yet$ago"
            else -> "${all.size - missing} clips · %.1f GB".format(total / 1e9) + ago +
                (if (missing > 0) " · $missing missing from the cloud" else "") +
                (if (lastError != null) " · offline, showing the last list (tap for details)" else "") + legend()
        }
    }

    private fun checkedAt(t: Long): String {
        val hm = Fmt.t(prefs, t, seconds = false)
        return if (dayStart(t) == dayStart(System.currentTimeMillis())) hm
        else SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(t)) + ", " + hm
    }

    /** Downloads remote clips into the phone's own Clips (never backed up again). */
    private fun saveToPhone(list: List<Clip>) =
        CollectionActions.saveToPhone(this, list.filter { it.id !in missingIds }) { if (selecting) exitSelection() }

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
            if (day !in collapsed) items.chunked(cols).forEach { out += Row.Items(it) }
        }
        rows = out
        display = out.filterIsInstance<Row.Items>().flatMap { it.clips }
        selected.retainAll(clips.map { it.id }.toSet())
        if (selecting && selected.isEmpty()) exitSelection() else updateSelection()
        list.post { updatePinned() }
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
        startActivity(Intent(this, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_START_ID, c.id)
            .putExtra(PlayerActivity.EXTRA_COLLECTION, collectionId).putExtra(PlayerActivity.EXTRA_REMOTE, remote?.id))
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
        val collapsedNow = h.dayStart in collapsed
        val items = arrayOf(
            "Play day in timeline",
            "Select all ${h.clips.size} clips",
            if (collapsedNow) "Expand" else "Collapse",
            "Collapse all days",
            "Expand all days",
            if (remote != null) "Save all ${h.clips.size} clips of this day to the phone" else "Delete all ${h.clips.size} clips of this day",
        ) + (if (canMark()) arrayOf("Protect all ${h.clips.size} in cloud", "Remove cloud protection from all ${h.clips.size}") else emptyArray())
        AlertDialog.Builder(this).setTitle(dayLabel(h.dayStart))
            .setItems(items) { _, which ->
                when (which) {
                    0 -> h.clips.minByOrNull { it.startMs }?.let { play(it) }
                    1 -> { selecting = true; selected.addAll(h.clips.map { it.id }); if (collapsedNow) toggleDay(h.dayStart) else updateSelection() }
                    2 -> toggleDay(h.dayStart)
                    3 -> { collapsed.addAll(clips.map { dayStart(it.startMs) }); buildRows(); scrollToDay(h.dayStart) }
                    4 -> { collapsed.clear(); buildRows(); scrollToDay(h.dayStart) }
                    5 -> if (remote != null) saveToPhone(h.clips) else AlertDialog.Builder(this).setTitle("Delete ${h.clips.size} clips?")
                        .setPositiveButton("Delete") { _, _ -> ClipDeleter.delete(this, h.clips) { reload() } }
                        .setNegativeButton("Cancel", null).show()
                    6 -> protect(h.clips, true)
                    7 -> protect(h.clips, false)
                }
            }.show()
    }

    /** Collapses / expands a day and keeps its header where the user is looking. */
    private fun toggleDay(day: Long) {
        if (!collapsed.remove(day)) collapsed += day
        buildRows()
        scrollToDay(day)
    }

    private fun scrollToDay(day: Long) {
        val pos = rows.indexOfFirst { it is Row.Header && it.dayStart == day }
        if (pos >= 0) list.post { list.setSelectionFromTop(pos, 0); updatePinned() }
    }

    // ------------------------------------------------------------------ day headers (list + sticky copy)

    private fun headerView(): LinearLayout {
        val ctx = this
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(ctx, 10), Ui.dp(ctx, 10), Ui.dp(ctx, 4), Ui.dp(ctx, 4))
            addView(Ui.label(ctx, "▾", 16f, Ui.ACCENT).apply { minWidth = Ui.dp(ctx, 22) })
            addView(Ui.label(ctx, "", 15f, Ui.ACCENT), LinearLayout.LayoutParams(0, -2, 1f))
            addView(iconButton(ctx, IconDrawable.Kind.MORE, 40, 10))
        }
    }

    /**
     * Header: tap = collapse / expand the day (in selection mode: select / deselect the day),
     * ⋮ = day options (play, select, collapse all, delete…).
     */
    private fun bindHeader(v: LinearLayout, row: Row.Header) {
        val isCollapsed = row.dayStart in collapsed
        (v.getChildAt(0) as TextView).text = if (isCollapsed) "▸" else "▾"
        val dur = row.clips.sumOf { it.durationMs }
        val sel = if (selecting) row.clips.count { it.id in selected } else 0
        (v.getChildAt(1) as TextView).text = "${dayLabel(row.dayStart)}  ·  ${row.clips.size} clips · ${durText(dur)}" +
            if (sel > 0) "  ·  $sel selected" else ""
        v.setOnClickListener {
            if (selecting) {
                val ids = row.clips.map { it.id }
                if (selected.containsAll(ids)) selected.removeAll(ids.toSet()) else selected.addAll(ids)
                updateSelection()
            } else toggleDay(row.dayStart)
        }
        v.getChildAt(2).setOnClickListener { dayOptions(row) }
    }

    /** Keeps the current day's header pinned at the top; the next header pushes it up. */
    private fun updatePinned() {
        val first = list.firstVisiblePosition
        if (rows.isEmpty() || first < 0 || first >= rows.size) { pinned.visibility = View.GONE; return }
        var hi = first
        while (hi > 0 && rows[hi] !is Row.Header) hi--
        val h = rows[hi] as? Row.Header ?: run { pinned.visibility = View.GONE; return }
        // the day's own header fully on screen at the top: no need for the copy
        val firstView = list.getChildAt(0)
        if (hi == first && firstView != null && firstView.top >= 0) { pinned.visibility = View.GONE; return }
        if (pinnedDay != h.dayStart || pinned.visibility != View.VISIBLE) {
            pinnedDay = h.dayStart
            bindHeader(pinned, h)
            pinned.visibility = View.VISIBLE
        }
        // push up by the next header
        var dy = 0f
        for (i in 0 until list.childCount) {
            val pos = first + i
            if (pos > hi && pos < rows.size && rows[pos] is Row.Header) {
                val top = list.getChildAt(i).top
                if (top < pinned.height) dy = (top - pinned.height).toFloat()
                break
            }
        }
        pinned.translationY = dy
    }

    // ------------------------------------------------------------------ calendar

    /** Month calendar where only days that have clips are highlighted and tappable. */
    private fun showCalendar() {
        if (clips.isEmpty()) { Toast.makeText(this, "No clips yet", Toast.LENGTH_SHORT).show(); return }
        val counts = clips.groupingBy { dayStart(it.startMs) }.eachCount()
        fun monthOf(ms: Long) = Calendar.getInstance().apply { timeInMillis = ms; set(Calendar.DAY_OF_MONTH, 1) }.let { it.get(Calendar.YEAR) * 12 + it.get(Calendar.MONTH) }
        val months = counts.keys.map { monthOf(it) }.toSortedSet()
        val start = if (pinnedDay != Long.MIN_VALUE) monthOf(pinnedDay) else months.last()
        var month = if (start in months) start else months.last()
        val ctx = this
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; val p = Ui.dp(ctx, 12); setPadding(p, p, p, 0) }
        val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val prev = Ui.label(ctx, "‹", 26f).apply { setPadding(Ui.dp(ctx, 12), 0, Ui.dp(ctx, 12), 0) }
        val next = Ui.label(ctx, "›", 26f).apply { setPadding(Ui.dp(ctx, 12), 0, Ui.dp(ctx, 12), 0) }
        val title = Ui.label(ctx, "", 17f).apply { gravity = Gravity.CENTER }
        head.addView(prev); head.addView(title, LinearLayout.LayoutParams(0, -2, 1f)); head.addView(next)
        box.addView(head)
        val grid = GridLayout(ctx).apply { columnCount = 7 }
        box.addView(grid, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(ctx, 8) })
        lateinit var dialog: AlertDialog
        val cell = Ui.dp(ctx, 40)
        fun render() {
            grid.removeAllViews()
            val cal = Calendar.getInstance().apply { clear(); set(month / 12, month % 12, 1) }
            title.text = SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(cal.time)
            prev.alpha = if (months.headSet(month).isEmpty()) 0.25f else 1f
            next.alpha = if (months.tailSet(month + 1).isEmpty()) 0.25f else 1f
            // weekday names, starting on the locale's first day of the week
            val fdw = cal.firstDayOfWeek
            val names = java.text.DateFormatSymbols.getInstance().shortWeekdays
            for (i in 0 until 7) {
                grid.addView(Ui.label(ctx, names[(fdw - 1 + i) % 7 + 1].take(2), 12f, Ui.GREY).apply { gravity = Gravity.CENTER },
                    GridLayout.LayoutParams().apply { width = cell; height = Ui.dp(ctx, 26) })
            }
            val lead = (cal.get(Calendar.DAY_OF_WEEK) - fdw + 7) % 7
            repeat(lead) { grid.addView(View(ctx), GridLayout.LayoutParams().apply { width = cell; height = cell }) }
            val today = dayStart(System.currentTimeMillis())
            for (d in 1..cal.getActualMaximum(Calendar.DAY_OF_MONTH)) {
                cal.set(Calendar.DAY_OF_MONTH, d)
                val day = dayStart(cal.timeInMillis)
                val n = counts[day] ?: 0
                val tv = Ui.label(ctx, "$d", 14f, if (n > 0) Color.BLACK else 0xFF55595F.toInt()).apply {
                    gravity = Gravity.CENTER
                    if (n > 0) {
                        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Ui.ACCENT) }
                        setOnClickListener { dialog.dismiss(); collapsed.remove(day); buildRows(); scrollToDay(day) }
                    } else if (day == today) {
                        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setStroke(Ui.dp(ctx, 1), Ui.GREY) }
                    }
                }
                grid.addView(tv, GridLayout.LayoutParams().apply { width = cell - Ui.dp(ctx, 4); height = cell - Ui.dp(ctx, 4); setMargins(Ui.dp(ctx, 2), Ui.dp(ctx, 2), Ui.dp(ctx, 2), Ui.dp(ctx, 2)) })
            }
        }
        prev.setOnClickListener { months.headSet(month).lastOrNull()?.let { month = it; render() } }
        next.setOnClickListener { months.tailSet(month + 1).firstOrNull()?.let { month = it; render() } }
        render()
        dialog = AlertDialog.Builder(ctx).setView(box).setNegativeButton("Close", null).create()
        dialog.show()
    }

    /**
     * Best thumbnail (frame with the action) when ready; until then a quick placeholder frame from just after
     * the pre-record window, so the grid fills immediately.
     */
    private fun bindThumb(iv: ImageView, c: Clip) {
        iv.tag = c.id
        thumbs.get(c.id)?.let { iv.setImageBitmap(it); return }
        val ph = placeholders.get(c.id)
        if (ph != null) iv.setImageBitmap(ph) else iv.setImageDrawable(null)
        pool.execute {
            if (iv.tag != c.id) return@execute                      // scrolled away: skip
            if (ClipThumbs.isReady(this, c)) {
                val b = ClipThumbs.cached(this, c)
                if (b != null) { thumbs.put(c.id, b); runOnUiThread { if (iv.tag == c.id) iv.setImageBitmap(b) }; return@execute }
            }
            if (ph == null) {
                val q = ClipThumbs.quick(this, c)
                if (q != null) { placeholders.put(c.id, q); runOnUiThread { if (iv.tag == c.id && thumbs.get(c.id) == null) iv.setImageBitmap(q) } }
            }
            bestPool.execute {
                if (iv.tag != c.id && placeholders.get(c.id) != null) return@execute   // not on screen any more
                val b = ClipThumbs.load(this, c) ?: return@execute
                thumbs.put(c.id, b)
                placeholders.remove(c.id)
                runOnUiThread { if (iv.tag == c.id) iv.setImageBitmap(b) }
            }
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
                val v = (convertView as? LinearLayout)?.takeIf { it.tag == "header" } ?: headerView().apply { tag = "header" }
                bindHeader(v, row)
                return v
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
                cell.getChildAt(3).visibility = if (c.id in favIds) View.VISIBLE else View.GONE
                val b = badge(c)
                (cell.getChildAt(4) as TextView).apply { text = b ?: ""; visibility = if (b == null) View.GONE else View.VISIBLE }
                bindThumb(iv, c)
                label.text = "${timeFmt.format(Date(c.startMs))}  ·  ${durText(c.durationMs)}"
                val sel = c.id in selected
                check.visibility = if (selecting) View.VISIBLE else View.GONE
                check.text = if (sel) "✓" else ""
                (check.background as GradientDrawable).setColor(if (sel) Ui.ACCENT else 0x66000000)
                cell.foreground = if (sel) selFrame else null
                iv.alpha = if (selecting && !sel) 0.75f else 1f
                cell.setOnClickListener {
                    if (selecting) { if (!selected.remove(c.id)) selected += c.id; updateSelection() }
                    else if (c.id in missingIds) missingOptions(c) else play(c)
                }
                iv.alpha = if (c.id in missingIds) 0.35f else iv.alpha
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
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
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
            val star = Ui.label(ctx, "★", 14f, Ui.ACCENT).apply {
                setShadowLayer(4f, 0f, 0f, Color.BLACK)
                visibility = View.GONE
            }
            addView(star, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
                setMargins(Ui.dp(ctx, 8), Ui.dp(ctx, 4), 0, 0)
            })
            // cloud protection badge (bottom-right, next to the time label)
            val status = Ui.label(ctx, "", 11f, Color.WHITE).apply {
                setBackgroundColor(0x99000000.toInt())
                setPadding(Ui.dp(ctx, 5), Ui.dp(ctx, 2), Ui.dp(ctx, 5), Ui.dp(ctx, 2))
                visibility = View.GONE
            }
            addView(status, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END))
        }
    }

    private val selFrame by lazy {
        GradientDrawable().apply { setColor(0x00000000); setStroke(Ui.dp(this@ClipsActivity, 3), Ui.ACCENT); cornerRadius = Ui.dp(this@ClipsActivity, 8).toFloat() }
    }

    // ------------------------------------------------------------------ multi-select

    private fun updateSelection() {
        normalBar.visibility = if (selecting) View.GONE else View.VISIBLE
        actionRow.visibility = normalBar.visibility
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

    private fun selectedClips() = clips.filter { it.id in selected }

    private fun deleteSelected() {
        val list = selectedClips()
        if (list.isEmpty()) return
        AlertDialog.Builder(this).setTitle("Delete ${list.size} clip${if (list.size == 1) "" else "s"}?")
            .setPositiveButton("Delete") { _, _ -> ClipDeleter.delete(this, list) { exitSelection(); reload() } }
            .setNegativeButton("Cancel", null).show()
    }

    private fun addSelectedTo() {
        val ids = selectedClips().map { it.id }
        if (ids.isEmpty()) return
        CollectionPicker.show(this, ids, store) { exitSelection(); reload() }
    }

    private fun removeSelectedFromCollection() {
        val cid = collectionId ?: return
        val ids = selectedClips().map { it.id }
        if (ids.isEmpty()) return
        store.remove(this, cid, ids)
        Toast.makeText(this, "Removed ${ids.size} from this collection (clips kept)", Toast.LENGTH_SHORT).show()
        exitSelection()
        reload()
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
