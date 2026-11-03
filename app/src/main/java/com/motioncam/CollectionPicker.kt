package com.motioncam

import android.app.Activity
import android.app.AlertDialog
import android.text.InputType
import android.widget.EditText
import android.widget.Toast

/** "Add to…" dialog: choose a collection (or create one) for the given clips. */
object CollectionPicker {
    fun show(a: Activity, clipIds: List<Long>, done: () -> Unit) {
        val cols = Collections.all(a)
        val names = cols.map { c ->
            val inAll = c.clipIds.containsAll(clipIds)
            (if (c.isFavourites) "★ " else "") + c.name + "  (${c.clipIds.size})" + if (inAll) "  ✓" else ""
        } + "＋ New collection…"
        AlertDialog.Builder(a).setTitle(if (clipIds.size == 1) "Add clip to" else "Add ${clipIds.size} clips to")
            .setItems(names.toTypedArray()) { _, which ->
                if (which < cols.size) {
                    Collections.add(a, cols[which].id, clipIds)
                    Toast.makeText(a, "Added to ${cols[which].name}", Toast.LENGTH_SHORT).show()
                    done()
                } else askName(a, "New collection", "") { name ->
                    val c = Collections.create(a, name)
                    Collections.add(a, c.id, clipIds)
                    Toast.makeText(a, "Added to ${c.name}", Toast.LENGTH_SHORT).show()
                    done()
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    fun askName(a: Activity, title: String, initial: String, ok: (String) -> Unit) {
        val input = EditText(a).apply {
            setText(initial)
            hint = "Name"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSelectAllOnFocus(true)
        }
        AlertDialog.Builder(a).setTitle(title).setView(input)
            .setPositiveButton("OK") { _, _ -> ok(input.text.toString()) }
            .setNegativeButton("Cancel", null).show()
    }
}
