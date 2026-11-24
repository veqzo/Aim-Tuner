package dev.veqzo.aimtune.core.accel

import dev.veqzo.aimtune.core.input.MouseEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AccelProcessorTimestampTest {
    private val ms = 1_000_000L

    @Test
    fun `speed is counts over the time since the previous report in inches per second at 1000 dpi`() {
        val p = AccelProcessor(NoAccel, dpi = 1000.0)
        p.speedOf(MouseEvent(0, 1, 0))
        assertEquals(10.0, p.speedOf(MouseEvent(1 * ms, 10, 0)), 1e-9)
        assertEquals(5.0, p.speedOf(MouseEvent(3 * ms, 10, 0)), 1e-9)
        assertEquals(20.0, AccelProcessor(NoAccel, 800.0).also { it.speedOf(MouseEvent(0, 1, 0)) }.speedOf(MouseEvent(1 * ms, 16, 0)), 1e-9)
    }

    @Test
    fun `reports that share a timestamp are treated as arriving at the fastest possible rate, which is why batched timestamps are refused for acceleration`() {
        val p = AccelProcessor(NoAccel, dpi = 800.0)
        p.speedOf(MouseEvent(0, 1, 0))
        val honest = AccelProcessor(NoAccel, dpi = 800.0).also { it.speedOf(MouseEvent(0, 1, 0)) }.speedOf(MouseEvent(1 * ms, 1, 0))
        val batched = p.speedOf(MouseEvent(0, 1, 0)) // dt = 0 is clamped to half the shortest polling interval
        assertTrue(batched / honest >= 15.9, "batched reports look ${batched / honest}x faster")
    }

    @Test
    fun `the first report after a pause sees the longest time and so a low speed`() {
        val p = AccelProcessor(NoAccel, dpi = 1000.0)
        assertEquals(0.1, p.speedOf(MouseEvent(5_000 * ms, 10, 0)), 1e-9)
        p.reset()
        assertEquals(0.1, p.speedOf(MouseEvent(9_000 * ms, 10, 0)), 1e-9)
    }
}
