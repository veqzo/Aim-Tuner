package dev.veqzo.aimtune.core.harness.review

import dev.veqzo.aimtune.core.accel.AbCondition
import dev.veqzo.aimtune.core.accel.AbOutcome
import dev.veqzo.aimtune.core.accel.AbStatus
import dev.veqzo.aimtune.core.accel.AccelAbPlan
import dev.veqzo.aimtune.core.accel.AccelCurve
import dev.veqzo.aimtune.core.accel.evaluateAb
import dev.veqzo.aimtune.core.accel.NaturalCurve
import dev.veqzo.aimtune.core.harness.TEST_DPI
import dev.veqzo.aimtune.core.metrics.computeRoundMetrics
import dev.veqzo.aimtune.core.optimizer.Observation
import dev.veqzo.aimtune.core.optimizer.SensitivityOptimizer
import dev.veqzo.aimtune.core.scenario.ScenarioKind
import dev.veqzo.aimtune.core.scenario.TrainerGeometry
import dev.veqzo.aimtune.core.score.MetricWeights
import dev.veqzo.aimtune.core.score.ScoreCalculator
import dev.veqzo.aimtune.core.score.ScoreMetric
import dev.veqzo.aimtune.core.score.scoreInputs
import dev.veqzo.aimtune.core.sim.HandAdaptation
import dev.veqzo.aimtune.core.sim.HeadlessRound
import dev.veqzo.aimtune.core.sim.NoiseModel
import dev.veqzo.aimtune.core.sim.SimulatedPlayer
import dev.veqzo.aimtune.core.units.SearchRange
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.math.abs
import kotlin.math.exp
import kotlin.test.Test

/**
 * The blind strength search for the player's goal (micro-adjustments and flash turn-away, the removed docs/OPTIMIZER-REVIEW.md (git history)):
 * a micro / flash / flick mix with flash weighted 35, strengths 0.7-2.0. A search that changes the strength every round
 * measures a hand that never gets used to any of them; the block designs play each strength for [BLOCK] rounds and
 * drop the first one of every block, which is for getting used to it. The truth is a sweep over fixed strengths with the
 * same mix, 24 rounds each, so a hand that adapts has adapted there.
 */
@EnabledIfEnvironmentVariable(named = "AIMTUNE_REVIEW", matches = ".+")
class BlockStrengthSearchTest {
    private val seeds = System.getenv("AIMTUNE_SEEDS")?.toIntOrNull() ?: 8
    private val geometry = TrainerGeometry.DEFAULT
    private val mix = listOf(ScenarioKind.MICRO, ScenarioKind.FLASH, ScenarioKind.FLICK)
    /** Flash dodge weight: 35 is the app's "often", 15 "sometimes" (env AIMTUNE_FLASH_WEIGHT). */
    private val flashWeight = System.getenv("AIMTUNE_FLASH_WEIGHT")?.toDoubleOrNull() ?: 35.0
    private val weights = MetricWeights(mapOf(ScoreMetric.ACCURACY to 40.0, ScoreMetric.TIME_TO_HIT to 30.0, ScoreMetric.FLASH_DODGE to flashWeight))
    private val strengths = listOf(0.7, 0.85, 1.0, 1.25, 1.5, 1.75, 2.0)
    private val range = SearchRange(70.0, 200.0)
    private val base = 50.0

    private class Config(val label: String, val model: String, val change: Double)

    private val configs = listOf("DEFAULT", "DECOUPLED", "RANGE", "ADAPTIVE").map { Config("$it flat", it, 0.0) } +
        listOf(Config("ADAPTIVE +15%", "ADAPTIVE", 0.15), Config("ADAPTIVE -15%", "ADAPTIVE", -0.15), Config("DEFAULT +15%", "DEFAULT", 0.15))

    private fun playerFor(config: Config): SimulatedPlayer {
        val p = SimulatedPlayer.forHeadSizedTargets(base).copy(gainPreference = { v -> 1.0 + config.change * (1.0 - exp(-v / 6.0)) })
        return when (config.model) {
            "DECOUPLED" -> p.copy(noiseModel = NoiseModel.DECOUPLED)
            "RANGE" -> p.copy(rangeBias = 0.05)
            "ADAPTIVE" -> p.copy(adaptation = HandAdaptation.adaptedTo(p.gainPreference))
            else -> p
        }
    }

    private fun curveOf(strength: Double): AccelCurve? = if (abs(strength - 1.0) < 0.02) null else NaturalCurve(0.05, strength)

    private fun play(player: SimulatedPlayer, index: Int, strength: Double, seed: Long) =
        computeRoundMetrics(HeadlessRound.of(mix[index % mix.size], player, TEST_DPI, base, seed * 1_000_003L + index, ROUND_SECONDS, index * ROUND_SECONDS * NANOS_PER_SECOND, curveOf(strength), geometry))

    /** Mean score per fixed strength, all strengths scored together; the truth the searches are judged by. */
    private fun truth(config: Config, seed: Long): List<Double> {
        val played = parallelList(strengths.size) { i -> val player = playerFor(config); (0 until 24).map { play(player, it, strengths[i], seed) } }
        val flat = played.flatten()
        val scores = ScoreCalculator(weights).scoreRounds(flat.map { it.scoreInputs() }, flat.indices.map { mix[(it % 24) % mix.size].id })
        return played.indices.map { i -> (0 until 24).mapNotNull { scores[i * 24 + it] }.average() }
    }

    /** One search of [rounds] rounds in blocks of [block] (1 = a new strength every round, nothing dropped); its found strength. */
    private fun search(config: Config, block: Int, rounds: Int, seed: Long): Double {
        val player = playerFor(config)
        val optimizer = SensitivityOptimizer(range, seed)
        val inputs = mutableListOf<Map<ScoreMetric, Double>>()
        val kinds = mutableListOf<String>()
        val pseudo = mutableListOf<Double>()
        fun history(): List<Observation> {
            val scores = ScoreCalculator(weights).scoreRounds(inputs, kinds)
            return pseudo.indices.map { Observation(1L, pseudo[it], scores[it] ?: 50.0, kinds[it].hashCode(), it) }
        }
        var index = 0
        while (index < rounds) {
            val next = optimizer.suggestNext(history()).cm360
            repeat(block) { r ->
                if (index >= rounds) return@repeat
                val metrics = play(player, index, next / 100.0, seed + 77)
                if (block == 1 || r > 0) {
                    inputs += metrics.scoreInputs()
                    kinds += mix[index % mix.size].id
                    pseudo += next
                }
                index++
            }
        }
        return checkNotNull(optimizer.analyze(history())).optimumCm360 / 100.0
    }

    /** A search design as the app would run it: its strength range and its round mix. */
    private class Design(val name: String, val range: SearchRange, val mix: List<ScenarioKind>)

    private val currentDesign = Design(
        "current (0.70-1.45, app mix)", SearchRange(70.0, 145.0),
        listOf(ScenarioKind.FLICK, ScenarioKind.FLASH, ScenarioKind.SWITCHING, ScenarioKind.FLASH, ScenarioKind.MICRO, ScenarioKind.FLASH),
    )
    /** The app's mix with a strength range that ends at [highPseudo] / 100 instead of 1.45. */
    private fun wider(highPseudo: Double) = Design("current mix, 0.70-%.2f".format(highPseudo / 100.0), SearchRange(70.0, highPseudo), currentDesign.mix)

    private val newDesign = Design("new (0.70-2.00, micro/flash/flick)", range, mix)

    private class Chain(val offered: Boolean, val exported: Boolean, val strength: Double)

    /**
     * The whole chain on one simulated person: 24 search rounds (a new strength every round), an offer only when the
     * credible range excludes "no curve" (as the app does), then the blind A/B test of up to 12 pairs with the same mix,
     * looked at after every pair; exported when it says better. The same hand plays everything in order.
     */
    private fun chain(config: Config, design: Design, seed: Long): Chain {
        val player = playerFor(config)
        val optimizer = SensitivityOptimizer(design.range, seed)
        val inputs = mutableListOf<Map<ScoreMetric, Double>>()
        val kinds = mutableListOf<String>()
        val pseudo = mutableListOf<Double>()
        fun history(): List<Observation> {
            val scores = ScoreCalculator(weights).scoreRounds(inputs, kinds)
            return pseudo.indices.map { Observation(1L, pseudo[it], scores[it] ?: 50.0, kinds[it].hashCode(), it) }
        }
        var index = 0
        fun playRound(kind: ScenarioKind, curve: AccelCurve?, roundSeed: Long) = computeRoundMetrics(
            HeadlessRound.of(kind, player, TEST_DPI, base, roundSeed, ROUND_SECONDS, index++ * ROUND_SECONDS * NANOS_PER_SECOND, curve, geometry),
        )
        repeat(24) {
            val next = optimizer.suggestNext(history()).cm360
            val kind = design.mix[it % design.mix.size]
            inputs += playRound(kind, curveOf(next / 100.0), seed * 1_000_003L + it).scoreInputs()
            kinds += kind.id
            pseudo += next
        }
        val result = checkNotNull(optimizer.analyze(history()))
        val strength = result.optimumCm360 / 100.0
        val curve = curveOf(strength)
        val offered = curve != null && (result.intervalLowCm360 > 100.0 || result.intervalHighCm360 < 100.0)
        if (!offered) return Chain(false, false, strength)
        val plan = AccelAbPlan(design.mix, seed + 31, AccelAbPlan.DEFAULT_MAX_PAIRS)
        val outcomes = mutableListOf<AbOutcome>()
        for (i in 0 until 2 * plan.maxPairs) {
            val round = plan.round(i)
            val metrics = playRound(round.scenario, if (round.condition == AbCondition.ACCEL) curve else null, round.seed)
            outcomes += AbOutcome(round.pair, round.condition, metrics.scoreInputs(), round.scenario.id)
            if (i % 2 == 1) {
                val verdict = evaluateAb(outcomes, weights)
                if (verdict.status != AbStatus.NEED_MORE_ROUNDS) return Chain(true, verdict.status == AbStatus.ACCEL_BETTER, strength)
            }
        }
        return Chain(true, false, strength)
    }

    @Test
    fun chainSearchAndBlindTest() {
        val n = seeds * 2
        val out = StringBuilder("== chain: 24-round strength search, offer if the range excludes plain, blind A/B (12 pairs, alpha 0.01, pooled), export if better; $n seeds per row; flash weight 35 ==\n")
        out.appendLine("benefit = truth score of the micro/flash/flick mix at the exported strength minus with no curve (0 when nothing is exported); truth from 24 rounds per fixed strength")
        for (config in configs) {
            val truths = (1..n).map { truth(config, it.toLong()) }
            val mean = strengths.indices.map { i -> truths.map { it[i] }.average() }
            out.appendLine("-- " + config.label + ": truth best x%.2f, its benefit %+.1f".format(strengths[mean.indices.maxBy { mean[it] }], mean.max() - mean[strengths.indexOf(1.0)]))
            for (design in listOf(currentDesign, wider(175.0), wider(200.0), newDesign)) {
                val runs = parallelList(n) { s -> chain(config, design, 900L + s) }
                val benefit = runs.indices.map { s -> if (runs[s].exported) interpolate(truths[s], runs[s].strength) - truths[s][strengths.indexOf(1.0)] else 0.0 }
                val exportedStrengths = runs.filter { it.exported }.map { it.strength }
                out.appendLine("   %-36s offered %2d/%d  exported %2d/%d  exported strength median %s | benefit mean %+.2f, worst %+.2f".format(
                    design.name, runs.count { it.offered }, n, runs.count { it.exported }, n,
                    if (exportedStrengths.isEmpty()) "-" else "x%.2f".format(median(exportedStrengths)), benefit.average(), benefit.min()))
            }
            write("chain-strength-search.txt", out.toString())
        }
    }

    private fun interpolate(scores: List<Double>, strength: Double): Double {
        if (strength <= strengths.first()) return scores.first()
        if (strength >= strengths.last()) return scores.last()
        val i = strengths.indexOfFirst { it > strength } - 1
        val t = (strength - strengths[i]) / (strengths[i + 1] - strengths[i])
        return scores[i] + (scores[i + 1] - scores[i]) * t
    }

    @Test
    fun blockSearch() {
        val designs = listOf("alternating 24" to Pair(1, 24), "blocks of 3, 24" to Pair(3, 24), "blocks of 4, 24" to Pair(4, 24), "blocks of 3, 36" to Pair(3, 36))
        val out = StringBuilder("== strength search for micro + flash: mix ${mix.joinToString("/") { it.id }}, flash weight 35, strengths 0.7-2.0, $seeds seeds; truth = 24 rounds per fixed strength ==\n")
        out.appendLine("benefit = truth score at the found strength minus truth score with no curve (interpolated in the truth table of the same seed)")
        for (config in configs) {
            val truths = (1..seeds).map { truth(config, it.toLong()) }
            val mean = strengths.indices.map { i -> truths.map { it[i] }.average() }
            out.appendLine("-- ${config.label}: truth " + strengths.indices.joinToString(" ") { "x%.2f=%.1f".format(strengths[it], mean[it]) } + " | best x%.2f".format(strengths[mean.indices.maxBy { mean[it] }]))
            for ((name, design) in designs) {
                val found = parallelList(seeds) { s -> search(config, design.first, design.second, 500L + s) }
                val benefit = found.indices.map { s -> interpolate(truths[s], found[s]) - truths[s][strengths.indexOf(1.0)] }
                out.appendLine("   %-16s found median x%.2f (p25 x%.2f, p75 x%.2f) | benefit mean %+.2f, worst %+.2f".format(
                    name, median(found), percentileOf(found, 0.25), percentileOf(found, 0.75), benefit.average(), benefit.min()))
            }
            write("block-strength-search.txt", out.toString())
        }
    }
}
