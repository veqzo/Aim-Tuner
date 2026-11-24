package dev.veqzo.aimtune.core.harness.review

import dev.veqzo.aimtune.core.harness.TEST_DPI
import dev.veqzo.aimtune.core.harness.geometricGrid
import dev.veqzo.aimtune.core.metrics.computeRoundMetrics
import dev.veqzo.aimtune.core.optimizer.Observation
import dev.veqzo.aimtune.core.optimizer.OptimizationResult
import dev.veqzo.aimtune.core.optimizer.OptimizerConfig
import dev.veqzo.aimtune.core.optimizer.SensitivityOptimizer
import dev.veqzo.aimtune.core.scenario.ScenarioKind
import dev.veqzo.aimtune.core.score.MetricWeights
import dev.veqzo.aimtune.core.score.ScoreCalculator
import dev.veqzo.aimtune.core.score.ScoreMetric
import dev.veqzo.aimtune.core.score.scoreInputs
import dev.veqzo.aimtune.core.sim.HeadlessRound
import dev.veqzo.aimtune.core.sim.SimulatedPlayer
import dev.veqzo.aimtune.core.units.SearchRange
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.test.Test

/** Gate T7: the time model (OptimizerConfig.timeDrift) against the legacy pooled model on drifting players. Raw numbers only. */
@EnabledIfEnvironmentVariable(named = "AIMTUNE_REVIEW", matches = ".+")
class DriftGateTest {
    private val range = SearchRange(20.0, 140.0)
    private val seeds = 40
    private val daysPerSession = 2.0

    /** Copy of playBlindTest's loop (FLICK) that also sets Observation.days = [daysPerSession] * session. */
    private fun play(playerFor: (Int) -> SimulatedPlayer, sessionOf: (Int) -> Long, rounds: Int, seed: Long, config: OptimizerConfig): OptimizationResult? {
        val optimizer = SensitivityOptimizer(range, seed, config)
        val calculator = ScoreCalculator(MetricWeights.DEFAULT)
        val inputs = mutableListOf<Map<ScoreMetric, Double>>()
        val scenarios = mutableListOf<String>()
        val cm360s = mutableListOf<Double>()
        var history = emptyList<Observation>()
        repeat(rounds) { index ->
            val cm360 = optimizer.suggestNext(history).cm360
            val roundSeed = seed * 7_919L + index
            val offset = index * 45 * 1_000_000_000L
            inputs += computeRoundMetrics(HeadlessRound.of(ScenarioKind.FLICK, playerFor(index), TEST_DPI, cm360, roundSeed, 45, offset)).scoreInputs()
            scenarios += ScenarioKind.FLICK.id
            cm360s += cm360
            val scores = calculator.scoreRounds(inputs, scenarios)
            val step = HashMap<Long, Int>()
            history = cm360s.indices.map {
                val s = sessionOf(it)
                val st = checkNotNull(step.merge(s, 1, Int::plus)) - 1
                Observation(s, cm360s[it], scores[it] ?: 50.0, group = scenarios[it].hashCode(), step = st, days = daysPerSession * s)
            }
        }
        return optimizer.analyze(history)
    }


    @Test
    fun driftGate() {
        val sessions = 4
        val perSession = System.getenv("AIMTUNE_ROUNDS_PER_SESSION")?.toIntOrNull() ?: 10
        val out = StringBuilder("== Gate T7 drift: $sessions sessions x $perSession rounds, sessions $daysPerSession days apart, FLICK, $seeds seeds, range 20-140 ==\n")
        out.appendLine("Truth = configured optimum of session 4 (50*(1+d)^3) [sweep: 17-point 20-160 sweep, 20 rounds/point, degree-4 argmax of session 4 player]")
        val grid = geometricGrid(20.0, 160.0, 17)
        val configs = listOf("legacy OptimizerConfig()" to OptimizerConfig(), "timeDrift=true" to OptimizerConfig(timeDrift = true))
        for (d in listOf(0.0, 0.05, 0.10)) {
            fun player(s: Int) = SimulatedPlayer().withOptimum(50.0 * (1.0 + d).pow(s))
            val truth = 50.0 * (1.0 + d).pow(sessions - 1)
            val sweep = SmoothCurve(grid, meanScoresFor(sweepInputs(player(sessions - 1), grid, 20, 77L + sessions), MetricWeights.DEFAULT)).argmax(20.0, 160.0)
            out.appendLine("\n-- d=%.2f: configured truth %.1f, sweep optimum %.1f".format(d, truth, sweep))
            for ((label, config) in configs) {
                val results = parallelList(seeds) { s ->
                    play({ player(it / perSession) }, { (it / perSession).toLong() }, sessions * perSession, 61_000L + s, config)
                }
                val rs = results.filterNotNull()
                fun row(t: Double, name: String) {
                    val err = rs.map { 100.0 * abs(it.optimumCm360 - t) / t }
                    val signed = rs.map { 100.0 * (it.optimumCm360 - t) / t }
                    val cover = rs.count { it.intervalLowCm360 <= t && t <= it.intervalHighCm360 }
                    out.appendLine("   %-26s vs %-6s: abs err median %5.1f%% p90 %5.1f%% | bias median %+5.1f%% | 80%% cover %3.0f%% | mean conf %4.1f | dayDrift %3.0f%% driftDetected %3.0f%% | n=%d".format(
                        label, name, median(err), percentileOf(err, 0.9), median(signed), 100.0 * cover / rs.size, rs.map { it.confidencePercent.toDouble() }.average(),
                        100.0 * rs.count { it.dayDriftDetected } / rs.size, 100.0 * rs.count { it.driftDetected } / rs.size, rs.size))
                }
                row(truth, "config")
                row(sweep, "sweep")
            }
        }
        out.appendLine("\n== Single session (optimum 50), $seeds seeds ==")
        for (rounds in listOf(8, 15, 30)) for ((label, config) in configs) {
            val results = parallelList(seeds) { s -> play({ SimulatedPlayer().withOptimum(50.0) }, { 0L }, rounds, 63_000L + s, config) }
            val rs = results.filterNotNull()
            val factor = rs.map { kotlin.math.exp(abs(ln(it.optimumCm360 / 50.0))) }
            out.appendLine("   %2d rounds %-26s error factor median %.4f | 80%% cover %3.0f%% | optimum sum %.6f | n=%d".format(
                rounds, label, median(factor), 100.0 * rs.count { it.intervalLowCm360 <= 50.0 && 50.0 <= it.intervalHighCm360 } / rs.size, rs.sumOf { it.optimumCm360 }, rs.size))
        }
        write("gate-" + (System.getenv("AIMTUNE_DRIFT_TAG") ?: "t7-drift") + ".txt", out.toString())
    }
}
