package com.sensorbio.example.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sensorbio.sensorbiosdk.SensorBioSDK
import java.time.Instant

private enum class Tab { DASHBOARD, INSIGHTS, PROFILE }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScaffold(usernameOrEmail: String) {
    var tab by remember { mutableStateOf(Tab.DASHBOARD) }
    var pairing by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<MetricKind?>(null) }
    // Hoisted so it survives dashboard ↔ detail navigation (the date picker "stays put").
    var selectedDate by remember { mutableStateOf(Instant.now()) }

    if (pairing) {
        PairDeviceScreen(onClose = { pairing = false })
        return
    }
    detail?.let { kind ->
        MetricDetailScreen(
            kind = kind,
            date = selectedDate,
            onDateChange = { selectedDate = it },
            onBack = { detail = null },
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SensorBio Example") },
                actions = { ConnectionIndicator() },
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == Tab.DASHBOARD,
                    onClick = { tab = Tab.DASHBOARD },
                    icon = { Icon(Icons.Filled.GridView, contentDescription = null) },
                    label = { Text("Dashboard") },
                )
                NavigationBarItem(
                    selected = tab == Tab.INSIGHTS,
                    onClick = { tab = Tab.INSIGHTS },
                    icon = { Icon(Icons.Filled.Lightbulb, contentDescription = null) },
                    label = { Text("Insights") },
                )
                NavigationBarItem(
                    selected = tab == Tab.PROFILE,
                    onClick = { tab = Tab.PROFILE },
                    icon = { Icon(Icons.Filled.Person, contentDescription = null) },
                    label = { Text("Profile") },
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                Tab.DASHBOARD -> DashboardScreen(
                    date = selectedDate,
                    onDateChange = { selectedDate = it },
                    onPair = { pairing = true },
                    onOpenDetail = { detail = it },
                )
                Tab.INSIGHTS -> InsightsScreen()
                Tab.PROFILE -> ProfileScreen(usernameOrEmail = usernameOrEmail)
            }
        }
    }
}

/** Compact "X% • Connected" / "Not connected" indicator, shown when a device is paired. */
@Composable
private fun ConnectionIndicator() {
    val haveDevice by SensorBioSDK.haveDevice.collectAsStateWithLifecycle()
    val connected by SensorBioSDK.connected.collectAsStateWithLifecycle()
    val battery by SensorBioSDK.batteryLevel.collectAsStateWithLifecycle()

    if (!haveDevice) return

    Row(modifier = Modifier.padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (connected) {
            Icon(Icons.Filled.BatteryFull, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(
                text = battery?.let { " $it%" } ?: " Connected",
                style = MaterialTheme.typography.labelMedium,
            )
        } else {
            Icon(Icons.Filled.BluetoothDisabled, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
            Text(" Offline", style = MaterialTheme.typography.labelMedium)
        }
    }
}
