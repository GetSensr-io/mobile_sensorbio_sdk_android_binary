package com.sensorbio.example.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.sensorbio.example.Env
import com.sensorbio.sensorbiosdk.SensorBioSDK
import com.sensorbio.sensorbiosdk.datatypes.SB_CreateAccountOutcome
import com.sensorbio.sensorbiosdk.datatypes.SB_CreateAccountRequest
import com.sensorbio.sensorbiosdk.datatypes.SB_Environment
import com.sensorbio.sensorbiosdk.datatypes.SB_Gender
import com.sensorbio.sensorbiosdk.datatypes.SB_SignInOutcome
import kotlinx.coroutines.launch

@Composable
fun AuthScreen() {
    val context = LocalContext.current
    var isDev by remember { mutableStateOf(Env.isDev(context)) }
    var tab by remember { mutableStateOf(0) } // 0 = Sign In, 1 = Create Account

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
            "Reference integration of com.sensorbio:sensorbio-sdk (binary).",
            style = MaterialTheme.typography.bodyMedium,
        )

        // Environment toggle — flip before signing in (mirrors the iOS sample).
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

        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(selected = tab == 0, onClick = { tab = 0 }, shape = SegmentedButtonDefaults.itemShape(0, 2)) {
                Text("Sign In")
            }
            SegmentedButton(selected = tab == 1, onClick = { tab = 1 }, shape = SegmentedButtonDefaults.itemShape(1, 2)) {
                Text("Create Account")
            }
        }

        HorizontalDivider()

        if (tab == 0) SignInForm() else SignUpForm()

        Spacer(Modifier.height(8.dp))
        Text(
            "SDK ${SensorBioSDK.version}",
            style = MaterialTheme.typography.labelSmall,
        )
    }
    }
}

@Composable
private fun SignInForm() {
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("Email") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = {
                submitting = true
                message = null
                scope.launch {
                    try {
                        when (val outcome = SensorBioSDK.signIn(email.trim(), password)) {
                            is SB_SignInOutcome.Success ->
                                message = "Signed in as ${outcome.session.username}"
                            SB_SignInOutcome.PasswordIncorrect -> message = "Password incorrect"
                            SB_SignInOutcome.UnknownUsername -> message = "Unknown username"
                            is SB_SignInOutcome.Other -> message = outcome.message
                        }
                    } catch (t: Throwable) {
                        message = "Error: ${t.message ?: t::class.simpleName}"
                    } finally {
                        submitting = false
                    }
                }
            },
            enabled = email.isNotBlank() && password.isNotBlank() && !submitting,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (submitting) {
                CircularProgressIndicator(Modifier.height(20.dp))
            } else {
                Text("Sign In")
            }
        }
        message?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun SignUpForm() {
    val scope = rememberCoroutineScope()
    var username by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var year by remember { mutableStateOf("1990") }
    var month by remember { mutableStateOf("1") }
    var day by remember { mutableStateOf("1") }
    var gender by remember { mutableStateOf(SB_Gender.UNDISCLOSED) }
    var heightCm by remember { mutableStateOf("") }
    var weightKg by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    val canSubmit = username.isNotBlank() && email.isNotBlank() && password.length >= 6 &&
        heightCm.toFloatOrNull() != null && weightKg.toFloatOrNull() != null && !submitting

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AuthField(username, { username = it }, "Username")
        AuthField(email, { email = it }, "Email", KeyboardType.Email)
        AuthField(password, { password = it }, "Password (min 6)", KeyboardType.Password, isPassword = true)

        Text("Birthday", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AuthField(year, { year = it }, "Year", KeyboardType.Number, Modifier.weight(1.2f))
            AuthField(month, { month = it }, "Mo", KeyboardType.Number, Modifier.weight(0.8f))
            AuthField(day, { day = it }, "Day", KeyboardType.Number, Modifier.weight(0.8f))
        }

        Text("Gender", style = MaterialTheme.typography.labelLarge)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            val opts = listOf(SB_Gender.MALE, SB_Gender.FEMALE, SB_Gender.UNDISCLOSED)
            opts.forEachIndexed { i, g ->
                SegmentedButton(
                    selected = gender == g,
                    onClick = { gender = g },
                    shape = SegmentedButtonDefaults.itemShape(i, opts.size),
                ) { Text(g.name.lowercase().replaceFirstChar { it.uppercase() }) }
            }
        }

        AuthField(heightCm, { heightCm = it }, "Height (cm)", KeyboardType.Decimal)
        AuthField(weightKg, { weightKg = it }, "Weight (kg)", KeyboardType.Decimal)

        Button(
            onClick = {
                submitting = true
                message = null
                scope.launch {
                    try {
                        val request = SB_CreateAccountRequest(
                            username = username.trim(),
                            email = email.trim(),
                            password = password,
                            birthdayYear = year.toIntOrNull(),
                            birthdayMonth = month.toIntOrNull(),
                            birthdayDay = day.toIntOrNull(),
                            gender = gender,
                            heightCm = heightCm.toFloatOrNull() ?: 0f,
                            weight = weightKg.toFloatOrNull() ?: 0f,
                            imperialUnits = false,
                        )
                        message = when (val outcome = SensorBioSDK.createAccount(request)) {
                            is SB_CreateAccountOutcome.Success -> "Account created — signed in as ${outcome.session.username}"
                            SB_CreateAccountOutcome.InvalidBirthday -> "Invalid birthday"
                            SB_CreateAccountOutcome.InvalidEmail -> "Invalid email"
                            SB_CreateAccountOutcome.InvalidHeight -> "Invalid height"
                            SB_CreateAccountOutcome.InvalidWeight -> "Invalid weight"
                            SB_CreateAccountOutcome.InvalidAccessCode -> "Invalid access code"
                            SB_CreateAccountOutcome.AccessCodeAlreadyInUse -> "Access code already in use"
                            SB_CreateAccountOutcome.DeviceSerialNumberRequired -> "Device serial number required"
                            SB_CreateAccountOutcome.DeviceSerialNumberMismatch -> "Device serial number mismatch"
                            is SB_CreateAccountOutcome.Other -> outcome.message
                        }
                    } catch (t: Throwable) {
                        message = "Error: ${t.message ?: t::class.simpleName}"
                    } finally {
                        submitting = false
                    }
                }
            },
            enabled = canSubmit,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (submitting) CircularProgressIndicator(Modifier.height(20.dp)) else Text("Create Account")
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
