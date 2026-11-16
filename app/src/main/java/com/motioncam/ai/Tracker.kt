package com.motioncam.ai

import kotlin.math.abs

/**
 * Follows people through a clip from one looked-at frame to the next (about a second apart): a person box that is
 * near where a person was a moment ago, and about the same size, is the same person; otherwise someone new.
 * Gives how many people there were, how long each was in view, and their colours.
 */
class Tracker {
    class Track(var box: FloatArray, val first: Float) {
        var last = first
        var hits = 1
        var bestScore = box[4]
        val top = ClipFacts.newVotes()
        val bottom = ClipFacts.newVotes()
        val seconds: Int get() = (last - first).toInt() + 1
    }

    val tracks = ArrayList<Track>()
    var mostAtOnce = 0; private set

    /** The people boxes ([left, top, right, bottom, score]) of the frame at [t] seconds. Returns each box's track. */
    fun add(t: Float, boxes: List<FloatArray>): List<Track> {
        mostAtOnce = maxOf(mostAtOnce, boxes.size)
        val used = HashSet<Track>()
        val out = ArrayList<Track>()
        for (b in boxes) {
            val h = b[3] - b[1]
            val cx = (b[0] + b[2]) / 2; val cy = (b[1] + b[3]) / 2
            var best: Track? = null; var bestD = Float.MAX_VALUE
            for (k in tracks) {
                if (k in used || t - k.last > 4f) continue          // gone for over 4 s: someone new
                val kh = k.box[3] - k.box[1]
                if (kh <= 0f || h / kh > 2f || kh / h > 2f) continue   // a very different size isn't the same person
                val d = abs(cx - (k.box[0] + k.box[2]) / 2) + abs(cy - (k.box[1] + k.box[3]) / 2)
                // people walk: allow about one and a half body heights per second of gap
                if (d < maxOf(h, kh) * 1.5f * maxOf(1f, t - k.last) && d < bestD) { bestD = d; best = k }
            }
            val k = best ?: Track(b, t).also { tracks.add(it) }
            if (best != null) { k.box = b; k.last = t; k.hits++; if (b[4] > k.bestScore) k.bestScore = b[4] }
            used.add(k)
            out.add(k)
        }
        return out
    }

    /** The people really seen: in two or more frames, or once but clearly; at least as many as were in view at once. */
    fun people(): List<Track> {
        val sure = tracks.filter { it.hits >= 2 || it.bestScore >= 0.6f }.sortedByDescending { it.seconds }
        return if (sure.size >= mostAtOnce) sure else tracks.sortedByDescending { it.bestScore }.take(mostAtOnce)
    }
}
