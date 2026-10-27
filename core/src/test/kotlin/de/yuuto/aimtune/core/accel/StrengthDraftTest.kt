package de.yuuto.aimtune.core.accel

import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The strength drawn from the movement profile (docs/FIND-CURVE-REDESIGN.md step 5): estimate, ceiling from the error bandwidth, half-way stage. */
class StrengthDraftTest {
    /** A curve of the player's own that acts across the hand speeds: flat up to 3 in/s, then rising to about 1.45 (times the 0.75 of a lower output DPI). */
    private fun base(v: Double) = 0.75 * (1.0 + 0.6 * (1.0 - exp(-maxOf(v - 3.0, 0.0) / 10.0)))
    private val slow = base(OwnCurveStrength.GRID_FROM)

    private fun gaussian(random: Random) = sqrt(-2.0 * ln(1.0 - random.nextDouble())) * cos(2.0 * Math.PI * random.nextDouble())

    /** Reaches of a player who wants the base's rise scaled by [truth]; the base is what was delivered. */
    private fun samples(truth: Double, seed: Long, noise: Double = 0.05, count: Int = 900, delivered: (Double) -> Double = ::base): List<MovementSample> {
        val random = Random(seed)
        return List(count) {
            val v = exp(random.nextDouble(ln(3.5), ln(25.0)))
            val wanted = slow * (delivered(v) / slow).pow(truth)
            MovementSample(v, exp(ln(delivered(v)) - ln(wanted) + noise * gaussian(random)) - 1.0)
        }
    }

    private fun draft(truth: Double, seed: Long = 1, noise: Double = 0.05, delivered: (Double) -> Double = ::base): StrengthDraft? =
        draftStrength(AccelAnalysis(samples(truth, seed, noise, delivered = delivered), delivered = delivered), delivered)

    @Test
    fun `a hand that wants a stronger rise is measured as stronger and the draft stops at the ceiling`() {
        val d = assertNotNull(draft(1.6))
        assertEquals(DraftBasis.MEASURED_STRONGER, d.basis)
        assertEquals(1.6, d.estimate, 0.25)
        assertTrue(d.strength > 1.0 && d.strength <= d.ceiling + 1e-9 && d.strength <= d.estimate + 1e-9, "draft ${d.strength}, ceiling ${d.ceiling}, estimate ${d.estimate}")
        assertEquals(1.0 + (d.strength - 1.0) / 2.0, d.stage1, 1e-12, "stage 1 is half-way")
    }

    @Test
    fun `a hand that wants a clearly weaker rise is drafted weaker`() {
        val d = assertNotNull(draft(0.4))
        assertEquals(DraftBasis.MEASURED_WEAKER, d.basis)
        assertTrue(d.estimate < CurveStrength.FALLING_MAX_HIGH, "the measured estimate ${d.estimate}")
        // The error bandwidth limits the step: with this spread the draft stops at the floor, well above the estimate.
        assertEquals(maxOf(d.estimate, d.floor), d.strength, 1e-9)
        assertTrue(d.strength < 1.0 && d.stage1 < 1.0 && d.stage1 > d.strength)
    }

    @Test
    fun `a hand that wants the curve as it is gets the small design step upward, not a measured one`() {
        val d = assertNotNull(draft(1.0))
        assertEquals(DraftBasis.DESIGN, d.basis)
        assertEquals(1.0 + StrengthDraft.DESIGN_SHARE * (d.ceiling - 1.0), d.strength, 1e-9)
        assertTrue(d.stage1 > 1.0 && d.stage1 < d.ceiling)
    }

    @Test
    fun `a weaker estimate needs the evidence that falling curves need`() {
        // Slightly weaker than the curve: not enough to leave the design default (the upper end of the range must lie below 0.90).
        val d = assertNotNull(draft(0.93, seed = 4, noise = 0.12))
        assertEquals(DraftBasis.DESIGN, d.basis)
        assertTrue(d.strength >= 1.0)
    }

    @Test
    fun `a wider error bandwidth allows a larger change and a stronger rise of the base allows a smaller one`() {
        val tight = assertNotNull(draft(1.0, noise = 0.05))
        val loose = assertNotNull(draft(1.0, noise = 0.15))
        assertTrue(loose.ceiling > tight.ceiling, "bandwidth ${tight.ceiling} -> ${loose.ceiling}")
        val steeper = { v: Double -> slow * (base(v) / slow).pow(2.0) }
        val steep = assertNotNull(draft(1.0, noise = 0.05, delivered = steeper))
        assertTrue(steep.ceiling < tight.ceiling, "a steeper base ${steep.ceiling} against ${tight.ceiling}")
    }

    @Test
    fun `the ceiling stays inside the searched range and a base that does not act in the range gets the range end`() {
        val flat = { _: Double -> 0.75 }
        val d = assertNotNull(draft(1.0, delivered = flat))
        assertEquals(OwnCurveStrength.MAX, d.ceiling, 1e-9)
        assertEquals(OwnCurveStrength.MIN, d.floor, 1e-9)
        assertTrue(d.strength in 1.0..OwnCurveStrength.MAX)
        assertEquals(DraftBasis.DESIGN, d.basis, "nothing can be measured on a curve that does not act")
    }

    @Test
    fun `too few movements give no draft`() {
        assertNull(draftStrength(AccelAnalysis(samples(1.0, 3, count = 40), delivered = ::base), ::base))
    }

    @Test
    fun `a speed trend in the errors that is no preference is not read as a measured rise, least of all on a curve that barely acts`() {
        // The first reaches of a hand miss a little more the faster they are, whatever gain it wants (about 5 % across the common speeds, as the gate harness models the range effect).
        fun trended(delivered: (Double) -> Double, seed: Long): StrengthDraft {
            val random = Random(seed)
            val samples = List(900) {
                val v = exp(random.nextDouble(ln(3.5), ln(25.0)))
                MovementSample(v, exp(0.05 * ln(v / 8.0) / ln(25.0 / 3.5) + 0.05 * gaussian(random)) - 1.0)
            }
            return assertNotNull(draftStrength(AccelAnalysis(samples, delivered = delivered), delivered))
        }
        val barely = { v: Double -> 0.75 * (1.0 + 0.03 * (1.0 - exp(-maxOf(v - 10.0, 0.0) / 10.0))) }
        for (seed in 1L..5L) {
            assertEquals(DraftBasis.DESIGN, trended(barely, seed).basis, "a curve that hardly acts, seed $seed")
            assertEquals(DraftBasis.DESIGN, trended(::base, seed).basis, "a trend of 5 % is below the allowance, seed $seed")
        }
    }
}
