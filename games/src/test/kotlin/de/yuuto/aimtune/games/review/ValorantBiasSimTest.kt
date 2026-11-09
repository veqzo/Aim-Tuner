package de.yuuto.aimtune.games.review

import de.yuuto.aimtune.core.metrics.computeRoundMetrics
import de.yuuto.aimtune.core.optimizer.Observation
import de.yuuto.aimtune.core.optimizer.SensitivityOptimizer
import de.yuuto.aimtune.core.scenario.ScenarioKind
import de.yuuto.aimtune.core.scenario.ScenarioSchedule
import de.yuuto.aimtune.core.scenario.TrainerGeometry
import de.yuuto.aimtune.core.score.DuelScorer
import de.yuuto.aimtune.core.score.Engagement
import de.yuuto.aimtune.core.score.FlashImportance
import de.yuuto.aimtune.core.score.MetricWeights
import de.yuuto.aimtune.core.score.RoundScorer
import de.yuuto.aimtune.core.score.ScoreMetric
import de.yuuto.aimtune.core.score.duelEngagements
import de.yuuto.aimtune.core.score.scoreInputs
import de.yuuto.aimtune.core.sim.HeadlessRound
import de.yuuto.aimtune.core.sim.SimulatedPlayer
import de.yuuto.aimtune.core.units.SearchRange
import de.yuuto.aimtune.games.GameProfiles
import de.yuuto.aimtune.games.parseGameProfile
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.test.Test

/**
 * M23 bias check in the simulator: does the Valorant setup of M23 (head geometry from the engagement data, scenario mix, duel
 * score) move the sensitivity finder's result for the same simulated hand, compared with the setup before M23 (the old Valorant
 * profile, metric weights, weight schedule)? The real bias was about 1.5x too fast (too few cm/360), so the fix needs a ratio
 * new / old of about 1.5 in cm/360. The simulated hand is a model, so this only shows the direction and size of the change the
 * setup makes, not whether a person's result is right. Range is left out of both setups: it is a separate mode.
 * Run with AIMTUNE_REVIEW=1; writes build/reports/valorant-bias-sim.txt.
 */
@EnabledIfEnvironmentVariable(named = "AIMTUNE_REVIEW", matches = ".+")
class ValorantBiasSimTest {
    private class Setup(val name: String, val geometry: TrainerGeometry, val schedule: ScenarioSchedule, val scorer: RoundScorer)

    private val old = run {
        val text = checkNotNull(javaClass.classLoader.getResourceAsStream("review/valorant-pre-m23.json")).use { it.readBytes().toString(Charsets.UTF_8) }
        val geometry = parseGameProfile(text).geometry
        Setup("before M23", geometry, ScenarioSchedule(setOf(ScenarioKind.FLICK, ScenarioKind.FLASH), MetricWeights.DEFAULT), RoundScorer(MetricWeights.DEFAULT))
    }

    private val new = run {
        val geometry = checkNotNull(GameProfiles.load().find("valorant")).geometry
        val enabled = setOf(ScenarioKind.FLICK, ScenarioKind.MICRO_STRAFE, ScenarioKind.SWITCH_STRAFE, ScenarioKind.FLASH)
        val schedule = ScenarioSchedule.forMix(enabled, checkNotNull(geometry.mix), FlashImportance.SOMETIMES.mixParts)
        Setup("M23", geometry, schedule, RoundScorer(MetricWeights.DEFAULT, checkNotNull(geometry.duel).scorer()))
    }

    /** Sweep only: one change at a time from the old setup, to see which part of M23 moves the optimum. */
    @Test
    fun `which part of M23 moves the optimum`() {
        val flick = setOf(ScenarioKind.FLICK)
        // The weight schedule gives flash no parts with the default weights, so the M23 mix decides the flick : flash ratio here.
        val oldMix = ScenarioSchedule.forMix(setOf(ScenarioKind.FLICK, ScenarioKind.FLASH), checkNotNull(new.geometry.mix), FlashImportance.SOMETIMES.mixParts)
        val metric = RoundScorer(MetricWeights.DEFAULT)
        val duel = RoundScorer(MetricWeights.DEFAULT, checkNotNull(new.geometry.duel).scorer())
        val flickOnly = ScenarioSchedule(flick, MetricWeights.DEFAULT)
        val variants = listOf(
            Setup("old geometry, flick only, metric", old.geometry, flickOnly, metric),
            Setup("old geometry, flick only, duel", old.geometry, flickOnly, duel),
            Setup("new geometry, flick only, metric", new.geometry, flickOnly, metric),
            Setup("new geometry, flick only, duel", new.geometry, flickOnly, duel),
            Setup("old geometry, flick + flash, duel", old.geometry, oldMix, duel),
            Setup("new geometry, flick + flash, metric", new.geometry, oldMix, metric),
            Setup("new geometry, M23 mix, metric", new.geometry, new.schedule, metric),
            Setup("new geometry, M23 mix, duel (M23)", new.geometry, new.schedule, duel),
        )
        val out = StringBuilder()
        out.appendLine("Sweep optimum (cm/360) per setup and hand; ${GRID.size} points x $SWEEP_ROUNDS rounds")
        out.appendLine("%-38s %s".format("setup", HANDS.joinToString("") { "%9.0f".format(it) }))
        for (setup in variants) {
            val optima = HANDS.toList().parallelStream().map { sweepOptimum(setup, SimulatedPlayer.forHeadSizedTargets(it), 11L) }.toList()
            out.appendLine("%-38s %s".format(setup.name, optima.joinToString("") { "%9.1f".format(it) }))
        }
        println(out)
        Files.createDirectories(Path.of("build", "reports"))
        Files.writeString(Path.of("build", "reports", "valorant-bias-parts.txt"), out.toString())
    }

    /**
     * Why the duel score favours fast settings on the new geometry: per grid point the parts the duel score is made of, then the
     * sweep optimum when the miss price or the head size changes. Writes build/reports/valorant-duel-diagnosis.txt.
     */
    @Test
    fun `what the duel score rewards on the new geometry`() {
        val flickOnly = ScenarioSchedule(setOf(ScenarioKind.FLICK), MetricWeights.DEFAULT)
        val metric = RoundScorer(MetricWeights.DEFAULT)
        val duelSetup = Setup("new geometry, flick only, duel", new.geometry, flickOnly, RoundScorer(MetricWeights.DEFAULT, checkNotNull(new.geometry.duel).scorer()))
        val out = StringBuilder()
        for (hand in listOf(50.0)) {
            val player = SimulatedPlayer.forHeadSizedTargets(hand)
            val rounds = GRID.flatMapIndexed { g, cm360 -> (0 until SWEEP_ROUNDS).map { i -> play(duelSetup, player, cm360, i, 11L * 100_003L + g * 1_000L + i) } }
            val duelScores = scores(duelSetup, rounds)
            val metricScores = metric.scoreRounds(rounds.map { it.inputs }, rounds.map { it.kind.id }, rounds.map { null })
            out.appendLine("hand %.0f, new geometry, flick only; first shot = first click of a reach, per grid point over $SWEEP_ROUNDS rounds".format(hand))
            out.appendLine("%8s %8s %9s %10s %10s %7s %7s %8s".format("cm360", "reaches", "1st hit%", "hit t ms", "miss t ms", "lost%", "duel", "metric"))
            for ((g, cm360) in GRID.withIndex()) {
                val slice = g * SWEEP_ROUNDS until (g + 1) * SWEEP_ROUNDS
                val reaches = slice.flatMap { rounds[it].engagements.orEmpty() }
                val hits = reaches.filter { it.firstShotHit && !it.lost }.mapNotNull { it.firstShotNanos }.sorted()
                val misses = reaches.filter { !it.firstShotHit && !it.lost }.mapNotNull { it.firstShotNanos }.sorted()
                fun ms(sorted: List<Long>) = if (sorted.isEmpty()) Double.NaN else sorted[sorted.size / 2] / 1e6
                out.appendLine(
                    "%8.1f %8d %9.1f %10.0f %10.0f %7.1f %7.1f %8.1f".format(
                        cm360, reaches.size / SWEEP_ROUNDS, 100.0 * hits.size / reaches.size, ms(hits), ms(misses), 100.0 * reaches.count { it.lost } / reaches.size,
                        slice.mapNotNull { duelScores[it] }.average(), slice.mapNotNull { metricScores[it] }.average(),
                    ),
                )
            }
            out.appendLine()
        }
        val body = checkNotNull(new.geometry.body)
        out.appendLine("Sweep optimum (cm/360), new geometry, flick only, duel score; one change at a time")
        out.appendLine("%-34s %s".format("change", HANDS.joinToString("") { "%9.0f".format(it) }))
        val changes = listOf(308L, 600L, 1_000L).map { miss ->
            "miss price $miss ms" to Setup("", new.geometry, flickOnly, RoundScorer(MetricWeights.DEFAULT, DuelScorer(miss * 1_000_000L)))
        } + listOf(0.7, 0.5).map { scale ->
            "head x$scale, miss 308 ms" to Setup("", new.geometry.copy(body = body.copy(headDiameterMeters = body.headDiameterMeters * scale)), flickOnly, duelSetup.scorer)
        }
        for ((name, setup) in changes) {
            val optima = HANDS.toList().parallelStream().map { sweepOptimum(setup, SimulatedPlayer.forHeadSizedTargets(it), 11L) }.toList()
            out.appendLine("%-34s %s".format(name, optima.joinToString("") { "%9.1f".format(it) }))
        }
        println(out)
        Files.createDirectories(Path.of("build", "reports"))
        Files.writeString(Path.of("build", "reports", "valorant-duel-diagnosis.txt"), out.toString())
    }

    private class Played(val cm360: Double, val kind: ScenarioKind, val inputs: Map<ScoreMetric, Double>, val engagements: List<Engagement>?)

    private fun play(setup: Setup, player: SimulatedPlayer, cm360: Double, index: Int, seed: Long): Played {
        val kind = setup.schedule.kindFor(index)
        val recording = HeadlessRound.of(kind, player, DPI, cm360, seed, ROUND_SECONDS, index * ROUND_SECONDS * 1_000_000_000L, geometry = setup.geometry)
        return Played(cm360, kind, computeRoundMetrics(recording).scoreInputs(), duelEngagements(kind, recording.targetEvents))
    }

    private fun scores(setup: Setup, rounds: List<Played>): List<Double?> =
        setup.scorer.scoreRounds(rounds.map { it.inputs }, rounds.map { it.kind.id }, rounds.map { it.engagements })

    /** The optimum of the mean score over a fixed grid, every grid point played with the same schedule (the "truth" of this hand and setup). */
    private fun sweepOptimum(setup: Setup, player: SimulatedPlayer, seed: Long): Double {
        val rounds = GRID.flatMapIndexed { g, cm360 -> (0 until SWEEP_ROUNDS).map { i -> play(setup, player, cm360, i, seed * 100_003L + g * 1_000L + i) } }
        val all = scores(setup, rounds)
        val means = GRID.indices.map { g -> (0 until SWEEP_ROUNDS).mapNotNull { all[g * SWEEP_ROUNDS + it] }.average() }
        return parabolicPeak(means)
    }

    /** One blind test the way the app runs it: the optimizer picks, a round is played, all rounds are rescored. */
    private fun blindTest(setup: Setup, player: SimulatedPlayer, seed: Long): Double {
        val optimizer = SensitivityOptimizer(RANGE, seed)
        val rounds = mutableListOf<Played>()
        var history = emptyList<Observation>()
        repeat(BLIND_ROUNDS) { index ->
            rounds += play(setup, player, optimizer.suggestNext(history).cm360, index, seed * 7_919L + index)
            val all = scores(setup, rounds)
            history = rounds.mapIndexed { i, r -> Observation(0L, r.cm360, all[i] ?: UNKNOWN_SCORE, group = r.kind.id.hashCode(), step = i) }
        }
        return checkNotNull(optimizer.analyze(history)).optimumCm360
    }

    @Test
    fun `the M23 setup moves the result of the same hand`() {
        val out = StringBuilder()
        out.appendLine("Valorant bias check in the simulator: the same hand, the setup before M23 against M23")
        out.appendLine("before M23: old profile geometry, flick + flash by the default weights, metric score")
        out.appendLine("M23:        ${new.schedule.shares.entries.joinToString { "%s %.0f%%".format(it.key.id, 100 * it.value) }}, duel score")
        out.appendLine("blind test: $BLIND_ROUNDS rounds, $SEEDS seeds, range $RANGE; sweep: ${GRID.size} points x $SWEEP_ROUNDS rounds")
        out.appendLine("ratio > 1: M23 recommends more cm/360 (slower); the real bias needs about 1.5")
        out.appendLine()
        for (optimum in HANDS) {
            val player = SimulatedPlayer.forHeadSizedTargets(optimum)
            val results = listOf(old, new).associateWith { setup ->
                val sweep = sweepOptimum(setup, player, 11L)
                val blind = (1L..SEEDS).toList().parallelStream().map { blindTest(setup, player, it) }.toList().sorted()
                sweep to blind
            }
            out.appendLine("hand with optimum %.0f cm/360 on the old head targets".format(optimum))
            for ((setup, result) in results) {
                val (sweep, blind) = result
                out.appendLine("  %-10s sweep optimum %6.1f | blind median %6.1f, p25-p75 %6.1f-%6.1f".format(setup.name, sweep, blind[blind.size / 2], blind[blind.size / 4], blind[3 * blind.size / 4]))
            }
            val (oldSweep, oldBlind) = results.getValue(old)
            val (newSweep, newBlind) = results.getValue(new)
            out.appendLine(
                "  ratio M23 / before: sweep x%.2f, blind median x%.2f, blind geometric mean x%.2f".format(
                    newSweep / oldSweep, newBlind[newBlind.size / 2] / oldBlind[oldBlind.size / 2], exp(newBlind.map(::ln).average() - oldBlind.map(::ln).average()),
                ),
            )
        }
        println(out)
        val dir = Path.of("build", "reports")
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("valorant-bias-sim.txt"), out.toString())
    }

    /** Peak of a parabola in ln(cm/360) through the best grid point and its neighbours. */
    private fun parabolicPeak(means: List<Double>): Double {
        val best = means.indices.maxBy { means[it] }.coerceIn(1, means.size - 2)
        val (a, b, c) = Triple(means[best - 1], means[best], means[best + 1])
        val step = ln(GRID[1] / GRID[0])
        val denominator = a - 2 * b + c
        val shift = if (denominator < 0) (0.5 * (a - c) / denominator).coerceIn(-1.0, 1.0) else 0.0
        return GRID[best] * exp(shift * step)
    }

    private companion object {
        const val DPI = 800.0
        const val ROUND_SECONDS = 45
        const val BLIND_ROUNDS = 30
        const val SEEDS = 12L
        const val SWEEP_ROUNDS = 24
        const val UNKNOWN_SCORE = 50.0
        val RANGE = SearchRange(15.0, 200.0)
        val GRID = (0 until 15).map { 15.0 * (200.0 / 15.0).pow(it / 14.0) }
        val HANDS = listOf(35.0, 50.0, 80.0)
    }
}
