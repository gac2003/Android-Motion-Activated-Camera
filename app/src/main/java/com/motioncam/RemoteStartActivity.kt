package com.motioncam

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.FrameLayout

/**
 * Remote start (a push from another phone, see [com.motioncam.live.Push]): shown for a few seconds, also over the lock
 * screen without unlocking it, because Android only lets a camera start from something on screen. Starts monitoring,
 * tells your other phones, and closes; the screen then turns off again as usual.
 */
class RemoteStartActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private var started = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        val who = intent.getStringExtra("by") ?: "another phone"
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(Ui.label(this@RemoteStartActivity, "Starting MotionCam\nrequested by $who", 16f, Ui.GREY).apply { gravity = Gravity.CENTER },
                FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        })
    }

    override fun onResume() {
        super.onResume()
        if (started) return
        started = true
        val who = intent.getStringExtra("by") ?: "another phone"
        try { CameraService.start(this) } catch (e: Exception) { Health.log(this, "Remote start failed: ${e.message}") }
        ui.postDelayed({
            if (CameraService.monitoring) {
                Health.log(this, "Monitoring started remotely by $who")
                try { com.motioncam.live.CameraLink.event(this, "monitor_on", "Started remotely by $who (MotionCam wasn't running)") } catch (_: Exception) {}
            } else Health.log(this, "Remote start by $who: monitoring didn't start")
            finish()
        }, 4000)
    }
}
