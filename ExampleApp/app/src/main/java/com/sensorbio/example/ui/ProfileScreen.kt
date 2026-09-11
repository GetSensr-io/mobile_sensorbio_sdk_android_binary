package com.sensorbio.example.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sensorbio.example.Env
import com.sensorbio.example.SdkTokenExchange
import com.sensorbio.example.SdkTokenRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.sensorbio.sensorbiosdk.SensorBioSDK
import com.sensorbio.sensorbiosdk.datatypes.SB_Environment
import com.sensorbio.sensorbiosdk.datatypes.SB_Unit
import com.sensorbio.sensorbiosdk.datatypes.SB_UpdateUserProfileOutcome
import com.sensorbio.sensorbiosdk.datatypes.SB_UserProfileUpdate
import kotlinx.coroutines.launch

@Composable
fun ProfileScreen(usernameOrEmail: String, onPair: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val profile by SensorBioSDK.userProfileFlow.collectAsStateWithLifecycle()
    val haveDevice by SensorBioSDK.haveDevice.collectAsStateWithLifecycle()

    // Editable fields, prefilled from the current profile once it arrives.
    var year by remember { mutableStateOf("") }
    var month by remember { mutableStateOf("") }
    var day by remember { mutableStateOf("") }
    var height by remember { mutableStateOf("") }
    var weight by remember { mutableStateOf("") }
    var prefilled by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var saveMsg by remember { mutableStateOf<String?>(null) }
    var showingToken by remember { mutableStateOf(false) }

    LaunchedEffect(profile) {
        val p = profile ?: return@LaunchedEffect
        if (!prefilled) {
            // `birthday` is null when the server has none — the fields stay empty rather than
            // showing a sentinel date, and the zero-check these lines used to need is gone.
            year = p.birthday?.year?.toString() ?: ""
            month = p.birthday?.month?.toString() ?: ""
            day = p.birthday?.day?.toString() ?: ""
            height = p.heightCM?.let { "%.0f".format(it) } ?: ""
            weight = p.weightKG?.let { "%.1f".format(it) } ?: ""
            prefilled = true
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Profile", style = MaterialTheme.typography.headlineSmall)

        // --- Read-only identity ---
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val p = profile
                InfoRow("Signed in", usernameOrEmail)
                InfoRow("Name", p?.name ?: "—")
                InfoRow("Email", p?.email ?: "—")
                InfoRow("Sex", p?.sex?.name ?: "—")
                InfoRow("Age", p?.age?.toString() ?: "—")
                InfoRow("Units", p?.units?.name ?: "—")
                InfoRow("SDK version", SensorBioSDK.version)
            }
        }

        // --- What this session was registered with ---
        //
        // A demonstration, not a pattern to copy: this app minted the token in-process, so it can
        // show exactly what went on the wire. A real app receives a token from its backend, hands it
        // to registerUser, and forgets it.
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Registered with", style = MaterialTheme.typography.titleMedium)
                val token = SdkTokenRecord.token
                if (token == null) {
                    Text(
                        "Session restored — no register this launch.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "A relaunch does not re-register: the SDK restored the access/refresh pair " +
                            "from its store, so no token was minted and none was needed. A token is " +
                            "a bootstrap credential, spent by the one register that used it. Sign " +
                            "out and register to see a fresh one.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Row(
                        Modifier.fillMaxWidth().clickable { showingToken = true },
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("sdk_token")
                            Text(
                                "${token.sdkToken.take(12)}…${token.sdkToken.takeLast(6)}",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text("View", color = MaterialTheme.colorScheme.primary)
                    }
                    Text(
                        "The single-use token the register call presented, exchanged from your SDK " +
                            "Key. Tap to see the whole thing.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // --- Editable metrics ---
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Edit metrics", style = MaterialTheme.typography.titleMedium)
                Text("Birthday", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumField(year, { year = it }, "Year", Modifier.weight(1.2f))
                    NumField(month, { month = it }, "Mo", Modifier.weight(0.8f))
                    NumField(day, { day = it }, "Day", Modifier.weight(0.8f))
                }
                NumField(height, { height = it }, "Height (cm)", Modifier.fillMaxWidth(), decimal = true)
                NumField(weight, { weight = it }, "Weight (kg)", Modifier.fillMaxWidth(), decimal = true)

                Button(
                    enabled = profile != null && !saving,
                    onClick = {
                        val p = profile ?: return@Button
                        saving = true
                        saveMsg = null
                        scope.launch {
                            try {
                                val update = SB_UserProfileUpdate(
                                    fullName = p.name,
                                    // The update RPC always writes a birthday, so an edit has
                                    // to state one. Fall back to the profile's, else the neutral
                                    // date the SDK's registerUser substitutes.
                                    birthdayYear = year.toIntOrNull() ?: p.birthday?.year ?: 1990,
                                    birthdayMonth = month.toIntOrNull() ?: p.birthday?.month ?: 6,
                                    birthdayDay = day.toIntOrNull() ?: p.birthday?.day ?: 15,
                                    gender = p.sex,
                                    heightCm = height.toFloatOrNull() ?: p.heightCM,
                                    weightKg = weight.toFloatOrNull() ?: p.weightKG,
                                    walkingStrideLength = p.walkingStrideLengthCM,
                                    runningStrideLength = p.runningStrideLengthCM,
                                    location = null,
                                    vo2Max = p.vo2Max,
                                    maxHr = p.maxHr,
                                    imperialUnits = p.units == SB_Unit.IMPERIAL,
                                )
                                saveMsg = when (val r = SensorBioSDK.updateUserProfile(update)) {
                                    SB_UpdateUserProfileOutcome.Ok -> "Saved ✓"
                                    SB_UpdateUserProfileOutcome.InvalidHeight -> "Invalid height"
                                    SB_UpdateUserProfileOutcome.InvalidWeight -> "Invalid weight"
                                    SB_UpdateUserProfileOutcome.InvalidBirthday -> "Invalid birthday"
                                    is SB_UpdateUserProfileOutcome.Other -> r.message
                                }
                            } catch (t: Throwable) {
                                saveMsg = "Error: ${t.message ?: t::class.simpleName}"
                            } finally {
                                saving = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (saving) "Saving…" else "Save changes") }

                saveMsg?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            }
        }

        // Pairing is an account action, not a dashboard one — it belongs with the
        // session it attaches the band to, which is where iOS has always had it.
        if (!haveDevice) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Device", style = MaterialTheme.typography.titleMedium)
                    Text("No device paired.")
                    Button(onClick = onPair, modifier = Modifier.fillMaxWidth()) { Text("Pair a device") }
                }
            }
        }

        Button(
            onClick = {
                scope.launch {
                    // Full logout teardown: signOut() awaits the RPC + clears the session/caches/DB,
                    // then clearPrefsOnLogout() wipes sdk_prefs so the session doesn't re-hydrate on
                    // relaunch (without this, the persisted user survives and you get signed back in).
                    runCatching { SensorBioSDK.signOut() }
                    SensorBioSDK.clearPrefsOnLogout()
                    // Re-apply the chosen environment (the prefs wipe clears it).
                    SensorBioSDK.environment =
                        if (Env.isDev(context)) SB_Environment.DEVELOPMENT else SB_Environment.PRODUCTION
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Sign out") }
    }

    if (showingToken) {
        SdkTokenRecord.token?.let { SdkTokenDialog(it) { showingToken = false } }
    }
}

@Composable
private fun NumField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    decimal: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number,
        ),
        modifier = modifier,
    )
}

/**
 * The full `sdk_token` the register presented, plus the rest of what the exchange returned. Shown
 * because this app minted it in-process; a real app has no reason to surface one.
 */
@Composable
private fun SdkTokenDialog(token: SdkTokenExchange.MintedToken, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("SDK token") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SelectionContainer {
                    Text(
                        token.sdkToken,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                Text(
                    "Spent. A token is good for exactly one register-or-login, and Sensor Bio keeps " +
                        "only its SHA-256 hash plus the ${token.sdkToken.take(9)}… prefix — which is " +
                        "the half you quote in a bug report.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                InfoRow("organization_id", token.organizationId)
                InfoRow("sdk_key_id", token.sdkKeyId.ifBlank { "—" })
                InfoRow("expires_in_seconds", token.expiresInSeconds.toString())
                SdkTokenRecord.mintedAtMillis?.let {
                    InfoRow("minted", TIME_FORMAT.format(Date(it)))
                    InfoRow("expires", TIME_FORMAT.format(Date(it + token.expiresInSeconds * 1000L)))
                }
                Text(
                    "organization_id and sdk_token are the only two fields your backend has to " +
                        "return to your app. sdk_key_id names the SDK Key this token was anchored " +
                        "to — revoking that key signs out every session minted under it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = {
            TextButton(onClick = { clipboard.setText(AnnotatedString(token.sdkToken)) }) {
                Text("Copy token")
            }
        },
    )
}

private val TIME_FORMAT = SimpleDateFormat("HH:mm:ss", Locale.US)

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}
