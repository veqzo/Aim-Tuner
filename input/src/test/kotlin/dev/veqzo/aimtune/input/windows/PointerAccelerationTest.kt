package dev.veqzo.aimtune.input.windows

import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PointerAccelerationTest {
    @Test
    fun `level zero is off and any other level is on`() {
        assertFalse(PointerAcceleration(0).enabled)
        assertTrue(PointerAcceleration(1).enabled)
        assertTrue(PointerAcceleration(2).enabled)
    }

    @Test
    fun `the current system setting can be read on windows`() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"), "Windows only")
        val acceleration = assertNotNull(WindowsPointerSettings.readAcceleration())
        assertTrue(acceleration.level in 0..2, "level ${acceleration.level}")
    }

    @Test
    fun `reading is a no-op elsewhere`() {
        assumeTrue(!System.getProperty("os.name").startsWith("Windows"), "non-Windows only")
        assertEquals(null, WindowsPointerSettings.readAcceleration())
    }
}
