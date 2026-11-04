package com.motioncam

import android.app.Activity
import android.app.AlertDialog
import android.text.InputType
import android.widget.EditText
import android.widget.Toast

/** "Add to…" dialog: choose a collection (or create one) for the given clips. */
object CollectionPicker {
    fun show(a: Activity, clipIds: List<Long>, store: CollectionStore = Collections, done: () -> Unit) {
        val cols = store.all(a)
        val opts = ArrayList<Opt>()
        for (c in cols) {
            val inAll = c.clipIds.containsAll(clipIds)
            val n = c.clipIds.size
            opts += Opt((if (c.isFavourites) "★ " else "") + c.name,
                "$n clip${if (n == 1) "" else "s"}" + if (inAll) " · already in it" else "",
                icon = if (c.isFavourites) null else IconDrawable.Kind.FOLDER, checked = inAll) {
                store.add(a, c.id, clipIds)
                Toast.makeText(a, "Added to ${c.name}", Toast.LENGTH_SHORT).show()
                done()
            }
        }
        opts += Opt("New collection…", "Create one and add the clips", section = "New") {
            askName(a, "New collection", "") { name ->
                val c = store.create(a, name)
                store.add(a, c.id, clipIds)
                Toast.makeText(a, "Added to ${c.name}", Toast.LENGTH_SHORT).show()
                done()
            }
        }
        OptionSheet.show(a, if (clipIds.size == 1) "Add clip to" else "Add ${clipIds.size} clips to", opts)
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
