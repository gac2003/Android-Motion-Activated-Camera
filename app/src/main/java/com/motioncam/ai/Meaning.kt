package com.motioncam.ai

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import com.motioncam.Clip
import com.motioncam.cloud.Device
import com.motioncam.remote.RemoteRepo
import com.motioncam.remote.RemoteSource
import com.motioncam.remote.RemoteSources
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.sqrt

/**
 * Search by meaning: "red hat", "person with a package". An image-text model turns pictures and words into the same
 * kind of "fingerprint" (512 numbers); a clip matches a search when one of its pictures' fingerprints is close to the
 * words' fingerprint.
 *
 *  - The recording phone makes up to four fingerprints per clip while describing it (see [ClipAnalyzer]): three
 *    moments of the whole picture and a close-up of the clearest person. About 2 KB per clip, kept on the phone and,
 *    for backed-up clips, in the backup folder (".ai", one small file per day and phone).
 *  - Any phone searches: the words' fingerprint is made when you search (the text half of the model is loaded for
 *    that moment only), and a remote folder's fingerprint files are fetched then (and kept).
 */
object Meaning {
    const val DIM = 512
    const val FOLDER = ".ai"
    private const val SIZE = 256
    private const val MAGIC = 0x4D434D31   // "MCM1"
    private const val MAX_LOCAL = 3000

    // ================================================================== words → numbers the model reads

    /** The model's way of cutting text into word pieces (its published word list and joining rules). */
    class Words(json: String) {
        private val vocab = HashMap<String, Int>(60000)
        private val rank = HashMap<String, Int>(60000)
        private val splitter = Regex("'s|'t|'re|'ve|'m|'ll|'d|[\\p{L}]+|[\\p{N}]|[^\\s\\p{L}\\p{N}]+")
        private val byteChar = CharArray(256)
        private val start: Int
        private val end: Int

        init {
            val model = JSONObject(json).getJSONObject("model")
            val v = model.getJSONObject("vocab")
            for (k in v.keys()) vocab[k] = v.getInt(k)
            val m = model.getJSONArray("merges")
            for (i in 0 until m.length()) rank[m.getString(i)] = i
            start = vocab["<|startoftext|>"] ?: 49406
            end = vocab["<|endoftext|>"] ?: 49407
            // each byte of the text as one character (printable ones as themselves, the rest moved out of the way)
            var n = 0
            for (b in 0..255) {
                val plain = b in 33..126 || b in 161..172 || b in 174..255
                byteChar[b] = if (plain) b.toChar() else (256 + n++).toChar()
            }
        }

        private fun pieces(word: String): List<String> {
            val bytes = word.toByteArray(Charsets.UTF_8)
            val parts = ArrayList<String>(bytes.size)
            for (b in bytes) parts.add(byteChar[b.toInt() and 0xFF].toString())
            if (parts.isEmpty()) return parts
            parts[parts.size - 1] = parts[parts.size - 1] + "</w>"
            // join the pair that the model learned first, again and again
            while (parts.size > 1) {
                var best = -1; var bestRank = Int.MAX_VALUE
                for (i in 0 until parts.size - 1) {
                    val r = rank[parts[i] + " " + parts[i + 1]] ?: continue
                    if (r < bestRank) { bestRank = r; best = i }
                }
                if (best < 0) break
                val a = parts[best]; val b = parts[best + 1]
                var i = 0
                while (i < parts.size - 1) {
                    if (parts[i] == a && parts[i + 1] == b) { parts[i] = a + b; parts.removeAt(i + 1) }
                    i++
                }
            }
            return parts
        }

        /** The text as the model's numbers: start, word pieces, end, filled up to [length]. */
        fun encode(text: String, length: Int = 77): LongArray {
            val clean = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFC).replace(Regex("\\s+"), " ").lowercase(java.util.Locale.ROOT)
            val ids = ArrayList<Long>()
            ids.add(start.toLong())
            for (m in splitter.findAll(clean)) for (p in pieces(m.value)) ids.add((vocab[p] ?: end).toLong())
            while (ids.size > length - 1) ids.removeAt(ids.size - 1)
            ids.add(end.toLong())
            val out = LongArray(length)
            for (i in ids.indices) out[i] = ids[i]
            return out
        }
    }

    // ================================================================== the model's two halves

    private fun normalise(v: FloatArray): FloatArray {
        var s = 0.0
        for (x in v) s += x * x
        val n = sqrt(s).toFloat().coerceAtLeast(1e-6f)
        for (i in v.indices) v[i] /= n
        return v
    }

    /** One half of the model, loaded from its file's bytes. */
    class Half(bytes: ByteArray, threads: Int) : java.io.Closeable {
        private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
        private val session: OrtSession = env.createSession(bytes, OrtSession.SessionOptions().apply { setIntraOpNumThreads(threads) })
        private val input: String = session.inputNames.first()

        /** A picture's fingerprint: [rgb] = 256 × 256 pixels (0xRRGGBB), row by row. */
        fun picture(rgb: IntArray): FloatArray {
            val n = SIZE * SIZE
            val x = FloatArray(3 * n)
            for (i in 0 until n) {
                val c = rgb[i]
                x[i] = ((c shr 16) and 0xFF) / 255f; x[n + i] = ((c shr 8) and 0xFF) / 255f; x[2 * n + i] = (c and 0xFF) / 255f
            }
            OnnxTensor.createTensor(env, FloatBuffer.wrap(x), longArrayOf(1, 3, SIZE.toLong(), SIZE.toLong())).use { t -> return run(t) }
        }

        /** The fingerprint of words already turned into the model's numbers ([Words.encode]). */
        fun words(ids: LongArray): FloatArray {
            OnnxTensor.createTensor(env, LongBuffer.wrap(ids), longArrayOf(1, ids.size.toLong())).use { t -> return run(t) }
        }

        private fun run(t: OnnxTensor): FloatArray {
            session.run(mapOf(input to t)).use { r ->
                val fb = (r.get(0) as OnnxTensor).floatBuffer
                val out = FloatArray(DIM)
                fb.get(out)
                return normalise(out)
            }
        }

        override fun close() { try { session.close() } catch (_: Throwable) {} }
    }

    private fun asset(ctx: Context, name: String): ByteArray = ctx.assets.open("ai/$name").use { it.readBytes() }

    /** The picture half (the recording phone, while describing clips). */
    fun pictureHalf(ctx: Context): Half = Half(asset(ctx, "clip_v.onnx"), 2)

    /** A picture's fingerprint, compact (one byte per number): the whole [bmp] squeezed to the model's square. */
    fun fingerprint(half: Half, bmp: Bitmap): ByteArray {
        val s = if (bmp.width == SIZE && bmp.height == SIZE) bmp else Bitmap.createScaledBitmap(bmp, SIZE, SIZE, true)
        val px = IntArray(SIZE * SIZE)
        s.getPixels(px, 0, SIZE, 0, 0, SIZE, SIZE)
        if (s !== bmp) s.recycle()
        return pack(half.picture(px))
    }

    fun pack(v: FloatArray): ByteArray = ByteArray(v.size) { Math.round(v[it] * 127f).coerceIn(-127, 127).toByte() }

    /** How alike a compact fingerprint (at [off] in [b]) and the words' fingerprint are (-1..1). */
    fun alike(b: ByteArray, off: Int, words: FloatArray): Float {
        var dot = 0f; var len = 0f
        for (i in 0 until DIM) { val x = b[off + i].toFloat(); dot += x * words[i]; len += x * x }
        return if (len <= 0f) 0f else dot / sqrt(len)
    }

    // ================================================================== this phone's fingerprints

    private var local: LinkedHashMap<String, ByteArray>? = null
    private fun localFile(ctx: Context) = File(ctx.filesDir, "ai_meaning.bin")
    private fun sp(ctx: Context) = ctx.getSharedPreferences("ai_meaning", Context.MODE_PRIVATE)

    private fun readRecords(bytes: ByteArray, into: MutableMap<String, ByteArray>) {
        try {
            val d = DataInputStream(bytes.inputStream())
            if (d.readInt() != MAGIC) return
            while (d.available() > 0) {
                val name = d.readUTF()
                val n = d.readUnsignedByte()
                val v = ByteArray(n * DIM)
                d.readFully(v)
                into[name] = v
            }
        } catch (_: Exception) {}   // a cut-off file: what was read so far counts
    }

    private fun writeRecords(records: Map<String, ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        val d = DataOutputStream(bos)
        d.writeInt(MAGIC)
        for ((name, v) in records) { d.writeUTF(name); d.writeByte(v.size / DIM); d.write(v) }
        d.flush()
        return bos.toByteArray()
    }

    @Synchronized
    private fun localMap(ctx: Context): LinkedHashMap<String, ByteArray> {
        local?.let { return it }
        val m = LinkedHashMap<String, ByteArray>()
        try { val f = localFile(ctx); if (f.exists()) readRecords(f.readBytes(), m) } catch (_: Exception) {}
        local = m
        return m
    }

    /** yyyyMMdd of a clip named MC_yyyyMMdd_HHmmss…, or null. */
    private fun dayOf(name: String): String? = if (name.startsWith("MC_") && name.length >= 11) name.substring(3, 11).takeIf { it.all(Char::isDigit) } else null

    /** The recording phone keeps a clip's fingerprints ([prints] = one or more of [DIM] bytes each). */
    @Synchronized
    fun keep(ctx: Context, clipName: String, prints: List<ByteArray>) {
        if (prints.isEmpty()) return
        val m = localMap(ctx)
        val v = ByteArray(prints.size * DIM)
        for (i in prints.indices) System.arraycopy(prints[i], 0, v, i * DIM, DIM)
        m.remove(clipName); m[clipName] = v
        // the newest MAX_LOCAL clips stay
        val iter = m.entries.iterator()
        while (m.size > MAX_LOCAL && iter.hasNext()) { iter.next(); iter.remove() }
        try { val f = localFile(ctx); val t = File(f.path + ".tmp"); t.writeBytes(writeRecords(m)); t.renameTo(f) } catch (_: Exception) {}
        dayOf(clipName)?.let { day -> val s = sp(ctx); s.edit().putStringSet("dirty", (s.getStringSet("dirty", emptySet()) ?: emptySet()) + day).apply() }
    }

    @Synchronized fun localCount(ctx: Context): Int = localMap(ctx).size
    fun daysToSend(ctx: Context): Int = sp(ctx).getStringSet("dirty", emptySet())?.size ?: 0

    /**
     * Sends the days whose fingerprints changed to the backup folders (one small file per day; network). Called when
     * the describing queue has drained, so a busy day is a few uploads, not one per clip.
     */
    fun sendDays(ctx: Context) {
        val s = sp(ctx)
        val days = s.getStringSet("dirty", emptySet())?.toList() ?: return
        val dev = Device.id(ctx).take(8)
        for (day in days) {
            val records = synchronized(this) { localMap(ctx).filterKeys { dayOf(it) == day } }
            val ok = records.isEmpty() || try { com.motioncam.cloud.CloudSync.pushMeaning(ctx, "m_${day}_$dev.bin", writeRecords(records)) } catch (_: Throwable) { false }
            if (!ok) return   // no way to the folder now: next time
            synchronized(this) { s.edit().putStringSet("dirty", (s.getStringSet("dirty", emptySet()) ?: emptySet()) - day).apply() }
        }
    }

    // ================================================================== a remote folder's fingerprints

    /** Fetches the folder's fingerprint files that this phone doesn't have yet (network), and returns them all. */
    private fun remotePrints(ctx: Context, src: RemoteSource): Map<String, ByteArray> {
        val dir = File(RemoteRepo.cacheDir(ctx, src.id), "meaning").apply { mkdirs() }
        try {
            RemoteSources.reader(ctx, src).use { r ->
                val folder = r.listFolders(src.folderId).firstOrNull { it.name == FOLDER }
                val want = HashSet<String>()
                if (folder != null) for (f in r.list(folder.id)) {
                    if (!f.name.startsWith("m_") || !f.name.endsWith(".bin")) continue
                    val local = File(dir, f.name.removeSuffix(".bin") + "~" + f.size + "~" + f.modifiedMs + ".bin")
                    want += local.name
                    if (!local.exists()) r.open(f, 0).use { ins -> local.writeBytes(ins.readBytes()) }
                }
                // older copies of a day's file, and days that are gone from the folder
                dir.listFiles()?.forEach { if (it.name !in want) it.delete() }
            }
        } catch (_: Exception) {}   // offline: what was fetched before still searches
        val out = HashMap<String, ByteArray>()
        dir.listFiles()?.sortedBy { it.lastModified() }?.forEach { f -> try { readRecords(f.readBytes(), out) } catch (_: Exception) {} }
        return out
    }

    // ================================================================== searching

    class Found(val names: List<String>, val known: Int)

    /**
     * The clips of [clips] that fit [query] best, best first (blocking; network for a remote folder [scope]).
     * [known] = how many of the clips have fingerprints at all.
     */
    fun search(ctx: Context, scope: String?, clips: List<Clip>, query: String): Found {
        val app = ctx.applicationContext
        val prints: Map<String, ByteArray> = if (scope == null) synchronized(this) { HashMap(localMap(app)) }
            else RemoteSources.get(app, scope)?.let { remotePrints(app, it) } ?: emptyMap()
        val names = clips.map { it.name }.filter { it in prints }
        if (names.isEmpty()) return Found(emptyList(), 0)
        val words = Words(String(asset(app, "words.json"), Charsets.UTF_8)).encode(query)
        val q = Half(asset(app, "clip_t.onnx"), 4).use { it.words(words) }
        val scored = names.map { n ->
            val v = prints[n]!!
            var best = -1f
            for (i in 0 until v.size / DIM) best = maxOf(best, alike(v, i * DIM, q))
            n to best
        }.sortedByDescending { it.second }
        // the best ones: close to the top score, at most 60 (weaker matches would only be noise)
        val top = scored.first().second
        return Found(scored.filter { it.second >= top - 0.05f && it.second >= 0.10f }.take(60).map { it.first }, names.size)
    }
}
