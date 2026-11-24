package dev.veqzo.aimtune.core.harness.review

import dev.veqzo.aimtune.core.accel.AccelCurve
import dev.veqzo.aimtune.core.accel.NaturalCurve
import dev.veqzo.aimtune.core.harness.TEST_DPI
import dev.veqzo.aimtune.core.metrics.RoundMetrics
import dev.veqzo.aimtune.core.metrics.computeRoundMetrics
import dev.veqzo.aimtune.core.scenario.ScenarioKind
import dev.veqzo.aimtune.core.scenario.TrainerGeometry
import dev.veqzo.aimtune.core.score.MetricWeights
import dev.veqzo.aimtune.core.score.ScoreCalculator
import dev.veqzo.aimtune.core.score.ScoreMetric
import dev.veqzo.aimtune.core.score.scoreInputs
import dev.veqzo.aimtune.core.sim.HeadlessRound
import dev.veqzo.aimtune.core.sim.SimulatedPlayer
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.math.exp
import kotlin.test.Test

/**
 * Two-way curve sweep: lower gain at slow hand speeds (factor s) and higher gain at fast speeds (ratio r), scored on a
 * micro / flash / flick mix with head-sized targets. Gated: set AIMTUNE_REVIEW; AIMTUNE_SEEDS (default 3), AIMTUNE_CYCLES (default 4).
 */
@EnabledIfEnvironmentVariable(named = "AIMTUNE_REVIEW", matches = ".+")
class TwoWayCurveSweepTest {
    private val seeds = (System.getenv("AIMTUNE_SEEDS") ?: "3").toInt()
    private val cycles = (System.getenv("AIMTUNE_CYCLES") ?: "4").toInt()
    private val geometry = TrainerGeometry.DEFAULT
    private val mix = listOf(ScenarioKind.MICRO, ScenarioKind.FLASH, ScenarioKind.FLICK, ScenarioKind.FLASH, ScenarioKind.MICRO, ScenarioKind.FLICK)
    private val weights = MetricWeights(mapOf(ScoreMetric.ACCURACY to 40.0, ScoreMetric.TIME_TO_HIT to 30.0, ScoreMetric.FLASH_DODGE to 35.0))

    private val slowFactors = listOf(0.70, 0.80, 0.90, 1.00)
    private val ratios = listOf(1.0, 1.25, 1.5, 1.75, 2.0)

    private data class Cell(val s: Double, val r: Double)

    private val cells = slowFactors.flatMap { s -> ratios.map { Cell(s, it) } } + listOf(Cell(1.0, 0.8), Cell(1.0, 0.9))

    private class Config(val label: String, val model: String, val change: Double)

    private val configs = listOf("DEFAULT", "DECOUPLED", "RANGE", "ADAPTIVE").map { Config("$it flat", it, 0.0) } +
        listOf(Config("DEFAULT rising +15%", "DEFAULT", 0.15), Config("DEFAULT falling -15%", "DEFAULT", -0.15))

    private fun playerFor(config: Config): SimulatedPlayer {
        val base = SimulatedPlayer.forHeadSizedTargets(50.0).copy(gainPreference = { v -> 1.0 + config.change * (1.0 - exp(-v / 6.0)) })
        return when (config.model) {
            "DECOUPLED" -> base.copy(noiseModel = dev.veqzo.aimtune.core.sim.NoiseModel.DECOUPLED)
            "RANGE" -> base.copy(rangeBias = 0.05)
            "ADAPTIVE" -> base.copy(adaptation = dev.veqzo.aimtune.core.sim.HandAdaptation.adaptedTo(base.gainPreference))
            else -> base
        }
    }

    private class Played(val kind: ScenarioKind, val metrics: RoundMetrics)

    private fun playCell(config: Config, cell: Cell, decay: Double, seed: Long): List<Played> {
        val player = playerFor(config)
        val curve: AccelCurve? = if (cell.r == 1.0) null else NaturalCurve(decay, cell.r)
        return (0 until cycles * mix.size).map { i ->
            val kind = mix[i % mix.size]
            val recording = HeadlessRound.of(kind, player, TEST_DPI, 50.0 / cell.s, seed * 1_000_003L + i, 45, i * 45_000_000_000L, curve, geometry)
            Played(kind, computeRoundMetrics(recording))
        }
    }

    private fun median(xs: List<Double>): Double = xs.sorted().let { if (it.isEmpty()) Double.NaN else it[it.size / 2] }

    private class Breakdown(val microAcc: Double, val microTth: Double, val dodge: Double, val flashAcc: Double, val flickTth: Double, val microPeak: Double, val turnPeak: Double)

    private fun breakdown(rounds: List<Played>): Breakdown {
        fun of(kind: ScenarioKind, f: (RoundMetrics) -> Double?) = rounds.filter { it.kind == kind }.mapNotNull { f(it.metrics) }.average()
        fun inPerS(countsPerMs: Double) = countsPerMs * 1000.0 / TEST_DPI
        return Breakdown(
            of(ScenarioKind.MICRO) { it.scoreInputs()[ScoreMetric.ACCURACY] }, of(ScenarioKind.MICRO) { it.meanTimeToHitMs },
            of(ScenarioKind.FLASH) { it.flashDodgeRate }, of(ScenarioKind.FLASH) { it.firstShotAccuracy }, of(ScenarioKind.FLICK) { it.meanTimeToHitMs },
            inPerS(median(rounds.filter { it.kind == ScenarioKind.MICRO }.flatMap { p -> p.metrics.movements.mapNotNull { it.firstPeakSpeedCountsPerMs } })),
            inPerS(median(rounds.filter { it.kind == ScenarioKind.FLASH }.mapNotNull { it.metrics.flashTurnPeakSpeed })),
        )
    }

    @Test
    fun twoWaySweep() {
        val out = StringBuilder("== two-way curve sweep: $seeds seeds, ${cycles * mix.size} rounds per cell, 45 s rounds, 800 dpi, flash weight 35 ==\n")
        for (decay in listOf(0.05, 0.2)) {
            for (config in configs) {
                val sums = DoubleArray(cells.size)
                val pooled = cells.map { mutableListOf<Played>() }
                for (seed in 1..seeds) {
                    val played = parallelList(cells.size) { playCell(config, cells[it], decay, seed.toLong()) }
                    val flat = played.flatten()
                    val scores = ScoreCalculator(weights).scoreRounds(flat.map { it.metrics.scoreInputs() }, flat.map { it.kind.id })
                    var k = 0
                    played.forEachIndexed { ci, cellRounds ->
                        sums[ci] += cellRounds.indices.map { scores[k++] }.filterNotNull().average() / seeds
                        pooled[ci] += cellRounds
                    }
                }
                fun score(c: Cell) = sums[cells.indexOf(c)]
                out.appendLine("-- ${config.label}, decay $decay --")
                out.appendLine("mean score; rows s (slow gain factor), columns r (fast/slow ratio)")
                out.appendLine("s vs r   " + ratios.joinToString("  ") { "%6.2f".format(it) })
                for (s in slowFactors) out.appendLine("%5.2f   ".format(s) + ratios.joinToString("  ") { "%6.2f".format(score(Cell(s, it))) })
                out.appendLine("s=1.00 falling: r=0.80 %.2f, r=0.90 %.2f".format(score(Cell(1.0, 0.8)), score(Cell(1.0, 0.9))))
                val grid = cells.take(20)
                val best = grid.maxBy { score(it) }
                val bestR1 = grid.filter { it.r == 1.0 }.maxBy { score(it) }
                val bestS1 = cells.filter { it.s == 1.0 }.maxBy { score(it) }
                out.appendLine("plain %.2f | best (s=%.2f r=%.2f) %.2f | best r=1 (s=%.2f) %.2f | best s=1 (r=%.2f) %.2f".format(
                    score(Cell(1.0, 1.0)), best.s, best.r, score(best), bestR1.s, score(bestR1), bestS1.r, score(bestS1)))
                out.appendLine("cell                 micro acc  micro tth ms  flash dodge  flash 1st-shot acc  flick tth ms  micro peak in/s  flash turn peak in/s")
                for ((name, c) in listOf("plain" to Cell(1.0, 1.0), "best s=%.2f r=%.2f".format(best.s, best.r) to best)) {
                    val b = breakdown(pooled[cells.indexOf(c)])
                    out.appendLine("%-20s %9.3f  %12.0f  %11.3f  %18.3f  %12.0f  %15.2f  %20.2f".format(name, b.microAcc, b.microTth, b.dodge, b.flashAcc, b.flickTth, b.microPeak, b.turnPeak))
                }
                out.appendLine()
                write("twoway-sweep.txt", out.toString())
            }
        }
    }
}
