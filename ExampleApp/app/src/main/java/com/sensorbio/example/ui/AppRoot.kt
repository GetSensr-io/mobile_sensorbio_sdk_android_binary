package com.sensorbio.example.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sensorbio.example.Env
import com.sensorbio.sensorbiosdk.SensorBioSDK
import com.sensorbio.sensorbiosdk.datatypes.SB_Environment

/**
 * Top-level gate: observe the SDK's `session`. Null → signed out (auth). Non-null → the main app.
 * Mirrors the iOS sample's `ContentView` switching on `sensorBio.session`.
 */
@Composable
fun AppRoot() {
    val context = LocalContext.current
    val session by SensorBioSDK.session.collectAsStateWithLifecycle()

    // The session is over and cannot be rebuilt from the device — the credential was rejected, or
    // there is none to send. The SDK reports it and stops there: only your backend can mint the
    // fresh sdk_token a new session needs, so ending this one is your call, not the SDK's.
    //
    // Handle it, or your app stays on an authenticated screen where every call fails and the user
    // has no way back. This is the Android half of what the iOS sample does when `fetchDashboardData`
    // throws `SB_AuthError.refreshTokenExpired`: tear the session down and fall back to Register.
    LaunchedEffect(Unit) {
        SensorBioSDK.reauthenticationRequired.collect {
            // Same teardown as the Sign out button in ProfileScreen: signOut() clears the SDK's
            // session, clearPrefsOnLogout() wipes sdk_prefs so it cannot re-hydrate, and the
            // environment is re-applied because that wipe clears it too.
            runCatching { SensorBioSDK.signOut() }
            SensorBioSDK.clearPrefsOnLogout()
            SensorBioSDK.environment =
                if (Env.isDev(context)) SB_Environment.DEVELOPMENT else SB_Environment.PRODUCTION
        }
    }

    val current = session
    if (current == null) {
        AuthScreen()
    } else {
        MainScaffold(usernameOrEmail = current.username.ifBlank { current.email })
    }
}
