package com.motioncam.remote

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.Toast
import com.motioncam.ClipsActivity
import com.motioncam.CollectionsActivity
import com.motioncam.Collections
import com.motioncam.IconDrawable
import com.motioncam.Prefs
import com.motioncam.Ui
import com.motioncam.cloud.CloudConfig
import com.motioncam.cloud.CloudKind
import com.motioncam.cloud.DriveProvider
import com.motioncam.cloud.DropboxProvider
import com.motioncam.cloud.GoogleAuth
import com.motioncam.cloud.LoopbackReceiver
import com.motioncam.cloud.Pkce
import com.motioncam.cloud.RemoteFolder
import com.motioncam.cloud.isOurClip
import com.motioncam.iconButton
import com.motioncam.topBar

/**
 * Remote folders: MotionCam backup folders of other devices (or of this account on another phone), opened
 * read-only in the normal Clips gallery and timeline player. Nothing in these folders is ever written.
 */
open class RemoteFoldersActivity : Activity() {
    companion object {
        private const val REQ_GOOGLE = 911
        /** Opened to set up backup (from Settings or "backup is off" prompts): shows the choices when nothing is backed up yet. */
        const val EXTRA_BACKUP = "backup"
    }

    private lateinit var scroll: ScrollView
    private var backupTitle: View? = null
    private var scrolledToBackup = false
    /** Backup status lines refreshed every 2 s while the screen is open. */
    private val liveLines = ArrayList<() -> Unit>()
    private val liveTick = object : Runnable {
        override fun run() { liveLines.forEach { try { it() } catch (_: Exception) {} }; ui.postDelayed(this, 2000) }
    }

    private lateinit var box: LinearLayout
    private lateinit var prefs: Prefs
    /** Shown as the Clips tab's Remote folders (title and the switch; + Add next to Your folders). */
    protected open val asTab = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.BG) }
        if (asTab) root.addView(LinearLayout(this).apply {
            tag = "clipsHeader"
            orientation = LinearLayout.VERTICAL
            val p = Ui.dp(this@RemoteFoldersActivity, 16)
            setPadding(p, p, p, Ui.dp(this@RemoteFoldersActivity, 4))
            addView(Ui.label(this@RemoteFoldersActivity, "Clips", 26f).apply { setTypeface(typeface, android.graphics.Typeface.BOLD); setPadding(0, 0, 0, Ui.dp(this@RemoteFoldersActivity, 10)) })
            addView(com.motioncam.ClipsSwitch.view(this@RemoteFoldersActivity, remote = true))
        }) else {
            val actions = Ui.chip(this, "＋ Add").apply { setOnClickListener { addSource() } }
            root.addView(topBar(this, "Remote folders", actions))
        }
        box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = Ui.dp(this@RemoteFoldersActivity, 14)
            setPadding(p, 0, p, p * 2)
        }
        scroll = ScrollView(this).apply { addView(box) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        Ui.capWidth(box)
        setContentView(root)
    }

    private val ui = android.os.Handler(android.os.Looper.getMainLooper())
    /** "checked N min ago" labels, kept current while the screen is open. */
    private val checkedLabels = HashMap<android.widget.TextView, RemoteSource>()
    private val tick = object : Runnable {
        override fun run() {
            for ((tv, s) in checkedLabels) tv.text = checkedText(s)
            ui.postDelayed(this, 30_000L)
        }
    }

    override fun onResume() {
        super.onResume()
        val y = if (::scroll.isInitialized) scroll.scrollY else 0
        render()
        ui.postDelayed(tick, 30_000L)
        ui.post(liveTick)
        if (intent.getBooleanExtra(EXTRA_BACKUP, false) && !scrolledToBackup) {
            // "set up backup": with no folder backed up yet, go straight to adding one
            scrolledToBackup = true
            if (folders().none { it.backingUp }) scroll.post { addSource() }
        } else scroll.post { scroll.scrollTo(0, y) }
    }
    override fun onPause() { ui.removeCallbacks(tick); ui.removeCallbacks(liveTick); super.onPause() }

    private fun checkedText(s: RemoteSource) =
        if (s.lastRefresh == 0L) "not loaded yet" else "${s.clipCount} clips · checked " + ago(s.lastRefresh)

    /**
     * One cloud folder of MotionCam clips (a remote folder). This phone can view it and back up its new clips to it
     * ([dests]: the backup destinations pointing at this folder; a phone can back up to several folders).
     */
    private class Folder(val kind: CloudKind, val source: RemoteSource, val dests: List<com.motioncam.cloud.Dest>) {
        val backingUp get() = dests.isNotEmpty()
    }

    /** Backup destinations of this folder: [onOnly] = only those uploading now (not switched off). */
    private fun destsOf(s: RemoteSource, onOnly: Boolean = true): List<com.motioncam.cloud.Dest> {
        if (s.mode == RemoteSources.MODE_LINK) return emptyList()
        return com.motioncam.cloud.Dest.all(this).filter { d ->
            d.kind == s.kind && CloudConfig(this, d).let { c -> c.isConfigured && c.folderId == s.folderId && (!onOnly || c.enabled) }
        }
    }

    private fun folders(): List<Folder> {
        // a backup folder set up elsewhere (older versions, Backup settings) is listed like any other remote folder
        for (d in com.motioncam.cloud.Dest.all(this)) {
            val c = CloudConfig(this, d)
            val fid = c.folderId ?: continue
            if (!c.isConfigured) continue
            if (RemoteSources.all(this).none { it.kind == d.kind && it.mode != RemoteSources.MODE_LINK && it.folderId == fid }) sourceFromBackup(d, c)
        }
        return RemoteSources.all(this).sortedBy { it.added }.map { Folder(it.kind, it, destsOf(it)) }
    }

    private fun isServer(k: CloudKind) = k == CloudKind.FTP || k == CloudKind.SFTP

    private fun render() {
        box.removeAllViews()
        checkedLabels.clear()
        liveLines.clear()
        quickActions()
        val list = folders()
        val shown = list.filter { it.backingUp || prefs.remoteFtp || !isServer(it.kind) }
        // "Your folders", with + Add beside it
        box.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, Ui.dp(this@RemoteFoldersActivity, 18), 0, Ui.dp(this@RemoteFoldersActivity, 6))
            addView(Ui.label(this@RemoteFoldersActivity, "YOUR FOLDERS", 13f, Ui.ACCENT).apply { setTypeface(typeface, android.graphics.Typeface.BOLD); letterSpacing = 0.06f },
                LinearLayout.LayoutParams(0, -2, 1f))
            addView(Ui.label(this@RemoteFoldersActivity, "+ Add", 13f, 0xFF15171B.toInt()).apply {
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(Ui.dp(this@RemoteFoldersActivity, 14), Ui.dp(this@RemoteFoldersActivity, 6), Ui.dp(this@RemoteFoldersActivity, 14), Ui.dp(this@RemoteFoldersActivity, 6))
                background = GradientDrawable().apply { setColor(Ui.ACCENT); cornerRadius = Ui.dp(this@RemoteFoldersActivity, 16).toFloat() }
                isClickable = true
                contentDescription = "Add remote folder"
                setOnClickListener { addSource() }
            })
        })
        if (shown.isEmpty()) {
            box.addView(Ui.label(this, "No remote folders yet. Tap ＋ Add to create one (and back up this phone's clips to it) or to open one another phone uses.", 15f).apply {
                setPadding(0, Ui.dp(this@RemoteFoldersActivity, 16), 0, Ui.dp(this@RemoteFoldersActivity, 8))
            })
        }
        for (f in shown) box.addView(folderRow(f))
        val hidden = list.size - shown.size
        if (hidden > 0) note("$hidden FTP/SFTP folder${if (hidden == 1) " is" else "s are"} hidden (Remote folder settings → Allow FTP / SFTP servers).")
        note("Tap a folder to watch its clips. A yellow cloud means this phone backs up its new clips there; ⋮ turns that on or off.")
    }

    private fun folderRow(f: Folder): View {
        val ctx = this
        val s = f.source
        val r = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(ctx, 10), Ui.dp(ctx, 10), Ui.dp(ctx, 2), Ui.dp(ctx, 10))
            background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x30FFFFFF),
                GradientDrawable().apply { setColor(0xFF1E2126.toInt()); cornerRadius = Ui.dp(ctx, 10).toFloat() }, null)
            isClickable = true
            setOnClickListener { openFolder(f) }
        }
        // the folder icon; a folder this phone backs up to gets the "uploading" cloud (↑ in it) in the accent colour
        r.addView(android.widget.ImageView(ctx).apply {
            setImageDrawable(IconDrawable(when {
                f.backingUp -> IconDrawable.Kind.CLOUD_UP
                isServer(f.kind) -> IconDrawable.Kind.FOLDER
                else -> IconDrawable.Kind.CLOUD
            }, if (f.backingUp) Ui.ACCENT else 0xFFFFFFFF.toInt()))
            val p = Ui.dp(ctx, 8); setPadding(p, p, p, p)
            contentDescription = if (f.backingUp) "Backing up new clips here" else null
        }, LinearLayout.LayoutParams(Ui.dp(ctx, 40), Ui.dp(ctx, 40)))
        val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dp(ctx, 10), 0, 0, 0) }
        texts.addView(Ui.label(ctx, s.name, 16f))
        texts.addView(Ui.label(ctx, s.describe, 12f, Ui.GREY).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        texts.addView(Ui.label(ctx, checkedText(s), 12f, Ui.GREY).also { checkedLabels[it] = s })
        for (d in f.dests) {
            val st = Ui.label(ctx, "", 12f, Ui.ACCENT)
            val update = { val t = "↑ Backing up new clips · " + com.motioncam.cloud.CloudSync.status(ctx, d); if (st.text != t) st.text = t }
            update(); liveLines += update
            texts.addView(st)
        }
        val conflicts = SharedSync.state(ctx, s.id).conflicts.size
        if (conflicts > 0) texts.addView(Ui.label(ctx, "⚠ $conflicts collection sync conflict${if (conflicts == 1) "" else "s"} — open Collections › Sync status", 12f, Ui.ACCENT))
        r.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        lateinit var more: View
        more = iconButton(ctx, IconDrawable.Kind.MORE, 44, 10) {
            val pm = PopupMenu(ctx, more)
            pm.menu.add(0, 1, 0, "Open")
            pm.menu.add(0, 2, 1, "Collections")
            if (s.mode != RemoteSources.MODE_LINK) pm.menu.add(0, 6, 2, if (f.backingUp) "Stop backing up here" else "Back up new clips here")
            val settingsDest = destsOf(s, onOnly = false).firstOrNull()
            if (settingsDest != null) pm.menu.add(0, 5, 3, "Backup settings…")
            pm.menu.add(0, 3, 4, "Rename")
            pm.menu.add(0, 4, 5, "Remove from this device")
            pm.setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> openFolder(f)
                    2 -> startActivity(Intent(ctx, CollectionsActivity::class.java).putExtra(CollectionsActivity.EXTRA_REMOTE, s.id))
                    3 -> askText("Rename", s.name) { n -> if (n.isNotBlank()) { s.name = n.trim(); RemoteSources.update(ctx, s); render() } }
                    4 -> AlertDialog.Builder(ctx).setTitle("Remove \"${s.name}\"?")
                        .setMessage("Removes it from this device only (with its cached clips and the collections you made for it). " +
                            "Nothing in the folder itself is touched. Clips you saved to this phone are kept." +
                            if (f.backingUp) "\n\nThis phone also stops backing up to it." else "")
                        .setPositiveButton("Remove") { _, _ ->
                            stopBackup(s, forget = true)
                            RemoteRepo.forget(ctx, s)
                            Collections.dropRemote(ctx, s.id)
                            RemoteSources.remove(ctx, s.id)
                            render()
                        }
                        .setNegativeButton("Cancel", null).show()
                    5 -> settingsDest?.let { d -> startActivity(Intent(ctx, com.motioncam.cloud.CloudActivity::class.java).putExtra(com.motioncam.cloud.CloudActivity.EXTRA_KIND, d.key)) }
                    6 -> if (f.backingUp) AlertDialog.Builder(ctx).setTitle("Stop backing up to \"${s.name}\"?")
                            .setMessage("New clips are no longer uploaded here. Clips already in the folder stay, and you can still watch them.")
                            .setPositiveButton("Stop") { _, _ -> stopBackup(s, forget = false); render() }
                            .setNegativeButton("Cancel", null).show()
                        else backUpHere(s)
                }
                true
            }
            pm.show()
        }
        r.addView(more)
        val wrap = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(0, Ui.dp(ctx, 10), 0, 0) }
        wrap.addView(r)
        return wrap
    }

    /** Lists a backup folder set up elsewhere as a remote folder (same account / server). */
    private fun sourceFromBackup(d: com.motioncam.cloud.Dest, c: CloudConfig): RemoteSource {
        val s = RemoteSource(id = RemoteSources.newId(), name = c.folderName ?: "MotionCam", kind = d.kind, mode = RemoteSources.MODE_OWN,
            folderId = c.folderId!!, folderName = c.folderName ?: "", account = c.account, host = c.host, port = c.port, user = c.user,
            tls = c.tls, useKey = c.useKey, hostKey = c.hostKey)
        s.password = c.password; s.privateKey = c.privateKey; s.passphrase = c.passphrase; s.refreshToken = c.refreshToken
        RemoteSources.add(this, s)
        return s
    }

    private fun openFolder(f: Folder) = open(f.source)

    /**
     * This phone backs up its new clips to [s] too (several folders are fine, also on the same service). Reuses the
     * folder's switched-off destination or the service's unused one, otherwise adds a destination.
     */
    private fun backUpHere(s: RemoteSource, done: (Boolean) -> Unit = {}) {
        val d = destsOf(s, onOnly = false).firstOrNull()
            ?: com.motioncam.cloud.Dest.primary(s.kind).takeIf { CloudConfig(this, it).let { c -> !c.isConfigured || c.folderId == null } }
            ?: com.motioncam.cloud.Dest.create(this, s.kind)
        val c = CloudConfig(this, d)
        when (s.kind) {
            CloudKind.DRIVE -> c.account = s.account
            CloudKind.DROPBOX -> { c.refreshToken = s.refreshToken; c.account = s.account }
            CloudKind.FTP -> { c.host = s.host; c.port = s.port; c.user = s.user; c.tls = s.tls; c.password = s.password }
            CloudKind.SFTP -> {
                c.host = s.host; c.port = s.port; c.user = s.user; c.useKey = s.useKey; c.password = s.password
                c.privateKey = s.privateKey; c.passphrase = s.passphrase; c.hostKey = s.hostKey
            }
        }
        c.folderId = s.folderId; c.folderName = s.folderName.ifEmpty { s.name }
        c.blocked = null
        c.enabled = true
        com.motioncam.cloud.CloudSync.kick(this)
        Toast.makeText(this, "New clips will also be backed up to ${s.name}", Toast.LENGTH_LONG).show()
        done(true)
        render()
    }

    /** Stops backing up to [s]: the service's own destination is switched off ([forget]: also forgets the folder), others are removed. */
    private fun stopBackup(s: RemoteSource, forget: Boolean) {
        for (d in destsOf(s, onOnly = false)) {
            val c = CloudConfig(this, d)
            com.motioncam.cloud.CloudSync.dropJobs(this, d)
            if (d.isPrimary) { c.enabled = false; if (forget) { c.folderId = null; c.folderName = null } }
            else { c.clearAll(); com.motioncam.cloud.Dest.remove(this, d) }
        }
        com.motioncam.cloud.CloudSync.kick(this)
    }

    // ------------------------------------------------------------------ top row: settings (with the upload queue and downloaded clips)

    private fun quickActions() {
        val ctx = this
        box.addView(FolderSettings.navRow(this, "Remote folder settings", {
            val c = com.motioncam.cloud.CloudSync.counts(ctx, null)
            "Upload queue (" + (if (c.queued == 0) "nothing waiting" else "${c.queued} waiting") + (if (c.failed > 0) ", ${c.failed} failed" else "") +
                "), downloaded clips, upload rules"
        }, IconDrawable.Kind.SETTINGS, liveLines) { startActivity(Intent(ctx, FolderSettingsActivity::class.java)) }.also { backupTitle = it })
    }

    private fun open(s: RemoteSource) {
        // in the Clips tab the folder opens inside the tab (tab bar and switch stay)
        startActivity(Intent(this, if (asTab) com.motioncam.RemoteClipsTabActivity::class.java else ClipsActivity::class.java)
            .putExtra(ClipsActivity.EXTRA_REMOTE, s.id))
        if (asTab) com.motioncam.Nav.noWindowAnim(this)
    }

    // ------------------------------------------------------------------ adding

    /**
     * Add remote folder: pick the service, sign in, then create a new folder or pick an existing one, and finally choose
     * whether this phone backs up its new clips there (also a switch on the folder afterwards).
     */
    private fun addSource() {
        val list = ArrayList<com.motioncam.Opt>()
        list += com.motioncam.Opt("Google Drive", "Your Google account — the same one on every phone that should see the clips", IconDrawable.Kind.CLOUD) { googleSignIn() }
        list += com.motioncam.Opt("Dropbox", "Your Dropbox (an app folder)", IconDrawable.Kind.CLOUD) { dropboxAccount { t, w -> dropboxOwn(t, w) } }
        list += com.motioncam.Opt("Dropbox viewer link", "Someone else's folder they shared with a link (view only)", IconDrawable.Kind.CLOUD) {
            dropboxAccount { t, w -> dropboxLink(t, w) }
        }
        if (prefs.remoteFtp) {
            list += com.motioncam.Opt("FTP / FTPS server", "Your own server; clips download before playing", IconDrawable.Kind.FOLDER) { serverForm(CloudKind.FTP) }
            list += com.motioncam.Opt("SFTP server", "Your own server over SSH", IconDrawable.Kind.FOLDER) { serverForm(CloudKind.SFTP) }
        }
        com.motioncam.OptionSheet.show(this, "Add remote folder", list,
            subtitle = "Next: create a new folder or open an existing one, then choose whether this phone backs up to it.")
    }

    // ---- Google Drive

    private fun googleSignIn() {
        GoogleAuth.start(this, REQ_GOOGLE, { t -> afterGoogle(t) }, { toast("Google sign-in failed: $it") })
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_GOOGLE) {
            val t = if (resultCode == RESULT_OK) GoogleAuth.result(this, data) else null
            if (t != null) afterGoogle(t) else toast("Google sign-in was cancelled")
            return
        }
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
    }

    private fun afterGoogle(token: String) {
        Thread {
            try {
                val email = DriveProvider({ token }, {}).test()
                val draft = RemoteSource(RemoteSources.newId(), "", CloudKind.DRIVE, RemoteSources.MODE_OWN, "", "", email)
                runOnUiThread { browse(draft, null, emptyList()) }
            } catch (e: Exception) { runOnUiThread { toast("Google Drive: ${e.message}") } }
        }.start()
    }

    // ---- Dropbox

    /** Uses the Dropbox account connected for backup, or signs in (any Dropbox account works for a viewer link). */
    private fun dropboxAccount(then: (refresh: String, who: String) -> Unit) {
        if (!DropboxProvider.configured) { android.app.AlertDialog.Builder(this).setTitle("Dropbox isn't set up in this build")
                .setMessage("This copy of MotionCam was built without a Dropbox app key. Whoever builds it can add their own " +
                    "(free, a few minutes): see SETUP.md in the project.").setPositiveButton("OK", null).show(); return }
        val cfg = CloudConfig(this, CloudKind.DROPBOX)
        val existing = cfg.refreshToken
        if (existing != null) {
            com.motioncam.OptionSheet.show(this, "Dropbox account", listOf(
                com.motioncam.Opt(cfg.account ?: "The account connected for backup", "Already connected on this phone") { then(existing, cfg.account ?: "Dropbox") },
                com.motioncam.Opt("Another Dropbox account", "Opens the Dropbox sign-in page") { dropboxSignIn(then) },
            ))
        } else dropboxSignIn(then)
    }

    private fun dropboxSignIn(then: (String, String) -> Unit) {
        val verifier = Pkce.verifier()
        val state = Pkce.state()
        val rx = try { LoopbackReceiver(DropboxProvider.REDIRECT_PORT) } catch (e: Exception) { toast("Could not start sign-in: ${e.message}"); return }
        Thread {
            try {
                val code = rx.waitForCode(state, 5 * 60_000)
                runOnUiThread { LoopbackReceiver.bringBack(this) }   // back from the browser tab
                val refresh = DropboxProvider.exchange(code, verifier)
                val who = DropboxProvider(DropboxProvider.APP_KEY, refresh).test()
                runOnUiThread { toast("Signed in to Dropbox as $who"); then(refresh, who) }
            } catch (e: Exception) {
                if (!rx.superseded) runOnUiThread { toast("Dropbox sign-in failed: ${e.message}") }   // replaced by a newer sign-in: quiet
            } finally { rx.close() }
        }.start()
        LoopbackReceiver.openPage(this, DropboxProvider.authorizeUrl(Pkce.challenge(verifier), state))
    }

    private fun dropboxOwn(refresh: String, who: String) {
        val draft = RemoteSource(RemoteSources.newId(), "", CloudKind.DROPBOX, RemoteSources.MODE_OWN, "", "", who)
        draft.refreshToken = refresh
        browse(draft, null, emptyList())
    }

    private fun dropboxLink(refresh: String, who: String) {
        val clip = try {
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.text?.toString()
        } catch (e: Exception) { null }
        val input = EditText(this).apply {
            hint = "https://www.dropbox.com/scl/fo/…"
            setText(clip?.takeIf { it.contains("dropbox.com") }?.trim() ?: "")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        AlertDialog.Builder(this).setTitle("Paste the viewer link").setView(input)
            .setMessage("Made on the recording phone: Clips → Remote folders → its Dropbox folder's ⋮ → Backup settings → Create viewer link.")
            .setPositiveButton("Open") { _, _ ->
                val url = input.text.toString().trim()
                if (!url.startsWith("https://") || !url.contains("dropbox.com")) { toast("That doesn't look like a Dropbox link"); return@setPositiveButton }
                Thread {
                    try {
                        val draft = RemoteSource(RemoteSources.newId(), "", CloudKind.DROPBOX, RemoteSources.MODE_LINK, "", "", who, link = url)
                        draft.refreshToken = refresh
                        val (name, n) = RemoteSources.reader(this, draft).use { r ->
                            Pair(r.linkFolderName() ?: "Shared folder", r.list("").count { isOurClip(it.name) })
                        }
                        val src = RemoteSource(draft.id, "", CloudKind.DROPBOX, RemoteSources.MODE_LINK, "", name, who, link = url)
                        src.refreshToken = refresh
                        runOnUiThread { confirmAdd(src, name, n) }
                    } catch (e: Exception) { runOnUiThread { toast("Could not open the link: ${e.message}") } }
                }.start()
            }
            .setNegativeButton("Cancel", null).show()
    }

    // ---- FTP / SFTP

    private fun serverForm(kind: CloudKind) {
        val ctx = this
        val form = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; val p = Ui.dp(ctx, 20); setPadding(p, Ui.dp(ctx, 8), p, 0) }
        fun field(hint: String, type: Int, value: String = ""): EditText = EditText(ctx).apply {
            this.hint = hint; inputType = type; setText(value); setSingleLine()
        }.also { form.addView(it) }
        val host = field("Server address", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val port = field("Port", InputType.TYPE_CLASS_NUMBER, if (kind == CloudKind.SFTP) "22" else "21")
        val user = field("Username", InputType.TYPE_CLASS_TEXT)
        val pass = field("Password", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val tls = CheckBox(ctx).apply { text = "Use FTPS (encrypted)"; isChecked = true; setTextColor(Color.WHITE) }
        val useKey = CheckBox(ctx).apply { text = "Log in with a private key instead"; setTextColor(Color.WHITE) }
        val key = EditText(ctx).apply { hint = "Private key (paste the whole key)"; minLines = 3; maxLines = 5; textSize = 11f; visibility = View.GONE }
        val phrase = field("Key passphrase (if any)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD).apply { visibility = View.GONE }
        if (kind == CloudKind.FTP) form.addView(tls)
        else {
            form.addView(useKey); form.removeView(phrase); form.addView(key); form.addView(phrase)
            useKey.setOnCheckedChangeListener { _, b ->
                key.visibility = if (b) View.VISIBLE else View.GONE
                phrase.visibility = key.visibility
                pass.visibility = if (b) View.GONE else View.VISIBLE
            }
        }
        form.addView(Ui.label(ctx, "Tip: a read-only server account is safest. MotionCam only ever reads from it.", 12f, Ui.GREY))
        AlertDialog.Builder(ctx).setTitle(kind.label).setView(ScrollView(ctx).apply { addView(form) })
            .setPositiveButton("Connect") { _, _ ->
                val k = kind == CloudKind.SFTP && useKey.isChecked
                val draft = RemoteSource(RemoteSources.newId(), "", kind, RemoteSources.MODE_OWN, "", "", null,
                    host = host.text.toString().trim(), port = port.text.toString().toIntOrNull() ?: if (kind == CloudKind.SFTP) 22 else 21,
                    user = user.text.toString().trim(), tls = tls.isChecked, useKey = k)
                if (draft.host.isEmpty() || draft.user.isEmpty()) { toast("Enter the server address and username"); return@setPositiveButton }
                if (k) { draft.privateKey = key.text.toString().trim(); draft.passphrase = phrase.text.toString().ifEmpty { null } }
                else draft.password = pass.text.toString()
                Thread {
                    try {
                        RemoteSources.reader(this, draft).use { it.test() }
                        runOnUiThread {
                            if (kind == CloudKind.SFTP && draft.hostKey != null) toast("Server key ${draft.hostKey} will be remembered")
                            browse(draft, null, emptyList())
                        }
                    } catch (e: Exception) { runOnUiThread { toast("Could not connect: ${e.message}") } }
                }.start()
            }
            .setNegativeButton("Cancel", null).show()
    }

    // ---- folder browser (shared by every service)

    private fun browse(draft: RemoteSource, parent: RemoteFolder?, trail: List<RemoteFolder>) {
        toast("Loading folders…")
        Thread {
            try {
                val (folders, clipCount) = RemoteSources.reader(this, draft).use { r ->
                    val fs = r.listFolders(parent?.id)
                    Pair(fs.filter { !it.name.startsWith(".") }, parent?.let { p -> r.list(p.id).count { isOurClip(it.name) } } ?: 0)
                }
                runOnUiThread {
                    val items = ArrayList<String>()
                    if (parent != null) items += "✓ Use \"${parent.name}\"  ($clipCount MotionCam clip${if (clipCount == 1) "" else "s"})"
                    if (parent != null) items += "⬆ Up"
                    folders.forEach { items += "▸ ${it.name}" }
                    val head = items.size - folders.size
                    val title = parent?.name ?: "Choose a folder"
                    val list = ArrayList<com.motioncam.Opt>()
                    list += com.motioncam.Opt("New folder here", if (parent == null) "Start a new folder for MotionCam clips" else "Inside \"${parent.name}\"",
                        IconDrawable.Kind.ADD_TO, section = "Create") {
                        askText("New folder name", "MotionCam") { n ->
                            val name = n.trim().ifEmpty { "MotionCam" }
                            Thread {
                                try {
                                    val f = RemoteSources.reader(this, draft).use { it.createFolder(parent?.id, name) }
                                    runOnUiThread { confirmAdd(withFolder(draft, f), f.name, 0) }
                                } catch (e: Exception) { runOnUiThread { toast("Could not create the folder: ${e.message}") } }
                            }.start()
                        }
                    }
                    if (parent != null) {
                        list += com.motioncam.Opt("Use “${parent.name}”", "$clipCount MotionCam clip${if (clipCount == 1) "" else "s"} in it", checked = true,
                            section = "This folder") { confirmAdd(withFolder(draft, parent), parent.name, clipCount) }
                        list += com.motioncam.Opt("Up one level", icon = IconDrawable.Kind.BACK, section = "This folder") { browse(draft, trail.lastOrNull(), trail.dropLast(1)) }
                    }
                    for (fo in folders) list += com.motioncam.Opt(fo.name, icon = IconDrawable.Kind.FOLDER, section = if (parent != null) "Folders inside" else "Existing folders") {
                        browse(draft, fo, if (parent != null) trail + parent else trail)
                    }
                    com.motioncam.OptionSheet.show(this, title, list, subtitle = if (parent == null && draft.kind == CloudKind.DRIVE)
                        "MotionCam only sees folders it created in ${draft.account}." else null)
                }
            } catch (e: Exception) { runOnUiThread { toast("Could not list folders: ${e.message}") } }
        }.start()
    }

    private fun withFolder(draft: RemoteSource, f: RemoteFolder) = RemoteSource(draft.id, "", draft.kind, draft.mode, f.id, f.name,
        draft.account, draft.host, draft.port, draft.user, draft.tls, draft.useKey, draft.hostKey).also {
        it.password = draft.password; it.privateKey = draft.privateKey; it.passphrase = draft.passphrase; it.refreshToken = draft.refreshToken
    }

    private fun confirmAdd(src: RemoteSource, folderName: String, clipCount: Int) {
        val suggested = when (src.kind) {
            CloudKind.DRIVE -> folderName
            CloudKind.DROPBOX -> if (src.mode == RemoteSources.MODE_LINK) "$folderName (link)" else folderName
            else -> "$folderName on ${src.host}"
        }
        askText("Name this folder ($clipCount clips)", suggested) { n ->
            src.name = n.trim().ifEmpty { suggested }
            RemoteSources.add(this, src)
            render()
            if (src.mode == RemoteSources.MODE_LINK) { if (clipCount > 0) open(src); return@askText }
            // last step: should this phone back up here? (⋮ on the folder changes it later)
            AlertDialog.Builder(this).setTitle("Back up this phone's new clips to “${src.name}”?")
                .setMessage("New clips recorded on this phone are uploaded to this folder, so other phones can watch them. A phone can " +
                    "back up to several folders.\n\nYou can change this any time with the folder's ⋮ menu.")
                .setPositiveButton("Back up here") { _, _ -> backUpHere(src); if (clipCount > 0) open(src) }
                .setNegativeButton("Only view it") { _, _ -> if (clipCount > 0) open(src) }
                .show()
        }
    }

    // ------------------------------------------------------------------ small helpers

    private fun askText(title: String, initial: String, ok: (String) -> Unit) {
        val input = EditText(this).apply { setText(initial); setSelectAllOnFocus(true); setSingleLine() }
        AlertDialog.Builder(this).setTitle(title).setView(input)
            .setPositiveButton("OK") { _, _ -> ok(input.text.toString()) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun ago(t: Long): String {
        val m = (System.currentTimeMillis() - t) / 60_000
        return when { m < 1 -> "just now"; m < 60 -> "$m min ago"; m < 1440 -> "${m / 60} h ago"; else -> "${m / 1440} d ago" }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun title(s: String): View =
        Ui.label(this, s.uppercase(), 13f, Ui.ACCENT).apply { setPadding(0, Ui.dp(this@RemoteFoldersActivity, 22), 0, Ui.dp(this@RemoteFoldersActivity, 4)) }
            .also { box.addView(it) }

    private fun note(s: String) {
        box.addView(Ui.label(this, s, 12f, 0xFFB0B4BA.toInt()).apply { setPadding(0, Ui.dp(this@RemoteFoldersActivity, 4), 0, Ui.dp(this@RemoteFoldersActivity, 4)) })
    }
}
