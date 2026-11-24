package dev.veqzo.aimtune.core.optimizer

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GaussianProcessTest {
    private fun f(x: Double) = 50.0 + 10.0 * sin(2.0 * x)

    private fun observations(xs: List<Double>, session: Long = 0, shift: Double = 0.0) =
        xs.map { Observation(session, exp(it), f(it) + shift) }

    private val hyper = GpHyperParams(lengthscale = 0.5, signalVariance = 1.0, noiseVariance = 0.01, sessionVariance = 0.0)

    @Test
    fun `cholesky solves a linear system and reports the determinant`() {
        val a = doubleArrayOf(4.0, 2.0, 2.0, 3.0)
        val chol = assertNotNull(Cholesky.factor(a, 2))
        val x = chol.solve(doubleArrayOf(2.0, 1.0))
        assertEquals(0.5, x[0], 1e-12)
        assertEquals(0.0, x[1], 1e-12)
        assertEquals(ln(8.0), chol.logDeterminant(), 1e-12)
    }

    @Test
    fun `a matrix that is not positive definite is rejected`() {
        assertNull(Cholesky.factor(doubleArrayOf(1.0, 2.0, 2.0, 1.0), 2))
    }

    @Test
    fun `the posterior follows the data and is unsure away from it`() {
        val xs = listOf(0.0, 0.4, 0.8, 1.2, 1.6, 2.0)
        val gp = assertNotNull(GaussianProcess.fit(observations(xs), hyper))
        val grid = doubleArrayOf(0.2, 1.0, 5.0)
        val post = gp.posterior(grid)
        assertEquals(f(0.2), post.mean[0], 2.0)
        assertEquals(f(1.0), post.mean[1], 2.0)
        assertTrue(post.sd(2) > 3 * post.sd(1), "far from the data the sd must be larger: ${post.sd(2)} vs ${post.sd(1)}")
    }

    @Test
    fun `a whole session being better or worse moves the estimate less with session offsets`() {
        val xs = listOf(0.0, 0.4, 0.8, 1.2, 1.6, 2.0)
        val first = observations(xs, session = 1)
        fun estimate(h: GpHyperParams, shift: Double): Double {
            val rounds = first + observations(xs.map { it + 0.2 }, session = 2, shift = shift)
            return assertNotNull(GaussianProcess.fit(rounds, h)).posterior(doubleArrayOf(1.0)).mean[0]
        }
        val withOffsets = GpHyperParams(0.5, 1.0, 0.05, sessionVariance = 1.0)
        val without = withOffsets.copy(sessionVariance = 0.0)
        val movedWith = kotlin.math.abs(estimate(withOffsets, 8.0) - estimate(withOffsets, 0.0))
        val movedWithout = kotlin.math.abs(estimate(without, 8.0) - estimate(without, 0.0))
        assertTrue(movedWith < movedWithout, "with offsets $movedWith, without $movedWithout")
    }

    @Test
    fun `the marginal likelihood prefers the lengthscale that generated the data`() {
        val xs = List(14) { it * 0.15 }
        val data = observations(xs)
        fun lml(ls: Double) = assertNotNull(GaussianProcess.fit(data, hyper.copy(lengthscale = ls))).logMarginalLikelihood
        assertTrue(lml(0.5) > lml(0.02), "tiny lengthscale must fit worse")
        assertTrue(lml(0.5) > lml(8.0), "huge lengthscale must fit worse")
    }

    @Test
    fun `identical x values with noise still factor`() {
        val same = List(5) { Observation(0, 40.0, 50.0 + it) }
        assertNotNull(GaussianProcess.fit(same, hyper))
    }
}
