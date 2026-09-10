package com.sensorbio.example

import android.app.Application
import android.util.Log
import com.sensorbio.sensorbiosdk.SensorBioSDK
import com.sensorbio.sensorbiosdk.datatypes.SB_AppConfig
import com.sensorbio.sensorbiosdk.datatypes.SB_AppType
import com.sensorbio.sensorbiosdk.datatypes.SB_Environment
import com.sensorbio.sensorbiosdk.datatypes.SB_SDKKeyCredentials

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

        // SDK-key mode: the SDK holds the org credentials in memory only, so a cold launch that
        // hydrates a prior SDK-key session must re-supply them before the first authenticated call.
        // The org id is whatever the last token exchange resolved; the SDK Key is what the
        // authenticated RPCs after a register still carry. The single-use `sdk_token` is deliberately
        // NOT persisted: it is spent by the register that used it and accepted on no other call.
        val creds = Creds.from(this)
        if (creds.orgId().isNotBlank() && creds.sdkKey().isNotBlank()) {
            SensorBioSDK.sdkKeyCredentials =
                SB_SDKKeyCredentials(org_id = creds.orgId(), sdk_token = creds.sdkKey())
        }

        // How the SDK asks for a single-use token when it needs one and hasn't been handed one: a
        // `registerUser(userId)` with no token, or a session that has died past refreshing and has to
        // be rebuilt. In a real app this lambda calls your backend; set it once here and no call site
        // ever plumbs a token again.
        SensorBioSDK.sdkTokenProvider = SdkTokenExchange.makeProvider(creds)

        // The SDK does not log itself — the host routes its log stream wherever it wants.
        SensorBioSDK.logHandler = { level, message, _ ->
            Log.d("SensorBioSDK", "[$level] ${message ?: ""}")
        }
    }
}
