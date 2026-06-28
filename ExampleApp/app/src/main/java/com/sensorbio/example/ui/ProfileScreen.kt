package com.sensorbio.example.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sensorbio.example.Env
import com.sensorbio.sensorbiosdk.SensorBioSDK
import com.sensorbio.sensorbiosdk.datatypes.SB_Environment
import com.sensorbio.sensorbiosdk.datatypes.SB_Unit
import com.sensorbio.sensorbiosdk.datatypes.SB_UpdateUserProfileOutcome
import com.sensorbio.sensorbiosdk.datatypes.SB_UserProfileUpdate
import kotlinx.coroutines.launch

@Composable
fun ProfileScreen(usernameOrEmail: String) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val profile by SensorBioSDK.userProfileFlow.collectAsStateWithLifecycle()

    // Editable fields, prefilled from the current profile once it arrives.
    var year by remember { mutableStateOf("") }
    var month by remember { mutableStateOf("") }
    var day by remember { mutableStateOf("") }
    var height by remember { mutableStateOf("") }
    var weight by remember { mutableStateOf("") }
    var prefilled by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var saveMsg by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(profile) {
        val p = profile ?: return@LaunchedEffect
        if (!prefilled) {
            year = p.birthday.year.takeIf { it > 0 }?.toString() ?: ""
            month = p.birthday.month.takeIf { it > 0 }?.toString() ?: ""
            day = p.birthday.day.takeIf { it > 0 }?.toString() ?: ""
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
                                    birthdayYear = year.toIntOrNull() ?: p.birthday.year,
                                    birthdayMonth = month.toIntOrNull() ?: p.birthday.month,
                                    birthdayDay = day.toIntOrNull() ?: p.birthday.day,
                                    gender = p.sex,
                                    heightCm = height.toFloatOrNull() ?: p.heightCM,
                                    weightKg = weight.toFloatOrNull() ?: p.weightKG,
                                    walkingStrideLength = p.walkingStrideLengthCM,
                                    runningStrideLength = p.runningStrideLengthCM,
                                    zipcode = null,
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

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}
