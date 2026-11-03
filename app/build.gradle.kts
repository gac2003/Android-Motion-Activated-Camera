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
        versionCode = 1
        versionName = "1.0"
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
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

// Deliberately no third-party dependencies: only the Android framework (Camera2, MediaRecorder).
