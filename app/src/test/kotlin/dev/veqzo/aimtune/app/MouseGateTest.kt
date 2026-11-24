package dev.veqzo.aimtune.app

import dev.veqzo.aimtune.core.input.MouseEvent
import dev.veqzo.aimtune.core.input.SystemNanoClock
import dev.veqzo.aimtune.input.RawMouseSource
import dev.veqzo.aimtune.app.ui.UiHolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MouseGateTest {
    private class RecordingSource : RawMouseSource {
        val calls = mutableListOf<Boolean>()
        override val name = "fake"
        override val clock = SystemNanoClock
        override val eventsReceived = 0L
        override fun start() = Unit
        override fun setWindowFocused(focused: Boolean) {
            calls += focused
        }
        override fun drain(consumer: (MouseEvent) -> Unit) = Unit
        override fun close() = Unit
    }

    @Test
    fun `the source only listens when the window is focused and a screen allows it`() {
        val source = RecordingSource()
        val gate = MouseGate(source)
        gate.setWindowFocused(true)
        assertEquals(false, source.calls.last())
        gate.setAllowed(true)
        assertEquals(true, source.calls.last())
        gate.setWindowFocused(false)
        assertEquals(false, source.calls.last())
        gate.setWindowFocused(true)
        assertEquals(true, source.calls.last())
        gate.setAllowed(false)
        assertEquals(false, source.calls.last())
    }

    @Test
    fun `allowing without focus never opens the source`() {
        val source = RecordingSource()
        val gate = MouseGate(source)
        gate.setAllowed(true)
        assertFalse(source.calls.any { it })
    }

    @Test
    fun `the skin is rebuilt only for noticeable size changes`() {
        assertFalse(UiHolder.needsRebuild(builtFor = 1080, now = 1080))
        assertFalse(UiHolder.needsRebuild(builtFor = 1080, now = 1040))
        assertTrue(UiHolder.needsRebuild(builtFor = 1080, now = 720))
        assertTrue(UiHolder.needsRebuild(builtFor = 720, now = 1080))
        assertFalse(UiHolder.needsRebuild(builtFor = 1080, now = 0))
    }
}
