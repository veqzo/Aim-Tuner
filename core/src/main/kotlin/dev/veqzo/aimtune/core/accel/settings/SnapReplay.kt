package dev.veqzo.aimtune.core.accel.settings

import dev.veqzo.aimtune.core.accel.RawAccelArgs
import dev.veqzo.aimtune.core.accel.RawAccelMode
import dev.veqzo.aimtune.core.accel.RawAccelModifier
import dev.veqzo.aimtune.core.accel.RawAccelProfile
import dev.veqzo.aimtune.core.recording.RoundRecording
import kotlin.math.abs

/** The vertical error of first movements in one scenario with snapping [snap] against none, from [SnapReplay]. */
data class SnapRow(
    val scenarioId: String,
    val snap: Double,
    val n: Int,
    val meanAbsErrorDegrees: Double,
    val meanAbsErrorSnapDegrees: Double,
    /** Relative change of the mean absolute vertical error: -0.2 is 20 % less error with snapping. */
    val relativeChange: Double,
    val low: Double,
    val high: Double,
    val passes: Boolean,
)

/** The recommendation (value in degrees: [PassiveResult.Recommend.low] and `high` are the smallest and largest snap that passed) and the numbers behind it. */
data class SnapOutcome(val result: PassiveResult, val rows: List<SnapRow>)

/**
 * Raw Accel's angle snapping, tested by replay (docs/ALGORITHMS.md, "Passive setting analyses"). Each stored round's reports run
 * through [RawAccelModifier] with an otherwise neutral profile and snap s in [CANDIDATES]; the counts of the original first
 * submovement window are summed again and their vertical error (counts in y against the target's vertical need) compared with
 * the unsnapped one, reach by reach. This is open loop: the player would see and correct a snapped movement differently.
 *
 * Thresholds, fixed before the gate ran: a scenario is used when it has at least [MIN_REACHES] reaches (none: not enough data);
 * a snap passes in a scenario when the mean absolute vertical error falls by at least [MIN_REDUCTION] and the 95 % bootstrap
 * interval of the relative change lies below 0; a snap is recommended only when it passes in every scenario used, and the one
 * with the largest mean reduction is chosen. Otherwise the recommendation is 0.
 */
object SnapReplay {
    val CANDIDATES = listOf(2.0, 5.0, 10.0)
    const val MIN_REACHES = 40
    const val MIN_REDUCTION = 0.10

    fun analyze(recordings: List<RoundRecording>): SnapOutcome {
        val reaches = firstReaches(recordings)
        val byScenario = reaches.groupBy { it.scenarioId }.filterValues { it.size >= MIN_REACHES }
        if (byScenario.isEmpty()) return SnapOutcome(PassiveResult.NotEnoughData(reaches.size, MIN_REACHES), emptyList())
        val rows = CANDIDATES.flatMap { snap -> rowsFor(snap, recordings, byScenario) }
        val passing = CANDIDATES.filter { s -> rows.filter { it.snap == s }.all { it.passes } }
        val n = byScenario.values.minOf { it.size }
        if (passing.isEmpty()) return SnapOutcome(PassiveResult.NoChange(0.0, 0.0, 0.0, n), rows)
        val best = passing.minBy { s -> rows.filter { it.snap == s }.map { it.relativeChange }.average() }
        return SnapOutcome(PassiveResult.Recommend(best, passing.min(), passing.max(), n), rows)
    }

    private fun rowsFor(snap: Double, recordings: List<RoundRecording>, byScenario: Map<String, List<FirstReach>>): List<SnapRow> {
        val snapped = recordings.map { replay(it, snap) }
        return byScenario.map { (scenario, reaches) ->
            val pairs = reaches.map { r ->
                val before = abs((r.gotY - r.needY) * r.degreesPerCount)
                val after = abs((snappedY(recordings[r.roundIndex], snapped[r.roundIndex], r) - r.needY) * r.degreesPerCount)
                before to after
            }
            val change = { sample: List<Pair<Double, Double>> -> sample.sumOf { it.second } / sample.sumOf { it.first } - 1.0 }
            val estimate = change(pairs)
            val (low, high) = bootstrapInterval(pairs, change)
            SnapRow(
                scenario, snap, reaches.size, pairs.map { it.first }.average(), pairs.map { it.second }.average(), estimate, low, high,
                passes = estimate <= -MIN_REDUCTION && high < 0.0,
            )
        }
    }

    /** The y counts the driver would emit for each report of [recording] with snapping [snap]. */
    private fun replay(recording: RoundRecording, snap: Double): LongArray {
        val driver = RawAccelModifier(neutralProfile(snap))
        val prefix = LongArray(recording.mouseEvents.size + 1)
        for ((i, e) in recording.mouseEvents.withIndex()) prefix[i + 1] = prefix[i] + driver.modify(e).dy
        return prefix
    }

    /** The summed snapped y counts of the reports inside the reach's window; [prefix] holds running sums of the replay. */
    private fun snappedY(recording: RoundRecording, prefix: LongArray, reach: FirstReach): Double {
        val events = recording.mouseEvents
        return (prefix[firstAtOrAfter(events, reach.endNanos)] - prefix[firstAtOrAfter(events, reach.startNanos)]).toDouble()
    }

    private fun firstAtOrAfter(events: List<dev.veqzo.aimtune.core.input.MouseEvent>, nanos: Long): Int {
        var lo = 0
        var hi = events.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (events[mid].timeNanos < nanos) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** A profile that changes nothing but the snapping: no acceleration, output DPI equal to the device DPI, no ratios. */
    internal fun neutralProfile(snap: Double) =
        RawAccelProfile("snap", RawAccelArgs(RawAccelMode.NOACCEL), outputDpi = 1000.0, deviceDpi = 1000.0, snapDegrees = snap)
}
