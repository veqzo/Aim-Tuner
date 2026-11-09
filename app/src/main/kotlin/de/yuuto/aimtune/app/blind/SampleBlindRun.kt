package de.yuuto.aimtune.app.blind

import de.yuuto.aimtune.app.TrainerConfig
import de.yuuto.aimtune.core.metrics.computeRoundMetrics
import de.yuuto.aimtune.core.scenario.ScenarioKind
import de.yuuto.aimtune.core.scenario.ScenarioSchedule
import de.yuuto.aimtune.core.score.MetricWeights
import de.yuuto.aimtune.core.score.RoundScorer
import de.yuuto.aimtune.core.sim.HeadlessRound
import de.yuuto.aimtune.core.sim.SimulatedPlayer
import de.yuuto.aimtune.core.units.AimStyle
import de.yuuto.aimtune.core.units.searchRange

private const val SAMPLE_ROUNDS = 14
private const val SAMPLE_ROUND_SECONDS = 30
private const val SAMPLE_PAD_WIDTH_CM = 45.0
private const val SAMPLE_OPTIMUM_FACTOR = 1.1

/**
 * A blind run filled with rounds of the simulated player, for the screenshot tour only: the between
 * rounds and results screens need an analysis to show. Never used with real user data.
 */
fun sampleBlindRun(config: TrainerConfig): BlindRun {
    val range = searchRange(AimStyle.MIXED, config.cm360, SAMPLE_PAD_WIDTH_CM)
    val test = BlindTest(range, RoundScorer(MetricWeights.DEFAULT), emptyList(), seed = 1)
    val schedule = ScenarioSchedule(ScenarioKind.entries.toSet(), MetricWeights.DEFAULT)
    val player = SimulatedPlayer().withOptimum(config.cm360 * SAMPLE_OPTIMUM_FACTOR)
    repeat(SAMPLE_ROUNDS) { i ->
        val cm360 = test.nextCm360()
        val kind = schedule.kindFor(i)
        test.recordRound(cm360, computeRoundMetrics(HeadlessRound.of(kind, player, config.dpi, cm360, 1_000L + i, SAMPLE_ROUND_SECONDS)), kind)
    }
    test.step()
    return BlindRun(test, runKey = 0L, baseConfig = config, nextSeed = 99L, schedule = schedule)
}
