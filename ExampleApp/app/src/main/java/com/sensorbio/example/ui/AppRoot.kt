package com.sensorbio.example.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sensorbio.sensorbiosdk.SensorBioSDK

/**
 * Top-level gate: observe the SDK's `session`. Null → signed out (auth). Non-null → the main app.
 * Mirrors the iOS sample's `ContentView` switching on `sensorBio.session`.
 */
@Composable
fun AppRoot() {
    val session by SensorBioSDK.session.collectAsStateWithLifecycle()
    val current = session
    if (current == null) {
        AuthScreen()
    } else {
        MainScaffold(usernameOrEmail = current.username.ifBlank { current.email })
    }
}
