plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.zeekr.sentrycam"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.zeekr.sentrycam"
        minSdk = 29
        targetSdk = 34
        versionCode = 146
        versionName = "0.88.0"
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")  // debug-signed APK for App Lab sideload
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("com.google.zxing:core:3.4.1")   // draws the setup QR with no relay needed
    implementation("com.squareup.okhttp3:okhttp:4.12.0")   // WebSocket client for live stream
}
