plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ios27.launcher"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ios27.launcher"
        minSdk = 24
        targetSdk = 35
        versionCode = 4
        versionName = "0.4.0"
    }

    buildFeatures {
        viewBinding = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}
