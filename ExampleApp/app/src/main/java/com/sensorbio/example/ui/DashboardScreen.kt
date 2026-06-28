package com.sensorbio.example.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sensorbio.sensorbiosdk.SensorBioSDK
import com.sensorbio.sensorbiosdk.datatypes.SB_Goals
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.time.Instant

// Activity / Recovery / Sleep scores first, then the biometric + activity-count metrics.
private val DASHBOARD_ORDER = listOf(
    MetricKind.ACTIVITY, MetricKind.RECOVERY, MetricKind.SLEEP,
    MetricKind.HEART_RATE, MetricKind.HRV, MetricKind.RESPIRATORY_RATE,
    MetricKind.STEPS, MetricKind.CALORIES,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    date: Instant,
    onDateChange: (Instant) -> Unit,
    onPair: () -> Unit,
    onOpenDetail: (MetricKind) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val haveDevice by SensorBioSDK.haveDevice.collectAsStateWithLifecycle()
    val connected by SensorBioSDK.connected.collectAsStateWithLifecycle()
    val charging by SensorBioSDK.charging.collectAsStateWithLifecycle()
    val battery by SensorBioSDK.batteryLevel.collectAsStateWithLifecycle()
    val serial by SensorBioSDK.serialNumber.collectAsStateWithLifecycle()

    var goals by remember { mutableStateOf<SB_Goals?>(null) }
    val headlines = remember { mutableStateMapOf<MetricKind, String>() }
    var refreshing by remember { mutableStateOf(false) }

    suspend fun load() {
        coroutineScope {
            launch { goals = runCatching { SensorBioSDK.fetchGoals() }.getOrNull() }
            DASHBOARD_ORDER.forEach { kind ->
                launch { headlines[kind] = runCatching { loadMetric(kind, date, Grain.DAY).headline }.getOrDefault("—") }
            }
        }
    }

    LaunchedEffect(date) { headlines.clear(); load() }

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = { refreshing = true; scope.launch { load(); refreshing = false } },
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DateBar(date = date, onDateChange = onDateChange)

            // --- Device card ---
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val deviceTitle = if (haveDevice && !serial.isNullOrBlank()) "Device — '$serial'" else "Device"
                    Text(deviceTitle, style = MaterialTheme.typography.titleMedium)
                    if (haveDevice) {
                        Text("Connection: ${if (connected) "Connected" else "Offline"}")
                        Text("Battery: ${battery?.let { "$it%" } ?: "—"}")
                        Text("Charging: ${if (charging) "Yes" else "No"}")

                        // Device commands. Blink/reset go over BLE, so they need an active connection.
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                enabled = connected,
                                onClick = { scope.launch { runCatching { SensorBioSDK.userLED(blue = true, blink = true, seconds = 5) } } },
                            ) { Text("Blink LED") }
                            OutlinedButton(enabled = connected, onClick = { SensorBioSDK.reset() }) { Text("Reset") }
                        }
                        TextButton(
                            onClick = { SensorBioSDK.pairedDevice.value?.macAddress?.let { SensorBioSDK.removeDeviceFromPairedDevices(it) } },
                        ) { Text("Unpair", color = MaterialTheme.colorScheme.error) }
                    } else {
                        Text("No device paired.")
                        Button(onClick = onPair) { Text("Pair a device") }
                    }
                }
            }

            // --- Metric score cards (tap for detail / D-W-M-Y / data points) ---
            Text("Metrics", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp))
            DASHBOARD_ORDER.forEach { kind ->
                val goalSuffix = when (kind) {
                    MetricKind.STEPS -> goals?.let { "/ ${it.targetSteps}" }
                    MetricKind.CALORIES -> goals?.let { "/ ${it.targetCalories}" }
                    else -> null
                }
                MetricScoreCard(
                    title = kind.title,
                    value = headlines[kind] ?: "…",
                    goalSuffix = goalSuffix,
                    onClick = { onOpenDetail(kind) },
                )
            }
        }
    }
}

@Composable
private fun MetricScoreCard(title: String, value: String, goalSuffix: String?, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    goalSuffix?.let {
                        Text(
                            "  $it",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 2.dp),
                        )
                    }
                }
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
    }
}
