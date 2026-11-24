package dev.veqzo.aimtune.core.sim

import dev.veqzo.aimtune.core.accel.AccelCurve
import dev.veqzo.aimtune.core.accel.NaturalCurve
import dev.veqzo.aimtune.core.metrics.RoundMetrics
import dev.veqzo.aimtune.core.metrics.computeRoundMetrics
import dev.veqzo.aimtune.core.scenario.ScenarioKind
import dev.veqzo.aimtune.core.scenario.TargetEvent
import dev.veqzo.aimtune.core.score.ScoreMetric
import dev.veqzo.aimtune.core.score.scoreInputs
import kotlin.math.exp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HeadlessRoundTest {
    private fun hits(player: SimulatedPlayer, cm360: Double = 50.0, seed: Long = 1) =
        HeadlessRound.flick(player, 800.0, cm360, seed, 30).targetEvents.count { it is TargetEvent.Hit }

    @Test
    fun `the same seed gives the same recording`() {
        val player = SimulatedPlayer()
        assertEquals(HeadlessRound.flick(player, 800.0, 50.0, 7, 20), HeadlessRound.flick(player, 800.0, 50.0, 7, 20))
    }

    @Test
    fun `a round has ordered events and realistic hit counts`() {
        val rec = HeadlessRound.flick(SimulatedPlayer(), 800.0, 50.0, 3, 30)
        assertTrue(rec.mouseEvents.zipWithNext().all { (a, b) -> a.timeNanos < b.timeNanos })
        assertTrue(rec.mouseEvents.all { it.timeNanos in rec.startNanos..rec.endNanos })
        val hits = rec.targetEvents.count { it is TargetEvent.Hit }
        assertTrue(hits in 12..45, "hits in 30 s: $hits")
    }

    @Test
    fun `the analysis finds corrections and a small first-movement error`() {
        val metrics = computeRoundMetrics(HeadlessRound.flick(SimulatedPlayer(), 800.0, 50.0, 4, 45))
        assertNotNull(metrics.errorSpread)
        assertTrue(metrics.errorSpread!! in 0.005..0.2, "spread ${metrics.errorSpread}")
        assertTrue(metrics.meanFirstError!! < 0.05, "players undershoot a little, mean ${metrics.meanFirstError}")
        assertTrue(metrics.accuracy > 0.8, "accuracy ${metrics.accuracy}")
    }

    @Test
    fun `a very low sensitivity ruins precision and a very high one costs time`() {
        val player = SimulatedPlayer()
        val best = hits(player, 50.0)
        assertTrue(hits(player, 12.0) < best / 2)
        assertTrue(hits(player, 250.0) < best)
    }

    @Test
    fun `more noise means fewer hits`() {
        assertTrue(hits(SimulatedPlayer(noiseLevel = 2.0)) < hits(SimulatedPlayer(noiseLevel = 0.5)))
    }

    @Test
    fun `the optimum can be moved by configuration`() {
        // A player whose optimum is 25 does better at 25 than a base player does there, and the reverse at 50.
        val low = SimulatedPlayer().withOptimum(25.0)
        val base = SimulatedPlayer()
        assertEquals(25.0, low.declaredOptimumCm360, 1e-9)
        assertTrue(hits(low, 25.0) > hits(base, 25.0))
        assertTrue(hits(base, 50.0) > hits(low, 50.0))
    }

    @Test
    fun `the simulated player plays every click scenario and the analysis sees its movements`() {
        for (kind in listOf(ScenarioKind.FLICK, ScenarioKind.MICRO, ScenarioKind.SWITCHING)) {
            val rec = HeadlessRound.of(kind, SimulatedPlayer(), 800.0, 60.0, 3, 30)
            assertEquals(kind.id, rec.scenarioId)
            assertEquals(rec, HeadlessRound.of(kind, SimulatedPlayer(), 800.0, 60.0, 3, 30), "${kind.id} must be deterministic")
            val metrics = computeRoundMetrics(rec)
            assertTrue(metrics.hits >= 10, "${kind.id}: ${metrics.hits} hits")
            assertTrue(metrics.accuracy > 0.5, "${kind.id}: accuracy ${metrics.accuracy}")
            assertTrue(metrics.movements.size >= 8 && metrics.errorSpread != null, "${kind.id}: ${metrics.movements.size} analysed movements")
            assertTrue(metrics.trackingOnTargetRatio == null)
        }
    }

    @Test
    fun `switching is faster per hit than flicking because the next target is already known`() {
        fun meanTime(kind: ScenarioKind) = (1L..4L).map { computeRoundMetrics(HeadlessRound.of(kind, SimulatedPlayer(), 800.0, 55.0, it, 30)).meanTimeToHitMs!! }.average()
        assertTrue(meanTime(ScenarioKind.SWITCHING) < meanTime(ScenarioKind.FLICK))
    }

    @Test
    fun `tracking is measured by time on target and needs no clicks`() {
        val rec = HeadlessRound.of(ScenarioKind.TRACKING, SimulatedPlayer(), 800.0, 60.0, 3, 30)
        assertTrue(rec.targetEvents.none { it is TargetEvent.Hit || it is TargetEvent.Miss })
        val metrics = computeRoundMetrics(rec)
        assertEquals(0, metrics.shots)
        assertEquals(setOf(ScoreMetric.TRACKING), metrics.scoreInputs().keys)
        assertTrue(metrics.trackingOnTargetRatio!! in 0.15..0.9, "on target ${metrics.trackingOnTargetRatio}")
        assertTrue(metrics.trackingMeanDistanceDegrees!! in 0.2..10.0, "distance ${metrics.trackingMeanDistanceDegrees}")
    }

    @Test
    fun `a player who keeps up is on target and one who does not move is not`() {
        val sharp = SimulatedPlayer(
            signalNoise = 0.0, handNoiseCm = 1e-4, trackingLagMs = 10.0, trackingPrediction = 1.0, trackingMaxHandSpeedCmPerS = 1000.0,
        )
        val following = computeRoundMetrics(HeadlessRound.of(ScenarioKind.TRACKING, sharp, 800.0, 50.0, 8, 30)).trackingOnTargetRatio!!
        assertTrue(following > 0.8, "following $following")

        val rec = HeadlessRound.of(ScenarioKind.TRACKING, sharp, 800.0, 50.0, 8, 30)
        val still = computeRoundMetrics(rec.copy(mouseEvents = emptyList())).trackingOnTargetRatio!!
        assertTrue(still < 0.3, "standing still $still")
    }

    /** Expects a gain that rises from 1 toward 1.5 with the hand speed; Raw Accel's natural mode can deliver exactly that. */
    private val wantsRisingGain = SimulatedPlayer(gainPreference = { v -> 1.0 + 0.5 * (1.0 - exp(-v / 6.0)) })
    private val matchingCurve = NaturalCurve(decayRate = 0.5 / 6.0, limit = 1.5)

    private fun round(player: SimulatedPlayer, accel: AccelCurve?, seed: Long) =
        computeRoundMetrics(HeadlessRound.flick(player, 800.0, 55.0, seed, 45, accel = accel))

    @Test
    fun `a player who expects more gain than the mouse gives undershoots and the matching curve takes most of it back`() {
        val seeds = 1L..5L
        fun mean(player: SimulatedPlayer, accel: AccelCurve?, value: (RoundMetrics) -> Double) = seeds.map { value(round(player, accel, 40 + it)) }.average()

        val flatError = mean(SimulatedPlayer(), null) { it.meanFirstError!! }
        val plainError = mean(wantsRisingGain, null) { it.meanFirstError!! }
        val matchedError = mean(wantsRisingGain, matchingCurve) { it.meanFirstError!! }
        assertTrue(plainError < flatError - 0.12, "undershoot without the curve $plainError vs flat $flatError")
        assertTrue(matchedError > plainError + 0.1, "with the curve $matchedError")

        val plainHits = mean(wantsRisingGain, null) { it.hits.toDouble() }
        val matchedHits = mean(wantsRisingGain, matchingCurve) { it.hits.toDouble() }
        assertTrue(matchedHits > plainHits + 3, "hits $plainHits -> $matchedHits")
    }

    @Test
    fun `a player without a speed dependent expectation is not helped by a curve`() {
        val seeds = 1L..5L
        val hitsPlain = seeds.map { round(SimulatedPlayer(), null, 70 + it).hits }.average()
        val hitsWithCurve = seeds.map { round(SimulatedPlayer(), matchingCurve, 70 + it).hits }.average()
        assertTrue(hitsWithCurve < hitsPlain + 1, "hits $hitsPlain -> $hitsWithCurve")
    }
}
