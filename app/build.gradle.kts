plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.motioncam"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.motioncam"
        minSdk = 30
        targetSdk = 34
        // CI build number, so every published APK is newer than the previous one
        val build = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionCode = build
        versionName = "1.0.$build"
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
    // Google sign-in for it (basic profile only: no Google verification needed)
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
}
