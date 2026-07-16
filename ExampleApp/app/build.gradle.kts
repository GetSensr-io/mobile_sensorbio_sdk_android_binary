plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.sensorbio.example"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.sensorbio.example"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

// gRPC pulls in Guava, which *contains* `com.google.common.util.concurrent.ListenableFuture`, while
// some transitive dep also pulls the empty `listenablefuture:1.0` marker artifact that declares the
// same class — a duplicate-class build failure. Excluding the marker is the standard Android fix.
configurations.configureEach {
    exclude(group = "com.google.guava", module = "listenablefuture")
}

dependencies {
    // The entire SensorBio integration: one coordinate, resolved from the public Maven repo.
    // It brings the embedded BLE + edge binaries and declares its OSS transitive deps (incl. coroutines).
    implementation("com.sensorbio:sensorbio-sdk:0.14.1")

    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
