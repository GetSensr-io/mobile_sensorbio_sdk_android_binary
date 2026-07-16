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
> copy. SDK `version = "0.13.0"`.

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
    implementation("com.sensorbio:sensorbio-sdk:0.14.1")
}
```

The single coordinate brings everything: the SDK plus the embedded BLE + edge-algorithm binaries
(including native `.so` for `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`) are bundled inside the one
`.aar`; all open-source transitive dependencies (gRPC, protobuf, OkHttp, Room, AndroidX lifecycle,
coroutines, …) are declared in the POM and resolved automatically from `google()` / `mavenCentral()`.


**Platform:** `compileSdk 36`, `minSdk 29`, Java 17.

### 1.1 Permissions (host responsibility)

The **host app requests Bluetooth runtime permissions** before any BLE operation (`startScan` /
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
| `logHandler` | `((SB_LogLevel, String?, Array<out Any?>) -> Unit)?` | sink for SDK logs (unset = silent) |

App identity is set-once config passed into `initialize(context, SB_AppConfig(...))` — `appType`,
`appFlavor`, `appDisplayName`, `enableCrashlytics`. App **version + build** are self-read from the host
`PackageManager` at init.

---

## 3. The `SensorBioSDK` facade

`SensorBioSDK` is a Kotlin `object` (the single entry point). It exposes observable state, one-shot
event streams, recording control, device & BLE control (§3.4), and the flat server-API methods (§5).

### 3.1 Observable state — `StateFlow` (collect from a ViewModel)

| Flow | Element | Meaning |
|---|---|---|
| `pairedDevice` | `SB_PairedDeviceState?` | the single paired device's slim identity (macAddress/name/type); null when none paired |
| `haveDevice` | `StateFlow<Boolean>` | a device is paired (derived from `pairedDevice`) |
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
| `buttonTaps` | `StateFlow<Int?>` | latest device button-tap count (used during pairing); null until first tap |
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

### 3.2 Event streams — `SharedFlow` (one-shot)

| Flow | Payload | Purpose |
|---|---|---|
| `deviceReset` | `SB_DeviceResetResult` | device-reset outcome |
| `analyticsEvents` | `SB_AnalyticsEvent` | SDK telemetry events incl. recording start/end (name + properties); host forwards to its backend |
| `latestBookend` | `SB_LatestBookend` | activity window resolved by sync (spot-check confirm) |
| `deviceLinkFailed` | `SB_DeviceLinkFailure` | a device-link (serial-enforcement) rejection; host alerts "wrong device" / retry |
| `firmwareProgress` | `Float` | raw firmware-flash progress percent (0–100); pairs with the suspend `updateFirmware()` (§3.4) |
| `syncCompleted` | `SB_SyncResult?` | a device sync drained; non-null on detailed completion, null on short-circuit |
| `biometricRecordResult` | `SB_BiometricRecordResult` | spot-check submit outcome (recording id on success / error on terminal failure) |
| `biometricRecordProcessed` | `Unit` | spot-check data synced + submit dispatched; host resets post-biometric UI |
| `hr` / `hrv` / `rr` / `snr` / `bbi` / `ppg` | `SB_HeartRateSample` / `SB_HrvSample` / `SB_RespiratoryRateSample` / `SB_SnrSample` / `SB_BbiSample` / `SB_PpgSample` | high-frequency per-sample biometric streams during a live/manual session (buffered + `DROP_OLDEST`) |
| `ecg` | `SB_EcgSample` | ECG waveform samples (V3 hardware) |
| `spo2` | `SB_Spo2Sample` | standalone live SpO2 stream (declared for parity; surfaced via synced trending) |
| `sleepStored` | `Unit` | a sleep session was decoded + stored on-device |
| `sleepUploaded` | `Unit` | a sleep session finished uploading; host refreshes affected UI (e.g. dashboard) |
| `sleepDetected` | `SB_DetectedSleep` | a valid auto-detected on-device sleep session was finalized (start/end epoch ms); host prompts the post-sleep survey |
| `deviceDiscovered` | `SB_DiscoveredDevice` | a device surfaced during a scan (pairing); lean payload (`macAddress`, `name`) |
| `deviceConnected` | `Unit` | raw BLE link came up (signal only; read identity from the facade) |
| `deviceFullyConfigured` | `Unit` | the device finished full configuration and is usable (rising edge of `isFullyConfigured`) |
| `deviceDisconnected` | `String` | the device disconnected; payload = macAddress |
| `pairingConnection` | `String` | mid-pairing connected event carrying the device macAddress; fires alongside `deviceConnected` |
| `hrSyncFinishedForActivity` | `Unit` | a recorded activity's HR/data finished syncing+uploading |
| `signOutComplete` | `Unit` | involuntary sign-out finished (terminal refresh-token failure); host runs teardown + routes to login |
| `syncNotificationActions` | `SB_SyncNotificationAction` | sync-lifecycle notification actions the host surfaces as OS notifications (replaces the former `SB_SyncNotificationHandler` seam) |

### 3.3 Recording control (suspend, on the facade)

| Member | Signature | Notes |
|---|---|---|
| `recordDetailedBiometrics` | `suspend (duration, minDuration) -> Unit` | spot-check, **awaits the whole session end-to-end**; auto-finalizes on the countdown or via `finishCurrentRecording()`; result on `biometricRecordResult` (§3.2). Throws `NoPairedDevice`/`BleStartFailed`/`TooShort`/`BleStopFailed`; cancel the coroutine to abort |
| `recordActivity` | `suspend (activityName, minDuration) -> Unit` | activity (count-up). **Awaits the whole session end-to-end** — returns on the successful submit (host then runs its survey), throws `NoPairedDevice`/`BleStartFailed`/`TooShort`/`NotEnoughData` |
| `recordMeditation` | `suspend (duration, minDuration, sessionName?, sessionNameAlreadyExists, surveyUrl?) -> Unit` | meditation (always a `duration`-sec countdown). **Awaits end-to-end**; same return/throw contract as `recordActivity` |
| `awaitActiveRecordingCompletion` | `suspend () -> Unit` | await a recording the host did **not** start via `record*()` (a process-kill resume) so it can run the survey + surface errors identically; no-op when idle |
| `finishCurrentRecording` | `suspend () -> Unit` | signal stop + window-sync + schedule submit; outcome surfaces in the in-flight `record*()` await |
| `cancelCurrentRecording` | `() -> Unit` | abort without submit |
| `pauseRecording` | `() -> Unit` | pause the running activity/meditation: freeze the elapsed clock + stop the device PPG stream (no biometrics accrue). No-op if not recording / already paused / spot-check |
| `resumeRecording` | `() -> Unit` | resume a paused recording: record the pause window + restart the device stream. Each paused span is excluded from the session's `active_workout_segments` |
| `resumeActiveRecording` | `() -> Unit` | resume a recording persisted across a process kill (crash-restore / app launch) |
| `activeRecording` | `val SB_PersistedRecording?` | crash-restore: a recording persisted across process death |
| `activeRecordingState` | `val SB_ActiveRecordingInfo?` | flat snapshot of the live activity/meditation recording the engine is driving (for the live screen) |

### 3.4 Device & BLE control (on the facade)

Pairing, connection, device commands, and sync are driven through the facade. (Device-telemetry
observation is the §3.1 `connected` / `charging` / `batteryLevel` / `bluetoothAvailable` / `buttonTaps`
flows + the §3.2 `deviceDiscovered` / `deviceConnected` / `deviceFullyConfigured` / `deviceDisconnected`
events, plus the connected-device identity below.)

| Member | Signature | Notes |
|---|---|---|
| `startScan` / `stopScan` | `() -> Unit` | BLE scan lifecycle |
| `connect` | `(id: String, pairing: Boolean = false) -> Unit` | connect (`pairing` auto-fires `pairingConnection`) |
| `disconnect` | `(id: String? = null) -> Unit` | drop the connection (null = current device) |
| `addPairedDevice` | `(device: SB_DiscoveredDevice) -> Unit` | register a paired device after confirmation |
| `removeDeviceFromPairedDevices` | `(id: String) -> Unit` | unpair |
| `reset` | `() -> Unit` | factory-reset the device |
| `userLED` | `suspend (red=…, green=…, blue=…, blink=…, seconds: Int)` | LED control (awaits the BLE write) |
| `hapticMotor` | `suspend (pulse: Boolean = false, intensity: Int, seconds: Int)` | run the haptic motor (awaits the BLE write). `pulse` = pulse vs. solid (haptic analogue of `blink`); `intensity` is 0..100% |
| `updateFirmware` / `setFirmwareUpdateDeviceId` | `suspend (url, delay?, size?)` *(throws `SB_FirmwareUpdateError(canRetry)`)* / `(deviceId: String?)` | firmware flash (`url` = local file); progress on the `firmwareProgress` event (§3.2). `setFirmwareUpdateDeviceId` is the session-guard seam |
| `updateConnectedDeviceFirmware` | `(packet: SB_FirmwareVersionPacket) -> Unit` | apply a resolved firmware-version packet to the connected device |
| `migrateDeviceTypeAfterFlash` | `() -> Unit` | call after a flash completes: if the device was an Alter/AlterV2 migrated onto Sensr firmware, rewrite its stored type to the Sensr equivalent + re-register so the forced-update gate stops re-firing on reconnect. No-op for a same-type flash |
| `setAskForDeviceResponse` | `(enable: Boolean) -> Unit` | device button-tap prompting |
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

- **Sync notifications** — observe `syncNotificationActions` and raise OS notifications from it.
- **Involuntary sign-out** — observe `signOutComplete`, run teardown, and route to login.
- **Analytics** — observe `analyticsEvents` and forward them to your backend.

---

## 5. Server APIs (flat on the facade)

Called directly on `SensorBioSDK.<method>(…)`. Reads are `suspend fun … : SB_…`.

| Domain | Methods (flat on `SensorBioSDK`) |
|---|---|
| Dashboard | `fetchDashboardData(date: Instant, tzOffset, forceRemote)`, `cachedDashboardData(date: Instant, tzOffset)` *(cache-only peek, null on miss — no network; for stale-while-revalidate paint)*, `clearDashboardData(date: Instant)` |
| Trending | `fetchRangeHR`/`fetchDailyHR`, `fetchRangeHRV`/`fetchDailyHRV`, `fetchRangeRR`/`fetchDailyRR`, `fetchRangeSpO2`/`fetchDailySpO2`, `fetchCalories`, `fetchSteps`, `fetchDailyActivityDetail`, `fetchRangeRecovery`/`fetchDailyRecovery` *(all take `date: Instant`; `forceRemote` optional)* |
| Sleep | `fetchSleepDetail(endDate: Instant, endTimestamp, forceRemote?)`, `fetchSleepAggregation(date: Instant, …, forceRemote?)`, `fetchSleepSessions(date: Instant)`, `deleteSleepSession(endTimestamp, date: Instant)`, `modifySleepSession(onset: Instant, wakeUp: Instant, endTimestamp, date: Instant) -> String`, `addSleepSession(onset: Instant, wakeUp: Instant)` *(writes throw `SB_SleepWriteError`)* |
| Workouts | `fetchWorkoutDetail(workoutTime: Instant)`, `modifyWorkout(action, date: Instant, timestamp: Instant, …)`, `fetchWorkoutSummary(date: Instant, granularity: SB_SummaryGranularity, workoutName, workoutTime: Instant)`, `fetchWorkoutTimeline(…, direction: SB_PageFetchDirection) -> SB_WorkoutTimelineResult`, `fetchWorkoutRecordingInfo` |
| In-flight submissions | `reconcileSubmissions(entries: List<SB_WorkoutEntry>)` *(flip matched in-flight cards → processed; call after each `fetchWorkoutTimeline` with `result.items.flatMap { it.entries }`; no network)*, `retrySubmission(startTimestamp)` *(re-drive a FAILED submission)* — observe via `inflightSubmissions` (§3.1) |
| Activities | `fetchActivityList(force: Boolean = false) -> SB_ActivityRecordingList`, `fetchTrainedActivities()` |
| Spot-check | `fetchSpotCheckDetails(recordingId, impersonatedUserId?)` *(one-shot suspend read; throws on RPC error)* |
| Recording meta | `fetchRecordingMetaInfo(type) -> List<SB_RecordingSessionMetaItem>`, `deleteRecordingMeta(id, name, type)` |
| Insights | `fetchNewInsights`, `submitInsightsFeedback`, `fetchPopulationInsightsMetricList`, `fetchPopulationInsights` |
| Meditation | `fetchMeditationGraph(date: Instant, sessionTimestamp)` |
| Surveys | `submitBriefSurvey(survey, impersonatedUserId?)` *(suspend; awaits the upload)* |
| Goals | `fetchGoals()`; `updateGoals(steps, calories, sleep)` *(suspend → `SB_UpdateGoalsOutcome`)* |
| Stats | `fetchDailyStats(startDate, days, includeBiometrics, includeSleep, includeSteps)` |
| Agreements | `shouldRequestAgreement`, `acceptAgreements(tosVersion, healthDataVersion)`, `acceptCurrentAgreements` *(suspend)* |
| Account | `createAccount(SB_CreateAccountRequest)`, `updateUserProfile(SB_UserProfileUpdate)`, `changePassword(currentPassword, newPassword)`, `requestPasswordReset`, `checkEmailAvailability`, `validateAccountRequirements(SB_ValidateAccountRequirementsRequest) -> SB_ValidateAccountRequirementsResult`, `refreshUser`, `hydrateSession`, `generateTemporaryAuthToken() -> String?`, `registerApp(deviceId)` |
| Recording submit | `createActivitySession(activityName, startEpochMs, durationSecs)` *(suspend; manual after-the-fact log)* |
| Session | `signIn(email, password) -> SB_SignInOutcome`, `signOut()`, `persistUser`, `deleteAccount`, `clearSession`, `clearPrefsOnLogout` *(signed-in identity is observable — see §3.1 `session`/`userProfileFlow`)* |
| Server writes | `reprocessSleep` *(suspend; user-tapped, throws on failure)*, `updateUserDeviceInfo`, `uploadUserPhoto` *(→ URL)*, `deleteUserPhoto` |

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


---

## 6. Domain types (`SB_*`)

~276 public `SB_*` types the facade returns/accepts. Grouped index:

- **User / auth** — `SB_UserProfile`, `SB_UserDemographics`, `SB_UserAppSettings`, `SB_Session`,
  `SB_SignInOutcome`, `SB_CreateAccountOutcome`, `SB_ChangePasswordOutcome`,
  `SB_EmailAvailabilityOutcome`, `SB_UpdateUserProfileOutcome`, `SB_RequestPasswordResetOutcome`,
  `SB_AgreementCheck`, `SB_Gender`, `SB_CreateAccountRequest`, `SB_UserProfileUpdate`,
  `SB_ValidateAccountRequirementsRequest`, `SB_ValidateAccountRequirementsResult`,
  `SB_OrganizationMembership`, `SB_OrganizationMemberStatus`, `SB_OrgMembership`.
- **Devices / Bluetooth** — `SB_PairedDeviceState`, `SB_DiscoveredDevice`, `SB_BSDeviceModel`, `SB_BluetoothDeviceType`,
  `SB_ConnectionStage`, `SB_BluetoothResetState`, `SB_BatteryLevel`, `SB_DeviceLinkFailure`,
  `SB_SyncResult`, `SB_FirmwareInfo`, `SB_FirmwareUpdateError`, `SB_FirmwareVersionPacket`, `SB_DeviceResetResult`, `SB_ServerDeviceName`.
- **Recording** — `SB_RecordingState`, `SB_RecordingFinalizationPhase`, `SB_RecordingMetaType`,
  `SB_RecordingSession`, `SB_RecordingInfo`, `SB_RecordingCustomization`, `SB_PersistedRecording`, `SB_ActiveRecordingInfo`,
  `SB_RecordingError`, `SB_RawSensorDataLogging`, `SB_SpotCheckDetails`,
  `SB_SubmitFinishedRecordingResult`, `SB_LatestBookend`, `SB_BiometricRecordResult`,
  `SB_ExerciseZoneAttributes` / `SB_HREffortZone`.
- **Sleep** — `SB_SleepItem`, `SB_SleepDetailDay`, `SB_SleepDetailAggregated`, `SB_SleepStages`,
  `SB_SleepStage`, `SB_SleepBiometrics`, `SB_SleepScore`(+factors/penalties/sections), `SB_SleepPosition`,
  `SB_SleepDisturbances`, `SB_SleepApneaInfo`, `SB_SleepAccounting`, `SB_SleepDebt*`, `SB_SleepMetric`,
  `SB_SleepWriteError`, `SB_DetectedSleep`. (~40 types)
- **Activity / workout / steps** — `SB_TrainedActivity`, `SB_ActivityRecordingList`,
  `SB_ActivitySummary`, `SB_WorkoutDetail`, `SB_WorkoutTimelineResult`, `SB_WorkoutSummaryMetric`,
  `SB_ModifyAction`, `SB_ModifyOutcome`, `SB_ExerciseZones`, `SB_StepsTrending`,
  `SB_StepMetric`, `SB_StepMetricType`, `SB_ActivityDetail`, `SB_ActivityScore`,
  `SB_SummaryGranularity`, `SB_PageFetchDirection`,
  `SB_WLSRecordingType`, `SB_WorkoutRecordingInfo`(+`SB_OngoingWorkoutProgram`).
- **Biometrics / metrics** — HR/HRV/SpO2/RR daily+range graph & trending families
  (`SB_HR*`, `SB_HRV*`, `SB_SpO2*`, `SB_RR*`); live per-sample stream payloads `SB_HeartRateSample` /
  `SB_HrvSample` / `SB_RespiratoryRateSample` / `SB_SnrSample` / `SB_BbiSample` / `SB_PpgSample` /
  `SB_EcgSample` (§3.2); `SB_LiveMetric`; `SB_HRMData`(+`SB_HRMCategory`); `SB_TimeValuePoint`, `SB_DateValuePoint`,
  `SB_PoincarePlotGraph`, `SB_BarGraph`, `SB_CalorieMetric`, `SB_CaloriesTrending`, `SB_CardioStats`.
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
- **Events / elements** — `SB_NotificationElement`, `SB_SyncNotificationAction`.
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

// Sign in
val outcome = SensorBioSDK.signIn(email = email, password = password)

// In a ViewModel
viewModelScope.launch {
    val dashboard = SensorBioSDK.fetchDashboardData(date = Instant.now(), tzOffset = tz, forceRemote = false)
    _state.value = dashboard
}
SensorBioSDK.deviceFullyConfigured.onEach { onDeviceReady() }.launchIn(viewModelScope)
SensorBioSDK.deviceDisconnected.onEach { mac -> onDeviceLost(mac) }.launchIn(viewModelScope)
```

See [`ExampleApp/`](https://github.com/GetSensr-io/mobile_sensorbio_sdk_android_binary/tree/main/ExampleApp)
in the binary repo for a complete reference integration (sign-in → pair → connect → dashboard).
