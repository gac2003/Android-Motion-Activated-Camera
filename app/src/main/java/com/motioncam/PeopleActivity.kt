package com.motioncam

import android.app.Activity
import android.app.AlertDialog
import android.graphics.BitmapFactory
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.motioncam.ai.People
import com.motioncam.remote.RemoteSources

/**
 * People (Settings › Camera › People): the faces a camera has grouped, for this phone's own clips or for a remote
 * folder. Tap a person to name them, say they are the same person as another group, or remove them. Names show on
 * the clips they are in ("Seen in clip" in Search) on every phone viewing the folder.
 */
class PeopleActivity : Activity() {
    private var scope: String? = null
    private lateinit var box: LinearLayout
    private lateinit var status: TextView
    private lateinit var scopes: LinearLayout
    private var loading = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = { v: Int -> Ui.dp(this, v) }
        val sources = RemoteSources.all(this)
        // this phone's own people when it has any, else the first remote folder
        scope = if (People.localCount(this) > 0 || sources.isEmpty()) null else sources.first().id
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.BG) }
        root.addView(topBar(this, "People"))
        scopes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(d(14), d(4), d(14), d(8)) }
        root.addView(HorizontalScrollView(this).apply { addView(scopes); isHorizontalScrollBarEnabled = false })
        status = Ui.label(this, "", 12f, Ui.GREY).apply { setPadding(d(16), 0, d(16), d(8)) }
        root.addView(status)
        box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(d(14), 0, d(14), d(24)) }
        root.addView(ScrollView(this).apply { addView(box) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        paintScopes()
        load(true)
    }

    private fun paintScopes() {
        scopes.removeAllViews()
        val all = listOf<Pair<String?, String>>(null to "This phone") + RemoteSources.all(this).map { it.id to it.name }
        for ((id, name) in all) scopes.addView(Ui.chip(this, name).apply {
            Ui.setChipState(this, id == scope)
            setOnClickListener { scope = id; paintScopes(); load(true) }
        }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = Ui.dp(this@PeopleActivity, 6) })
    }

    /** Shows the people of the chosen place; [refresh] fetches a remote folder's now (off the main thread). */
    private fun load(refresh: Boolean) {
        val mine = ++loading
        val sc = scope
        status.text = if (sc != null && refresh) "Reading the folder…" else ""
        val app = applicationContext
        Thread {
            val list = try { People.list(app, sc, refresh) } catch (_: Exception) { emptyList() }
            runOnUiThread { if (mine == loading && !isFinishing) render(list) }
        }.start()
    }

    private fun label(g: People.Group) = People.nameOf(this, scope, g.id) ?: "Unnamed"

    private fun render(list: List<People.Group>) {
        box.removeAllViews()
        status.text = when {
            list.isNotEmpty() -> "${list.size} ${if (list.size == 1) "person" else "people"} · tap one to name, merge or remove"
            scope == null -> "No faces yet. Turn on Recognise faces (and Describe clips) in Settings › Camera on the phone that records; " +
                "people appear here after clips with a clear face have been described."
            else -> "No faces in this folder yet (they come from a camera with Recognise faces on)."
        }
        val d = { v: Int -> Ui.dp(this, v) }
        val fmt = java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault())
        for (g in list) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(0, d(8), 0, d(8)); isClickable = true
                setOnClickListener { actions(g, list) }
            }
            row.addView(ImageView(this).apply {
                try { setImageBitmap(BitmapFactory.decodeByteArray(g.thumb, 0, g.thumb.size)) } catch (_: Exception) {}
                scaleType = ImageView.ScaleType.CENTER_CROP
            }, LinearLayout.LayoutParams(d(64), d(64)))
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(d(14), 0, 0, 0) }
            val named = People.nameOf(this, scope, g.id) != null
            col.addView(Ui.label(this, label(g), 16f, if (named) 0xFFFFFFFF.toInt() else Ui.GREY))
            col.addView(Ui.label(this, "seen in ${g.seen} clip${if (g.seen == 1) "" else "s"} · last ${fmt.format(java.util.Date(g.last))}", 12f, Ui.GREY))
            row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
            box.addView(row)
        }
    }

    private fun actions(g: People.Group, all: List<People.Group>) {
        val others = all.filter { it.id != g.id }
        val items = arrayListOf("Name…")
        if (others.isNotEmpty()) items += "Same person as…"
        items += "Remove"
        AlertDialog.Builder(this).setTitle(label(g)).setItems(items.toTypedArray()) { _, i ->
            when (items[i]) {
                "Name…" -> askName(g)
                "Same person as…" -> AlertDialog.Builder(this).setTitle("Same person as")
                    .setItems(others.map { o -> label(o) + " · ${o.seen} clip${if (o.seen == 1) "" else "s"}" }.toTypedArray()) { _, j -> apply(g, "=" + others[j].id) }
                    .setNegativeButton("Cancel", null).show()
                else -> AlertDialog.Builder(this).setTitle("Remove ${label(g)}?")
                    .setMessage("Their face samples and name are removed. Clips stay; a later clear face of them starts a new unnamed person.")
                    .setPositiveButton("Remove") { _, _ -> apply(g, "!") }.setNegativeButton("Cancel", null).show()
            }
        }.show()
    }

    private fun askName(g: People.Group) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setText(People.nameOf(this@PeopleActivity, scope, g.id) ?: ""); hint = "Name (empty = no name)"
        }
        AlertDialog.Builder(this).setTitle("Who is this?").setView(input)
            .setPositiveButton("OK") { _, _ -> apply(g, TagStore.clean(input.text.toString()).replace("~", "-").trimStart('=', '!', '@')) }
            .setNegativeButton("Cancel", null).show()
    }

    /** Names / merges / removes (the folder is told off the main thread), then shows the list again. */
    private fun apply(g: People.Group, value: String) {
        val sc = scope
        val app = applicationContext
        status.text = "Saving…"
        Thread {
            val problem = try { People.setName(app, sc, g.id, value) } catch (e: Exception) { e.message }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (problem != null) Toast.makeText(this, problem, Toast.LENGTH_LONG).show()
                load(false)
            }
        }.start()
    }
}
