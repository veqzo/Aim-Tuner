package dev.veqzo.aimtune.app

import dev.veqzo.aimtune.core.input.MouseEvent
import dev.veqzo.aimtune.core.input.NanoClock
import dev.veqzo.aimtune.core.recording.RoundRecording
import dev.veqzo.aimtune.core.scenario.ShotResult
import dev.veqzo.aimtune.core.scenario.TargetEvent
import dev.veqzo.aimtune.games.GameProfiles
import dev.veqzo.aimtune.input.RawMouseSource
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrainerTest {
    private class FakeSource : RawMouseSource {
        var now = 0L
        private val queue = ConcurrentLinkedQueue<MouseEvent>()
        override val name = "fake"
        override val clock = NanoClock { now }
        override var reportsClicks = false
        override var eventsReceived = 0L
        override fun start() = Unit
        override fun setWindowFocused(focused: Boolean) = Unit
        override fun close() = Unit
        fun push(dx: Int, dy: Int, click: Boolean = false) {
            queue.add(MouseEvent(now, dx, dy, click))
            eventsReceived++
        }
        override fun drain(consumer: (MouseEvent) -> Unit) {
            while (true) consumer(queue.poll() ?: return)
        }
    }

    private val options = TrainerConfig(
        dpi = 800.0, cm360 = 40.0, profile = checkNotNull(GameProfiles.load().find("valorant")), roundSeconds = 2, seed = 5,
    )
    private val second = 1_000_000_000L

    private fun trainer(source: FakeSource, sink: (RoundRecording) -> Unit) = Trainer(options, source, sink)

    @Test
    fun `a finished round is delivered with its mouse and target events`() {
        val source = FakeSource()
        val finished = mutableListOf<RoundRecording>()
        val trainer = trainer(source) { finished += it }

        source.now = 10 * second
        trainer.startRound()
        source.now += second / 2
        source.push(120, -4)
        trainer.update()
        source.now += second / 2
        source.push(-30, 8)
        trainer.update()
        assertEquals(Phase.RUNNING, trainer.phase)
        assertTrue(finished.isEmpty())

        source.now = 10 * second + 2 * second
        trainer.update()

        assertEquals(Phase.FINISHED, trainer.phase)
        val r = finished.single()
        assertEquals("flick", r.scenarioId)
        assertEquals(5L, r.seed)
        assertEquals(800.0, r.dpi)
        assertEquals(40.0, r.cm360)
        assertEquals(10 * second, r.startNanos)
        assertEquals(12 * second, r.endNanos)
        assertEquals(listOf(120 to -4, -30 to 8), r.mouseEvents.map { it.dx to it.dy })
        assertTrue(r.targetEvents.first() is TargetEvent.Spawned)
    }

    @Test
    fun `mouse reports after the round end are not part of the recording`() {
        val source = FakeSource()
        val finished = mutableListOf<RoundRecording>()
        val trainer = trainer(source) { finished += it }
        source.now = 0
        trainer.startRound()
        source.now = 2 * second + 5_000_000
        source.push(50, 50)
        trainer.update()
        assertTrue(finished.single().mouseEvents.isEmpty())
    }

    @Test
    fun `aborting discards the round and delivers nothing`() {
        val source = FakeSource()
        val finished = mutableListOf<RoundRecording>()
        val trainer = trainer(source) { finished += it }
        trainer.startRound()
        source.push(10, 10)
        trainer.update()
        trainer.abort()
        assertEquals(Phase.READY, trainer.phase)
        source.now += 5 * second
        trainer.update()
        assertTrue(finished.isEmpty())
        assertNull(trainer.summary)
    }

    @Test
    fun `the next round uses the next seed and starts clean`() {
        val source = FakeSource()
        val finished = mutableListOf<RoundRecording>()
        val trainer = trainer(source) { finished += it }
        trainer.startRound()
        source.now += 3 * second
        trainer.update()
        trainer.startRound()
        source.now += 3 * second
        trainer.update()
        assertEquals(listOf(5L, 6L), finished.map { it.seed })
        assertNotNull(finished[1].targetEvents.firstOrNull())
        assertTrue(finished[1].mouseEvents.isEmpty())
    }

    @Test
    fun `input outside a running round moves nothing and is not recorded`() {
        val source = FakeSource()
        val trainer = trainer(source) { }
        source.push(500, 500)
        trainer.update()
        assertEquals(0.0, trainer.view.yawDegrees)
        assertEquals(500, trainer.lastEvent?.dx)
    }

    @Test
    fun `a click in the report stream is shot at the view of its own report, not at the view of the frame`() {
        val source = FakeSource().also { it.reportsClicks = true }
        val finished = mutableListOf<RoundRecording>()
        val trainer = trainer(source) { finished += it }
        source.now = 10 * second
        trainer.startRound()
        val target = trainer.activeTargets.single()
        val yaw = Math.toDegrees(Math.atan2(target.center.x, -target.center.z))
        val pitch = Math.toDegrees(Math.asin(target.center.y / target.center.length))
        val dpc = trainer.degreesPerCountValue

        source.now += 400_000_000L
        source.push(Math.round(yaw / dpc).toInt(), -Math.round(pitch / dpc).toInt())
        source.now += 1_000_000L
        source.push(0, 0, click = true)
        // The hand keeps moving after the click, and the frame is only processed later.
        source.now += 1_000_000L
        source.push(900, 900)
        source.now += 12_000_000L
        trainer.update()

        assertEquals(1, trainer.hits, "the shot belongs to the click's report")
        assertEquals(0, trainer.misses)
        assertEquals(ShotResult.IGNORED, trainer.fire(), "clicks of the UI are not shots when the stream reports clicks")
    }

    @Test
    fun `a click right after the round started is the one that started it and is not a shot`() {
        val source = FakeSource().also { it.reportsClicks = true }
        val trainer = trainer(source) { }
        source.now = 10 * second
        trainer.startRound()
        source.now += 20_000_000L
        source.push(0, 0, click = true)
        trainer.update()
        assertEquals(0, trainer.misses)
    }
}
