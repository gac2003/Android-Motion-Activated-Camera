package com.motioncam.live

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.Toast
import com.motioncam.Fmt
import com.motioncam.IconDrawable
import com.motioncam.Prefs
import com.motioncam.Ui
import com.motioncam.cloud.Device
import com.motioncam.iconButton
import com.motioncam.topBar

/**
 * Live connection hub: join the network of your phones (Google account or network code), see which phones are
 * connected (cameras with battery / temperature / last motion), open a camera, alerts, background mode and PIN.
 */
class LiveActivity : Activity() {
    private lateinit var f: Form
    private lateinit var prefs: Prefs
    private val refresh: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.BG) }
        root.addView(topBar(this, "Live connection"))
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; val p = Ui.dp(this@LiveActivity, 16); setPadding(p, 0, p, p * 2) }
        root.addView(ScrollView(this).apply { addView(box) }, LinearLayout.LayoutParams(-1, 0, 1f))
        Ui.capWidth(box)
        setContentView(root)
        f = Form(this, box)
    }

    override fun onResume() { super.onResume(); Live.listeners += refresh; Live.viewerActive = true; Live.refresh(this); render() }
    override fun onPause() { Live.listeners -= refresh; super.onPause() }

    private fun render() {
        val box = f.box
        box.removeAllViews()
        if (!LiveConfig.configured) { f.note("This build has no live-connection project configured."); return }
        f.note("Connects your phones through the internet (Firebase), so it also works where phones can't reach each other " +
            "(captive Wi-Fi, isolated networks). Camera phones share their status, alerts and a live view; other phones can " +
            "control them. Clips and collections still come from the cloud folder.")
        f.toggle("Live connection on this phone", prefs.liveOn) { on ->
            if (on) chooseRole { enable() } else {
                prefs.liveOn = false
                LiveService.stop(this)
                CameraLink.stop(this)
                Live.refresh(this)
                AlertCheckJob.schedule(this)
            }
            render()
        }
        if (!prefs.liveOn) return
        f.line(if (prefs.liveCamera) "This phone: camera and viewer" else "This phone: viewer only (it can't be watched or controlled)",
            13f, Ui.GREY)

        f.title("Your phones' network")
        val mode = LiveNet.mode(this)
        if (mode == null || !LiveNet.ready(this)) {
            f.note("All your phones must join the same network: either the same Google account (simplest), or a network code " +
                "if you don't want to use Google.")
            f.button("Sign in with Google") {
                LiveNet.signInGoogle(this) { err ->
                    if (err != null) Toast.makeText(this, err, Toast.LENGTH_LONG).show() else { Live.refresh(this); render() }
                }
            }
            f.button("Create a network code") { createCode() }
            f.button("Join with a network code") { askCode() }
            return
        }
        if (mode == LiveNet.MODE_GOOGLE) f.line("Google account: ${LiveNet.account(this) ?: "signed in"}")
        else {
            f.line("Network code: •••••-•••••-•••••-•••••")
            f.row(f.chip("Show / share code") { showCode(LiveNet.shownCode(this) ?: "") })
            f.note("Anyone with the code can join and see your cameras: share it only with your own phones. Everything in a " +
                "code network is encrypted with the code.")
        }
        f.button("Leave this network (sign out)", full = false) {
            AlertDialog.Builder(this).setTitle("Leave the network?").setMessage("This phone stops sending and receiving. Your cloud clips are not affected.")
                .setPositiveButton("Leave") { _, _ -> LiveNet.signOut(this); LiveService.stop(this); Live.refresh(this); render() }
                .setNegativeButton("Cancel", null).show()
        }

        f.title("Phones")
        val me = Device.id(this)
        val phones = Live.phones.values.sortedWith(compareBy({ it.id != me }, { !it.camera }, { it.name }))
        if (phones.isEmpty()) f.note("Connecting…")
        for (p in phones) box.addView(phoneRow(p, p.id == me))
        f.note("Camera phones appear while they are monitoring or in standby. A phone you remove signs itself out when it next connects" +
            if (mode == LiveNet.MODE_CODE) "; to keep it out, also create a new code." else ".")

        f.title("Alerts on this phone")
        f.button("Alerts & notifications…") { startActivity(Intent(this, AlertSettingsActivity::class.java)) }
        f.toggle("Stay connected in the background", prefs.liveStay) { on ->
            prefs.liveStay = on
            if (on) LiveService.start(this) else LiveService.stop(this)
            AlertCheckJob.schedule(this)
            render()
        }
        f.note(if (prefs.liveStay) "Alerts arrive instantly any time: a silent notification keeps the connection open (uses a " +
                "little battery). For reliable alerts, also exempt MotionCam from battery optimisation."
            else "Off: alerts arrive instantly while MotionCam is open; otherwise " +
                (if (prefs.alertsPoll) "MotionCam checks about every 15 minutes (Alerts & notifications), so an alert can be up to ~15 minutes late. "
                else "they are shown when you next open MotionCam. ") +
                "Nothing is missed either way: cameras keep 7 days of events. Turn this on for instant alerts.")

        f.title("This phone as a camera")
        f.toggle("Let other phones see and control this phone while it is monitoring", prefs.liveCamera) { on ->
            if (on) withCameraPin { prefs.liveCamera = true; com.motioncam.CameraService.settingsChanged(this); render() }   // the PIN is always asked to turn this on
            else {
                prefs.liveCamera = false
                CameraLink.stop(this)
                Live.refresh(this); render()
            }
            render()
        }
        if (prefs.liveCamera) {
            f.row(f.chip("Change PIN") { setPin { render() } })
            if (CameraLink.shortPin(this)) f.note("Your PIN is shorter than 6 characters: change it for better protection.", Ui.ACCENT)
            f.toggle("PIN also needed for the live view and frames", prefs.livePinForView) { prefs.livePinForView = it }
            f.toggle("Standby: can be started from your other phones", prefs.liveStandby) { on ->
                prefs.liveStandby = on
                com.motioncam.CameraService.settingsChanged(this)
            }
            f.note("When monitoring is stopped, MotionCam stays connected with the camera off (a small notification shows " +
                "\"standby\"), so your other phones can start monitoring again. Turn it off from that notification or here. " +
                "Most reliable while the phone is charging.")
            f.note("The PIN protects the camera's controls, settings and pausing/stopping monitoring (and, if on, its live view). " +
                "It is never sent: requests are signed with it. After 5 wrong PINs remote control is locked for 10 minutes.")
        } else f.note("Viewer only: this phone sees the cameras and gets their alerts, but is never shown or controllable as a camera.")
    }

    private fun phoneRow(p: Live.Phone, isMe: Boolean): View {
        val ctx = this
        val r = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(ctx, 12), Ui.dp(ctx, 10), Ui.dp(ctx, 4), Ui.dp(ctx, 10))
            background = GradientDrawable().apply { setColor(0xFF1E2126.toInt()); cornerRadius = Ui.dp(ctx, 10).toFloat() }
        }
        val dot = Ui.label(ctx, "●", 14f, if (p.online) 0xFF4CAF50.toInt() else 0xFF6B7078.toInt())
        r.addView(dot)
        val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dp(ctx, 10), 0, 0, 0) }
        val roles = listOfNotNull(if (p.camera) "Camera" else null, if (p.viewer) "Viewer" else null).joinToString(" · ")
        texts.addView(Ui.label(ctx, (p.name.ifEmpty { "Phone" }) + (if (isMe) " (this phone)" else ""), 15f))
        val seen = if (p.online) "online" else "last seen " + (if (p.seen > 0) Fmt.t(prefs, p.seen - Live.serverOffset, seconds = false) else "—")
        texts.addView(Ui.label(ctx, listOf(roles, seen).filter { it.isNotEmpty() }.joinToString(" · "), 12f, Ui.GREY))
        Live.cams[p.id]?.status?.let { s ->
            val line = Ui.label(ctx, "", 12f, Ui.ACCENT)
            val parts = ArrayList<CharSequence>()
            parts += when { s.optString("mode") == "RECORDING" -> "● recording"; s.optBoolean("monitoring") -> "monitoring"; s.optBoolean("standby") -> "standby"; else -> "not monitoring" }
            if (s.optInt("battery", -1) >= 0) parts += com.motioncam.BatteryDrawable.text(line, s.optInt("battery"), s.optBoolean("charging"))
            parts += "%.0f °C".format(s.optDouble("tempC"))
            s.optLong("lastMotion").takeIf { it > 0 }?.let { parts += "motion " + Fmt.t(prefs, it, seconds = false) }
            line.text = android.text.SpannableStringBuilder().apply { parts.forEachIndexed { i, x -> if (i > 0) append(" · "); append(x) } }
            texts.addView(line)
            val mu = Alerts.mutedUntil(ctx, p.id)
            if (mu > 0) texts.addView(Ui.label(ctx, "Alerts muted" + if (mu == Long.MAX_VALUE) "" else " until " + Fmt.t(prefs, mu, seconds = false), 12f, Ui.GREY))
        }
        r.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        if (p.camera && !isMe) r.setOnClickListener { startActivity(Intent(ctx, RemoteCameraActivity::class.java).putExtra(Alerts.EXTRA_CAM, p.id)) }
        if (!isMe) {
            lateinit var more: View
            more = iconButton(ctx, IconDrawable.Kind.MORE, 40, 10) {
                val pm = PopupMenu(ctx, more)
                if (p.camera) {
                    pm.menu.add(0, 1, 0, "Open")
                    pm.menu.add(0, 2, 1, "Mute alerts for 1 hour")
                    pm.menu.add(0, 3, 2, "Mute alerts for 8 hours")
                    pm.menu.add(0, 4, 3, "Mute alerts until I turn them on")
                    if (Alerts.mutedUntil(ctx, p.id) > 0) pm.menu.add(0, 5, 4, "Unmute alerts")
                }
                pm.menu.add(0, 6, 5, "Remove from network")
                pm.setOnMenuItemClickListener {
                    when (it.itemId) {
                        1 -> startActivity(Intent(ctx, RemoteCameraActivity::class.java).putExtra(Alerts.EXTRA_CAM, p.id))
                        2 -> Alerts.mute(ctx, p.id, 60)
                        3 -> Alerts.mute(ctx, p.id, 8 * 60)
                        4 -> Alerts.mute(ctx, p.id, 0)
                        5 -> Alerts.unmute(ctx, p.id)
                        6 -> AlertDialog.Builder(ctx).setTitle("Remove ${p.name}?")
                            .setMessage("It signs itself out of the live connection when it next connects.")
                            .setPositiveButton("Remove") { _, _ -> Live.removePhone(ctx, p.id) }.setNegativeButton("Cancel", null).show()
                    }
                    render(); true
                }
                pm.show()
            }
            r.addView(more)
        }
        return LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(0, Ui.dp(ctx, 8), 0, 0); addView(r) }
    }

    // ------------------------------------------------------------------ network code

    private fun createCode() {
        val code = LiveNet.newCode()
        AlertDialog.Builder(this).setTitle("New network code")
            .setMessage("$code\n\nEnter this code on each of your phones (Join with a network code). Keep it private: anyone with it can see " +
                "and (without a PIN) control your cameras.")
            .setPositiveButton("Use this code") { _, _ -> join(code) }
            .setNeutralButton("Copy") { _, _ -> copy(code); join(code) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun askCode() {
        val input = EditText(this).apply { hint = "XXXXX-XXXXX-XXXXX-XXXXX"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS }
        AlertDialog.Builder(this).setTitle("Join with a network code").setView(input)
            .setPositiveButton("Join") { _, _ -> join(input.text.toString()) }.setNegativeButton("Cancel", null).show()
    }

    private fun join(code: String) {
        Toast.makeText(this, "Connecting…", Toast.LENGTH_SHORT).show()
        Thread {
            val err = LiveNet.joinWithCode(this, code)
            runOnUiThread { if (err != null) Toast.makeText(this, err, Toast.LENGTH_LONG).show() else { Live.refresh(this); render() } }
        }.start()
    }

    private fun showCode(code: String) {
        AlertDialog.Builder(this).setTitle("Network code").setMessage(code)
            .setPositiveButton("Copy") { _, _ -> copy(code) }
            .setNeutralButton("Share…") { _, _ -> startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, code), "Share code")) }
            .setNegativeButton("Close", null).show()
    }

    private fun copy(s: String) {
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("MotionCam network code", s))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }

    /** Asks for the camera PIN (twice); [done] only runs when a valid PIN was saved. */
    private fun setPin(done: () -> Unit) {
        val input = EditText(this).apply { hint = "PIN (at least 6 characters)"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        val input2 = EditText(this).apply { hint = "Repeat the PIN"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; val p = Ui.dp(this@LiveActivity, 20); setPadding(p, 0, p, 0); addView(input); addView(input2) }
        AlertDialog.Builder(this).setTitle("Camera PIN").setView(box)
            .setMessage("Other phones need this PIN to control this camera, change its settings or (if you keep that on) watch it. " +
                "Use at least 6 characters; letters and digits mixed are much harder to guess.")
            .setPositiveButton("Save") { _, _ ->
                val p = input.text.toString().trim()
                when {
                    p.length < 6 -> Toast.makeText(this, "At least 6 characters", Toast.LENGTH_SHORT).show()
                    p != input2.text.toString().trim() -> Toast.makeText(this, "The two PINs differ", Toast.LENGTH_SHORT).show()
                    else -> { CameraLink.setPin(this, p); done() }
                }
                render()
            }.setNegativeButton("Cancel") { _, _ -> render() }.show()
    }

    /**
     * Turning the camera role on always needs the PIN: set one (entered twice) if there is none, otherwise enter it
     * (with "Set a new PIN" for a forgotten one). Cancel / wrong PIN: the camera role stays off.
     */
    private fun withCameraPin(then: () -> Unit) {
        if (!CameraLink.hasPin(this)) { setPin(then); return }
        val input = EditText(this).apply { hint = "PIN"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        val box = LinearLayout(this).apply { val p = Ui.dp(this@LiveActivity, 20); setPadding(p, 0, p, 0); addView(input, LinearLayout.LayoutParams(-1, -2)) }
        AlertDialog.Builder(this).setTitle("Enter this camera's PIN").setView(box)
            .setMessage("Needed to let other phones see and control this phone.")
            .setPositiveButton("OK") { _, _ ->
                if (CameraLink.pinMatches(this, input.text.toString())) then()
                else { Toast.makeText(this, "Wrong PIN", Toast.LENGTH_SHORT).show(); render() }
            }
            .setNeutralButton("Set a new PIN") { _, _ -> setPin(then) }
            .setNegativeButton("Cancel") { _, _ -> render() }
            .setOnCancelListener { render() }
            .show()
    }

    /** First step when turning the live connection on: viewer only, or also a camera (which needs a PIN). */
    private fun chooseRole(then: () -> Unit) {
        val d = com.motioncam.OptionSheet.show(this, "How will this phone take part?", listOf(
            com.motioncam.Opt("Viewer only", "See your cameras and get their alerts. This phone is never watched or controlled.",
                com.motioncam.IconDrawable.Kind.SCREEN) { prefs.liveCamera = false; then() },
            com.motioncam.Opt("Camera and viewer", "While it is monitoring, your other phones can see and control it — protected by a PIN.",
                com.motioncam.IconDrawable.Kind.VIDEOCAM) { withCameraPin { prefs.liveCamera = true; then() } },
        ), subtitle = "You can change this later.")
        d.setOnDismissListener { render() }
    }

    private fun enable() {
        prefs.liveOn = true
        AlertCheckJob.schedule(this)
        if (prefs.liveStay) LiveService.start(this)
        Live.refresh(this)
        render()
    }
}
