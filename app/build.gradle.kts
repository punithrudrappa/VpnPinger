plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.vpnpinger"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.vpnpinger"
        minSdk = 26        // Android 8.0+. Covers the vast majority of active devices.
        targetSdk = 34     // Keep <= 34: Android 15's 6h/day dataSync foreground-service cap
                           // only applies to apps that target SDK 35+.
        versionCode = 1
        versionName = "0.0.1"
    }

    buildTypes {
        release {
            // Tiny app, no need to shrink; keeps the build simple and predictable.
            isMinifyEnabled = false
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

// No external dependencies at all: everything uses the Android platform APIs,
// which keeps the APK small and the build fast.
dependencies {
    // Host-side unit tests only (never packaged into the APK).
    testImplementation("junit:junit:4.13.2")
}
