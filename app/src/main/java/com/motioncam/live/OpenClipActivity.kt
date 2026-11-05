package com.motioncam.live

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import com.motioncam.Opt
import com.motioncam.OptionSheet
import com.motioncam.PlayerActivity
import com.motioncam.Ui
import com.motioncam.cloud.CloudKind
import com.motioncam.remote.RemoteFoldersActivity
import com.motioncam.remote.RemoteRepo
import com.motioncam.remote.RemoteSource
import com.motioncam.remote.RemoteSources
import com.motioncam.topBar
import org.json.JSONObject

/**
 * Opens the clip an alert is about. The clip lives on the camera phone and reaches this phone through the camera's
 * cloud backup, so this finds that backup among this phone's Remote folders, waits for the upload if it isn't there
 * yet, and plays it. Explains what to do when the folder isn't added here or the camera doesn't back up.
 */
class OpenClipActivity : Activity() {
    companion object {
        private const val EXTRA_CLIP = "clip"
        fun intent(ctx: Context, cam: String, clip: String): Intent =
            Intent(ctx, OpenClipActivity::class.java).putExtra(Alerts.EXTRA_CAM, cam).putExtra(EXTRA_CLIP, clip)
        fun open(ctx: Context, cam: String, clip: String) = ctx.startActivity(intent(ctx, cam, clip))
        private const val WAIT_MS = 3 * 60_000L
    }

    private lateinit var cam: String
    private lateinit var clip: JSONObject
    private lateinit var f: Form
    private val ui = Handler(Looper.getMainLooper())
    @Volatile private var stopped = false
    private var waitingSince = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cam = intent.getStringExtra(Alerts.EXTRA_CAM) ?: run { finish(); return }
        clip = try { JSONObject(intent.getStringExtra(EXTRA_CLIP) ?: "") } catch (_: Exception) { finish(); return }
        val camName = Live.phones[cam]?.name?.ifEmpty { null } ?: "the camera"
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.BG) }
        root.addView(topBar(this, "Clip from " + camName.replaceFirstChar { it.uppercase() }))
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; val p = Ui.dp(this@OpenClipActivity, 16); setPadding(p, 0, p, p * 2) }
        root.addView(ScrollView(this).apply { addView(box) }, LinearLayout.LayoutParams(-1, 0, 1f))
        Ui.capWidth(box)
        setContentView(root)
        f = Form(this, box)
        resolve()
    }

    override fun onDestroy() { stopped = true; ui.removeCallbacksAndMessages(null); super.onDestroy() }

    private val name get() = clip.optString("name")

    /** This phone's remote folders that are the camera's backup folders (same folder), best first. */
    private fun sources(): List<RemoteSource> {
        val folders = clip.optJSONArray("folders") ?: return emptyList()
        val keys = (0 until folders.length()).map { folders.getJSONObject(it) }
        val all = RemoteSources.all(this)
        val exact = all.filter { s -> keys.any { it.optString("f") == Live.folderKey(s.kind, s.folderId) } }
        if (exact.isNotEmpty()) return exact
        // a Dropbox viewer link names the folder differently: try folders of the same kind that already list this clip
        val kinds = keys.mapNotNull { CloudKind.of(it.optString("k")) }.toSet()
        return all.filter { it.kind in kinds && RemoteRepo.cached(this, it).any { c -> c.name == name } }
    }

    private fun resolve() {
        f.box.removeAllViews()
        val folders = clip.optJSONArray("folders")
        if (folders == null || folders.length() == 0) {
            f.line("This clip is only on the camera phone.")
            f.note("The camera doesn't back up its clips to the cloud, so other phones can't play them. Turn on Cloud backup " +
                "on the camera phone (Settings → Cloud backup), then add that folder here in Clips → Remote Folders.")
            camButtons(); return
        }
        val list = sources()
        when {
            list.isEmpty() -> {
                f.line("The camera's backup folder isn't added on this phone yet.")
                f.note("Add it once in Clips → Remote Folders (same Google / Dropbox account as the camera, or its Dropbox viewer " +
                    "link). After that, alerts open their clips directly.")
                f.button("Open Remote Folders") { startActivity(Intent(this, RemoteFoldersActivity::class.java)) }
                camButtons()
            }
            list.size == 1 -> waitFor(list[0])
            else -> OptionSheet.show(this, "Which folder?", list.map { s -> Opt(s.name, s.describe) { waitFor(s) } })
        }
    }

    private fun camButtons() {
        f.button("Open the camera (live view)") { startActivity(Intent(this, RemoteLiveActivity::class.java).putExtra(Alerts.EXTRA_CAM, cam)); finish() }
    }

    /** Lists the folder until the clip is there (the camera may still be uploading it), then plays it. */
    private fun waitFor(src: RemoteSource) {
        waitingSince = System.currentTimeMillis()
        f.box.removeAllViews()
        val status = f.line("Looking for the clip in ${src.name}…")
        f.box.addView(ProgressBar(this).apply { isIndeterminate = true }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = Ui.dp(this@OpenClipActivity, 12) })
        f.note("$name — the camera uploads it after recording (when its upload rules allow, e.g. Wi-Fi only or while charging).")
        camButtons()
        var tries = 0
        fun attempt() {
            if (stopped) return
            Thread {
                val found = try { RemoteRepo.refresh(this, src).any { it.name == name } } catch (e: Exception) { null }
                runOnUiThread {
                    if (stopped) return@runOnUiThread
                    tries++
                    when {
                        found == true -> {
                            startActivity(Intent(this, PlayerActivity::class.java)
                                .putExtra(PlayerActivity.EXTRA_REMOTE, src.id).putExtra(PlayerActivity.EXTRA_START_ID, RemoteRepo.idFor(src.id, name)))
                            finish()
                        }
                        System.currentTimeMillis() - waitingSince > WAIT_MS -> {
                            f.box.removeAllViews()
                            f.line("The clip hasn't been uploaded yet.")
                            f.note("The camera may be waiting for Wi-Fi or for charging (its Cloud backup rules), or has no internet. " +
                                "The clip stays on the camera phone; it appears in ${src.name} once uploaded.")
                            f.button("Keep waiting") { waitFor(src) }
                            camButtons()
                        }
                        else -> {
                            status.text = if (found == null) "Can't reach ${src.name} right now — trying again…"
                                else "Waiting for the camera to upload the clip… (checked $tries×)"
                            ui.postDelayed({ attempt() }, 6000)
                        }
                    }
                }
            }.start()
        }
        attempt()
    }
}
