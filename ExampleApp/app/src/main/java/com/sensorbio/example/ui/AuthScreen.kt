package com.sensorbio.example.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.sensorbio.example.Env
import com.sensorbio.sensorbiosdk.SensorBioSDK
import com.sensorbio.sensorbiosdk.datatypes.SB_Environment
import com.sensorbio.sensorbiosdk.datatypes.SB_RegisterUserOutcome
import com.sensorbio.sensorbiosdk.datatypes.SB_SDKKeyCredentials
import kotlinx.coroutines.launch

/**
 * SDK-key-only auth surface (SB-1628). This example is a **third-party SDK integration**: users are
 * registered password-lessly via [SensorBioSDK.registerUser], not the first-party email/password
 * sign-in or create-account flows (those were removed). Set [SensorBioSDK.sdkKeyCredentials] once, then
 * call [SensorBioSDK.registerUser] with the org's own `userId`.
 */
@Composable
fun AuthScreen() {
    val context = LocalContext.current
    var isDev by remember { mutableStateOf(Env.isDev(context)) }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("SensorBio Example", style = MaterialTheme.typography.headlineMedium)
            Text(
                "SDK-key integration of com.sensorbio:sensorbio-sdk.",
                style = MaterialTheme.typography.bodyMedium,
            )

            // Environment toggle — flip before registering (mirrors the iOS sample).
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Environment", style = MaterialTheme.typography.titleSmall)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = isDev,
                            onClick = {
                                isDev = true
                                Env.setDev(context, true)
                                SensorBioSDK.environment = SB_Environment.DEVELOPMENT
                            },
                            shape = SegmentedButtonDefaults.itemShape(0, 2),
                        ) { Text("Staging") }
                        SegmentedButton(
                            selected = !isDev,
                            onClick = {
                                isDev = false
                                Env.setDev(context, false)
                                SensorBioSDK.environment = SB_Environment.PRODUCTION
                            },
                            shape = SegmentedButtonDefaults.itemShape(1, 2),
                        ) { Text("Prod") }
                    }
                }
            }

            HorizontalDivider()

            SdkRegisterForm()

            Spacer(Modifier.height(8.dp))
            Text(
                "SDK ${SensorBioSDK.version}",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

/**
 * SB-1628 SDK-key register-or-login. The host sets [SensorBioSDK.sdkKeyCredentials] once (in-memory,
 * never persisted), then calls [SensorBioSDK.registerUser] with just the org's own `userId` — no email
 * or password. On success the SDK persists the session and publishes `userProfileFlow`, so [AppRoot]
 * routes to the dashboard, and every subsequent authenticated call rides the new `access_token`
 * auth-session protocol. The SDK key is a secret, so it's typed here at runtime rather than baked in.
 */
@Composable
private fun SdkRegisterForm() {
    val scope = rememberCoroutineScope()
    // The host supplies its own org id + secret SDK key at runtime; the SDK never persists them.
    var orgId by remember { mutableStateOf("") }
    var sdkKey by remember { mutableStateOf("") }
    // You choose the user id + activation code on-device.
    var userId by remember { mutableStateOf("") }
    var activationCode by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Register-or-login for a user your app has already authenticated. Set sdkKeyCredentials, " +
                "then registerUser(userId). No email/password.",
            style = MaterialTheme.typography.bodySmall,
        )
        AuthField(orgId, { orgId = it }, "Org ID")
        AuthField(sdkKey, { sdkKey = it }, "SDK Token (secret)", isPassword = true)
        AuthField(userId, { userId = it }, "User ID (client_sdk_user_id)")
        AuthField(activationCode, { activationCode = it }, "Activation code (optional)")

        Button(
            onClick = {
                submitting = true
                message = null
                scope.launch {
                    try {
                        // 1) Put the SDK into SDK-key mode (in-memory only).
                        SensorBioSDK.sdkKeyCredentials =
                            SB_SDKKeyCredentials(org_id = orgId.trim(), sdk_token = sdkKey.trim())
                        // 2) Register-or-login the org's user.
                        val outcome = SensorBioSDK.registerUser(
                            userId = userId.trim(),
                            activationCode = activationCode.trim().ifEmpty { null },
                        )
                        message = when (outcome) {
                            is SB_RegisterUserOutcome.Success ->
                                "Registered — signed in as ${outcome.session.username}"
                            is SB_RegisterUserOutcome.Failed -> "Failed: ${outcome.code}"
                            else -> outcome::class.simpleName ?: "Unknown outcome"
                        }
                    } catch (t: Throwable) {
                        message = "Error: ${t.message ?: t::class.simpleName}"
                    } finally {
                        submitting = false
                    }
                }
            },
            enabled = orgId.isNotBlank() && sdkKey.isNotBlank() && userId.isNotBlank() && !submitting,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (submitting) CircularProgressIndicator(Modifier.height(20.dp)) else Text("SDK Register / Login")
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
    }
}

@Composable
private fun AuthField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    modifier: Modifier = Modifier.fillMaxWidth(),
    isPassword: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (isPassword) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = modifier,
    )
}
