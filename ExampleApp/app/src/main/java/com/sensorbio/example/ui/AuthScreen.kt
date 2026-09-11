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
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
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
import com.sensorbio.example.Creds
import com.sensorbio.example.Env
import com.sensorbio.example.SdkTokenExchange
import com.sensorbio.example.SdkTokenRecord
import com.sensorbio.sensorbiosdk.SensorBioSDK
import com.sensorbio.sensorbiosdk.datatypes.SB_Environment
import com.sensorbio.sensorbiosdk.datatypes.SB_RegisterUserOutcome
import com.sensorbio.sensorbiosdk.datatypes.SB_SDKCredentials
import kotlinx.coroutines.launch

/**
 * SDK-token auth surface. This example is a **third-party SDK integration**: users are registered
 * password-lessly via [SensorBioSDK.registerUser], not the first-party email/password sign-in or
 * create-account flows (those were removed).
 *
 * Registering is two steps: exchange the organization SDK Key for a single-use `sdk_token`
 * ([SdkTokenExchange] — the part a real integration puts on its **own backend**), then register with
 * that token. The exchange also returns `organization_id`, so there is no Org ID to type.
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
 * Exchange, then register. The exchange is what your backend does for you; the SDK Key is typed here
 * at runtime (and saved, so you don't retype it every run) because this app is standing in for that
 * backend. On success the SDK persists the session and publishes `userProfileFlow`, so [AppRoot]
 * routes to the dashboard, and every subsequent authenticated call rides the `access_token`
 * auth-session protocol.
 */
@Composable
private fun SdkRegisterForm() {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val creds = remember { Creds.from(context) }

    // The SDK Key + your own user id, remembered across launches. The org id is not typed at all —
    // the exchange resolves it from the key.
    var sdkKey by remember { mutableStateOf(creds.sdkKey()) }
    var userId by remember { mutableStateOf(creds.userId()) }
    var activationCode by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var minted by remember { mutableStateOf<SdkTokenExchange.MintedToken?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Register-or-login for a user your app has already authenticated. The SDK Key is " +
                "exchanged for a single-use SDK token, which is what registerUser presents. No " +
                "email/password.",
            style = MaterialTheme.typography.bodySmall,
        )

        // Deliberately a card, not fine print: copying this app's in-app exchange into a real app
        // ships the organization's SDK Key to every install.
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
        ) {
            Row(
                Modifier.padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Filled.Warning, contentDescription = null)
                Text(
                    "Dev stand-in — YOUR BACKEND does this exchange. A shipping app never holds " +
                        "the SDK Key.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        AuthField(sdkKey, { sdkKey = it }, "SDK Key (sbsk_… secret)", isPassword = true)
        AuthField(userId, { userId = it }, "User ID (client_sdk_user_id)")
        AuthField(activationCode, { activationCode = it }, "Activation code (optional)")

        Button(
            onClick = {
                submitting = true
                message = null
                minted = null
                scope.launch {
                    try {
                        creds.save(sdkKey = sdkKey, userId = userId)

                        // 1) What your backend does: exchange the long-lived key for a single-use
                        //    token. Fresh every submit — a token is spent by the register it
                        //    succeeds at, and reusing one fails as an opaque auth error.
                        val token = SdkTokenExchange.mintToken(sdkKey.trim())
                        minted = token
                        SdkTokenRecord.record(token)
                        creds.saveOrgId(token.organizationId)

                        // 2) Hand the SDK exactly what the exchange returned. This is the only
                        //    credential it ever sees; the SDK Key that minted the token stays on
                        //    the "backend" side of this app's pretence.
                        SensorBioSDK.sdkCredentials = SB_SDKCredentials(
                            organizationId = token.organizationId,
                            sdkToken = token.sdkToken,
                        )

                        // 3) Register-or-login the org's user.
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
                        // An exchange failure means no register call was made at all.
                        message = if (minted == null) {
                            "Couldn't mint an SDK token — no register call was made. ${t.message ?: t::class.simpleName}"
                        } else {
                            "Error: ${t.message ?: t::class.simpleName}"
                        }
                    } finally {
                        submitting = false
                    }
                }
            },
            enabled = sdkKey.isNotBlank() && userId.isNotBlank() && !submitting,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (submitting) CircularProgressIndicator(Modifier.height(20.dp)) else Text("Exchange token & register")
        }

        minted?.let { token ->
            Text(
                "Org ${token.organizationId}\nToken ${token.sdkToken.take(9)}… (${token.expiresInSeconds}s, single use)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
