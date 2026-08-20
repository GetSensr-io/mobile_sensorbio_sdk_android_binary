# ExampleApp

A reference Jetpack Compose integration of `SensorBioSDK`, consumed as a **binary** from the public
Maven repo (GitHub Pages) at the root of this repository. This is what you'd build in your own app,
modulo the UI.

The pieces that matter:

- **`settings.gradle.kts`** — the single SensorBio line is the Maven repo:
  `maven { url = uri("https://getsensr-io.github.io/mobile_sensorbio_sdk_android_binary/") }`.
- **`app/build.gradle.kts`** — one dependency: `implementation("com.sensorbio:sensorbio-sdk:<version>")`.
  It brings the embedded BLE + edge binaries and the OSS transitive deps (incl. coroutines).
- **`AndroidManifest.xml`** — BLE runtime permissions (`BLUETOOTH_SCAN`/`BLUETOOTH_CONNECT`, plus
  `ACCESS_FINE_LOCATION`, which BLE scanning needs at every API level). The SDK's own permissions +
  foreground service merge in.
- **`ExampleApplication.kt`** — the required init pattern: `SensorBioSDK.initialize(...)` →
  set `environment` → wire `logHandler`.
- **`MainActivity.kt`** — requests the BLE runtime permissions (the host's responsibility).
- **`ui/`** — the flow: `session`-gated **AuthScreen** (`registerUser` + staging/prod toggle) →
  **MainScaffold** (connection/battery indicator + Dashboard / Insights / Profile), and a
  **PairDeviceScreen** that renders `pairingState`.

## What it demonstrates

| Area | SDK API |
|------|---------|
| Init | `SensorBioSDK.initialize`, `environment`, `logHandler`, `version` |
| Auth | `sdkKeyCredentials`, `registerUser`, `signOut`, observe `session` / `userProfileFlow` |
| Pairing | `beginPairing`, `selectDevice`, `endPairing`, observe `pairingState` |
| Device | observe `connected` / `batteryLevel` / `charging` / `haveDevice` / `pairedDevice` / `serialNumber`; `userLED`, `reset`, `removeDeviceFromPairedDevices` |
| Reads | `fetchGoals`, `fetchDailyHR`/`fetchRangeHR` (and the HRV / RR / recovery / steps / calories / sleep / activity equivalents), `fetchPopulationInsights` |
| Profile | `updateUserProfile`, `clearPrefsOnLogout` |

### Registration

`registerUser` is the SDK's registration path: set `SensorBioSDK.sdkKeyCredentials` once with your org
id + SDK key, then call `registerUser(userId = …)` with **your own** stable identifier for a user your
app has already authenticated (your login, SSO, OAuth — the SDK doesn't care which). It is
**register-or-login**: the first call for a given `userId` registers, later calls sign the same user
back in. There is no email/password path in the SDK — your users have no Sensor Bio credentials to
supply.

### Pairing

Pairing is **one SDK-owned transaction**. `PairDeviceScreen.kt` is a renderer over
`SensorBioSDK.pairingState` making three calls — `beginPairing()`, `selectDevice(macAddress)`,
`endPairing()`. Scanning, discovery de-duplication, connecting, the on-band blink/buzz confirmation and
button listening, persistence, server registration, device configuration and every timeout happen
inside the SDK. The host renders the state and supplies the runtime Bluetooth permission grant; it
never sequences the steps itself. A transaction that ends any way other than `Paired` leaves no trace,
so there is nothing to undo on the failure paths.

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

- Everything in this app is built against the SDK's **public** surface only — first-party and
  white-label API is not present in the published artifact, so the app cannot reach for it.
- `app/build.gradle.kts` sets `missingDimensionStrategy("surface", "public")`. It is inert against the
  published artifact (a single variant) and is what selects the customer surface if you ever build the
  SDK from source.
