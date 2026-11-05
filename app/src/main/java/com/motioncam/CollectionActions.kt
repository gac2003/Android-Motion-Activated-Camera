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

    /** Queues phone clips for every enabled backup destination (clips already backed up are skipped). */
    fun backUpAll(a: Activity, list: List<Clip>, done: () -> Unit = {}) {
        if (list.isEmpty()) return
        if (CloudSync.enabledKinds(a).isEmpty()) {
            AlertDialog.Builder(a).setTitle("Cloud backup is off")
                .setMessage("Set up where clips are backed up first (Google Drive, Dropbox or your own FTP/SFTP server).")
                .setPositiveButton("Set up backup") { _, _ ->
                    a.startActivity(android.content.Intent(a, com.motioncam.remote.RemoteFoldersActivity::class.java)
                        .putExtra(com.motioncam.remote.RemoteFoldersActivity.EXTRA_BACKUP, true))
                }
                .setNegativeButton("Cancel", null).show()
            return
        }
        Thread {
            val r = CloudSync.enqueueClips(a, list.map { it.id })
            a.runOnUiThread {
                val parts = ArrayList<String>()
                parts += if (r.queued > 0) "${plural(r.queued)} queued for upload (Clips → Remote folders → Upload queue)."
                         else "Nothing new to upload."
                if (r.already > 0) parts += "${plural(r.already)} already backed up or waiting."
                if (r.savedCopies > 0) parts += "${plural(r.savedCopies)} skipped: copies saved from a remote folder are never uploaded."
                done()
                AlertDialog.Builder(a).setTitle("Back up").setMessage(parts.joinToString("\n\n")).setPositiveButton("OK", null)
                    .setNeutralButton("Upload queue") { _, _ -> a.startActivity(android.content.Intent(a, com.motioncam.cloud.UploadQueueActivity::class.java)) }.show()
            }
        }.start()
    }
}
