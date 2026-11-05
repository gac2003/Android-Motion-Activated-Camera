package com.motioncam

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.widget.Toast

/**
 * "Show in file manager": opens the folder the clips are in (Movies/MotionCam) in the phone's own file manager —
 * Samsung's My Files on Samsung phones, otherwise the system Files app or any app that opens folders. Android has no
 * standard way to point at one file in a folder, so the clip's name is shown to look for (newest clips are last by name).
 */
object FileReveal {
    private const val REL = "Movies/MotionCam"

    fun show(a: Activity, clip: Clip?) {
        val abs = Environment.getExternalStorageDirectory().path + "/" + REL
        val tries = listOf(
            // Samsung My Files, straight into the folder
            Intent("samsung.myfiles.intent.action.LAUNCH_MY_FILES").putExtra("samsung.myfiles.intent.extra.START_PATH", abs),
            // the system Files app (Google's on most phones)
            Intent(Intent.ACTION_VIEW).setDataAndType(
                DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:$REL"),
                DocumentsContract.Document.MIME_TYPE_DIR).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            // other file managers that open folders
            Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("file://$abs"), "resource/folder"),
        )
        for (i in tries) {
            try {
                a.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                clip?.let { Toast.makeText(a, "Look for ${it.name}", Toast.LENGTH_LONG).show() }
                return
            } catch (_: ActivityNotFoundException) {
            } catch (_: Exception) {}   // e.g. Android refusing a file:// link (FileUriExposedException), a locked-down activity
        }
        AlertDialog.Builder(a).setTitle("Where the clips are")
            .setMessage("No file manager could be opened. Clips are in the phone's storage, in the folder $REL" +
                (clip?.let { "\n\nThis clip: ${it.name}" } ?: ""))
            .setPositiveButton("OK", null).show()
    }
}
