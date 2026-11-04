package com.motioncam

import android.content.Context
import com.motioncam.cloud.CloudConfig
import com.motioncam.cloud.CloudKind

/** What a settings reset must do besides clearing the values, so the app follows the new defaults right away. */
object ResetEffects {
    fun apply(ctx: Context, keys: List<String>) {
        val p = Prefs(ctx)
        if (keys.any { it in Prefs.CLOUD_KEYS }) {
            for (k in CloudKind.values()) CloudConfig(ctx, k).resetPolicies()
            com.motioncam.cloud.MarkSync.scheduleLocal(ctx)    // protected collections back to Favourites
            com.motioncam.cloud.CloudSync.kick(ctx)            // Wi-Fi / charging rules, un-paused queue
        }
        if (keys.any { it in Prefs.ALERT_KEYS }) ctx.getSharedPreferences("alerts_mute", Context.MODE_PRIVATE).edit().clear().apply()
        if (keys.any { it in Prefs.LIVE_KEYS }) {
            if (!p.liveOn || !p.liveStay) com.motioncam.live.LiveService.stop(ctx)
            if (!p.liveOn || !p.liveCamera) com.motioncam.live.CameraLink.stop(ctx)
            com.motioncam.live.Live.refresh(ctx)
        }
        if (keys.any { it in Prefs.REMOTE_KEYS }) CameraService.instance?.reloadServer()
    }
}
