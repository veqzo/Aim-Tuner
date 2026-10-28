package de.yuuto.aimtune.app.accel

import de.yuuto.aimtune.app.TrainerConfig
import de.yuuto.aimtune.core.score.MetricWeights
import de.yuuto.aimtune.core.sim.HeadlessRound
import de.yuuto.aimtune.core.sim.SimulatedPlayer
import kotlin.math.exp

/** The player's own curve in the screenshots and tests: Raw Accel classic, gain form, offset 10, cap 62.5 in/s. */
val sampleOwnCurve = de.yuuto.aimtune.core.accel.RawAccelProfile(
    "default",
    de.yuuto.aimtune.core.accel.RawAccelArgs(
        de.yuuto.aimtune.core.accel.RawAccelMode.CLASSIC, gain = true, inputOffset = 10.0, capX = 62.5, capY = 1.6,
        capMode = de.yuuto.aimtune.core.accel.RawAccelCapMode.IN_OUT,
    ),
    600.0, 800.0,
)

/** An analysis on the sample curve of a loose hand that moves at many speeds (earlier reaches, no simulated player), which shows the draft card with a step. */
fun sampleDraftRun(config: TrainerConfig): AccelRun {
    val random = kotlin.random.Random(5)
    val samples = List(1200) {
        val v = exp(random.nextDouble(kotlin.math.ln(3.5), kotlin.math.ln(25.0)))
        de.yuuto.aimtune.core.accel.MovementSample(v, exp(0.45 * (random.nextDouble() + random.nextDouble() + random.nextDouble() - 1.5) * 2.0) - 1.0)
    }
    val run = AccelRun(config.copy(roundSeconds = SAMPLE_ROUND_SECONDS), MetricWeights.DEFAULT, runKey = 0L, seed = SAMPLE_SEED, prior = PriorCalibration(40, samples), baseCurve = sampleOwnCurve)
    while (run.phase == AccelPhase.CALIBRATION) {
        val next = run.nextRoundConfig()
        run.recordRound(HeadlessRound.of(next.scenario, SimulatedPlayer(), next.dpi, next.cm360, next.seed, next.roundSeconds, accel = next.accel))
    }
    return run
}

private const val SAMPLE_ROUND_SECONDS = 45
private const val SAMPLE_SEED = 3L
private const val CALIBRATION_ROUNDS_SHOWN = 6
private const val TEST_ROUNDS_SHOWN = 3

/** A simulated player who expects the gain to fall with the hand speed (the case the analysis finds reliably); Raw Accel's natural curve can deliver it. */
val fallingGainPlayer = SimulatedPlayer(gainPreference = { speed -> 1.0 - 0.3 * (1.0 - exp(-speed / 6.0)) })

/**
 * An acceleration run played by the simulated [fallingGainPlayer] up to [until], for the screenshot
 * tour and tests only: the analysis and verdict screens need a real fit to show. Never user data.
 */
fun sampleAccelRun(config: TrainerConfig, until: AccelPhase): AccelRun {
    val sampleConfig = config.copy(roundSeconds = SAMPLE_ROUND_SECONDS)
    val run = AccelRun(sampleConfig, MetricWeights.DEFAULT, runKey = 0L, seed = SAMPLE_SEED)
    fun playRound() {
        val next = run.nextRoundConfig()
        run.recordRound(
            HeadlessRound.of(next.scenario, fallingGainPlayer, next.dpi, next.cm360, next.seed, next.roundSeconds, accel = next.accel),
        )
    }
    when (until) {
        AccelPhase.CALIBRATION -> repeat(CALIBRATION_ROUNDS_SHOWN) { playRound() }
        AccelPhase.SEARCH, AccelPhase.SEARCH_RESULT -> {
            while (run.phase == AccelPhase.CALIBRATION) playRound()
            run.startSearch()
            if (until == AccelPhase.SEARCH) repeat(CALIBRATION_ROUNDS_SHOWN) { playRound() } else while (run.phase == AccelPhase.SEARCH) playRound()
        }
        AccelPhase.ANALYSIS, AccelPhase.TEST, AccelPhase.DONE -> {
            while (run.phase == AccelPhase.CALIBRATION) playRound()
            if (until == AccelPhase.ANALYSIS || run.candidate == null) return run
            run.startTest()
            if (until == AccelPhase.TEST) repeat(TEST_ROUNDS_SHOWN) { playRound() } else while (run.phase == AccelPhase.TEST) playRound()
        }
    }
    return run
}
