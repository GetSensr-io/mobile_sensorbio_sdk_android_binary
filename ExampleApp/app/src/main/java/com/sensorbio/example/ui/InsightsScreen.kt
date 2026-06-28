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
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sensorbio.sensorbiosdk.SensorBioSDK
import com.sensorbio.sensorbiosdk.datatypes.SB_PopulationAgeGroup
import com.sensorbio.sensorbiosdk.datatypes.SB_PopulationGender
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

private data class HistRow(val range: String, val value: String, val isUser: Boolean)
private data class InsightSection(val name: String, val insightText: String, val rows: List<HistRow>)

private sealed interface InsightsUi {
    object Loading : InsightsUi
    data class Error(val message: String) : InsightsUi
    data class Loaded(val ageGroups: List<SB_PopulationAgeGroup>, val sections: List<InsightSection>) : InsightsUi
}

private fun fmt(v: Float): String = if (v % 1f == 0f) v.toInt().toString() else "%.1f".format(v)

/** The population filter list encodes "all ages" as a (-1, -1) group. */
private fun ageLabel(g: SB_PopulationAgeGroup): String =
    if (g.ageStart < 0) "All Ages" else "${g.ageStart}–${g.ageEnd}"

@Composable
fun InsightsScreen() {
    var gender by remember { mutableStateOf(SB_PopulationGender.ALL) }
    var ageStart by remember { mutableStateOf(-1) } // -1 = use the first age group
    var ui by remember { mutableStateOf<InsightsUi>(InsightsUi.Loading) }

    LaunchedEffect(gender, ageStart) {
        ui = InsightsUi.Loading
        ui = try {
            val filters = SensorBioSDK.fetchPopulationInsightsMetricList()
            val age = filters.ageGroups.firstOrNull { it.ageStart == ageStart } ?: filters.ageGroups.first()
            // Each metric (HRV, HR, sleep, …) is its own population comparison — fetch them in parallel.
            val sections = coroutineScope {
                filters.metrics.map { m ->
                    async {
                        runCatching {
                            val h = SensorBioSDK.fetchPopulationInsights(age.ageStart, age.ageEnd, gender, m.metricType).histogram
                            InsightSection(
                                name = m.metricName.ifBlank { m.metricType.name },
                                insightText = h.insightText,
                                rows = h.histogramData.mapIndexed { i, b ->
                                    HistRow("${fmt(b.xStartValue)}–${fmt(b.xEndValue)}", fmt(b.yValue), i == h.userXPosition)
                                },
                            )
                        }.getOrNull()
                    }
                }.mapNotNull { it.await() }
            }
            InsightsUi.Loaded(filters.ageGroups, sections)
        } catch (t: Throwable) {
            InsightsUi.Error(t.message ?: t::class.simpleName ?: "Unknown error")
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Insights", style = MaterialTheme.typography.headlineSmall)
        Text(
            "How you compare against the population. Your bin is highlighted.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Filters: gender + age group
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            val genders = listOf(SB_PopulationGender.ALL, SB_PopulationGender.MALE, SB_PopulationGender.FEMALE)
            genders.forEachIndexed { i, gOpt ->
                SegmentedButton(
                    selected = gender == gOpt,
                    onClick = { gender = gOpt },
                    shape = SegmentedButtonDefaults.itemShape(i, genders.size),
                ) { Text(gOpt.name.lowercase().replaceFirstChar { it.uppercase() }) }
            }
        }

        when (val s = ui) {
            is InsightsUi.Loading -> Row(
                Modifier.fillMaxWidth().padding(top = 24.dp), horizontalArrangement = Arrangement.Center,
            ) { CircularProgressIndicator() }

            is InsightsUi.Error -> Text("Couldn't load insights: ${s.message}", color = MaterialTheme.colorScheme.error)

            is InsightsUi.Loaded -> {
                AgePicker(
                    ageGroups = s.ageGroups,
                    selectedStart = ageStart,
                    onSelect = { ageStart = it },
                )
                if (s.sections.isEmpty()) {
                    Text("No population data available.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    s.sections.forEach { MetricHistogramCard(it) }
                }
            }
        }
    }
}

@Composable
private fun AgePicker(ageGroups: List<SB_PopulationAgeGroup>, selectedStart: Int, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val current = ageGroups.firstOrNull { it.ageStart == selectedStart } ?: ageGroups.firstOrNull()
    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Text("Age: " + (current?.let { ageLabel(it) } ?: "—"))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ageGroups.forEach { g ->
                DropdownMenuItem(
                    text = { Text(ageLabel(g)) },
                    onClick = { onSelect(g.ageStart); expanded = false },
                )
            }
        }
    }
}

@Composable
private fun MetricHistogramCard(section: InsightSection) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(section.name, style = MaterialTheme.typography.titleMedium)
            if (section.insightText.isNotBlank()) {
                Text(section.insightText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (section.rows.isEmpty()) {
                Text("No data", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                section.rows.forEach { row -> HistogramRow(row) }
            }
        }
    }
}

@Composable
private fun HistogramRow(row: HistRow) {
    val bg = if (row.isUser) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else androidx.compose.ui.graphics.Color.Transparent
    val fg = if (row.isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    val weight = if (row.isUser) FontWeight.Bold else FontWeight.Normal
    Row(
        Modifier.fillMaxWidth().background(bg).padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(row.range, color = fg, fontWeight = weight, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(row.value, color = fg, fontWeight = weight)
            if (row.isUser) Text("  ● you", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
        }
    }
}
