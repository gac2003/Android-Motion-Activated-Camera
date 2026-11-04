package com.motioncam.live

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

/**
 * "Stay connected" (viewing phones, optional): keeps the live connection while MotionCam is closed so camera alerts
 * still arrive. Shows a silent, minimised notification (Android requires one); uses little battery because the
 * connection is idle between messages.
 */
class LiveService : Service() {
    companion object {
        @Volatile var running = false
        fun start(ctx: Context) { try { ctx.startForegroundService(Intent(ctx, LiveService::class.java)) } catch (_: Exception) {} }
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, LiveService::class.java)) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("live_service", "Stay connected (silent)", NotificationManager.IMPORTANCE_MIN))
        val open = PendingIntent.getActivity(this, 0, Intent(this, LiveActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "live_service").setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle("Connected to your cameras").setContentText("Alerts arrive while MotionCam is closed. Turn off in Settings → Live connection.")
            .setContentIntent(open).setOngoing(true).build()
        try { startForeground(2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) } catch (_: Exception) { stopSelf(); return START_NOT_STICKY }
        running = true
        Live.viewerActive = true
        Live.refresh(this)
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }
}
