package dev.veqzo.aimtune.core.sim

import dev.veqzo.aimtune.core.accel.AccelCurve
import dev.veqzo.aimtune.core.accel.AccelProcessor
import dev.veqzo.aimtune.core.accel.NaturalCurve
import dev.veqzo.aimtune.core.harness.TEST_DPI
import dev.veqzo.aimtune.core.metrics.computeRoundMetrics
import dev.veqzo.aimtune.core.scenario.ScenarioKind
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HandAdaptationTest {
    private val speed = 6.0

    /** Plays [n] movements at [speed] against a constant [delivered] gain; returns the last expected gain. */
    private fun play(hand: HandAdaptation, n: Int, delivered: Double, pref: Double = 1.0): Double {
        repeat(n) { hand.update(speed, pref * hand.factor(speed), delivered) }
        return pref * hand.factor(speed)
    }

    private fun residual(expected: Double, delivered: Double, pref: Double = 1.0) = ln(delivered / expected) / ln(delivered / pref)

    @Test
    fun `a 10 percent gain step is mostly compensated after 50 movements without overcorrection`() {
        val hand = HandAdaptation.adaptedTo(preference = { 1.0 }, delivered = { 1.0 })
        val expected = play(hand, 50, delivered = 1.1)
        val r = residual(expected, 1.1)
        assertTrue(r < 0.5, "residual $r")
        assertTrue(r > 0.0, "overcorrected: $r")
    }

    @Test
    fun `a constant mismatch leaves the steady residual after long play`() {
        val hand = HandAdaptation()
        val expected = play(hand, 2000, delivered = 1.1)
        val r = residual(expected, 1.1)
        assertTrue(r in 0.05..0.30, "residual $r")
        assertEquals(hand.steadyResidual, r, 0.02)
    }

    @Test
    fun `switching back after adaptation produces an aftereffect of opposite sign`() {
        val hand = HandAdaptation()
        val adapted = play(hand, 300, delivered = 1.1)
        assertTrue(adapted > 1.0, "adapted expectation $adapted")
        // Back to plain: the first movement overshoots, the opposite sign of the step ln(1.1) > 0 ...
        val firstAfterSwitch = hand.factor(speed)
        assertTrue(ln(1.0 / firstAfterSwitch) < 0.0, "first mismatch ${ln(1.0 / firstAfterSwitch)}")
        // ... and the hand unlearns it: after 30 plain movements the expectation is closer to 1.
        val later = play(hand, 30, delivered = 1.0)
        assertTrue(abs(ln(later)) < 0.5 * abs(ln(firstAfterSwitch)), "after 30 plain movements $later, at the switch $firstAfterSwitch")
    }

    @Test
    fun `a speed that is not played keeps what was learned there`() {
        val hand = HandAdaptation()
        play(hand, 300, delivered = 1.1)
        val before = hand.factor(speed)
        repeat(300) { hand.update(40.0, hand.factor(40.0), 1.0) }
        assertTrue(abs(ln(hand.factor(speed)) / ln(before) - 1.0) < 0.05, "at 6 in/s ${hand.factor(speed)} after only playing 40 in/s, before $before")
    }

    @Test
    fun `adaptation generalizes to nearby speeds but not to distant ones`() {
        val hand = HandAdaptation()
        play(hand, 30, delivered = 1.2)
        val at6 = abs(ln(hand.factor(6.0)))
        assertTrue(at6 > 0.0)
        assertTrue(abs(ln(hand.factor(40.0))) < 0.2 * at6, "far speed too affected")
        assertTrue(abs(ln(hand.factor(7.0))) >= 0.5 * at6, "near speed too little affected")
    }

    @Test
    fun `adaptedTo starts at the steady state of the preference`() {
        val hand = HandAdaptation.adaptedTo(preference = { 1.3 }, delivered = { 1.0 })
        for (v in listOf(3.0, 6.0, 12.0, 24.0)) {
            assertEquals(1.0 - hand.steadyResidual, ln(hand.factor(v)) / ln(1.0 / 1.3), 0.02, "at $v in/s")
        }
    }

    private val rising: (Double) -> Double = { v -> 1.0 + 0.3 * (1.0 - exp(-v / 6.0)) }
    private val matchingCurve = NaturalCurve(decayRate = 0.3 / 6.0, limit = 1.3)

    private fun medianError(player: SimulatedPlayer, seed: Long, accel: AccelCurve? = null): Double {
        val metrics = computeRoundMetrics(HeadlessRound.of(ScenarioKind.FLICK, player, TEST_DPI, 55.0, seed, 45, accel = accel))
        val errors = metrics.movements.mapNotNull { m ->
            val peak = m.firstPeakSpeedCountsPerMs ?: return@mapNotNull null
            val e = m.firstErrorRatio ?: return@mapNotNull null
            if (AccelProcessor.speedInPerSecond(peak, TEST_DPI) >= 3.0) e else null
        }.sorted()
        check(errors.isNotEmpty()) { "no movements" }
        return errors[errors.size / 2]
    }

    @Test
    fun `an adapted player undershoots less than one without adaptation`() {
        val plain = SimulatedPlayer(gainPreference = rising)
        val adaptedPlayer = plain.copy(adaptation = HandAdaptation.adaptedTo(rising))
        val without = (1L..3L).map { abs(medianError(plain, it)) }.average()
        val with = (1L..3L).map { abs(medianError(adaptedPlayer.copy(adaptation = HandAdaptation.adaptedTo(rising)), it)) }.average()
        assertTrue(with * 2.0 <= without, "with $with, without $without")
    }

    @Test
    fun `an adapted player overshoots with the matching curve and relearns over rounds`() {
        // Relative to the same adapted hand playing plain: the 3 % undershoot of first reaches is in both.
        val plainError = medianError(SimulatedPlayer(gainPreference = rising, adaptation = HandAdaptation.adaptedTo(rising)), 1L)
        val player = SimulatedPlayer(gainPreference = rising, adaptation = HandAdaptation.adaptedTo(rising))
        val errors = (1L..6L).map { medianError(player, it, matchingCurve) }
        assertTrue(errors.first() > plainError + 0.01, "round 1 should overshoot against plain $plainError: ${errors.first()}")
        assertTrue(errors.last() < errors.first(), "overshoot should shrink: $errors")
    }

    @Test
    fun `a default player has no adaptation`() {
        assertNull(SimulatedPlayer().adaptation)
    }
}
