import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * Your own API credentials (Firebase, Google sign-in, Dropbox): from environment variables (CI) or from
 * credentials.properties in the project folder (local builds; never committed). See SETUP.md.
 * Anything missing simply turns that feature off in the build ("not set up in this build").
 */
val credentials = Properties().apply {
    rootProject.file("credentials.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
/** The app's version: <major.minor> and the CI run that was its .0 (see version.properties). */
val versionProps = Properties().apply { rootProject.file("version.properties").inputStream().use { load(it) } }
fun cred(name: String): String = (System.getenv(name)?.takeIf { it.isNotBlank() } ?: credentials.getProperty(name).orEmpty()).trim()
fun str(v: String) = "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "com.motioncam"
    compileSdk = 34
    buildFeatures { buildConfig = true }

    defaultConfig {
        // your own package name if you like (it must match your Firebase app and Google OAuth client)
        applicationId = cred("MC_APPLICATION_ID").ifEmpty { "com.motioncam" }
        buildConfigField("String", "FIREBASE_API_KEY", str(cred("MC_FIREBASE_API_KEY")))
        buildConfigField("String", "FIREBASE_APP_ID", str(cred("MC_FIREBASE_APP_ID")))
        buildConfigField("String", "FIREBASE_PROJECT_ID", str(cred("MC_FIREBASE_PROJECT_ID")))
        buildConfigField("String", "FIREBASE_DB_URL", str(cred("MC_FIREBASE_DB_URL")))
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", str(cred("MC_GOOGLE_WEB_CLIENT_ID")))
        buildConfigField("String", "DROPBOX_APP_KEY", str(cred("MC_DROPBOX_APP_KEY")))
        // push alerts: a Firebase service account key that may only send messages (SETUP.md); the JSON file's
        // contents, or that base64-encoded. Kept base64 in the build. Empty = cameras don't send pushes.
        val fcm = cred("MC_FCM_SERVICE_ACCOUNT")
        buildConfigField("String", "FCM_SERVICE_ACCOUNT", str(
            if (fcm.startsWith("{")) java.util.Base64.getEncoder().encodeToString(fcm.toByteArray()) else fcm.replace(Regex("\\s"), "")))
        // MC_GOOGLE_DRIVE=off: no Google Drive (the public build of a repository that also makes a personal build)
        buildConfigField("boolean", "GOOGLE_DRIVE", (cred("MC_GOOGLE_DRIVE") != "off").toString())
        minSdk = 30
        targetSdk = 34
        // CI build number, so every published APK is newer than the previous one
        val build = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionCode = build
        // <major.minor> from version.properties; the patch counts builds since that version started
        // in a fork (or locally) the patch is simply the build number
        val patch = if (System.getenv("GITHUB_REPOSITORY") == versionProps.getProperty("base_repo")) (build - versionProps.getProperty("base_run").toInt()).coerceAtLeast(0) else build
        versionName = versionProps.getProperty("major_minor") + "." + patch
    }

    // Release signing key comes from GitHub Actions secrets (never stored in the repo). Builds without it
    // (e.g. local Android Studio builds) fall back to the normal local debug key.
    val ksPath = System.getenv("MOTIONCAM_KEYSTORE")
    signingConfigs {
        if (ksPath != null) {
            create("motioncam") {
                storeFile = file(ksPath)
                storePassword = System.getenv("MOTIONCAM_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("MOTIONCAM_KEY_ALIAS") ?: "motioncam"
                keyPassword = System.getenv("MOTIONCAM_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            if (ksPath != null) signingConfig = signingConfigs.getByName("motioncam")
        }
        release {
            isMinifyEnabled = false
            // published builds use the release key; local/fork builds fall back to the local debug key
            signingConfig = if (ksPath != null) signingConfigs.getByName("motioncam") else signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    // lint warnings must not block publishing a release
    lint { checkReleaseBuilds = false; abortOnError = false }
}

dependencies {
    // Google Drive sign-in (phone's account picker); the Drive API itself is called over plain HTTPS
    implementation("com.google.android.gms:play-services-auth:21.2.0")
    // SFTP (SSH) client
    implementation("com.github.mwiede:jsch:0.2.20")
    // pull-down-to-refresh in remote folders
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    // live connection between your phones: Firebase Realtime Database (websocket) + sign-in
    implementation(platform("com.google.firebase:firebase-bom:33.5.1"))
    implementation("com.google.firebase:firebase-database")
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-messaging")
    // Google sign-in for it (basic profile only: no Google verification needed)
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
}
