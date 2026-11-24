package dev.veqzo.aimtune.core.accel

import kotlin.math.abs

/** Result of a minimization: the best point and its value. */
class Minimum(val point: DoubleArray, val value: Double)

/**
 * Nelder-Mead simplex minimization for the handful of parameters of an acceleration curve. The
 * objective may be flat or kinked (parameters are clamped inside it), which the method tolerates.
 */
object NelderMead {
    fun minimize(
        start: DoubleArray,
        step: Double = 0.4,
        maxIterations: Int = 600,
        tolerance: Double = 1e-10,
        objective: (DoubleArray) -> Double,
    ): Minimum {
        val n = start.size
        val simplex = MutableList(n + 1) { i -> start.copyOf().also { if (i > 0) it[i - 1] += step } }
        val values = simplex.map(objective).toMutableList()
        repeat(maxIterations) {
            val order = simplex.indices.sortedBy { values[it] }
            val sortedPoints = order.map { simplex[it] }
            val sortedValues = order.map { values[it] }
            simplex.clear(); simplex.addAll(sortedPoints)
            values.clear(); values.addAll(sortedValues)
            if (abs(values.last() - values.first()) < tolerance) return Minimum(simplex.first(), values.first())

            val centroid = DoubleArray(n) { d -> (0 until n).sumOf { simplex[it][d] } / n }
            fun along(factor: Double) = DoubleArray(n) { d -> centroid[d] + factor * (simplex[n][d] - centroid[d]) }

            val reflected = along(-1.0)
            val reflectedValue = objective(reflected)
            when {
                reflectedValue < values[0] -> {
                    val expanded = along(-2.0)
                    val expandedValue = objective(expanded)
                    if (expandedValue < reflectedValue) replaceWorst(simplex, values, expanded, expandedValue) else replaceWorst(simplex, values, reflected, reflectedValue)
                }
                reflectedValue < values[n - 1] -> replaceWorst(simplex, values, reflected, reflectedValue)
                else -> {
                    val contracted = if (reflectedValue < values[n]) along(-0.5) else along(0.5)
                    val contractedValue = objective(contracted)
                    if (contractedValue < minOf(values[n], reflectedValue)) {
                        replaceWorst(simplex, values, contracted, contractedValue)
                    } else {
                        for (i in 1..n) {
                            simplex[i] = DoubleArray(n) { d -> simplex[0][d] + 0.5 * (simplex[i][d] - simplex[0][d]) }
                            values[i] = objective(simplex[i])
                        }
                    }
                }
            }
        }
        val best = values.indices.minBy { values[it] }
        return Minimum(simplex[best], values[best])
    }

    private fun replaceWorst(simplex: MutableList<DoubleArray>, values: MutableList<Double>, point: DoubleArray, value: Double) {
        simplex[simplex.lastIndex] = point
        values[values.lastIndex] = value
    }
}
