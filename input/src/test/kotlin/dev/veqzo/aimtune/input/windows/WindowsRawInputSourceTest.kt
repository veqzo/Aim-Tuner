package dev.veqzo.aimtune.input.windows

import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Lifecycle checks only; real mouse input cannot be injected without moving the user's cursor. */
class WindowsRawInputSourceTest {
    private fun windowsOnly() = assumeTrue(System.getProperty("os.name").startsWith("Windows"), "Windows only")

    @Test
    fun `qpc clock is monotonic and in nanoseconds`() {
        windowsOnly()
        val clock = QpcClock()
        val a = clock.nowNanos()
        Thread.sleep(20)
        val b = clock.nowNanos()
        assertTrue(b - a in 15_000_000L..2_000_000_000L, "elapsed ${b - a} ns")
    }

    @Test
    fun `no events are accepted while the window is unfocused`() {
        windowsOnly()
        WindowsRawInputSource().use { source ->
            source.start()
            source.setWindowFocused(true)
            source.setWindowFocused(false)
            Thread.sleep(100)
            val before = source.eventsReceived
            Thread.sleep(200)
            assertEquals(before, source.eventsReceived)
        }
    }

    @Test
    fun `can be started again after close`() {
        windowsOnly()
        repeat(2) {
            WindowsRawInputSource().use { it.start() }
        }
    }
}
