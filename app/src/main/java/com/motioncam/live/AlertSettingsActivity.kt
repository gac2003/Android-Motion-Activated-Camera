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
    private var resumedOnce = false

    override fun onResume() {
        super.onResume()
        if (resumedOnce) render()   // back from Android's channel settings
        resumedOnce = true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = Prefs(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.BG) }
        root.addView(topBar(this, "Alerts & activity"))
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

    /** About how much activity history all cameras keep (from their per-day counts), or null while counting. */
    private var usage: Long? = null

    private fun loadUsage() {
        val ids = (Live.phones.keys + com.motioncam.cloud.Device.id(this)).toSet()
        val n = java.util.concurrent.atomic.AtomicLong()
        val left = java.util.concurrent.atomic.AtomicInteger(ids.size)
        for (id in ids) {
            val ref = Live.ref(this, "cams/$id/days")
            if (ref == null) { if (left.decrementAndGet() == 0) { usage = n.get(); render() }; continue }
            ref.addListenerForSingleValueEvent(object : com.google.firebase.database.ValueEventListener {
                override fun onDataChange(s: com.google.firebase.database.DataSnapshot) {
                    n.addAndGet(s.children.sumOf { (it.child("n").value as? Number)?.toLong() ?: 0L })
                    if (left.decrementAndGet() == 0) { usage = n.get(); runOnUiThread { if (!isFinishing) render() } }
                }
                override fun onCancelled(e: com.google.firebase.database.DatabaseError) { if (left.decrementAndGet() == 0) { usage = n.get() } }
            })
        }
    }

    private var keepLoaded = false

    /** How long camera activity is kept: one setting for the whole network. */
    private fun activitySection() {
        f.title("Activity")
        if (!NetJoin.joined(this)) { f.note("Join your phones' network (Settings › Your phones) to see and keep camera activity."); return }
        if (!keepLoaded) { keepLoaded = true; KeepActivity.load(this) { runOnUiThread { if (!isFinishing) render() } }; loadUsage() }
        val opts = KeepActivity.OPTIONS
        f.choice("Keep activity", opts.map { KeepActivity.label(it) }, opts.indexOf(KeepActivity.cached).coerceAtLeast(0)) { i ->
            KeepActivity.set(this, opts[i])
        }
        f.note("The same for all your phones. Older activity is removed by each camera once a day; clips are never affected.")
        val u = usage
        f.line(if (u == null) "Activity history: counting…" else "Activity history: about " + mb(u * 300) + " of the database's free 1 GB", 13f, 0xFFB0B4BA.toInt())
        f.note("If all history together nears that limit, the oldest activity is removed first (and Activity says so), so new alerts never stop.")
        f.button("Delete activity…", full = false) { startActivity(android.content.Intent(this, DeleteActivityActivity::class.java)) }
    }

    private fun mb(bytes: Long): String = when {
        bytes < 1_000_000 -> "%.1f MB".format(bytes / 1e6)
        bytes < 1_000_000_000 -> "%.0f MB".format(bytes / 1e6)
        else -> "%.2f GB".format(bytes / 1e9)
    }

    private fun render() {
        f.box.removeAllViews()
        activitySection()
        f.title("Alerts on this phone")
        f.toggle("Show camera alerts on this phone", p.alertsOn) { p.alertsOn = it; AlertCheckJob.schedule(this); render() }
        if (!p.alertsOn) return
        if (!p.liveStay) {
            f.toggle("Check for alerts every 15 minutes when MotionCam is closed", p.alertsPoll) { p.alertsPoll = it; AlertCheckJob.schedule(this); render() }
            f.note("Connects for a few seconds, then disconnects: very little battery. Android picks the exact moment, so an alert " +
                "can be up to ~15 minutes late. For instant alerts turn on Stay connected (Settings › Your phones). Anything missed is " +
                "always shown when you open MotionCam.")
        }
        f.choice("Where alerts appear", listOf("Phone notifications when MotionCam isn't on screen (while it is: a card in the app)",
            "Inside MotionCam only (a card on the open screen; nothing in the notification shade)",
            "Phone notifications always, plus a card while MotionCam is on screen",
            "Phone notifications only (never a card in the app)"),
            when { p.alertsInApp -> 1; p.alertsAlways && p.alertsNoCard -> 3; p.alertsAlways -> 2; else -> 0 }) {
            p.alertsInApp = it == 1; p.alertsAlways = it >= 2; p.alertsNoCard = it == 3
        }
        f.note("These settings decide what notifies this phone. Everything the cameras report is in Activity either way " +
            "(new entries are marked, and the Activity tab shows how many). Each camera's alerts are grouped, and every alert has " +
            "Mute 1 h / 8 h buttons. Mute a camera for longer in Settings › Your phones → ⋮.")
        f.button("See all activity", full = false) { com.motioncam.Nav.go(this, com.motioncam.Nav.Tab.ACTIVITY) }

        f.title("Motion")
        f.toggle("Motion detected", p.alertMotion) { p.alertMotion = it; render() }
        if (p.alertMotion) {
            // 15 s up to an hour, in steps that make sense
            val steps = listOf(1, 5, 10, 15, 30, 45, 60, 120, 180, 300, 600, 900, 1200, 1800, 2700, 3600)
            fun label(sec: Int) = if (sec < 60) "$sec s" else "${sec / 60} min"
            val cur = steps.indices.minByOrNull { Math.abs(steps[it] - p.alertMotionGapSec) } ?: 0
            f.slider("At most one alert per camera every", 0, steps.size - 1, cur, { label(steps[it]) }) { p.alertMotionGapSec = steps[it] }
        }
        if (p.alertMotion) f.note("More motion within that time updates the same alert silently (\"3 times since 1:32\"). " +
            "The camera counts motion with gaps under 5 s as one motion, so 1 s means an alert for every separate motion.")
        f.toggle("New clip saved (silent)", p.alertClip) { p.alertClip = it }
        f.toggle("Show a picture of the clip", p.alertPicture) { p.alertPicture = it }
        f.note("Once the clip is saved, its alert shows a small picture from it. Turn off to keep alerts to text, e.g. if " +
            "others can see your lock screen. (Activity still shows the pictures.)")

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
