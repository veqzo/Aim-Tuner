package dev.veqzo.aimtune.core.scenario

import dev.veqzo.aimtune.core.math.Vec3
import dev.veqzo.aimtune.core.math.ViewAngles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FlashScenarioTest {
    private val geometry = TrainerGeometry.DEFAULT
    private val g = geometry.flash
    private val second = 1_000_000_000L

    /** A scenario started at time 0 and advanced to just after its first cue. */
    private fun cued(seed: Long = 1L): Pair<FlashScenario, TargetEvent.FlashCue> {
        val scenario = FlashScenario(60 * second, geometry, seed)
        scenario.start(0L)
        var now = 0L
        while (scenario.events.none { it is TargetEvent.FlashCue } && now < 5 * second) {
            now += 10_000_000L
            scenario.update(now, ViewAngles())
        }
        return scenario to scenario.events.filterIsInstance<TargetEvent.FlashCue>().first()
    }

    @Test
    fun `the flash becomes visible inside the field of view and pops within the documented delay`() {
        repeat(30) { seed ->
            val (scenario, cue) = cued(seed.toLong())
            val angle = ViewAngles().direction().angleToDegrees(ViewAngles.directionOf(cue.yawDegrees, cue.pitchDegrees))
            assertTrue(angle in g.cueOffsetDegrees.min - 0.5..g.cueOffsetDegrees.max + 0.5, "flash at $angle degrees")
            assertTrue(angle < g.halfFovDegrees, "the flash must start on screen")
            val delay = (cue.popNanos - cue.timeNanos) / 1e9
            assertTrue(delay in g.popDelaySeconds.min..g.popDelaySeconds.max, "pop delay $delay")
            assertEquals(g.safeAngleDegrees, cue.safeAngleDegrees)
            assertNotNull(scenario.flash)
            assertTrue(scenario.targets.isEmpty(), "no enemy before the pop")
        }
    }

    @Test
    fun `a player who keeps looking is blinded, the enemy stays hidden and shots are ignored until the white-out ends`() {
        val (scenario, cue) = cued()
        scenario.update(cue.popNanos + 1, ViewAngles())
        val pop = scenario.events.filterIsInstance<TargetEvent.FlashPop>().single()
        assertFalse(pop.dodged)
        assertEquals(cue.popNanos + (g.blindSeconds * 1e9).toLong(), pop.blindUntilNanos)
        assertTrue(scenario.events.none { it is TargetEvent.Spawned }, "a blinded player is not shown the enemy yet")
        assertTrue(scenario.targets.isEmpty())
        assertTrue(scenario.flash?.popped == true && scenario.flash?.blindUntilNanos == pop.blindUntilNanos)
        assertEquals(ShotResult.IGNORED, scenario.fire(Vec3.ZERO, ViewAngles().direction(), cue.popNanos + 100_000_000L))
        scenario.update(pop.blindUntilNanos + 1, ViewAngles())
        assertEquals(pop.blindUntilNanos, scenario.events.filterIsInstance<TargetEvent.Spawned>().single().timeNanos, "the time to hit counts from the end of the white-out")
        assertEquals(1, scenario.targets.size)
        assertNull(scenario.flash)
    }

    @Test
    fun `a player who turned beyond the edge of the screen sees the enemy at once near where the view was`() {
        val (scenario, cue) = cued(seed = 3L)
        val flash = ViewAngles.directionOf(cue.yawDegrees, cue.pitchDegrees)
        // Turn directly away from the flash until it is beyond the safe angle.
        val step = if (cue.yawDegrees > 0) -5.0 else 5.0
        var away = ViewAngles()
        while (away.direction().angleToDegrees(flash) < cue.safeAngleDegrees + 1.0) away = away.copy(yawDegrees = away.yawDegrees + step)
        scenario.update(cue.popNanos, away)
        val pop = scenario.events.filterIsInstance<TargetEvent.FlashPop>().single()
        assertTrue(pop.dodged)
        assertEquals(0L, pop.blindUntilNanos)
        assertEquals(cue.popNanos, scenario.events.filterIsInstance<TargetEvent.Spawned>().single().timeNanos)
        val enemy = scenario.targets.single()
        val offset = ViewAngles().direction().angleToDegrees(enemy.center)
        assertTrue(offset in g.returnMinDegrees - 0.1..g.returnSpreadDegrees + 0.1, "enemy $offset degrees from the original view")
        assertEquals(ShotResult.HIT, scenario.fire(Vec3.ZERO, enemy.center.normalized(), cue.popNanos + 300_000_000L))
        assertEquals(1, scenario.events.filterIsInstance<TargetEvent.Hit>().size)
    }

    @Test
    fun `after a hit the next flash is placed relative to the view at that time`() {
        val (scenario, cue) = cued(seed = 5L)
        scenario.update(cue.popNanos + 1, ViewAngles())
        scenario.update(cue.popNanos + (g.blindSeconds * 1e9).toLong() + 1, ViewAngles())
        val enemy = scenario.targets.single()
        scenario.fire(Vec3.ZERO, enemy.center.normalized(), cue.popNanos + 2 * second)
        val aimed = ViewAngles(yawDegrees = 40.0)
        scenario.update(cue.popNanos + 5 * second, aimed)
        val next = scenario.events.filterIsInstance<TargetEvent.FlashCue>()[1]
        val angle = aimed.direction().angleToDegrees(ViewAngles.directionOf(next.yawDegrees, next.pitchDegrees))
        assertTrue(angle in g.cueOffsetDegrees.min - 0.5..g.cueOffsetDegrees.max + 0.5, "second flash at $angle degrees from the new view")
    }

    @Test
    fun `the same seed gives the same round and forHorizontalFov keeps the offsets on screen`() {
        fun run(): List<TargetEvent> = cued(9L).first.also { it.update(10 * second, ViewAngles()) }.events.toList()
        assertEquals(run(), run())
        val narrow = FlashGeometry().forHorizontalFov(60.0)
        assertEquals(30.0, narrow.halfFovDegrees)
        assertTrue(narrow.cueOffsetDegrees.max < narrow.halfFovDegrees)
    }
}
