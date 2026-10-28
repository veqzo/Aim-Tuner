package de.yuuto.aimtune.core.accel

import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Steps defined by the change the player can feel at their own speeds (docs/FIND-CURVE-REDESIGN.md section 12). */
class FeltStepTest {
    private val base = RawAccelProfile(
        "default", RawAccelArgs(RawAccelMode.CLASSIC, gain = true, inputOffset = 10.0, capX = 62.5, capY = 1.6, capMode = RawAccelCapMode.IN_OUT), 600.0, 800.0,
    )
    private val family = FeltStepFamily(base, 800.0)
    private val own = OwnCurveStrength(base, 800.0)
    private val slow = 9.0
    private val fast = 22.0

    private fun b(v: Double) = base.planningGain(v, 800.0)

    private fun gaussian(random: Random) = sqrt(-2.0 * ln(1.0 - random.nextDouble())) * cos(2.0 * Math.PI * random.nextDouble())

    private fun analysis(noise: Double, lo: Double = 3.5, hi: Double = 25.0): AccelAnalysis {
        val random = Random(1)
        val samples = List(900) {
            val v = exp(random.nextDouble(ln(lo), ln(hi)))
            MovementSample(v, exp(noise * gaussian(random)) - 1.0)
        }
        return AccelAnalysis(samples, delivered = ::b)
    }

    @Test
    fun `a step leaves the curve alone at and below the slow anchor and adds the whole step at and above the fast one`() {
        val table = family.table(0.15, slow, fast)
        for (v in listOf(0.5, 2.0, 5.0, slow)) assertEquals(b(v), table.multiplier(v), b(v) * 1e-3, "untouched at $v in/s")
        for (v in listOf(fast, 40.0, 100.0)) assertEquals(1.15, table.multiplier(v) / b(v), 2e-3, "whole step at $v in/s")
        val ratios = own.speeds.map { table.multiplier(it) / b(it) }
        assertTrue(ratios.zipWithNext().all { (a, c) -> c >= a - 1e-4 }, "the extra never falls")
        assertTrue(ratios.zipWithNext().all { (a, c) -> ln(c / a) < ln(1.15) / 5 }, "no jumps between neighbours")
    }

    @Test
    fun `the step is read back from the table and no strength table is taken for one`() {
        for (x in listOf(0.08, 0.15, 0.25)) assertEquals(x, assertNotNull(family.stepOf(family.table(x, slow, fast))), 1e-3)
        assertNull(family.stepOf(own.model(1.3) as AccelCurve))
        assertNull(own.strengthOf(family.table(0.15, slow, fast)), "a felt step is no strength")
    }

    @Test
    fun `a calm hand allows no felt step and says so, a loose one allows the rungs up to the bandwidth`() {
        val calm = assertNotNull(feltPlan(analysis(0.05), StrengthDraft.BANDWIDTH_Z))
        assertTrue(calm.steps.isEmpty(), "max ${calm.maxStep}")
        val loose = assertNotNull(feltPlan(analysis(0.45), StrengthDraft.BANDWIDTH_Z))
        assertEquals(FeltStep.LADDER, loose.steps, "max ${loose.maxStep}")
        val middle = assertNotNull(feltPlan(analysis(0.17), StrengthDraft.BANDWIDTH_Z))
        assertTrue(middle.steps.size in 1 until FeltStep.LADDER.size, "max ${middle.maxStep}")
        assertTrue(middle.steps.all { it <= middle.maxStep })
    }

    @Test
    fun `speeds that do not span enough give no plan`() {
        assertNull(feltPlan(analysis(0.3, lo = 10.0, hi = 12.0), StrengthDraft.BANDWIDTH_Z))
    }

    @Test
    fun `the draft carries the plan except when the errors ask for a weaker rise`() {
        val d = assertNotNull(draftStrength(analysis(0.45), ::b))
        assertNotNull(d.felt)
    }
}
