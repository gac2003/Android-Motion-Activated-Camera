package com.motioncam.live

import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper

/**
 * A live view that was left on by accident doesn't run for hours (camera battery and heat, data, the relay
 * allowance): after [WATCH_MIN] minutes it asks "Still watching?" and stops after a [COUNTDOWN_S]-second countdown
 * unless you tap Keep watching, which starts the next [WATCH_MIN] minutes. Leaving the screen stops the live view and
 * this check at once (the screens call [cancel]), so it never appears after you've left.
 */
class LiveTimeout(private val a: Activity, private val stop: () -> Unit) {
    companion object {
        const val WATCH_MIN = 10
        const val COUNTDOWN_S = 10
    }
    private val ui = Handler(Looper.getMainLooper())
    private var dialog: AlertDialog? = null
    private var left = COUNTDOWN_S

    private val ask = Runnable {
        // only on the live view you're looking at (leaving it cancels this; never on a closing screen)
        if (a.isFinishing || a.isDestroyed || !a.hasWindowFocus() && a.window?.decorView?.isShown != true) return@Runnable
        left = COUNTDOWN_S
        dialog = AlertDialog.Builder(a).setTitle("Still watching?")
            .setMessage(text())
            .setPositiveButton("Keep watching") { _, _ -> restart() }
            .setNegativeButton("Stop now") { _, _ -> end() }
            .setCancelable(false)
            .show()
        ui.postDelayed(tick, 1000)
    }

    private val tick: Runnable = object : Runnable {
        override fun run() {
            val d = dialog ?: return
            left--
            if (left <= 0) { end(); return }
            d.setMessage(text())
            ui.postDelayed(this, 1000)
        }
    }

    private fun text() = "Live view has been on for $WATCH_MIN minutes. It stops in $left s to save the camera's battery and your data."

    /** Live view started (or you kept watching): the next check in [WATCH_MIN] minutes. */
    fun restart() {
        cancel()
        ui.postDelayed(ask, WATCH_MIN * 60_000L)
    }

    /** Live view stopped (or the screen left): no check. */
    fun cancel() {
        ui.removeCallbacks(ask); ui.removeCallbacks(tick)
        try { dialog?.dismiss() } catch (_: Exception) {}
        dialog = null
    }

    private fun end() {
        cancel()
        stop()
    }
}
