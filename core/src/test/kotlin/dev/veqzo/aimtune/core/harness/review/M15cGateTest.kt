package dev.veqzo.aimtune.core.harness.review

import dev.veqzo.aimtune.core.accel.NaturalCurve
import dev.veqzo.aimtune.core.accel.movementSamples
import dev.veqzo.aimtune.core.harness.TEST_DPI
import dev.veqzo.aimtune.core.harness.playBlindTest
import dev.veqzo.aimtune.core.metrics.computeRoundMetrics
import dev.veqzo.aimtune.core.optimizer.Observation
import dev.veqzo.aimtune.core.optimizer.SensitivityOptimizer
import dev.veqzo.aimtune.core.scenario.ScenarioKind
import dev.veqzo.aimtune.core.scenario.ScenarioSchedule
import dev.veqzo.aimtune.core.scenario.TrainerGeometry
import dev.veqzo.aimtune.core.score.MetricWeights
import dev.veqzo.aimtune.core.score.ScoreCalculator
import dev.veqzo.aimtune.core.score.ScoreMetric
import dev.veqzo.aimtune.core.score.scoreInputs
import dev.veqzo.aimtune.core.sim.HeadlessRound
import dev.veqzo.aimtune.core.sim.SimulatedPlayer
import dev.veqzo.aimtune.core.units.SearchRange
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.math.abs
import kotlin.math.exp

/**
 * Gate of M15c (Range scenario): (a) range rounds mixed into the calibration do not raise the false-offer rate of flat
 * players, (b) the sensitivity optimizer with range rounds against flick only (reported), (c) detection of a rising or
 * falling gain in the strength search with range rounds in the schedule against without (reported). Numbers only; nothing
 * here is tuned to pass.
 */
@EnabledIfEnvironmentVariable(named = "AIMTUNE_REVIEW", matches = ".+")
class M15cGateTest {
    private val range = SearchRange(20.0, 140.0)

    @Test
    fun gate() {
        val out = StringBuilder()
        out.appendLine("== M15c gate @ git ${gitShortHash()} ==")
        falseOffers(out)
        out.appendLine()
        optimizer(out)
        out.appendLine()
        strengthSearch(out)
        write("gate-m15c.txt", out.toString())
    }

    private class Arm(val offered: Boolean, val samples: Int, val rangeMovements: Int, val rangeUsed: Int, val kills: Double)

    private fun falseOffers(out: StringBuilder) {
        val n = 30
        val rounds = 15
        val mixed = listOf(ScenarioKind.RANGE, ScenarioKind.FLICK, ScenarioKind.SWITCHING, ScenarioKind.MICRO)
        val arms = listOf(
            "flick/switching/micro x$rounds" to listOf(ScenarioKind.FLICK, ScenarioKind.SWITCHING, ScenarioKind.MICRO),
            "range + flick/switching/micro x$rounds" to mixed,
            "range x$rounds" to listOf(ScenarioKind.RANGE),
        )
        out.appendLine("-- (a) flat players (no gain preference), $n seeds per row, $rounds calibration rounds: curves offered by the legacy analysis, usable samples, range movements and how many reached the analysis, kills per range round")
        out.appendLine("model      arm                                        offered  samples/run  range mvts  range used  kills/round")
        for (model in listOf("DEFAULT" to { p: SimulatedPlayer -> p }, "RANGE" to { p: SimulatedPlayer -> p.copy(rangeBias = 0.05) })) {
            for ((label, kinds) in arms) {
                val runs = parallelList(n) { s ->
                    val player = model.second(SimulatedPlayer())
                    val metrics = (0 until rounds).map { computeRoundMetrics(HeadlessRound.of(kinds[it % kinds.size], player, TEST_DPI, CALIBRATION_BASE_CM360, (400_000L + s) * 1_000 + it, ROUND_SECONDS)) }
                    val rangeRounds = metrics.filterIndexed { i, _ -> kinds[i % kinds.size] == ScenarioKind.RANGE }
                    Arm(
                        LegacyCurveMethod.recommend(metrics, TEST_DPI) != null,
                        movementSamples(metrics, TEST_DPI).size,
                        rangeRounds.flatMap { it.movements }.size,
                        movementSamples(rangeRounds, TEST_DPI).size,
                        rangeRounds.map { (it.rangeKills ?: 0).toDouble() }.average().takeIf { !it.isNaN() } ?: 0.0,
                    )
                }
                out.appendLine(
                    "%-10s %-42s %3d/%-3d  %11.1f  %10.1f  %10.1f  %11.1f".format(
                        model.first, label, runs.count { it.offered }, n, runs.map { it.samples }.average(),
                        runs.map { it.rangeMovements }.average(), runs.map { it.rangeUsed }.average(), runs.map { it.kills }.average(),
                    ),
                )
            }
        }
        out.appendLine("(range used = movements of range rounds that entered the analysis: only reaches toward a bot that stood still during the whole reach)")
    }

    private fun optimizer(out: StringBuilder) {
        val n = 30
        val rounds = 15
        val player = SimulatedPlayer().withOptimum(50.0)
        val mixSchedule = ScenarioSchedule(setOf(ScenarioKind.FLICK, ScenarioKind.RANGE), MetricWeights.DEFAULT)
        val arms = listOf<Pair<String, (Int) -> ScenarioKind>>(
            "flick only" to { ScenarioKind.FLICK },
            "range only" to { ScenarioKind.RANGE },
            "flick + range (schedule)" to { mixSchedule.kindFor(it) },
        )
        out.appendLine("-- (b) sensitivity optimizer, optimum 50 cm/360, range 20-140, $rounds rounds, $n seeds (report only)")
        out.appendLine("schedule shares: " + mixSchedule.shares.entries.joinToString { "%s %.0f%%".format(it.key.id, it.value * 100) })
        out.appendLine("arm                                  median  p25-p75      medErr   conf mean")
        for ((label, kindFor) in arms) {
            val results = parallelList(n) { s -> checkNotNull(playBlindTest(player, range, rounds, 5000L + s, scenarioFor = kindFor).result) }
            val optima = results.map { it.optimumCm360 }
            out.appendLine(
                "%-36s %6.1f  %5.1f-%-5.1f  x%.3f  %5.0f".format(
                    label, median(optima), percentileOf(optima, 0.25), percentileOf(optima, 0.75),
                    median(optima.map { errorFactorOf(it, 50.0) }), results.map { it.confidencePercent.toDouble() }.average(),
                ),
            )
        }
    }

    // --- (c) strength search ---

    private val geometry = TrainerGeometry.DEFAULT
    private val strengthRange = SearchRange(70.0, 200.0)
    private val base = 50.0
    private val strengthWeights = MetricWeights(mapOf(ScoreMetric.ACCURACY to 40.0, ScoreMetric.TIME_TO_HIT to 30.0, ScoreMetric.FLASH_DODGE to 15.0))

    private fun gainPlayer(change: Double) = SimulatedPlayer.forHeadSizedTargets(base).copy(gainPreference = { v -> 1.0 + change * (1.0 - exp(-v / 6.0)) })

    private fun searchStrength(player: SimulatedPlayer, mix: List<ScenarioKind>, rounds: Int, seed: Long): Double {
        val optimizer = SensitivityOptimizer(strengthRange, seed)
        val inputs = mutableListOf<Map<ScoreMetric, Double>>()
        val kinds = mutableListOf<String>()
        val pseudo = mutableListOf<Double>()
        fun history(): List<Observation> {
            val scores = ScoreCalculator(strengthWeights).scoreRounds(inputs, kinds)
            return pseudo.indices.map { Observation(1L, pseudo[it], scores[it] ?: 50.0, kinds[it].hashCode(), it) }
        }
        repeat(rounds) { index ->
            val next = optimizer.suggestNext(history()).cm360
            val strength = next / 100.0
            val curve = if (abs(strength - 1.0) < 0.02) null else NaturalCurve(0.05, strength)
            val kind = mix[index % mix.size]
            val metrics = computeRoundMetrics(HeadlessRound.of(kind, player, TEST_DPI, base, seed * 1_000_003L + index, ROUND_SECONDS, index * ROUND_SECONDS * NANOS_PER_SECOND, curve, geometry))
            inputs += metrics.scoreInputs()
            kinds += kind.id
            pseudo += next
        }
        return checkNotNull(optimizer.analyze(history())).optimumCm360 / 100.0
    }

    private fun strengthSearch(out: StringBuilder) {
        val n = 16
        val rounds = 24
        val mixes = listOf(
            "without range: micro/flash/flick" to listOf(ScenarioKind.MICRO, ScenarioKind.FLASH, ScenarioKind.FLICK),
            "with range: micro/flash/range/flick" to listOf(ScenarioKind.MICRO, ScenarioKind.FLASH, ScenarioKind.RANGE, ScenarioKind.FLICK),
        )
        out.appendLine("-- (c) strength search, strengths 0.70-2.00, $rounds rounds (a new strength every round), $n seeds; direction found = best strength above 1.15 (rising) / below 0.90 (falling); flat shows false directions (report only)")
        out.appendLine("player          mix                                    median strength  direction found")
        for ((playerLabel, change) in listOf("rising +30%" to 0.30, "falling -30%" to -0.30, "flat" to 0.0)) {
            for ((label, mix) in mixes) {
                val found = parallelList(n) { s -> searchStrength(gainPlayer(change), mix, rounds, 9000L + s) }
                val hits = when {
                    change > 0 -> found.count { it > 1.15 }
                    change < 0 -> found.count { it < 0.90 }
                    else -> found.count { it > 1.15 || it < 0.90 }
                }
                out.appendLine("%-15s %-38s %15.2f  %d/%d".format(playerLabel, label, median(found), hits, n))
            }
        }
    }
}
