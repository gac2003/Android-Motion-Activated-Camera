package com.motioncam.cloud

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.widget.Toast
import com.motioncam.remote.RemoteSource
import com.motioncam.remote.RemoteSources

/** Shared "Protect in cloud" / "Remove cloud protection" actions (selections, collections, days, player). */
object CloudProtect {
    /** Where marks apply: the remote folder, or this phone's backup destinations. */
    fun spaces(ctx: Context, remote: RemoteSource?): List<String> =
        remote?.let { listOf(MarkSync.remoteKey(it.id)) } ?: MarkSync.localSpaces(ctx)

    /** Protection can be changed (cloud backup set up; not a read-only Dropbox viewer link). */
    fun canChange(ctx: Context, remote: RemoteSource?) = spaces(ctx, remote).isNotEmpty() && remote?.mode != RemoteSources.MODE_LINK

    private fun plural(n: Int) = "$n clip${if (n == 1) "" else "s"}"

    /** Protects [names] in the background. */
    fun protect(a: Activity, remote: RemoteSource?, names: List<String>, done: () -> Unit = {}) {
        if (names.isEmpty()) return
        val app = a.applicationContext
        Toast.makeText(a, "Protecting ${plural(names.size)} in the cloud…", Toast.LENGTH_SHORT).show()
        Thread {
            try { for (s in spaces(app, remote)) MarkSync.protect(app, s, names) } catch (_: Exception) {}
            a.runOnUiThread(done)
        }.start()
    }

    /** Asks, then removes the protection of [names] for everyone. */
    fun confirmRemove(a: Activity, remote: RemoteSource?, names: List<String>, done: () -> Unit = {}) {
        if (names.isEmpty()) return
        AlertDialog.Builder(a).setTitle("Remove cloud protection from ${plural(names.size)}?")
            .setMessage("This removes every phone's protection of these clips (also protections from other phones' collections). " +
                "They become subject to the camera phone's normal clean-up again and may be deleted from the cloud.\n\n" +
                "Clips stay in your collections; protecting them again later works any time.")
            .setPositiveButton("Remove protection") { _, _ ->
                val app = a.applicationContext
                Toast.makeText(a, "Removing protection…", Toast.LENGTH_SHORT).show()
                Thread {
                    val err = try { for (s in spaces(app, remote)) MarkSync.override(app, s, names); null } catch (e: Exception) { e.message ?: "failed" }
                    a.runOnUiThread {
                        Toast.makeText(a, if (err == null) "Protection removed from ${plural(names.size)}" else "Could not remove: $err", Toast.LENGTH_LONG).show()
                        done()
                    }
                }.start()
            }
            .setNegativeButton("Cancel", null).show()
    }
}
