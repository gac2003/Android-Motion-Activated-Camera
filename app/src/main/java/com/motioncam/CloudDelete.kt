package com.motioncam

import android.app.Activity
import android.app.AlertDialog
import com.motioncam.remote.RemoteRepo
import com.motioncam.remote.RemoteSource
import com.motioncam.remote.RemoteSources

/** "Delete from cloud" for remote-folder clips: same confirmation as deleting on the phone; protected clips are kept. */
object CloudDelete {
    fun canDelete(remote: RemoteSource?) = remote != null && remote.mode != RemoteSources.MODE_LINK

    fun confirm(a: Activity, remote: RemoteSource, clips: List<Clip>, done: () -> Unit = {}) {
        if (clips.isEmpty()) return
        val prot = RemoteRepo.protectedNames(a, remote)
        val keep = clips.count { it.name in prot }
        val del = clips.size - keep
        if (del == 0) {
            AlertDialog.Builder(a).setTitle("Nothing to delete")
                .setMessage("${if (clips.size == 1) "This clip is" else "These clips are"} protected (by hand or in a shared collection). " +
                    "Remove the protection first (⋮ → Remove cloud protection).")
                .setPositiveButton("OK", null).show()
            return
        }
        AlertDialog.Builder(a).setTitle("Delete $del clip${if (del == 1) "" else "s"}?")
            .setMessage("They are deleted from ${remote.name} in the cloud, for every phone." +
                (if (keep > 0) "\n\n$keep protected clip${if (keep == 1) " is" else "s are"} kept." else ""))
            .setPositiveButton("Delete") { _, _ -> run(a, remote, clips, done) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun run(a: Activity, remote: RemoteSource, clips: List<Clip>, done: () -> Unit) {
        val tv = Ui.label(a, "Deleting…", 14f).apply { val p = Ui.dp(a, 20); setPadding(p, p, p, 0) }
        val dlg = AlertDialog.Builder(a).setTitle("Deleting from the cloud").setView(tv).setCancelable(false).show()
        Thread {
            val r = try { RemoteRepo.deleteFromCloud(a, remote, clips) { i, n -> a.runOnUiThread { tv.text = "Clip ${minOf(i + 1, n)} of $n" } } }
                    catch (e: Exception) { RemoteRepo.DeleteResult(failed = clips.size, lastError = e.message) }
            a.runOnUiThread {
                try { dlg.dismiss() } catch (_: Exception) {}
                val parts = ArrayList<String>()
                parts += "Deleted ${r.deleted} clip${if (r.deleted == 1) "" else "s"} from the cloud."
                if (r.protectedKept > 0) parts += "${r.protectedKept} protected clip${if (r.protectedKept == 1) " was" else "s were"} kept."
                if (r.failed > 0) parts += "${r.failed} could not be deleted" + (r.lastError?.let { ": $it" } ?: ".")
                if (r.failed > 0 || r.protectedKept > 0) AlertDialog.Builder(a).setTitle("Delete from cloud").setMessage(parts.joinToString("\n\n"))
                    .setPositiveButton("OK", null).show()
                else android.widget.Toast.makeText(a, parts.first(), android.widget.Toast.LENGTH_SHORT).show()
                done()
            }
        }.start()
    }
}
