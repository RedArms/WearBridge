// Optional phone app for the Health Data Bridge: reads Samsung Health on the
// Android phone the watch is paired with (sleep stages and score, SpO2, blood
// pressure, heart rate) and sends it to the iPhone app through an end-to-end
// encrypted internet mailbox, and to the watch app over the Data Layer.
//
// Only built when the Samsung Health Data SDK AAR is in relay/libs/ (see
// settings.gradle.kts). Download it from developer.samsung.com/health/data;
// its license doesn't allow committing it.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.wearos.ancsbridge.relay"
    compileSdk = 35

    defaultConfig {
        // Must match the watch app: the Data Layer only connects apps with the
        // same application ID and signing certificate.
        applicationId = "com.wearos.ancsbridge"
        minSdk = 29 // Samsung Health Data SDK requirement
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":shared"))

    // Samsung Health Data SDK (local AAR) and the runtime classes it expects but doesn't declare
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("org.jetbrains.kotlin:kotlin-parcelize-runtime:2.1.0")

    implementation("com.google.android.gms:play-services-wearable:18.2.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    // QR code for pairing the iPhone with the internet relay
    implementation("com.google.zxing:core:3.5.3")

    testImplementation("junit:junit:4.13.2")
}
