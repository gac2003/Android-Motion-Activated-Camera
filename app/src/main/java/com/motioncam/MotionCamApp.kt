package com.motioncam

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.motioncam.live.Live
import com.motioncam.live.LiveService

/** Knows whether any MotionCam screen is open: the live connection (viewer side) only runs then, or in "Stay connected". */
class MotionCamApp : Application() {
    private var started = 0
    private val ui = Handler(Looper.getMainLooper())
    private val idle = Runnable {
        if (started == 0 && !LiveService.running) { Live.viewerActive = false; Live.refresh(this) }
    }

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(a: Activity) {
                started++
                ui.removeCallbacks(idle)
                if (started == 1) {
                    Live.viewerActive = true
                    try { Live.refresh(this@MotionCamApp) } catch (_: Exception) {}
                    if (Prefs(a).liveOn && Prefs(a).liveStay) LiveService.start(a)
                }
            }
            override fun onActivityStopped(a: Activity) {
                started = (started - 1).coerceAtLeast(0)
                if (started == 0) ui.postDelayed(idle, 20_000)   // brief app switches keep the connection
            }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityResumed(a: Activity) { com.motioncam.live.AlertInbox.front = java.lang.ref.WeakReference(a) }
            override fun onActivityPaused(a: Activity) { if (com.motioncam.live.AlertInbox.front?.get() === a) com.motioncam.live.AlertInbox.front = null }
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }
}
