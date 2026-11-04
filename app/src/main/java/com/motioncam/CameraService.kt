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

/** Foreground service: keeps the shared camera engine monitoring with the screen off, plus the web viewer. */
class CameraService : Service() {
    companion object {
        const val ACTION_STOP = "com.motioncam.STOP"
        private const val CHANNEL = "monitor"
        private const val NOTIF_ID = 1
        @Volatile var instance: CameraService? = null

        fun start(ctx: Context) { ctx.startForegroundService(Intent(ctx, CameraService::class.java)) }
        fun stop(ctx: Context) { ctx.startService(Intent(ctx, CameraService::class.java).setAction(ACTION_STOP)) }
    }

    private var engine: CameraEngine? = null
    private var web: WebServer? = null
    private var wake: PowerManager.WakeLock? = null
    private lateinit var prefs: Prefs
    private var lastNotifText = ""
    private val main = Handler(Looper.getMainLooper())
    private val statusListener: (Status) -> Unit = { s -> onStatus(s) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { shutdown(manual = true); return START_NOT_STICKY }
        if (engine != null) return START_NOT_STICKY
        prefs = Prefs(this)
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Monitoring", NotificationManager.IMPORTANCE_LOW))

        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        if (prefs.audio && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        startForeground(NOTIF_ID, notification("Starting…"), type)
        instance = this

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "motioncam:monitor").also { it.acquire() }

        StatusBus.add(statusListener)
        com.motioncam.cloud.CloudSync.kick(this)   // resume any pending cloud uploads
        val e = EngineHost.acquire(this, "service")
        e.onMonitoringStopped = { why -> main.post { com.motioncam.live.CameraLink.safetyStop(this, why); shutdownWithMessage(why) } }
        e.setMonitoring(true)
        engine = e
        com.motioncam.live.CameraLink.start(this)   // live connection (status, alerts, remote control), if turned on
        reloadServer()
        return START_NOT_STICKY
    }

    fun reloadServer() {
        web?.stop()
        web = null
        if (prefs.serverOn && prefs.password.isNotEmpty()) web = WebServer(this, prefs).also { it.start(prefs.port) }
    }

    private fun onStatus(s: Status) {
        // the phone shows its own battery; the useful extra here is when motion was last seen
        val lastMotion = if (s.lastMotionWall > 0) " · last motion " + Fmt.t(prefs, s.lastMotionWall, seconds = false) else ""
        val text = when (s.mode) {
            Mode.RECORDING -> "● Recording — ${s.format}"
            Mode.WATCHING -> "Watching for motion$lastMotion"
            else -> s.message.ifEmpty { s.mode.name } + lastMotion
        }
        if (text == lastNotifText || instance == null) return
        lastNotifText = text
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(text))
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, CameraService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("MotionCam")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    private fun shutdownWithMessage(why: String) {
        getSystemService(NotificationManager::class.java).notify(
            NOTIF_ID + 1,
            Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("MotionCam stopped").setContentText(why).build(),
        )
        shutdown()
    }

    /** [manual] = the user stopped monitoring (button or notification), not a safety stop. */
    private fun shutdown(manual: Boolean = false) {
        if (manual) com.motioncam.live.CameraLink.event(this, "monitor_off", "Monitoring stopped on the camera phone")
        com.motioncam.live.CameraLink.stop(this)
        StatusBus.remove(statusListener)
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
        instance = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (engine != null) shutdown()
        super.onDestroy()
    }
}
