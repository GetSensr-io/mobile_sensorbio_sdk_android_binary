package com.sensorbio.example

import android.app.Application
import android.util.Log
import com.sensorbio.sensorbiosdk.SensorBioSDK
import com.sensorbio.sensorbiosdk.datatypes.SB_Environment

/**
 * The required SDK init pattern. Mirrors the iOS sample's `@main` init:
 * `initialize` once, then set the environment + a log sink.
 */
class ExampleApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // One entry point — stands up the encrypted prefs store, runs the legacy migrator, wires
        // subsystems. A customer integration passes nothing but the context: `SB_AppConfig` carries
        // first-party brand identity (`appType`/`appFlavor`) that has no correct value for a
        // third-party app and no effect on one, so it is defaulted (SB-2095).
        SensorBioSDK.initialize(this)

        // Environment persists across launches (default staging, for SDK dogfooding).
        SensorBioSDK.environment =
            if (Env.isDev(this)) SB_Environment.DEVELOPMENT else SB_Environment.PRODUCTION

        // Nothing else to configure. The SDK remembers which organization a session belongs to,
        // so a cold launch into a restored session needs no credentials from the host — and the
        // organization SDK Key is never on the device to re-supply in the first place (SB-2095).
        //
        // What used to be here: a restore of `sdkKeyCredentials` read back out of local storage,
        // including the raw SDK Key, plus a token-provider lambda to mint from. Both are gone.

        // The SDK does not log itself — the host routes its log stream wherever it wants.
        SensorBioSDK.logHandler = { level, message, _ ->
            Log.d("SensorBioSDK", "[$level] ${message ?: ""}")
        }
    }
}
