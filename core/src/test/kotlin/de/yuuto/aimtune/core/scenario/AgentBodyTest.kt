package de.yuuto.aimtune.core.scenario

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AgentBodyTest {
    private val body = AgentBody(0.24, 1.96, 1.75, 1.35, 5.4, 2.97, 1.62)

    @Test
    fun `a standing bot has its head centre at the standing eye height`() {
        val shape = body.standingShape
        assertEquals(1.75, shape.headCenterAboveFeet, 1e-12)
        assertEquals(0.24, shape.headDiameterMeters)
    }

    @Test
    fun `impossible bodies are rejected`() {
        assertFailsWith<IllegalArgumentException> { body.copy(eyeCrouchMeters = 1.8) }
        assertFailsWith<IllegalArgumentException> { body.copy(eyeStandMeters = 1.9) } // head top above the capsule
        assertFailsWith<IllegalArgumentException> { body.copy(walkSpeedMetersPerSecond = 6.0) }
        assertFailsWith<IllegalArgumentException> { body.copy(crouchSpeedMetersPerSecond = 0.0) }
        assertFailsWith<IllegalArgumentException> { body.copy(headDiameterMeters = 0.7) }
        assertFailsWith<IllegalArgumentException> { body.copy(runSpeedMetersPerSecond = 20.0, walkSpeedMetersPerSecond = 2.97) }
    }
}
