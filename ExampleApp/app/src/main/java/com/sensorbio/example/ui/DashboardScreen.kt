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
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import com.sensorbio.sensorbiosdk.SensorBioSDK
import com.sensorbio.sensorbiosdk.datatypes.SB_Goals
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.time.Instant

// Two groups, matching the iOS sample: the three daily scores, then the
// underlying biometrics. The device is not here at all — it is an account
// concern and lives on Profile with pairing.
private val SUMMARY_ORDER = listOf(MetricKind.ACTIVITY, MetricKind.RECOVERY, MetricKind.SLEEP)
private val METRIC_ORDER = listOf(
    MetricKind.HEART_RATE, MetricKind.HRV, MetricKind.RESPIRATORY_RATE,
    MetricKind.STEPS, MetricKind.CALORIES,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    date: Instant,
    onDateChange: (Instant) -> Unit,
    onOpenDetail: (MetricKind) -> Unit,
) {
    val scope = rememberCoroutineScope()

    var goals by remember { mutableStateOf<SB_Goals?>(null) }
    val headlines = remember { mutableStateMapOf<MetricKind, String>() }
    var refreshing by remember { mutableStateOf(false) }

    suspend fun load() {
        coroutineScope {
            launch { goals = runCatching { SensorBioSDK.fetchGoals() }.getOrNull() }
            (SUMMARY_ORDER + METRIC_ORDER).forEach { kind ->
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
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DateBar(date = date, onDateChange = onDateChange)

            SectionHeader("Summary")
            MetricList(SUMMARY_ORDER, headlines, goals, onOpenDetail)

            SectionHeader("Metrics")
            MetricList(METRIC_ORDER, headlines, goals, onOpenDetail)
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp),
    )
}

/** One grouped block of single-line rows — the Compose analogue of a `List` `Section` on iOS. */
@Composable
private fun MetricList(
    kinds: List<MetricKind>,
    headlines: Map<MetricKind, String>,
    goals: SB_Goals?,
    onOpenDetail: (MetricKind) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column {
            kinds.forEachIndexed { index, kind ->
                val goalSuffix = when (kind) {
                    MetricKind.STEPS -> goals?.let { " / ${it.targetSteps}" }
                    MetricKind.CALORIES -> goals?.let { " / ${it.targetCalories}" }
                    else -> null
                }
                MetricRow(
                    title = kind.title,
                    value = (headlines[kind] ?: "…") + (goalSuffix ?: ""),
                    onClick = { onOpenDetail(kind) },
                )
                if (index < kinds.lastIndex) HorizontalDivider()
            }
        }
    }
}

/** Label left, value right, chevron — `LabeledContent` inside a `NavigationLink` on iOS. */
@Composable
private fun MetricRow(title: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                value,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}
