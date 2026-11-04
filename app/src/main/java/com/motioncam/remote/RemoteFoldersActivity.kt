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
    }

    private lateinit var box: LinearLayout
    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.BG) }
        val add = Ui.chip(this, "＋ Add").apply { setOnClickListener { addSource() } }
        root.addView(topBar(this, "Remote folders", add))
        box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = Ui.dp(this@RemoteFoldersActivity, 14)
            setPadding(p, 0, p, p * 2)
        }
        root.addView(ScrollView(this).apply { addView(box) }, LinearLayout.LayoutParams(-1, 0, 1f))
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

    override fun onResume() { super.onResume(); render(); ui.postDelayed(tick, 30_000L) }
    override fun onPause() { ui.removeCallbacks(tick); super.onPause() }

    private fun checkedText(s: RemoteSource) =
        if (s.lastRefresh == 0L) "not loaded yet" else "${s.clipCount} clips · checked " + ago(s.lastRefresh)

    private fun render() {
        box.removeAllViews()
        checkedLabels.clear()
        val all = RemoteSources.all(this)
        val shown = all.filter { prefs.remoteFtp || (it.kind != CloudKind.FTP && it.kind != CloudKind.SFTP) }
        if (shown.isEmpty()) {
            box.addView(Ui.label(this, "No remote folders yet. Tap ＋ Add to view the clips another phone backs up.", 15f).apply {
                setPadding(0, Ui.dp(this@RemoteFoldersActivity, 16), 0, Ui.dp(this@RemoteFoldersActivity, 8))
            })
        }
        for (s in shown.sortedBy { it.added }) box.addView(row(s))
        val hidden = all.size - shown.size
        if (hidden > 0) note("$hidden FTP/SFTP folder${if (hidden == 1) " is" else "s are"} hidden (Settings → Remote folders).")
        title("How it works")
        note("Remote folders never change your clips: this device never uploads, renames or deletes them. The only thing it " +
            "writes is a small \"protected\" mark when you favourite a clip, add it to a collection or protect it, so the " +
            "camera phone's clean-up keeps it (Collections → Protected in cloud shows all marks). Clips stream " +
            "when you play them and are kept in a cache (Settings → Remote folders) so replaying and scrubbing are instant. " +
            "Favourites and collections you make here stay on this device.")
        title("Which account to use")
        note("• Google Drive: sign in here with the same Google account the recording phone backs up to (＋ Add → Google Drive). " +
            "Other Google accounts can't be used: MotionCam's Drive access only sees files MotionCam created in that account.\n" +
            "• Dropbox, same account: ＋ Add → Dropbox: my own backups.\n" +
            "• Dropbox, another account: on the recording phone open Settings → Cloud backup → Dropbox → Create viewer link, " +
            "and send the link to this device. Then here: ＋ Add → Dropbox: a viewer link.\n" +
            "Thumbnails show the moment with the most motion when the recording phone has \"Upload thumbnails for other " +
            "devices\" on (default).")
    }

    private fun row(s: RemoteSource): View {
        val ctx = this
        val r = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(ctx, 10), Ui.dp(ctx, 10), Ui.dp(ctx, 2), Ui.dp(ctx, 10))
            background = GradientDrawable().apply { setColor(0xFF1E2126.toInt()); cornerRadius = Ui.dp(ctx, 10).toFloat() }
            isClickable = true
            setOnClickListener { open(s) }
        }
        r.addView(iconButton(ctx, IconDrawable.Kind.CLOUD, 40, 8))
        val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dp(ctx, 10), 0, 0, 0) }
        texts.addView(Ui.label(ctx, s.name, 16f))
        texts.addView(Ui.label(ctx, s.describe, 12f, Ui.GREY).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        texts.addView(Ui.label(ctx, checkedText(s), 12f, Ui.GREY).also { checkedLabels[it] = s })
        val conflicts = SharedSync.state(ctx, s.id).conflicts.size
        if (conflicts > 0) texts.addView(Ui.label(ctx, "⚠ $conflicts collection sync conflict${if (conflicts == 1) "" else "s"} — open Collections › Sync status", 12f, Ui.ACCENT))
        r.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        lateinit var more: View
        more = iconButton(ctx, IconDrawable.Kind.MORE, 44, 10) {
            val pm = PopupMenu(ctx, more)
            pm.menu.add(0, 1, 0, "Open")
            pm.menu.add(0, 2, 1, "Collections")
            pm.menu.add(0, 3, 2, "Rename")
            pm.menu.add(0, 4, 3, "Remove from this device")
            pm.setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> open(s)
                    2 -> startActivity(Intent(ctx, CollectionsActivity::class.java).putExtra(CollectionsActivity.EXTRA_REMOTE, s.id))
                    3 -> askText("Rename", s.name) { n -> if (n.isNotBlank()) { s.name = n.trim(); RemoteSources.update(ctx, s); render() } }
                    4 -> AlertDialog.Builder(ctx).setTitle("Remove \"${s.name}\"?")
                        .setMessage("Removes it from this device only (with its cached clips and the collections you made for it). " +
                            "Nothing in the folder itself is touched. Clips you saved to this phone are kept.")
                        .setPositiveButton("Remove") { _, _ ->
                            RemoteRepo.forget(ctx, s)
                            Collections.dropRemote(ctx, s.id)
                            RemoteSources.remove(ctx, s.id)
                            render()
                        }
                        .setNegativeButton("Cancel", null).show()
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

    private fun open(s: RemoteSource) {
        startActivity(Intent(this, ClipsActivity::class.java).putExtra(ClipsActivity.EXTRA_REMOTE, s.id))
    }

    // ------------------------------------------------------------------ adding

    private fun addSource() {
        val opts = ArrayList<Pair<String, () -> Unit>>()
        val list = ArrayList<com.motioncam.Opt>()
        list += com.motioncam.Opt("Google Drive", "Sign in with the Google account the recording phone backs up to", IconDrawable.Kind.CLOUD) { googleSignIn() }
        list += com.motioncam.Opt("Dropbox — my own backups", "Same Dropbox account as the recording phone", IconDrawable.Kind.CLOUD) { dropboxAccount { t, w -> dropboxOwn(t, w) } }
        list += com.motioncam.Opt("Dropbox — viewer link", "A link made on the recording phone (any Dropbox account)", IconDrawable.Kind.CLOUD) { dropboxAccount { t, w -> dropboxLink(t, w) } }
        if (prefs.remoteFtp) {
            list += com.motioncam.Opt("FTP / FTPS server", "Your own server; clips download before playing", IconDrawable.Kind.FOLDER) { serverForm(CloudKind.FTP) }
            list += com.motioncam.Opt("SFTP server", "Your own server over SSH", IconDrawable.Kind.FOLDER) { serverForm(CloudKind.SFTP) }
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
            .setMessage("Made on the recording phone: Settings → Cloud backup → Dropbox → Create viewer link.")
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

    private fun title(s: String) {
        box.addView(Ui.label(this, s.uppercase(), 13f, Ui.ACCENT).apply { setPadding(0, Ui.dp(this@RemoteFoldersActivity, 22), 0, Ui.dp(this@RemoteFoldersActivity, 4)) })
    }

    private fun note(s: String) {
        box.addView(Ui.label(this, s, 12f, 0xFFB0B4BA.toInt()).apply { setPadding(0, Ui.dp(this@RemoteFoldersActivity, 4), 0, Ui.dp(this@RemoteFoldersActivity, 4)) })
    }
}
