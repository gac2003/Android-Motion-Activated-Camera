package com.motioncam.remote

import android.app.Activity
import android.app.AlertDialog
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.motioncam.CollectionPicker
import com.motioncam.Fmt
import com.motioncam.Prefs
import com.motioncam.Ui
import com.motioncam.topBar
import java.util.Date

/**
 * Sync status of a remote folder's shared collections: what is waiting to be sent, conflicts the app could not
 * settle by itself (each with clear choices), clips missing from the cloud, and last-resort tools.
 */
class SyncStatusActivity : Activity() {
    companion object { const val EXTRA_SOURCE = "source" }

    private lateinit var srcId: String
    private lateinit var box: LinearLayout
    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        srcId = intent.getStringExtra(EXTRA_SOURCE) ?: run { finish(); return }
        val src = RemoteSources.get(this, srcId) ?: run { finish(); return }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.BG) }
        root.addView(topBar(this, "Sync · ${src.name}", Ui.chip(this, "Sync now").apply {
            setOnClickListener { text = "Syncing…"; SharedSync.schedule(this@SyncStatusActivity, srcId, 0) }
        }))
        box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; val p = Ui.dp(this@SyncStatusActivity, 16); setPadding(p, 0, p, p * 2) }
        root.addView(ScrollView(this).apply { addView(box) }, LinearLayout.LayoutParams(-1, 0, 1f))
        Ui.capWidth(box)
        setContentView(root)
    }

    private val synced: (String) -> Unit = { s -> if (s == srcId) runOnUiThread { recreateContent() } }

    override fun onResume() {
        super.onResume()
        SharedSync.listeners += synced
        recreateContent()
        SharedSync.schedule(this, srcId, 0)
    }

    override fun onPause() { SharedSync.listeners -= synced; super.onPause() }

    private fun recreateContent() {
        if (isFinishing) return
        box.removeAllViews()
        val s = SharedSync.state(this, srcId)
        val editable = RemoteSources.get(this, srcId)?.mode != RemoteSources.MODE_LINK

        title("Status")
        val waiting = s.outbox.count { !it.held }
        line(when {
            s.lastSync == 0L && s.lastError != null -> "Not synced yet: ${s.lastError}"
            s.lastSync == 0L -> "Not synced yet"
            else -> "Last synced " + Fmt.t(prefs, s.lastSync, seconds = false) +
                (if (System.currentTimeMillis() - s.lastSync > 86_400_000L) " (" + java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault()).format(Date(s.lastSync)) + ")" else "") +
                (s.lastError?.let { "\nLast attempt failed: $it (changes are kept and sent later)" } ?: "")
        })
        line(if (waiting == 0) "All changes sent." else "$waiting change${if (waiting == 1) "" else "s"} waiting to be sent.")
        if (!editable) note("Opened through a viewer link: collections can be seen here but only changed by the account's own phones.")

        if (s.conflicts.isNotEmpty()) {
            title("Needs your decision (${s.conflicts.size})")
            note("These changes clash with what another phone did. Nothing is lost while you decide: your change is held, " +
                "the other phone's version stays as it is.")
            for (k in s.conflicts.toList()) conflictCard(k)
        }

        val missing = s.missing.keys.toList()
        if (missing.isNotEmpty()) {
            title("Missing from the cloud (${missing.size})")
            note("These clips are in collections but no longer in the folder (deleted, or the folder was changed). They are shown " +
                "as \"Missing\" tiles and removed from collections automatically after ${SharedSync.MISSING_DAYS} days.")
            if (editable) button("Remove them from all collections now") {
                confirm("Remove ${missing.size} missing clip${if (missing.size == 1) "" else "s"} from all collections?", "Only the collection entries are removed.") {
                    SharedSync.removeEverywhere(this, srcId, missing); recreateContent()
                }
            }
        }

        val ops = s.outbox
        if (ops.isNotEmpty()) {
            title("Waiting to be sent (${ops.size})")
            for (op in ops.take(60)) {
                val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                r.addView(Ui.label(this, (if (op.held) "⏸ " else "• ") + SharedSync.describe(this, srcId, op) +
                    (if (op.fails > 0 && !op.held) "  (failed ${op.fails}×, retrying)" else ""), 13f), LinearLayout.LayoutParams(0, -2, 1f))
                r.addView(Ui.label(this, "Discard", 12f, Ui.GREY).apply {
                    setPadding(Ui.dp(this@SyncStatusActivity, 12), Ui.dp(this@SyncStatusActivity, 8), 0, Ui.dp(this@SyncStatusActivity, 8))
                    setOnClickListener { SharedSync.discardOp(this@SyncStatusActivity, srcId, op.id); recreateContent() }
                })
                box.addView(r)
            }
            if (ops.size > 60) note("…and ${ops.size - 60} more")
        }

        title("If something looks wrong")
        note("Last-resort tools, like in other syncing apps. Use them only if the collections here and on your other phones don't match.")
        button("Discard all changes not sent yet") {
            confirm("Discard ${ops.size} unsent change${if (ops.size == 1) "" else "s"}?", "This phone shows the cloud's collections again. Changes already sent are not affected.") {
                SharedSync.discardUnsent(this, srcId); recreateContent()
            }
        }
        button("Re-download collections from the cloud") {
            confirm("Re-download everything?", "This phone forgets its copy of the shared collections (and unsent changes) and reads them from the cloud again.") {
                SharedSync.redownload(this, srcId); recreateContent()
            }
        }
        if (editable) button("Re-upload this phone's collections") {
            confirm("Re-upload this phone's collections?", "Sends every collection and clip this phone shows to the cloud again. It only adds " +
                "(merges with what is there) and never removes anything. Useful if collections were lost in the cloud.") {
                SharedSync.reupload(this, srcId); recreateContent()
            }
        }
    }

    private fun conflictCard(k: SharedSync.Conflict) {
        val o = k.o
        val (text, choices) = when (k.kind) {
            "rename" -> Pair("A collection was renamed to “${o.optString("theirs")}” on another phone while you renamed it to “${o.optString("mine")}”.",
                listOf("Keep “${o.optString("mine")}”" to "mine", "Keep “${o.optString("theirs")}”" to "theirs"))
            "deleted" -> Pair("“${o.optString("name")}” was deleted by ${o.optString("by")} while you added ${o.getJSONArray("ops").length()} clip(s) to it.",
                listOf("Restore it with my clips" to "restore", "Discard my additions" to "discard"))
            "deleteVsAdd" -> Pair("You deleted “${o.optString("name")}”, but ${o.optString("by")} added ${o.optInt("count")} clip(s) to it since.",
                listOf("Delete anyway" to "delete", "Keep the collection" to "keep"))
            "duplicate" -> Pair("There are ${o.getJSONArray("ids").length()} collections called “${o.optString("name")}” (made on different phones).",
                listOf("Merge into one" to "merge", "Keep both" to "keep", "Rename one…" to "rename"))
            "failed" -> Pair("“${o.optString("what")}” keeps failing: ${o.optString("error")}", listOf("Try again" to "retry", "Discard this change" to "discard"))
            else -> Pair(k.kind, listOf("Dismiss" to "keep"))
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = Ui.dp(this@SyncStatusActivity, 12)
            setPadding(p, p, p, p)
            background = GradientDrawable().apply { setColor(0xFF2A2416.toInt()); cornerRadius = Ui.dp(this@SyncStatusActivity, 10).toFloat() }
        }
        card.addView(Ui.label(this, "⚠ $text", 14f))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, Ui.dp(this@SyncStatusActivity, 8), 0, 0) }
        for ((label, choice) in choices) row.addView(Ui.chip(this, label).apply {
            setOnClickListener {
                if (choice == "rename") CollectionPicker.askName(this@SyncStatusActivity, "New name", o.optString("name") + " 2") { n ->
                    SharedSync.resolve(this@SyncStatusActivity, srcId, k.id, "rename", n); recreateContent()
                } else { SharedSync.resolve(this@SyncStatusActivity, srcId, k.id, choice); recreateContent() }
            }
        }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = Ui.dp(this@SyncStatusActivity, 6) })
        card.addView(android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) })
        box.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(this@SyncStatusActivity, 8) })
    }

    private fun confirm(title: String, msg: String, ok: () -> Unit) {
        AlertDialog.Builder(this).setTitle(title).setMessage(msg).setPositiveButton("OK") { _, _ -> ok() }.setNegativeButton("Cancel", null).show()
    }

    private fun title(s: String) {
        box.addView(Ui.label(this, s.uppercase(), 13f, Ui.ACCENT).apply { setPadding(0, Ui.dp(this@SyncStatusActivity, 20), 0, Ui.dp(this@SyncStatusActivity, 4)) })
    }

    private fun line(s: String) { box.addView(Ui.label(this, s, 14f).apply { setPadding(0, Ui.dp(this@SyncStatusActivity, 2), 0, Ui.dp(this@SyncStatusActivity, 2)) }) }

    private fun note(s: String) {
        box.addView(Ui.label(this, s, 12f, 0xFFB0B4BA.toInt()).apply { setPadding(0, Ui.dp(this@SyncStatusActivity, 4), 0, Ui.dp(this@SyncStatusActivity, 4)) })
    }

    private fun button(label: String, click: () -> Unit) {
        box.addView(Ui.chip(this, label).apply { setOnClickListener { click() } },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(this@SyncStatusActivity, 8) })
    }
}
