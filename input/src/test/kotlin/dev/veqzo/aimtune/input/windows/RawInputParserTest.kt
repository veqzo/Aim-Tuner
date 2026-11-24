package dev.veqzo.aimtune.input.windows

import com.sun.jna.Memory
import com.sun.jna.Native
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Builds packets byte by byte at the offsets derived from the Microsoft struct definitions
 * (x64: RAWINPUTHEADER is 24 bytes, RAWMOUSE follows at offset 24) and checks the JNA mapping decodes them.
 */
class RawInputParserTest {
    private fun x64() = assumeTrue(Native.POINTER_SIZE == 8, "layout offsets below assume 64-bit")

    private fun packet(type: Int = RIM_TYPEMOUSE, flags: Int = 0, dx: Int, dy: Int, size: Int = 48, buttons: Int = 0): Memory {
        val memory = Memory(64)
        memory.clear()
        val buffer = memory.getByteBuffer(0, 64).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(0, type)
        buffer.putInt(4, size)
        buffer.putShort(24, flags.toShort())
        buffer.putInt(28, buttons)
        buffer.putInt(36, dx)
        buffer.putInt(40, dy)
        return memory
    }

    @Test
    fun `structure sizes match the documented x64 layout`() {
        x64()
        assertEquals(24, RawInputParser.headerSize)
        assertEquals(48, RawInputParser.mouseInputSize)
        assertEquals(16, RawInputDevice().size())
    }

    @Test
    fun `decodes relative movement with sign`() {
        x64()
        val move = RawInputParser.parseRelativeMove(packet(dx = -5, dy = 7), 48)
        assertNotNull(move)
        assertEquals(-5, move.dx)
        assertEquals(7, move.dy)
    }

    @Test
    fun `ignores absolute devices`() {
        x64()
        assertNull(RawInputParser.parseRelativeMove(packet(flags = MOUSE_MOVE_ABSOLUTE, dx = 100, dy = 100), 48))
    }

    @Test
    fun `a left button press is decoded with or without movement, other buttons and empty reports are not clicks`() {
        x64()
        assertEquals(RawMouseMove(0, 0, true), RawInputParser.parseRelativeMove(packet(dx = 0, dy = 0, buttons = RI_MOUSE_LEFT_BUTTON_DOWN), 48))
        assertEquals(RawMouseMove(4, -2, true), RawInputParser.parseRelativeMove(packet(dx = 4, dy = -2, buttons = RI_MOUSE_LEFT_BUTTON_DOWN), 48))
        assertEquals(RawMouseMove(4, -2, false), RawInputParser.parseRelativeMove(packet(dx = 4, dy = -2, buttons = 0x0002), 48))
        // The left button going up (0x2) or the right one (0x4) is not a shot.
        assertNull(RawInputParser.parseRelativeMove(packet(dx = 0, dy = 0, buttons = 0x0004), 48))
    }

    @Test
    fun `ignores empty reports and non mouse packets`() {
        x64()
        assertNull(RawInputParser.parseRelativeMove(packet(dx = 0, dy = 0), 48))
        assertNull(RawInputParser.parseRelativeMove(packet(type = 1, dx = 3, dy = 3), 48))
    }

    @Test
    fun `ignores truncated data`() {
        x64()
        assertNull(RawInputParser.parseRelativeMove(packet(dx = 3, dy = 3), 20))
    }

    @Test
    fun `qpc ticks convert to nanoseconds without overflow`() {
        val freq = 10_000_000L
        assertEquals(1_000_000_000L, QpcClock.ticksToNanos(freq, freq))
        assertEquals(100L, QpcClock.ticksToNanos(1, freq))
        val longUptime = 10L * 365 * 24 * 3600 * freq
        assertEquals(10L * 365 * 24 * 3600 * 1_000_000_000L, QpcClock.ticksToNanos(longUptime, freq))
    }
}
