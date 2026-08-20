package com.sensorbio.example.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sensorbio.sensorbiosdk.SensorBioSDK
import com.sensorbio.sensorbiosdk.datatypes.SB_PairingFailure
import com.sensorbio.sensorbiosdk.datatypes.SB_PairingState

/**
 * Pairing is ONE SDK-owned transaction, so this screen is a *renderer* over
 * [SensorBioSDK.pairingState] plus three calls: [SensorBioSDK.beginPairing] to open it,
 * [SensorBioSDK.selectDevice] to pick a band, [SensorBioSDK.endPairing] to cancel a running
 * transaction or dismiss a terminal one.
 *
 * There is deliberately no host-side sequencing here — no scan/stop bookkeeping, no connect step, no
 * LED or haptic choreography, no button-tap listening, no timeouts, and no cleanup on failure. The SDK
 * does all of it and reports progress on the flow; a transaction that ends any way other than
 * `Paired` leaves no trace, so there is nothing for this screen to undo.
 *
 * The one genuinely host-side job is the runtime **Bluetooth permission** grant: the SDK scans, it
 * never asks for the grant.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairDeviceScreen(onClose: () -> Unit) {
    val state by SensorBioSDK.pairingState.collectAsStateWithLifecycle()
    var permissionError by remember { mutableStateOf<String?>(null) }

    // The SDK assumes BLE permissions are granted — the host requests them, right before pairing.
    // Location is required for scan results because the SDK's BLUETOOTH_SCAN isn't neverForLocation.
    val context = LocalContext.current
    val blePerms = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION,
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result.values.all { it }) {
            SensorBioSDK.beginPairing()
        } else {
            permissionError = "Bluetooth permission is required to pair."
        }
    }

    fun begin() {
        permissionError = null
        val granted = blePerms.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        // beginPairing() re-issues the scan itself once the adapter is up, so it is safe to call with
        // a freshly-granted permission and Bluetooth still coming online.
        if (granted) SensorBioSDK.beginPairing() else permLauncher.launch(blePerms)
    }

    fun close() {
        SensorBioSDK.endPairing() // idempotent + safe from any state, including "none open"
        onClose()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pair a device") },
                navigationIcon = {
                    IconButton(onClick = { close() }) {
                        Icon(Icons.Filled.Close, contentDescription = "Close")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (val current = state) {
                // No transaction open — the start screen.
                null -> {
                    permissionError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Text(
                        "Put the wearable in pairing range and start pairing. Pick it from the list; " +
                            "when it blinks and buzzes, press its button to confirm.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = { begin() }, modifier = Modifier.fillMaxWidth()) {
                        Text("Start pairing")
                    }
                }

                is SB_PairingState.Scanning -> {
                    Text("Searching for devices…", style = MaterialTheme.typography.titleMedium)
                    if (current.devices.isEmpty()) {
                        CircularProgressIndicator()
                    } else {
                        Text("Select a device to pair.", style = MaterialTheme.typography.bodyMedium)
                    }
                    // Cumulative + de-duplicated by the SDK; just render it.
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(current.devices) { device ->
                            Card(
                                Modifier.fillMaxWidth().clickable {
                                    SensorBioSDK.selectDevice(device.macAddress)
                                },
                            ) {
                                Column(Modifier.padding(12.dp)) {
                                    Text(
                                        device.name ?: "(unnamed ${device.typeName})",
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                    Text(
                                        device.macAddress,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                }
                            }
                        }
                    }
                }

                is SB_PairingState.Connecting -> {
                    Text("Connecting…", style = MaterialTheme.typography.titleMedium)
                    Text(current.device.name ?: current.device.macAddress)
                    CircularProgressIndicator()
                }

                is SB_PairingState.AwaitingConfirmation -> {
                    Text("Press the button", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${current.device.name ?: "The device"} is blinking and buzzing — press its " +
                            "button to confirm the pair.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    CircularProgressIndicator()
                }

                // Terminal — the transaction stays open until endPairing(), which `close()` calls.
                is SB_PairingState.Paired -> {
                    Text("All set", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${current.device.name} (${current.device.type}) is paired and ready.",
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Button(onClick = { close() }, modifier = Modifier.fillMaxWidth()) { Text("Done") }
                }

                is SB_PairingState.Failed -> {
                    Text("Couldn't pair", style = MaterialTheme.typography.titleMedium)
                    Text(current.reason.message(), color = MaterialTheme.colorScheme.error)
                    Button(onClick = { begin() }, modifier = Modifier.fillMaxWidth()) {
                        Text("Try again")
                    }
                }
            }
        }
    }
}

/** Failure reasons are typed, so the host owns the wording. */
private fun SB_PairingFailure.message(): String = when (this) {
    SB_PairingFailure.ScanTimeout ->
        "No device found. Make sure it's charged, awake, and close to the phone."
    SB_PairingFailure.ConnectTimeout ->
        "Couldn't connect to the device. Move closer and try again."
    SB_PairingFailure.ConnectionLost ->
        "The device disconnected before pairing finished."
    SB_PairingFailure.NotConfirmed ->
        "No button press was registered in time. Try again and press the device's button."
    SB_PairingFailure.DeviceUnavailable ->
        "That device is no longer available. Search again."
}
