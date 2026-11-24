package dev.veqzo.aimtune.core.optimizer

import kotlin.math.sqrt

/**
 * Lower-triangular Cholesky factor of a symmetric positive definite matrix, stored row-major.
 * The matrices here are at most a few hundred rows, so a plain implementation is enough
 * (ADR 0004 originally named EJML; see the amendment there).
 */
internal class Cholesky private constructor(val size: Int, private val l: DoubleArray) {
    /** Solves `L x = b` (forward substitution). */
    fun solveLower(b: DoubleArray): DoubleArray {
        require(b.size == size) { "size mismatch" }
        val x = DoubleArray(size)
        for (i in 0 until size) {
            var sum = b[i]
            for (k in 0 until i) sum -= l[i * size + k] * x[k]
            x[i] = sum / l[i * size + i]
        }
        return x
    }

    /** Solves `Lᵀ x = b` (back substitution). */
    private fun solveUpper(b: DoubleArray): DoubleArray {
        val x = DoubleArray(size)
        for (i in size - 1 downTo 0) {
            var sum = b[i]
            for (k in i + 1 until size) sum -= l[k * size + i] * x[k]
            x[i] = sum / l[i * size + i]
        }
        return x
    }

    /** Solves `A x = b` for the factored `A = L Lᵀ`. */
    fun solve(b: DoubleArray): DoubleArray = solveUpper(solveLower(b))

    /** `log det A`. */
    fun logDeterminant(): Double {
        var sum = 0.0
        for (i in 0 until size) sum += kotlin.math.ln(l[i * size + i])
        return 2.0 * sum
    }

    /** `L v` for sampling: turns independent standard normals into a draw with covariance `A`. */
    fun multiplyLower(v: DoubleArray): DoubleArray {
        val out = DoubleArray(size)
        for (i in 0 until size) {
            var sum = 0.0
            for (k in 0..i) sum += l[i * size + k] * v[k]
            out[i] = sum
        }
        return out
    }

    companion object {
        /**
         * Factors [a] (row-major, `size x size`). Adds growing diagonal jitter if the matrix is
         * numerically not positive definite; returns null if even large jitter fails.
         */
        fun factor(a: DoubleArray, size: Int): Cholesky? {
            require(a.size == size * size) { "matrix must be size x size" }
            var jitter = 0.0
            repeat(MAX_ATTEMPTS) {
                tryFactor(a, size, jitter)?.let { return Cholesky(size, it) }
                jitter = if (jitter == 0.0) INITIAL_JITTER else jitter * JITTER_GROWTH
            }
            return null
        }

        private fun tryFactor(a: DoubleArray, size: Int, jitter: Double): DoubleArray? {
            val l = DoubleArray(size * size)
            for (i in 0 until size) {
                for (j in 0..i) {
                    var sum = a[i * size + j] + if (i == j) jitter else 0.0
                    for (k in 0 until j) sum -= l[i * size + k] * l[j * size + k]
                    if (i == j) {
                        if (sum <= MIN_PIVOT) return null
                        l[i * size + i] = sqrt(sum)
                    } else {
                        l[i * size + j] = sum / l[j * size + j]
                    }
                }
            }
            return l
        }

        private const val MAX_ATTEMPTS = 5
        private const val INITIAL_JITTER = 1e-10
        private const val JITTER_GROWTH = 100.0
        private const val MIN_PIVOT = 1e-14
    }
}
