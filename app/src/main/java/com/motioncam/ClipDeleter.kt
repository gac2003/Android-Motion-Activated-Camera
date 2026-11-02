package com.motioncam

import android.app.Activity
import android.provider.MediaStore

/**
 * Deletes clips. Clips recorded by an earlier install of the app are not "owned" by this install, so Android
 * asks the user to confirm with its own dialog (MediaStore.createDeleteRequest).
 * The calling activity must forward onActivityResult to [onResult].
 */
object ClipDeleter {
    private const val REQ = 4711
    private var pending: ((Boolean) -> Unit)? = null

    fun delete(a: Activity, clips: List<Clip>, done: (deleted: Boolean) -> Unit) {
        Thread {
            val failed = clips.filter { !ClipStore.delete(a, it) }
            a.runOnUiThread {
                if (failed.isEmpty()) { done(true); return@runOnUiThread }
                try {
                    val pi = MediaStore.createDeleteRequest(a.contentResolver, failed.map { it.uri })
                    pending = done
                    a.startIntentSenderForResult(pi.intentSender, REQ, null, 0, 0, 0)
                } catch (e: Exception) {
                    done(failed.size < clips.size)
                }
            }
        }.start()
    }

    fun onResult(requestCode: Int, resultCode: Int): Boolean {
        if (requestCode != REQ) return false
        pending?.invoke(resultCode == Activity.RESULT_OK)
        pending = null
        return true
    }
}
