package com.motioncam

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * What Search shows: a date range, tags, collections, length and file size.
 *  - Tags and collections have three states, like many tag filters on the web: tap once to include, again to exclude,
 *    once more to clear. Included ones match **any** (default) or **all** of them; excluded ones always hide a clip.
 *  - Different kinds of filters must **all** match (the usual rule), or **any** of them ([matchAny]); exclusions
 *    still always apply.
 * Each view (this phone, each remote folder, each collection) keeps its own filter, saved on the phone, so switching
 * between this phone and a remote folder (or leaving the app) keeps it.
 */
class ClipFilter {
    /** "today", "yesterday", "7d", "30d" (moves with the date), or null for [from]..[to]. */
    var datePreset: String? = null
    /** Custom range: first and last day (start of day, inclusive); null = open. */
    var from: Long? = null
    var to: Long? = null
    val tagsIn = sortedSet()
    val tagsOut = sortedSet()
    var tagsAll = false
    val colsIn = LinkedHashSet<String>()
    val colsOut = LinkedHashSet<String>()
    var colsAll = false
    /** Length in seconds, file size in MB; null = no limit. */
    var minSec: Int? = null
    var maxSec: Int? = null
    var minMb: Int? = null
    var maxMb: Int? = null
    var matchAny = false

    val hasDate get() = datePreset != null || from != null || to != null
    val hasTags get() = tagsIn.isNotEmpty() || tagsOut.isNotEmpty()
    val hasCols get() = colsIn.isNotEmpty() || colsOut.isNotEmpty()
    val hasLength get() = minSec != null || maxSec != null
    val hasSize get() = minMb != null || maxMb != null
    /** How many kinds of filters are on (shown on the Search tile). */
    val count get() = listOf(hasDate, hasTags, hasCols, hasLength, hasSize).count { it }
    val active get() = count > 0

    fun clear() {
        datePreset = null; from = null; to = null
        tagsIn.clear(); tagsOut.clear(); colsIn.clear(); colsOut.clear()
        minSec = null; maxSec = null; minMb = null; maxMb = null
    }

    fun copy(): ClipFilter = fromJson(toJson())

    /** The dates shown, as [start, end) in ms, or null when there is no date filter. */
    fun range(): Pair<Long, Long>? {
        val today = dayStart(System.currentTimeMillis())
        return when (datePreset) {
            "today" -> today to addDays(today, 1)
            "yesterday" -> addDays(today, -1) to today
            "7d" -> addDays(today, -6) to addDays(today, 1)
            "30d" -> addDays(today, -29) to addDays(today, 1)
            else -> if (from == null && to == null) null else (from ?: Long.MIN_VALUE) to (to?.let { addDays(it, 1) } ?: Long.MAX_VALUE)
        }
    }

    /**
     * Whether a clip is shown. [tags] are the clip's tags, [inCol] tells whether it is in a collection.
     */
    fun matches(c: Clip, tags: Set<String>, inCol: (String) -> Boolean): Boolean {
        // exclusions always hide
        if (tagsOut.any { t -> tags.any { it.equals(t, true) } }) return false
        if (colsOut.any { inCol(it) }) return false
        val checks = ArrayList<Boolean>(5)
        range()?.let { (a, b) -> checks += c.startMs >= a && c.startMs < b }
        if (tagsIn.isNotEmpty()) {
            val has = { t: String -> tags.any { it.equals(t, true) } }
            checks += if (tagsAll) tagsIn.all(has) else tagsIn.any(has)
        }
        if (colsIn.isNotEmpty()) checks += if (colsAll) colsIn.all(inCol) else colsIn.any(inCol)
        if (hasLength) {
            val s = c.durationMs / 1000.0
            checks += (minSec == null || s >= minSec!!) && (maxSec == null || s <= maxSec!!)
        }
        if (hasSize) {
            val mb = c.size / 1_000_000.0
            checks += (minMb == null || mb >= minMb!!) && (maxMb == null || mb <= maxMb!!)
        }
        if (checks.isEmpty()) return true
        return if (matchAny) checks.any { it } else checks.all { it }
    }

    // ------------------------------------------------------------------ describing

    /** One removable piece of the filter, for the bar above the clips. */
    class Part(val text: String, val remove: (ClipFilter) -> Unit)

    fun parts(colName: (String) -> String?): List<Part> {
        val out = ArrayList<Part>()
        if (hasDate) out += Part(dateText(), { it.datePreset = null; it.from = null; it.to = null })
        if (tagsIn.isNotEmpty()) out += Part("Tags: " + tagsIn.joinToString(if (tagsAll) " and " else " or "), { it.tagsIn.clear() })
        if (tagsOut.isNotEmpty()) out += Part("Not tagged " + tagsOut.joinToString(" or "), { it.tagsOut.clear() })
        if (colsIn.isNotEmpty()) out += Part("In " + colsIn.mapNotNull(colName).joinToString(if (colsAll) " and " else " or "), { it.colsIn.clear() })
        if (colsOut.isNotEmpty()) out += Part("Not in " + colsOut.mapNotNull(colName).joinToString(" or "), { it.colsOut.clear() })
        if (hasLength) out += Part("Length " + rangeText(minSec, maxSec) { secText(it) }, { it.minSec = null; it.maxSec = null })
        if (hasSize) out += Part("Size " + rangeText(minMb, maxMb) { "$it MB" }, { it.minMb = null; it.maxMb = null })
        return out
    }

    fun dateText(): String = when (datePreset) {
        "today" -> "Today"
        "yesterday" -> "Yesterday"
        "7d" -> "Last 7 days"
        "30d" -> "Last 30 days"
        else -> {
            val f = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
            val a = from; val b = to
            when {
                a != null && b != null && a == b -> f.format(Date(a))
                a != null && b != null -> f.format(Date(a)) + " – " + f.format(Date(b))
                a != null -> "From " + f.format(Date(a))
                b != null -> "Until " + f.format(Date(b))
                else -> "Any time"
            }
        }
    }

    // ------------------------------------------------------------------ saving

    fun toJson(): JSONObject = JSONObject().apply {
        datePreset?.let { put("preset", it) }
        from?.let { put("from", it) }
        to?.let { put("to", it) }
        put("tagsIn", JSONArray(tagsIn.toList())); put("tagsOut", JSONArray(tagsOut.toList())); put("tagsAll", tagsAll)
        put("colsIn", JSONArray(colsIn.toList())); put("colsOut", JSONArray(colsOut.toList())); put("colsAll", colsAll)
        minSec?.let { put("minSec", it) }; maxSec?.let { put("maxSec", it) }
        minMb?.let { put("minMb", it) }; maxMb?.let { put("maxMb", it) }
        put("any", matchAny)
    }

    companion object {
        private fun sortedSet() = java.util.TreeSet(String.CASE_INSENSITIVE_ORDER)
        private fun sp(ctx: Context) = ctx.getSharedPreferences("clip_filters", Context.MODE_PRIVATE)

        fun fromJson(o: JSONObject) = ClipFilter().apply {
            datePreset = o.optString("preset").ifEmpty { null }
            from = if (o.has("from")) o.getLong("from") else null
            to = if (o.has("to")) o.getLong("to") else null
            fun strs(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
            tagsIn += strs("tagsIn"); tagsOut += strs("tagsOut"); tagsAll = o.optBoolean("tagsAll")
            colsIn += strs("colsIn"); colsOut += strs("colsOut"); colsAll = o.optBoolean("colsAll")
            fun int(k: String) = if (o.has(k)) o.getInt(k) else null
            minSec = int("minSec"); maxSec = int("maxSec"); minMb = int("minMb"); maxMb = int("maxMb")
            matchAny = o.optBoolean("any")
        }

        /** The saved filter of a view ("local", a remote folder id, plus "/collection" inside a collection). */
        fun load(ctx: Context, key: String): ClipFilter =
            try { sp(ctx).getString(key, null)?.let { fromJson(JSONObject(it)) } } catch (_: Exception) { null } ?: ClipFilter()

        fun save(ctx: Context, key: String, f: ClipFilter) {
            if (f.active) sp(ctx).edit().putString(key, f.toJson().toString()).apply()
            else sp(ctx).edit().remove(key).apply()
        }

        fun dayStart(ms: Long): Long = Calendar.getInstance().apply {
            timeInMillis = ms
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        fun addDays(day: Long, n: Int): Long = Calendar.getInstance().apply { timeInMillis = day; add(Calendar.DAY_OF_MONTH, n) }.timeInMillis

        fun secText(s: Int) = when {
            s < 60 -> "$s s"
            s % 60 == 0 -> "${s / 60} min"
            else -> "${s / 60} min ${s % 60} s"
        }

        fun rangeText(a: Int?, b: Int?, f: (Int) -> String) = when {
            a != null && b != null -> f(a) + " – " + f(b)
            a != null -> "over " + f(a)
            b != null -> "under " + f(b)
            else -> "any"
        }
    }
}
