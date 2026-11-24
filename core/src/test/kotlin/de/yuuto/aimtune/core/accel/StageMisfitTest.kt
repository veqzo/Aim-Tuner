package de.yuuto.aimtune.core.accel

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StageMisfitTest {
    private val base = RawAccelProfile(
        "default", RawAccelArgs(RawAccelMode.CLASSIC, gain = true, inputOffset = 10.0, capX = 62.5, capY = 1.6, capMode = RawAccelCapMode.IN_OUT), 600.0, 800.0,
    )
    private val family = FeltStepFamily(base, 800.0)
    private val slow = 9.0
    private val fast = 22.0
    private val stage = family.table(0.08, slow, fast)

    private fun b(v: Double) = base.planningGain(v, 800.0)
    private fun played(v: Double) = stage.multiplier(v)

    private fun gaussian(random: Random) = sqrt(-2.0 * ln(1.0 - random.nextDouble())) * cos(2.0 * Math.PI * random.nextDouble())

    /** First reaches of a hand that wants [wanted] while the stage delivers [played]. */
    private fun analysis(wanted: (Double) -> Double, noise: Double = 0.10, lo: Double = 3.5, hi: Double = 25.0): AccelAnalysis {
        val random = Random(2)
        val samples = List(1200) {
            val v = exp(random.nextDouble(ln(lo), ln(hi)))
            MovementSample(v, exp(ln(played(v)) - ln(wanted(v)) + noise * gaussian(random)) - 1.0)
        }
        return AccelAnalysis(samples, delivered = ::played)
    }

    @Test
    fun `a hand that wants the curve without the step reads as asking for less at the fast end`() {
        val m = assertNotNull(stageMisfit(analysis(::b), ::played, slow, fast))
        assertTrue(m.contrast < -0.03 && m.contrast > -0.09, "contrast ${m.contrast}")
        assertTrue(m.contrast < -2 * m.standardError, "se ${m.standardError}")
    }

    @Test
    fun `a hand that has adapted to the stage reads as zero`() {
        val m = assertNotNull(stageMisfit(analysis(::played), ::played, slow, fast))
        assertTrue(abs(m.contrast) < 2.5 * m.standardError, "contrast ${m.contrast}, se ${m.standardError}")
    }

    @Test
    fun `speeds that stay on one side of the step give no contrast`() {
        assertNull(stageMisfit(analysis(::b, lo = 3.5, hi = 7.0), ::played, slow, fast))
    }
}
