package dev.veqzo.aimtune.app.blind

import dev.veqzo.aimtune.app.TrainerConfig
import dev.veqzo.aimtune.app.i18n.UiText
import dev.veqzo.aimtune.core.scenario.ScenarioKind
import dev.veqzo.aimtune.core.scenario.ScenarioSchedule
import dev.veqzo.aimtune.core.score.MetricWeights

/**
 * A blind test run in progress: the optimizer-backed [test], the key that groups this run's rounds
 * into one stored session, and the settings every round shares. [nextRoundConfig] gives the trainer
 * config of the next round: the scenario comes from the [schedule], the sensitivity from the
 * optimizer and stays hidden from the user.
 */
class BlindRun(
    val test: BlindTest,
    val runKey: Long,
    private val baseConfig: TrainerConfig,
    private var nextSeed: Long,
    private val schedule: ScenarioSchedule = ScenarioSchedule(setOf(ScenarioKind.FLICK), MetricWeights.DEFAULT),
) {
    /** Current sensitivity setting of the user (shown after the test, as "now"). */
    val currentCm360: Double get() = baseConfig.cm360

    val profile get() = baseConfig.profile

    val dpi: Double get() = baseConfig.dpi

    /** Config of the next round; asks the optimizer, so it may wait for the analysis of the last round. */
    fun nextRoundConfig(): TrainerConfig =
        baseConfig.copy(cm360 = test.nextCm360(), seed = nextSeed++, scenario = schedule.kindFor(test.roundsPlayed))

    /** "Round 4 / 10", or just "Round 12" once the planned rounds are used up. */
    fun nextRoundLabel(): UiText = roundLabel(test.roundsPlayed + 1)

    fun completedRoundLabel(): UiText = roundLabel(test.roundsPlayed)

    private fun roundLabel(number: Int): UiText =
        if (number <= test.plannedRounds) UiText("round.label", number, test.plannedRounds) else UiText("round.label_open", number)
}
