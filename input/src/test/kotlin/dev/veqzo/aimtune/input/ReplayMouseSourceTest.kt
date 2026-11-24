package dev.veqzo.aimtune.input

import dev.veqzo.aimtune.core.input.MouseEvent
import dev.veqzo.aimtune.core.math.Vec3
import dev.veqzo.aimtune.core.recording.RoundRecording
import dev.veqzo.aimtune.core.scenario.Target
import dev.veqzo.aimtune.core.scenario.TargetEvent
import dev.veqzo.aimtune.core.sim.ManualClock
import kotlin.test.Test
import kotlin.test.assertEquals

class ReplayMouseSourceTest {
    private val ms = 1_000_000L
    private val recording = RoundRecording(
        scenarioId = "flick", seed = 1, dpi = 800.0, cm360 = 40.0, startNanos = 100 * ms, endNanos = 200 * ms,
        mouseEvents = listOf(MouseEvent(110 * ms, 5, 0), MouseEvent(120 * ms, 3, -1), MouseEvent(150 * ms, 1, 1)),
        targetEvents = listOf(
            TargetEvent.Spawned(100 * ms, Target(0, Vec3(0.0, 0.0, -10.0), 0.25)),
            TargetEvent.Miss(130 * ms),
            TargetEvent.Hit(160 * ms, 0),
        ),
    )

    private fun drained(source: ReplayMouseSource) = buildList { source.drain { add(it) } }

    private fun startedSource(clock: ManualClock) = ReplayMouseSource(recording, clock).also {
        it.start()
        it.anchor()
    }

    @Test
    fun `events and shots are due at their recorded offsets from the anchor`() {
        val clock = ManualClock(1_000 * ms)
        val source = startedSource(clock)

        clock.advance(15 * ms)
        assertEquals(listOf(MouseEvent(1_010 * ms, 5, 0)), drained(source))
        assertEquals(0, source.consumeShots())

        clock.advance(20 * ms)
        assertEquals(listOf(MouseEvent(1_020 * ms, 3, -1)), drained(source))
        assertEquals(1, source.consumeShots())

        clock.advance(100 * ms)
        assertEquals(listOf(MouseEvent(1_050 * ms, 1, 1)), drained(source))
        assertEquals(1, source.consumeShots())
        assertEquals(3, source.eventsReceived)
    }

    @Test
    fun `nothing is delivered while the window is unfocused`() {
        val clock = ManualClock(0)
        val source = startedSource(clock).also { it.setWindowFocused(false) }
        clock.advance(500 * ms)
        assertEquals(emptyList(), drained(source))
        assertEquals(0, source.eventsReceived)
    }
}
