package com.motioncam.cloud

import android.accounts.Account
import android.app.Activity
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import java.util.concurrent.TimeUnit

/**
 * Google Drive sign-in through Google Play services: the phone's own account picker and consent screen,
 * no passwords or tokens handled by the user. The app is identified to Google by its package name and
 * signing certificate (registered as an Android OAuth client).
 */
object GoogleAuth {
    const val SCOPE = "https://www.googleapis.com/auth/drive.file"
    /**
     * Read-only access to the whole Drive. Only requested when the user adds a folder another Google account
     * shared with them (Remote folders); backups never use it.
     */
    const val SCOPE_READONLY = "https://www.googleapis.com/auth/drive.readonly"
    /** Android OAuth client registered for package com.motioncam + the release signing key (informational). */
    const val CLIENT_ID = "198396848516-1288dfl5tulora61kltqdnrdd57882ks.apps.googleusercontent.com"

    private fun request(account: String?, scope: String): AuthorizationRequest {
        val b = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(scope)))
        if (account != null) b.setAccount(Account(account, "com.google"))
        return b.build()
    }

    /** Interactive sign-in from a screen. [onToken] gets an access token; a consent screen may be shown first. */
    fun start(a: Activity, requestCode: Int, onToken: (String) -> Unit, onError: (String) -> Unit, scope: String = SCOPE) {
        Identity.getAuthorizationClient(a).authorize(request(null, scope))
            .addOnSuccessListener { r ->
                val pi = r.pendingIntent
                if (r.hasResolution() && pi != null) {
                    try { a.startIntentSenderForResult(pi.intentSender, requestCode, null, 0, 0, 0) }
                    catch (e: Exception) { onError(e.message ?: "Could not open Google sign-in") }
                } else {
                    val t = r.accessToken
                    if (t != null) onToken(t) else onError("Google did not return access")
                }
            }
            .addOnFailureListener { onError(it.message ?: "Google sign-in failed") }
    }

    /** Result of the consent screen started by [start]. */
    fun result(a: Activity, data: Intent?): String? = try {
        Identity.getAuthorizationClient(a).getAuthorizationResultFromIntent(data).accessToken
    } catch (e: Exception) { null }

    private class Cached(val token: String, val at: Long)
    private val cache = HashMap<String, Cached>()

    /** Background token for an already-authorised [account]; never shows UI. Reused for ~45 minutes. */
    @Synchronized
    fun token(ctx: Context, account: String?, scope: String = SCOPE): String {
        val key = "$account|$scope"
        cache[key]?.let { if (System.currentTimeMillis() - it.at < 45 * 60_000L) return it.token }
        return fetch(ctx, account, scope).also { cache[key] = Cached(it, System.currentTimeMillis()) }
    }

    private fun fetch(ctx: Context, account: String?, scope: String): String {
        val r = try {
            Tasks.await(Identity.getAuthorizationClient(ctx).authorize(request(account, scope)), 30, TimeUnit.SECONDS)
        } catch (e: Exception) {
            throw CloudException("Google sign-in unavailable: ${e.message}", CloudException.Kind.NETWORK, e)
        }
        if (r.hasResolution()) throw CloudException("Google Drive access needs to be renewed — open MotionCam and reconnect", CloudException.Kind.AUTH)
        return r.accessToken ?: throw CloudException("No Google access token", CloudException.Kind.AUTH)
    }

    /** Drops a token Google rejected so the next request gets a fresh one. */
    fun invalidate(ctx: Context, token: String) {
        synchronized(this) { cache.entries.removeAll { it.value.token == token } }
        try { GoogleAuthUtil.clearToken(ctx, token) } catch (_: Exception) {}
    }
}
