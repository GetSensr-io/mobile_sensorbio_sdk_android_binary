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
    implementation("com.sensorbio:sensorbio-sdk:4.0.0")
}
```

(Groovy DSL is equivalent: `maven { url '…' }` + `implementation 'com.sensorbio:sensorbio-sdk:4.0.0'`.)

## What you get

- A single self-contained `.aar` — the embedded BLE + edge-algorithm binaries (incl. native
  `.so` for `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`) are bundled inside; no extra
  repositories or coordinates are required.
- gRPC, protobuf-javalite and Guava are bundled too, relocated under
  `com.sensorbio.sdk.shaded.*`, so they never collide with your app's own copies and you can
  use any version of them. Their versions are listed inside the `.aar` at
  `META-INF/com.sensorbio.sdk/THIRD_PARTY_NOTICES.txt`; the SDK ships its own R8 rules for them.
- The remaining open-source dependencies (OkHttp, Gson, joda-time, Paho MQTT, DiskLruCache,
  Room, AndroidX lifecycle/WorkManager, coroutines, …) are declared in the published POM and
  resolved automatically from `google()` / `mavenCentral()`.
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

## Updating

1. Raise the version in your `implementation(...)` line.
2. Sync Gradle and rebuild.

`SDK_INTERFACE.md` documents the public surface as of each release.

## Release notes

### v4.0.0 — October 14, 2026

This release breaks the integration in several places. An app built against
3.3.0 may not compile against it without the changes below. It also contains
everything from 3.4.0, which was not published separately.

- **Heart-rate zones are identified by `SB_HRZone`, with no display text.** `SB_HRMExerciseZone.zone` says which zone a row is (`BASELINE`, `ZONE_1` … `ZONE_5`), and rows are built with `zone = …`. `SB_HREffortZone` and `SB_HRMExerciseZone.description` are deprecated and will be removed in the next release. They carry the server's English names, so show your own localized zone names instead.
- **A workout's zone list now adds up to the whole workout.** A last `BASELINE` row holds the time below zone 1 plus any time with no heart-rate reading. Its `yAxisLimitChartValue` is `0`, so skip it when you draw chart limit lines or work out the y-axis range. `hexadecimalColor` is now the bar colour, matching the heart-rate chart, not the zone's floor colour. If you colour a chart gradient from it, use your own floor colours.
- **`userAppSettings` is never null.** It is now `StateFlow<SB_UserAppSettings>` and holds the defaults until the settings load, and again after sign-out. Remove your null handling.
- **Exhaustive `when` expressions need new cases.** `SB_PairingFailure.BluetoothOff` is reported straight away from `beginPairing()` when Bluetooth is off, instead of `ScanTimeout` after the scan window. Ask the user to turn Bluetooth on, call `endPairing()`, then `beginPairing()` again. `SB_SleepWriteError` gains `NeverAsleep`, `TimeInFuture`, `WakeUpNotAfterOnset`, `InvalidTimes` and `SessionNotFound`, and `addSleepSession` and `modifySleepSession` now name each refusal. `modifySleepSession` throws `ModifyFailed` only for a refusal the SDK doesn't recognize, so catching `ModifyFailed` alone no longer sees the known ones.
- **Analytics events are now the same on iOS and Android.** Same names, property keys and value formats, every value a `String`, and every event carries `phone_epoch`. `recording started`, `recording ended`, `user consent given` and `device in foreground connected` are no longer sent, so fire your own. `device sync failed due to missing sequence number` is now `device sync failed`, with a `reason`. The full set is in `SDK_INTERFACE.md` §3.2.3.
- **Continuous stress index.** When your organization enables the algorithm, the SDK calculates a stress index on the device and uploads it. The new `getStressIndex(date)` and `fetchStressIndex(date)` return an `SB_StressIndexDay`. Dashboard reads add a `STRESS_INDEX_DASH_METRIC` tile, a new `SB_DashboardMetricType` value, when the day has readings.
- **Offline mode.** An organization can turn on offline mode in its white-label settings. Nothing is uploaded, and server reads throw `SB_OfflineModeException`, while the on-device `get…` reads keep working. Read the setting with `isOfflineMode` or `offlineModeFlow`. The new `getDashboardData(date)` builds the dashboard on the device.
- **A night the band recorded in pieces is one sleep.** Sleeps that overlap or sit less than 60 minutes apart are merged in the on-device reads, the same way the server merges them.
- **`signOut()` returns once the user's local data is gone**, so the next user to sign in can never see it. It now cancels only the SDK's own background jobs, not your app's WorkManager work.
- **Fixes:** a crash on Samsung Android 10 devices, "No firmware image" for every Sensr V1 band, and a crash when the band dropped mid-sync. Band data recorded before the account was created is no longer uploaded, yesterday's detected activities clear at midnight, and the push device ID survives sign-out.

### v3.3.0 — September 24, 2026

- **gRPC, protobuf-javalite and Guava are now bundled inside the SDK, relocated under `com.sensorbio.sdk.shaded.*`.** They no longer collide with your app's own copies (for example Firebase Firestore's protobuf), and you can use any version of them you like. **If your build has `exclude(group = "com.google.guava", module = "listenablefuture")`, remove it.** Earlier SDKs needed it. With this release it removes WorkManager's `ListenableFuture` entirely, and WorkManager fails at runtime. The bundled libraries and their versions are listed inside the `.aar` at `META-INF/com.sensorbio.sdk/THIRD_PARTY_NOTICES.txt`.
- **Heart-rate zones can be computed on the device.** When your organization enables the HR Zone algorithm, `exerciseZoneAttributes` carries zones computed from the wearer's age, sex and resting heart rate, and the new `SB_ExerciseZoneAttributes.deviceComputed` is `true`. If you apply your own max-HR override, skip it when `deviceComputed` is `true`. With the algorithm on, a finished activity's zone breakdown is uploaded first, so its submit can wait up to 90 seconds.
- **Editing or deleting a workout that hasn't uploaded yet now works.** `modifyWorkout` keeps its signature. An edit made before the recording reaches the server is stored, shown on every read right away, and sent once the recording lands. `REMOVE` works on those rows too, so you no longer need to hide delete on a locally built row. A non-`Ok` outcome now means the server rejected the values themselves.
- **`cancelCurrentRecording` really discards the session.** Nothing is stored, reported or uploaded, and a later launch can't bring the session back. `recordingState` goes straight from `Recording` to `Idle`.
- **Countdown recordings end exactly on their target.** A 30-minute meditation stores exactly 30:00, and the auto-stop fires on time with the screen off. The SDK merges the `WAKE_LOCK` permission for you.
- **Auto-detected activities only appear for organizations with activity detection enabled.** For every other organization, `detectedActivities` stays empty whatever the band's firmware sends.
- **Fixes:** a band in recovery mode can now pair and take a firmware update. Zero and invalid PPG metrics are no longer stored or uploaded. Signing out during a cache write no longer crashes.

### v3.2.0 — September 20, 2026

- **Brief surveys no longer wait on the network, and are no longer lost if the
  recording hasn't reached the server yet.** `submitBriefSurvey` keeps its
  signature, but it now returns as soon as the answers are stored on the device,
  so your survey sheet can dismiss the moment the user taps Submit. There is
  nothing to wait on and no spinner to show. The SDK sends the survey once the
  recording it belongs to is known to have landed, retries on failure, and
  survives backgrounding and relaunch. Previously a survey sent before its
  recording arrived had nothing to attach to: the server accepted it, returned an
  empty id, and the answers were orphaned with nothing on screen to say so.
- **A survey shows up in your UI before it is sent.** Every read that returns a
  survey — `fetchWorkoutDetail`, `fetchMeditationGraph`, `fetchSleepDetail` /
  `sleepDetailUpdates`, and the local workout / meditation replays — merges in
  what this device holds, so there is nothing to refetch after a submit. You no
  longer need the returned id either: passing `survey.id = null` every time is
  correct and cannot create a duplicate.
- **You can set the environment before the SDK starts using one.** New
  `SB_AppConfig.environment` is applied at the top of `initialize`, before any
  network activity. `initialize` makes authenticated calls of its own, and
  `SensorBioSDK.environment` could only be assigned after it returned, so on a
  non-production build those first calls went to production with the wrong token
  and could end the session. The `environment` setter still works for switching
  at runtime.
- **A token refresh that can't finish no longer ends the session.** A refresh
  response with no recognised status was treated as a dead refresh token. Only the
  server's four real rejections are final now; anything else is retried on the
  next call.
- **Activity and meditation heart-rate charts no longer draw a comb.** Two of the
  band's heart-rate channels were merged side by side and drawn as one line
  wherever they disagreed. The band's continuous heart-rate channel is now the
  series, and the algorithm's samples only fill its gaps. Meditation also drops
  implausible heart-rate readings, as activity and spot check already did.
- **Sleep stages stay aligned with their timestamps.** Trimming the start of a
  night shortened the stage list but not the timestamp list, so the night could be
  stored with its wake-up before its onset and rejected by the server, or uploaded
  with no stage data at all. A backwards sleep window is now discarded instead of
  stored and uploaded.
- **A sleep the server has permanently rejected is no longer retried forever.**
  The SDK used to retry it every 10 seconds for as long as the app ran. A
  temporary failure no longer discards a sleep either: it backs off, up to every
  30 minutes, and keeps the night.
- **Organization settings survive a cold, offline launch.** A signed-in app that
  launched with no connection used to come up on stock defaults — a spot check
  used the built-in five minutes instead of your configured duration, for
  example. The last fetched settings are now restored during `initialize`.

### v3.1.1 — September 14, 2026

- **Fixes a crash at launch on a fresh, signed-out install.** 3.1.0 refused a
  startup call that needs a session in a way that crashed the process on the main
  thread within seconds of launch, before any sign-in screen appeared. Signed-in
  users were unaffected. **If you are on 3.1.0, upgrade.**

### v3.1.0 — September 14, 2026

> Crashes on launch on a fresh, signed-out install. Use 3.1.1 or later.

- **Which calls need a session now matches the server's own list exactly.** An
  authenticated call with no credential is refused on the device instead of being
  sent for the server to reject.
- **`reauthenticationRequired` is only sent when there was a session to lose.**
  It no longer fires at a user who is signed out or part-way through registering,
  so a host that turns the event into a sign-out can't sign out someone who was
  never signed in.

### v3.0.0 — September 11, 2026

This release breaks the integration in several places. An app built against
2.3.0 does not compile against it without the changes below.

- **`initialize` takes only a `Context`.** `SB_AppType` is gone, and so are
  `SB_AppConfig.appType` and `appFlavor`. Neither meant anything to an
  integration, and the old README snippet's `BuildConfig.FLAVOR` didn't compile
  in an app without product flavors. `SensorBioSDK.initialize(this)` is the whole
  call.
- **Credentials are one object, and the SDK Key never reaches the device.** Set
  `SensorBioSDK.sdkCredentials = SB_SDKCredentials(organizationId, sdkToken)`
  with the pair your backend's token exchange returns, then call
  `registerUser(userId)`. The SDK previously sent the raw organization key on
  every authenticated call after registering, so your app had to hold it, which
  defeated the token exchange. Remove `sdkKeyCredentials`, `sdkTokenProvider` and
  the `sdkToken` argument to `registerUser`; all three are gone. The organization
  id is remembered for you, so a relaunch into a restored session sets nothing.
- **The SDK no longer signs the user out when a session can't be recovered.** It
  emits `reauthenticationRequired` and leaves the decision to you: only your
  backend can mint a fresh token, and a sign-out also deletes local data,
  including the paired band. Collect `reauthenticationRequired` and route the user
  from there. `signOutComplete` is deprecated and is never emitted.
- **Expired sessions are actually refreshed now.** In 2.x the refresh-and-retry
  path never ran on a device, so an expired access token left the dashboard empty
  indefinitely, with the session neither recovered nor ended.
- **Signing out now fully clears the user.** The profile could reappear
  immediately after a sign-out, leaving the user's name and details on screen.
- **Firmware activity auto-detection follows your organization's setting**
  instead of always being off.

## Support

For integration help, contact support@sensorbio.com.
