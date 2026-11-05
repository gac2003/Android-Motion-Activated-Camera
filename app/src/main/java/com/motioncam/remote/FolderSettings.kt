package com.motioncam.remote

import android.app.Activity
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import com.motioncam.Prefs
import com.motioncam.SettingsForm
import com.motioncam.Ui
import com.motioncam.cloud.CloudSync
import com.motioncam.topBar

/**
 * The settings of cloud folders: uploading (for the folders this phone backs up to) and viewing other phones' folders.
 * Built by the same code in the app's Settings and in Remote Folders → ⚙, so both always show the same thing.
 */
object FolderSettings {
    /** A tall (two-line) tappable row: icon, title, a live subtitle (refreshed through [live]) and ›. */
    fun navRow(a: Activity, title: String, subtitle: () -> String, icon: com.motioncam.IconDrawable.Kind,
               live: MutableList<() -> Unit>, click: () -> Unit): android.view.View {
        val r = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            minimumHeight = Ui.dp(a, 60)
            setPadding(Ui.dp(a, 12), Ui.dp(a, 8), Ui.dp(a, 12), Ui.dp(a, 8))
            background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x30FFFFFF),
                android.graphics.drawable.GradientDrawable().apply { setColor(0xFF2A2D33.toInt()); cornerRadius = Ui.dp(a, 12).toFloat() }, null)
            isClickable = true
            setOnClickListener { click() }
        }
        r.addView(android.widget.ImageView(a).apply { setImageDrawable(com.motioncam.IconDrawable(icon)) }, LinearLayout.LayoutParams(Ui.dp(a, 24), Ui.dp(a, 24)))
        val texts = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dp(a, 14), 0, 0, 0) }
        texts.addView(Ui.label(a, title, 16f))
        val sub = Ui.label(a, subtitle(), 12f, Ui.GREY)
        texts.addView(sub)
        live += { val t = subtitle(); if (sub.text != t) sub.text = t }
        r.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        r.addView(Ui.label(a, "›", 22f, Ui.GREY))
        return LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(0, Ui.dp(a, 8), 0, 0); addView(r) }
    }

    /** The upload queue and the downloaded (played) clips: on top of the folder settings. */
    fun storageRows(a: Activity, box: LinearLayout, live: MutableList<() -> Unit>) {
        box.addView(navRow(a, "Upload queue", {
            val c = CloudSync.counts(a, null)
            (if (c.queued == 0) "Nothing waiting" else "${c.queued} waiting") + (if (c.failed > 0) " · ${c.failed} failed" else "")
        }, com.motioncam.IconDrawable.Kind.CLOUD_UP, live) { a.startActivity(android.content.Intent(a, com.motioncam.cloud.UploadQueueActivity::class.java)) })
        box.addView(navRow(a, "Clear downloaded clips", { "${StreamCache.cacheBytes(a) / 1_000_000} MB used by clips played from remote folders" },
            com.motioncam.IconDrawable.Kind.TRASH, live) {
            android.app.AlertDialog.Builder(a).setTitle("Clear downloaded clips?")
                .setMessage("Frees the space used by clips played from cloud folders. They stream again the next time you play them.")
                .setPositiveButton("Clear") { _, _ ->
                    StreamCache.clearAll(a); android.widget.Toast.makeText(a, "Cleared", android.widget.Toast.LENGTH_SHORT).show(); live.forEach { it() }
                }
                .setNegativeButton("Cancel", null).show()
        })
    }

    fun build(a: Activity, form: SettingsForm, title: (String) -> Unit, note: (String) -> Unit, changed: () -> Unit = {}) {
        val p = Prefs(a)
        title("Uploading")
        note("For the folders this phone backs up to (↑ in Clips → Remote folders). New clips upload by themselves and resume if the " +
            "internet drops; to upload older clips, select them in Clips → Back up.")
        form.toggle("Upload only on Wi-Fi (not mobile data)", p.cloudWifiOnly) { p.cloudWifiOnly = it; CloudSync.kick(a) }
        form.toggle("Upload only while charging", p.cloudChargingOnly) { p.cloudChargingOnly = it; CloudSync.kick(a) }
        form.choice("Upload quality", listOf("Original (largest, best quality)", "720p (about 1/3 the size)", "480p (smallest)"), p.cloudQuality) {
            p.cloudQuality = it
        }
        note("Smaller copies are made on the phone before uploading (uses some extra power; original stays on the phone).")
        form.toggle("Delete the phone copy after it is backed up everywhere (clips in collections are kept)", p.cloudDeleteLocal) { p.cloudDeleteLocal = it }
        form.toggle("Upload thumbnails for other devices", p.cloudThumbs) { p.cloudThumbs = it; CloudSync.kick(a) }
        note("A small peak-motion picture (about 20 KB) per clip, in a \".thumbnails\" subfolder, so another device viewing " +
            "the folder sees the right frame and clip length without downloading videos.")

        title("Viewing other phones' folders")
        form.toggle("Allow FTP / SFTP servers as remote folders", p.remoteFtp) { p.remoteFtp = it; changed() }
        note("When off, other phones' FTP/SFTP folders are hidden and cannot be added. Clips from servers are downloaded " +
            "fully before they play (cloud folders stream).")
        form.toggle("Keep a copy on this phone when a clip in my collections loses its cloud protection", p.remoteKeepCopy) { p.remoteKeepCopy = it }
        note("Clips you favourite or add to a collection in another phone's folder are protected from that phone's clean-up. " +
            "Anyone signed in to the account can remove that protection; this saves such clips to this phone the next time " +
            "the folder is checked, so your collection keeps them.")
        note("Played remote clips are kept (up to 1.5 GB) so replaying and scrubbing are instant; \"Clear downloaded clips\" " +
            "in Clips → Remote folders frees that space. Android may also clear them when storage is low.")
    }

    /** Background on how folders work (shown in Remote Folders → ⚙). */
    fun help(title: (String) -> Unit, note: (String) -> Unit) {
        title("How it works")
        note("This device never renames clips in other phones' folders, and deletes them only when you choose " +
            "\"Delete from cloud\" (protected clips are always kept). It also writes small \"protected\" marks and the shared " +
            "collections (Collections → Protected in cloud shows all marks). Clips stream when you play them and are kept " +
            "in a cache so replaying and scrubbing are instant. Favourites and collections you make stay on this device.")
        title("Which account to use")
        note("• Google Drive: sign in with the same Google account the recording phone backs up to. Other Google accounts " +
            "can't be used: MotionCam's Drive access only sees files MotionCam created in that account.\n" +
            "• Dropbox, same account: ＋ Add → Dropbox: my own backups.\n" +
            "• Dropbox, another account: on the recording phone open its folder's ⋮ → Backup settings → Create viewer link, " +
            "and send the link to this device. Then: ＋ Add → Dropbox: a viewer link.\n" +
            "Thumbnails show the moment with the most motion when the recording phone has \"Upload thumbnails for other " +
            "devices\" on (default).")
    }
}

/** Remote Folders → ⚙: the folder settings on a page of their own (the same ones as in the app's Settings). */
class FolderSettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.BG) }
        root.addView(topBar(this, "Remote folder settings"))
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; val p = Ui.dp(this@FolderSettingsActivity, 16); setPadding(p, 0, p, p * 2) }
        root.addView(ScrollView(this).apply { addView(box) }, LinearLayout.LayoutParams(-1, 0, 1f))
        Ui.capWidth(box)
        setContentView(root)
        val title: (String) -> Unit = { s -> box.addView(Ui.label(this, s.uppercase(), 13f, Ui.ACCENT).apply { setPadding(0, Ui.dp(this@FolderSettingsActivity, 22), 0, Ui.dp(this@FolderSettingsActivity, 4)) }) }
        val note: (String) -> Unit = { s -> box.addView(Ui.label(this, s, 12f, 0xFFB0B4BA.toInt()).apply { setPadding(0, Ui.dp(this@FolderSettingsActivity, 6), 0, Ui.dp(this@FolderSettingsActivity, 6)) }) }
        FolderSettings.storageRows(this, box, live)
        FolderSettings.build(this, SettingsForm(this, box), title, note)
        FolderSettings.help(title, note)
    }

    private val live = ArrayList<() -> Unit>()
    private val ui = android.os.Handler(android.os.Looper.getMainLooper())
    private val tick = object : Runnable { override fun run() { live.forEach { try { it() } catch (_: Exception) {} }; ui.postDelayed(this, 2000) } }
    override fun onResume() { super.onResume(); ui.post(tick) }
    override fun onPause() { ui.removeCallbacks(tick); super.onPause() }
}
