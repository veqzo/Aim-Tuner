package dev.veqzo.aimtune.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RoundCountdownTest {
    @Test
    fun `counts down and ends exactly once`() {
        val countdown = RoundCountdown(3f)
        assertEquals(3, countdown.shownSeconds)
        assertFalse(countdown.tick(1.2f))
        assertEquals(2, countdown.shownSeconds)
        assertFalse(countdown.tick(1.7f))
        assertEquals(1, countdown.shownSeconds)
        assertTrue(countdown.tick(0.5f))
        assertTrue(countdown.finished)
        assertEquals(0f, countdown.fraction)
        assertFalse(countdown.tick(1f))
    }

    @Test
    fun `a pause stops the time and a resume starts from the full length`() {
        val countdown = RoundCountdown(3f)
        countdown.tick(2f)
        countdown.toggle()
        assertTrue(countdown.paused)
        assertFalse(countdown.tick(5f))
        assertEquals(1f, countdown.remaining)
        countdown.toggle()
        assertFalse(countdown.paused)
        assertEquals(3f, countdown.remaining)
    }

    @Test
    fun `a finished countdown cannot be paused`() {
        val countdown = RoundCountdown(1f)
        countdown.tick(1f)
        countdown.pause()
        assertFalse(countdown.paused)
    }

    @Test
    fun `the length must be positive`() {
        assertFailsWith<IllegalArgumentException> { RoundCountdown(0f) }
    }
}
