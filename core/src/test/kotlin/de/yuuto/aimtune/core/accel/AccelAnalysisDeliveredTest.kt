package de.yuuto.aimtune.core.accel

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Rounds played with a curve of the player's own (docs/FIND-CURVE-REDESIGN.md step 1): a reach lands at `delivered / wanted` times
 * its distance, so the analysis must divide the delivered gain out to say what the player wants.
 */
class AccelAnalysisDeliveredTest {
    private fun wanted(v: Double) = 1.0 + 0.5 * (1.0 - exp(-v / 6.0))

    /** A rising curve like the player's own one, with the constant factor a lower output DPI brings. */
    private fun delivered(v: Double) = 0.75 * (1.0 + 0.6 * (1.0 - exp(-v / 12.0)))

    private fun gaussian(random: Random) = sqrt(-2.0 * ln(1.0 - random.nextDouble())) * cos(2.0 * Math.PI * random.nextDouble())

    private fun samples(count: Int, seed: Long, noise: Double = 0.05): List<MovementSample> {
        val random = Random(seed)
        return List(count) {
            val v = exp(random.nextDouble(ln(3.0), ln(25.0)))
            MovementSample(v, exp(ln(delivered(v)) - ln(wanted(v)) + noise * gaussian(random)) - 1.0)
        }
    }

    /** The change of ln gain from the first to the last point: the shape without the level the analysis cannot know. */
    private fun rise(analysis: AccelAnalysis) = analysis.points.last().logGain - analysis.points.first().logGain

    @Test
    fun `without a delivered gain the analysis is what it was`() {
        val s = samples(600, 1)
        assertEquals(AccelAnalysis(s).points, AccelAnalysis(s, delivered = null).points)
        assertEquals(AccelAnalysis(s).points, AccelAnalysis(s, delivered = { 1.0 }).points)
    }

    @Test
    fun `a delivered curve is divided out, so the points are what the player wants`() {
        val s = samples(800, 2)
        val first = AccelAnalysis(s, delivered = ::delivered).points.first().speed
        val last = AccelAnalysis(s, delivered = ::delivered).points.last().speed
        val wantedRise = ln(wanted(last) / wanted(first))
        val correctionRise = ln((wanted(last) / delivered(last)) / (wanted(first) / delivered(first)))
        assertTrue(abs(rise(AccelAnalysis(s, delivered = ::delivered)) - wantedRise) < 0.03, "with the delivered gain: the wanted shape")
        assertTrue(abs(rise(AccelAnalysis(s)) - correctionRise) < 0.03, "without it: only the correction of what was delivered")
    }

    @Test
    fun `a constant factor of the delivered gain changes nothing but the level`() {
        val s = samples(600, 3)
        val a = AccelAnalysis(s, delivered = ::delivered).points
        val b = AccelAnalysis(s, delivered = { delivered(it) * 1.3 }).points
        assertEquals(a.map { it.speed }, b.map { it.speed })
        a.zip(b).forEach { (x, y) -> assertEquals(x.logGain, y.logGain, 1e-9) }
    }

    @Test
    fun `a player who wants exactly the delivered curve is read as constant after the own curve is divided out`() {
        val random = Random(4)
        val flat = List(600) {
            val v = exp(random.nextDouble(ln(3.0), ln(25.0)))
            MovementSample(v, exp(0.05 * gaussian(random)) - 1.0)
        }
        // The reaches land on target on average: the wanted gain is the delivered one, so the points follow ln(delivered).
        val points = AccelAnalysis(flat, delivered = ::delivered).points
        val expected = points.map { ln(delivered(it.speed)) }
        val offset = points.map { it.logGain }.zip(expected).map { (y, e) -> y - e }
        assertTrue(offset.max() - offset.min() < 0.04, "the shape is the delivered curve's")
    }

    @Test
    fun `a fit on the own curve keeps its level at the typical speed and follows the wanted shape`() {
        val analysis = AccelAnalysis(samples(900, 5), delivered = ::delivered)
        val fit = checkNotNull(boundedRecommendation(fitAccel(analysis), analysis)) { "the rising wish should be found" }
        val mid = analysis.speedQuantile(0.5)
        val own = fit.scaledBy(delivered(mid))
        assertTrue(abs(own.totalGain(mid) / delivered(mid) - 1.0) < 0.10, "level at the typical speed stays that of the base")
        // The families do not reproduce this wish exactly at the slow end (5.6 % off there with and without an own curve, checked against a plain control).
        listOf(0.05, 0.25, 0.75, 0.95).map { analysis.speedQuantile(it) }.forEach { v ->
            val shape = own.totalGain(v) / own.totalGain(mid)
            assertTrue(abs(shape / (wanted(v) / wanted(mid)) - 1.0) < 0.08, "shape at $v in/s")
        }
    }
}
