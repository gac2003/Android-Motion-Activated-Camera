package com.motioncam.cloud

import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Minimal HTTP client on HttpURLConnection (no dependencies). */
object Http {
    class Resp(val code: Int, val body: String, private val headers: Map<String?, List<String>>) {
        fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, true) }?.value?.firstOrNull()
        val ok get() = code in 200..299
    }

    fun form(vararg kv: Pair<String, String>): ByteArray =
        kv.joinToString("&") { URLEncoder.encode(it.first, "UTF-8") + "=" + URLEncoder.encode(it.second, "UTF-8") }.toByteArray()

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /**
     * Sends a request. [body] is either bytes or a stream with [bodyLength]. Network failures become
     * CloudException(NETWORK) so the queue retries them.
     */
    fun request(
        method: String, url: String, headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null, stream: InputStream? = null, bodyLength: Long = -1,
        progress: ((Long) -> Unit)? = null, timeoutMs: Int = 60_000,
    ): Resp {
        try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.requestMethod = method
            c.connectTimeout = 20_000
            c.readTimeout = timeoutMs
            c.instanceFollowRedirects = false
            for ((k, v) in headers) c.setRequestProperty(k, v)
            val len = body?.size?.toLong() ?: bodyLength
            if (body != null || stream != null || method == "PUT" || method == "POST") {
                c.doOutput = true
                c.setFixedLengthStreamingMode(maxOf(0L, len))
                c.outputStream.use { out ->
                    if (body != null) out.write(body)
                    else if (stream != null) {
                        val buf = ByteArray(64 * 1024)
                        var sent = 0L
                        while (sent < len) {
                            val n = stream.read(buf, 0, minOf(buf.size.toLong(), len - sent).toInt())
                            if (n < 0) throw IOException("source ended early")
                            out.write(buf, 0, n)
                            sent += n
                            progress?.invoke(n.toLong())
                        }
                    }
                }
            }
            val code = c.responseCode
            val text = try {
                (if (code >= 400) c.errorStream else c.inputStream)?.bufferedReader()?.use { it.readText() } ?: ""
            } catch (e: IOException) { "" }
            val r = Resp(code, text, c.headerFields)
            c.disconnect()
            return r
        } catch (e: CloudException) {
            throw e
        } catch (e: IOException) {
            throw CloudException("Network error: ${e.message}", CloudException.Kind.NETWORK, e)
        }
    }

    /** Maps common HTTP failures to error kinds. */
    fun fail(r: Resp, what: String): Nothing {
        val kind = when {
            r.code == 401 -> CloudException.Kind.AUTH
            r.code == 404 -> CloudException.Kind.NOT_FOUND
            r.code == 429 || r.code >= 500 -> CloudException.Kind.NETWORK
            r.body.contains("storageQuotaExceeded") || r.body.contains("insufficient_space") || r.body.contains("quotaExceeded") -> CloudException.Kind.QUOTA
            r.body.contains("invalid_grant") || r.body.contains("expired_access_token") || r.body.contains("invalid_access_token") -> CloudException.Kind.AUTH
            else -> CloudException.Kind.OTHER
        }
        throw CloudException("$what failed (HTTP ${r.code}): ${r.body.take(300)}", kind)
    }
}
