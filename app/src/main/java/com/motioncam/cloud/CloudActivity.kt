package com.motioncam.cloud

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.motioncam.Collections
import com.motioncam.Ui
import com.motioncam.topBar

/** Settings for one backup destination (Google Drive, Dropbox, FTP or SFTP). */
class CloudActivity : Activity() {
    companion object {
        const val EXTRA_KIND = "kind"
        private const val REQ_GOOGLE = 901
    }

    private lateinit var kind: CloudKind
    private lateinit var cfg: CloudConfig
    private lateinit var box: LinearLayout
    private lateinit var statusTv: TextView
    private val ui = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() { statusTv.text = CloudSync.status(this@CloudActivity, kind); ui.postDelayed(this, 1500) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        kind = CloudKind.of(intent.getStringExtra(EXTRA_KIND)) ?: CloudKind.DRIVE
        cfg = CloudConfig(this, kind)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.BG) }
        root.addView(topBar(this, kind.label))
        box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = Ui.dp(this@CloudActivity, 16)
            setPadding(p, 0, p, p * 2)
        }
        root.addView(ScrollView(this).apply { addView(box) }, LinearLayout.LayoutParams(-1, 0, 1f))
        Ui.capWidth(box)
        setContentView(root)
        build()
    }

    override fun onResume() { super.onResume(); ui.post(ticker) }
    override fun onPause() { ui.removeCallbacks(ticker); super.onPause() }

    private fun build() {
        box.removeAllViews()
        title("Status")
        statusTv = Ui.label(this, CloudSync.status(this, kind), 14f)
        box.addView(statusTv)
        cfg.lastError?.let { if (cfg.blocked != null || CloudSync.counts(this, kind).failed > 0) note("Last error: $it") }
        row(button("View queue") { startActivity(Intent(this, UploadQueueActivity::class.java)) },
            button("Retry now") { CloudSync.retryNow(this, kind); toast("Retrying") })
        box.addView(button("Upload existing clips…") { uploadExisting() })
        note("Clips already in the backup folder (same name) are skipped, so nothing is uploaded twice.")

        title("Backup")
        toggle("Back up new clips to ${kind.label}", cfg.enabled) { on ->
            if (on && !cfg.isConfigured) { toast("Connect ${kind.label} first"); build(); return@toggle }
            cfg.enabled = on
            if (on) CloudSync.kick(this) else CloudSync.dropJobs(this, kind)
        }

        title("Account")
        when (kind) {
            CloudKind.DRIVE -> accountRow("Connect Google Drive") { connectGoogle() }
            CloudKind.DROPBOX -> accountRow("Connect Dropbox") { connectDropbox() }
            CloudKind.FTP -> serverFields()
            CloudKind.SFTP -> serverFields()
        }
        if (cfg.isConfigured) box.addView(button("Test connection") { testConnection() })

        title("Folder")
        note(when (kind) {
            CloudKind.DRIVE -> "MotionCam can only see folders and files it created itself (Google's \"drive.file\" access), never the rest of your Drive."
            CloudKind.DROPBOX -> "Everything stays inside Dropbox/Apps/MotionCam; the app cannot see the rest of your Dropbox."
            else -> "Only files named MC_….mp4 inside this folder are ever deleted."
        })
        box.addView(Ui.label(this, "Current: " + (cfg.folderName ?: "MotionCam (created automatically)"), 14f))
        if (cfg.isConfigured) box.addView(button("Choose or create folder…") { browse(null, listOf()) })

        title("When ${kind.label} is full")
        choice(listOf("Delete the oldest backed-up clips to make room", "Pause uploads and notify me"), cfg.fullPolicy) { cfg.fullPolicy = it }
        number("Use at most (0 = no limit)", "GB", cfg.maxGb) { cfg.maxGb = it }
        number("Delete cloud copies older than (0 = never)", "days", cfg.keepDays) { cfg.keepDays = it }
        val prot = Collections.all(this).filter { it.id in cfg.protectedCollections }.joinToString { it.name }.ifEmpty { "none" }
        box.addView(Ui.label(this, "Never delete clips in these collections: $prot", 14f).apply { setPadding(0, Ui.dp(this@CloudActivity, 12), 0, 0) })
        box.addView(button("Choose protected collections…") { chooseProtected() })
        note("Clean-up only ever deletes MotionCam's own clips in the backup folder, oldest first.")
    }

    // ------------------------------------------------------------------ account

    private fun accountRow(connectLabel: String, connect: () -> Unit) {
        if (cfg.isConfigured) {
            box.addView(Ui.label(this, "Connected: ${cfg.account ?: kind.label}", 14f))
            box.addView(button("Disconnect") {
                AlertDialog.Builder(this).setTitle("Disconnect ${kind.label}?")
                    .setMessage("Uploads stop and the sign-in is removed from this phone. Clips already in the cloud stay there.")
                    .setPositiveButton("Disconnect") { _, _ -> cfg.disconnect(); cfg.enabled = false; CloudSync.dropJobs(this, kind); build() }
                    .setNegativeButton("Cancel", null).show()
            })
        } else {
            note(if (kind == CloudKind.DRIVE) "You'll pick a Google account and allow MotionCam to save its own files in your Drive."
                 else "Your browser opens; sign in to Dropbox and tap Allow, then come back here.")
            box.addView(button(connectLabel) { connect() })
        }
    }

    private fun connectGoogle() {
        GoogleAuth.start(this, REQ_GOOGLE, { token -> finishGoogle(token) }, { err -> toast("Google sign-in failed: $err") })
    }

    private fun finishGoogle(token: String) {
        Thread {
            try {
                val email = DriveProvider({ token }, {}).test()
                cfg.account = email
                cfg.blocked = null
                runOnUiThread { toast("Connected to Google Drive as $email"); cfg.enabled = true; CloudSync.kick(this); build() }
            } catch (e: Exception) { runOnUiThread { toast("Google Drive: ${e.message}") } }
        }.start()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_GOOGLE) {
            val t = if (resultCode == RESULT_OK) GoogleAuth.result(this, data) else null
            if (t != null) finishGoogle(t) else toast("Google sign-in was cancelled")
            return
        }
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
    }

    private fun connectDropbox() {
        val verifier = Pkce.verifier()
        val state = Pkce.state()
        val rx = try { LoopbackReceiver(DropboxProvider.REDIRECT_PORT) } catch (e: Exception) { toast("Could not start sign-in: ${e.message}"); return }
        Thread {
            try {
                val code = rx.waitForCode(state, 5 * 60_000)
                val refresh = DropboxProvider.exchange(code, verifier)
                cfg.refreshToken = refresh
                val who = DropboxProvider(DropboxProvider.APP_KEY, refresh).test()
                cfg.account = who
                cfg.blocked = null
                runOnUiThread { toast("Connected to Dropbox as $who — return complete"); cfg.enabled = true; CloudSync.kick(this); build() }
            } catch (e: Exception) {
                runOnUiThread { toast("Dropbox sign-in failed: ${e.message}") }
            } finally { rx.close() }
        }.start()
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(DropboxProvider.authorizeUrl(Pkce.challenge(verifier), state))))
    }

    private fun serverFields() {
        field("Server address", cfg.host, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI) { cfg.host = it }
        field("Port", cfg.port.toString(), InputType.TYPE_CLASS_NUMBER) { v -> v.toIntOrNull()?.let { if (it in 1..65535) cfg.port = it } }
        field("Username", cfg.user, InputType.TYPE_CLASS_TEXT) { cfg.user = it }
        if (kind == CloudKind.FTP) {
            field("Password", cfg.password ?: "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD) { cfg.password = it }
            toggle("Use FTPS (encrypted, recommended)", cfg.tls) { cfg.tls = it }
            note("Plain FTP sends your password and clips unencrypted; only use it on a network you trust.")
        } else {
            choice(listOf("Password", "Private key"), if (cfg.useKey) 1 else 0) { cfg.useKey = it == 1; build() }
            if (!cfg.useKey) field("Password", cfg.password ?: "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD) { cfg.password = it }
            else {
                field("Private key (paste the whole key, OpenSSH or PEM)", cfg.privateKey ?: "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE, multi = true) { cfg.privateKey = it }
                field("Key passphrase (if any)", cfg.passphrase ?: "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD) { cfg.passphrase = it }
            }
            val hk = cfg.hostKey
            note(if (hk == null) "The server's identity (host key) is remembered on the first connection." else "Trusted server key: $hk")
            if (hk != null) box.addView(button("Forget host key") { cfg.hostKey = null; build() })
        }
        note("Passwords and keys are stored encrypted on this phone only.")
    }

    private fun testConnection() {
        toast("Testing…")
        Thread {
            val msg = try {
                CloudSync.providerFor(this, kind).use { p ->
                    val who = p.test()
                    if (kind == CloudKind.FTP || kind == CloudKind.SFTP) cfg.account = who
                    val q = try { p.quota(cfg.folderId ?: "/") } catch (e: Exception) { null }
                    "Connected as $who" + (q?.free?.let { "\nFree space: %.1f GB".format(it / 1e9) } ?: "")
                }
            } catch (e: Exception) { "Failed: ${e.message}" }
            runOnUiThread { AlertDialog.Builder(this).setTitle("Connection test").setMessage(msg).setPositiveButton("OK", null).show(); build() }
        }.start()
    }

    // ------------------------------------------------------------------ folder picker

    /** Simple folder browser: open a folder, go up, create a folder, or pick the current one. */
    private fun browse(parent: RemoteFolder?, trail: List<RemoteFolder>) {
        toast("Loading folders…")
        Thread {
            try {
                val folders = CloudSync.providerFor(this, kind).use { it.listFolders(parent?.id) }
                runOnUiThread {
                    val items = ArrayList<String>()
                    if (parent != null) items += "✓ Use \"${parent.name}\""
                    items += "＋ New folder here"
                    if (parent != null) items += "⬆ Up"
                    folders.forEach { items += "▸ ${it.name}" }
                    val head = items.size - folders.size
                    AlertDialog.Builder(this).setTitle(if (parent == null) "Choose a folder" else parent.name)
                        .setItems(items.toTypedArray()) { _, i ->
                            val label = items[i]
                            when {
                                label.startsWith("✓") -> pick(parent!!)
                                label.startsWith("＋") -> askName { name -> create(parent, name) }
                                label.startsWith("⬆") -> browse(trail.lastOrNull(), trail.dropLast(1))
                                else -> browse(folders[i - head], if (parent != null) trail + parent else trail)
                            }
                        }
                        .setNegativeButton("Cancel", null).show()
                }
            } catch (e: Exception) { runOnUiThread { toast("Could not list folders: ${e.message}") } }
        }.start()
    }

    private fun create(parent: RemoteFolder?, name: String) {
        Thread {
            try {
                val f = CloudSync.providerFor(this, kind).use { it.createFolder(parent?.id, name) }
                runOnUiThread { pick(f) }
            } catch (e: Exception) { runOnUiThread { toast("Could not create folder: ${e.message}") } }
        }.start()
    }

    private fun pick(f: RemoteFolder) {
        cfg.folderId = f.id
        cfg.folderName = f.name
        toast("Backups go to \"${f.name}\"")
        build()
    }

    private fun askName(ok: (String) -> Unit) {
        val input = EditText(this).apply { hint = "Folder name"; setText("MotionCam") ; setSelectAllOnFocus(true) }
        AlertDialog.Builder(this).setTitle("New folder").setView(input)
            .setPositiveButton("Create") { _, _ -> input.text.toString().trim().takeIf { it.isNotEmpty() }?.let(ok) }
            .setNegativeButton("Cancel", null).show()
    }

    // ------------------------------------------------------------------ other actions

    private fun chooseProtected() {
        val cols = Collections.all(this)
        val checked = BooleanArray(cols.size) { cols[it].id in cfg.protectedCollections }
        AlertDialog.Builder(this).setTitle("Never delete from ${kind.label}")
            .setMultiChoiceItems(cols.map { it.name }.toTypedArray(), checked) { _, i, b -> checked[i] = b }
            .setPositiveButton("Save") { _, _ -> cfg.protectedCollections = cols.filterIndexed { i, _ -> checked[i] }.map { it.id }.toSet(); build() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun uploadExisting() {
        if (!cfg.isConfigured) { toast("Connect ${kind.label} first"); return }
        val opts = arrayOf("Last 24 hours", "Last 7 days", "Last 30 days", "All clips")
        val spans = longArrayOf(86_400_000L, 7 * 86_400_000L, 30 * 86_400_000L, 0)
        AlertDialog.Builder(this).setTitle("Upload existing clips").setItems(opts) { _, i ->
            if (!cfg.enabled) { cfg.enabled = true }
            Thread {
                val n = CloudSync.enqueueExisting(this, kind, if (spans[i] == 0L) 0 else System.currentTimeMillis() - spans[i])
                runOnUiThread { toast("$n clips queued"); build() }
            }.start()
        }.setNegativeButton("Cancel", null).show()
    }

    // ------------------------------------------------------------------ small UI builders

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun title(s: String) {
        box.addView(Ui.label(this, s.uppercase(), 13f, Ui.ACCENT).apply { setPadding(0, Ui.dp(this@CloudActivity, 22), 0, Ui.dp(this@CloudActivity, 6)) })
    }

    private fun note(s: String) {
        box.addView(Ui.label(this, s, 12f, 0xFFB0B4BA.toInt()).apply { setPadding(0, Ui.dp(this@CloudActivity, 4), 0, Ui.dp(this@CloudActivity, 4)) })
    }

    private fun button(label: String, click: () -> Unit): View =
        Ui.chip(this, label).apply { setOnClickListener { click() } }.also {
            it.layoutParams = LinearLayout.LayoutParams(-2, -2).apply { topMargin = Ui.dp(this@CloudActivity, 8); rightMargin = Ui.dp(this@CloudActivity, 8) }
        }

    private fun row(vararg views: View) {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        views.forEach { r.addView(it) }
        box.addView(r)
    }

    private fun toggle(label: String, value: Boolean, set: (Boolean) -> Unit) {
        box.addView(Switch(this).apply {
            text = label
            setTextColor(Color.WHITE)
            isChecked = value
            setOnCheckedChangeListener { _, b -> set(b) }
            setPadding(0, Ui.dp(this@CloudActivity, 8), 0, Ui.dp(this@CloudActivity, 8))
        })
    }

    private fun field(label: String, value: String, type: Int, multi: Boolean = false, set: (String) -> Unit) {
        box.addView(Ui.label(this, label, 13f, Ui.GREY).apply { setPadding(0, Ui.dp(this@CloudActivity, 8), 0, 0) })
        box.addView(EditText(this).apply {
            setText(value)
            setTextColor(Color.WHITE)
            inputType = type
            if (multi) { minLines = 3; maxLines = 6; textSize = 11f } else setSingleLine()
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { set(s?.toString() ?: "") }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        })
    }

    private fun number(label: String, unit: String, value: Int, set: (Int) -> Unit) {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, Ui.dp(this@CloudActivity, 8), 0, 0) }
        r.addView(Ui.label(this, label, 14f), LinearLayout.LayoutParams(0, -2, 1f))
        r.addView(EditText(this).apply {
            setText(if (value == 0) "" else "$value")
            hint = "0"
            setTextColor(Color.WHITE)
            gravity = Gravity.END
            inputType = InputType.TYPE_CLASS_NUMBER
            minWidth = Ui.dp(this@CloudActivity, 64)
            setSingleLine()
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { set(s?.toString()?.toIntOrNull()?.coerceAtLeast(0) ?: 0) }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        })
        r.addView(Ui.label(this, unit, 13f, Ui.GREY))
        box.addView(r)
    }

    private fun choice(options: List<String>, current: Int, set: (Int) -> Unit) {
        val group = android.widget.RadioGroup(this)
        options.forEachIndexed { i, o ->
            group.addView(android.widget.RadioButton(this).apply { text = o; id = 1000 + i; setTextColor(Color.WHITE); isChecked = i == current })
        }
        group.setOnCheckedChangeListener { _, id -> set(id - 1000) }
        box.addView(group)
    }
}
