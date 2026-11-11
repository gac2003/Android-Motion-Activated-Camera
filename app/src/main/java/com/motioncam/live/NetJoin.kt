package com.motioncam.live

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import com.motioncam.Prefs
import com.motioncam.Ui

/**
 * Joining the network of your phones (Google account or network code), the camera role and its PIN: shared by Home's
 * first-run steps and Settings › Your phones. Joining the network is what connects a phone; there is no separate switch.
 */
object NetJoin {
    /** Signed in to a network and connected on this phone. */
    fun joined(ctx: Context) = LiveNet.mode(ctx) != null && LiveNet.ready(ctx) && Prefs(ctx).liveOn

    fun signInGoogle(a: Activity, done: () -> Unit) {
        LiveNet.signInGoogle(a) { err ->
            if (err != null) Toast.makeText(a, err, Toast.LENGTH_LONG).show() else afterJoin(a, done)
        }
    }

    fun createCode(a: Activity, done: () -> Unit) {
        val code = LiveNet.newCode()
        AlertDialog.Builder(a).setTitle("New network code")
            .setMessage("$code\n\nEnter this code on each of your phones (Join with a network code). Keep it private: anyone with it can see " +
                "and (without a PIN) control your cameras.")
            .setPositiveButton("Use this code") { _, _ -> join(a, code, done) }
            .setNeutralButton("Copy") { _, _ -> copy(a, code); join(a, code, done) }
            .setNegativeButton("Cancel", null).show()
    }

    fun askCode(a: Activity, done: () -> Unit) {
        val input = EditText(a).apply { hint = "XXXXX-XXXXX-XXXXX-XXXXX"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS }
        AlertDialog.Builder(a).setTitle("Join with a network code").setView(input)
            .setPositiveButton("Join") { _, _ -> join(a, input.text.toString(), done) }.setNegativeButton("Cancel", null).show()
    }

    /** "Create a code" or "Join with a code". */
    fun codeChoice(a: Activity, done: () -> Unit) {
        AlertDialog.Builder(a).setTitle("Network code")
            .setMessage("Use a network code instead of a Google account: create one on your first phone, then enter it on the others.")
            .setPositiveButton("Join with a code") { _, _ -> askCode(a, done) }
            .setNeutralButton("Create a new code") { _, _ -> createCode(a, done) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun join(a: Activity, code: String, done: () -> Unit) {
        Toast.makeText(a, "Connecting…", Toast.LENGTH_SHORT).show()
        Thread {
            val err = LiveNet.joinWithCode(a, code)
            a.runOnUiThread { if (err != null) Toast.makeText(a, err, Toast.LENGTH_LONG).show() else afterJoin(a, done) }
        }.start()
    }

    /** Signed in: connect, then ask whether this phone may also be a camera your other phones see. */
    private fun afterJoin(a: Activity, done: () -> Unit) {
        val prefs = Prefs(a)
        prefs.liveOn = true
        AlertCheckJob.schedule(a)
        if (prefs.liveStay) LiveService.start(a)
        Live.refresh(a)
        if (prefs.liveCamera) { done(); return }
        val d = com.motioncam.OptionSheet.show(a, "How will this phone take part?", listOf(
            com.motioncam.Opt("Viewer only", "See your cameras and get their alerts. This phone is never watched or controlled.",
                com.motioncam.IconDrawable.Kind.SCREEN) { prefs.liveCamera = false },
            com.motioncam.Opt("Camera and viewer", "While it is monitoring, your other phones can see and control it — protected by a PIN.",
                com.motioncam.IconDrawable.Kind.VIDEOCAM) { withCameraPin(a) { prefs.liveCamera = true; com.motioncam.CameraService.settingsChanged(a); askRemoteStart(a, done) } },
        ), subtitle = "You can change this later in Settings › Your phones.")
        d.setOnDismissListener { done() }
    }

    /** Leaves the network on this phone. */
    fun leave(a: Activity, done: () -> Unit) {
        AlertDialog.Builder(a).setTitle("Leave the network?").setMessage("This phone stops sending and receiving. Your clips are not affected.")
            .setPositiveButton("Leave") { _, _ ->
                Prefs(a).liveOn = false
                LiveService.stop(a)
                CameraLink.stop(a)
                LiveNet.signOut(a)
                Live.refresh(a)
                AlertCheckJob.schedule(a)
                done()
            }
            .setNegativeButton("Cancel", null).show()
    }

    fun showCode(a: Activity, code: String) {
        AlertDialog.Builder(a).setTitle("Network code").setMessage(code)
            .setPositiveButton("Copy") { _, _ -> copy(a, code) }
            .setNeutralButton("Share…") { _, _ -> a.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, code), "Share code")) }
            .setNegativeButton("Close", null).show()
    }

    private fun copy(a: Activity, s: String) {
        (a.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("MotionCam network code", s))
        Toast.makeText(a, "Copied", Toast.LENGTH_SHORT).show()
    }

    /** Asks for the camera PIN (twice); [done] only runs when a valid PIN was saved. */
    fun setPin(a: Activity, done: () -> Unit) {
        val input = EditText(a).apply { hint = "PIN (at least 6 characters)"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        val input2 = EditText(a).apply { hint = "Repeat the PIN"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; val p = Ui.dp(a, 20); setPadding(p, 0, p, 0); addView(input); addView(input2) }
        AlertDialog.Builder(a).setTitle("Camera PIN").setView(box)
            .setMessage("Other phones need this PIN to control this camera, change its settings or (if you keep that on) watch it. " +
                "Use at least 6 characters; letters and digits mixed are much harder to guess.")
            .setPositiveButton("Save") { _, _ ->
                val p = input.text.toString().trim()
                when {
                    p.length < 6 -> Toast.makeText(a, "At least 6 characters", Toast.LENGTH_SHORT).show()
                    p != input2.text.toString().trim() -> Toast.makeText(a, "The two PINs differ", Toast.LENGTH_SHORT).show()
                    else -> { CameraLink.setPin(a, p); done() }
                }
            }.setNegativeButton("Cancel", null).show()
    }

    /**
     * Turning the camera role on always needs the PIN: set one (entered twice) if there is none, otherwise enter it
     * (with "Set a new PIN" for a forgotten one). Cancel / wrong PIN: the camera role stays off.
     */
    /**
     * Part of setting a phone up as a camera: Remote start (on by default) needs Android's "Display over other apps".
     * Asked here once; Settings › Your phones can do it later.
     */
    fun askRemoteStart(a: Activity, then: () -> Unit = {}) {
        val p = Prefs(a)
        val sp = a.getSharedPreferences("asked", Activity.MODE_PRIVATE)
        if (!p.liveCamera || !p.liveRemoteStart || android.provider.Settings.canDrawOverlays(a) || sp.getBoolean("overlay", false)) { then(); return }
        sp.edit().putBoolean("overlay", true).apply()
        AlertDialog.Builder(a).setTitle("Let your other phones start this camera?")
            .setMessage("If MotionCam stops on this phone (Android closed it, or it was turned off), your other phones can start it " +
                "again, even while it's locked. Android needs one permission for that: \"Display over other apps\". On the next " +
                "screen, turn it on for MotionCam, then come back.")
            .setPositiveButton("Allow") { _, _ ->
                try { a.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:${a.packageName}"))) }
                catch (_: Exception) { try { a.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION)) } catch (_: Exception) {} }
                then()
            }
            .setNegativeButton("Not now") { _, _ -> then() }
            .setCancelable(false).show()
    }

    fun withCameraPin(a: Activity, then: () -> Unit) {
        if (!CameraLink.hasPin(a)) { setPin(a, then); return }
        val input = EditText(a).apply { hint = "PIN"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        val box = LinearLayout(a).apply { val p = Ui.dp(a, 20); setPadding(p, 0, p, 0); addView(input, LinearLayout.LayoutParams(-1, -2)) }
        AlertDialog.Builder(a).setTitle("Enter this camera's PIN").setView(box)
            .setMessage("Needed to let other phones see and control this phone.")
            .setPositiveButton("OK") { _, _ ->
                if (CameraLink.pinMatches(a, input.text.toString())) then()
                else Toast.makeText(a, "Wrong PIN", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Set a new PIN") { _, _ -> setPin(a, then) }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
