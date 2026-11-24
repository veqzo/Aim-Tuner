package dev.veqzo.aimtune.core.harness.review

import dev.veqzo.aimtune.core.harness.TEST_DPI
import dev.veqzo.aimtune.core.harness.geometricGrid
import dev.veqzo.aimtune.core.metrics.computeRoundMetrics
import dev.veqzo.aimtune.core.scenario.FlickConfig
import dev.veqzo.aimtune.core.scenario.FlickScenario
import dev.veqzo.aimtune.core.scenario.Scenario
import dev.veqzo.aimtune.core.score.MetricWeights
import dev.veqzo.aimtune.core.score.ScoreCalculator
import dev.veqzo.aimtune.core.score.ScoreMetric
import dev.veqzo.aimtune.core.score.scoreInputs
import dev.veqzo.aimtune.core.sim.HeadlessRound
import dev.veqzo.aimtune.core.sim.SimulatedPlayer
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.IntStream
import kotlin.math.exp
import kotlin.math.ln

/** Helpers for the review experiments (docs/history/REVIEW.md). They only run when AIMTUNE_REVIEW is set. */
const val ROUND_SECONDS = 45
const val NANOS_PER_SECOND = 1_000_000_000L

/** Runs [body] for 0 until [count] on all cores and returns the results in order. */
fun <T> parallelList(count: Int, body: (Int) -> T): List<T> {
    val out = arrayOfNulls<Any>(count)
    IntStream.range(0, count).parallel().forEach { out[it] = body(it) }
    @Suppress("UNCHECKED_CAST")
    return out.toList() as List<T>
}

fun flickScenario(radius: Double, seed: Long, seconds: Int = ROUND_SECONDS): Scenario =
    FlickScenario(FlickConfig(seconds * NANOS_PER_SECOND, targetRadius = radius), seed)

/** Metric inputs of [roundsEach] rounds at each grid point; the scenario factory decides the geometry. */
fun sweepInputs(
    player: SimulatedPlayer,
    grid: List<Double>,
    roundsEach: Int,
    seed: Long,
    scenarioFor: (Long) -> Scenario = { flickScenario(0.25, it) },
): List<List<Map<ScoreMetric, Double>>> = grid.indices.map { gi ->
    parallelList(roundsEach) { r ->
        val roundSeed = seed * 1_000_003L + gi * 1_009L + r
        val offset = (gi * roundsEach + r) * ROUND_SECONDS * NANOS_PER_SECOND
        val recording = HeadlessRound.run(player, TEST_DPI, grid[gi], roundSeed, ROUND_SECONDS, offset, scenarioFor(roundSeed))
        computeRoundMetrics(recording).scoreInputs()
    }
}

/** Mean score per grid point when all rounds are scored together under [weights]. */
fun meanScoresFor(inputs: List<List<Map<ScoreMetric, Double>>>, weights: MetricWeights): List<Double> {
    val scores = ScoreCalculator(weights).scoreRounds(inputs.flatten())
    var i = 0
    return inputs.map { group -> group.indices.map { scores[i++] }.filterNotNull().average() }
}

/** Least-squares polynomial of [degree] through (ln cm, mean) pairs, evaluated at any cm: a smooth ground-truth curve. */
class SmoothCurve(grid: List<Double>, means: List<Double>, degree: Int = 4) {
    private val coefficients: DoubleArray
    private val center = ln(grid.first() * grid.last()) / 2
    private val half = ln(grid.last() / grid.first()) / 2

    init {
        val n = degree + 1
        val a = Array(n) { DoubleArray(n + 1) }
        for (k in grid.indices) {
            val x = (ln(grid[k]) - center) / half
            for (i in 0 until n) {
                for (j in 0 until n) a[i][j] += Math.pow(x, (i + j).toDouble())
                a[i][n] += means[k] * Math.pow(x, i.toDouble())
            }
        }
        for (c in 0 until n) {
            val p = (c until n).maxBy { Math.abs(a[it][c]) }
            val tmp = a[c]; a[c] = a[p]; a[p] = tmp
            for (r in 0 until n) if (r != c) {
                val f = a[r][c] / a[c][c]
                for (j in c..n) a[r][j] -= f * a[c][j]
            }
        }
        coefficients = DoubleArray(n) { a[it][n] / a[it][it] }
    }

    fun at(cm360: Double): Double {
        val x = (ln(cm360) - center) / half
        return coefficients.indices.sumOf { coefficients[it] * Math.pow(x, it.toDouble()) }
    }

    /** Argmax on a fine grid between [lo] and [hi]. */
    fun argmax(lo: Double, hi: Double): Double = geometricGrid(lo, hi, 400).maxBy { at(it) }
}

fun write(name: String, text: String) {
    println(text)
    val dir = Path.of("build", "reports", "harness")
    Files.createDirectories(dir)
    Files.writeString(dir.resolve(name), text)
}

fun errorFactorOf(estimate: Double, truth: Double): Double = exp(Math.abs(ln(estimate / truth)))
