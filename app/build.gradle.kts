plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.theduvanf.panelprius"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.theduvanf.panelprius"
        minSdk = 26
        targetSdk = 34
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
    }

    // Fixed key so each new version installs over the previous one.
    signingConfigs {
        create("panel") {
            storeFile = file("panel.keystore")
            storePassword = "panelprius"
            keyAlias = "panel"
            keyPassword = "panelprius"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("panel")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
