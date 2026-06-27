# SensorBio Android SDK — binary distribution

This repository is a **Maven repository** (served via GitHub Pages) for the SensorBio
Android SDK. Add one repository URL and one dependency coordinate to integrate.

> This repo is **generated** — the `com/…` tree is published by the SDK's release script
> (`scripts/release.sh` in `mobile_sensorbio_sdk_android`). Do not hand-edit artifacts.

## Integration

Maven URL:

```
https://getsensr-io.github.io/mobile_sensorbio_sdk_android_binary/
```

### Kotlin DSL (`settings.gradle.kts`)

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://getsensr-io.github.io/mobile_sensorbio_sdk_android_binary/") }
    }
}
```

### App module (`app/build.gradle.kts`)

```kotlin
dependencies {
    implementation("com.sensorbio:sensorbio-sdk:0.2.1")
}
```

(Groovy DSL is equivalent: `maven { url '…' }` + `implementation 'com.sensorbio:sensorbio-sdk:0.2.1'`.)

## What you get

- A single self-contained `.aar` — the embedded BLE + edge-algorithm binaries (incl. native
  `.so` for `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`) are bundled inside; no extra
  repositories or coordinates are required.
- All open-source transitive dependencies (gRPC, protobuf, OkHttp, Room, AndroidX lifecycle,
  coroutines, …) are declared in the published POM and resolved automatically from
  `google()` / `mavenCentral()`.
- The public SDK surface only: integrate against the `SensorBioSDK` entry point.

## Requirements

- `minSdk` 29+.
- The SDK declares the Bluetooth + location + foreground-service permissions it needs; they
  merge into your app's manifest. You are responsible for requesting the runtime permissions.

## Available versions

See [`com/sensorbio/sensorbio-sdk/maven-metadata.xml`](com/sensorbio/sensorbio-sdk/maven-metadata.xml).
