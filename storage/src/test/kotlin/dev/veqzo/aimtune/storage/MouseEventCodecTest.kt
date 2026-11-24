package dev.veqzo.aimtune.storage

import dev.veqzo.aimtune.core.input.MouseEvent
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MouseEventCodecTest {
    private val origin = 5_000_000_000L

    @Test
    fun `empty list round trips`() {
        val bytes = MouseEventCodec.encode(emptyList(), origin)
        assertTrue(MouseEventCodec.decode(bytes, 0, origin).isEmpty())
    }

    @Test
    fun `events round trip exactly including negative counts and large gaps`() {
        val events = listOf(
            MouseEvent(origin + 1, -3, 7),
            MouseEvent(origin + 125_000, 120, -250),
            MouseEvent(origin + 125_000, 0, 1),
            MouseEvent(origin + 9_000_000_000L, Short.MAX_VALUE.toInt(), Short.MIN_VALUE.toInt()),
        )
        assertEquals(events, MouseEventCodec.decode(MouseEventCodec.encode(events, origin), events.size, origin))
    }

    @Test
    fun `events before the origin are supported`() {
        val events = listOf(MouseEvent(origin - 10, 1, 1), MouseEvent(origin + 10, 2, 2))
        assertEquals(events, MouseEventCodec.decode(MouseEventCodec.encode(events, origin), 2, origin))
    }

    @Test
    fun `a realistic 8 khz round stays small`() {
        val random = Random(1)
        var t = origin
        val events = List(45 * 8000) {
            t += 125_000L + random.nextLong(-5_000, 5_000)
            MouseEvent(t, random.nextInt(-6, 7), random.nextInt(-6, 7))
        }
        val bytes = MouseEventCodec.encode(events, origin)
        assertEquals(events, MouseEventCodec.decode(bytes, events.size, origin))
        assertTrue(bytes.size < events.size * 6, "${bytes.size} bytes for ${events.size} events")
    }

    @Test
    fun `corrupt or mismatched data is rejected`() {
        val bytes = MouseEventCodec.encode(listOf(MouseEvent(origin + 1, 1, 1)), origin)
        assertFailsWith<IllegalArgumentException> { MouseEventCodec.decode(bytes, 5, origin) }
        assertFailsWith<IllegalArgumentException> { MouseEventCodec.decode(byteArrayOf(9, 9, 9), 1, origin) }
        assertFailsWith<IllegalArgumentException> { MouseEventCodec.decode(bytes, 0, origin) }
        assertFailsWith<IllegalArgumentException> { MouseEventCodec.decode(byteArrayOf(1, 0, 1, 2, 3), 1, origin) }
    }
}
