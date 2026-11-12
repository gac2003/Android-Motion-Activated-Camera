package com.motioncam

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * What MotionCam is doing on this phone right now, for troubleshooting (Settings › About & help › Diagnostics): what
 * runs in the background and why, the camera, the connections, live video, memory, battery and heat, a few counters,
 * and the end of the camera log. Gathered only while the screen is open; the counters are a number each, added to when
 * the event happens anyway. Copy pastes it all as text.
 */
object Diagnostics {
    private val counters = ConcurrentHashMap<String, AtomicLong>()
    private val since = System.currentTimeMillis()

    /** One more of [what] (e.g. "push received"); free when nobody looks. */
    fun count(what: String) { counters.getOrPut(what) { AtomicLong() }.incrementAndGet() }

    fun report(ctx: Context): String {
        val p = Prefs(ctx)
        val b = StringBuilder()
        fun line(k: String, v: Any?) { b.append(k.padEnd(28)).append(v).append('\n') }
        fun head(t: String) { b.append('\n').append("== ").append(t).append(" ==\n") }
        fun yes(v: Boolean) = if (v) "yes" else "no"
        fun ago(t: Long) = if (t <= 0) "never" else ((System.currentTimeMillis() - t) / 60_000).let { if (it < 1) "just now" else "$it min ago" }

        val ver = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName } catch (_: Exception) { "?" }
        b.append("MotionCam $ver · Android ${android.os.Build.VERSION.RELEASE} · ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n")
        b.append(java.text.SimpleDateFormat("EEE d MMM HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())).append('\n')

        head("This phone's role")
        line("Live connection", if (!com.motioncam.live.LiveConfig.configured) "not in this build" else if (p.liveOn) "on" else "off")
        line("Camera role", yes(p.liveCamera))
        line("PIN set", yes(com.motioncam.live.CameraLink.hasPin(ctx)))
        line("Standby allowed", yes(CameraService.standbyAllowed(ctx)) + " (last monitored ${ago(p.lastMonitoredAt)})")
        line("Remote start ready", yes(com.motioncam.live.Push.remoteStartReady(ctx)))
        line("Stay connected", yes(p.liveStay))
        line("Push: receive / send", yes(com.motioncam.live.Push.token(ctx) != null) + " / " + yes(com.motioncam.live.Push.canSend))
        line("Push covers all cameras", yes(com.motioncam.live.Push.covers(ctx)))
        line("Smooth live video", if (!com.motioncam.live.Rtc.enabled) "not in this build" else if (p.liveSmooth) "on" else "off")

        head("Running in the background now")
        val svc = CameraService.instance
        line("Camera service", when { svc == null -> "not running"; CameraService.inStandby -> "standby"; CameraService.watching -> "monitoring"; else -> "on, not monitoring" })
        svc?.autoOffAt?.takeIf { it > 0 }?.let { line("  turns off in", "${(it - System.currentTimeMillis()) / 60_000} min") }
        line("Stay-connected service", yes(com.motioncam.live.LiveService.running))
        line("MotionCam on screen", yes(MotionCamApp.foreground))
        line("Live: viewer / camera", yes(com.motioncam.live.Live.viewerActive) + " / " + yes(com.motioncam.live.Live.cameraActive))
        line("Camera duty (quiet)", yes(com.motioncam.live.Live.cameraDuty))
        line("Following cameras", com.motioncam.live.Live.watchedCount())
        line("Monitoring wanted", yes(Health.wanted(ctx)) + " (alive ${ago(Health.aliveAt(ctx))})")
        val js = ctx.getSystemService(android.app.job.JobScheduler::class.java)
        line("Scheduled checks", js?.allPendingJobs?.joinToString { when (it.id) { 4207 -> "alerts 15 min"; 4208 -> "camera watchdog"; else -> "job ${it.id}" } }?.ifEmpty { "none" })
        val q = try { com.motioncam.cloud.CloudSync.counts(ctx, null) } catch (_: Exception) { null }
        line("Uploads waiting / failed", q?.let { "${it.queued} / ${it.failed}" })
        line("Transfers running", try { com.motioncam.remote.TransferQueue.pending(ctx) } catch (_: Exception) { "?" })

        head("Camera")
        val e = EngineHost.peek()
        line("Camera users", EngineHost.holdersText().ifEmpty { "none (closed)" })
        if (e != null) {
            val s = StatusBus.status
            line("Mode", s.mode.name + (s.message.takeIf { it.isNotEmpty() }?.let { " · $it" } ?: ""))
            line("Format", s.format)
            line("Low-power idle", yes(e.lowPowerNow))
        }
        line("Live video viewers here", com.motioncam.live.RtcCamera.viewers())
        line("Frames sent as video", com.motioncam.live.RtcCamera.frames())
        line("Web viewer clients", FrameHub.clients)

        head("Phone")
        val bat = ctx.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val pct = bat?.let { it.getIntExtra("level", -1) * 100 / it.getIntExtra("scale", 100).coerceAtLeast(1) }
        val temp = (bat?.getIntExtra("temperature", 0) ?: 0) / 10f
        val plugged = (bat?.getIntExtra("plugged", 0) ?: 0) != 0
        line("Battery", "$pct%" + (if (plugged) " · charging" else "") + " · %.1f °C".format(temp))
        val th = try { (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus } catch (_: Exception) { -1 }
        line("Android heat level", listOf("none", "light", "moderate", "severe", "critical", "emergency", "shutdown").getOrNull(th) ?: "?")
        line("Battery optimisation", if ((ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(ctx.packageName)) "exempt" else "on (Android may stop MotionCam)")
        val rt = Runtime.getRuntime()
        line("Memory (app)", "${(rt.totalMemory() - rt.freeMemory()) / 1_048_576} MB of ${rt.maxMemory() / 1_048_576} MB · native ${android.os.Debug.getNativeHeapAllocatedSize() / 1_048_576} MB")
        line("Threads", Thread.activeCount())
        line("Free storage", "${ClipStore.freeBytes() / 1_000_000_000} GB")
        line("Running for", "${(System.currentTimeMillis() - since) / 60_000} min (since MotionCam started)")

        head("Counts since MotionCam started")
        if (counters.isEmpty()) b.append("nothing yet\n")
        for ((k, v) in counters.toSortedMap()) line(k, v.get())

        head("Camera log (newest first, last 25)")
        b.append(Health.text(ctx).lines().filter { it.isNotBlank() }.takeLast(25).reversed().joinToString("\n")).append('\n')
        return b.toString()
    }
}

/** Settings › About & help › Diagnostics: the report, refreshed every 2 s while open; Copy / Share. */
class DiagnosticsActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var tv: TextView
    private val tick = object : Runnable { override fun run() { tv.text = Diagnostics.report(this@DiagnosticsActivity); ui.postDelayed(this, 2000) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = { v: Int -> Ui.dp(this, v) }
        val copy = Ui.chip(this, "Copy").apply {
            setOnClickListener {
                (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("MotionCam diagnostics", Diagnostics.report(this@DiagnosticsActivity)))
                Toast.makeText(this@DiagnosticsActivity, "Copied", Toast.LENGTH_SHORT).show()
            }
        }
        val share = Ui.chip(this, "Share").apply {
            setOnClickListener {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, Diagnostics.report(this@DiagnosticsActivity)), "Share diagnostics"))
            }
        }
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; addView(copy); addView(share, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = d(8) }) }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.BG) }
        root.addView(topBar(this, "Diagnostics", actions))
        tv = Ui.label(this, "", 11f, 0xFFDDDDDD.toInt()).apply {
            typeface = android.graphics.Typeface.MONOSPACE; setTextIsSelectable(true)
            setPadding(d(14), d(8), d(14), d(24))
        }
        root.addView(ScrollView(this).apply { addView(tv) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    override fun onResume() { super.onResume(); ui.post(tick) }
    override fun onPause() { ui.removeCallbacks(tick); super.onPause() }
}
