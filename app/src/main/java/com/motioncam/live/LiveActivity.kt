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
 * Settings › Your phones: join the network of your phones (Google account or network code; joining is what connects
 * this phone), the phones on it (cameras with battery / temperature / last motion; mute, remove), alerts,
 * background mode, and this phone as a camera (control, PIN, standby).
 */
class LiveActivity : Activity() {
    private lateinit var f: Form
    private lateinit var prefs: Prefs
    private val refresh: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.BG) }
        root.addView(topBar(this, "Your phones"))
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; val p = Ui.dp(this@LiveActivity, 16); setPadding(p, 0, p, p * 2) }
        root.addView(ScrollView(this).apply { addView(box) }, LinearLayout.LayoutParams(-1, 0, 1f))
        Ui.capWidth(box)
        setContentView(root)
        f = Form(this, box)
    }

    override fun onResume() {
        super.onResume()
        Live.listeners += refresh
        Live.viewerActive = true
        Live.refresh(this)
        render()
    }
    override fun onPause() { Live.listeners -= refresh; super.onPause() }

    private fun render() {
        val box = f.box
        box.removeAllViews()
        if (!LiveConfig.configured) { f.note("Connecting your phones isn't set up in this copy of MotionCam (the one to download from the project page). " +
            "It needs your own build with your free Firebase project: see SETUP.md in the project. Everything on this phone works without it."); return }
        val mode = LiveNet.mode(this)
        // joining the network is what connects this phone: there is no separate on/off switch
        f.title("Your phones' network")
        if (mode == null || !LiveNet.ready(this)) {
            f.note("Connects your phones through the internet (Firebase), so it also works where phones can't reach each other " +
                "(captive Wi-Fi, isolated networks). Camera phones share their status, alerts and a live view; other phones can " +
                "control them. All your phones must join the same network: the same Google account (simplest), or a network code.")
            f.button("Sign in with Google") { NetJoin.signInGoogle(this) { render() } }
            f.button("Create a network code") { NetJoin.createCode(this) { render() } }
            f.button("Join with a network code") { NetJoin.askCode(this) { render() } }
            return
        }
        if (mode == LiveNet.MODE_GOOGLE) f.line("Google account: ${LiveNet.account(this) ?: "signed in"}")
        else {
            f.line("Network code: •••••-•••••-•••••-•••••")
            f.row(f.chip("Show / share code") { NetJoin.showCode(this, LiveNet.shownCode(this) ?: "") })
            f.note("Anyone with the code can join and see your cameras: share it only with your own phones. Everything in a " +
                "code network is encrypted with the code.")
        }
        if (!prefs.liveOn) {
            // turned off with the old on/off switch: signed in, but not connected on this phone
            f.note("This phone is signed in but not connected (it was turned off in an earlier version).", Ui.ACCENT)
            f.button("Connect this phone") { enable() }
        }
        f.button("Leave this network (sign out)", full = false) { NetJoin.leave(this) { render() } }
        if (!prefs.liveOn) return
        f.line(if (prefs.liveCamera) "This phone: camera and viewer" else "This phone: viewer only (it can't be watched or controlled)",
            13f, Ui.GREY)

        f.title("Phones")
        val me = Device.id(this)
        val phones = Live.phones.values.sortedWith(compareBy({ it.id != me }, { !it.camera }, { it.name }))
        if (phones.isEmpty()) f.note("Connecting…")
        for (p in phones) box.addView(phoneRow(p, p.id == me))
        f.note("Camera phones appear while they are monitoring or in standby. A phone you remove signs itself out when it next connects" +
            if (mode == LiveNet.MODE_CODE) "; to keep it out, also create a new code." else ".")

        f.title("Alerts on this phone")
        f.button("Alerts & activity…") { startActivity(Intent(this, AlertSettingsActivity::class.java)) }
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
                "Nothing is missed either way: cameras keep their activity. Turn this on for instant alerts.")

        if (Rtc.enabled) {
            f.title("Live view")
            f.toggle("Smooth live video", prefs.liveSmooth) { prefs.liveSmooth = it }
            f.note("Live view as real video (up to 15 frames a second), straight between the phones or through your Cloudflare " +
                "relay when the network doesn't allow that. Off: a picture every 2 seconds, as before. If video can't connect, " +
                "the pictures are used automatically.")
            // Cloudflare has no spending cap: MotionCam keeps count and stops using the relay at this limit
            val bar = f.slider("Monthly relay limit (all phones)", 10, 1000, prefs.relayCapGb, { "$it GB" }) { prefs.relayCapGb = it; Rtc.setCap(this, it) }
            val usageTv = f.note("Relay used this month: …")
            Rtc.usage(this) { used, capGb ->
                if (capGb != prefs.relayCapGb) { prefs.relayCapGb = capGb; bar.progress = capGb - 10 }
                usageTv.text = "Relay used this month: %.1f GB of your %d GB limit (Cloudflare: first 1,000 GB free, then \$0.05/GB). ".format(used / 1e9, capGb) +
                    "At the limit, live video only connects directly (free) until next month, otherwise pictures."
            }
        }

        f.title("This phone as a camera")
        f.toggle("Let other phones see and control this phone while it is monitoring", prefs.liveCamera) { on ->
            if (on) NetJoin.withCameraPin(this) { prefs.liveCamera = true; com.motioncam.CameraService.settingsChanged(this); render(); NetJoin.askRemoteStart(this) }   // the PIN is always asked to turn this on
            else {
                prefs.liveCamera = false
                CameraLink.stop(this)
                Live.refresh(this); render()
            }
            render()
        }
        if (prefs.liveCamera) {
            f.row(f.chip("Change PIN") { NetJoin.setPin(this) { render() } })
            if (CameraLink.shortPin(this)) f.note("Your PIN is shorter than 6 characters: change it for better protection.", Ui.ACCENT)
            f.toggle("PIN also needed for the live view and frames", prefs.livePinForView) { prefs.livePinForView = it }
            f.toggle("Card picture every 15 minutes", prefs.cardSnapshot) { prefs.cardSnapshot = it }
            f.note("Your phones' Home shows this camera's latest clip (its moment of most motion). With this on, also a fresh picture " +
                "every 15 minutes while monitoring. With the PIN needed for the live view, only phones that know the PIN see it.")
            f.toggle("Standby: can be started from your other phones", prefs.liveStandby) { on ->
                prefs.liveStandby = on
                com.motioncam.CameraService.settingsChanged(this)
            }
            f.note("When monitoring is stopped, MotionCam stays connected with the camera off (a small notification shows " +
                "\"standby\"), so your other phones can start monitoring again. Turn it off from that notification or here. " +
                "Most reliable while the phone is charging.")
            f.toggle("Remote start: your other phones can start MotionCam here, even when it's locked or closed", prefs.liveRemoteStart) { on ->
                prefs.liveRemoteStart = on
                if (on && !android.provider.Settings.canDrawOverlays(this)) askOverlay()
                Live.publishDevice(this, force = true); render()
            }
            if (prefs.liveRemoteStart) {
                val missing = listOfNotNull(
                    if (!android.provider.Settings.canDrawOverlays(this)) "allow \"Display over other apps\"" else null,
                    if (!Push.canReceive) "push alerts in your build (SETUP.md step 7)" else if (Push.token(this) == null) "push registration (reopen MotionCam)" else null,
                )
                if (missing.isNotEmpty()) {
                    f.note("Not ready yet: needs " + missing.joinToString(" and ") + ".", Ui.ACCENT)
                    if (!android.provider.Settings.canDrawOverlays(this)) f.row(f.chip("Allow display over other apps") { askOverlay() })
                }
            }
            f.note("A failsafe for when MotionCam isn't running on this phone (Android closed it, or it was shut off): a phone " +
                "that knows the PIN can wake it with a push and start monitoring. The screen lights up for a few seconds over " +
                "the lock screen (the phone stays locked), then turns off again.")
            f.note("The PIN protects the camera's controls, settings and pausing/stopping monitoring (and, if on, its live view). " +
                "It is never sent: requests are signed with it. After 5 wrong PINs remote control is locked for 10 minutes.")
        } else f.note("Viewer only: this phone sees the cameras and gets their alerts, but is never shown or controllable as a camera.")
    }

    /** Remote start opens MotionCam over the lock screen: Android's "Display over other apps" permission. */
    private fun askOverlay() {
        android.app.AlertDialog.Builder(this).setTitle("Allow display over other apps")
            .setMessage("Android only lets MotionCam open by itself (to start the camera when another phone asks) with this permission. " +
                "On the next screen, turn on MotionCam, then come back.")
            .setPositiveButton("Open settings") { _, _ ->
                try { startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName"))) }
                catch (_: Exception) { startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION)) }
            }
            .setNegativeButton("Cancel", null).show()
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

    private fun enable() {
        prefs.liveOn = true
        AlertCheckJob.schedule(this)
        if (prefs.liveStay) LiveService.start(this)
        Live.refresh(this)
        render()
    }
}
