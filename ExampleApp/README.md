# ExampleApp

A reference Jetpack Compose integration of `SensorBioSDK`, consumed as a **binary** from the public
Maven repo (GitHub Pages) at the root of this repository. This is what you'd build in your own app,
modulo the UI.

The pieces that matter:

- **`settings.gradle.kts`** — the single SensorBio line is the Maven repo:
  `maven { url = uri("https://getsensr-io.github.io/mobile_sensorbio_sdk_android_binary/") }`.
- **`app/build.gradle.kts`** — one dependency: `implementation("com.sensorbio:sensorbio-sdk:0.9.0")`.
  It brings the embedded BLE + edge binaries and the OSS transitive deps (incl. coroutines).
- **`AndroidManifest.xml`** — BLE runtime permissions (`BLUETOOTH_SCAN`/`BLUETOOTH_CONNECT`, plus
  `ACCESS_FINE_LOCATION` on API < 31). The SDK's own permissions + foreground service merge in.
- **`ExampleApplication.kt`** — the required init pattern: `SensorBioSDK.initialize(...)` →
  set `environment` → wire `logHandler`.
- **`MainActivity.kt`** — requests the BLE runtime permissions (the host's responsibility).
- **`ui/`** — the flow: `session`-gated **AuthScreen** (sign-in + staging/prod toggle) →
  **MainScaffold** (connection/battery indicator + Dashboard / Insights / Profile) and a
  **PairDeviceScreen** (scan → connect → confirm by device button-tap → `addPairedDevice`).

## What it demonstrates

| Area | SDK API |
|------|---------|
| Init | `SensorBioSDK.initialize`, `environment`, `logHandler` |
| Auth | `signIn`, `signOut`, observe `session` / `userProfileFlow` |
| Pairing | `startScan`/`stopScan`, observe `deviceDiscovered`, `connect(id, pairing = true)`, `pairingConnection`, `userLED`, `setAskForDeviceResponse`, `buttonTaps`, `addPairedDevice` |
| Device telemetry | observe `connected` / `batteryLevel` / `charging` / `haveDevice` |
| Reads | `fetchGoals`, `fetchDailyHR`, `fetchDailyHRV`, `fetchNewInsights`, `featureFlags`, `networkStatus` |

See [`../SDK_INTERFACE.md`](../SDK_INTERFACE.md) for the full public surface.

## Building

```bash
# From this directory:
./gradlew :app:assembleDebug
# install + run on a connected device (BLE needs a physical phone):
./gradlew :app:installDebug
```

Open in Android Studio by pointing it at this `ExampleApp/` directory. A `local.properties` with
`sdk.dir=` is required (Android Studio writes one automatically).

## Notes

- Account creation (`createAccount`) is referenced but not wired in this sample — the focus is the
  sign-in → pair → dashboard flow.
- The SDK ships the `public` surface only; first-party/white-label API is not present in the binary.
