package dev.veqzo.aimtune.core.metrics

import dev.veqzo.aimtune.core.scenario.ScenarioKind
import dev.veqzo.aimtune.core.scenario.TrainerGeometry
import dev.veqzo.aimtune.core.sim.HeadlessRound
import dev.veqzo.aimtune.core.sim.SimulatedPlayer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Metrics of rounds with static targets must not change when moving-target support is added (M15a): the metrics of
 * seeded rounds are compared with a fixture recorded before that change. To re-record after an intended change of the
 * metric definitions, run with the environment variable AIMTUNE_WRITE_FIXTURE set and review the diff.
 */
class StaticMetricsFixtureTest {
    private val fixture = Path.of("src", "test", "resources", "metrics", "static-metrics.txt")

    @Test
    fun staticRoundsKeepTheirMetrics() {
        val text = describeStaticRounds()
        if (System.getenv("AIMTUNE_WRITE_FIXTURE") != null) {
            Files.createDirectories(fixture.parent)
            Files.writeString(fixture, text)
        }
        assertEquals(Files.readString(fixture).replace("\r\n", "\n"), text)
    }
}

internal fun describeStaticRounds(): String = buildString {
    val player = SimulatedPlayer().withOptimum(50.0)
    for (kind in listOf(ScenarioKind.FLICK, ScenarioKind.MICRO, ScenarioKind.SWITCHING)) {
        for (geometry in listOf<TrainerGeometry?>(null, TrainerGeometry.DEFAULT)) {
            for (seed in 1L..3L) {
                val recording = HeadlessRound.of(kind, player, 800.0, 50.0, seed, roundSeconds = 20, geometry = geometry)
                val metrics = computeRoundMetrics(recording)
                appendLine("round ${kind.id} geometry=${geometry != null} seed=$seed")
                appendLine("  summary ${metrics.hits} ${metrics.shots} ${metrics.accuracy} ${metrics.firstShotAccuracy} ${metrics.meanTimeToHitMs} ${metrics.meanFirstError} ${metrics.errorSpread}")
                appendLine("  axes ${metrics.meanAbsFirstErrorHDegrees} ${metrics.meanAbsFirstErrorVDegrees} ${metrics.meanFirstErrorVDegrees} ${metrics.meanPathEfficiency} ${metrics.meanCorrectiveSubmovements}")
                metrics.movements.forEach { appendLine("  hit ${describe(it)}") }
                allFirstReaches(recording).forEach { appendLine("  reach ${describe(it)}") }
            }
        }
    }
}

private fun describe(m: MovementMetric): String =
    "${m.targetId} ${m.timeToTargetNanos} ${m.initialDistanceDegrees} ${m.reactionNanos} ${m.firstErrorRatio} ${m.firstPeakSpeedCountsPerMs} " +
        "${m.correctiveSubmovements} ${m.pathEfficiency} ${m.firstErrorHDegrees} ${m.firstErrorVDegrees}"
