package dev.veqzo.aimtune.core.harness

import dev.veqzo.aimtune.core.sim.SimulatedPlayer
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/** What an estimator recovered and how many rounds it spent. */
data class Estimate(val optimumCm360: Double, val roundsUsed: Int)

/**
 * Anything that finds the best cm/360 for a simulated player by playing rounds. The baseline below
 * stands in until the Bayesian optimizer (M5) exists; it plugs in here with no test changes.
 */
fun interface SensitivityEstimator {
    fun estimate(player: SimulatedPlayer, seed: Long): Estimate
}

/** Fixed grid, equal rounds per point, parabola through the best mean score. */
class GridSweepEstimator(
    private val lo: Double = 20.0,
    private val hi: Double = 140.0,
    private val points: Int = 9,
    private val roundsEach: Int = 4,
    private val roundSeconds: Int = 45,
) : SensitivityEstimator {
    override fun estimate(player: SimulatedPlayer, seed: Long): Estimate {
        val grid = geometricGrid(lo, hi, points)
        val means = meanScores(player, grid, roundsEach, seed, roundSeconds)
        return Estimate(parabolicOptimum(grid, means), points * roundsEach)
    }
}

/** Multiplicative error of [estimate] against [truth]: 1.0 is exact, 1.2 is 20 % off in either direction. */
fun errorFactor(estimate: Double, truth: Double): Double = exp(abs(ln(estimate / truth)))

data class ConvergenceCase(val label: String, val player: SimulatedPlayer, val truthCm360: Double)

data class ConvergenceResult(
    val case: ConvergenceCase,
    val errorFactors: List<Double>,
    val roundsUsed: Int,
    val tolerance: Double,
) {
    val rate: Double get() = errorFactors.count { it <= tolerance }.toDouble() / errorFactors.size
    val medianError: Double get() = errorFactors.sorted()[errorFactors.size / 2]
}

fun runConvergence(case: ConvergenceCase, estimator: SensitivityEstimator, seeds: List<Long>, tolerance: Double): ConvergenceResult {
    val estimates = seeds.map { estimator.estimate(case.player, it) }
    return ConvergenceResult(case, estimates.map { errorFactor(it.optimumCm360, case.truthCm360) }, estimates.first().roundsUsed, tolerance)
}

fun formatReport(title: String, results: List<ConvergenceResult>): String = buildString {
    appendLine(title)
    appendLine("%-34s %8s %9s %10s %7s".format("case", "rate", "median x", "rounds", "tol x"))
    for (r in results) {
        appendLine("%-34s %7.0f%% %9.2f %10d %7.2f".format(r.case.label, r.rate * 100, r.medianError, r.roundsUsed, r.tolerance))
    }
}

/** Prints the report and saves it under the module's build directory for later comparison. */
fun publishReport(fileName: String, text: String) {
    println(text)
    val dir = Path.of("build", "reports", "harness")
    Files.createDirectories(dir)
    Files.writeString(dir.resolve(fileName), text)
}
