package com.motioncam

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager

/**
 * Foreground service: keeps the shared camera engine monitoring with the screen off, plus the web viewer.
 *
 * Standby (camera phones on the live connection, optional): when monitoring stops, the service stays with the camera
 * closed, so the phone stays reachable and another phone can start monitoring again. Android only lets an app use the
 * camera from the background if its camera service was started while the app was on screen, which is why standby
 * keeps this service (started from the app) instead of starting it later from a remote request.
 */
class CameraService : Service() {
    companion object {
        const val ACTION_STOP = "com.motioncam.STOP"          // stop monitoring (to standby when allowed)
        const val ACTION_EXIT = "com.motioncam.EXIT"          // stop completely, standby included
        const val ACTION_STANDBY = "com.motioncam.STANDBY"    // start in standby (camera closed)
        private const val CHANNEL = "monitor"
        private const val NOTIF_ID = 1
        @Volatile var instance: CameraService? = null

        fun start(ctx: Context) { ctx.startForegroundService(Intent(ctx, CameraService::class.java)) }
        fun stop(ctx: Context) { ctx.startService(Intent(ctx, CameraService::class.java).setAction(ACTION_STOP)) }
        fun exit(ctx: Context) { if (instance != null) ctx.startService(Intent(ctx, CameraService::class.java).setAction(ACTION_EXIT)) }

        /** Monitoring right now (not just in standby). */
        val monitoring: Boolean get() = instance?.engine != null
        /** In standby: reachable by other phones, camera closed. */
        val inStandby: Boolean get() = instance.let { it != null && it.engine == null }

        /** A phone only counts as a camera for standby if it monitored in the last week. */
        private const val STANDBY_DAYS = 7

        /**
         * Standby is possible: camera on the live connection with a PIN, the user hasn't turned standby off, and this
         * phone was actually used as a camera in the last week. (A phone you mostly watch your cameras from never sits
         * in standby, with its always-on connection, just because its camera role is on.)
         */
        fun standbyAllowed(ctx: Context): Boolean {
            val p = Prefs(ctx)
            return p.liveStandby && p.liveCamera && com.motioncam.live.Live.enabled(ctx) && com.motioncam.live.CameraLink.hasPin(ctx) &&
                System.currentTimeMillis() - p.lastMonitoredAt < STANDBY_DAYS * 86_400_000L &&
                ctx.checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        }

        /** Called while MotionCam is on screen: enters standby if allowed and nothing runs yet. */
        fun ensureStandby(ctx: Context) {
            if (instance != null || !standbyAllowed(ctx)) return
            try { ctx.startForegroundService(Intent(ctx, CameraService::class.java).setAction(ACTION_STANDBY)) } catch (_: Exception) {}
        }

        /** Settings changed: leave standby if it is no longer allowed, or enter it if it now is (app on screen). */
        fun settingsChanged(ctx: Context) {
            if (instance != null) com.motioncam.live.CameraLink.start(ctx)   // camera role / live turned on while running
            if (inStandby && !standbyAllowed(ctx)) exit(ctx) else ensureStandby(ctx)
        }
    }

    private var engine: CameraEngine? = null
    private var web: WebServer? = null
    private var wake: PowerManager.WakeLock? = null
    private lateinit var prefs: Prefs
    private var lastNotifText = ""
    private var foreground = false
    private var exiting = false
    private val main = Handler(Looper.getMainLooper())
    private val statusListener: (Status) -> Unit = { s -> onStatus(s) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        prefs = Prefs(this)
        when (intent?.action) {
            ACTION_EXIT -> { exit(manual = engine != null); return START_NOT_STICKY }
            ACTION_STOP -> {
                if (!foreground) { stopSelf(); return START_NOT_STICKY }
                if (engine == null) exit(manual = false) else stopMonitoring(manual = true)
                return START_NOT_STICKY
            }
        }
        if (!goForeground()) return START_NOT_STICKY
        if (intent?.action == ACTION_STANDBY) { if (engine == null) showStandby() } else startMonitoring()
        return START_NOT_STICKY
    }

    private fun goForeground(): Boolean {
        if (foreground) return true
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Monitoring", NotificationManager.IMPORTANCE_LOW))
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        // the microphone too when allowed, so a clip with sound can start later (also from standby)
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        try { startForeground(NOTIF_ID, notification("Starting…", standby = false), type) } catch (e: Exception) { stopSelf(); return false }
        foreground = true
        instance = this
        StatusBus.add(statusListener)
        com.motioncam.live.CameraLink.start(this)   // live connection (status, alerts, remote control), if turned on
        return true
    }

    /** Starts monitoring (from the app, the notification, or another phone while in standby). */
    fun startMonitoring() {
        if (engine != null || !foreground) return
        prefs.lastMonitoredAt = System.currentTimeMillis()
        main.removeCallbacks(standbyCheck); standbyChecking = false
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "motioncam:monitor").also { it.acquire() }
        com.motioncam.cloud.CloudSync.kick(this)   // resume any pending cloud uploads
        val e = EngineHost.acquire(this, "service")
        e.onMonitoringStopped = { why -> main.post { com.motioncam.live.CameraLink.safetyStop(this, why); shutdownWithMessage(why) } }
        e.setMonitoring(true)
        engine = e
        lastNotifText = ""
        onStatus(StatusBus.status)
        reloadServer()
        com.motioncam.live.CameraLink.publishStatus(this)
    }

    fun reloadServer() {
        web?.stop()
        web = null
        if (engine != null && prefs.serverOn && prefs.password.isNotEmpty()) web = WebServer(this, prefs).also { it.start(prefs.port) }
    }

    private fun onStatus(s: Status) {
        if (instance == null) return
        if (engine == null) { showStandby(); return }
        // the phone shows its own battery; the useful extra here is when motion was last seen
        val lastMotion = if (s.lastMotionWall > 0) " · last motion " + Fmt.t(prefs, s.lastMotionWall, seconds = false) else ""
        val text = when (s.mode) {
            Mode.RECORDING -> "● Recording — ${s.format}"
            Mode.WATCHING -> "Watching for motion$lastMotion"
            else -> s.message.ifEmpty { s.mode.name } + lastMotion
        }
        if (text == lastNotifText) return
        lastNotifText = text
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(text, standby = false))
    }

    /** In standby: checked every hour, it ends by itself once this phone hasn't been a camera for a week. */
    private val standbyCheck: Runnable = object : Runnable {
        override fun run() {
            if (instance == null || engine != null) { standbyChecking = false; return }
            if (!standbyAllowed(this@CameraService)) { exit(this@CameraService); return }
            main.postDelayed(this, 3_600_000L)
        }
    }

    private var standbyChecking = false

    private fun showStandby() {
        if (!standbyChecking) { standbyChecking = true; main.postDelayed(standbyCheck, 3_600_000L) }
        val text = "Standby · your other phones can start monitoring"
        if (text == lastNotifText) return
        lastNotifText = text
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(text, standby = true))
    }

    private fun notification(text: String, standby: Boolean): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle(if (standby) "MotionCam · standby" else "MotionCam")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
        if (standby) {
            val start = PendingIntent.getService(this, 2, Intent(this, CameraService::class.java), PendingIntent.FLAG_IMMUTABLE)
            val exit = PendingIntent.getService(this, 3, Intent(this, CameraService::class.java).setAction(ACTION_EXIT), PendingIntent.FLAG_IMMUTABLE)
            b.addAction(Notification.Action.Builder(null, "Start monitoring", start).build())
            b.addAction(Notification.Action.Builder(null, "Turn off", exit).build())
        } else {
            val stop = PendingIntent.getService(this, 1, Intent(this, CameraService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
            b.addAction(Notification.Action.Builder(null, "Stop", stop).build())
        }
        return b.build()
    }

    private fun shutdownWithMessage(why: String) {
        getSystemService(NotificationManager::class.java).notify(
            NOTIF_ID + 1,
            Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("MotionCam stopped").setContentText(why).build(),
        )
        stopMonitoring(manual = false)
    }

    /**
     * Stops monitoring; stays in standby when allowed, otherwise stops the service.
     * [manual] = the user stopped monitoring (button, notification or another phone), not a safety stop.
     */
    fun stopMonitoring(manual: Boolean) {
        if (!standbyAllowed(this)) { exit(manual); return }
        if (manual && engine != null) com.motioncam.live.CameraLink.event(this, "monitor_off", "Stopped on the camera phone · standby (Start monitoring from your other phones turns it back on)")
        releaseEngine(manual)
        lastNotifText = ""
        showStandby()
        main.postDelayed({ com.motioncam.live.CameraLink.publishStatus(this) }, 300)
    }

    private fun releaseEngine(manual: Boolean) {
        engine?.let {
            it.onMonitoringStopped = null
            it.setMonitoring(false, manual)
            EngineHost.release("service")
        }
        engine = null
        web?.stop()
        web = null
        try { wake?.release() } catch (_: Exception) {}
        wake = null
    }

    /** Stops everything, standby included. */
    private fun exit(manual: Boolean = false) {
        if (exiting) return
        exiting = true
        if (manual && engine != null) com.motioncam.live.CameraLink.event(this, "monitor_off", "Stopped on the camera phone · offline until started there")
        releaseEngine(manual)
        com.motioncam.live.CameraLink.stop(this)
        StatusBus.remove(statusListener)
        instance = null
        foreground = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (instance === this) exit()
        super.onDestroy()
    }
}
