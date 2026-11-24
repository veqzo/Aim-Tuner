package dev.veqzo.aimtune.app

import kotlin.test.Test
import kotlin.test.assertEquals

class EventRateMeterTest {
    @Test
    fun `reports events per second inside the window`() {
        val meter = EventRateMeter()
        repeat(1000) { meter.record(it * 1_000_000L) }
        assertEquals(1000.0, meter.ratePerSecond(999_000_000L))
    }

    @Test
    fun `old events fall out of the window`() {
        val meter = EventRateMeter()
        repeat(100) { meter.record(it * 1_000_000L) }
        assertEquals(0.0, meter.ratePerSecond(5_000_000_000L))
    }
}
