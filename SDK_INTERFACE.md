# SensorBioSDK — Android Integration & Interface Reference

This document describes the **public** customer-facing surface of the SensorBio Android SDK
(`com.sensorbio:sensorbio-sdk`). The SDK ships as a single `.aar` consumed from a Maven repository
(see [README.md](./README.md) for integration); `SensorBioSDK` is the only type a customer app calls.

> **Source of truth.** This file lives on `mobile_sensorbio_sdk_android` `main` and tracks the latest
> public surface. A copy is synced into the
> [binary repo](https://github.com/GetSensr-io/mobile_sensorbio_sdk_android_binary) at each tagged
> release; customers pinning a binary version should read the copy in the binary repo for the surface
> that matches their pin. The SDK-repo version may include symbols not yet in the most recent binary.

> **Visibility note.** This covers the customer-facing API only. SDK-internal symbols and first-party
> (`internal`-flavor) API are not part of the published binary and are not documented in the customer
> copy. SDK `version = "1.0.0"`.

The design rule: **the app integrates with ONLY the `SensorBioSDK` object.** Everything else the host
needs is either a domain type it receives (`SB_*`) or a **hook** it supplies (§4).

---

## 1. Adding the SDK

The SDK is distributed as a binary `.aar` from a Maven repository served over GitHub Pages. Add the
repository and the coordinate:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://getsensr-io.github.io/mobile_sensorbio_sdk_android_binary/") }
    }
}

// app/build.gradle.kts
dependencies {
    implementation("com.sensorbio:sensorbio-sdk:2.1.0")
}
```

The single coordinate brings everything: the SDK plus the embedded BLE + edge-algorithm binaries
(including native `.so` for `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`) are bundled inside the one
`.aar`; all open-source transitive dependencies (gRPC, protobuf, OkHttp, Room, AndroidX lifecycle,
coroutines, …) are declared in the POM and resolved automatically from `google()` / `mavenCentral()`.


**Platform:** `compileSdk 36`, `minSdk 29`, Java 17.

### 1.1 Permissions (host responsibility)

The **host app requests Bluetooth runtime permissions** before any BLE operation (`beginPairing` /
`connect` / `syncDeviceData` / firmware update / …). The SDK does **not** check or request
permissions — a BLE call made without permission throws `SecurityException`. (Mirrors iOS, where the
integrator supplies the usage strings and drives the prompt.)

Request at runtime:

- **Android 12+ (API 31+):** `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, **and** `ACCESS_FINE_LOCATION`.
- **API < 31:** `ACCESS_FINE_LOCATION`.

**Location is required at every API level** because the SDK's `BLUETOOTH_SCAN` is **not** declared
`neverForLocation` — Android therefore treats scan results as location-deriving and returns none unless
fine-location is granted. **Location Services must also be turned on** on the device, or scans come back
empty. (A future SDK release may add the `neverForLocation` flag to drop the location requirement on
12+; until then, request location.) The SDK's manifest declares the BLE permissions; the **grant** is
the host's to obtain. See `ExampleApp/`'s `MainActivity`/`PairDeviceScreen` for the request pattern.

---

## 2. Lifecycle & configuration

### 2.1 Startup — one entry point

```kotlin
SensorBioSDK.initialize(context, SB_AppConfig(appType = …, appFlavor = …))   // once, top of Application.onCreate
```

`initialize` stands up the encrypted `sdk_prefs` store, runs the one-time legacy-prefs migrator (no
re-login on upgrade), and wires the subsystems. `SensorBioSDK.isInitialized: Boolean` reports whether
it has run.

### 2.2 Static configuration knobs

Plain `var`s the host sets once after `initialize`:

| Property | Type | Controls |
|---|---|---|
| `environment` | `SB_Environment` | gRPC target (dev/prod); runtime-switchable |
| `sdkKeyCredentials` | `SB_SDKKeyCredentials?` | org credentials for SDK-key mode (non-null ⇒ SDK-key auth); in-memory only, never persisted. Set once at launch before `registerUser` — see §5.1 |
| `logHandler` | `((SB_LogLevel, String?, Array<out Any?>) -> Unit)?` | sink for SDK logs (unset = silent) |

App identity is set-once config passed into `initialize(context, SB_AppConfig(...))` — `appType`,
`appFlavor`, `appDisplayName`, `enableCrashlytics`. App **version + build** are self-read from the host
`PackageManager` at init. `SB_AppConfig.firmware` (`SB_FirmwareConfig`: S3 bucket + Cognito pool) is the
optional firmware-fetch config consumed by `getFirmware` — set on first-party `internal` builds only,
null (and ignored) in customer builds.

---

## 3. The `SensorBioSDK` facade

`SensorBioSDK` is a Kotlin `object` (the single entry point). It exposes observable state, one-shot
event streams, recording control, device & BLE control (§3.4), and the flat server-API methods (§5).

### 3.1 Observable state — `StateFlow` (collect from a ViewModel)

| Flow | Element | Meaning |
|---|---|---|
| `pairedDevice` | `SB_PairedDeviceState?` | the single paired device's slim identity (macAddress/name/type); null when none paired |
| `haveDevice` | `StateFlow<Boolean>` | a device is paired (derived from `pairedDevice`) |
| `pairingState` | `SB_PairingState?` | where the open pairing transaction stands; null when none is open (§3.4 *Pairing*) |
| `updateRequired` | `StateFlow<Boolean>` | a firmware update is **required** (current firmware outside the min/max window); SDK-owned decision |
| `updateSuggested` | `StateFlow<Boolean>` | a firmware update is **suggested** (newer baseline exists, not mandatory) |
| `latestFirmwareVersion` | `StateFlow<String?>` | the version the host would flash to; null when no device/firmware or upgrades disabled |
| `deviceSyncing` | `StateFlow<Boolean>` | a device sync is in progress |
| `percentSynced` | `StateFlow<Int>` | connected device's sync progress 0–100 (**100 = up to date**); resets to 100 on disconnect |
| `recordingState` | `SB_RecordingState` | recording FSM (Idle / Recording(elapsed,target) / Finalizing(phase)) |
| `canFinalize` | `Boolean` | whether the active recording may be finished early |
| `isRecordingPaused` | `StateFlow<Boolean>` | whether the active recording is paused (drives the Pause/Resume button); `false` off-session |
| `lastSyncedTemp` | `SB_LiveMetric?` | latest skin-temp reading from sync as a dashboard live-metric (value/unit in the user's °C/°F units); null until first |
| `exerciseZoneAttributes` | `SB_ExerciseZoneAttributes?` | HR effort-zone config; null when unconfigured, auto-clears on logout |
| `buttonTaps` | `StateFlow<Int?>` | latest device button-tap count. Pairing no longer needs this — the SDK consumes it internally to detect the confirmation press (§3.4 *Pairing*); null until first tap |
| `connected` | `StateFlow<Boolean>` | BLE connection is up |
| `bluetoothAvailable` | `StateFlow<Boolean>` | phone BLE availability |
| `charging` | `StateFlow<Boolean>` | device is on its charger |
| `batteryLevel` | `StateFlow<Int?>` | device battery 0–100; null until first |
| `lastSyncStartEpoch` / `lastSyncEndEpoch` | `StateFlow<Long?>` | bulk-sync window endpoints (epoch ms); null until first sync |
| `lastSyncd` | `StateFlow<Long?>` | wall-clock epoch (ms) of the last completed sync; survives disconnect; null until first |
| `networkStatus` | `StateFlow<SB_NetworkStatus>` | phone reachability (UNREACHABLE/WIFI/CELLULAR/OTHER) |
| `haveUnuploadedPackets` | `StateFlow<Boolean>` | synced packets buffered on disk awaiting upload |
| `inflightSubmissions` | `StateFlow<List<SB_RecordingSubmissionInfo>>` | finished recordings the server hasn't ingested yet (newest first); host pins them atop the timeline as Uploading…/Processing…/Couldn't-sync cards. Reconcile with `reconcileSubmissions(...)` after each timeline fetch |
| `webAppCookie` | `StateFlow<String?>` | web-dashboard auth cookie from the login response; set on sign-in, cleared on sign-out, persisted |
| `userProfileFlow` | `StateFlow<SB_UserProfile?>` | reactive signed-in profile; emits on sign-in / profile / photo / goals / globals refresh / sign-out |
| `userAppSettings` | `StateFlow<SB_UserAppSettings?>` | reactive app-settings (units/preferences); null until loaded, manual refresh via `refreshUserAppSettings()` |
| `session` | `StateFlow<SB_Session?>` | reactive auth-session identity, derived from the profile; null when signed out |
| `organization` | `StateFlow<SB_OrganizationMembership?>` | reactive org membership; null when signed out |
| `featureFlags` | `StateFlow<List<String>>` | reactive feature flags; set at login + globals refresh, cleared on sign-out |
| `forceUserToUpdatePassword` / `forceUserToUpdateProfile` | `StateFlow<Boolean>` | forced-on-login flags; set at login, cleared on sign-out |

> **An absent birthday is null, not a sentinel.** `SB_UserProfile.birthday` is
> `SB_CalendarDate?` — null when the server has no birthday for the user — and
> `SB_UserProfile.age` is `Int?` for the same reason. Neither is ever the zero
> calendar date. Before SB-1837 both platforms represented "no birthday" as that
> zero date, which no type check could distinguish from a real one: iOS computed an
> age around 2025 from it (a max HR of roughly -912), and Android threw
> `IllegalFieldValueException` out of `age`, `BMR` and `CFF`. Host code that has to
> produce a number should substitute `SB_UserProfile.DEFAULT_AGE_YEARS`, which is
> what the SDK's own internal compute uses and is the same on both platforms.

### 3.2 Event streams — `SharedFlow` (one-shot)

| Flow | Payload | Purpose |
|---|---|---|
| `deviceReset` | `SB_DeviceResetResult` | device-reset outcome |
| `analyticsEvents` | `SB_AnalyticsEvent` | SDK telemetry events incl. recording start/end (name + properties); host forwards to its backend |
| `latestBookend` | `SB_LatestBookend` | activity window resolved by sync (spot-check confirm) |
| `captureEnded` | `SB_CaptureEndEvent` | the band reported capture ended, with the reason (`SB_CaptureEndReason`: worn-stop / timer / firmware / button / our own BLE stop). Decoded from the firmware `.event` packet, which is written unconditionally even when the bookend is skipped, so it is the dependable end-of-session signal. Consumed internally by the recording engine (SB-1745); hosts may observe it but need not |
| `deviceLinkFailed` | `SB_DeviceLinkFailure` | a device-link (serial-enforcement) rejection; host alerts "wrong device" / retry |
| `firmwareProgress` | `Float` | raw firmware-flash progress percent (0–100); pairs with the suspend `updateFirmware()` (§3.4) |
| `syncCompleted` | `SB_SyncResult?` | a device sync drained; non-null on detailed completion, null on short-circuit |
| `biometricRecordResult` | `SB_BiometricRecordResult` | spot-check submit outcome (recording id on success / error on terminal failure) |
| `spotCheckReport` | `SB_SpotCheckDetails` | the spot check's report, built from local data at finalize — **before** the submit and without waiting on it (see below) |
| `biometricRecordProcessed` | `Unit` | spot-check data synced + submit dispatched; host resets post-biometric UI |
| `hr` / `hrv` / `rr` / `snr` / `bbi` / `ppg` | `SB_HeartRateSample` / `SB_HrvSample` / `SB_RespiratoryRateSample` / `SB_SnrSample` / `SB_BbiSample` / `SB_PpgSample` | high-frequency per-sample biometric streams during a live/manual session (buffered + `DROP_OLDEST`) |
| `ecg` | `SB_EcgSample` | ECG waveform samples (V3 hardware) |
| `spo2` | `SB_Spo2Sample` | standalone live SpO2 stream (declared for parity; surfaced via synced trending) |
| `sleepStored` | `Unit` | a sleep session was decoded + stored on-device |
| `sleepUploaded` | `Unit` | a sleep session finished uploading; host refreshes affected UI (e.g. dashboard) |
| `sleepDetected` | `SB_DetectedSleep` | a valid auto-detected on-device sleep session was finalized (start/end epoch ms); host prompts the post-sleep survey |
| `deviceConnected` | `Unit` | raw BLE link came up (signal only; read identity from the facade) |
| `deviceFullyConfigured` | `Unit` | the device finished full configuration and is usable (rising edge of `isFullyConfigured`) |
| `deviceDisconnected` | `String` | the device disconnected; payload = macAddress |
| `hrSyncFinishedForActivity` | `Unit` | a recorded activity's HR/data finished syncing+uploading |
| `signOutComplete` | `Unit` | involuntary sign-out finished (terminal refresh-token failure); host runs teardown + routes to login |
| `subscriptionLost` | `Unit` | the server rejected an authenticated call for no active device subscription; host signs the user out + explains why (see §3.2.1) |

#### 3.2.1 `subscriptionLost` and the subscription block

When the server rejects an authenticated call because the account has no active device subscription,
the SDK does two things: it emits `subscriptionLost`, and it enters a **subscription block** — it
drops the BLE link and refuses to auto-connect or sync until the block lifts. The block is a
data-exfil guard, not a UI state: BLE sync pulls data off the band with no server round-trip, so the
gate is persisted and deliberately survives relaunch.

What a host needs to know:

- **The band is inert while blocked.** No auto-connect on launch, no reconnect after a drop. The
  paired record stays, and pairing a *new* band still works, so "paired but never connects" is the
  shape the user sees.
- **It can arrive in any app state, and not only after a call you made.** Background uploads and the
  SDK's own foreground re-verification both reach the server, so this can land mid-session, while
  backgrounded, or moments after launch. Act on it without needing a screen to present on: sign the
  user out first and explain afterwards. Gating the sign-out behind a dialog is what left iOS users
  signed in with a dead band and no explanation (SB-1884) — MySensr signs out immediately, states the
  reason on the onboarding landing, and adds a local notification when the app was not in front of the
  user.
- **It lifts by itself when the subscription is genuinely fine.** Any successful authenticated RPC
  clears the block and reconnects the band — the server gates its whole authenticated surface on the
  subscription, so a `200` is proof. A block armed by a transient server condition therefore heals on
  the next successful call or the next foreground, with no alert and no user action.
- **Inconclusive is not "cleared".** Offline or a transport failure leaves the block standing and
  re-checks next foreground, rather than freeing the band on no evidence.

So the host's job is only the sign-out plus the explanation. Do not build a local mirror of the
blocked state, and do not treat one emission as permanent — the SDK owns the lifecycle and stops
emitting once the server stops rejecting.

#### `spotCheckReport` — the report, before the upload

A spot check's report is **not** a server calculation. bioedge derives it on the phone from the recording's beat-to-beat intervals, the submit ships those numbers up as its `SpotCheckData`, the server persists them, and `fetchSpotCheckDetails(id)` hands the same values back. A host that waits for the round trip is holding the user on a spinner to retrieve numbers the device already produced.

`spotCheckReport` fires once per finalized spot check, at the end of finalize — before the submit, and independent of whether it ever succeeds. Every field the server would return is populated, including the HR graph (read from the same `continuous_hr` rows the upload sends) and the HRV baseline (read from the persisted user). There is no server-only field to wait for, so the report needs no refresh once the submit lands.

The two outcomes that produce **no** report instead throw out of `recordDetailedBiometrics`, which is where this platform already reports finalize verdicts:

| outcome | channel | meaning | host should |
|---|---|---|---|
| the report | `spotCheckReport` | built from the data being uploaded | show it now — no wait, works offline |
| `SB_RecordingError.NotEnoughData` | throw | the window is empty *after a settled sync*: nothing was captured, so no report exists and none will | tell the user there wasn't enough data |
| `SB_RecordingError.StillSyncing` | throw | the window is empty because the post-stop sync never settled — the data may still be on the band | tell the user the band hasn't finished handing the scan over |

Neither error submits: a dataless submit leaves an in-flight card nothing can ever reconcile away (SB-1745). All three are terminal for the scan — none is followed by another.

> **iOS divergence.** iOS publishes the same three verdicts as one `SB_SpotCheckReportEvent` enum (`.ready` / `.unscoreable` / `.deferred`) on its equivalent subject, because its finalize cannot throw. The information is identical; only the channel differs. iOS's `.ready` also omits `pdfReportURL` — Android's `SB_SpotCheckDetails` has no such field, so this report is complete.

### 3.3 Recording control (suspend, on the facade)

| Member | Signature | Notes |
|---|---|---|
| `recordDetailedBiometrics` | `suspend (duration, minDuration) -> Unit` | spot-check, **awaits the whole session end-to-end**; auto-finalizes on the countdown or via `finishCurrentRecording()`. The report arrives earlier, on `spotCheckReport` (§3.2). Throws `NoPairedDevice`/`BleStartFailed`/`TooShort`/`BleStopFailed`/`NotEnoughData`/`StillSyncing`; cancel the coroutine to abort |
| `recordActivity` | `suspend (activityName, minDuration) -> Unit` | activity (count-up). **Awaits the whole session end-to-end** — returns on the successful submit (host then runs its survey), throws `NoPairedDevice`/`BleStartFailed`/`TooShort`/`NotEnoughData` |
| `recordMeditation` | `suspend (duration, minDuration, sessionName?, sessionNameAlreadyExists, surveyUrl?) -> Unit` | meditation (always a `duration`-sec countdown). **Awaits end-to-end**; same return/throw contract as `recordActivity` |
| `awaitActiveRecordingCompletion` | `suspend () -> Unit` | await a recording the host did **not** start via `record*()` (a process-kill resume) so it can run the survey + surface errors identically; no-op when idle |
| `finishCurrentRecording` | `suspend () -> Unit` | signal stop + window-sync + schedule submit; outcome surfaces in the in-flight `record*()` await |
| `cancelCurrentRecording` | `() -> Unit` | abort without submit |
| `pauseRecording` | `() -> Unit` | pause the running activity/meditation: freeze the elapsed clock + stop the device PPG stream (no biometrics accrue). No-op if not recording / already paused / spot-check |
| `resumeRecording` | `() -> Unit` | resume a paused recording: record the pause window + restart the device stream. Each paused span is excluded from the session's `active_workout_segments` |
| `resumeActiveRecording` | `() -> Unit` | resume a recording persisted across a process kill (crash-restore / app launch). Since SB-1745 this is *restore*, not blindly *resume*: if the persisted envelope carries a stop intent (the user had already tapped End before the process died) it re-enters finalize at the persisted stop instant instead of resuming the count-up; if the envelope is older than 24h with no stop intent it is discarded rather than resurrected. Only a genuinely still-running session resumes live |
| `activeRecording` | `val SB_PersistedRecording?` | crash-restore: a recording persisted across process death |
| `activeRecordingState` | `val SB_ActiveRecordingInfo?` | flat snapshot of the live activity/meditation recording the engine is driving (for the live screen) |

### 3.4 Device & BLE control (on the facade)

Pairing, connection, device commands, and sync are driven through the facade. (Device-telemetry
observation is the §3.1 `connected` / `charging` / `batteryLevel` / `bluetoothAvailable` / `buttonTaps`
flows + the §3.2 `deviceConnected` / `deviceFullyConfigured` / `deviceDisconnected` events, plus the
connected-device identity below.)

**Pairing — one SDK-owned transaction**

Pairing is **one transaction owned by the SDK**, not a sequence of host calls. Three methods open,
advance, and close it; everything in between is reported on the `pairingState` flow (§3.1).

| Member | Signature | Notes |
|---|---|---|
| `beginPairing` | `() -> Unit` | open a transaction and start scanning. Calling it again restarts one already open |
| `selectDevice` | `(id: String) -> Unit` | pair with one of the bands from the current `Scanning` payload; `id` is the band's `macAddress`, the same identifier `connect`/`disconnect` take |
| `endPairing` | `() -> Unit` | cancel a running transaction, or dismiss a terminal one. Idempotent, safe from any state |
| `pairingState` | `StateFlow<SB_PairingState?>` | the transaction's state; `null` when none is open |

```kotlin
sealed interface SB_PairingState {
    data class Scanning(val devices: List<SB_DiscoveredDevice>) : SB_PairingState  // cumulative, de-duplicated
    data class Connecting(val device: SB_DiscoveredDevice) : SB_PairingState
    data class AwaitingConfirmation(val device: SB_DiscoveredDevice) : SB_PairingState  // band blinking; press its button
    data class Paired(val device: SB_PairedDeviceState) : SB_PairingState          // terminal
    data class Failed(val reason: SB_PairingFailure) : SB_PairingState             // terminal
}

enum class SB_PairingFailure {
    ScanTimeout, ConnectTimeout, ConnectionLost, NotConfirmed, DeviceUnavailable
}
```

Render the state; make three calls:

```kotlin
lifecycleScope.launch {
    SensorBioSDK.pairingState.collect { state ->
        when (state) {
            is SB_PairingState.Scanning             -> showList(state.devices)   // tap → selectDevice(device.macAddress)
            is SB_PairingState.Connecting           -> showConnecting()
            is SB_PairingState.AwaitingConfirmation -> showPressTheButton()
            is SB_PairingState.Paired               -> showAllSet(state.device)   // dismiss → endPairing()
            is SB_PairingState.Failed               -> showCantReachBand(state.reason)  // dismiss → endPairing()
            null                                    -> showStartScreen()
        }
    }
}
SensorBioSDK.beginPairing()
```

**What the SDK does inside the transaction**, so the host doesn't: scanning and discovery
de-duplication; re-issuing the scan once Bluetooth becomes available (a scan issued while the adapter is
still coming up is refused, which is what happens on a first-run pair with the permission prompt still on
screen); connecting; the on-band confirmation choreography (blink + buzz, listening for the button,
acknowledging the press, and refusing a late press once the window closes); persistence; server
registration including the device-type wire mapping; configuring the band over the link that is already
up; suppressing BLE auto-reconnect so a previously-paired band can't steal the link mid-pair; and every
timeout — scan, connect, confirm, and a configure backstop.

The host keeps what is genuinely host-side: the **runtime Bluetooth permissions** (§1.1 — the SDK scans,
it never asks for the grant), which screen to show, analytics, and any cosmetic hold or countdown.

> **Nothing is persisted, registered, or reported to the server until the user confirms on the band.** A
> transaction that ends any way other than `Paired` — cancelled, timed out, dropped — leaves **no trace**:
> no persisted device, no BLE registration, no server-side link event. There is nothing for the host to
> undo, which is why no un-pair or reset call is needed on the failure paths.

`Paired` means the band is ready: persisted, registered, reported, and **configured** — no reconnect
required. A band that pairs but fails to *configure* (firmware too old to answer some setup commands)
still reports `Paired`, because the pair is real and configuration retries on the next reconnect;
unpairing it would put the forced-firmware-update flow that fixes it out of reach. The two terminal
states persist until `endPairing()`, so a host showing an "all set" or "can't reach the band" screen
keeps the transaction open until that screen is dismissed — and the SDK keeps the forced-firmware prompt
(`updateRequired`) suppressed for that whole window. A required update is raised on the *reconnect* after
pairing, so a host that wants it raised promptly drops the link (`disconnect()`) when it dismisses the
all-set screen.

**Connection, unpair & device commands**

| Member | Signature | Notes |
|---|---|---|
| `connect` | `(id: String) -> Unit` | manual reconnect of the paired device; **not** used for pairing |
| `disconnect` | `(id: String? = null) -> Unit` | drop the connection (null = current device) |
| `removeDeviceFromPairedDevices` | `(id: String) -> Unit` | unpair |
| `reset` | `() -> Unit` | factory-reset the device |
| `userLED` | `suspend (red=…, green=…, blue=…, blink=…, seconds: Int)` | LED control (awaits the BLE write) |
| `hapticMotor` | `suspend (pulse: Boolean = false, intensity: Int, seconds: Int)` | run the haptic motor (awaits the BLE write). `pulse` = pulse vs. solid (haptic analogue of `blink`); `intensity` is 0..100% |
| `updateFirmware` / `setFirmwareUpdateDeviceId` | `suspend (url, delay?, size?)` **or** `suspend (data: ByteArray, delay?, size?)` *(throws `SB_FirmwareUpdateError(canRetry)`)* / `(deviceId: String?)` | firmware flash — `url` = a local file, or pass `data` = the bytes from `getFirmware` (SDK temp-files them internally); progress on the `firmwareProgress` event (§3.2). `setFirmwareUpdateDeviceId` is the session-guard seam |
| `updateConnectedDeviceFirmware` | `(packet: SB_FirmwareVersionPacket) -> Unit` | apply a resolved firmware-version packet to the connected device |
| `migrateDeviceTypeAfterFlash` | `() -> Unit` | call after a flash completes: if the device was an Alter/AlterV2 migrated onto Sensr firmware, rewrite its stored type to the Sensr equivalent + re-register so the forced-update gate stops re-firing on reconnect. No-op for a same-type flash |
| `setAskForDeviceResponse` | `(enable: Boolean) -> Unit` | device button-tap prompting. **Not needed for pairing** — `beginPairing` runs the LED/haptic confirmation and the button listening itself; reach for this (and `userLED` / `hapticMotor`) only for your own device interactions outside a pair |
| `syncDeviceData` | `suspend (force: Boolean = false) -> …` | trigger a packet-count sync |
| `airplaneMode` | `suspend () -> Unit` | put the connected device into airplane mode; persists + publishes `deviceAirplaneModeOn`, cleared on next connect |
| `performBackgroundTasks` | `suspend () -> …` | run queued background work (WorkManager entry) |

**Connected-device identity (on the facade; populated after connect, cleared on disconnect):**

| Member | Type | Notes |
|---|---|---|
| `serialNumber` | `StateFlow<String?>` | connected device serial, null until read |
| `firmwareVersion` | `StateFlow<String?>` | connected device firmware revision, null until read |
| `hardwareRevision` | `StateFlow<String?>` | connected device hardware revision string, null until read |
| `type` | `StateFlow<SB_BluetoothDeviceType?>` | resolved device model (hardware-revision aware), null when disconnected |
| `isConnectedDeviceSensr` | `val Boolean` | connected device is a "sensr"-family device (vs "alter") |
| `isFullyConfigured` | `StateFlow<Boolean>` | connected device has reported its hardware revision |
| `modelNumber` / `manufacturerName` | `StateFlow<String?>` | device-info strings, null until read |
| `bluetoothSoftwareRevision` / `algorithmsSoftwareRevision` / `sleepSoftwareRevision` | `StateFlow<String?>` | per-component software revisions, null until read |
| `worn` | `StateFlow<Boolean?>` | whether the device is currently worn; null until reported |
| `deviceAirplaneModeOn` | `StateFlow<Boolean>` | device put into airplane mode via `airplaneMode()`; hydrated from prefs, cleared on connect |
| `isAirplaneModeActive` | `val Boolean` | convenience snapshot of `deviceAirplaneModeOn` |

**Remote config / globals + lifecycle (also on the facade):**

| Member | Type | Notes |
|---|---|---|
| `remoteGlobals` | `SB_RemoteGlobals` | goals/branding globals |
| `rawDataEnabled` | `val Boolean` | whether raw sensor logging is active (sensor-config raw channel or legacy `rawSensorDataLogging`); raw logging fills device storage faster, so the host shortens its "haven't synced" reminder (armed off §3.2 `syncCompleted`) when true |
| `attachRemoteGlobals(owner)` / `refreshGlobalState()` | — / `suspend (): SB_OrgMembership` | globals lifecycle auto-refresh / manual refresh returning org membership |
| `deviceId` | `val String` | SDK-owned stable per-install id (generated + persisted in `sdk_prefs`); read-only — the host reads it only to tag its own analytics with the same id |
| `clearLocalRecordingState()` | — | recording-state lifecycle |
| `refreshUserAppSettings()` | `suspend (): SB_UserAppSettings` | force-refresh the app-settings backing the `userAppSettings` flow (§3.1) |


---

## 4. Host seams (the legitimate public extension points)

The public binary exposes a **single host-supplied hook**: `logHandler` (§2.2) — the sink the SDK
writes its logs to (unset = silent). App identity is supplied once as configuration via `SB_AppConfig`
(`appType` / `appFlavor` / `appDisplayName` / `enableCrashlytics`) passed to `initialize` (§2.1).

Everything a host previously wired as a supplied callback or implemented interface is now delivered
through the observable event streams (§3.2) — the host **observes**, it does not implement a seam:

- **Sync notifications** — observe `syncCompleted` and arm your own "haven't synced" reminder from it
  (it fires on both the detailed drain and the nothing-to-fetch short-circuit, so either way the band
  was reached). Removed in favour of this: a `syncNotificationActions` stream of explicit
  SCHEDULE/CANCEL requests, which armed off *connection* events rather than off a sync.
- **Involuntary sign-out** — observe `signOutComplete`, run teardown, and route to login.
- **Lapsed device subscription** — observe `subscriptionLost`, sign out, and explain why (§3.2.1).
- **Analytics** — observe `analyticsEvents` and forward them to your backend.

---

## 5. Server APIs (flat on the facade)

Called directly on `SensorBioSDK.<method>(…)`. One-shot reads are `suspend fun … : SB_…` (pull-to-refresh / single fetch). The cache-backed reads additionally expose an **`…Updates(…): Flow<SB_…>`** sibling (iOS-parity `…Updates` → `AsyncThrowingStream`): a stale-while-revalidate stream that emits the last-known cached value immediately, then the authoritative value (fresh server value, or the final/stale cache per the cache policy — final-past served from disk, today always fetched, `forceRemote` bypass, stale-on-failure fallback). `collect { … }` one of these from a ViewModel instead of pairing a cache peek with a fetch. The `suspend cachedX(…)` peeks were removed in favour of the streams.

| Domain | Methods (flat on `SensorBioSDK`) |
|---|---|
| Dashboard | `fetchDashboardData(date: Instant, tzOffset, forceRemote)`, `dashboardUpdates(date: Instant, tzOffset, forceRemote): Flow<SB_DashboardData>` *(stale-then-fresh stream — replaces the removed `cachedDashboardData` peek)*, `clearDashboardData(date: Instant)` |
| Skin temperature | `getSkinTemperature(date: Instant) -> SB_SkinTemperature?` *(on-device read — aggregates the local `temperature_data` rows for the calendar day into min/max/average + ascending points, all Celsius; no network; null when the day has no points. App applies °C/°F for display.)* |
| Local-first HR points | `getHRPoints(date: Instant) -> SB_HRDataPoints` *(local-first read — see §5.2)* |
| Local-first HRV points | `getHRVPoints(date: Instant) -> SB_HRVDataPoints` *(local-first read — see §5.3)* |
| Local-first RR points | `getRRPoints(date: Instant) -> SB_RRDataPoints` *(local-first read — see §5.4)* |
| Local-first steps | `getStepsPoints(date: Instant) -> SB_StepsDataPoints` *(local-first read — see §5.5)* |
| Local-first calories | `getCaloriesPoints(date: Instant) -> SB_CaloriesDataPoints` *(local-first read — see §5.6)* |
| Local-first sleep | `getSleepDetail(date: Instant) -> SB_SleepDetailDay?` *(local-first read — same shape as the server `fetchSleepDetail`, rebuilt from the on-device `sleep_sessions` row; see §5.7)* |
| Local-first sleep graphs | `getSleepHR(sessionEndTimestamp: Long)`, `getSleepHRV(…)`, `getSleepRR(…)` — each `-> List<SB_TimeValuePoint>` *(the session's HR / HRV / RR timeseries for the sleep biometric graphs; local-first with server-detail fallback; see §5.7)* |
| Local-first sleep disturbances | `getSleepArmDisturbances(sessionEndTimestamp: Long) -> List<SB_SleepDisturbancePoint>` *(the session's arm-restlessness severity timeline, bucketed from on-device `activity_packets`; colour-free `SB_SleepDisturbanceLevel` per epoch; fully local, no fallback; see §5.7)* |
| Trending | `fetchRangeHR`/`fetchDailyHR`, `fetchRangeHRV`/`fetchDailyHRV`, `fetchRangeRR`/`fetchDailyRR`, `fetchRangeSpO2`/`fetchDailySpO2`, `fetchCalories`, `fetchSteps`, `fetchDailyActivityDetail`, `fetchRangeRecovery`/`fetchDailyRecovery` *(all take `date: Instant`; `forceRemote` optional)*. **All four daily biometric reads return the same `SB_BiometricDailyTrending` — see §5.10.** Stale-then-fresh `Flow` siblings: `rangeHRUpdates`/`dailyHRUpdates`, `rangeHRVUpdates`/`dailyHRVUpdates`, `rangeRRUpdates`/`dailyRRUpdates`, `rangeSpO2Updates`/`dailySpO2Updates`, `caloriesUpdates`, `stepsUpdates`, `dailyActivityDetailUpdates`, `rangeRecoveryUpdates`/`dailyRecoveryUpdates`. *(`fetchDailyRecovery`/`dailyRecoveryUpdates` are computed on-device where possible — see §5.8; `fetchDailyActivityDetail`/`dailyActivityDetailUpdates` likewise for `DAY` — see §5.9)* |
| Sleep | `fetchSleepDetail(endDate: Instant, endTimestamp, forceRemote?)`, `fetchSleepAggregation(date: Instant, …, forceRemote?)` *(+ `sleepDetailUpdates(endDate: Instant, endTimestamp, forceRemote?): Flow<SB_SleepDetailDay>` / `sleepAggregationUpdates(date: Instant, …, forceRemote?): Flow<SB_SleepDetailAggregated>` stale-then-fresh streams)*, `fetchSleepSessions(date: Instant)`, `deleteSleepSession(endTimestamp, date: Instant)`, `modifySleepSession(onset: Instant, wakeUp: Instant, endTimestamp, date: Instant) -> String`, `addSleepSession(onset: Instant, wakeUp: Instant)` *(writes throw `SB_SleepWriteError`)* |
| Workouts | `fetchWorkoutDetail(workoutTime: Instant)`, `modifyWorkout(action, date: Instant, timestamp: Instant, …)`, `fetchWorkoutSummary(date: Instant, granularity: SB_SummaryGranularity, workoutName, workoutTime: Instant)`, `fetchWorkoutTimeline(…, direction: SB_PageFetchDirection) -> SB_WorkoutTimelineResult`, `fetchWorkoutRecordingInfo` |
| In-flight submissions | `reconcileSubmissions(entries: List<SB_WorkoutEntry>)` *(flip matched in-flight cards → processed; call after each `fetchWorkoutTimeline` with `result.items.flatMap { it.entries }`; no network)*, `retrySubmission(startTimestamp)` *(re-drive a FAILED submission)* — observe via `inflightSubmissions` (§3.1) |
| Activities | `fetchActivityList(force: Boolean = false) -> SB_ActivityRecordingList`, `fetchTrainedActivities()` |
| Spot-check | `fetchSpotCheckDetails(recordingId)` *(one-shot suspend read; throws on RPC error)* |
| Recording meta | `fetchRecordingMetaInfo(type) -> List<SB_RecordingSessionMetaItem>`, `deleteRecordingMeta(id, name, type)` |
| Insights | `fetchNewInsights`, `submitInsightsFeedback`, `fetchPopulationInsightsMetricList`, `fetchPopulationInsights` |
| Meditation | `fetchMeditationGraph(date: Instant, sessionTimestamp)` |
| Surveys | `submitBriefSurvey(survey)` *(suspend; awaits the upload)* |
| Goals | `fetchGoals()`; `updateGoals(steps, calories, sleep)` *(suspend → `SB_UpdateGoalsOutcome`)* |
| Stats | `fetchDailyStats(startDate, days, includeBiometrics, includeSleep, includeSteps)` |
| Agreements | `shouldRequestAgreement`, `acceptAgreements(tosVersion, healthDataVersion)`, `acceptCurrentAgreements` *(suspend)* |
| Account | `updateUserProfile(SB_UserProfileUpdate)`, `changePassword(currentPassword, newPassword)`, `requestPasswordReset`, `checkEmailAvailability`, `validateAccountRequirements(SB_ValidateAccountRequirementsRequest) -> SB_ValidateAccountRequirementsResult`, `refreshUser`, `hydrateSession`, `generateTemporaryAuthToken() -> String?`, `registerApp(deviceId)` |
| Recording submit | `createActivitySession(activityName, startEpochMs, durationSecs)` *(suspend; manual after-the-fact log)* |
| Session | `registerUser(userId, email?, sex?, birthdayYear?, birthdayMonth?, birthdayDay?, heightCm?, weightKg?, imperialUnits, activationCode?) -> SB_RegisterUserOutcome` *(SDK-key register-or-login; org creds come from `sdkKeyCredentials` — see §5.1; this is the SDK's **only** registration path)*, `signOut()`, `persistUser`, `deleteAccount`, `clearSession`, `clearPrefsOnLogout` *(signed-in identity is observable — see §3.1 `session`/`userProfileFlow`)* |

| Server writes | `reprocessSleep` *(suspend; user-tapped, throws on failure)*, `updateUserDeviceInfo`, `uploadUserPhoto` *(→ URL)*, `deleteUserPhoto` |

> **`location` is a full-replace field.** `SB_UserProfileUpdate.location` and `SB_UserProfile.location`
> name the same value — the user's location (city / country). It maps to a wire field historically
> named `zipcode`. `updateUserProfile` replaces the whole profile, so read the current value from
> `userProfileFlow.value?.location` and pass it back in on every update; sending `""` (or `null`)
> overwrites the stored value on the server.

### 5.1 SDK-key registration (`registerUser`)

For third-party apps embedding the SDK, `registerUser` is a **register-or-login** entry point for
users your app has already authenticated by its own means (your login, SSO, OAuth — the SDK doesn't
care which). These users have **no** Sensor Bio email/password. On success the SDK persists the
returned session and publishes `session` / `userProfileFlow`. It is the **only** registration path in
the distributed SDK — there is no email/password entry point.

- **`sdkKeyCredentials`** — set `SensorBioSDK.sdkKeyCredentials = SB_SDKKeyCredentials(org_id, sdk_token)`
  **once at launch** (like `environment`) with the server-issued organization credentials for your
  integration (from your Sensor Bio dashboard); the backend validates that the token is active and belongs
  to `org_id`. A non-null value puts the SDK in **SDK-key mode**; it is held in memory only and never
  persisted. `registerUser` reads these — it no longer takes `org_id`/`sdk_token` parameters (iOS parity) —
  and fails if `sdkKeyCredentials` is unset.
- **`userId`** — your own stable identifier for the end-user (`client_sdk_user_id`). The first call for
  a given `userId` registers; subsequent calls log in. It is also recorded as the user's **username**
  (visible in the web dashboard).
- **`email`** *(optional)* — a contact email. Omitted if null/blank; when supplied it is recorded on
  the backend as the user's contact email (never used as the login identity).
- **`sex` / `birthdayYear`+`birthdayMonth`+`birthdayDay` / `heightCm` / `weightKg` / `imperialUnits`**
  *(optional)* — demographics. **Any omitted value is filled with a dummy** before the request is sent:
  the platform requires height/weight/sex/birthday to compute higher-level metrics (recovery, calories,
  sleep scoring, …), so a user with none would break downstream processing. Pass real values when you
  have them (a partial birthday — not all of year/month/day — is treated as omitted).
- **`activationCode`** *(optional)* — redeems a device-subscription activation code during a first
  registration.

Every failure resolves to a typed `SB_RegisterUserOutcome` — it does not throw on a register error.
`Failed` carries a typed `SB_ServiceErrorCode` (e.g. `INVALID_ARGUMENT` for missing metadata,
`PERMISSION_DENIED` for an inactive SDK key), and no raw gRPC message string ever crosses the boundary.

```kotlin
// Set once at launch (in-memory, never persisted):
SensorBioSDK.sdkKeyCredentials = SB_SDKKeyCredentials(org_id = orgId, sdk_token = sdkToken)

when (val outcome = SensorBioSDK.registerUser(userId = userId)) {
    is SB_RegisterUserOutcome.Success               -> routeToHome(outcome.session)
    SB_RegisterUserOutcome.ClientSdkUserIdAlreadyInUse -> showError("This user id is already in use.")
    SB_RegisterUserOutcome.DeviceSubscriptionRequired,
    SB_RegisterUserOutcome.OrgBillingPeriodInactive -> showError("No active subscription — contact your administrator.")
    is SB_RegisterUserOutcome.Failed                -> showError("Something went wrong. (${outcome.code.name})")
    else                                            -> showError("Could not register: $outcome")
}
```

```kotlin
sealed class SB_RegisterUserOutcome {
    data class Success(val session: SB_Session) : SB_RegisterUserOutcome()
    object InvalidClientSdkUserId : SB_RegisterUserOutcome()
    object ClientSdkUserIdAlreadyInUse : SB_RegisterUserOutcome()
    object InvalidHeight : SB_RegisterUserOutcome()
    object InvalidWeight : SB_RegisterUserOutcome()
    object InvalidBirthday : SB_RegisterUserOutcome()
    object InvalidEmail : SB_RegisterUserOutcome()
    object InvalidAccessCode : SB_RegisterUserOutcome()
    object AccessCodeAlreadyInUse : SB_RegisterUserOutcome()
    object DeviceSerialNumberRequired : SB_RegisterUserOutcome()
    object DeviceSerialNumberMismatch : SB_RegisterUserOutcome()
    object DeviceSubscriptionRequired : SB_RegisterUserOutcome()
    object OrgBillingPeriodInactive : SB_RegisterUserOutcome()
    data class Failed(val code: SB_ServiceErrorCode) : SB_RegisterUserOutcome()
}
```

> SDK users authenticate with the new-auth `session_auth_token` only — there is no legacy `auth_token`
> / web-app cookie for them. The SDK stores the session's access + refresh tokens and refreshes them
> automatically, so subsequent authenticated calls (and `signOut()`) behave exactly as for a normal
> sign-in.

**Read-cache policy** (date-keyed reads — dashboard, trending, sleep, activity, etc.): a three-case
disk cache, mirroring iOS `cachedRead`.
- **Today** — always fetched fresh while online; the successful response is cached, and a fetch
  failure falls back to the last cached payload (so a cold offline open shows stale today rather
  than a blank/error).
- **Past date, final cache** — served straight from disk with no network. A cache entry is *final*
  only once it was written on a calendar day strictly *after* the date it holds.
- **Past date, provisional (or missing) cache** — an entry captured while that date was still
  "today" is provisional (the day kept accumulating data afterwards — a late device sync, a sleep
  the server scores hours later), so the first view of the day *after it has passed* refetches once
  to finalize it; on failure it falls back to the provisional snapshot. This is the SB-1112 fix.

`forceRemote = true` (pull-to-refresh) bypasses every cache shortcut and always fetches live,
falling back to cache only on failure.


### 5.2 Local-first HR points

`getHRPoints(date)` returns a day's HR samples **local-first**: it reads the day's on-device HR (local midnight→midnight in the device time zone) with **no** API round-trip when that data is already synced locally. Each point is tagged `AWAKE` / `ASLEEP` from the device's sleep sessions (`ASLEEP` = the point's epoch falls inside a session's `[sleepOnset, wakeUp)` window). Only when the day predates local sync (no local HR for the window) does it fall back to the API — one daily-HR fetch + one sleep fetch — backfill the HR + sleep locally, and rebuild, so a subsequent call for the same day is served entirely from the device. The first feature of the reusable `offlinefirst` surface (iOS-parity `getHRPoints(date:)`).

```kotlin
suspend fun getHRPoints(date: Instant): SB_HRDataPoints

data class SB_HRDataPoints(val points: List<SB_HRDataPoint>) {
    // Computed on the fly from `points`; each is null when its input set is empty
    // (and `averageRHR` is null when there are no ASLEEP points — never a misleading 0).
    val averageHR: Int?   // mean value of all points
    val averageRHR: Int?  // mean value of ASLEEP points (resting HR)
    val lowestHR: Int?    // min value
    val highestHR: Int?   // max value
}

data class SB_HRDataPoint(
    val epoch: Long,          // ms
    val value: Int,           // bpm, rounded from the stored Float
    val type: SB_HRPointType,
)

enum class SB_HRPointType { AWAKE, ASLEEP }
```

`getHRPoints` throws only on the server-backfill path (e.g. when signed out / the daily-HR or sleep fetch fails); the pure-local path never touches the network.

### 5.3 Local-first HRV points

`getHRVPoints(date)` is the HRV sibling of `getHRPoints` — same local-first contract: it reads the day's on-device HRV (local midnight→midnight in the device time zone) with **no** API round-trip when that data is already synced locally, tags each point `AWAKE` / `ASLEEP` from the device's sleep sessions, and falls back to the API (one daily-HRV fetch + one sleep fetch) only when the day predates local sync — then backfills and rebuilds so a later call is served entirely from the device. HRV shares the same single `ppg_data_results` row as HR (distinct columns), so the backfill updates only the HRV slot and never clobbers HR.

```kotlin
suspend fun getHRVPoints(date: Instant): SB_HRVDataPoints

data class SB_HRVDataPoints(val points: List<SB_HRVDataPoint>) {
    // Computed on the fly from `points`; each is null when its input set is empty.
    // Mirrors the HRV day view (rMSSD + daily-average / lowest / highest): `averageRestingHRV`
    // (mean of ASLEEP/nocturnal points) is the offline proxy for the server's sleep-derived rMSSD.
    val averageHRV: Int?          // mean value of all points (daily average)
    val averageRestingHRV: Int?   // mean value of ASLEEP points (nocturnal — rMSSD-parity primary)
    val lowestHRV: Int?           // min value
    val highestHRV: Int?          // max value
}

data class SB_HRVDataPoint(
    val epoch: Long,          // ms
    val value: Int,           // rMSSD in ms, rounded from the stored Float
    val type: SB_HRPointType, // AWAKE / ASLEEP — reused from getHRPoints
)
```

`getHRVPoints` throws only on the server-backfill path (e.g. when signed out / the daily-HRV or sleep fetch fails); the pure-local path never touches the network.

### 5.4 Local-first RR points

`getRRPoints(date)` is the RR (respiratory / breathing rate) sibling of `getHRPoints` / `getHRVPoints` — same local-first contract: it reads the day's on-device RR (local midnight→midnight in the device time zone) with **no** API round-trip when that data is already synced locally, tags each point `AWAKE` / `ASLEEP` from the device's sleep sessions, and falls back to the API (one daily-BRPM fetch + one sleep fetch) only when the day predates local sync — then backfills and rebuilds so a later call is served entirely from the device. RR shares the same single `ppg_data_results` row as HR/HRV (distinct columns), so the backfill updates only the RR slot and never clobbers HR/HRV.

```kotlin
suspend fun getRRPoints(date: Instant): SB_RRDataPoints

data class SB_RRDataPoints(val points: List<SB_RRDataPoint>) {
    // Computed on the fly from `points`; each is null when its input set is empty.
    // Mirrors the RR day view (brpm + daily-average / lowest / highest): `averageRestingRR`
    // (mean of ASLEEP/nocturnal points) is the offline proxy for the server's sleep-derived brpm.
    // Unlike HR/HRV these are Float — RR is inherently fractional (matches the API path's brpm).
    val averageRR: Float?          // mean value of all points (daily average)
    val averageRestingRR: Float?   // mean value of ASLEEP points (nocturnal — brpm-parity primary)
    val lowestRR: Float?           // min value
    val highestRR: Float?          // max value
}

data class SB_RRDataPoint(
    val epoch: Long,          // ms
    val value: Float,         // breaths per minute (brpm) — raw stored Float, unrounded
    val type: SB_HRPointType, // AWAKE / ASLEEP — reused from getHRPoints
)
```

`getRRPoints` throws only on the server-backfill path (e.g. when signed out / the daily-BRPM or sleep fetch fails); the pure-local path never touches the network.

### 5.5 Local-first steps

`getStepsPoints(date)` is the steps sibling of `getHRPoints` / `getHRVPoints` / `getRRPoints` — same local-first contract: it reads the day's on-device steps (local midnight→midnight in the device time zone) with **no** API round-trip when that data is already synced locally, and falls back to the API (one daily-steps fetch) only when the day predates local sync — then backfills and rebuilds so a later call is served entirely from the device.

Steps differ from the vitals in three deliberate ways:

- **Different store, no shared-row / slot model.** Steps live in their own `engine_result_requests` table (one row per interval, with step / distance / calories / active-seconds columns), not the vitals' `ppg_data_results`. The backfill is a plain keyed upsert (`REPLACE` on the ms-epoch `time`), marked `uploaded = true` so it never re-uploads.
- **No awake/asleep tag, no min/avg/max.** A step total is a count, not a distribution, so there is no `SB_HRPointType` and no lowest/average/highest — the aggregates are day **totals**.
- **Totals are non-null (default `0`).** A day with the band worn but idle legitimately totals `0` steps, so the totals never go null. Use `points.isEmpty()` to tell "no data for this day" from a genuine zero.

`totalActiveSeconds` is the day's active **duration** (SB-1662). On device it is the real per-minute `active_seconds` from the band; SB-1662 also fixed the store path (`StepsDataManager`), which previously hard-coded a flat 60 s/minute — throwing the band's duration signal away both locally and on the uploaded packet (iOS already stored the real value). On a server-backfilled day the duration comes from the server `TOTAL_DURATION` metric; distance and calories are recomputed from `(steps, activeSeconds)` via the same in-SDK formula the live store uses (the server's `DISTANCE` metric is in the user's display unit, not metres).

```kotlin
suspend fun getStepsPoints(date: Instant): SB_StepsDataPoints

data class SB_StepsDataPoints(val points: List<SB_StepsDataPoint>) {
    // Computed on the fly from `points`; day totals (non-null, default 0 — a worn-but-idle day is a genuine 0).
    val totalSteps: Int             // total steps
    val totalDistanceMeters: Float  // total distance, metres
    val totalCalories: Float        // total active (step) calories, kcal
    val totalActiveSeconds: Int     // total active duration, seconds
    val hourlyBuckets: List<SB_StepsHourBucket> // points summed into local hour-of-day buckets (present hours only)
}

data class SB_StepsDataPoint(
    val epoch: Long,           // ms
    val steps: Int,
    val distanceMeters: Float, // metres
    val calories: Float,       // kcal
    val activeSeconds: Int,    // active duration in the interval, seconds
)

data class SB_StepsHourBucket(
    val hour: Int,             // 0..23 local hour of day
    val steps: Int,
    val distanceMeters: Float,
    val calories: Float,
    val activeSeconds: Int,
)
```

`getStepsPoints` throws only on the server-backfill path (e.g. when signed out / the daily-steps fetch fails); the pure-local path never touches the network. (iOS has no `getStepsPoints` yet — Android leads, as with HRV/RR.)

### 5.6 Local-first calories

`getCaloriesPoints(date)` is the calorie sibling of `getStepsPoints` (§5.5) — same local-first contract, reading the **same** `engine_result_requests` rows — and a faithful local recreation of the backend's five-metric calorie graph (**Resting, Workout, Active, Steps, Total**). No API round-trip when the day is already synced locally; otherwise a steps + calories fetch, a backfill, and a rebuild.

The model mirrors the server (`physicalstats.getCalorieVal`) exactly. Each interval's `totalStepCalories` is the **active** calories the band measured, split into a **step** and a **workout** part:

- `stepCalories   = max(0, totalStepCalories − workoutStepCalories)`
- `workoutCalories = max(0, workoutCalories)`
- `activeCalories = stepCalories + workoutCalories` (always = the interval's `totalStepCalories`)

so **Active is unaffected by workouts** — only the Workout↔Steps split moves. On device the band never reports the split; it is reconstructed from the day's **on-device workout sessions** for locally-synced days (a minute inside a workout session counts as workout, exactly as the server reclassifies it), and from the **server calories graph** for backfilled days (the unified backfill persists the server's per-hour split into the rows' workout columns). Locally-synced non-workout minutes also apply the backend's per-minute step-calorie cap (`round(steps/10)` when the stored value exceeds `steps/10`) so the local Active total matches the server's. **Resting** is a day-level value from the user's BMR (`BMR × elapsed/86400` today, full `BMR` for a past day), and **Total = Active + Resting**. Active / Total / Resting are exact regardless of the workout split; the Workout/Steps split is exact on backfilled days and best-effort (from local workout sessions) on locally-synced days.

The ring / progress tracks **Active** calories toward `goals.calories` (server parity). Totals are non-null (default `0`, a worn-but-idle day is a genuine `0`); no awake/asleep tag and no min/avg/max.

```kotlin
suspend fun getCaloriesPoints(date: Instant): SB_CaloriesDataPoints

data class SB_CaloriesDataPoints(
    val points: List<SB_CaloriesDataPoint>,
    val restingCalories: Float,     // day BMR calories (BMR×elapsed today, full BMR past days); 0 if no BMR
) {
    // Computed on the fly (non-null, default 0). Server metric identities: active = step + workout; total = active + resting.
    val totalStepCalories: Float    // "Steps" tile, kcal
    val totalWorkoutCalories: Float // "Workout" tile, kcal
    val totalActiveCalories: Float  // "Active" tile (drives the ring), kcal
    val totalCalories: Float        // "Total" tile = active + resting, kcal
    val hourlyBuckets: List<SB_CaloriesHourBucket> // active/step/workout summed into local hour buckets (present hours only)
}

data class SB_CaloriesDataPoint(
    val epoch: Long,             // ms
    val stepCalories: Float,     // kcal
    val workoutCalories: Float,  // kcal
) { val activeCalories: Float }  // = stepCalories + workoutCalories

data class SB_CaloriesHourBucket(
    val hour: Int,               // 0..23 local hour of day
    val stepCalories: Float,     // kcal
    val workoutCalories: Float,  // kcal
) { val activeCalories: Float }  // = stepCalories + workoutCalories
```

`getCaloriesPoints` throws only on the server-backfill path (e.g. when signed out / the fetch fails); the pure-local path never touches the network. (iOS has no `getCaloriesPoints` yet — Android leads, as with steps/HRV/RR.)

### 5.7 Local-first sleep detail

`getSleepDetail(date)` is the sleep member of the family — same local-first contract, but sleep is stored on-device as **sessions** (the `sleep_sessions` rows) rather than a per-epoch timeseries, so instead of a bespoke points container it returns the **same `SB_SleepDetailDay`** the server `fetchSleepDetail` returns. The sleep-detail screen renders it unchanged. The day's **longest** session (most time asleep) is surfaced. A session is attributed to the day it **wakes up** — it may start the previous evening (or be a nap fully inside the day), but a session that starts in the day and wakes the next belongs to the next day (so an overnight sleep isn't double-counted on both days). Two paths:

- **On-device day** (a device-recorded session with stages exists): the detail is **rebuilt entirely from the local row** — no API round-trip — with all the server-parity math below.
- **Old day** (no on-device sleep — it predates local sync): a **server-backed read**, like the vitals do — it fetches the day's server sleep, persists **every** session's window locally via `persistServerSleep` (marked uploaded; there may be several sleeps in a day, and the windows let the vitals reads tag against them), and returns the main session's **full server `SB_SleepDetailDay`** (server stages / score / factors / penalties / biometrics / disturbances). So old days show the real sleep rather than an empty shell.

`null` when neither the device nor the server has sleep for the day.

The returned `SB_SleepDetailDay` is populated with what the device stores, **recomputed to match server-first exactly** (the server re-derives its stage metrics after upload, so the entity's own stored totals — summed over the full stage list — would not match):

- **stage metrics** — the per-epoch stages are clamped to `[sleepOnset, wakeUp]` and re-counted (server `UpdateSleepDerivedMetricsUsingTimestampedStages`), so the leading sleep-latency awake and any trailing awake are excluded. Each stage's minutes are the epoch count divided by the device's epochs-per-minute with **integer truncation** (server `int32(count / multiplier)`), matching the server to the minute. This drives the four legend percentages (largest-remainder rounding), the per-stage duration tiles, `sleepTimeSec` = (light + deep + rem) × 60, and the in-window arousal count. The stage-timeline `intervals` are likewise the in-window epochs;
- **`sleepOnset` / `wakeUpTime` / `timezone`**;
- **biometrics from the on-device `ppg_data_results` over the sleep window, each reproduced the server's way**: **`restingHr`** (header — `CalculateRestingBPM`: mean of the 5 lowest lower-fence-outlier-free BPM), the **Average HR** tile (median of the same outlier-free BPM) and the **Nocturnal HRV** tile (`CalculateRestingHRV`: the residual-outlier-free OLS HRV-vs-time line evaluated at the last epoch). Each is `0`/omitted when there is too little data;
- **`metrics`** — the locally-derivable tiles, in the server's tile order with the server's labels: **Total Time in Bed** (= wakeUp − onset + 1 min), **Total Awake**, **Light**, **Deep**, **REM Sleep** (`DURATION`), **Nocturnal HRV** (ms), **Average HR** (bpm), **Awakenings** (count), **Sleep Latency** (= onset − start), then the two editable `TimeTz` tiles **Sleep Onset** + **Wake up Time**. `SleepMetricsGrid` hides any tile whose value is `≤ 0` and full-widths the first survivor;
- **the sleep score + contributing factors + penalties** (`sleepScore` / `scoreFactors` / `scorePenalty`), computed locally to match the server's `CalculateSleepMainScore`: `score = max(1, goalAchieved×efficiency×100 − penalties)`. Factors = **Total Sleep** (vs the user's sleep goal from `GetGoal`, default 7h), **Deep** (or **Deep And REM**), **Efficiency**; penalties (points > 0 only) = **Awakenings**, **Restlessness** (from the arm disturbances), **Latency**, and **Elevated Avg Heart Rate**. Every input is on-device **except** the HR penalty's 30-day resting-HR baseline, which is recomputed from the last 30 nights' own sleep+PPG history — so the HR penalty is simply **omitted when there isn't enough history** (server parity; e.g. right after a fresh login). If the night has no resting HR at all, no score is produced and the header falls back to the empty "--" ring.

Everything else the server owns and the local row does not hold is left empty/null and simply doesn't render: **bedtime recommendations**, **accounting**, the **HR / HRV / RR / SpO2 biometric graphs** (the per-epoch charts — distinct from the single-value HRV/HR tiles above), **positions**, **apnea / breathing** and **survey**. A **server-backfilled** row (a day that predated local sync) has no stages, so the timeline + stage tiles are empty while the header + window still render.

```kotlin
suspend fun getSleepDetail(date: Instant): SB_SleepDetailDay?   // null when no local sleep for the day

// The session's biometric-graph timeseries (HR / HRV / RR) for the sleep-detail charts. The
// session is identified by its wake-up epoch (SB_SleepDetailDay.wakeUpTime from getSleepDetail).
suspend fun getSleepHR(sessionEndTimestamp: Long): List<SB_TimeValuePoint>
suspend fun getSleepHRV(sessionEndTimestamp: Long): List<SB_TimeValuePoint>
suspend fun getSleepRR(sessionEndTimestamp: Long): List<SB_TimeValuePoint>
```

`getSleepHR` / `getSleepHRV` / `getSleepRR` return the session's per-epoch metric over its `[onset, wakeUp]` window, time-ordered with **real-epoch** timestamps (the convention the sleep biometric chart bucketing expects), with each metric's server-parity outlier handling applied (HR lower-fence only; HRV two-sided residual; RR unfiltered, rounded to 1 dp). Local-first: read from the on-device `ppg_data_results`; if the day predates local sync (no on-device PPG) each falls back to the server sleep detail's matching graph — hence `suspend`. Empty when neither source has data. A host wraps each list in a graph (points + mean average) to feed the same biometric chart the server path draws; there is no local SpO2 (that card stays hidden).

`getSleepArmDisturbances` returns the session's arm-restlessness severity timeline for the disturbance chart — **fully local**, bucketed from the on-device `activity_packets` motion value over `[onset, wakeUp]` with the server's fixed thresholds (`≤250000 NONE / ≤750000 MILD / ≤1250000 MODERATE / else SEVERE`), first + last forced `NONE`. It returns a colour-free `SB_SleepDisturbanceLevel` per epoch (`NONE`/`MILD`/`MODERATE`/`SEVERE`) — the SDK deliberately does **not** return colours, so the host owns the palette (and its theming). `NONE` points are included so the host can compute the level distribution; the host typically drops them from the drawn series (server parity). No server fallback — the arm series is only ever local; empty when the session has no activity packets on device. (Leg / snoring disturbance graphs are dead on the server, so only the arm series is exposed.)

```kotlin
suspend fun getSleepArmDisturbances(sessionEndTimestamp: Long): List<SB_SleepDisturbancePoint>

enum class SB_SleepDisturbanceLevel { NONE, MILD, MODERATE, SEVERE }
data class SB_SleepDisturbancePoint(val timestamp: Long, val timezone: Int, val level: SB_SleepDisturbanceLevel)
```

`getSleepDetail` throws only on the server-backfill path (e.g. when signed out / the fetch fails); the pure-local path never touches the network. (Pairs with the iOS local-first sleep ticket.)

### 5.8 Local-first recovery score

`fetchDailyRecovery(date)` / `dailyRecoveryUpdates(date)` keep their signatures and their `SB_DailyRecoveryTrending` return, but the **score behind them is now computed on-device** whenever the night is on the device (SB-1681). Unlike §5.2–5.7 this is not a new entry point — it is the same trending read with a local source, so no caller changes.

The daily recovery score ranks tonight against the user's own recent nights (server `GenerateRecoveryScore`):

```
score = round(100 · (0.4·fracHRV + 0.4·fracBPM + 0.1·sleepGoalAchieved + 0.1·sleepEfficiency))
```

- **fracHRV / fracBPM** — the share of the prior 30 days' nights whose resting HRV was no higher than tonight's / whose resting BPM was no lower. Each night's resting values are derived from that night's on-device `ppg_data_results` with the same server-parity math the sleep detail uses (`CalculateRestingBPM` — mean of the 5 lowest outlier-free BPM; `CalculateRestingHRV` — the residual-outlier-free OLS HRV-vs-time line at the last epoch);
- **sleepGoalAchieved** — `min(1, totalSleep / goal)` against the user's sleep goal (an unset goal scores full credit);
- **sleepEfficiency** — `totalSleep / (totalSleep + excessAwake + excessLatency)`, where awake time up to 5% of total sleep and latency up to 20 minutes are free. Both sleep terms read the same clamped stage minutes the sleep detail reports.

The returned graph carries the ring score + **stage** (`REST_UP` <30 · `GO_EASY` <50 · `MEDIUM` <60 · `READY` <75 · `EXCELLENT` ≥75) and its message, the night's resting HR and total sleep, the **vs-average trend** (`variationPercentage`, measured against the mean of the last 30 days' locally-computed scores), and the four **contributing factors** in the server's order — **Nocturnal HRV**, **Resting HR**, **Sleep Efficiency**, **Total Sleep Duration** — each with its percentile and its weighted point contribution.

**Incomplete nights are backfilled, not skipped.** A prior night whose sleep *window* is on device but whose data is not — the partial day a fresh login leaves behind, or a window persisted only for vitals tagging — would otherwise drop silently out of the ranking and shrink its denominator, moving the score without any signal. Two misses are detected and repaired on read, each attempted once per night per process:

- **no biometrics in the window** → the night's raw HR/HRV are fetched and persisted. A night straddles midnight, so *both* calendar days it touches are fetched;
- **no stage minutes** → the night's server breakdown is fetched and its stage minutes persisted, which is what makes the night scorable and keeps it in the 30-day average.

Both are one-time per night; afterwards the window is served entirely from the device.

The read **falls back to the server** for a day the device cannot score the server's way:

- no on-device sleep for the day, or a stage-less (server-backfilled) night;
- the night has no resting BPM or HRV;
- fewer than **four prior nights** with sleep / resting BPM / resting HRV — the server's 5-nights-including-tonight gate. This window matters: the backend blends a population baseline to serve a real score on days 1–4, and that baseline is not on the device, so those days deliberately stay server-served rather than showing nothing.

`SB_DailyRecoveryTrending.isLocallyComputed` reports which source actually served the day, so a host can distinguish a genuine local score from a fallback. `preferLocal = false` (on both `fetchDailyRecovery` and `dailyRecoveryUpdates`) skips the on-device computation and reads the server's score — the comparison hatch behind the app's debug source toggle; the default `true` keeps every existing caller local-first. `dailyRecoveryUpdates` has **no stale peek**: the local computation is fast enough to be the first emission, so the stream never paints a stale server score first.

`forceRemote` applies to the fallback fetch only — it does **not** bypass the local computation, matching the rest of the offline-first family. Factor titles and stage messages are the SDK's own English strings (the server's are localized server-side), the same trade-off §5.7's sleep score makes.

### 5.9 Local-first activity detail

`fetchDailyActivityDetail(date, granularity)` / `dailyActivityDetailUpdates(date, granularity)` keep their signatures and their `SB_ActivityDetail` return, but a **`DAY` view is now rebuilt on-device** from the day's activity rows (SB-1683) — score, baseline comparison and all four metrics. Like §5.8 this is not a new entry point, so no caller changes.

The daily activity score is a pure function of one day (server `GenerateActivityScore`) — no history, no statistical aggregation, no calibration:

```
score = round(100 · (0.8·min(1, activeCals/caloriesGoal) + 0.2·min(1, activeHours/12)))
```

- **activeCals** — the day's active calories, `Σ(totalStepCalories + workoutCalories − workoutStepCalories)` over the local activity rows. This is the same total §5.6's calories read reports as **Active**, including the backend's per-minute `round(steps/10)` step-calorie cap (which the server applies in its DB row scan, and skips for workout minutes);
- **activeHours** — local hours with **more than 250** steps, off the same hourly buckets §5.5's steps read exposes;
- **caloriesGoal** — `remoteGlobals.goals.calories`, with the server's 500 default substituted for an unset goal.

The ring's **fill** is not the score: the server publishes `progressPercentage` relative to a rolling baseline, and `SB_ActivityScore.diffVsBaseline` alongside it. Both are reproduced locally — the baseline is the same score recomputed for each of the prior **30 days** and averaged, and is published only once **5** prior days exist, matching the server's gate (below that the server omits `progressPercentage` too, and a host falls back to the raw score). `progressPercentage` is `100` at or above the baseline, and the shortfall as a percentage below it. The prior days are read **local-only, with no backfill** — a 30-day baseline that fetched every missing day would cost 30 round-trips on first open, and a day with no local rows simply does not contribute, which is how the server treats a date with no stored score.

The four **metrics** — Calories, Duration, Distance, Steps, in the server's order — carry their day total in `avgValue` and hourly `timeDatapoints` in the API's wall-clock-encoded-as-UTC convention. `DISTANCE` is emitted in the user's **display unit** (km, or miles for an imperial profile, rounded to two decimals), as the API sends it; every other metric is SI. `name` / `unit` / `barChartTitle` are left **empty** — those are localized server-side, and the SDK owns no strings, so a host that shows them supplies its own.

The read **falls back to the server** when the day has no activity rows on device even after a backfill attempt, and always for `WEEK` / `MONTH` / `YEAR` — those periods average the server's stored daily scores and aggregate from its rollup tables, which the device does not reproduce.

`SB_ActivityDetail.isLocallyComputed` reports which source actually served the day. `preferLocal = false` (on both entry points) reads the server's payload instead — the comparison hatch behind the app's debug source toggle; the default `true` keeps every existing caller local-first. As in §5.8, the `DAY` stream has **no stale peek** on the local path (the range tabs keep theirs), and `forceRemote` applies to the fallback fetch only.

Org custom / white-label activity scoring stays server-computed.

---

### 5.10 One daily shape for all four biometrics (SB-1738) ⚠️ breaking

All four daily biometric reads now return the same `SB_BiometricDailyTrending`; the per-metric
`SB_HRDailyTrending` / `SB_HRVDailyTrending` / `SB_RRDailyTrending` / `SB_SpO2DailyTrending` and
their four graph types are **gone**. The metric is identified by the call you made, not by the
type. Range reads keep their per-metric types — those graphs genuinely differ.

```kotlin
suspend fun fetchDailyHR(date: Instant, forceRemote: Boolean = false): SB_BiometricDailyTrending
suspend fun fetchDailyHRV(date: Instant, forceRemote: Boolean = false): SB_BiometricDailyTrending
suspend fun fetchDailyRR(date: Instant, forceRemote: Boolean = false): SB_BiometricDailyTrending
suspend fun fetchDailySpO2(date: Instant, forceRemote: Boolean = false): SB_BiometricDailyTrending
// …and the four `daily*Updates` Flow siblings, likewise.

data class SB_BiometricDailyTrending(
    val graph: SB_BiometricDailyGraph? = null,   // null when the day has no processed data
)

data class SB_BiometricDailyGraph(
    val resting: Float = 0f,                     // HR: resting HR · HRV: RMSSD · RR/SpO2: nocturnal average
    val average: Float = 0f,                     // whole-day mean, sleep included
    val lowest: Float = 0f,
    val highest: Float = 0f,
    val baseline: Float = 0f,                    // 30-day median, server-computed
    val points: List<SB_BiometricPoint> = emptyList(),   // ascending by timestamp
    val linearFit: SB_TimeValueStraightLine? = null,     // recovery-rate fit; HRV-only today
)

data class SB_BiometricPoint(
    val timestamp: Long,                         // absolute ms epoch
    val value: Float,
    val valueType: SB_BiometricValueType,        // sleep / awake / outlier / abnormal-rhythm
)
```

**Migrating:**

| was | now |
| --- | --- |
| `graph.restingBpm` / `.rMssd` / `.brpm` / `.spo2` | `graph.resting` |
| `graph.rawAvg` / `.rawLowest` / `.rawHighest` / `.rawBaseline` | `graph.average` / `.lowest` / `.highest` / `.baseline` |
| `graph.heartRateTimeseriesPoints` | `graph.points` |
| `graph.rawSleepPoints` + `graph.rawDatetimePoints` (and the `…HrvPoints` pair) | `graph.points`, split by `valueType` (`SLEEP` / `AWAKE`) |
| `graph.tzOffset`, `point.timezone` | **removed** — `timestamp` is an absolute ms epoch; render it in the viewer's own zone |
| `graph.startTimestamp` | **removed** — derive the axis start as `min(localMidnight, floorToHour(points.first()))` |
| `graph.improvementVsBaseline` | **removed** — compute `resting - baseline`, sign-flipped where lower is better (HR, RR); `0` when `baseline <= 0` |
| `graph.improvementCorrelatesDirectlyWithChange` | **removed** — it only ever said "higher is better", which is a property of the metric, not the response |
| `SB_HeartRateValueType` / `SB_HRVValueType` | `SB_BiometricValueType` (HR's case list; HRV's `HRV_OUTLIER` maps to `SLEEP_OUTLIER`) |
| `SB_HeartRateTimeValuePoint` / `SB_HRVTimeValuePoint` | `SB_BiometricPoint` |
| `line.lineColor` (`SB_LineColor`) | `line.rating` (`SB_ImprovementRating`) — pick your own colours |
| The daily HR response's `hasHeartHealthLicense` / `abnormalRhythmInfoCard` / `aeCard` / `peCard` / `arterialHealthScore` | **removed** — unread on the daily read; the *range* read (`SB_HRRangeTrending`) still carries its own card set |

`SB_TimeValuePoint` also loses `timezone`, so every graph that carries those points (sleep
biometrics, workout, activity, meditation, spot check) now hands back `(timestamp, value)` only.

Range graphs lose `improvementVsBaseline` and `improvementCorrelatesDirectlyWithChange` too, spell
`Avg` out (`averageBpm` / `average` / `averageBrpm` / `averageSpo2`, `rolling7DayAverage`), and
`SB_HRVRangeGraph.rawBaseline` is dropped as a duplicate of `baseline`.

**Two traps worth knowing about.** HRV / RR / SpO2 points are tagged by **array provenance**, never
by a raw value or ordinal: `SB_BiometricValueType`'s case list mirrors the heart-rate proto, where
`AWAKE` is ordinal 0, while the HRV proto numbers `BASIC` as 0 — an ordinal bridge would compile
clean and silently relabel every HRV *sleep* sample as awake. And the merged array is **sorted by
timestamp**: the server builds its sleep and awake arrays independently, so a plain concatenation
yields a sleep block followed by an awake block — fine for a scatter set, wrong for HRV's
`linearFit` overlay.

Cached responses from an older build fail to decode once, are deleted, and refetch — no migration
step and no cache-version bump needed.

---

## 6. Domain types (`SB_*`)

~276 public `SB_*` types the facade returns/accepts. Grouped index:

- **User / auth** — `SB_UserProfile`, `SB_UserDemographics`, `SB_UserAppSettings`, `SB_Session`,
  `SB_SDKKeyCredentials`,
  `SB_RegisterUserOutcome` (+`SB_ServiceErrorCode`), `SB_ChangePasswordOutcome`,
  `SB_EmailAvailabilityOutcome`, `SB_UpdateUserProfileOutcome`, `SB_RequestPasswordResetOutcome`,
  `SB_AgreementCheck`, `SB_Gender`, `SB_UserProfileUpdate`,
  `SB_ValidateAccountRequirementsRequest`, `SB_ValidateAccountRequirementsResult`,
  `SB_OrganizationMembership`, `SB_OrganizationMemberStatus`, `SB_OrgMembership`.
- **Devices / Bluetooth** — `SB_PairedDeviceState`, `SB_DiscoveredDevice`, `SB_PairingState`, `SB_PairingFailure`,
  `SB_BSDeviceModel`, `SB_BluetoothDeviceType`,
  `SB_ConnectionStage`, `SB_BluetoothResetState`, `SB_BatteryLevel`, `SB_DeviceLinkFailure`,
  `SB_SyncResult`, `SB_FirmwareInfo`, `SB_FirmwareUpdateError`, `SB_FirmwareVersionPacket`, `SB_DeviceResetResult`, `SB_ServerDeviceName`.
- **Recording** — `SB_RecordingState`, `SB_RecordingFinalizationPhase`, `SB_RecordingMetaType`,
  `SB_RecordingSession`, `SB_RecordingInfo`, `SB_RecordingCustomization`, `SB_PersistedRecording`, `SB_ActiveRecordingInfo`,
  `SB_RecordingError`, `SB_RawSensorDataLogging`, `SB_SpotCheckDetails`,
  `SB_SubmitFinishedRecordingResult`, `SB_LatestBookend`, `SB_CaptureEndEvent` / `SB_CaptureEndReason`, `SB_BiometricRecordResult`,
  `SB_ExerciseZoneAttributes` / `SB_HREffortZone`.
- **Sleep** — `SB_SleepItem`, `SB_SleepDetailDay`, `SB_SleepDetailAggregated`, `SB_SleepStages`,
  `SB_SleepStage`, `SB_SleepBiometrics`, `SB_SleepScore`(+factors/penalties/sections), `SB_SleepPosition`,
  `SB_SleepDisturbances`, `SB_SleepApneaInfo`, `SB_SleepAccounting`, `SB_SleepDebt*`, `SB_SleepMetric`,
  `SB_SleepWriteError`, `SB_DetectedSleep`. (~40 types)
  `SB_SleepBedtimeRecommendation` carries **no calibration block** — a recommendation only exists once
  the user is out of the calibration window, so `SB_SleepDetailDay.bedtimeRecommendation` is `null`
  while the server is still calibrating rather than a recommendation with empty times. Hosts render
  nothing for that state. `SB_CalibrationInfo` (`totalDays` / `pendingDays`) is still
  surfaced on `SB_SleepAccounting`, which *does* render a calibrating state. It carries **no
  `message`**: the server sends a pre-rendered sentence for this phase, but the SDK drops it so the
  copy — and its localization — stays the host app's to own, built from the two day counts.
- **Activity / workout / steps** — `SB_TrainedActivity`, `SB_ActivityRecordingList`,
  `SB_ActivitySummary`, `SB_WorkoutDetail`, `SB_WorkoutTimelineResult`, `SB_WorkoutSummaryMetric`,
  `SB_ModifyAction`, `SB_ModifyOutcome`, `SB_ExerciseZones`, `SB_StepsTrending`,
  `SB_StepMetric`, `SB_StepMetricType`, `SB_ActivityDetail`, `SB_ActivityScore`,
  `SB_SummaryGranularity`, `SB_PageFetchDirection`,
  `SB_WLSRecordingType`, `SB_WorkoutRecordingInfo`(+`SB_OngoingWorkoutProgram`).
- **Biometrics / metrics** — one shared daily family
  `SB_BiometricDailyTrending`(+`SB_BiometricDailyGraph`, `SB_BiometricPoint`, `SB_BiometricValueType`)
  for HR / HRV / RR / SpO2 (§5.10), plus the per-metric range graph & trending families
  (`SB_HRRange*`, `SB_HRVRange*`, `SB_SpO2Range*`, `SB_RRRange*`); `SB_ImprovementRating`;
  live per-sample stream payloads `SB_HeartRateSample` /
  `SB_HrvSample` / `SB_RespiratoryRateSample` / `SB_SnrSample` / `SB_BbiSample` / `SB_PpgSample` /
  `SB_EcgSample` (§3.2); `SB_LiveMetric`; `SB_HRMData`(+`SB_HRMCategory`); `SB_TimeValuePoint`, `SB_DateValuePoint`,
  `SB_BarGraph`, `SB_CalorieMetric`, `SB_CaloriesTrending`, `SB_CardioStats`;
  `SB_SkinTemperature`(+`SB_SkinTemperature.Point`) — on-device day summary from `getSkinTemperature(date)`, all Celsius.
  `SB_HRDataPoints`(+`SB_HRDataPoint`, `SB_HRPointType{AWAKE,ASLEEP}`) — local-first day HR from `getHRPoints(date)` (§5.2); the container computes `averageHR`/`averageRHR`/`lowestHR`/`highestHR` on the fly (nullable, never a misleading 0).
  `SB_HRVDataPoints`(+`SB_HRVDataPoint`; reuses `SB_HRPointType`) — local-first day HRV from `getHRVPoints(date)` (§5.3); the container computes `averageHRV`/`averageRestingHRV`/`lowestHRV`/`highestHRV` on the fly (nullable, never a misleading 0).
  `SB_RRDataPoints`(+`SB_RRDataPoint`; reuses `SB_HRPointType`) — local-first day RR from `getRRPoints(date)` (§5.4); the container computes `averageRR`/`averageRestingRR`/`lowestRR`/`highestRR` on the fly (nullable, never a misleading 0).
  `SB_StepsDataPoints`(+`SB_StepsDataPoint`, `SB_StepsHourBucket`) — local-first day steps from `getStepsPoints(date)` (§5.5); the container computes day totals `totalSteps`/`totalDistanceMeters`/`totalCalories`/`totalActiveSeconds` (active duration) + `hourlyBuckets` on the fly. No awake/asleep tag and no min/avg/max (steps are counts); totals are non-null (default 0).
  `SB_CaloriesDataPoints`(+`SB_CaloriesDataPoint`, `SB_CaloriesHourBucket`) — local-first day calories from `getCaloriesPoints(date)` (§5.6); a faithful local recreation of the server's five calorie metrics. The container computes `totalActiveCalories` (ring) / `totalStepCalories` / `totalWorkoutCalories` / `totalCalories` (= active + resting) + `hourlyBuckets` on the fly, alongside the constructor's `restingCalories` (BMR-based). Each point splits its `totalStepCalories` into `stepCalories` + `workoutCalories` (server parity: `active = step + workout`). Shares the step rows; no awake/asleep tag, no min/avg/max; totals non-null (default 0).
  Local-first sleep reuses the server **Sleep** types above: `getSleepDetail(date)` (§5.7) returns an `SB_SleepDetailDay` rebuilt from the on-device `sleep_sessions` row (stage timeline + metric tiles populated; server-owned score/recommendations/accounting/biometric graphs/positions left empty).
- **Recovery** — `SB_RecoveryRange*`, `SB_DailyRecovery*`, `SB_RecoveryScoreFactor/Section`. Each
  `SB_RecoveryScoreFactor` reports its `percentile` (0–100) and a pre-computed `scoreValue` — the
  factor's weighted contribution under `0.4·HRV + 0.4·RHR + 0.1·Sleep Efficiency + 0.1·Sleep
  Duration`; colors are **not** returned (the app derives them from the percentile). Both
  `SB_DailyRecoveryTrending` and `SB_RecoveryRangeTrending` also carry the signed-in user's
  `joinedDate` (from the profile, not the recovery payload) so the app can describe the averaging window.
- **Dashboard** — `SB_DashboardData` + ~20 card/metric types (`SB_DashboardMetric`,
  `SB_DashboardInsight`, `SB_DashboardGradientCard`, `SB_DashboardSleepItem`, …).
- **Insights / population** — `SB_NewInsights`, `SB_InsightItem`, `SB_InsightFeedback`,
  `SB_PopulationInsights`(+filters/histogram/radar), `SB_PopulationInsightsFilterList`,
  `SB_PopulationMetricType`, `SB_PopulationAgeGroup`, `SB_PopulationGender`, `SB_DailyStatsResponse`.
- **Meditation** — `SB_MeditationGraph`, `SB_MeditationScore`(+factors/penalties).
- **Events / elements** — `SB_NotificationElement`.
- **Surveys / agreements** — `SB_BriefSurvey`(+Question/Answer/Type), `SB_AgreementType`.
- **Goals / config** — `SB_Goals`, `SB_AppConfig`, `SB_AppType`, `CacheStrategy`, `SB_ViewGranularity`.
- **Errors / results** — `SB_InsightError`, `SB_RecordStage`, `SB_UnitType`.
- **Top-level config** — `SB_Environment` (DEVELOPMENT/PRODUCTION), `SB_LogLevel` (V/D/I/W/E),
  `SB_NetworkStatus` (UNREACHABLE/WIFI/CELLULAR/OTHER).
- **Analytics** — `SB_AnalyticsEvent` (`name` + `properties: Map<String,String>`).

---

## 7. Top-level symbols

- **`SB_Environment`** (enum) — `DEVELOPMENT` (staging gRPC) / `PRODUCTION`. Set via `SensorBioSDK.environment`.
- **`SB_LogLevel`** (enum) — `V/D/I/W/E`; passed to `logHandler`.

---

## 8. Minimal example

```kotlin
// Application.onCreate
SensorBioSDK.initialize(this, SB_AppConfig(appType = SB_AppType.SENSR, appFlavor = BuildConfig.FLAVOR))
SensorBioSDK.environment = SB_Environment.PRODUCTION
SensorBioSDK.logHandler = { level, msg, args -> Log.println(level.toAndroid(), "SDK", msg ?: "") }

// Involuntary sign-out is now an event, not a supplied callback (§4):
SensorBioSDK.signOutComplete.onEach { logoutAndShowLogin() }.launchIn(appScope)

// Register-or-login a user your app has already authenticated by its own means. Set the org
// credentials once (in-memory, never persisted), then call registerUser with your own stable user id:
// the first call for a given userId registers, later calls sign the same user back in.
SensorBioSDK.sdkKeyCredentials = SB_SDKKeyCredentials(org_id = ORG_ID, sdk_token = SDK_TOKEN)
when (val outcome = SensorBioSDK.registerUser(userId = myUserId)) {
    is SB_RegisterUserOutcome.Success -> onSignedIn(outcome.session)
    is SB_RegisterUserOutcome.Failed  -> showError(outcome.code)
}

// In a ViewModel
viewModelScope.launch {
    val dashboard = SensorBioSDK.fetchDashboardData(date = Instant.now(), tzOffset = tz, forceRemote = false)
    _state.value = dashboard
}
SensorBioSDK.deviceFullyConfigured.onEach { onDeviceReady() }.launchIn(viewModelScope)
SensorBioSDK.deviceDisconnected.onEach { mac -> onDeviceLost(mac) }.launchIn(viewModelScope)
```

See [`ExampleApp/`](https://github.com/GetSensr-io/mobile_sensorbio_sdk_android_binary/tree/main/ExampleApp)
in the binary repo for a complete reference integration (register → pair → connect → dashboard). It is
rebuilt against the published artifact as part of every release, so it always compiles against the
surface documented here.
