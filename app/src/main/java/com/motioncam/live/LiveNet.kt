package com.motioncam.live

import android.app.Activity
import android.content.Context
import android.util.Base64
import androidx.credentials.CredentialManager
import androidx.credentials.CredentialManagerCallback
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.gms.tasks.Tasks
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.database.FirebaseDatabase
import com.motioncam.cloud.SecretStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * The Firebase project this build uses, set when building (credentials.properties or CI variables; see SETUP.md).
 * Public identifiers, not secrets: access is controlled by the database rules (firebase/database.rules.json).
 * Empty = the live connection is not set up in this build.
 */
object LiveConfig {
    val API_KEY: String = com.motioncam.BuildConfig.FIREBASE_API_KEY
    val APP_ID: String = com.motioncam.BuildConfig.FIREBASE_APP_ID
    val PROJECT_ID: String = com.motioncam.BuildConfig.FIREBASE_PROJECT_ID
    val DB_URL: String = com.motioncam.BuildConfig.FIREBASE_DB_URL
    /** OAuth "Web client" created by Firebase for Google sign-in (Authentication → Google); empty = network code only. */
    val WEB_CLIENT_ID: String = com.motioncam.BuildConfig.GOOGLE_WEB_CLIENT_ID

    val configured get() = API_KEY.isNotEmpty() && APP_ID.isNotEmpty() && PROJECT_ID.isNotEmpty() && DB_URL.isNotEmpty()
}

/**
 * The "network" of camera and viewer phones this phone belongs to:
 *  - Google mode: every phone signed in with the same Google account (Firebase Auth); data under users/<uid>,
 *    readable only by that account (database rules).
 *  - Code mode: phones that know a network code (no Google sign-in); data under nets/<SHA-256 of the code>, a
 *    path nobody can list, and everything in it is encrypted with a key derived from the code.
 */
object LiveNet {
    const val MODE_GOOGLE = "google"
    const val MODE_CODE = "code"
    /** Network code characters: no 0/O, 1/I/L. */
    const val CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    private const val APP_NAME = "motioncam-live"

    private fun sp(ctx: Context) = ctx.getSharedPreferences("live", Context.MODE_PRIVATE)
    fun mode(ctx: Context): String? = sp(ctx).getString("mode", null)
    fun account(ctx: Context): String? = sp(ctx).getString("account", null)
    private fun code(ctx: Context): String? = SecretStore.get(ctx, "live.code")
    /** The network code, to show it again to add more phones (code mode). */
    fun shownCode(ctx: Context): String? = if (mode(ctx) == MODE_CODE) code(ctx) else null

    // ------------------------------------------------------------------ Firebase

    @Volatile private var app: FirebaseApp? = null

    @Synchronized
    fun app(ctx: Context): FirebaseApp? {
        if (!LiveConfig.configured) return null
        app?.let { return it }
        val existing = try { FirebaseApp.getApps(ctx).firstOrNull { it.name == APP_NAME } } catch (_: Exception) { null }
        val a = existing ?: FirebaseApp.initializeApp(ctx.applicationContext, FirebaseOptions.Builder()
            .setApiKey(LiveConfig.API_KEY).setApplicationId(LiveConfig.APP_ID)
            .setProjectId(LiveConfig.PROJECT_ID).setDatabaseUrl(LiveConfig.DB_URL).build(), APP_NAME)
        app = a
        return a
    }

    fun auth(ctx: Context): FirebaseAuth? = app(ctx)?.let { FirebaseAuth.getInstance(it) }
    fun db(ctx: Context): FirebaseDatabase? = app(ctx)?.let { FirebaseDatabase.getInstance(it, LiveConfig.DB_URL) }

    /** Signed in and part of a network. */
    fun ready(ctx: Context): Boolean = LiveConfig.configured && mode(ctx) != null && auth(ctx)?.currentUser != null

    /** Root path of this network's data. */
    fun root(ctx: Context): String? {
        val user = auth(ctx)?.currentUser ?: return null
        return when (mode(ctx)) {
            MODE_GOOGLE -> "users/${user.uid}"
            MODE_CODE -> code(ctx)?.let { "nets/" + sha256Hex("motioncam-net:" + normalize(it)) }
            else -> null
        }
    }

    // ------------------------------------------------------------------ joining

    /** Google sign-in (account picker): only basic profile, so no Google verification is needed. */
    fun signInGoogle(a: Activity, done: (String?) -> Unit) {
        if (!LiveConfig.configured || LiveConfig.WEB_CLIENT_ID.isEmpty()) { done("Live connection is not set up in this build"); return }
        val opt = GetGoogleIdOption.Builder().setServerClientId(LiveConfig.WEB_CLIENT_ID).setFilterByAuthorizedAccounts(false).build()
        val req = GetCredentialRequest.Builder().addCredentialOption(opt).build()
        CredentialManager.create(a).getCredentialAsync(a, req, null, a.mainExecutor,
            object : CredentialManagerCallback<GetCredentialResponse, GetCredentialException> {
                override fun onResult(r: GetCredentialResponse) {
                    val c = r.credential
                    if (c !is CustomCredential || c.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) { done("Unexpected sign-in result"); return }
                    val g = GoogleIdTokenCredential.createFrom(c.data)
                    val auth = auth(a) ?: run { done("Not set up"); return }
                    auth.signInWithCredential(GoogleAuthProvider.getCredential(g.idToken, null))
                        .addOnSuccessListener {
                            sp(a).edit().putString("mode", MODE_GOOGLE).putString("account", g.id).apply()
                            SecretStore.put(a, "live.code", null)
                            done(null)
                        }
                        .addOnFailureListener { e -> done(e.message ?: "Sign-in failed") }
                }
                override fun onError(e: GetCredentialException) { done(e.message ?: "Sign-in cancelled") }
            })
    }

    /** Joins (or creates) a network with a code, without a Google account. Blocking. */
    fun joinWithCode(ctx: Context, code: String): String? {
        val c = normalize(code)
        // only codes made by MotionCam (20 random characters, ~99 bits): a hand-made code could be guessed
        if (c.length != 20 || c.any { it !in CODE_ALPHABET }) return "That isn't a MotionCam network code. Codes are 20 letters/digits " +
            "like ABCDE-FGHJK-MNPQR-STUVW, made with \"Create a network code\" on one of your phones."
        val auth = auth(ctx) ?: return "Live connection is not set up in this build"
        return try {
            if (auth.currentUser == null) Tasks.await(auth.signInAnonymously(), 30, TimeUnit.SECONDS)
            SecretStore.put(ctx, "live.code", c)
            sp(ctx).edit().putString("mode", MODE_CODE).putString("account", null).apply()
            keyCache = null
            null
        } catch (e: Exception) { e.cause?.message ?: e.message ?: "Could not connect" }
    }

    fun signOut(ctx: Context) {
        try { auth(ctx)?.signOut() } catch (_: Exception) {}
        sp(ctx).edit().remove("mode").remove("account").apply()
        SecretStore.put(ctx, "live.code", null)
        keyCache = null
    }

    /** A new random network code: 20 characters, about 100 bits, easy to type (no 0/O, 1/I/L). */
    fun newCode(): String {
        val r = SecureRandom()
        return (0 until 20).map { CODE_ALPHABET[r.nextInt(CODE_ALPHABET.length)] }.joinToString("").chunked(5).joinToString("-")
    }

    private fun normalize(code: String) = code.uppercase().filter { it.isLetterOrDigit() }

    fun sha256Hex(s: String): String = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    // ------------------------------------------------------------------ end-to-end encryption (code mode)

    @Volatile private var keyCache: SecretKeySpec? = null

    private fun key(ctx: Context): SecretKeySpec? {
        if (mode(ctx) != MODE_CODE) return null
        keyCache?.let { return it }
        val c = code(ctx) ?: return null
        val f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val k = f.generateSecret(PBEKeySpec(normalize(c).toCharArray(), "motioncam-live-key".toByteArray(), 20_000, 256)).encoded
        return SecretKeySpec(k, "AES").also { keyCache = it }
    }

    /** Text to store: encrypted in code mode ("e:" + base64), as is in Google mode. */
    fun seal(ctx: Context, plain: String): String = sealBytes(ctx, plain.toByteArray(Charsets.UTF_8))

    fun sealBytes(ctx: Context, plain: ByteArray): String {
        val k = key(ctx) ?: return "p:" + Base64.encodeToString(plain, Base64.NO_WRAP)
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, k, GCMParameterSpec(128, iv)) }
        return "e:" + Base64.encodeToString(iv + c.doFinal(plain), Base64.NO_WRAP)
    }

    fun open(ctx: Context, sealed: String?): String? = openBytes(ctx, sealed)?.toString(Charsets.UTF_8)

    fun openBytes(ctx: Context, sealed: String?): ByteArray? {
        if (sealed == null) return null
        return try {
            val raw = Base64.decode(sealed.substring(2), Base64.NO_WRAP)
            when {
                sealed.startsWith("p:") -> raw
                sealed.startsWith("e:") -> {
                    val k = key(ctx) ?: return null
                    Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, raw.copyOfRange(0, 12))) }
                        .doFinal(raw.copyOfRange(12, raw.size))
                }
                else -> null
            }
        } catch (e: Exception) { null }
    }
}
