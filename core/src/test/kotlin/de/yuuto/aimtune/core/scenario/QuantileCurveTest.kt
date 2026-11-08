package de.yuuto.aimtune.core.scenario

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class QuantileCurveTest {
    private val curve = QuantileCurve(listOf(QuantilePoint(0.0, 4.0), QuantilePoint(0.5, 18.0), QuantilePoint(1.0, 60.0)))

    @Test
    fun `values between the points follow straight lines, and share and value are inverse`() {
        assertEquals(4.0, curve.valueAt(0.0))
        assertEquals(11.0, curve.valueAt(0.25), 1e-12)
        assertEquals(60.0, curve.valueAt(1.0))
        for (share in listOf(0.0, 0.1, 0.5, 0.77, 1.0)) assertEquals(share, curve.shareBelow(curve.valueAt(share)), 1e-12)
        assertEquals(0.0, curve.shareBelow(1.0))
        assertEquals(1.0, curve.shareBelow(100.0))
    }

    @Test
    fun `draws reproduce the quantiles`() {
        val random = Random(5)
        val draws = List(40_000) { curve.draw(random) }.sorted()
        assertEquals(18.0, draws[draws.size / 2], 0.3)
        assertEquals(11.0, draws[draws.size / 4], 0.3)
        assertEquals(4.0, draws.first(), 0.1)
    }

    @Test
    fun `curves without both ends or with falling values are rejected`() {
        assertFailsWith<IllegalArgumentException> { QuantileCurve(listOf(QuantilePoint(0.1, 4.0), QuantilePoint(1.0, 9.0))) }
        assertFailsWith<IllegalArgumentException> { QuantileCurve(listOf(QuantilePoint(0.0, 4.0), QuantilePoint(1.0, 3.0))) }
        assertFailsWith<IllegalArgumentException> { QuantileCurve(listOf(QuantilePoint(0.0, 0.0), QuantilePoint(1.0, 3.0))) }
        assertFailsWith<IllegalArgumentException> { curve.valueAt(1.5) }
    }
}
