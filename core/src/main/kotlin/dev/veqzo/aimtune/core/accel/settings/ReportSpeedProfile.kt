package dev.veqzo.aimtune.core.accel.settings

import dev.veqzo.aimtune.core.accel.AccelProcessor
import dev.veqzo.aimtune.core.recording.RoundRecording
import kotlin.math.hypot

/**
 * What the stored rounds say about the mouse reports: percentiles of the report speed in inches per second at each round's DPI
 * (the driver's way: counts / DPI over the report interval clamped to `MIN_TIME_MS..MAX_TIME_MS`, `driver.cpp:84-94`) and of the
 * interval between reports in milliseconds (unclamped), plus the share of reports whose interval the driver would clamp.
 * For the info pages; there is nothing to recommend.
 */
data class ReportSpeedSummary(
    val reports: Int,
    val speedPercentiles: Map<Int, Double>,
    val intervalMsPercentiles: Map<Int, Double>,
    val clampedShare: Double,
)

object ReportSpeedProfile {
    const val MIN_REPORTS = 200
    val PERCENTILES = listOf(50, 90, 95, 99)

    /** Uses every round, with or without acceleration (the raw counts are the hand); the first report of a round has no interval and is skipped. Null below [MIN_REPORTS] moving reports. */
    fun summarize(recordings: List<RoundRecording>): ReportSpeedSummary? {
        val speeds = ArrayList<Double>()
        val intervals = ArrayList<Double>()
        var clamped = 0
        for (recording in recordings) {
            val events = recording.mouseEvents
            for (i in 1 until events.size) {
                val intervalMs = (events[i].timeNanos - events[i - 1].timeNanos) / NANOS_PER_MS
                intervals += intervalMs
                if (events[i].dx == 0 && events[i].dy == 0) continue
                val time = intervalMs.coerceIn(AccelProcessor.MIN_TIME_MS, AccelProcessor.MAX_TIME_MS)
                if (time != intervalMs) clamped++
                speeds += hypot(events[i].dx.toDouble(), events[i].dy.toDouble()) / recording.dpi / (time / MS_PER_S)
            }
        }
        if (speeds.size < MIN_REPORTS) return null
        return ReportSpeedSummary(
            reports = speeds.size,
            speedPercentiles = PERCENTILES.associateWith { speeds.percentile(it / 100.0) },
            intervalMsPercentiles = PERCENTILES.associateWith { intervals.percentile(it / 100.0) },
            clampedShare = clamped.toDouble() / speeds.size,
        )
    }

    private const val NANOS_PER_MS = 1_000_000.0
    private const val MS_PER_S = 1000.0
}
