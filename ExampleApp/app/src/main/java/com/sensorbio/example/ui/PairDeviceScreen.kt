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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.sensorbio.sensorbiosdk.SensorBioSDK
import com.sensorbio.sensorbiosdk.datatypes.SB_DiscoveredDevice
import kotlinx.coroutines.launch

private enum class Phase { IDLE, SCANNING, CONNECTING, CONFIRMING, ALL_SET, ERROR }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairDeviceScreen(onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var phase by remember { mutableStateOf(Phase.IDLE) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    val devices = remember { mutableStateListOf<SB_DiscoveredDevice>() }
    var selected by remember { mutableStateOf<SB_DiscoveredDevice?>(null) }
    var tapBaseline by remember { mutableStateOf<Int?>(null) }

    // Event collectors — live for the screen's lifetime; act based on the current phase.
    LaunchedEffect(Unit) {
        scope.launch {
            SensorBioSDK.deviceDiscovered.collect { device ->
                if (phase == Phase.SCANNING && devices.none { it.macAddress == device.macAddress }) {
                    devices.add(device)
                }
            }
        }
        scope.launch {
            SensorBioSDK.pairingConnection.collect {
                if (phase == Phase.CONNECTING) {
                    SensorBioSDK.stopScan()
                    phase = Phase.CONFIRMING
                    tapBaseline = SensorBioSDK.buttonTaps.value
                    SensorBioSDK.setAskForDeviceResponse(true)
                    scope.launch { runCatching { SensorBioSDK.userLED(blue = true, blink = true, seconds = 5) } }
                }
            }
        }
        scope.launch {
            SensorBioSDK.buttonTaps.collect { count ->
                if (phase == Phase.CONFIRMING && count != null && count != tapBaseline) {
                    SensorBioSDK.setAskForDeviceResponse(false)
                    selected?.let { SensorBioSDK.addPairedDevice(it) }
                    phase = Phase.ALL_SET
                }
            }
        }
        scope.launch {
            SensorBioSDK.deviceDisconnected.collect {
                if (phase == Phase.CONNECTING || phase == Phase.CONFIRMING) {
                    errorMsg = "Device disconnected before pairing finished."
                    phase = Phase.ERROR
                }
            }
        }
    }

    fun startScan() {
        devices.clear()
        selected = null
        errorMsg = null
        phase = Phase.SCANNING
        SensorBioSDK.startScan()
    }

    fun connect(device: SB_DiscoveredDevice) {
        selected = device
        phase = Phase.CONNECTING
        SensorBioSDK.connect(device.macAddress, pairing = true)
    }

    fun cancel() {
        SensorBioSDK.stopScan()
        runCatching { SensorBioSDK.setAskForDeviceResponse(false) }
        devices.clear()
        selected = null
        phase = Phase.IDLE
    }

    // The SDK assumes BLE permissions are granted — the host requests them. Do it right before scanning.
    val context = LocalContext.current
    val blePerms = remember {
        // Location is required for scan results because the SDK's BLUETOOTH_SCAN isn't neverForLocation.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION,
            )
        else
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    fun hasBlePerms() = blePerms.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) startScan() else { errorMsg = "Bluetooth permission is required to scan." ; phase = Phase.ERROR }
    }
    fun requestScan() {
        if (hasBlePerms()) startScan() else permLauncher.launch(blePerms)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pair a device") },
                navigationIcon = {
                    IconButton(onClick = { cancel(); onClose() }) {
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
            Text("Status: ${phase.name}", style = MaterialTheme.typography.titleMedium)

            when (phase) {
                Phase.IDLE, Phase.ERROR -> {
                    errorMsg?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Text(
                        "Put the wearable in pairing range, then scan. Select it to connect; " +
                            "when it's connected, tap the button on the device to confirm.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = { requestScan() }, modifier = Modifier.fillMaxWidth()) { Text("Scan") }
                }

                Phase.SCANNING -> {
                    Text("Scanning… select a device to connect.", style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = { SensorBioSDK.stopScan(); phase = Phase.IDLE }) { Text("Stop") }
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(devices) { device ->
                            Card(
                                Modifier.fillMaxWidth().clickable { connect(device) },
                            ) {
                                Column(Modifier.padding(12.dp)) {
                                    Text(device.name ?: "(unnamed)", style = MaterialTheme.typography.titleSmall)
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

                Phase.CONNECTING ->
                    Text("Connecting to ${selected?.name ?: selected?.macAddress}…")

                Phase.CONFIRMING ->
                    Text("Connected. Tap the button on the device to confirm pairing (LED is blinking).")

                Phase.ALL_SET -> {
                    Text("All set — ${selected?.name ?: "device"} paired.", color = MaterialTheme.colorScheme.primary)
                    Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Done") }
                }
            }
        }
    }
}
