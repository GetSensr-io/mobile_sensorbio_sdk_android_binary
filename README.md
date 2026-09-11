# SensorBio Android SDK — binary distribution

This repository is a **Maven repository** (served via GitHub Pages) for the SensorBio
Android SDK. Add one repository URL and one dependency coordinate to integrate.

This repository contains:

- **`com/sensorbio/sensorbio-sdk/`** — the published `.aar` + `.pom` + `maven-metadata.xml` (the Maven tree).
- **[`SDK_INTERFACE.md`](./SDK_INTERFACE.md)** — the public API reference (the surface a customer app calls).
- **[`ExampleApp/`](./ExampleApp)** — a reference Jetpack Compose integration you can build + run.

> The `com/…` tree + `SDK_INTERFACE.md` are **generated** by the SDK's release script
> (`scripts/release.sh` in `mobile_sensorbio_sdk_android`). Do not hand-edit them.

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
    implementation("com.sensorbio:sensorbio-sdk:3.0.0")
}
```

(Groovy DSL is equivalent: `maven { url '…' }` + `implementation 'com.sensorbio:sensorbio-sdk:3.0.0'`.)

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

## Use the SDK

`SensorBioSDK` is the single entry point. Initialize once, then call it.

**Authentication needs one endpoint on your own server.** Your organization
SDK Key (`sbsk_…`) is long-lived and org-wide, so it stays on your server;
your server exchanges it for a single-use `sdk_token` that is worth one
register-or-login, for one user, for a few minutes. Your app never sees the
key.

```kotlin
// Application.onCreate
SensorBioSDK.initialize(this)
SensorBioSDK.environment = SB_Environment.PRODUCTION

// 1. Your backend calls POST /sdk/v1/token with `Authorization: SDKKey sbsk_…`
//    and returns the organization_id + the single-use sdk_token.
val minted = yourBackend.mintSensorBioToken()

// 2. Hand both to the SDK.
SensorBioSDK.sdkCredentials = SB_SDKCredentials(
    organizationId = minted.organizationId,
    sdkToken = minted.sdkToken,
)

// 3. Register or log in. The first call for a given userId registers;
//    later calls log the same user back in. There is no email/password
//    sign-in on the customer surface.
val outcome = SensorBioSDK.registerUser(userId = yourUserId)

// Observe device + read metrics
SensorBioSDK.connected.collect { isConnected -> /* … */ }
val dashboard = SensorBioSDK.fetchDashboardData(date = Instant.now(), tzOffset = tz)
```

Get a fresh token for every registration and never cache one — a spent token
fails inside `registerUser` as an authentication error, far from its cause.

The SDK Key never reaches the device, and there is nothing else to configure:
the organization is remembered for you, so an app relaunching into a restored
session sets nothing.

See **[`SDK_INTERFACE.md`](./SDK_INTERFACE.md)** for the full public surface, and **[`ExampleApp/`](./ExampleApp)**
for a complete reference integration (token exchange → `registerUser` → pair → dashboard with metric
detail views, insights, profile).

## Documentation

- **[`SDK_INTERFACE.md`](./SDK_INTERFACE.md)** — public API reference (synced from the SDK repo at each release).
- **[`ExampleApp/README.md`](./ExampleApp/README.md)** — building + running the reference app.

## Available versions

See [`com/sensorbio/sensorbio-sdk/maven-metadata.xml`](com/sensorbio/sensorbio-sdk/maven-metadata.xml).

## Support

For integration help, contact support@sensorbio.com.
