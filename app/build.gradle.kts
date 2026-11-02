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

    // A fixed debug key committed to the repo: every CI build has the same signature,
    // so a new APK installs over the old one (keeps settings and permissions).
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
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
