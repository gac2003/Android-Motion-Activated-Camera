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
class RemoteFoldersActivity : Activity() {
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.BG) }
        // title bar: ⚙ folder settings (a page of their own) and ＋ Add
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        actions.addView(iconButton(this, IconDrawable.Kind.SETTINGS, 44, 11) {
            startActivity(Intent(this, FolderSettingsActivity::class.java))
        }.apply { contentDescription = "Remote folder settings"; tooltipText = "Remote folder settings" },
            LinearLayout.LayoutParams(-2, -2).apply { rightMargin = Ui.dp(this@RemoteFoldersActivity, 6) })
        actions.addView(Ui.chip(this, "＋ Add").apply { setOnClickListener { addSource() } })
        root.addView(topBar(this, "Remote Folders", actions))
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
            // "set up backup": with no folder backed up yet, go straight to choosing one
            scrolledToBackup = true
            if (folders().none { it.backup != null }) scroll.post { addSource() }
        } else scroll.post { scroll.scrollTo(0, y) }
    }
    override fun onPause() { ui.removeCallbacks(tick); ui.removeCallbacks(liveTick); super.onPause() }

    private fun checkedText(s: RemoteSource) =
        if (s.lastRefresh == 0L) "not loaded yet" else "${s.clipCount} clips · checked " + ago(s.lastRefresh)

    /**
     * One cloud folder of MotionCam clips. This phone may back up to it ([backup]), view its clips ([source]), or both:
     * the same folder is never listed twice.
     */
    private class Folder(val kind: CloudKind, val backup: CloudConfig?, val source: RemoteSource?)

    private fun folders(): List<Folder> {
        val all = RemoteSources.all(this)
        val used = HashSet<String>()
        val out = ArrayList<Folder>()
        for (k in CloudKind.values()) {
            val cfg = CloudConfig(this, k)
            val fid = cfg.folderId ?: continue
            if (!cfg.isConfigured) continue
            val s = all.firstOrNull { it.kind == k && it.mode != RemoteSources.MODE_LINK && it.folderId == fid }
            s?.let { used += it.id }
            out += Folder(k, cfg, s)
        }
        for (s in all.sortedBy { it.added }) if (s.id !in used) out += Folder(s.kind, null, s)
        return out
    }

    private fun isServer(k: CloudKind) = k == CloudKind.FTP || k == CloudKind.SFTP

    private fun render() {
        box.removeAllViews()
        checkedLabels.clear()
        liveLines.clear()
        val list = folders()
        val shown = list.filter { it.backup != null || prefs.remoteFtp || !isServer(it.kind) }
        note("Cloud folders of MotionCam clips: the ones this phone backs up to (↑) and other phones' folders. Tap one to " +
            "see its clips. ＋ Add to back up this phone or to open another phone's folder.")
        if (shown.isEmpty()) {
            box.addView(Ui.label(this, "No folders yet. Tap ＋ Add to back up this phone's clips to the cloud, or to see the clips another phone backs up.", 15f).apply {
                setPadding(0, Ui.dp(this@RemoteFoldersActivity, 16), 0, Ui.dp(this@RemoteFoldersActivity, 8))
            })
        }
        for (f in shown) box.addView(folderRow(f))
        val hidden = list.size - shown.size
        if (hidden > 0) note("$hidden FTP/SFTP folder${if (hidden == 1) " is" else "s are"} hidden (⚙ → Allow FTP / SFTP servers).")
        quickActions()
    }

    private fun backupDescribe(k: CloudKind, c: CloudConfig) = when (k) {
        CloudKind.DRIVE -> "Google Drive · " + (c.account ?: "")
        CloudKind.DROPBOX -> "Dropbox" + (c.account?.let { " · $it" } ?: "")
        CloudKind.FTP -> (if (c.tls) "FTPS · " else "FTP · ") + "${c.user}@${c.host}"
        CloudKind.SFTP -> "SFTP · ${c.user}@${c.host}"
    }

    private fun folderRow(f: Folder): View {
        val ctx = this
        val s = f.source
        val cfg = f.backup
        val r = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(ctx, 10), Ui.dp(ctx, 10), Ui.dp(ctx, 2), Ui.dp(ctx, 10))
            background = GradientDrawable().apply { setColor(0xFF1E2126.toInt()); cornerRadius = Ui.dp(ctx, 10).toFloat() }
            isClickable = true
            setOnClickListener { openFolder(f) }
        }
        r.addView(iconButton(ctx, if (isServer(f.kind)) IconDrawable.Kind.FOLDER else IconDrawable.Kind.CLOUD, 40, 8))
        val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dp(ctx, 10), 0, 0, 0) }
        texts.addView(Ui.label(ctx, s?.name ?: (cfg?.folderName ?: "MotionCam"), 16f))
        texts.addView(Ui.label(ctx, s?.describe ?: backupDescribe(f.kind, cfg!!), 12f, Ui.GREY).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        if (cfg != null) {
            val st = Ui.label(ctx, "", 12f, Ui.ACCENT)
            val update = {
                val t = if (cfg.enabled) "↑ This phone backs up here · " + com.motioncam.cloud.CloudSync.status(ctx, f.kind)
                    else "↑ Backup to this folder is off (⋮ → Backup settings)"
                if (st.text != t) st.text = t
            }
            update(); liveLines += update
            texts.addView(st)
        }
        if (s != null) texts.addView(Ui.label(ctx, checkedText(s), 12f, Ui.GREY).also { checkedLabels[it] = s })
        else texts.addView(Ui.label(ctx, "Tap to see its clips", 12f, Ui.GREY))
        if (s != null) {
            val conflicts = SharedSync.state(ctx, s.id).conflicts.size
            if (conflicts > 0) texts.addView(Ui.label(ctx, "⚠ $conflicts collection sync conflict${if (conflicts == 1) "" else "s"} — open Collections › Sync status", 12f, Ui.ACCENT))
        }
        r.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        lateinit var more: View
        more = iconButton(ctx, IconDrawable.Kind.MORE, 44, 10) {
            val pm = PopupMenu(ctx, more)
            pm.menu.add(0, 1, 0, "Open")
            pm.menu.add(0, 2, 1, "Collections")
            if (cfg != null) pm.menu.add(0, 5, 2, "Backup settings…")
            else if (s != null && s.mode != RemoteSources.MODE_LINK) pm.menu.add(0, 6, 2, "Back up this phone here")
            if (s != null) pm.menu.add(0, 3, 3, "Rename")
            if (s != null && cfg == null) pm.menu.add(0, 4, 4, "Remove from this device")
            pm.setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> openFolder(f)
                    2 -> startActivity(Intent(ctx, CollectionsActivity::class.java).putExtra(CollectionsActivity.EXTRA_REMOTE, sourceOf(f).id))
                    3 -> askText("Rename", s!!.name) { n -> if (n.isNotBlank()) { s.name = n.trim(); RemoteSources.update(ctx, s); render() } }
                    4 -> AlertDialog.Builder(ctx).setTitle("Remove \"${s!!.name}\"?")
                        .setMessage("Removes it from this device only (with its cached clips and the collections you made for it). " +
                            "Nothing in the folder itself is touched. Clips you saved to this phone are kept.")
                        .setPositiveButton("Remove") { _, _ ->
                            RemoteRepo.forget(ctx, s)
                            Collections.dropRemote(ctx, s.id)
                            RemoteSources.remove(ctx, s.id)
                            render()
                        }
                        .setNegativeButton("Cancel", null).show()
                    5 -> startActivity(Intent(ctx, com.motioncam.cloud.CloudActivity::class.java).putExtra(com.motioncam.cloud.CloudActivity.EXTRA_KIND, f.kind.key))
                    6 -> backUpHere(s!!)
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

    /** The folder as a source of clips to view (this phone's backup folder gets one the first time it is opened). */
    private fun sourceOf(f: Folder): RemoteSource {
        f.source?.let { return it }
        val c = f.backup!!
        val s = RemoteSource(id = RemoteSources.newId(), name = c.folderName ?: "MotionCam", kind = f.kind, mode = RemoteSources.MODE_OWN,
            folderId = c.folderId!!, folderName = c.folderName ?: "", account = c.account, host = c.host, port = c.port, user = c.user,
            tls = c.tls, useKey = c.useKey, hostKey = c.hostKey)
        s.password = c.password; s.privateKey = c.privateKey; s.passphrase = c.passphrase; s.refreshToken = c.refreshToken
        RemoteSources.add(this, s)
        return s
    }

    private fun openFolder(f: Folder) = open(sourceOf(f))

    /** Makes a folder this phone views also the folder it backs up to (same account / server). */
    private fun backUpHere(s: RemoteSource) {
        val c = CloudConfig(this, s.kind)
        fun go() {
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
            Toast.makeText(this, "This phone now backs up new clips to ${s.name}", Toast.LENGTH_LONG).show()
            render()
        }
        if (c.isConfigured && c.folderId != null && c.folderId != s.folderId)
            AlertDialog.Builder(this).setTitle("Back up here instead?")
                .setMessage("This phone backs up to \"${c.folderName ?: "MotionCam"}\" on ${s.kind.label}. A phone backs up to one folder per " +
                    "service, so new clips would go to \"${s.name}\" instead. Clips already uploaded stay where they are.")
                .setPositiveButton("Back up here") { _, _ -> go() }.setNegativeButton("Cancel", null).show()
        else go()
    }

    // ------------------------------------------------------------------ the two things used often here (settings: ⚙)

    private fun quickActions() {
        val ctx = this
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, Ui.dp(ctx, 18), 0, 0) }
        backupTitle = row
        row.addView(Ui.chip(ctx, "").apply {
            val chip = this
            val update = {
                val c = com.motioncam.cloud.CloudSync.counts(ctx, null)
                val t = "Upload queue · ${c.queued}" + if (c.failed > 0) " · ${c.failed} failed" else ""
                if (chip.text != t) chip.text = t
            }
            update(); liveLines += update
            setOnClickListener { startActivity(Intent(ctx, com.motioncam.cloud.UploadQueueActivity::class.java)) }
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = Ui.dp(ctx, 8) })
        row.addView(Ui.chip(ctx, "").apply {
            val chip = this
            val update = { chip.text = "Clear downloaded clips · ${StreamCache.cacheBytes(ctx) / 1_000_000} MB" }
            update(); liveLines += update
            setOnClickListener {
                AlertDialog.Builder(ctx).setTitle("Clear downloaded clips?")
                    .setMessage("Frees the space used by clips played from cloud folders. They stream again the next time you play them.")
                    .setPositiveButton("Clear") { _, _ -> StreamCache.clearAll(ctx); Toast.makeText(ctx, "Cleared", Toast.LENGTH_SHORT).show(); update() }
                    .setNegativeButton("Cancel", null).show()
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        box.addView(row)
    }

    private fun open(s: RemoteSource) {
        startActivity(Intent(this, ClipsActivity::class.java).putExtra(ClipsActivity.EXTRA_REMOTE, s.id))
    }

    // ------------------------------------------------------------------ adding

    private fun addSource() {
        val opts = ArrayList<Pair<String, () -> Unit>>()
        val list = ArrayList<com.motioncam.Opt>()
        // back up this phone (sign in, pick or create the folder); one folder per service
        for (k in CloudKind.values()) {
            val c = CloudConfig(this, k)
            val set = c.isConfigured && c.folderId != null
            list += com.motioncam.Opt(k.label + if (set) " (change)" else "", if (set) "Backing up to \"${c.folderName ?: "MotionCam"}\" now"
                else when (k) { CloudKind.DRIVE -> "Your Google account"; CloudKind.DROPBOX -> "Your Dropbox (an app folder)"; else -> "Your own server" },
                if (isServer(k)) IconDrawable.Kind.FOLDER else IconDrawable.Kind.CLOUD, section = "Back up this phone to") {
                startActivity(Intent(this, com.motioncam.cloud.CloudActivity::class.java).putExtra(com.motioncam.cloud.CloudActivity.EXTRA_KIND, k.key))
            }
        }
        list += com.motioncam.Opt("Google Drive", "Sign in with the Google account the recording phone backs up to", IconDrawable.Kind.CLOUD,
            section = "See another phone's folder") { googleSignIn() }
        list += com.motioncam.Opt("Dropbox — my own backups", "Same Dropbox account as the recording phone", IconDrawable.Kind.CLOUD, section = "See another phone's folder") { dropboxAccount { t, w -> dropboxOwn(t, w) } }
        list += com.motioncam.Opt("Dropbox — viewer link", "A link made on the recording phone (any Dropbox account)", IconDrawable.Kind.CLOUD, section = "See another phone's folder") { dropboxAccount { t, w -> dropboxLink(t, w) } }
        if (prefs.remoteFtp) {
            list += com.motioncam.Opt("FTP / FTPS server", "Your own server; clips download before playing", IconDrawable.Kind.FOLDER, section = "See another phone's folder") { serverForm(CloudKind.FTP) }
            list += com.motioncam.Opt("SFTP server", "Your own server over SSH", IconDrawable.Kind.FOLDER, section = "See another phone's folder") { serverForm(CloudKind.SFTP) }
        }
        com.motioncam.OptionSheet.show(this, "Add a remote folder", list)
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
                val refresh = DropboxProvider.exchange(code, verifier)
                val who = DropboxProvider(DropboxProvider.APP_KEY, refresh).test()
                runOnUiThread { toast("Signed in to Dropbox as $who"); then(refresh, who) }
            } catch (e: Exception) {
                runOnUiThread { toast("Dropbox sign-in failed: ${e.message}") }
            } finally { rx.close() }
        }.start()
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(DropboxProvider.authorizeUrl(Pkce.challenge(verifier), state))))
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
            .setMessage("Made on the recording phone: Clips → Remote Folders → its Dropbox folder's ⋮ → Backup settings → Create viewer link.")
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
                    val title = parent?.name ?: "Choose the backup folder"
                    if (items.isEmpty()) {
                        AlertDialog.Builder(this).setTitle(title).setMessage(
                            if (draft.kind == CloudKind.DRIVE) "No MotionCam folders in ${draft.account}. Sign in with the Google account the " +
                                "recording phone backs up to (MotionCam can only see folders it created in that account)."
                            else "No folders found.").setPositiveButton("OK", null).show()
                        return@runOnUiThread
                    }
                    val list = ArrayList<com.motioncam.Opt>()
                    if (parent != null) {
                        list += com.motioncam.Opt("Use “${parent.name}”", "$clipCount MotionCam clip${if (clipCount == 1) "" else "s"} in it", checked = true) {
                            confirmAdd(RemoteSource(draft.id, "", draft.kind, draft.mode, parent.id, parent.name,
                                draft.account, draft.host, draft.port, draft.user, draft.tls, draft.useKey, draft.hostKey).also {
                                it.password = draft.password; it.privateKey = draft.privateKey; it.passphrase = draft.passphrase
                                it.refreshToken = draft.refreshToken
                            }, parent.name, clipCount)
                        }
                        list += com.motioncam.Opt("Up one level", icon = IconDrawable.Kind.BACK) { browse(draft, trail.lastOrNull(), trail.dropLast(1)) }
                    }
                    for (fo in folders) list += com.motioncam.Opt(fo.name, icon = IconDrawable.Kind.FOLDER, section = if (parent != null) "Folders inside" else null) {
                        browse(draft, fo, if (parent != null) trail + parent else trail)
                    }
                    com.motioncam.OptionSheet.show(this, title, list)
                }
            } catch (e: Exception) { runOnUiThread { toast("Could not list folders: ${e.message}") } }
        }.start()
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
            open(src)
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
