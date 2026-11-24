package dev.veqzo.aimtune.core.sim

import dev.veqzo.aimtune.core.harness.TEST_DPI
import dev.veqzo.aimtune.core.metrics.allFirstReaches
import dev.veqzo.aimtune.core.metrics.computeRangeMetrics
import dev.veqzo.aimtune.core.metrics.computeRoundMetrics
import dev.veqzo.aimtune.core.scenario.RangeConfig
import dev.veqzo.aimtune.core.scenario.RangeOptions
import dev.veqzo.aimtune.core.scenario.RangeScenario
import dev.veqzo.aimtune.core.scenario.RangeSpeed
import dev.veqzo.aimtune.core.scenario.ScenarioKind
import dev.veqzo.aimtune.core.scenario.TargetEvent
import dev.veqzo.aimtune.core.score.ScoreMetric
import dev.veqzo.aimtune.core.score.scoreInputs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RangeAimerTest {
    private val player = SimulatedPlayer().withOptimum(50.0)
    private val seeds = 1L..8L

    private fun round(seed: Long, options: RangeOptions = RangeOptions.DEFAULT, roundSeconds: Int = 45) = HeadlessRound.run(
        player, TEST_DPI, 50.0, seed, roundSeconds, 0L, RangeScenario(RangeConfig(roundSeconds * 1_000_000_000L, options = options), seed),
    )

    @Test
    fun `the simulated aimer kills bots in every round`() {
        for (seed in seeds) {
            val metrics = computeRoundMetrics(round(seed))
            println("seed $seed kills ${metrics.rangeKills} median ttk ${metrics.rangeMedianTimeToKillMs} shots/kill ${metrics.rangeShotsPerKill} first shot ${metrics.firstShotAccuracy}")
            assertTrue((metrics.rangeKills ?: 0) > 0, "no kill in seed $seed")
        }
    }

    @Test
    fun `standing bots are killed faster than strafing ones`() {
        fun medianTtk(options: RangeOptions) = seeds.mapNotNull { computeRoundMetrics(round(it, options)).meanTimeToHitMs }.average()
        val standing = medianTtk(RangeOptions(botsStrafe = false))
        val strafing = medianTtk(RangeOptions(botsStrafe = true))
        println("mean time to kill standing $standing ms, strafing $strafing ms")
        assertTrue(standing < strafing, "standing $standing vs strafing $strafing")
    }

    @Test
    fun `range metrics are consistent with the events`() {
        val recording = round(3L)
        val metrics = computeRoundMetrics(recording)
        val range = computeRangeMetrics(recording)
        assertEquals(recording.targetEvents.count { it is TargetEvent.Hit }, range.kills)
        assertEquals(metrics.hits, range.kills)
        assertNotNull(range.medianTimeToKillMs)
        val expectedShotsPerKill = metrics.shots.toDouble() / range.kills
        assertEquals(expectedShotsPerKill, range.shotsPerKill ?: Double.NaN, 1e-9)
        assertTrue((range.shotsPerKill ?: 0.0) >= 1.0)
        // The score uses first-shot head accuracy and the time to kill.
        val inputs = metrics.scoreInputs()
        assertEquals(metrics.firstShotAccuracy, inputs[ScoreMetric.ACCURACY])
        assertEquals(metrics.meanTimeToHitMs, inputs[ScoreMetric.TIME_TO_HIT])
    }

    @Test
    fun `static rounds have no range metrics`() {
        val metrics = computeRoundMetrics(HeadlessRound.of(ScenarioKind.FLICK, player, TEST_DPI, 50.0, 1L, roundSeconds = 20))
        assertNull(metrics.rangeKills)
        assertNull(metrics.rangeShotsPerKill)
    }

    @Test
    fun `first reaches toward a moving bot are measured against its head and flagged moved`() {
        val moved = seeds.flatMap { computeRoundMetrics(round(it)).movements }
        assertTrue(moved.any { it.targetMoved }, "strafing bots move during reaches")
        assertTrue(moved.any { it.leadErrorDegrees != null })
    }

    @Test
    fun `bots that despawn end their reach window and the round keeps going`() {
        val recording = round(2L, RangeOptions(speed = RangeSpeed.EASY))
        assertTrue(recording.targetEvents.any { it is TargetEvent.Despawned }, "a hard preset loses bots")
        val reaches = allFirstReaches(recording)
        val despawn = recording.targetEvents.filterIsInstance<TargetEvent.Despawned>().first()
        val spawn = recording.targetEvents.filterIsInstance<TargetEvent.Spawned>().first { it.target.id == despawn.targetId }
        val reach = reaches.firstOrNull { it.targetId == despawn.targetId }
        if (reach != null) assertEquals(despawn.timeNanos - spawn.timeNanos, reach.timeToTargetNanos)
        val metrics = computeRoundMetrics(recording)
        // An unshot bot is a first shot that did not hit.
        assertTrue((metrics.firstShotAccuracy ?: 1.0) < 1.0)
        assertTrue(seeds.sumOf { computeRoundMetrics(round(it, RangeOptions(speed = RangeSpeed.EASY))).hits } > 0, "kills under time pressure")
    }

    @Test
    fun `a round is deterministic for a seed`() {
        val a = round(5L)
        val b = round(5L)
        assertEquals(a.mouseEvents, b.mouseEvents)
        assertEquals(a.targetEvents, b.targetEvents)
    }
}
