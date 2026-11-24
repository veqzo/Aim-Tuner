package dev.veqzo.aimtune.core.harness.review

import dev.veqzo.aimtune.core.harness.geometricGrid
import dev.veqzo.aimtune.core.harness.parabolicOptimum
import dev.veqzo.aimtune.core.harness.playBlindTest
import dev.veqzo.aimtune.core.score.MetricWeights
import dev.veqzo.aimtune.core.score.ScoreMetric
import dev.veqzo.aimtune.core.sim.SimulatedPlayer
import dev.veqzo.aimtune.core.units.SearchRange
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.math.exp
import kotlin.math.ln
import kotlin.test.Test

/** Review experiments: does the score definition, the target size or session drift bias the recommended sensitivity? */
@EnabledIfEnvironmentVariable(named = "AIMTUNE_REVIEW", matches = ".+")
class ReviewBiasTest {
    private val grid = geometricGrid(20.0, 160.0, 17)

    private fun weights(vararg pairs: Pair<ScoreMetric, Double>) = MetricWeights(mapOf(*pairs))

    private val weightSets = listOf(
        "accuracy only" to weights(ScoreMetric.ACCURACY to 1.0),
        "time to hit only" to weights(ScoreMetric.TIME_TO_HIT to 1.0),
        "default 40/30" to MetricWeights.DEFAULT,
        "speed heavy 20/70" to weights(ScoreMetric.ACCURACY to 20.0, ScoreMetric.TIME_TO_HIT to 70.0),
        "precision heavy 80/20" to weights(ScoreMetric.ACCURACY to 80.0, ScoreMetric.TIME_TO_HIT to 20.0),
    )

    @Test
    fun weightsAndTargetSize() {
        val out = StringBuilder()
        out.appendLine("== Optimum (cm/360, parabola through the best reference point) per metric weights, 17 grid points x 30 rounds, flick radius 0.25 m ==")
        val players = listOf("default player" to SimulatedPlayer().withOptimum(50.0), "noisy player x1.6" to SimulatedPlayer(noiseLevel = 1.6).withOptimum(50.0))
        for ((label, player) in players) {
            val inputs = sweepInputs(player, grid, 30, 7)
            out.appendLine(label)
            for ((name, w) in weightSets) {
                val means = meanScoresFor(inputs, w)
                out.appendLine("  %-26s optimum %6.1f   score at 30/50/80/120 cm: %s".format(name, parabolicOptimum(grid, means), listOf(30.0, 50.0, 80.0, 120.0).joinToString { "%.0f".format(SmoothCurve(grid, means).at(it)) }))
            }
        }
        out.appendLine()
        out.appendLine("== Optimum vs. target radius (default weights; default player; angular diameter at 12 m in brackets) ==")
        for (radius in listOf(0.08, 0.10, 0.15, 0.25, 0.5, 1.0)) {
            val inputs = sweepInputs(players[0].second, grid, 30, 11) { flickScenario(radius, it) }
            val means = meanScoresFor(inputs, MetricWeights.DEFAULT)
            val acc = inputs.map { g -> g.mapNotNull { it[ScoreMetric.ACCURACY] }.average() }
            val tth = inputs.map { g -> g.mapNotNull { it[ScoreMetric.TIME_TO_HIT] }.average() }
            val atOpt = grid.indices.minBy { Math.abs(ln(grid[it] / parabolicOptimum(grid, means))) }
            out.appendLine(
                "  radius %.2f m (%.2f deg at 12 m): optimum %6.1f cm/360 | at optimum accuracy %.0f%%, mean time to hit %.0f ms | accuracy at 20/50/140 cm: %s".format(
                    radius, 2 * Math.toDegrees(Math.atan(radius / 12.0)), parabolicOptimum(grid, means), 100 * acc[atOpt], tth[atOpt],
                    listOf(0, 4, 16).joinToString { "%.0f%%".format(100 * acc[it]) },
                ),
            )
            for ((name, w) in weightSets.filter { it.first.startsWith("accuracy") || it.first.startsWith("time") }) {
                out.appendLine("      %-18s optimum %6.1f".format(name, parabolicOptimum(grid, meanScoresFor(inputs, w))))
            }
        }
        val realistic = sweepInputs(players[0].second, grid, 30, 13) {
            dev.veqzo.aimtune.core.scenario.FlickScenario(
                dev.veqzo.aimtune.core.scenario.FlickConfig(ROUND_SECONDS * NANOS_PER_SECOND, geometry = dev.veqzo.aimtune.core.scenario.TrainerGeometry.DEFAULT), it,
            )
        }
        val realisticMeans = meanScoresFor(realistic, MetricWeights.DEFAULT)
        out.appendLine()
        out.appendLine("== Flick with the profile geometry (head-sized targets 0.4-1.9 deg, flick-angle mix): optimum %.1f cm/360 ==".format(parabolicOptimum(grid, realisticMeans)))
        out.appendLine("  score at 30/50/80/120/160 cm: " + listOf(30.0, 50.0, 80.0, 120.0, 160.0).joinToString { "%.0f".format(SmoothCurve(grid, realisticMeans).at(it)) })
        write("review-bias.txt", out.toString())
    }

    @Test
    fun driftAndSessions() {
        val out = StringBuilder()
        val seeds = (System.getenv("AIMTUNE_SEEDS") ?: "40").toInt()
        val base = SimulatedPlayer().withOptimum(50.0)
        val truthMeans = meanScoresFor(sweepInputs(base, grid, 30, 3), MetricWeights.DEFAULT)
        val truth = SmoothCurve(grid, truthMeans).argmax(20.0, 140.0)
        out.appendLine("== Drift: 30-round blind tests, %d seeds, truth optimum %.1f cm/360 (no-drift reference sweep) ==".format(seeds, truth))
        out.appendLine("%-44s %8s %9s %9s %12s %9s".format("condition", "median x", "within1.3", "mean bias", "mean conf", "drift seen"))
        val range = SearchRange(20.0, 140.0)
        val rounds = 30

        fun run(label: String, playerFor: (Int) -> SimulatedPlayer, sessionOf: (Int) -> Long = { 0L }) {
            val results = parallelList(seeds) { s -> checkNotNull(playBlindTest(base, range, rounds, 5000L + s, playerFor = playerFor, sessionOf = sessionOf).result) }
            val errors = results.map { errorFactorOf(it.optimumCm360, truth) }.sorted()
            val bias = results.map { ln(it.optimumCm360 / truth) }.average()
            out.appendLine(
                "%-44s %8.2f %8.0f%% %+8.0f%% %11.0f%% %8.0f%%".format(label, errors[errors.size / 2], 100.0 * errors.count { it <= 1.3 } / errors.size, 100 * (exp(bias) - 1), results.map { it.confidencePercent }.average(), 100.0 * results.count { it.driftDetected } / results.size),
            )
        }
        run("no drift", { base })
        run("fatigue 3 %/min (x1.9 slower+noisier at end)", { base.copy(fatiguePerMinute = 0.03) })
        run("fatigue 6 %/min", { base.copy(fatiguePerMinute = 0.06) })
        run("learning: noise x1.5 -> x0.7 over the run", { base.copy(noiseLevel = 1.5 - 0.8 * it / (rounds - 1)) })
        run("learning x2 (noise x2.0 -> x0.6)", { base.copy(noiseLevel = 2.0 - 1.4 * it / (rounds - 1)) })
        run("3 sessions, middle one a bad day (noise x1.4)", { if (it in 10..19) base.copy(noiseLevel = 1.4) else base }, { (it / 10).toLong() + 1 })
        run("3 sessions, sessions get better (x1.4,1.0,0.7)", { base.copy(noiseLevel = listOf(1.4, 1.0, 0.7)[it / 10]) }, { (it / 10).toLong() + 1 })
        run("3 sessions, same quality", { base }, { (it / 10).toLong() + 1 })
        write("review-drift.txt", out.toString())
    }
}
