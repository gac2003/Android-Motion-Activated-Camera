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
    private lateinit var liveBtn: TextView
    private lateinit var f: Form
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var r: CamRemote
    private lateinit var feed: CamRemote.Feed
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
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.BG) }
        root.addView(topBar(this, Live.phones[cam]?.name?.ifEmpty { null } ?: "Camera"))
        val frame = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        image = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        frame.addView(image, FrameLayout.LayoutParams(-1, -1))
        imageLabel = Ui.label(this, "Tap Live view or Frame to see the camera", 12f).apply {
            setBackgroundColor(0x99000000.toInt()); setPadding(Ui.dp(this@RemoteCameraActivity, 8), Ui.dp(this@RemoteCameraActivity, 3), Ui.dp(this@RemoteCameraActivity, 8), Ui.dp(this@RemoteCameraActivity, 3))
        }
        frame.addView(imageLabel, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START))
        val w = resources.displayMetrics.widthPixels.coerceAtMost(Ui.dp(this, 700))
        root.addView(frame, LinearLayout.LayoutParams(-1, w * 9 / 16))
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; val p = Ui.dp(this@RemoteCameraActivity, 8); setPadding(p, p, p, 0) }
        liveBtn = Ui.chip(this, "▶ Live view").apply { setOnClickListener { if (feed.watching) stopWatching() else startWatching() } }
        buttons.addView(liveBtn)
        buttons.addView(Ui.chip(this, "⛶ Full screen").apply {
            contentDescription = "Full-screen live view with camera controls"
            setOnClickListener { startActivity(Intent(this@RemoteCameraActivity, RemoteLiveActivity::class.java).putExtra(Alerts.EXTRA_CAM, cam)) }
        }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = Ui.dp(this@RemoteCameraActivity, 6) })
        buttons.addView(Ui.chip(this, "High-quality frame").apply { setOnClickListener { requestFrame() } },
            LinearLayout.LayoutParams(-2, -2).apply { leftMargin = Ui.dp(this@RemoteCameraActivity, 6) })
        buttons.addView(Ui.chip(this, "Activity").apply {
            setOnClickListener { startActivity(Intent(this@RemoteCameraActivity, ActivityLogActivity::class.java).putExtra(Alerts.EXTRA_CAM, cam)) }
        }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = Ui.dp(this@RemoteCameraActivity, 6) })
        root.addView(android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(buttons) })
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; val p = Ui.dp(this@RemoteCameraActivity, 16); setPadding(p, 0, p, p * 2) }
        hqBox = hqPanel()
        content.addView(hqBox)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(box)
        scroll = ScrollView(this).apply { addView(content) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        Ui.capWidth(content)
        saveBar = saveBar()
        root.addView(saveBar)
        setContentView(root)
        f = Form(this, box)
        image.setOnClickListener { if (!feed.watching) startWatching() }
        feed = r.Feed(3 * 60_000L, { bmp, at ->
            image.setImageBitmap(bmp)
            imageLabel.text = "Live (every 2 s) · " + Fmt.t(prefs, at)
        }) { msg ->
            imageLabel.text = msg
            liveBtn.text = if (feed.watching) "■ Stop live" else "▶ Live view"
        }
    }

    // ------------------------------------------------------------------ high-quality frame (under the live view)

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
                contentDescription = "High-quality frame (tap to view full screen)"
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
        saveBtn.isEnabled = !saving; saveBtn.alpha = if (saving) 0.5f else 1f
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
        Live.viewerActive = true
        Live.refresh(this)
        lastSettings = ""
        renderIfChanged()
    }

    override fun onPause() {
        Live.listeners -= refresh
        if (::feed.isInitialized) stopWatching()
        super.onPause()
    }

    private fun status(): JSONObject? = r.status()
    private fun caps(): JSONObject? = r.caps()
    private fun online() = r.online()
    private fun pinFor(): String? = r.pinFor()
    private fun askPin(then: () -> Unit) = r.askPin(then)

    // ------------------------------------------------------------------ live view / frames

    private fun startWatching() {
        feed.start()
        if (feed.watching) liveBtn.text = "■ Stop live"
    }

    private fun stopWatching() {
        feed.stop()
        liveBtn.text = "▶ Live view"
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
        hqLabel.text = "Asking for a high-quality frame…"
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
                hqLabel.text = "High-quality frame · " + Fmt.t(prefs, hqTime)
            }
            override fun onCancelled(e: DatabaseError) {}
        }
        out.addValueEventListener(l)
        req.setValue(m)
        ui.postDelayed({ out.removeEventListener(l) }, 30_000)
    }

    // ------------------------------------------------------------------ PIN + commands

    private fun send(type: String, args: JSONObject? = null, onFail: () -> Unit = {}, done: (Boolean, JSONObject?) -> Unit = { _, _ -> }) =
        r.send(type, args, onFail = onFail, done = done)

    /** Settings that show or hide other controls: the screen is rebuilt right away when they change. */
    private val STRUCTURAL = setOf("exposureAuto", "focusAuto", "wbAuto", "camera")

    /** A setting changed here: kept as a draft until Save. */
    private fun set(key: String, value: Any) {
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
        val key = (status()?.toString() ?: "") + "|" + (caps()?.toString()?.length ?: 0) + "|" + online()
        // controls are rebuilt only when the camera's state changed (not while the user drags something)
        if (key == lastSettings) return
        lastSettings = key
        render()
    }

    private fun render() {
        f.box.removeAllViews()
        val s = status()
        val phone = Live.phones[cam]
        if (s == null) { f.note(if (phone?.online == true) "Waiting for the camera's status…" else "The camera is not connected right now."); return }
        val st = shownSettings(s.optJSONObject("settings") ?: JSONObject())
        val c = caps() ?: JSONObject()
        val enabled = online()

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

        f.title("Monitoring")
        val stopChip = f.chip("Stop monitoring") {
            val standbyOn = s.optBoolean("standbyOn")
            AlertDialog.Builder(this).setTitle("Stop monitoring?")
                .setMessage(if (standbyOn) "The camera closes and the phone goes to standby: it stays connected, and you can start monitoring again from here."
                    else "The camera phone stops completely and goes offline. It can only be started again on the phone itself " +
                        "(or turn on Standby in its Live connection settings). Use Pause to keep it reachable.")
                .setPositiveButton("Stop") { _, _ -> send("stop") }.setNegativeButton("Cancel", null).show()
        }
        when {
            s.optBoolean("standby") -> f.row(f.chip("▶ Start monitoring") { send("start") }.apply { Ui.setChipState(this, true) })
            s.optBoolean("monitoring") -> f.row(f.chip("Pause monitoring") { send("pause") }, stopChip)
            else -> f.row(f.chip("Resume monitoring") { send("resume") }, stopChip)
        }

        f.title("Camera")
        f.note("Changes below are sent to the camera when you tap Save.")
        val lenses = c.optJSONArray("lenses")
        val lensLabel = (0 until (lenses?.length() ?: 0)).map { lenses!!.getJSONObject(it) }.firstOrNull { it.optString("key") == st.optString("camera") }?.optString("label")
            ?: s.optString("lens")
        f.row(f.chip("Lens: $lensLabel") {
            val list = (0 until (lenses?.length() ?: 0)).map { lenses!!.getJSONObject(it) }
            if (list.isEmpty()) return@chip
            com.motioncam.OptionSheet.show(this, "Lens", list.map { l ->
                val label = l.optString("label")
                com.motioncam.Opt(label.substringBefore(" ["), label.substringAfter(" [", "").removeSuffix("]").ifEmpty { null },
                    checked = l.optString("key") == st.optString("camera")) { set("camera", l.optString("key")) }
            })
        })
        val evStep = c.optDouble("evStep", 0.5)
        f.toggle("Exposure: automatic", st.optBoolean("exposureAuto", true), enabled) { set("exposureAuto", it) }
        if (st.optBoolean("exposureAuto", true)) {
            f.slider("Exposure compensation", c.optInt("evMin", -8), c.optInt("evMax", 8), st.optInt("evIndex"), { "EV %+.1f".format(it * evStep) }, enabled) { set("evIndex", it) }
        } else {
            val isoMin = c.optInt("isoMin", 50); val isoMax = c.optInt("isoMax", 3200)
            f.slider("ISO", 0, 1000, toLog(st.optInt("iso", 400).toDouble(), isoMin.toDouble(), isoMax.toDouble()),
                { "ISO " + fromLog(it, isoMin.toDouble(), isoMax.toDouble()).roundToInt() }, enabled) { set("iso", fromLog(it, isoMin.toDouble(), isoMax.toDouble()).roundToInt()) }
            val eMin = c.optLong("expMinNs", 100_000L).coerceAtLeast(125_000L).toDouble(); val eMax = c.optLong("expMaxNs", 250_000_000L).toDouble()
            f.slider("Shutter", 0, 1000, toLog(st.optLong("shutterNs", 16_666_666L).toDouble(), eMin, eMax),
                { shutterText(fromLog(it, eMin, eMax)) }, enabled) { set("shutterNs", fromLog(it, eMin, eMax).toLong()) }
        }
        val minFocus = c.optDouble("minFocus", 0.0)
        if (minFocus > 0) {
            f.toggle("Focus: automatic", st.optBoolean("focusAuto", true), enabled) { set("focusAuto", it) }
            // focusPos: 1 = far (∞) … 0 = closest; the slider runs far → close
            if (!st.optBoolean("focusAuto", true)) f.slider("Focus distance", 0, 100, ((1 - st.optDouble("focusPos", 1.0).coerceIn(0.0, 1.0)) * 100).roundToInt(),
                { if (it == 0) "far (∞)" else "%.2f m".format(1 / (minFocus * it / 100)) }, enabled) { set("focusPos", 1 - it / 100.0) }
        }
        f.toggle("White balance: automatic", st.optBoolean("wbAuto", true), enabled) { set("wbAuto", it) }
        if (!st.optBoolean("wbAuto", true)) f.slider("White balance", 20, 100, st.optInt("wbKelvin", 5200) / 100, { "${it * 100} K" }, enabled) { set("wbKelvin", it * 100) }
        val zMin = c.optDouble("zoomMin", 1.0); val zMax = c.optDouble("zoomMax", 1.0)
        if (zMax > zMin + 0.01) f.slider("Zoom", (zMin * 10).roundToInt(), (zMax * 10).roundToInt(), (st.optDouble("zoom", 1.0) * 10).roundToInt(),
            { "%.1f×".format(it / 10.0) }, enabled) { set("zoom", it / 10.0) }

        f.title("Recording")
        f.toggle("Record audio", st.optBoolean("audio"), enabled) { set("audio", it) }
        f.choice("Codec", listOf("H.264", "HEVC (smaller)"), if (st.optBoolean("hevc")) 1 else 0, enabled) { set("hevc", it == 1) }
        val heights = listOf(720, 1080, 2160)
        f.choice("Resolution", listOf("720p", "1080p", "4K"), heights.indexOf(st.optInt("resHeight", 1080)).coerceAtLeast(0), enabled) { set("resHeight", heights[it]) }
        val fpsList = c.optJSONArray("fps")?.let { a -> (0 until a.length()).map { a.getInt(it) } } ?: listOf(24, 30, 60)
        f.choice("Frame rate", fpsList.map { "$it fps" }, fpsList.indexOf(st.optInt("fps", 30)).coerceAtLeast(0), enabled) { set("fps", fpsList[it]) }
        f.slider("Bitrate", 0, 100, st.optInt("bitrateMbps"), { if (it == 0) "auto" else "$it Mbps" }, enabled) { set("bitrateMbps", it) }
        val pre = listOf(0, 500, 1000, 2000, 3000)
        f.choice("Pre-record", listOf("Off", "0.5 s", "1 s", "2 s", "3 s"), pre.indexOf(st.optInt("preRecordMs")).coerceAtLeast(0), enabled) { set("preRecordMs", pre[it]) }
        f.slider("Stop after no motion for", 0, 120, st.optInt("stopDelaySec", 30), { "$it s" }, enabled) { set("stopDelaySec", it) }
        f.slider("Longest clip", 0, 60, st.optInt("maxClipMin", 10), { if (it == 0) "no limit" else "$it min" }, enabled) { set("maxClipMin", it) }

        f.title("Motion detection")
        f.slider("Sensitivity", 1, c.optInt("maxSensitivity", 14), st.optInt("sensitivity", 5), { "$it" }, enabled) { set("sensitivity", it) }
        f.choice("Light filter", listOf("Off", "Normal", "Strong (peepholes, windows)"), st.optInt("lightFilter", 2), enabled) { set("lightFilter", it) }
        f.slider("Minimum moving area", 2, 200, (st.optDouble("minAreaPct", 5.0) * 10).roundToInt(), { "%.1f %%".format(it / 10.0) }, enabled) { set("minAreaPct", it / 10.0) }
        f.slider("Motion must last at least", 0, 40, st.optInt("minMotionMs", 250) / 50, { "${it * 50} ms" }, enabled) { set("minMotionMs", it * 50) }
        if (!enabled) f.note("Controls work while the camera is connected.")
    }

    private fun toLog(v: Double, lo: Double, hi: Double) = (ln(v.coerceIn(lo, hi) / lo) / ln(hi / lo) * 1000).roundToInt().coerceIn(0, 1000)
    private fun fromLog(p: Int, lo: Double, hi: Double) = lo * exp(ln(hi / lo) * p / 1000.0)
    private fun shutterText(ns: Double) = if (ns >= 500_000_000) "%.1f s".format(ns / 1e9) else "1/${(1e9 / ns).roundToInt()}"
}
