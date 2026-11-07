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
                "on the camera phone (Clips → Remote Folders → ＋ Add → Back up this phone to…), then add that folder here in Clips → Remote Folders.")
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
            else -> pick(list)
        }
    }

    /**
     * The clip is in several of this phone's folders: the same name is the same clip, so it simply plays from the
     * best one. Only asks when the copies differ (another size or length), naming what differs.
     */
    private fun pick(list: List<RemoteSource>) {
        val copies = list.mapNotNull { s -> RemoteRepo.cached(this, s).firstOrNull { it.name == name }?.let { s to it } }
        if (copies.isEmpty()) { waitFor(list[0]); return }
        val sizes = copies.map { it.second.size }.filter { it > 0 }.toSet()
        val lengths = copies.map { (it.second.durationMs + 500) / 1000 }.filter { it > 0 }.toSet()
        if (sizes.size <= 1 && lengths.size <= 1) { waitFor(copies[0].first); return }
        fun mb(b: Long) = String.format(java.util.Locale.getDefault(), "%.1f MB", b / 1048576.0)
        fun len(ms: Long) = (ms / 1000).let { "%d:%02d".format(it / 60, it % 60) }
        val opts = copies.map { (s, c) ->
            val detail = listOfNotNull(if (c.size > 0) mb(c.size) else null, if (c.durationMs > 0) len(c.durationMs) else null).joinToString(" · ")
            Opt(s.name, listOf(detail, s.describe).filter { it.isNotEmpty() }.joinToString(" · ")) { waitFor(s) }
        }
        val what = listOfNotNull(if (sizes.size > 1) "sizes" else null, if (lengths.size > 1) "lengths" else null).joinToString(" and ")
        OptionSheet.show(this, "These copies differ ($what): which one?", opts)
    }

    private fun camButtons() {
        f.button("Open the camera (live view)") { startActivity(Intent(this, RemoteLiveActivity::class.java).putExtra(Alerts.EXTRA_CAM, cam)); finish() }
    }

    /**
     * Plays the clip when it is in the folder. When it isn't, works out why before saying anything: deleted (by this
     * phone — it remembers what it deleted —, or it was in the folder before / the camera reported it uploaded) or not
     * uploaded yet (then it keeps checking until it arrives).
     */
    private fun waitFor(src: RemoteSource) {
        waitingSince = System.currentTimeMillis()
        f.box.removeAllViews()
        val status = f.line("Looking for the clip in ${src.name}…")
        val spinner = ProgressBar(this).apply { isIndeterminate = true }
        f.box.addView(spinner, LinearLayout.LayoutParams(-2, -2).apply { topMargin = Ui.dp(this@OpenClipActivity, 12) })
        var tries = 0
        var decided = false
        fun play() {
            startActivity(Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_REMOTE, src.id).putExtra(PlayerActivity.EXTRA_START_ID, RemoteRepo.idFor(src.id, name)))
            finish()
        }
        fun deleted(why: String) {
            f.box.removeAllViews()
            f.line("This clip was deleted", 16f, Ui.ACCENT)
            f.note("$name\n\n$why")
            f.note("The camera phone may still have its own copy (its Clips), unless it deletes clips after backing them up.")
            camButtons()
        }
        fun attempt() {
            if (stopped) return
            Thread {
                val found = try { RemoteRepo.refresh(this, src).any { it.name == name } } catch (e: Exception) { null }
                runOnUiThread {
                    if (stopped) return@runOnUiThread
                    tries++
                    if (found == true) { play(); return@runOnUiThread }
                    if (!decided && found == false) {
                        decided = true
                        // deleted by this phone?
                        RemoteRepo.deletedAt(this, src.id, name)?.let { t ->
                            deleted("You deleted it from ${src.name} on " + java.text.SimpleDateFormat("EEE d MMM, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(t)) + ".")
                            return@runOnUiThread
                        }
                        // in the folder before, or reported uploaded by the camera: deleted since (another phone, or clean-up)
                        if (RemoteRepo.seenBefore(this, src.id, name)) {
                            deleted("It was in ${src.name} before and has been deleted since — from another phone, or by the camera's clean-up rules (folder full or older than its keep time).")
                            return@runOnUiThread
                        }
                        wasUploaded { up ->
                            if (stopped) return@wasUploaded
                            if (up) deleted("The camera uploaded it to ${src.name}, and it has been deleted since — from another phone, or by the camera's clean-up rules.")
                            else { waitingUi(status, src); ui.postDelayed({ attempt() }, 6000) }
                        }
                        return@runOnUiThread
                    }
                    when {
                        System.currentTimeMillis() - waitingSince > WAIT_MS -> {
                            f.box.removeAllViews()
                            f.line("The clip hasn't been uploaded yet.")
                            f.note("The camera may be waiting for Wi-Fi or for charging (its upload rules), or has no internet. " +
                                "The clip stays on the camera phone; it appears in ${src.name} once uploaded.")
                            f.button("Keep waiting") { waitFor(src) }
                            camButtons()
                            return@runOnUiThread
                        }
                        found == null -> status.text = "Can't reach ${src.name} right now — trying again…"
                        decided -> status.text = "Waiting for the camera to upload the clip… (checked $tries×)"
                    }
                    ui.postDelayed({ attempt() }, 6000)
                }
            }.start()
        }
        attempt()
    }

    private fun waitingUi(status: android.widget.TextView, src: RemoteSource) {
        status.text = "Not uploaded yet — waiting for the camera to upload it…"
        f.note("$name — the camera uploads it after recording (when its upload rules allow, e.g. Wi-Fi only or while charging). " +
            "This screen opens it as soon as it arrives.")
        camButtons()
    }

    /** Did the camera report this clip uploaded? (its "clip uploaded" events; unknown counts as no) */
    private fun wasUploaded(done: (Boolean) -> Unit) {
        val ref = Live.ref(this, "cams/$cam/events")?.orderByChild("t")?.limitToLast(500) ?: run { done(false); return }
        var answered = false
        ref.addListenerForSingleValueEvent(object : com.google.firebase.database.ValueEventListener {
            override fun onDataChange(s: com.google.firebase.database.DataSnapshot) {
                if (answered) return
                answered = true
                val up = s.children.any { c ->
                    val d = LiveNet.open(this@OpenClipActivity, c.child("d").value as? String) ?: return@any false
                    try { JSONObject(d).let { (it.optString("type") == "clip_up" || (it.optString("type") == "clip" && (it.optJSONArray("up")?.length() ?: 0) > 0)) && it.optString("name") == name } } catch (_: Exception) { false }
                }
                done(up)
            }
            override fun onCancelled(e: com.google.firebase.database.DatabaseError) { if (!answered) { answered = true; done(false) } }
        })
        ui.postDelayed({ if (!answered) { answered = true; done(false) } }, 8000)
    }
}
