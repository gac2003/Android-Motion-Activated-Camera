package com.motioncam.remote

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.motioncam.IconDrawable
import com.motioncam.Ui
import com.motioncam.cloud.CloudKind
import com.motioncam.cloud.RemoteFolder
import com.motioncam.cloud.isOurClip
import com.motioncam.iconButton

/**
 * Choosing a cloud folder, like a file manager's "choose folder": where you are as a path at the top (tap any part to
 * go back there), the current folder with how many MotionCam clips are in it and **Use this folder**, the folders
 * inside it, and **New folder here** at the bottom. Back goes up one level.
 */
class FolderPicker(
    private val a: Activity,
    private val draft: RemoteSource,
    private val onPick: (RemoteFolder, Int) -> Unit,
) {
    private val dialog = Dialog(a, android.R.style.Theme_Material_NoActionBar).apply { requestWindowFeature(Window.FEATURE_NO_TITLE) }
    private val trail = ArrayList<RemoteFolder>()   // the folders from the top down to the current one
    private val crumbs = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val crumbScroll = HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false; addView(crumbs) }
    private val current = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
    private val list = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
    private var loadSeq = 0

    private fun d(v: Int) = Ui.dp(a, v)
    private val rootName = when (draft.kind) {
        CloudKind.DRIVE -> "Google Drive"
        CloudKind.DROPBOX -> "Dropbox (app folder)"
        else -> draft.host.ifEmpty { "Server" }
    }

    fun show() {
        val root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.BG) }
        // top bar: close, title and which account
        val top = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(d(4), d(8), d(16), d(4)) }
        top.addView(iconButton(a, IconDrawable.Kind.CLOSE, 48, 12) { dialog.dismiss() }.apply { contentDescription = "Cancel" })
        val tt = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(d(4), 0, 0, 0) }
        tt.addView(Ui.label(a, "Choose a folder", 20f).apply { setTypeface(typeface, Typeface.BOLD) })
        tt.addView(Ui.label(a, draft.describe, 12f, Ui.GREY).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        top.addView(tt, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(top)
        // where you are: a path of tappable folders
        root.addView(crumbScroll, LinearLayout.LayoutParams(-1, -2).apply { setMargins(d(16), d(4), d(16), d(8)) })
        val body = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(d(16), 0, d(16), d(16)) }
        body.addView(current)
        body.addView(list)
        root.addView(ScrollView(a).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        // bottom: a new folder inside the one shown
        val bottom = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(d(16), d(10), d(16), d(16)); setBackgroundColor(0xFF1A1C20.toInt())
        }
        bottom.addView(Ui.label(a, "+  New folder here", 15f).apply {
            gravity = Gravity.CENTER; setPadding(0, d(12), 0, d(12)); setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable().apply { cornerRadius = d(24).toFloat(); setStroke(d(1), 0xFF4A4E55.toInt()) }
            isClickable = true
            setOnClickListener { newFolder() }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(bottom)
        dialog.setContentView(root)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        dialog.setOnKeyListener { _, code, ev ->
            if (code == android.view.KeyEvent.KEYCODE_BACK && ev.action == android.view.KeyEvent.ACTION_UP) {
                if (trail.isEmpty()) dialog.dismiss() else go(trail.size - 1)
                true
            } else code == android.view.KeyEvent.KEYCODE_BACK
        }
        dialog.show()
        load()
    }

    private val here: RemoteFolder? get() = trail.lastOrNull()

    /** Goes to the folder at [depth] in the path (0 = the top). */
    private fun go(depth: Int) {
        while (trail.size > depth) trail.removeAt(trail.size - 1)
        load()
    }

    private fun open(f: RemoteFolder) { trail += f; load() }

    private fun renderCrumbs() {
        crumbs.removeAllViews()
        val names = listOf(rootName) + trail.map { it.name }
        names.forEachIndexed { i, n ->
            if (i > 0) crumbs.addView(Ui.label(a, "›", 16f, Ui.GREY).apply { setPadding(d(6), 0, d(6), 0) })
            val last = i == names.lastIndex
            crumbs.addView(Ui.label(a, n, 14f, if (last) Ui.ACCENT else 0xFFC4C8CE.toInt()).apply {
                if (last) setTypeface(typeface, Typeface.BOLD)
                setPadding(d(8), d(6), d(8), d(6))
                background = GradientDrawable().apply { cornerRadius = d(14).toFloat(); setColor(if (last) 0xFF2A2416.toInt() else 0xFF1E2126.toInt()) }
                contentDescription = if (last) "Current folder: $n" else "Go to $n"
                if (!last) { isClickable = true; setOnClickListener { go(i) } }
            })
        }
        crumbScroll.post { crumbScroll.fullScroll(View.FOCUS_RIGHT) }
    }

    private fun load() {
        val seq = ++loadSeq
        renderCrumbs()
        current.removeAllViews()
        list.removeAllViews()
        currentCard(null, null)
        list.addView(LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(d(4), d(16), 0, d(16))
            addView(ProgressBar(a), LinearLayout.LayoutParams(d(24), d(24)))
            addView(Ui.label(a, "Loading folders…", 14f, Ui.GREY).apply { setPadding(d(12), 0, 0, 0) })
        })
        val parent = here
        Thread {
            try {
                val (folders, clips) = RemoteSources.reader(a, draft).use { r ->
                    val fs = r.listFolders(parent?.id).filter { !it.name.startsWith(".") }.sortedBy { it.name.lowercase() }
                    fs to (parent?.let { p -> r.list(p.id).count { isOurClip(it.name) } })
                }
                a.runOnUiThread { if (seq == loadSeq && dialog.isShowing) showFolders(folders, clips) }
            } catch (e: Exception) {
                a.runOnUiThread { if (seq == loadSeq && dialog.isShowing) showError(e.message ?: "Could not list folders") }
            }
        }.start()
    }

    /** The folder you're in: its name, what's in it and Use this folder (not at the very top). */
    private fun currentCard(clips: Int?, folders: Int?) {
        current.removeAllViews()
        val f = here
        val card = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d(14), d(12), d(14), d(14))
            background = GradientDrawable().apply { setColor(0xFF1E2126.toInt()); cornerRadius = d(14).toFloat() }
        }
        val head = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(ImageView(a).apply { setImageDrawable(IconDrawable(IconDrawable.Kind.FOLDER, Ui.ACCENT)) }, LinearLayout.LayoutParams(d(26), d(26)))
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(d(12), 0, 0, 0) }
        col.addView(Ui.label(a, "CURRENT FOLDER", 11f, Ui.GREY).apply { letterSpacing = 0.06f; setTypeface(typeface, Typeface.BOLD) })
        col.addView(Ui.label(a, f?.name ?: rootName, 17f).apply { setTypeface(typeface, Typeface.BOLD); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
        val info = when {
            f == null -> if (draft.kind == CloudKind.DRIVE) "MotionCam only sees folders it created in ${draft.account ?: "this account"}. Open one below, or make a new one."
                else "Open a folder below, or make a new one."
            clips == null -> "Checking…"
            else -> (if (clips == 0) "No MotionCam clips yet" else "$clips MotionCam clip${if (clips == 1) "" else "s"}") +
                (folders?.let { " · $it folder${if (it == 1) "" else "s"} inside" } ?: "")
        }
        col.addView(Ui.label(a, info, 12f, 0xFFB0B4BA.toInt()).apply { setPadding(0, d(2), 0, 0) })
        head.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(head)
        if (f != null) card.addView(Ui.label(a, "Use this folder", 15f, 0xFF15171B.toInt()).apply {
            gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD); setPadding(0, d(12), 0, d(12))
            background = GradientDrawable().apply { cornerRadius = d(24).toFloat(); setColor(if (clips != null) Ui.ACCENT else 0xFF6B5A1E.toInt()) }
            isClickable = clips != null
            setOnClickListener { dialog.dismiss(); onPick(f, clips ?: 0) }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = d(12) })
        current.addView(card)
    }

    private fun showFolders(folders: List<RemoteFolder>, clips: Int?) {
        currentCard(clips, folders.size)
        list.removeAllViews()
        list.addView(Ui.label(a, if (here == null) "FOLDERS" else "FOLDERS IN ${here!!.name.uppercase()}", 12f, Ui.GREY).apply {
            letterSpacing = 0.06f; setTypeface(typeface, Typeface.BOLD); setPadding(d(4), d(20), 0, d(6))
        })
        if (folders.isEmpty()) {
            list.addView(Ui.label(a, "No folders here. Use this folder, or make a new one with the button below.", 13f, Ui.GREY).apply { setPadding(d(4), d(8), d(4), d(8)) })
            return
        }
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(0xFF1E2126.toInt()); cornerRadius = d(14).toFloat() }
        }
        folders.forEachIndexed { i, f ->
            if (i > 0) box.addView(View(a).apply { setBackgroundColor(0xFF2A2D33.toInt()) }, LinearLayout.LayoutParams(-1, d(1)).apply { leftMargin = d(52) })
            box.addView(LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(d(14), d(13), d(12), d(13))
                isClickable = true
                val tv = android.util.TypedValue()
                a.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                if (tv.resourceId != 0) setBackgroundResource(tv.resourceId)
                contentDescription = "Open ${f.name}"
                setOnClickListener { open(f) }
                addView(ImageView(a).apply { setImageDrawable(IconDrawable(IconDrawable.Kind.FOLDER, 0xFFC4C8CE.toInt())) }, LinearLayout.LayoutParams(d(24), d(24)))
                addView(Ui.label(a, f.name, 15f).apply { setPadding(d(14), 0, d(8), 0); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END },
                    LinearLayout.LayoutParams(0, -2, 1f))
                addView(Ui.label(a, "›", 20f, Ui.GREY))
            })
        }
        list.addView(box)
    }

    private fun showError(msg: String) {
        currentCard(null, null)
        list.removeAllViews()
        list.addView(Ui.label(a, "Couldn't load this folder: $msg", 13f, 0xFFFF8A85.toInt()).apply { setPadding(d(4), d(16), d(4), d(8)) })
        list.addView(Ui.label(a, "Try again", 14f, Ui.ACCENT).apply {
            setPadding(d(4), d(8), d(4), d(8)); isClickable = true; setOnClickListener { load() }
        })
    }

    private fun newFolder() {
        val input = EditText(a).apply { setText("MotionCam"); setSelectAllOnFocus(true); setSingleLine() }
        val wrap = FrameLayout(a).apply { setPadding(d(20), d(4), d(20), 0); addView(input) }
        AlertDialog.Builder(a).setTitle("New folder in " + (here?.name ?: rootName)).setView(wrap)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim().ifEmpty { "MotionCam" }
                val parent = here
                Thread {
                    try {
                        val f = RemoteSources.reader(a, draft).use { it.createFolder(parent?.id, name) }
                        a.runOnUiThread { dialog.dismiss(); onPick(f, 0) }
                    } catch (e: Exception) { a.runOnUiThread { Toast.makeText(a, "Could not create the folder: ${e.message}", Toast.LENGTH_LONG).show() } }
                }.start()
            }
            .setNegativeButton("Cancel", null).show()
    }
}
