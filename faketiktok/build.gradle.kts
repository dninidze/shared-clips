// Test-only stand-in for TikTok. It is installed on the CI emulator under TikTok's package name so the
// app sees exactly what it sees on a phone (OWNER_PACKAGE_NAME = com.zhiliaoapp.musically), and it saves
// videos into DCIM/Camera in the different ways a real app might.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "app.sharedclips.faketiktok"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.zhiliaoapp.musically"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "test"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
