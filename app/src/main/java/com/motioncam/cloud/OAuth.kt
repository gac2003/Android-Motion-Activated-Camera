package com.motioncam.cloud

import java.net.InetAddress
import java.net.ServerSocket
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** PKCE helpers (RFC 7636). */
object Pkce {
    private val rnd = SecureRandom()
    private fun b64(b: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    fun verifier(): String = b64(ByteArray(48).also { rnd.nextBytes(it) })
    fun challenge(verifier: String): String = b64(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
    fun state(): String = b64(ByteArray(16).also { rnd.nextBytes(it) })
}

/**
 * Receives the OAuth redirect on http://127.0.0.1:<port>/ (the "loopback" flow Google supports for installed
 * apps). The browser on the same phone is redirected here after the user approves.
 */
class LoopbackReceiver(port: Int = 0) : java.io.Closeable {
    companion object {
        /** The sign-in waiting for its redirect: a new sign-in replaces it (an abandoned one kept the port busy). */
        @Volatile private var current: LoopbackReceiver? = null

        /**
         * Opens a sign-in page in an in-app browser tab (Custom Tab, falls back to the browser); the app comes back to
         * the front by itself once the sign-in is done ([bringBack]).
         */
        fun openPage(a: android.app.Activity, url: String) {
            val i = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
            i.putExtras(android.os.Bundle().apply { putBinder("android.support.customtabs.extra.SESSION", null) })
            i.putExtra("android.support.customtabs.extra.TOOLBAR_COLOR", 0xFF202328.toInt())
            try { a.startActivity(i) } catch (_: Exception) {
                a.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
            }
        }

        /** Brings the screen that started a sign-in back in front of the browser tab. */
        fun bringBack(a: android.app.Activity) {
            try { a.startActivity(android.content.Intent(a, a.javaClass).addFlags(android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) } catch (_: Exception) {}
        }
    }

    /** Closed because a newer sign-in started (its waiting thread should end quietly). */
    @Volatile var superseded = false; private set

    private val server: ServerSocket

    init {
        current?.let { it.superseded = true; it.close() }
        server = ServerSocket().apply {
            reuseAddress = true
            bind(java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 1)
        }
        current = this
    }

    val redirectUri = "http://127.0.0.1:${server.localPort}"

    /** Waits for the redirect; returns the "code" parameter (checks [state]). */
    fun waitForCode(state: String, timeoutMs: Int): String {
        server.soTimeout = timeoutMs
        while (true) {
            val s = server.accept()
            s.use { sock ->
                val line = sock.getInputStream().bufferedReader().readLine() ?: return@use
                val q = line.substringAfter("?", "").substringBefore(" ")
                val params = q.split("&").filter { it.contains("=") }.associate {
                    URLDecoder.decode(it.substringBefore("="), "UTF-8") to URLDecoder.decode(it.substringAfter("="), "UTF-8")
                }
                val ok = params["code"] != null && params["state"] == state
                val msg = if (ok) "MotionCam is connected. You can close this page and return to the app."
                else "Sign-in was not completed${params["error"]?.let { " ($it)" } ?: ""}. Return to MotionCam and try again."
                val html = "<html><body style='font-family:sans-serif;padding:24px'><h2>$msg</h2></body></html>"
                sock.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${html.toByteArray().size}\r\nConnection: close\r\n\r\n$html").toByteArray())
                sock.getOutputStream().flush()
                if (ok) return params["code"]!!
                if (params.containsKey("error")) throw CloudException("Sign-in cancelled: ${params["error"]}", CloudException.Kind.AUTH)
            }
        }
    }

    override fun close() {
        try { server.close() } catch (_: Exception) {}
        if (current === this) current = null
    }
}
