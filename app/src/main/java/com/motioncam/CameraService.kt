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

/** Foreground service that keeps the camera, motion detection and web server alive with the screen off. */
class CameraService : Service() {
    companion object {
        const val ACTION_STOP = "com.motioncam.STOP"
        private const val CHANNEL = "monitor"
        private const val NOTIF_ID = 1
        @Volatile var instance: CameraService? = null

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, CameraService::class.java))
        }
        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, CameraService::class.java).setAction(ACTION_STOP))
        }
    }

    var engine: CameraEngine? = null; private set
    private var web: WebServer? = null
    private var wake: PowerManager.WakeLock? = null
    private lateinit var prefs: Prefs
    private var lastNotifText = ""
    private val main = Handler(Looper.getMainLooper())
    private val statusListener: (Status) -> Unit = { s -> onStatus(s) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }
        if (engine != null) return START_NOT_STICKY
        prefs = Prefs(this)
        createChannel()

        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        if (prefs.audio && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        startForeground(NOTIF_ID, notification("Starting…"), type)
        instance = this

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "motioncam:monitor").also { it.acquire() }

        StatusBus.add(statusListener)
        engine = CameraEngine(this, prefs) { why -> main.post { shutdownWithMessage(why) } }.also { it.start() }
        reloadServer()
        return START_NOT_STICKY
    }

    /** Called by the UI after settings changed. */
    fun onSettingsChanged() {
        engine?.applyConfig()
        reloadServer()
    }

    private fun reloadServer() {
        web?.stop()
        web = null
        if (prefs.serverOn && prefs.password.isNotEmpty()) {
            web = WebServer(this, prefs).also { it.start(prefs.port) }
        }
    }

    private fun onStatus(s: Status) {
        val text = when (s.mode) {
            Mode.RECORDING -> "● Recording — ${s.format}"
            Mode.WATCHING -> "Watching for motion"
            else -> s.message.ifEmpty { s.mode.name }
        } + if (s.batteryPct >= 0) " · ${s.batteryPct}%" else ""
        if (text == lastNotifText) return
        lastNotifText = text
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, notification(text))
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Monitoring", NotificationManager.IMPORTANCE_LOW))
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, CameraService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
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

    private fun shutdown() {
        StatusBus.remove(statusListener)
        engine?.release()
        engine = null
        web?.stop()
        web = null
        try { wake?.release() } catch (_: Exception) {}
        wake = null
        instance = null
        StatusBus.publish(Status(mode = Mode.STOPPED, message = "Stopped"))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (engine != null) shutdown()
        super.onDestroy()
    }
}
