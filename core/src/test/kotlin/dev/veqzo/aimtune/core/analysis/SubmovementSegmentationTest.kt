package dev.veqzo.aimtune.core.analysis

import dev.veqzo.aimtune.core.input.MouseEvent
import dev.veqzo.aimtune.core.testing.Reach
import dev.veqzo.aimtune.core.testing.SyntheticMouse
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SubmovementSegmentationTest {
    private val dpi = 800.0

    private fun segment(events: List<MouseEvent>) = segmentSubmovements(events, dpi)

    @Test
    fun `no events give no submovements`() {
        assertTrue(segment(emptyList()).isEmpty())
    }

    @Test
    fun `a single flick is one submovement with its displacement and duration`() {
        val events = SyntheticMouse.events(listOf(Reach(startMs = 50.0, durationMs = 150.0, dx = 400.0)), totalMs = 500.0)
        val result = segment(events)
        assertEquals(1, result.size)
        val m = result.single()
        assertEquals(400.0, m.dx.toDouble(), 3.0)
        assertEquals(0.0, m.dy.toDouble(), 3.0)
        assertEquals(150.0, m.durationNanos / 1e6, 40.0)
        // Minimum-jerk peak speed is 1.875 * D / T counts per ms.
        assertEquals(1.875 * 400 / 150, m.peakSpeedCountsPerMs, 0.6)
        assertTrue(abs(m.startNanos / 1e6 - 50.0) < 25.0, "start ${m.startNanos / 1e6} ms")
    }

    @Test
    fun `direction is kept for a diagonal reach`() {
        val events = SyntheticMouse.events(listOf(Reach(50.0, 150.0, dx = -300.0, dy = 200.0)), totalMs = 400.0)
        val m = segment(events).single()
        assertEquals(-300.0, m.dx.toDouble(), 3.0)
        assertEquals(200.0, m.dy.toDouble(), 3.0)
    }

    @Test
    fun `two reaches separated by a rest are two movements`() {
        val events = SyntheticMouse.events(
            listOf(Reach(50.0, 150.0, 400.0), Reach(500.0, 150.0, -380.0)),
            totalMs = 900.0,
        )
        val result = segment(events)
        assertEquals(2, result.size)
        assertTrue(result[0].dx > 0 && result[1].dx < 0)
        assertTrue(result[1].startNanos > result[0].endNanos)
    }

    @Test
    fun `a correction that starts before the first reach ends is split into two submovements`() {
        // Second reach starts when the first is nearly done, so speed dips deeply but not to rest.
        val events = SyntheticMouse.events(
            listOf(Reach(50.0, 150.0, 400.0), Reach(185.0, 120.0, 90.0)),
            totalMs = 500.0,
        )
        val result = segment(events)
        assertEquals(2, result.size, "got ${result.map { it.durationNanos / 1e6 }}")
        assertEquals(400.0, result[0].dx.toDouble(), 40.0)
        assertEquals(90.0, result[1].dx.toDouble(), 40.0)
        assertEquals(result[0].endNanos, result[1].startNanos)
    }

    @Test
    fun `a slow smooth movement is not split`() {
        val events = SyntheticMouse.events(listOf(Reach(50.0, 400.0, 300.0)), totalMs = 800.0)
        assertEquals(1, segment(events).size)
    }

    @Test
    fun `isolated single count jitter is not a movement`() {
        val jitter = listOf(MouseEvent(100_000_000, 1, 0), MouseEvent(400_000_000, 0, -1), MouseEvent(900_000_000, 1, 1))
        assertTrue(segment(jitter).isEmpty())
    }

    @Test
    fun `result is the same at 8000 hz polling`() {
        val reaches = listOf(Reach(50.0, 150.0, 400.0))
        val low = segment(SyntheticMouse.events(reaches, 500.0, pollingHz = 1000)).single()
        val high = segment(SyntheticMouse.events(reaches, 500.0, pollingHz = 8000)).single()
        assertEquals(low.dx.toDouble(), high.dx.toDouble(), 3.0)
        assertEquals(low.durationNanos / 1e6, high.durationNanos / 1e6, 6.0)
    }

    @Test
    fun `path length is at least the displacement`() {
        val events = SyntheticMouse.events(listOf(Reach(50.0, 150.0, 300.0, 100.0)), totalMs = 400.0)
        val m = segment(events).single()
        assertTrue(m.pathCounts >= kotlin.math.hypot(m.dx.toDouble(), m.dy.toDouble()) - 1e-9)
    }
}
