package com.sensorbio.example.ui

import com.sensorbio.sensorbiosdk.SensorBioSDK
import com.sensorbio.sensorbiosdk.datatypes.SB_SleepMetricValue
import com.sensorbio.sensorbiosdk.datatypes.SB_StepMetric
import com.sensorbio.sensorbiosdk.datatypes.SB_StepMetricType
import com.sensorbio.sensorbiosdk.datatypes.SB_ValueUnitWrapper
import com.sensorbio.sensorbiosdk.datatypes.SB_ViewGranularity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/** The seven dashboard metrics, each with a tappable detail view. */
enum class MetricKind(val title: String) {
    HEART_RATE("Heart Rate"),
    HRV("Heart Rate Variability"),
    RESPIRATORY_RATE("Respiratory Rate"),
    STEPS("Steps"),
    CALORIES("Calories"),
    ACTIVITY("Activity"),
    RECOVERY("Recovery"),
    SLEEP("Sleep"),
}

/** D / W / M / Y selector backed by the SDK's granularity enum. */
enum class Grain(val label: String, val sdk: SB_ViewGranularity) {
    DAY("Day", SB_ViewGranularity.DAY),
    WEEK("Week", SB_ViewGranularity.WEEK),
    MONTH("Month", SB_ViewGranularity.MONTH),
    YEAR("Year", SB_ViewGranularity.YEAR),
}

/** One row in a section table: a label (time/date/name), a value, and an optional tag (e.g. Sleep/Awake). */
data class DataPoint(val label: String, val value: String, val tag: String? = null)

/** A titled block of data points (e.g. one SDK metric within a steps/calories response).
 *  `monospaceLabels` is for time/date point lists; word labels (score factors) read better proportional. */
data class MetricSection(
    val name: String,
    val subtitle: String?,
    val points: List<DataPoint>,
    val monospaceLabels: Boolean = true,
)

/** A flat, UI-agnostic view of one metric for one date/granularity. */
data class MetricData(
    val headline: String,
    val summary: List<Pair<String, String>>,
    val sections: List<MetricSection>,
)

// SDK time-value points pack `timestamp` (ms) as a *local epoch* — the device's local-time digits
// stored as if UTC. Render the wall-clock time by reading those digits back in UTC (iOS-parity).
private val hmUtc = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.of("UTC"))

private fun timeLabel(ms: Long): String =
    runCatching { hmUtc.format(Instant.ofEpochMilli(ms)) }.getOrDefault(ms.toString())

// `SB_DateValuePoint.date` is packed YYYYMMDD (e.g. 20260515).
private fun dateLabel(packed: Int, grain: Grain): String {
    if (packed in 19000101..29991231) {
        return runCatching {
            val ld = LocalDate.of(packed / 10000, (packed / 100) % 100, packed % 100)
            ld.format(DateTimeFormatter.ofPattern(if (grain == Grain.YEAR) "MMM yyyy" else "MMM d"))
        }.getOrDefault(packed.toString())
    }
    return packed.toString()
}

private fun unitLabel(value: Float, unit: String) = "${value.toInt()} $unit".trim()

/** Merge a metric's separate sleep + daytime point lists into one chronological, sleep/wake-tagged table. */
private fun mergedReadings(
    sleep: List<Pair<Long, Float>>,
    daytime: List<Pair<Long, Float>>,
    unit: String,
): List<DataPoint> =
    (sleep.map { it to "Sleep" } + daytime.map { it to "Awake" })
        .sortedBy { it.first.first }
        .map { (p, tag) -> DataPoint(timeLabel(p.first), "${p.second.toInt()} $unit", tag = tag) }

/** Each SB_StepMetric → a section: avg subtitle + per-bucket points (time of day for Day, date for ranges). */
private fun stepMetricSection(m: SB_StepMetric, grain: Grain): MetricSection = MetricSection(
    name = m.name.ifBlank { m.barChartTitle.ifBlank { "Metric" } },
    subtitle = "Average ${unitLabel(m.avgValue, m.unit)}",
    points = if (grain == Grain.DAY) {
        m.timeDatapoints.sortedBy { it.timestamp }.map { DataPoint(timeLabel(it.timestamp), unitLabel(it.value, m.unit)) }
    } else {
        m.datapoints.sortedBy { it.date }.map { DataPoint(dateLabel(it.date, grain), unitLabel(it.value, m.unit)) }
    },
)

/** One call per (metric, date, granularity) — mirrors how a real integrator reads the SDK. */
suspend fun loadMetric(kind: MetricKind, date: Instant, grain: Grain): MetricData = when (kind) {
    MetricKind.HEART_RATE ->
        if (grain == Grain.DAY) {
            val g = SensorBioSDK.fetchDailyHR(date).graph ?: return noData()
            MetricData(
                "${g.rawAvg.toInt()} bpm",
                listOf("Resting" to "${g.restingBpm.toInt()} bpm", "Average" to "${g.rawAvg.toInt()} bpm",
                    "Lowest" to "${g.rawLowest.toInt()} bpm", "Highest" to "${g.rawHighest.toInt()} bpm",
                    "Baseline" to "${g.rawBaseline.toInt()} bpm"),
                listOf(MetricSection("Readings", null,
                    g.heartRateTimeseriesPoints.sortedBy { it.timestamp }.map {
                        // Each reading is tagged AWAKE / SLEEP (+ outlier/abnormal variants) by the SDK.
                        DataPoint(timeLabel(it.timestamp), "${it.value.toInt()} bpm",
                            tag = if (it.valueType.name.startsWith("SLEEP")) "Sleep" else "Awake")
                    })),
            )
        } else {
            val g = SensorBioSDK.fetchRangeHR(date, grain.sdk).graph ?: return noData()
            MetricData(
                "${g.avgBpm.toInt()} bpm",
                listOf("Average" to "${g.avgBpm.toInt()} bpm", "Lowest" to "${g.lowest.toInt()} bpm",
                    "Highest" to "${g.highest.toInt()} bpm", "Baseline" to "${g.baseline.toInt()} bpm"),
                listOf(MetricSection("BPM", null,
                    g.bpmPoints.sortedBy { it.date }.map { DataPoint(dateLabel(it.date, grain), "${it.value.toInt()} bpm") })),
            )
        }

    MetricKind.HRV ->
        if (grain == Grain.DAY) {
            val g = SensorBioSDK.fetchDailyHRV(date).graph ?: return noData()
            MetricData(
                "${g.rMssd.toInt()} ms",
                listOf("rMSSD" to "${g.rMssd.toInt()} ms", "Average" to "${g.rawAvg.toInt()} ms",
                    "Lowest" to "${g.rawLowest.toInt()} ms", "Highest" to "${g.rawHighest.toInt()} ms",
                    "Baseline" to "${g.rawBaseline.toInt()} ms"),
                listOf(MetricSection("Readings", null, mergedReadings(
                    g.rawSleepHrvPoints.map { it.timestamp to it.value },
                    g.rawDatetimeHrvPoints.map { it.timestamp to it.value },
                    "ms"))),
            )
        } else {
            val g = SensorBioSDK.fetchRangeHRV(date, grain.sdk).graph ?: return noData()
            MetricData(
                "${g.rMssd.toInt()} ms",
                listOf("rMSSD" to "${g.rMssd.toInt()} ms", "Average" to "${g.avg.toInt()} ms",
                    "Lowest" to "${g.lowest.toInt()} ms", "Highest" to "${g.highest.toInt()} ms",
                    "Baseline" to "${g.baseline.toInt()} ms"),
                listOf(MetricSection("HRV index", null,
                    g.hrvIndexPoints.sortedBy { it.date }.map { DataPoint(dateLabel(it.date, grain), "${it.value.toInt()} ms") })),
            )
        }

    MetricKind.RESPIRATORY_RATE ->
        if (grain == Grain.DAY) {
            val g = SensorBioSDK.fetchDailyRR(date).graph ?: return noData()
            MetricData(
                "${g.brpm.toInt()} brpm",
                listOf("Resting" to "${g.brpm.toInt()} brpm", "Average" to "${g.rawAvg.toInt()} brpm",
                    "Lowest" to "${g.rawLowest.toInt()} brpm", "Highest" to "${g.rawHighest.toInt()} brpm",
                    "Baseline" to "${g.rawBaseline.toInt()} brpm"),
                listOf(MetricSection("Readings", null, mergedReadings(
                    g.rawSleepPoints.map { it.timestamp to it.value },
                    g.rawDatetimePoints.map { it.timestamp to it.value },
                    "brpm"))),
            )
        } else {
            val g = SensorBioSDK.fetchRangeRR(date, grain.sdk).graph ?: return noData()
            MetricData(
                "${g.avgBrpm.toInt()} brpm",
                listOf("Average" to "${g.avgBrpm.toInt()} brpm", "Lowest" to "${g.lowest.toInt()} brpm",
                    "Highest" to "${g.highest.toInt()} brpm", "Baseline" to "${g.baseline.toInt()} brpm"),
                listOf(MetricSection("BRPM", null,
                    g.brpmPoints.sortedBy { it.date }.map { DataPoint(dateLabel(it.date, grain), "${it.value.toInt()} brpm") })),
            )
        }

    MetricKind.STEPS -> {
        val g = SensorBioSDK.fetchSteps(date, grain.sdk).graph ?: return noData()
        // The steps response also carries calories/distance/duration metrics — show only the steps one here.
        val stepsMetrics = g.metrics.filter { it.metricType == SB_StepMetricType.STEPS }.ifEmpty { g.metrics }
        MetricData(
            g.progressChartDisplayedValue.ifBlank { "${g.progressChartValue}" },
            listOf("Steps" to "${g.progressChartValue}", "Summary" to g.progressChartDescriptionValue.ifBlank { "—" }),
            stepsMetrics.map { stepMetricSection(it, grain) },
        )
    }

    MetricKind.CALORIES -> {
        val g = SensorBioSDK.fetchCalories(date, grain.sdk).graph ?: return noData()
        MetricData(
            g.progressChartDisplayedValue.ifBlank { "${g.progressChartValue}" },
            listOf("Calories" to "${g.progressChartValue}", "Summary" to g.progressChartDescriptionValue.ifBlank { "—" }),
            // SB_CalorieMetric is shaped like SB_StepMetric; reuse the same section builder via its fields.
            g.metrics.map { m ->
                MetricSection(
                    name = m.name.ifBlank { m.barChartTitle.ifBlank { "Metric" } },
                    subtitle = "Average ${unitLabel(m.avgValue, m.unit)}",
                    points = if (grain == Grain.DAY)
                        m.timeDatapoints.sortedBy { it.timestamp }.map { DataPoint(timeLabel(it.timestamp), unitLabel(it.value, m.unit)) }
                    else
                        m.datapoints.sortedBy { it.date }.map { DataPoint(dateLabel(it.date, grain), unitLabel(it.value, m.unit)) },
                )
            },
        )
    }

    MetricKind.ACTIVITY -> {
        val d = SensorBioSDK.fetchDailyActivityDetail(date, grain.sdk)
        val score = d.activityScore
        MetricData(
            score?.let { "Score ${it.score.toInt()}" } ?: "—",
            buildList {
                score?.description?.let { add("About" to it) }
                score?.let { add("vs baseline" to "%.0f".format(it.diffVsBaseline)) }
            },
            d.metrics.map { stepMetricSection(it, grain) },
        )
    }

    MetricKind.RECOVERY ->
        if (grain == Grain.DAY) {
            val g = SensorBioSDK.fetchDailyRecovery(date).graph ?: return noData()
            val score = g.goalItem?.item?.value
            MetricData(
                score?.let { "Score ${it.toInt()}" } ?: "—",
                listOf("Resting HR" to "${g.restingHr.toInt()} bpm", "Sleep" to secsToHm(g.sleepTimeSeconds.toInt()),
                    "Variation" to "%.0f%%".format(g.variationPercentage)),
                listOf(MetricSection("Score factors", null,
                    // factor value is a 0–1 fraction → render as a percentage
                    g.scoreFactors.map { DataPoint(it.title.ifBlank { "Factor" }, "%.0f%%".format(it.value * 100)) },
                    monospaceLabels = false)),
            )
        } else {
            val g = SensorBioSDK.fetchRangeRecovery(date, grain.sdk).graph ?: return noData()
            val score = g.goalItem?.item?.value
            MetricData(
                score?.let { "Score ${it.toInt()}" } ?: "—",
                listOf("Resting HR" to "${g.restingHr.toInt()} bpm", "Sleep" to secsToHm(g.sleepTimeSeconds.toInt()),
                    "Variation" to "%.0f%%".format(g.variationPercentage)),
                listOf(MetricSection("Recovery score", null,
                    (g.recoveryScoreSection?.scorePoints ?: emptyList()).sortedBy { it.date }
                        .map { DataPoint(dateLabel(it.date, grain), it.value.toInt().toString()) })),
            )
        }

    MetricKind.SLEEP ->
        if (grain == Grain.DAY) {
            // The daily detail keys off a specific night — pick the latest session for the date.
            val endTs = SensorBioSDK.fetchSleepSessions(date).maxByOrNull { it.endTs }?.endTs ?: return noData()
            val d = SensorBioSDK.fetchSleepDetail(date, endTs)
            val sections = buildList {
                add(table("Summary", listOf(
                    DataPoint("Score", "${d.sleepScore.score}"),
                    DataPoint("Sleep time", secsToHm(d.sleepTimeSec)),
                    DataPoint("Resting HR", "${d.restingHr.toInt()} bpm"),
                    DataPoint("Resting HRV", "${d.restingHrv.toInt()} ms"),
                )))
                add(table("Stages", listOf(
                    DataPoint("Awake", "${d.stages.awakePercentage}%"),
                    DataPoint("Light", "${d.stages.lightPercentage}%"),
                    DataPoint("Deep", "${d.stages.deepPercentage}%"),
                    DataPoint("REM", "${d.stages.remPercentage}%"),
                )))
                if (d.metrics.isNotEmpty()) add(table("Metrics",
                    d.metrics.map { DataPoint(it.name.ifBlank { "Metric" }, formatSleepMetric(it.value)) }))
                if (d.scoreFactors.isNotEmpty()) add(table("Contributing factors",
                    d.scoreFactors.map { DataPoint(it.title.ifBlank { "Factor" }, it.description) }))
                if (d.scorePenalty.isNotEmpty()) add(table("Penalties",
                    d.scorePenalty.map { DataPoint(it.name.ifBlank { "Penalty" }, it.value) }))
                d.biometrics?.let { b -> add(table("Biometrics (avg)", listOf(
                    DataPoint("Heart rate", "${b.hrGraph.avg.toInt()} bpm"),
                    DataPoint("HRV", "${b.hrvGraph.avg.toInt()} ms"),
                    DataPoint("Resp rate", "${b.respGraph.avg.toInt()} brpm"),
                    DataPoint("SpO₂", "${b.spo2Graph.avg.toInt()}%"),
                ))) }
                val disturbances = buildList {
                    add(DataPoint("Arm movements", "${d.disturbances.armGraph.stages.size}"))
                    add(DataPoint("Leg movements", "${d.disturbances.legGraph.stages.size}"))
                    add(DataPoint("Kicks", "${d.disturbances.kicksGraph.stages.size}"))
                    if (d.bathroomBreakTimestamps.isNotEmpty()) add(DataPoint("Bathroom breaks", "${d.bathroomBreakTimestamps.size}"))
                }
                add(table("Disturbances", disturbances))
                d.bedtimeRecommendation?.takeIf { !it.isGenerating }?.let { r ->
                    add(table("Recommendation", buildList {
                        r.bedtime?.let { add(DataPoint("Bedtime", timeLabel(it.tsMillis))) }
                        r.wakeup?.let { add(DataPoint("Wake up", timeLabel(it.tsMillis))) }
                        add(DataPoint("Target sleep", minsToHm(r.sleepHoursInMins)))
                    }))
                }
                d.sleepAccounting?.takeIf { !it.isGenerating }?.let { a ->
                    add(table("Sleep accounting", listOf(
                        DataPoint("Circadian score", "${a.circadianScore}"),
                        DataPoint("Sleep debt", minsToHm(a.sleepDebtNetMins)),
                        DataPoint("Recommended", minsToHm(a.current.recommendedMins)),
                        DataPoint("Achieved", minsToHm(a.current.achievedMins)),
                    )))
                }
            }
            MetricData("Score ${d.sleepScore.score}", emptyList(), sections)
        } else {
            val a = SensorBioSDK.fetchSleepAggregation(date, grain.sdk)
            val sections = buildList {
                add(table("Summary", listOf(
                    DataPoint("Score", "${a.sleepScore.score}"),
                    DataPoint("Avg score", "${a.sleepScore.avgScore}"),
                    DataPoint("Sleep time", secsToHm(a.sleepTimeSec)),
                    DataPoint("Resting HR", "${a.restingHr.toInt()} bpm"),
                )))
                add(table("Stages", listOf(
                    DataPoint("Awake", "${a.stages.awakePercentage}%"),
                    DataPoint("Light", "${a.stages.lightPercentage}%"),
                    DataPoint("Deep", "${a.stages.deepPercentage}%"),
                    DataPoint("REM", "${a.stages.remPercentage}%"),
                )))
                if (a.metrics.isNotEmpty()) add(table("Metrics",
                    a.metrics.map { DataPoint(it.name.ifBlank { "Metric" }, formatSleepMetric(it.value)) }))
                add(MetricSection(if (grain == Grain.YEAR) "By month" else "By day", null,
                    a.sleepTimePoints.sortedBy { it.date }.map { DataPoint(dateLabel(it.date, grain), secsToHm(it.value.toInt())) }))
            }
            MetricData("Score ${a.sleepScore.score}", emptyList(), sections)
        }
}

/** A key→value table section with word labels (proportional font). */
private fun table(name: String, rows: List<DataPoint>) = MetricSection(name, null, rows, monospaceLabels = false)

private fun secsToHm(seconds: Int): String = "${seconds / 3600}h ${(seconds % 3600) / 60}m"

private fun minsToHm(mins: Int): String {
    val m = abs(mins)
    return "${if (mins < 0) "-" else ""}${m / 60}h ${m % 60}m"
}

private fun formatValueUnit(w: SB_ValueUnitWrapper): String {
    if (w.stringValue.isNotBlank()) return w.stringValue
    if (w.unit.lowercase() in listOf("min", "mins", "minute", "minutes")) return minsToHm(w.value.toInt())
    return "${w.value.toInt()} ${w.unit}".trim()
}

private fun formatSleepMetric(v: SB_SleepMetricValue): String = when (v) {
    is SB_SleepMetricValue.ValueUnit -> formatValueUnit(v.wrapper)
    is SB_SleepMetricValue.TimeTz -> timeLabel(v.wrapper.timestamp)
    else -> "—"
}

private fun noData() = MetricData("—", listOf("Status" to "No data for this date"), emptyList())
