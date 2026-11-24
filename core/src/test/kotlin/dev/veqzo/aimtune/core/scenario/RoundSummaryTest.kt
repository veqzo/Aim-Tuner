package dev.veqzo.aimtune.core.scenario

import dev.veqzo.aimtune.core.math.Vec3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RoundSummaryTest {
    private fun target(id: Int) = Target(id, Vec3(0.0, 0.0, -10.0), 0.25)

    @Test
    fun `empty round has no hits and zero accuracy`() {
        val s = summarize(emptyList())
        assertEquals(0, s.shots)
        assertEquals(0.0, s.accuracy)
        assertNull(s.meanTimeToHitNanos)
    }

    @Test
    fun `counts hits misses and mean time to hit`() {
        val events = listOf(
            TargetEvent.Spawned(0, target(0)),
            TargetEvent.Miss(100),
            TargetEvent.Hit(400, 0),
            TargetEvent.Spawned(400, target(1)),
            TargetEvent.Hit(1000, 1),
        )
        val s = summarize(events)
        assertEquals(2, s.hits)
        assertEquals(1, s.misses)
        assertEquals(3, s.shots)
        assertEquals(2.0 / 3.0, s.accuracy, 1e-12)
        assertEquals(500L, s.meanTimeToHitNanos)
    }

    @Test
    fun `first shot accuracy counts the first click of every reach, not every click`() {
        val events = listOf(
            TargetEvent.Spawned(0, target(0)),
            TargetEvent.Miss(100), // first shot of reach 1 missed
            TargetEvent.Miss(200), // a second miss does not count again
            TargetEvent.Hit(400, 0),
            TargetEvent.Spawned(400, target(1)),
            TargetEvent.Hit(1000, 1), // first shot of reach 2 hit
            TargetEvent.Spawned(1000, target(2)),
            TargetEvent.Hit(1300, 2),
        )
        val s = summarize(events)
        assertEquals(5, s.shots)
        assertEquals(3, s.firstShots)
        assertEquals(2, s.firstShotHits)
        assertEquals(2.0 / 3.0, assertNotNull(s.firstShotAccuracy), 1e-12)
        assertEquals(3.0 / 5.0, s.accuracy, 1e-12)
        assertNull(summarize(listOf(TargetEvent.Spawned(0, target(0)))).firstShotAccuracy)
    }
}
