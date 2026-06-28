package com.sensorbio.example

import android.app.Application
import android.util.Log
import com.sensorbio.sensorbiosdk.SensorBioSDK
import com.sensorbio.sensorbiosdk.datatypes.SB_AppConfig
import com.sensorbio.sensorbiosdk.datatypes.SB_AppType
import com.sensorbio.sensorbiosdk.datatypes.SB_Environment

/**
 * The required SDK init pattern. Mirrors the iOS sample's `@main` init:
 * `initialize` once, then set the environment + a log sink.
 */
class ExampleApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // One entry point — stands up the encrypted prefs store, runs the legacy migrator, wires subsystems.
        SensorBioSDK.initialize(
            this,
            SB_AppConfig(appType = SB_AppType.SENSR, appFlavor = "example"),
        )

        // Environment persists across launches (default staging, for SDK dogfooding).
        SensorBioSDK.environment =
            if (Env.isDev(this)) SB_Environment.DEVELOPMENT else SB_Environment.PRODUCTION

        // The SDK does not log itself — the host routes its log stream wherever it wants.
        SensorBioSDK.logHandler = { level, message, _ ->
            Log.d("SensorBioSDK", "[$level] ${message ?: ""}")
        }
    }
}
