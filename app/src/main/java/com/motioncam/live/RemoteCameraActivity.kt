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
    private var watching = false
    private var watchStarted = 0L
    private var lastSettings = ""
    private val refresh: () -> Unit = { renderIfChanged() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        cam = intent.getStringExtra(Alerts.EXTRA_CAM) ?: run { finish(); return }
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
        liveBtn = Ui.chip(this, "▶ Live view").apply { setOnClickListener { if (watching) stopWatching() else startWatching() } }
        buttons.addView(liveBtn)
        buttons.addView(Ui.chip(this, "High-quality frame").apply { setOnClickListener { requestFrame() } },
            LinearLayout.LayoutParams(-2, -2).apply { leftMargin = Ui.dp(this@RemoteCameraActivity, 6) })
        buttons.addView(Ui.chip(this, "Activity").apply {
            setOnClickListener { startActivity(Intent(this@RemoteCameraActivity, ActivityLogActivity::class.java).putExtra(Alerts.EXTRA_CAM, cam)) }
        }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = Ui.dp(this@RemoteCameraActivity, 6) })
        root.addView(android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(buttons) })
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; val p = Ui.dp(this@RemoteCameraActivity, 16); setPadding(p, 0, p, p * 2) }
        root.addView(ScrollView(this).apply { addView(box) }, LinearLayout.LayoutParams(-1, 0, 1f))
        Ui.capWidth(box)
        setContentView(root)
        f = Form(this, box)
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
        stopWatching()
        super.onPause()
    }

    private fun status(): JSONObject? = Live.cams[cam]?.status
    private fun caps(): JSONObject? = Live.cams[cam]?.caps
    private fun online() = Live.phones[cam]?.online == true

    // ------------------------------------------------------------------ live view / frames

    private var liveL: ValueEventListener? = null
    private val heartbeat = object : Runnable {
        override fun run() {
            if (!watching) return
            if (System.currentTimeMillis() - watchStarted > 3 * 60_000L) {   // don't stream forever by accident
                stopWatching(); imageLabel.text = "Live view paused after 3 minutes — tap Live view to continue"; return
            }
            val at = Live.now()
            val m = HashMap<String, Any>(); m["at"] = at
            pinFor()?.let { m["sig"] = CameraLink.sign(cam, it, "watch:${Device.id(this@RemoteCameraActivity)}:$at") }
            Live.ref(this@RemoteCameraActivity, "cams/$cam/watchers/${Device.id(this@RemoteCameraActivity)}")?.setValue(m)
            ui.postDelayed(this, 15_000)
        }
    }

    private fun startWatching() {
        if (!online()) { Toast.makeText(this, "The camera is offline", Toast.LENGTH_SHORT).show(); return }
        if (status()?.optBoolean("pinForView") == true && pinFor() == null) { askPin { startWatching() }; return }
        watching = true
        watchStarted = System.currentTimeMillis()
        liveBtn.text = "■ Stop live"
        imageLabel.text = "Starting live view…"
        Live.ref(this, "cams/$cam/watchers/${Device.id(this)}")?.onDisconnect()?.removeValue()
        heartbeat.run()
        liveL = object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                val bytes = LiveNet.openBytes(this@RemoteCameraActivity, s.child("img").value as? String) ?: return
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { image.setImageBitmap(it) }
                val at = (s.child("at").value as? Number)?.toLong() ?: 0
                imageLabel.text = "Live (every 2 s) · " + Fmt.t(prefs, at - Live.serverOffset)
            }
            override fun onCancelled(e: DatabaseError) {}
        }.also { Live.ref(this, "cams/$cam/live")?.addValueEventListener(it) }
    }

    private fun stopWatching() {
        if (!watching) return
        watching = false
        liveBtn.text = "▶ Live view"
        ui.removeCallbacks(heartbeat)
        liveL?.let { Live.ref(this, "cams/$cam/live")?.removeEventListener(it) }
        liveL = null
        Live.ref(this, "cams/$cam/watchers/${Device.id(this)}")?.removeValue()
    }

    private fun requestFrame() {
        if (!online()) { Toast.makeText(this, "The camera is offline", Toast.LENGTH_SHORT).show(); return }
        if (status()?.optBoolean("pinForView") == true && pinFor() == null) { askPin { requestFrame() }; return }
        val req = Live.ref(this, "cams/$cam/snapReq")?.push() ?: return
        val id = req.key ?: return
        val at = Live.now()
        val m = HashMap<String, Any>(); m["by"] = Device.id(this); m["at"] = at
        pinFor()?.let { m["sig"] = CameraLink.sign(cam, it, "snap:$id:$at") }
        imageLabel.text = "Asking for a high-quality frame…"
        val out = Live.ref(this, "cams/$cam/snap/$id") ?: return
        val l = object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                if (!s.exists()) return
                out.removeEventListener(this)
                out.removeValue()
                (s.child("err").value as? String)?.let { imageLabel.text = it; return }
                val bytes = LiveNet.openBytes(this@RemoteCameraActivity, s.child("img").value as? String) ?: return
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { image.setImageBitmap(it) }
                imageLabel.text = "High-quality frame · " + Fmt.t(prefs, System.currentTimeMillis())
            }
            override fun onCancelled(e: DatabaseError) {}
        }
        out.addValueEventListener(l)
        req.setValue(m)
        ui.postDelayed({ out.removeEventListener(l) }, 30_000)
    }

    // ------------------------------------------------------------------ PIN + commands

    private fun pinFor(): String? = SecretStore.get(this, "live.pin.$cam")

    private fun askPin(then: () -> Unit) {
        val input = EditText(this).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD; hint = "PIN" }
        AlertDialog.Builder(this).setTitle("PIN of this camera").setView(input)
            .setMessage("Set on the camera phone (Live connection → Remote control PIN). It is remembered on this phone.")
            .setPositiveButton("OK") { _, _ -> SecretStore.put(this, "live.pin.$cam", input.text.toString().trim()); then() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun send(type: String, args: JSONObject? = null, done: (Boolean) -> Unit = {}) {
        if (!online()) { Toast.makeText(this, "The camera is offline", Toast.LENGTH_SHORT).show(); return }
        if (status()?.optBoolean("pin") == true && pinFor() == null) { askPin { send(type, args, done) }; return }
        val ref = Live.ref(this, "cams/$cam/cmd")?.push() ?: return
        val id = ref.key ?: return
        val d = JSONObject().put("type", type).put("by", Device.id(this)).put("byName", Device.name(this)).put("at", Live.now())
        if (args != null) d.put("args", args)
        val sealed = LiveNet.seal(this, d.toString())
        val m = HashMap<String, Any>(); m["d"] = sealed
        pinFor()?.let { m["sig"] = CameraLink.sign(cam, it, sealed) }
        val res = Live.ref(this, "cams/$cam/cmdResult/$id") ?: return
        res.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                if (!s.exists()) return
                res.removeEventListener(this); res.removeValue()
                val r = LiveNet.open(this@RemoteCameraActivity, s.child("d").value as? String)?.let { JSONObject(it) } ?: return
                val msg = r.optString("msg")
                if (msg == "Wrong PIN") SecretStore.put(this@RemoteCameraActivity, "live.pin.$cam", null)
                Toast.makeText(this@RemoteCameraActivity, msg, Toast.LENGTH_SHORT).show()
                done(r.optBoolean("ok"))
            }
            override fun onCancelled(e: DatabaseError) {}
        })
        ref.setValue(m)
    }

    private fun set(key: String, value: Any) = send("set", JSONObject().put(key, value))

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
        val st = s.optJSONObject("settings") ?: JSONObject()
        val c = caps() ?: JSONObject()
        val enabled = online()

        f.title("Status")
        val mode = s.optString("mode")
        f.line(when {
            !enabled -> "Offline" + (phone?.seen?.takeIf { it > 0 }?.let { " since " + Fmt.t(prefs, it - Live.serverOffset, seconds = false) } ?: "")
            mode == "RECORDING" -> "● Recording (since " + Fmt.t(prefs, s.optLong("recSince"), seconds = false) + ")"
            s.optBoolean("monitoring") -> "Monitoring (watching for motion)"
            else -> "Not monitoring" + s.optString("message").takeIf { it.isNotEmpty() }?.let { " · $it" }.orEmpty()
        }, 15f, if (mode == "RECORDING") Ui.RED else Color.WHITE)
        val lm = s.optLong("lastMotion")
        f.line("Last motion: " + if (lm > 0) Fmt.dateTime(prefs).format(java.util.Date(lm)) else "none yet")
        f.line("Battery ${s.optInt("battery")}%" + (if (s.optBoolean("charging")) " (charging)" else "") +
            "  ·  %.0f °C".format(s.optDouble("tempC")) + "  ·  ${s.optString("lens")}  ·  ${s.optString("format")}")
        if (s.optBoolean("pin")) f.note("This camera needs a PIN for controls" + if (pinFor() != null) " (remembered)." else ".")

        f.title("Monitoring")
        f.row(
            if (s.optBoolean("monitoring")) f.chip("Pause monitoring") { send("pause") } else f.chip("Resume monitoring") { send("resume") },
            f.chip("Stop monitoring") {
                AlertDialog.Builder(this).setTitle("Stop monitoring?")
                    .setMessage("The camera phone stops completely and goes offline. It can only be started again on the phone itself. " +
                        "Use Pause to keep it reachable.")
                    .setPositiveButton("Stop") { _, _ -> send("stop") }.setNegativeButton("Cancel", null).show()
            })

        f.title("Camera")
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
            if (!st.optBoolean("focusAuto", true)) f.slider("Focus distance", 0, 100, (st.optDouble("focusPos") / minFocus * 100).roundToInt(),
                { if (it == 0) "far (∞)" else "%.2f m".format(1 / (minFocus * it / 100)) }, enabled) { set("focusPos", minFocus * it / 100) }
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
