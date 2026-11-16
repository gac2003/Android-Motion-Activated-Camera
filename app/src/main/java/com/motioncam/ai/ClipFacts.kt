package com.motioncam.ai

import android.graphics.Bitmap
import android.graphics.Color

/**
 * What the AI saw in a clip, as words: how many people, how long they stayed, the main colour of their upper and lower
 * body, and objects. Stored as automatic tags (marked with [MARK]) next to your own tags, so they travel with the clip
 * to its backup folder and every phone can search them without downloading anything extra. Search shows them in their
 * own section ("Seen in clip"), the tag editor leaves them alone.
 */
object ClipFacts {
    const val MARK = "✦ "
    fun isAi(tag: String) = tag.startsWith(MARK)
    fun label(tag: String) = tag.removePrefix(MARK)

    /** The detector's object kinds worth telling apart at a camera (its number → the word). */
    val KINDS: Map<Int, String> = mapOf(1 to "bicycle", 2 to "car", 3 to "motorcycle", 5 to "bus", 7 to "truck",
        16 to "cat", 17 to "dog", 26 to "backpack", 30 to "handbag", 32 to "suitcase")
    const val PERSON = 0

    private val COLOURS = listOf("black", "grey", "white", "red", "orange", "yellow", "green", "blue", "purple", "pink", "brown")

    /** The everyday name of a colour. */
    fun colourName(rgb: Int): String {
        val hsv = FloatArray(3)
        Color.colorToHSV(rgb, hsv)
        val h = hsv[0]; val s = hsv[1]; val v = hsv[2]
        if (v < 0.2f) return "black"
        if (s < 0.18f) return if (v > 0.75f) "white" else if (v < 0.35f) "black" else "grey"
        return when {
            h < 15f || h >= 345f -> if (v < 0.45f) "brown" else "red"
            h < 40f -> if (v < 0.6f) "brown" else "orange"
            h < 65f -> if (v < 0.45f) "brown" else "yellow"
            h < 170f -> "green"
            h < 260f -> "blue"
            h < 300f -> "purple"
            else -> "pink"
        }
    }

    /**
     * Votes for the colour names inside a region of [bmp] (fractions of its size), added to [votes] (one slot per
     * colour in [COLOURS] order). Returns how colourful the region is on average (0..1), to recognise night pictures.
     */
    fun vote(bmp: Bitmap, l: Float, t: Float, r: Float, b: Float, votes: IntArray): Float {
        val x0 = (l * bmp.width).toInt().coerceIn(0, bmp.width - 1); val x1 = (r * bmp.width).toInt().coerceIn(x0, bmp.width - 1)
        val y0 = (t * bmp.height).toInt().coerceIn(0, bmp.height - 1); val y1 = (b * bmp.height).toInt().coerceIn(y0, bmp.height - 1)
        val sx = maxOf(1, (x1 - x0) / 16); val sy = maxOf(1, (y1 - y0) / 16)
        val hsv = FloatArray(3)
        var sat = 0f; var n = 0
        var y = y0
        while (y <= y1) {
            var x = x0
            while (x <= x1) {
                val c = bmp.getPixel(x, y)
                votes[COLOURS.indexOf(colourName(c))]++
                Color.colorToHSV(c, hsv); sat += hsv[1]; n++
                x += sx
            }
            y += sy
        }
        return if (n == 0) 0f else sat / n
    }

    /** The winning colour of [votes], or null without a clear one (under 40 % of the votes). */
    fun winner(votes: IntArray): String? {
        val total = votes.sum()
        if (total < 20) return null
        var best = 0
        for (i in votes.indices) if (votes[i] > votes[best]) best = i
        return if (votes[best] * 100 >= total * 40) COLOURS[best] else null
    }

    fun newVotes() = IntArray(COLOURS.size)

    fun stayed(seconds: Int): String = when {
        seconds < 10 -> "stayed under 10 s"
        seconds < 30 -> "stayed 10–30 s"
        seconds < 120 -> "stayed 30 s – 2 min"
        else -> "stayed over 2 min"
    }

    fun people(n: Int): String? = when { n <= 1 -> null; n >= 5 -> "5+ people"; else -> "$n people" }

    /** The automatic tags as stored ([MARK] in front). */
    fun tags(words: Collection<String>): Set<String> = words.map { MARK + it }.toSet()

    private fun rank(w: String): Int = when {
        w == "person" || w.endsWith(" people") -> 0
        w.startsWith("stayed ") -> 1
        w.endsWith(" top") -> 2
        w.endsWith(" bottom") -> 3
        else -> 4
    }

    /** One readable line from a clip's tags ("2 people · stayed 10–30 s · red top · car"), or "" without AI tags. */
    fun line(tags: Collection<String>): String {
        val words = tags.filter { isAi(it) }.map { label(it) }
        if (words.isEmpty()) return ""
        val counted = words.any { it.endsWith(" people") }
        return words.filter { !(counted && it == "person") }.sortedWith(compareBy({ rank(it) }, { it })).joinToString(" · ")
    }
}
