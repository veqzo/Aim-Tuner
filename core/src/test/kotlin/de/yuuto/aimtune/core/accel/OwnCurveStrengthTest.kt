package de.yuuto.aimtune.core.accel

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The blind strength search on top of the player's own curve (docs/FIND-CURVE-REDESIGN.md step 2): the strength scales the rise of that curve. */
class OwnCurveStrengthTest {
    private val base = RawAccelProfile(
        "default", RawAccelArgs(RawAccelMode.CLASSIC, gain = true, inputOffset = 10.0, capX = 62.5, capY = 1.6, capMode = RawAccelCapMode.IN_OUT), 600.0, 800.0,
    )
    private val own = OwnCurveStrength(base, 800.0)
    private val low = OwnCurveStrength.GRID_FROM

    private fun b(v: Double) = base.planningGain(v, 800.0)

    @Test
    fun `a strength of one is the player's curve itself`() {
        assertSame(base, own.model(1.0))
        assertSame(base, own.model(1.01), "within the neutral band")
    }

    @Test
    fun `a strength scales the rise of the curve on the log scale`() {
        for (s in listOf(0.6, 0.85, 1.3, 1.8)) {
            val curve = own.model(s) as LookupCurve
            for (v in listOf(2.0, 8.0, 15.0, 30.0, 60.0, 120.0)) {
                val expected = b(low) * exp(s * ln(b(v) / b(low)))
                assertEquals(expected, curve.multiplier(v), expected * 0.012, "s=$s at $v in/s")
            }
        }
    }

    @Test
    fun `a stronger setting rises more and a weaker one less`() {
        val weak = own.model(0.6) as LookupCurve
        val strong = own.model(1.8) as LookupCurve
        assertTrue(strong.multiplier(40.0) > b(40.0) && b(40.0) > weak.multiplier(40.0))
        assertEquals(weak.multiplier(1.0), strong.multiplier(1.0), 1e-6, "slow speeds are untouched")
    }

    @Test
    fun `the strength is read back from the played table, other curves are no strength of it`() {
        for (s in listOf(0.55, 0.9, 1.25, 1.9)) assertEquals(s, assertNotNull(own.strengthOf(own.model(s) as LookupCurve)), 1e-3)
        assertNull(own.strengthOf(LookupCurve(listOf(1.0, 10.0, 50.0), listOf(1.0, 1.1, 1.3))))
        assertNull(own.strengthOf(NaturalCurve(0.05, 1.3)))
        assertNull(OwnCurveStrength(RawAccelProfile("default", base.args.copy(capY = 1.8), 600.0, 800.0), 800.0).strengthOf(own.model(1.3) as LookupCurve), "another base")
    }

    @Test
    fun `the offer is the curve of the strength and the search covers half to double`() {
        val offer = own.offer(1.4)
        assertEquals(1.0, offer.sensitivityFactor)
        assertEquals(own.model(1.4), offer.curve)
        assertEquals(OwnCurveStrength.MIN * 100.0, own.searchRange.minCm360, 1e-9)
        assertEquals(OwnCurveStrength.MAX * 100.0, own.searchRange.maxCm360, 1e-9)
        assertTrue(abs(OwnCurveStrength.MIN * OwnCurveStrength.MAX - 1.0) < 1e-9, "symmetric around 1 on the log scale")
    }
}
