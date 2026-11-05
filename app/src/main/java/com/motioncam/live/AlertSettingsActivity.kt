package com.motioncam.live

import android.app.Activity
import android.app.TimePickerDialog
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.ScrollView
import com.motioncam.Prefs
import com.motioncam.Ui
import com.motioncam.topBar

/** Which camera alerts this phone shows, and how (quiet hours, per-type sound via Android's channel settings). */
class AlertSettingsActivity : Activity() {
    private lateinit var f: Form
    private lateinit var p: Prefs
    private var inboxBtn: android.widget.TextView? = null
    private var resumedOnce = false
    private val inboxChanged: () -> Unit = { updateInbox() }

    override fun onResume() {
        super.onResume()
        AlertInbox.listeners += inboxChanged
        if (resumedOnce) render() else updateInbox()   // back from the Alerts list or Android's channel settings
        resumedOnce = true
    }

    override fun onPause() { AlertInbox.listeners -= inboxChanged; super.onPause() }

    private fun updateInbox() { inboxBtn?.text = "Alerts list (${AlertInbox.unread(this)} unread)" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = Prefs(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.BG) }
        root.addView(topBar(this, "Alerts & notifications"))
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; val pd = Ui.dp(this@AlertSettingsActivity, 16); setPadding(pd, 0, pd, pd * 2) }
        root.addView(ScrollView(this).apply { addView(box) }, LinearLayout.LayoutParams(-1, 0, 1f))
        Ui.capWidth(box)
        setContentView(root)
        f = Form(this, box)
        Alerts.channels(this)
        render()
    }

    private fun hm(m: Int) = com.motioncam.Fmt.t(p, java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, m / 60); set(java.util.Calendar.MINUTE, m % 60) }.timeInMillis, seconds = false)

    private fun render() {
        f.box.removeAllViews()
        inboxBtn = null
        f.toggle("Show camera alerts on this phone", p.alertsOn) { p.alertsOn = it; render() }
        if (!p.alertsOn) return
        f.choice("Where alerts appear", listOf("Phone notifications (also when MotionCam is closed, with Stay connected)",
            "Inside MotionCam only (a banner on the open screen + the Alerts list; nothing in the notification shade)"),
            if (p.alertsInApp) 1 else 0) { p.alertsInApp = it == 1 }
        inboxBtn = f.button("Alerts list (${AlertInbox.unread(this)} unread)") { startActivity(android.content.Intent(this, AlertsInboxActivity::class.java)) }
        f.note("Alerts come from the camera phones in your live connection. Each camera's alerts are grouped, and every alert " +
            "has Mute 1 h / 8 h buttons. Mute a camera for longer in Live connection → ⋮.")

        f.title("Motion")
        f.toggle("Motion detected", p.alertMotion) { p.alertMotion = it; render() }
        if (p.alertMotion) f.slider("At most one alert per camera every", 1, 60, p.alertMotionGapMin, { "$it min" }) { p.alertMotionGapMin = it }
        if (p.alertMotion) f.note("More motion within that time updates the same alert silently (\"3 times since 1:32\").")
        f.toggle("New clip saved (silent)", p.alertClip) { p.alertClip = it }

        f.title("Camera health")
        f.toggle("Battery low", p.alertBattery) { p.alertBattery = it; render() }
        if (p.alertBattery) f.slider("Alert below", 5, 50, p.alertBatteryPct, { "$it %" }) { p.alertBatteryPct = it }
        f.toggle("Charger unplugged", p.alertCharger) { p.alertCharger = it }
        f.toggle("Too hot (camera paused)", p.alertHot) { p.alertHot = it }
        f.toggle("Monitoring stopped (by the phone or someone else)", p.alertMonitoring) { p.alertMonitoring = it }

        f.title("Connection")
        f.toggle("Camera offline", p.alertOffline) { p.alertOffline = it; render() }
        if (p.alertOffline) f.slider("Only after it stayed offline for", 1, 60, p.alertOfflineMin, { "$it min" }) { p.alertOfflineMin = it }
        if (p.alertOffline) f.note("Short drops (Wi-Fi switching, a reboot) don't alert. Cameras stopped on purpose don't count as offline.")
        f.toggle("Back online (replaces the offline alert, silent)", p.alertBackOnline) { p.alertBackOnline = it }
        f.toggle("Collection sync needs a decision (silent)", p.alertSync) { p.alertSync = it }

        f.title("Quiet hours")
        f.toggle("Quiet hours", p.quietOn) { p.quietOn = it; render() }
        if (p.quietOn) {
            f.row(f.chip("From ${hm(p.quietFrom)}") {
                TimePickerDialog(this, { _, h, m -> p.quietFrom = h * 60 + m; render() }, p.quietFrom / 60, p.quietFrom % 60, p.use24h).show()
            }, f.chip("To ${hm(p.quietTo)}") {
                TimePickerDialog(this, { _, h, m -> p.quietTo = h * 60 + m; render() }, p.quietTo / 60, p.quietTo % 60, p.use24h).show()
            })
            f.choice("During quiet hours", listOf("Alerts arrive silently", "Only \"camera offline\" alerts (silently)", "No alerts"), p.quietMode) { p.quietMode = it }
        }

        f.title("Sound, vibration, pop-up")
        f.note("Set these per alert type in Android's settings (e.g. motion with sound, battery silent):")
        for ((ch, label) in listOf(Alerts.CH_MOTION to "Motion", Alerts.CH_STATUS to "Battery, heat, monitoring", Alerts.CH_CONN to "Offline / online",
            Alerts.CH_CLIP to "New clips", Alerts.CH_SYNC to "Sync", Alerts.CH_QUIET to "Quiet hours")) {
            f.button("$label…") {
                startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName).putExtra(Settings.EXTRA_CHANNEL_ID, ch))
            }
        }
        f.button("Reset alert settings") { p.reset(Prefs.ALERT_KEYS); com.motioncam.ResetEffects.apply(this, Prefs.ALERT_KEYS); render() }
    }
}
