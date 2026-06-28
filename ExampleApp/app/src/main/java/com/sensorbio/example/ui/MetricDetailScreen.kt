package com.sensorbio.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.Instant

private sealed interface DetailState {
    object Loading : DetailState
    data class Error(val message: String) : DetailState
    data class Loaded(val data: MetricData) : DetailState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MetricDetailScreen(
    kind: MetricKind,
    date: Instant,
    onDateChange: (Instant) -> Unit,
    onBack: () -> Unit,
) {
    var grain by remember { mutableStateOf(Grain.DAY) }
    var state by remember { mutableStateOf<DetailState>(DetailState.Loading) }

    LaunchedEffect(kind, date, grain) {
        state = DetailState.Loading
        state = try {
            DetailState.Loaded(loadMetric(kind, date, grain))
        } catch (t: Throwable) {
            DetailState.Error(t.message ?: t::class.simpleName ?: "Unknown error")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(kind.title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DateBar(date = date, onDateChange = onDateChange)

            // D / W / M / Y
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                Grain.entries.forEachIndexed { i, g ->
                    SegmentedButton(
                        selected = grain == g,
                        onClick = { grain = g },
                        shape = SegmentedButtonDefaults.itemShape(i, Grain.entries.size),
                    ) { Text(g.label) }
                }
            }

            when (val s = state) {
                is DetailState.Loading -> Row(
                    Modifier.fillMaxWidth().padding(top = 24.dp),
                    horizontalArrangement = Arrangement.Center,
                ) { CircularProgressIndicator() }

                is DetailState.Error -> Text(
                    "Couldn't load ${kind.title.lowercase()}: ${s.message}",
                    color = MaterialTheme.colorScheme.error,
                )

                is DetailState.Loaded -> {
                    val d = s.data
                    Text(d.headline, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold)

                    if (d.summary.isNotEmpty()) {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp)) {
                                d.summary.forEachIndexed { i, (label, value) ->
                                    if (i > 0) HorizontalDivider(Modifier.padding(vertical = 8.dp))
                                    KeyValueRow(label, value)
                                }
                            }
                        }
                    }

                    if (d.sections.isEmpty()) {
                        Text("No data points for this date / range.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        d.sections.forEach { section -> SectionCard(section) }
                    }

                    Text(
                        "Fetched live from SensorBioSDK.fetch… — see SDK_INTERFACE.md §5.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionCard(section: MetricSection) {
    // Title sits ABOVE the card (a heading for the table), not inside it.
    Text(
        section.name,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
    )
    section.subtitle?.let {
        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (section.points.isEmpty()) {
        Text("No data points.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            section.points.forEachIndexed { i, point ->
                if (i > 0) HorizontalDivider(Modifier.padding(vertical = 6.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(
                            point.label,
                            fontFamily = if (section.monospaceLabels) FontFamily.Monospace else FontFamily.Default,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        point.tag?.let { TagChip(it) }
                    }
                    Text(point.value, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
private fun TagChip(tag: String) {
    val isSleep = tag.equals("Sleep", ignoreCase = true)
    val container = if (isSleep) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val content = if (isSleep) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        Modifier
            .padding(start = 8.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(container)
            .padding(horizontal = 6.dp, vertical = 1.dp),
    ) {
        Text(tag, style = MaterialTheme.typography.labelSmall, color = content)
    }
}

@Composable
private fun KeyValueRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Medium)
    }
}
