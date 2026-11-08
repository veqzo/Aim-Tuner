package de.yuuto.aimtune.core.scenario

import kotlin.math.exp
import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GroundMovementTest {
    private val body = AgentBody(0.24, 1.96, 1.75, 1.35, 5.4, 2.97, 1.62)
    private val movement = GroundMovement(
        accel = 18.0, friction = 3.3, brakingFriction = 21.5, frictionMin = 11.0, aboveMaxSpeedDecay = 13.0,
        walkAccelFactor = 0.55, walkBrakingFactor = 0.55, crouchAccelFactor = 0.4, crouchFrictionFactor = 0.8, crouchFrictionMinFactor = 0.85,
    )
    private val motion = Locomotion(body, movement)
    private val tick = Locomotion.DEFAULT_TICK_SECONDS

    private fun hold(input: Int, gait: Gait, ticks: Int, start: Double = 0.0): Double =
        (1..ticks).fold(start) { v, _ -> motion.step(v, input, gait) }

    @Test
    fun `holding a key reaches and keeps the top speed of each gait`() {
        for (gait in Gait.entries) {
            assertEquals(motion.topSpeed(gait), hold(1, gait, 200), 1e-9, "$gait")
            assertEquals(-motion.topSpeed(gait), hold(-1, gait, 200), 1e-9, "$gait")
        }
    }

    @Test
    fun `the top speed is reached at the gait's acceleration`() {
        // Straight on there is nothing to oppose, so the speed rises linearly at accel x factor.
        assertEquals(39, motion.ticksToTopSpeed(Gait.RUN)) // 5.4 / 18 = 0.3 s
        assertEquals(39, motion.ticksToTopSpeed(Gait.WALK)) // 2.97 / (18 x 0.55) = 0.3 s
        assertEquals(29, motion.ticksToTopSpeed(Gait.CROUCH)) // 1.62 / (18 x 0.4) = 0.225 s
    }

    @Test
    fun `letting go stops a runner as the braking equation says`() {
        // dv/dt = -(b v + m): v(t) = (v0 + m/b) e^(-b t) - m/b.
        val b = 21.5
        val m = 11.0
        val stopSeconds = ln((5.4 + m / b) / (m / b)) / b
        val stopMeters = (5.4 + m / b) * (1 - exp(-b * stopSeconds)) / b - m / b * stopSeconds
        val stop = motion.stop(5.4, Gait.RUN)
        assertEquals(stopSeconds, stop.ticks * tick, 2 * tick)
        assertEquals(stopMeters, stop.meters, 0.03)
        assertTrue(stop.ticks * tick < 0.13, "a runner stands still in about 0.11 s")
    }

    @Test
    fun `a walker brakes more gently, and a counter-strafe turns the runner around`() {
        assertTrue(motion.stop(2.97, Gait.WALK).ticks > motion.stop(2.97, Gait.RUN).ticks)
        val counter = motion.stop(5.4, Gait.RUN, counterStrafe = true)
        assertTrue(counter.ticks > 0 && counter.meters > 0.0)
        assertEquals(-5.4, hold(-1, Gait.RUN, 200, start = 5.4), 1e-9)
    }

    @Test
    fun `switching from running to walking decays the speed instead of cutting it`() {
        val first = motion.step(5.4, 1, Gait.WALK)
        assertTrue(first < 5.4 && first > 2.97)
        assertEquals(2.97, hold(1, Gait.WALK, 200, start = 5.4), 1e-9)
    }

    @Test
    fun `invalid movement and input are rejected`() {
        assertFailsWith<IllegalArgumentException> { movement.copy(accel = 0.0) }
        assertFailsWith<IllegalArgumentException> { movement.copy(friction = -1.0) }
        assertFailsWith<IllegalArgumentException> { movement.copy(crouchAccelFactor = 0.0) }
        assertFailsWith<IllegalArgumentException> { motion.step(0.0, 2, Gait.RUN) }
        assertFailsWith<IllegalArgumentException> { Locomotion(body, movement, tickSeconds = 0.0) }
    }
}
