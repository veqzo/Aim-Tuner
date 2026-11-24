package dev.veqzo.aimtune.app.accel

import dev.veqzo.aimtune.app.TrainerConfig
import dev.veqzo.aimtune.core.accel.RefinePhase
import dev.veqzo.aimtune.core.score.MetricWeights
import dev.veqzo.aimtune.core.sim.HeadlessRound
import dev.veqzo.aimtune.core.sim.SimulatedPlayer
import dev.veqzo.aimtune.storage.RawAccelImportResult

private const val SAMPLE_ROUND_SECONDS = 20
private const val SAMPLE_SEED = 5L

/** The text of the sample `settings.json` (the user profile of the tests), for the screenshot tour only. */
fun sampleRefineText(): String? =
    object {}.javaClass.classLoader.getResourceAsStream("sample/refine-sample.json")?.use { String(it.readAllBytes(), Charsets.UTF_8) }

/**
 * A refine run played by a simulated player up to the first stage result, for the screenshot tour only: the stage screen needs
 * a real result to show. [maxRounds] stops earlier, for the screen between two rounds. Never user data.
 */
fun sampleRefineRun(config: TrainerConfig, maxRounds: Int = Int.MAX_VALUE): RefineRun? {
    val text = sampleRefineText() ?: return null
    val source = RefineImport.parse(text, "refine-sample.json").getOrNull() ?: return null
    val profile = (RefineImport.choose(source, source.file.profileNames.first(), config.dpi).profile) ?: return null
    val run = RefineRun(config.copy(roundSeconds = SAMPLE_ROUND_SECONDS), MetricWeights.DEFAULT, runKey = 0L, seed = SAMPLE_SEED, source = source, profile = profile, flash = false)
    val player = SimulatedPlayer()
    var played = 0
    while ((run.phase == RefinePhase.WARMUP || run.phase == RefinePhase.SEARCH) && played++ < maxRounds) {
        val next = run.nextRoundConfig()
        run.recordRound(HeadlessRound.of(next.scenario, player, next.dpi, next.cm360, next.seed, next.roundSeconds, accel = next.accel))
    }
    return run
}
