package com.motioncam.live

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import com.motioncam.Fmt
import com.motioncam.Prefs
import com.motioncam.Ui
import com.motioncam.cloud.Device
import com.motioncam.cloud.SecretStore
import com.motioncam.topBar
import org.json.JSONObject
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * A camera phone seen from another phone: low-rate live view (only while watching), high-quality frame on request,
 * status (monitoring, battery, temperature, last motion…), the camera controls and settings, and its Activity log.
 */
class RemoteCameraActivity : Activity() {
    private lateinit var cam: String
    private lateinit var prefs: Prefs
    private lateinit var image: ImageView
    private lateinit var imageLabel: TextView
    /** Under the picture, in colour: why no new picture arrives (the last one stays on screen as it is). */
    private lateinit var liveNote: TextView
    private lateinit var liveBtn: TextView
    private lateinit var f: Form
    private val ui = Handler(Looper.getMainLooper())

    // ------------------------------------------------------------------ recent activity (under the status)

    private var recent: List<ActivityEvent> = emptyList()
    private var recentLoaded = false
    private var recentNewSince = Long.MAX_VALUE
    private var recentBox: LinearLayout? = null
    private var recentL: com.google.firebase.database.ValueEventListener? = null

    /** The camera's events, followed live (the last 20, enough for the latest 3 entries once paired). */
    private fun followRecent() {
        recentL = object : com.google.firebase.database.ValueEventListener {
            override fun onDataChange(s: com.google.firebase.database.DataSnapshot) {
                recent = s.children.mapNotNull { e ->
                    val t = (e.child("t").value as? Number)?.toLong() ?: return@mapNotNull null
                    val d = LiveNet.open(this@RemoteCameraActivity, e.child("d").value as? String) ?: return@mapNotNull null
                    try { ActivityEvent(cam, t - Live.serverOffset, JSONObject(d)) } catch (_: Exception) { null }
                }
                recentLoaded = true
                fillRecent()
            }
            override fun onCancelled(e: com.google.firebase.database.DatabaseError) {}
        }.also { Live.ref(this, "cams/$cam/events")?.orderByChild("t")?.limitToLast(20)?.addValueEventListener(it) }
    }

    private fun unfollowRecent() { recentL?.let { Live.ref(this, "cams/$cam/events")?.removeEventListener(it) }; recentL = null }

    private fun fillRecent() {
        val box = recentBox ?: return
        if (isFinishing) return
        box.removeAllViews()
        val list = ActivityTypes.pair(recent.filter { it.type != "clip_up" && it.type != "remote_taken" }).take(3)
        if (list.isEmpty()) {
            box.addView(Ui.label(this, if (recentLoaded) "No activity yet." else "Loading…", 13f, Ui.GREY).apply {
                setPadding(0, Ui.dp(this@RemoteCameraActivity, 4), 0, Ui.dp(this@RemoteCameraActivity, 4))
            })
            return
        }
        val p = Prefs(this)
        for (e in list) box.addView(ActivityLogActivity.eventRow(this, p, e, showCam = false, openCamera = false, seconds = false, newSince = recentNewSince))
        AlertInbox.markSeen(this, cam)   // shown here = seen
    }
    private lateinit var r: CamRemote
    private lateinit var feed: CamRemote.Feed
    /** Smooth video in the picture, when this build has it (else the pictures). */
    private var video: LiveVideo? = null
    private var lastSettings = ""
    private val refresh: () -> Unit = { renderIfChanged() }
    /** Settings changed here but not sent yet (sent together with Save). */
    private val draft = JSONObject()
    /** Saved values shown until the camera's status confirms them (so controls don't flick back meanwhile). */
    private val saved = JSONObject()
    private var savedAt = 0L
    private var saving = false
    private var leaveOk = false
    private lateinit var scroll: ScrollView
    private lateinit var saveBar: LinearLayout
    private lateinit var saveInfo: TextView
    private lateinit var saveBtn: TextView
    private lateinit var hqBox: LinearLayout
    private lateinit var hqImage: ImageView
    private lateinit var hqLabel: TextView
    private var hqBytes: ByteArray? = null
    private var hqTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        cam = intent.getStringExtra(Alerts.EXTRA_CAM) ?: run { finish(); return }
        r = CamRemote(this, cam)
        r.busy = { draft.length() > 0 || saving }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.BG) }
        // landscape: the picture and its buttons on the left, status and controls scrolling on the right
        val land = com.motioncam.Nav.landscape(this)
        val right = if (land) LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.BG) } else null
        root.addView(topBar(this, Live.phones[cam]?.name?.ifEmpty { null } ?: "Camera"))
        val frame = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        image = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        frame.addView(image, FrameLayout.LayoutParams(-1, -1))
        imageLabel = Ui.label(this, "Paused · tap Live view or Screenshot to see the camera", 12f).apply {
            setBackgroundColor(0x99000000.toInt()); setPadding(Ui.dp(this@RemoteCameraActivity, 8), Ui.dp(this@RemoteCameraActivity, 3), Ui.dp(this@RemoteCameraActivity, 8), Ui.dp(this@RemoteCameraActivity, 3))
        }
        frame.addView(imageLabel, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START))
        val w = resources.displayMetrics.widthPixels.coerceAtMost(Ui.dp(this, 700))
        root.addView(frame, if (land) LinearLayout.LayoutParams(-1, 0, 1f).apply { setMargins(Ui.dp(this@RemoteCameraActivity, 8), 0, 0, 0) }
            else LinearLayout.LayoutParams(-1, w * 9 / 16))
        liveNote = Ui.label(this, "", 12f, Ui.ACCENT).apply {
            setPadding(Ui.dp(this@RemoteCameraActivity, 12), Ui.dp(this@RemoteCameraActivity, 4), Ui.dp(this@RemoteCameraActivity, 12), 0)
            visibility = android.view.View.GONE
        }
        root.addView(liveNote)
        // tap the picture: full screen with the camera controls
        image.setOnClickListener { startActivity(Intent(this@RemoteCameraActivity, RemoteLiveActivity::class.java).putExtra(Alerts.EXTRA_CAM, cam)) }
        image.contentDescription = "Camera picture (tap for full screen with controls)"
        // live view · screenshot (one high-quality picture, shown below) · activity log
        val gap = Ui.dp(this, 6)
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; val p = Ui.dp(this@RemoteCameraActivity, 8); setPadding(p, p, p, if (land) p else 0) }
        liveBtn = Ui.chip(this, "▶ Live view").apply { setOnClickListener { if (feed.watching || video?.active == true) stopWatching() else startWatching() } }
        buttons.addView(liveBtn, LinearLayout.LayoutParams(0, -2, 1f))
        buttons.addView(Ui.chip(this, "Screenshot").apply {
            contentDescription = "Ask the camera for one high-quality picture (shown below)"
            setOnClickListener { requestFrame() }
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = gap })
        root.addView(buttons)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; val p = Ui.dp(this@RemoteCameraActivity, 16); setPadding(p, 0, p, p * 2) }
        hqBox = hqPanel()
        content.addView(hqBox)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(box)
        scroll = ScrollView(this).apply { addView(content) }
        (right ?: root).addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        if (!land) Ui.capWidth(content)
        saveBar = saveBar()
        (right ?: root).addView(saveBar)
        if (right != null) {
            // the picture as large as the height allows (16:9), the rest for the controls
            val dm = resources.displayMetrics
            val leftW = minOf((dm.widthPixels * 0.58f).toInt(), ((dm.heightPixels - Ui.dp(this, 130)) * 16 / 9) + Ui.dp(this, 16))
            setContentView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(Ui.BG)
                addView(root, LinearLayout.LayoutParams(leftW, -1))
                addView(android.view.View(this@RemoteCameraActivity).apply { setBackgroundColor(0xFF2A2D33.toInt()) }, LinearLayout.LayoutParams(Ui.dp(this@RemoteCameraActivity, 1), -1))
                addView(right, LinearLayout.LayoutParams(0, -1, 1f))
            })
        } else setContentView(root)
        f = Form(this, box)
        video = LiveVideo(this, cam, r, frame, image,
            playing = {
                if (feed.watching) feed.stop()   // the pictures aren't needed while video plays
                imageLabel.text = "● LIVE · video"
                liveBtn.text = "■ Stop live"
                liveNote.visibility = android.view.View.GONE
            }) { why ->
            Toast.makeText(this, "Video didn't connect ($why): showing pictures", Toast.LENGTH_LONG).show()
            if (!feed.watching) feed.start()
            liveBtn.text = if (feed.watching) "■ Stop live" else "▶ Live view"
        }
        feed = r.Feed(3 * 60_000L, { bmp, at ->
            // video is on (or connecting): pictures don't belong here (a late one arriving after the switch)
            if (video?.active == true) { feed.stop(); return@Feed }
            image.setImageBitmap(bmp)
            imageLabel.text = "● LIVE · frame sent " + Fmt.t(prefs, at)
            liveNote.visibility = android.view.View.GONE
        }) { msg ->
            imageLabel.text = msg
            liveBtn.text = if (feed.watching) "■ Stop live" else "▶ Live view"
        }
        // the last picture seen of this camera stays on screen (with its time) until a new one arrives
        CamRemote.lastFrames[cam]?.let { (bmp, at) ->
            image.setImageBitmap(bmp)
            imageLabel.text = "Paused · frame sent " + Fmt.t(prefs, at) + " — tap Live view for a new one"
        }
    }

    /** Once a second while watching: under the picture, why no new one arrives (the last one stays as it is). */
    private val liveTick = object : Runnable {
        override fun run() {
            val h = if (feed.watching && video?.on != true) feed.health() else null
            if (h != null) {
                val last = CamRemote.lastFrames[cam]
                liveNote.text = "⚠ " + h + (last?.let { " · showing the picture from " + Fmt.t(prefs, it.second) } ?: "")
                liveNote.visibility = android.view.View.VISIBLE
            } else liveNote.visibility = android.view.View.GONE
            ui.postDelayed(this, 1000)
        }
    }

    /** Settings changed on the camera phone itself: the screen already shows them; say so, and keep unsent edits. */
    private val events: (String, String, JSONObject) -> Unit = { c, type, d ->
        if (c == cam && type == "settings_local") {
            val keys = d.optJSONArray("keys")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
            val clash = keys.filter { draft.has(it) }
            Toast.makeText(this, "The camera phone changed its " + r.changedText(d) +
                (if (clash.isNotEmpty()) ". Your unsaved values for those are kept: Save to apply them, or Cancel to take the camera's." else "."),
                Toast.LENGTH_LONG).show()
        }
    }

    // ------------------------------------------------------------------ screenshot: one high-quality frame (under the live view)

    private fun hqPanel(): LinearLayout {
        val ctx = this
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = android.view.View.GONE
            setPadding(0, Ui.dp(ctx, 12), 0, 0)
            val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            hqLabel = Ui.label(ctx, "", 13f, Ui.ACCENT)
            head.addView(hqLabel, LinearLayout.LayoutParams(0, -2, 1f))
            head.addView(Ui.chip(ctx, "Save to phone").apply { setOnClickListener { saveFrame() } })
            head.addView(com.motioncam.iconButton(ctx, com.motioncam.IconDrawable.Kind.CLOSE, 40, 10) {
                visibility = android.view.View.GONE; hqImage.setImageDrawable(null); hqBytes = null
            }.apply { contentDescription = "Close the frame" }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = Ui.dp(ctx, 4) })
            addView(head)
            hqImage = ImageView(ctx).apply {
                adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER; setBackgroundColor(Color.BLACK)
                contentDescription = "Screenshot (tap to view full screen)"
                setOnClickListener { showFrameFull() }
            }
            addView(hqImage, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(ctx, 6) })
        }
    }

    private fun showFrameFull() {
        val b = hqBytes ?: return
        val bmp = BitmapFactory.decodeByteArray(b, 0, b.size) ?: return
        val d = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        d.setContentView(ImageView(this).apply {
            setImageBitmap(bmp); scaleType = ImageView.ScaleType.FIT_CENTER; setBackgroundColor(Color.BLACK)
            setOnClickListener { d.dismiss() }
        })
        d.show()
    }

    /** Saves the frame to the phone's Pictures/MotionCam (seen in the gallery). */
    private fun saveFrame() {
        val b = hqBytes ?: return
        val name = (Live.phones[cam]?.name?.ifEmpty { null } ?: "Camera").replace(Regex("[^A-Za-z0-9 _-]"), "") + " " +
            java.text.SimpleDateFormat("yyyy-MM-dd HH-mm-ss", java.util.Locale.US).format(java.util.Date(hqTime)) + ".jpg"
        try {
            val v = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name)
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/MotionCam")
            }
            val uri = contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v) ?: throw Exception("no storage")
            contentResolver.openOutputStream(uri)?.use { it.write(b) }
            Toast.makeText(this, "Saved to Pictures/MotionCam", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) { Toast.makeText(this, "Couldn't save: ${e.message}", Toast.LENGTH_LONG).show() }
    }

    // ------------------------------------------------------------------ unsaved settings (Save / Cancel stay on screen)

    private fun saveBar(): LinearLayout {
        val ctx = this
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF2A2416.toInt())
            val p = Ui.dp(ctx, 12)
            setPadding(p, Ui.dp(ctx, 8), p, Ui.dp(ctx, 8))
            visibility = android.view.View.GONE
            saveInfo = Ui.label(ctx, "", 14f)
            addView(saveInfo, LinearLayout.LayoutParams(0, -2, 1f))
            addView(Ui.chip(ctx, "Cancel").apply { setOnClickListener { cancelDraft() } })
            saveBtn = Ui.chip(ctx, "Save").apply { Ui.setChipState(this, true); setOnClickListener { saveDraft() } }
            addView(saveBtn, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = Ui.dp(ctx, 8) })
        }
    }

    private fun updateBar() {
        val n = draft.length()
        val show = n > 0 || saving
        saveInfo.text = if (saving) "Saving…" else "$n unsaved change${if (n == 1) "" else "s"}"
        val can = !saving && !r.lockedOut()
        saveBtn.isEnabled = can; saveBtn.alpha = if (can) 1f else 0.5f
        if (r.lockedOut() && !saving) saveInfo.text = "Another phone is changing this camera"
        if (show == (saveBar.visibility == android.view.View.VISIBLE)) return
        if (show) {
            saveBar.visibility = android.view.View.VISIBLE
            saveBar.translationY = Ui.dp(this, 60).toFloat()
            saveBar.animate().translationY(0f).setDuration(180).start()
        } else saveBar.animate().translationY(saveBar.height.toFloat()).setDuration(150).withEndAction {
            saveBar.visibility = android.view.View.GONE; saveBar.translationY = 0f
        }.start()
    }

    private fun saveDraft() {
        if (draft.length() == 0 || saving) return
        val sent = JSONObject(draft.toString())
        saving = true; updateBar()
        send("set", sent, onFail = { saving = false; updateBar() }) { ok, res ->
            saving = false
            val applied = res?.optJSONArray("applied")
            val keys = if (applied != null) (0 until applied.length()).map { applied.getString(it) } else if (ok) sent.keys().asSequence().toList() else emptyList()
            for (k in keys) {
                // only if not changed again meanwhile
                if (draft.opt(k)?.toString() == sent.opt(k)?.toString()) draft.remove(k)
                saved.put(k, sent.get(k))
            }
            savedAt = System.currentTimeMillis()
            updateBar(); render()
        }
    }

    private fun cancelDraft() {
        draft.keys().asSequence().toList().forEach { draft.remove(it) }
        updateBar(); render()
    }

    /** Leaving with unsaved changes asks first. */
    override fun finish() {
        if (draft.length() == 0 || leaveOk) { super.finish(); return }
        AlertDialog.Builder(this).setTitle("Unsaved changes")
            .setMessage("${draft.length()} setting${if (draft.length() == 1) "" else "s"} changed here ha${if (draft.length() == 1) "s" else "ve"} not been sent to the camera.")
            .setPositiveButton("Save") { _, _ -> saveDraft() }
            .setNegativeButton("Discard") { _, _ -> leaveOk = true; finish() }
            .setNeutralButton("Keep editing", null).show()
    }

    override fun onResume() {
        super.onResume()
        Live.listeners += refresh
        Live.eventListeners += events
        recentNewSince = AlertInbox.seenAt(this, cam)
        followRecent()
        Live.viewerActive = true
        Live.refresh(this)
        lastSettings = ""
        renderIfChanged()
        ui.post(liveTick)
    }

    override fun onPause() {
        Live.listeners -= refresh
        Live.eventListeners -= events
        unfollowRecent()
        r.leave()   // gives the remote back
        ui.removeCallbacks(liveTick)
        if (::feed.isInitialized) stopWatching()
        super.onPause()
    }

    private fun status(): JSONObject? = r.status()
    private fun caps(): JSONObject? = r.caps()
    private fun online() = r.online()

    /** Remote start: a push signed with the camera's PIN (asked for if this phone doesn't know it yet). */
    private fun remoteStart() {
        val token = Live.phones[cam]?.push ?: return
        val pin = pinFor() ?: run { r.askPin({ remoteStart() }); return }
        Toast.makeText(this, "Sending…", Toast.LENGTH_SHORT).show()
        Push.sendStart(this, cam, token, pin) { ok ->
            Toast.makeText(this, if (ok) "Sent: the camera should come online within a minute" else "Couldn't send it (no connection?)", Toast.LENGTH_LONG).show()
        }
    }
    private fun pinFor(): String? = r.pinFor()
    private fun askPin(then: () -> Unit) = r.askPin(then)

    // ------------------------------------------------------------------ live view / frames

    private fun startWatching() {
        // video when it can be used here; pictures only if not (or as the fallback when video doesn't connect)
        if (video?.start() != true) feed.start()
        if (feed.watching || video?.active == true) liveBtn.text = "■ Stop live"
        if (video?.active == true) imageLabel.text = "Connecting live video…"
    }

    private fun stopWatching() {
        feed.stop()
        video?.stop()
        liveBtn.text = "▶ Live view"
    }

    override fun onDestroy() {
        video?.release()
        super.onDestroy()
    }

    private fun requestFrame() {
        if (!online()) { Toast.makeText(this, "The camera is offline", Toast.LENGTH_SHORT).show(); return }
        if (status()?.optBoolean("pinForView") == true && pinFor() == null) { askPin { requestFrame() }; return }
        val req = Live.ref(this, "cams/$cam/snapReq")?.push() ?: return
        val id = req.key ?: return
        val at = Live.now()
        val m = HashMap<String, Any>(); m["by"] = Device.id(this); m["at"] = at
        pinFor()?.let { m["sig"] = CameraLink.sign(cam, it, "snap:$id:$at") }
        hqBox.visibility = android.view.View.VISIBLE
        hqLabel.text = "Taking a screenshot…"
        scroll.post { scroll.smoothScrollTo(0, 0) }
        val out = Live.ref(this, "cams/$cam/snap/$id") ?: return
        val l = object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                if (!s.exists()) return
                out.removeEventListener(this)
                out.removeValue()
                (s.child("err").value as? String)?.let { hqLabel.text = it; return }
                val bytes = LiveNet.openBytes(this@RemoteCameraActivity, s.child("img").value as? String) ?: return
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { hqImage.setImageBitmap(it) } ?: return
                hqBytes = bytes
                hqTime = System.currentTimeMillis()
                hqLabel.text = "Screenshot · " + Fmt.t(prefs, hqTime)
            }
            override fun onCancelled(e: DatabaseError) {}
        }
        out.addValueEventListener(l)
        req.setValue(m)
        ui.postDelayed({ out.removeEventListener(l) }, 30_000)
    }

    // ------------------------------------------------------------------ PIN + commands

    private fun send(type: String, args: JSONObject? = null, onFail: () -> Unit = {}, done: (Boolean, JSONObject?) -> Unit = { _, _ -> }) {
        // changing the camera (monitoring, Save) claims its remote like any change
        if (type in setOf("set", "pause", "resume", "stop", "start") && !r.touch()) { onFail(); return }
        r.send(type, args, onFail = onFail, done = done)
    }

    /** Settings that show or hide other controls: the screen is rebuilt right away when they change. */
    private val STRUCTURAL = setOf("exposureAuto", "focusAuto", "wbAuto", "camera")

    /** A setting changed here: kept as a draft until Save (and claims the camera's remote meanwhile). */
    private fun set(key: String, value: Any) {
        if (!r.touch()) { lastSettings = ""; ui.post { renderIfChanged() }; return }
        val server = status()?.optJSONObject("settings")?.opt(key)
        if (server != null && server.toString() == value.toString()) draft.remove(key) else draft.put(key, value)
        updateBar()
        if (key in STRUCTURAL) ui.post { render() }
    }

    /** The camera's settings with saved-but-unconfirmed values and this screen's unsaved changes on top. */
    private fun shownSettings(server: JSONObject): JSONObject {
        val m = JSONObject(server.toString())
        if (System.currentTimeMillis() - savedAt > 15_000) saved.keys().asSequence().toList().forEach { saved.remove(it) }
        for (k in saved.keys().asSequence().toList()) {
            if (server.opt(k)?.toString() == saved.opt(k)?.toString()) saved.remove(k) else m.put(k, saved.get(k))
        }
        for (k in draft.keys()) m.put(k, draft.get(k))
        return m
    }

    // ------------------------------------------------------------------ screen

    private fun renderIfChanged() {
        // the camera's live values (meter, auto ISO…) change every few seconds while watching: they don't rebuild the list
        val key = (status()?.let { JSONObject(it.toString()).apply { remove("live") } }?.toString() ?: "") + "|" +
            (caps()?.toString()?.length ?: 0) + "|" + online()
        // controls are rebuilt only when the camera's state changed (not while the user drags something)
        if (key == lastSettings) return
        // someone is typing a value: rebuild once they're done (their text would be lost otherwise)
        if ((currentFocus as? android.widget.EditText)?.let { isInside(it) } == true) { ui.postDelayed({ renderIfChanged() }, 1500); return }
        lastSettings = key
        render()
    }

    private fun isInside(v: android.view.View): Boolean {
        var p = v.parent
        while (p != null) { if (p === f.box) return true; p = p.parent }
        return false
    }

    private fun render() {
        f.box.removeAllViews()
        val s = status()
        val phone = Live.phones[cam]
        if (s == null) { f.note(if (phone?.online == true) "Waiting for the camera's status…" else "The camera is not connected right now."); return }
        val st = shownSettings(s.optJSONObject("settings") ?: JSONObject())
        val c = caps() ?: JSONObject()
        // one controller at a time: while another phone has the remote this one can only watch
        val enabled = online() && !r.lockedOut()

        f.title("Status")
        val mode = s.optString("mode")
        f.line(when {
            !enabled -> "Offline" + (phone?.seen?.takeIf { it > 0 }?.let { " since " + Fmt.t(prefs, it - Live.serverOffset, seconds = false) } ?: "")
            mode == "RECORDING" -> "● Recording (since " + Fmt.t(prefs, s.optLong("recSince"), seconds = false) + ")"
            s.optBoolean("monitoring") -> "Monitoring (watching for motion)"
            s.optBoolean("standby") -> "Standby · not monitoring (you can start it from here)"
            else -> "Not monitoring" + s.optString("message").takeIf { it.isNotEmpty() }?.let { " · $it" }.orEmpty()
        }, 15f, if (mode == "RECORDING") Ui.RED else Color.WHITE)
        val lm = s.optLong("lastMotion")
        f.line("Last motion: " + if (lm > 0) Fmt.dateTime(prefs).format(java.util.Date(lm)) else "none yet")
        f.line("").let { tv ->
            tv.text = android.text.SpannableStringBuilder("Battery ")
                .append(com.motioncam.BatteryDrawable.text(tv, s.optInt("battery"), s.optBoolean("charging")))
                .append(if (s.optBoolean("charging")) " (charging)" else "")
                .append("  ·  %.0f °C".format(s.optDouble("tempC")) + "  ·  ${s.optString("lens")}  ·  ${s.optString("format")}")
        }
        if (s.optBoolean("pin")) f.note("This camera needs a PIN for controls" + if (pinFor() != null) " (remembered)." else ".")

        // another phone is changing this camera: one line, controls locked until it's done (a few seconds)
        r.lockText()?.let { f.line(it, 13f, Ui.ACCENT) }

        // this camera's latest events (motion with its clip as one entry), live; the rest in its Activity
        f.title("Recent activity")
        recentBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        f.box.addView(recentBox)
        fillRecent()
        f.button("See all activity ›", full = false) {
            startActivity(Intent(this, ActivityLogActivity::class.java).putExtra(Alerts.EXTRA_CAM, cam))
        }

        f.title("Monitoring")
        // one button: Stop monitoring keeps the camera on for a while (instant restart, live view), then it turns off by
        // itself to save power — only when it can be started again from here (standby or remote start)
        when {
            s.optBoolean("standby") -> f.row(f.chip("▶ Start monitoring") { send("start") }.apply { Ui.setChipState(this, true) })
            s.optBoolean("monitoring") -> f.row(f.chip("Stop monitoring") { send("pause") })
            else -> f.row(f.chip("▶ Start monitoring") { send("resume") }.apply { Ui.setChipState(this, true) })
        }.also { row -> if (!enabled) for (i in 0 until row.childCount) row.getChildAt(i).apply { isEnabled = false; alpha = 0.4f } }
        if (enabled) {
            val offAt = s.optLong("offAt")
            when {
                s.optBoolean("monitoring") -> f.note("Stopping keeps the camera on for ${com.motioncam.CameraService.AUTO_OFF_MIN} minutes " +
                    "(start again instantly, watch live), then it turns off to save battery if it can be started again from here.")
                s.optBoolean("standby") -> f.note("Stopped · the camera is off to save power; starting takes a few seconds.")
                offAt > 0 -> {
                    val min = ((offAt - System.currentTimeMillis() + 59_999) / 60_000).coerceAtLeast(1)
                    f.note("Stopped · the camera turns off in about $min min to save power (start again any time).")
                }
            }
        }
        // a failsafe when MotionCam isn't running there (offline): wake it by push, if that phone allows it
        if (!online()) {
            val allowed = phone?.remoteStart == true && !phone.push.isNullOrEmpty()
            val start = f.chip("▶ Start MotionCam remotely") { remoteStart() }
            f.row(start)
            if (allowed && Push.canSend) {
                Ui.setChipState(start, true)
                f.note("Wakes MotionCam on that phone with a push (also when it's locked or MotionCam was closed) and starts monitoring. " +
                    "It shows up here within a minute if the phone has internet.")
            } else {
                start.isEnabled = false; start.alpha = 0.4f
                f.note(if (!Push.canSend) "Needs push alerts in your build (SETUP.md step 7)."
                    else "Turn on Remote start on that phone (Settings › Your phones) to start it from here while it's offline.")
            }
        }

        // never show (or send) defaults: the controls appear only once the camera has said what its settings are
        if (!s.has("settings")) { f.title("Settings"); f.note("Loading the camera's settings…"); return }
        val sf = com.motioncam.SettingsForm(this, f.box)
        f.title("Camera")
        f.note("These are the camera's own settings" + (Live.cams[cam]?.statusAt?.takeIf { it > 0 }?.let { " (as of " + Fmt.t(prefs, it - Live.serverOffset, seconds = false) + ")" } ?: "") +
            ". Changes are sent when you tap Save. Drag a slider's knob, or type the exact value.")
        val lenses = c.optJSONArray("lenses")
        val lensLabel = (0 until (lenses?.length() ?: 0)).map { lenses!!.getJSONObject(it) }.firstOrNull { it.optString("key") == st.optString("camera") }?.optString("label")
            ?: s.optString("lens")
        f.row(f.chip("Lens: ${lensLabel.substringBefore(" [")}") {
            val list = (0 until (lenses?.length() ?: 0)).map { lenses!!.getJSONObject(it) }
            if (list.isEmpty()) return@chip
            com.motioncam.OptionSheet.show(this, "Lens", list.map { l ->
                val label = l.optString("label")
                com.motioncam.Opt(label.substringBefore(" ["), label.substringAfter(" [", "").removeSuffix("]").ifEmpty { null },
                    checked = l.optString("key") == st.optString("camera")) { set("camera", l.optString("key")) }
            })
        })
        // exposure: as on the camera screen (EV while automatic; ISO + shutter when manual)
        val evStep = c.optDouble("evStep", 1.0 / 6)
        sf.toggle("Exposure: automatic", st.optBoolean("exposureAuto", true), enabled) { set("exposureAuto", it) }
        if (st.optBoolean("exposureAuto", true)) {
            val lo = c.optInt("evMin", -12); val hi = c.optInt("evMax", 12)
            sf.scaled("Exposure compensation", "EV", st.optInt("evIndex") * evStep, hi - lo,
                toPos = { (it / evStep).roundToInt() - lo }, fromPos = { (it + lo) * evStep },
                format = { (if (it > 0.001) "+" else "") + "%.1f".format(it) },
                parse = { t -> t.replace(',', '.').replace("+", "").toDoubleOrNull()?.let { ((it / evStep).roundToInt().coerceIn(lo, hi)) * evStep } },
                signed = true, enabled = enabled) { set("evIndex", (it / evStep).roundToInt()) }
        } else {
            // manual exposure: the camera's light meter (0 = what automatic exposure would aim for)
            s.optJSONObject("live")?.opt("meter")?.let { it as? Number }?.let { m ->
                f.line("Meter: " + (if (m.toDouble() > 0.05) "+" else "") + "%.1f EV".format(m.toDouble()), 13f, Ui.ACCENT)
            }
            val isoLo = c.optInt("isoMin", 50).toDouble(); val isoHi = c.optInt("isoMax", 3200).toDouble()
            sf.scaled("ISO", "", st.optInt("iso", 400).toDouble(), 1000, toPos = { toLog(it, isoLo, isoHi) }, fromPos = { fromLog(it, isoLo, isoHi).roundToInt().toDouble() },
                format = { "${it.roundToInt()}" }, parse = { it.toDoubleOrNull()?.coerceIn(isoLo, isoHi)?.roundToInt()?.toDouble() },
                decimalInput = false, enabled = enabled) { set("iso", it.roundToInt()) }
            val eMin = c.optLong("expMinNs", 100_000L).coerceAtLeast(125_000L).toDouble()
            val eMax = minOf(c.optLong("expMaxNs", 250_000_000L), 1_000_000_000L / st.optInt("fps", 30).coerceAtLeast(1)).toDouble().coerceAtLeast(eMin * 2)
            // the box takes the "1/…" number (60 = 1/60 s), like the camera screen's exact entry
            sf.scaled("Shutter speed  1 /", "s", st.optLong("shutterNs", 16_666_666L).toDouble(), 1000,
                toPos = { toLog(it, eMin, eMax) }, fromPos = { fromLog(it, eMin, eMax) },
                format = { ns -> val den = 1e9 / ns; if (den >= 10) "${den.roundToInt()}" else "%.2f".format(den).trimEnd('0').trimEnd('.') },
                parse = { t -> t.replace(',', '.').removePrefix("1/").toDoubleOrNull()?.takeIf { it > 0 }?.let { (1e9 / it).coerceIn(eMin, eMax) } },
                enabled = enabled) { set("shutterNs", it.toLong()) }
        }
        // focus: the same distance scale as the camera screen (slider: near → far ∞; box in cm, empty = ∞)
        val minFocus = c.optDouble("minFocus", 0.0)
        if (minFocus > 0) {
            sf.toggle("Focus: automatic", st.optBoolean("focusAuto", true), enabled) { set("focusAuto", it) }
            if (!st.optBoolean("focusAuto", true)) {
                fun mOf(pos: Double): Double { val d = (1 - pos) * minFocus; return if (d < 0.1) Double.POSITIVE_INFINITY else 1 / d }
                sf.scaled("Focus distance", "m", st.optDouble("focusPos", 1.0).coerceIn(0.0, 1.0), 1000,
                    toPos = { (it * 1000).roundToInt() }, fromPos = { it / 1000.0 },
                    format = { pos -> mOf(pos).let { if (it.isInfinite()) "" else if (it < 10) "%.2f".format(it) else "%.1f".format(it) } },
                    parse = { t -> if (t.isEmpty() || t == "∞") 1.0 else t.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 }?.let { m ->
                        (1 - (1 / m) / minFocus).coerceIn(0.0, 1.0) } },
                    hint = "∞", enabled = enabled) { set("focusPos", it) }
                f.note("Closest: about %.2f m. Leave the box empty for infinity (∞).".format(1 / minFocus))
            }
        }
        // white balance: as on the camera screen — automatic, or a slider over its presets (type Kelvin: nearest preset)
        val presets = com.motioncam.ProCaps.wbPresets
        sf.toggle("White balance: automatic", st.optBoolean("wbAuto", true), enabled) { set("wbAuto", it) }
        if (!st.optBoolean("wbAuto", true)) {
            val idx = com.motioncam.ProCaps.nearestPreset(st.optInt("wbKelvin", 5200))
            sf.scaled("White balance", "K", idx.toDouble(), presets.size - 1,
                toPos = { it.roundToInt() }, fromPos = { it.toDouble() },
                format = { "${presets[it.roundToInt().coerceIn(0, presets.size - 1)].second}" },
                parse = { t -> t.toIntOrNull()?.let { com.motioncam.ProCaps.nearestPreset(it).toDouble() } },
                decimalInput = false, enabled = enabled) { set("wbKelvin", presets[it.roundToInt().coerceIn(0, presets.size - 1)].second) }
        }
        val zMin = c.optDouble("zoomMin", 1.0); val zMax = c.optDouble("zoomMax", 1.0)
        if (zMax > zMin + 0.01) sf.decimal("Zoom", "×", zMin.toFloat(), zMax.toFloat(), st.optDouble("zoom", 1.0).toFloat(), enabled) { set("zoom", (it * 10).roundToInt() / 10.0) }

        // recording and motion detection: exactly as in the app's Settings (same labels, units, ranges)
        f.title("Recording")
        val heights = listOf(720, 1080, 2160)
        sf.choice("Resolution", listOf("720p", "1080p (FHD)", "4K"), heights.indexOf(st.optInt("resHeight", 1080)).coerceAtLeast(1), enabled) { set("resHeight", heights[it]) }
        val rates = listOf(24, 30, 60)
        sf.choice("Frame rate", listOf("24 fps", "30 fps", "60 fps"), rates.indexOf(st.optInt("fps", 30)).coerceAtLeast(1), enabled) { set("fps", rates[it]) }
        sf.choice("Codec", listOf("H.264 (most compatible)", "HEVC / H.265 (smaller files)"), if (st.optBoolean("hevc")) 1 else 0, enabled) { set("hevc", it == 1) }
        sf.number("Bitrate", "Mbps", 0, 100, st.optInt("bitrateMbps"), offLabel = "auto", enabled = enabled) { set("bitrateMbps", it) }
        sf.toggle("Record audio", st.optBoolean("audio"), enabled) { set("audio", it) }
        val pre = listOf(0, 500, 1000, 2000, 3000)
        sf.choice("Pre-record (video from before the motion)", listOf("Off", "0.5 s", "1 s", "2 s", "3 s"), pre.indexOf(st.optInt("preRecordMs")).coerceAtLeast(0), enabled) { set("preRecordMs", pre[it]) }

        f.title("Motion detection")
        sf.number("Sensitivity (1 = least, ${c.optInt("maxSensitivity", com.motioncam.MotionDetector.MAX_SENSITIVITY)} = most; default 5)", "", 1,
            c.optInt("maxSensitivity", com.motioncam.MotionDetector.MAX_SENSITIVITY), st.optInt("sensitivity", 5), enabled = enabled) { set("sensitivity", it) }
        sf.choice("Light filter", listOf("Off (any brightness change)", "Normal — ignore lights turning on/off, exposure changes",
            "Strong — also ignore glare and reflections (peepholes, windows)"), st.optInt("lightFilter", 2), enabled) { set("lightFilter", it) }
        sf.decimal("Minimum moving area", "% of frame", 0.2f, 20f, st.optDouble("minAreaPct", 5.0).toFloat(), enabled) { set("minAreaPct", (it * 10).roundToInt() / 10.0) }
        sf.number("Motion must last at least", "ms", 0, 3000, st.optInt("minMotionMs", 250), enabled = enabled) { set("minMotionMs", it) }
        sf.number("Stop recording after no motion for (0 = as soon as motion ends)", "s", 0, 600, st.optInt("stopDelaySec", 30), enabled = enabled) { set("stopDelaySec", it) }
        sf.number("Split long events into clips of", "min", 1, 60, st.optInt("maxClipMin", 10), enabled = enabled) { set("maxClipMin", it) }
        if (!enabled) f.note("Controls work while the camera is connected.")
    }

    private fun toLog(v: Double, lo: Double, hi: Double) = (ln(v.coerceIn(lo, hi) / lo) / ln(hi / lo) * 1000).roundToInt().coerceIn(0, 1000)
    private fun fromLog(p: Int, lo: Double, hi: Double) = lo * exp(ln(hi / lo) * p / 1000.0)
    private fun shutterText(ns: Double) = if (ns >= 500_000_000) "%.1f s".format(ns / 1e9) else "1/${(1e9 / ns).roundToInt()}"
}
