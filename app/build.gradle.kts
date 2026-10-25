plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.sharedclips"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.sharedclips"
        minSdk = 30
        targetSdk = 36
        versionCode = 8
        versionName = "0.5.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // A fixed debug key, so each CI build installs over the previous one instead of failing
        // with "package conflicts with an existing package". Debug builds only.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // The Play upload key. Never checked in: CI and local release builds read it from the
        // environment, and without it release builds come out unsigned.
        System.getenv("RELEASE_KEYSTORE")?.let { path ->
            create("release") {
                storeFile = file(path)
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    // "play" is the Google Play build: no All files access and no battery-exemption request, which
    // Play does not allow for this kind of app, so background moves always go through a one-tap
    // notification. "full" is the sideloaded build with silent background moves; it keeps the old
    // application ID so it installs over earlier test builds.
    flavorDimensions += "distribution"
    productFlavors {
        create("play") {
            dimension = "distribution"
            applicationId = "app.clipsweep"
            buildConfigField("boolean", "ALL_FILES_ACCESS", "false")
        }
        create("full") {
            dimension = "distribution"
            buildConfigField("boolean", "ALL_FILES_ACCESS", "true")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.work.runtime.ktx)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestImplementation(libs.androidx.work.testing)
}
