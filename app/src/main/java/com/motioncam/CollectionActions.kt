package com.motioncam

import android.app.Activity
import android.app.AlertDialog
import com.motioncam.cloud.CloudSync
import com.motioncam.remote.RemoteRepo

/** "Save all to phone" (remote clips) and "Back up all" (phone clips), for selections and whole collections. */
object CollectionActions {
    private fun plural(n: Int) = "$n clip${if (n == 1) "" else "s"}"

    /** Downloads remote clips into this phone's Clips, with progress (copies are never uploaded to backup). */
    fun saveToPhone(a: Activity, list: List<Clip>, done: () -> Unit = {}) {
        if (list.isEmpty()) return
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        val tv = Ui.label(a, "Starting…", 14f).apply { val p = Ui.dp(a, 20); setPadding(p, p, p, 0) }
        val dlg = AlertDialog.Builder(a).setTitle("Saving ${plural(list.size)} to this phone")
            .setView(tv).setCancelable(false)
            .setNegativeButton("Cancel") { _, _ -> cancelled.set(true) }.show()
        Thread {
            val r = RemoteRepo.saveToPhone(a, list, { d, total, pct ->
                a.runOnUiThread { tv.text = if (d >= total) "Finishing…" else "Clip ${d + 1} of $total  ·  $pct%" }
            }, { cancelled.get() })
            a.runOnUiThread {
                try { dlg.dismiss() } catch (_: Exception) {}
                val parts = ArrayList<String>()
                parts += "Saved ${plural(r.saved)} to Clips on this phone."
                if (r.skipped > 0) parts += "${r.skipped} already on this phone (skipped)."
                if (r.failed > 0) parts += "${r.failed} failed" + (r.lastError?.let { ": $it" } ?: ".")
                if (cancelled.get()) parts += "Stopped."
                parts += "Saved copies are never uploaded to cloud backup and are not removed by automatic clean-up."
                if (!a.isFinishing) AlertDialog.Builder(a).setTitle("Save to phone").setMessage(parts.joinToString("\n\n")).setPositiveButton("OK", null).show()
                done()
            }
        }.start()
    }

    /** "Back up to…": choose which backup folders these phone clips are in (see [BackupSheet]). */
    fun backUpAll(a: Activity, list: List<Clip>, done: () -> Unit = {}) = BackupSheet.show(a, list, done)
}
