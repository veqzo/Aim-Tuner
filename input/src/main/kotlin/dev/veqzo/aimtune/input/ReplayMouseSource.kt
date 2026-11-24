package dev.veqzo.aimtune.input

import dev.veqzo.aimtune.core.input.MouseEvent
import dev.veqzo.aimtune.core.input.NanoClock
import dev.veqzo.aimtune.core.recording.RoundRecording
import dev.veqzo.aimtune.core.scenario.TargetEvent

/**
 * Plays a recorded round back as a [RawMouseSource]: every report is delivered when [clock] reaches
 * its recorded offset from the round start, restamped onto the replay's time. The recorded shots
 * (hits and misses) are available through [consumeShots] so the driver can fire them at the same
 * moments. Feeding this to a trainer with the recorded seed reproduces the round, which makes
 * recorded sessions regression fixtures. Call [anchor] right before the trainer starts its round.
 */
class ReplayMouseSource(private val recording: RoundRecording, override val clock: NanoClock) : RawMouseSource {
    private val shotOffsets = recording.targetEvents
        .filter { it is TargetEvent.Hit || it is TargetEvent.Miss }
        .map { it.timeNanos - recording.startNanos }
    private var origin = 0L
    private var nextEvent = 0
    private var nextShot = 0
    private var focused = true
    private var started = false
    private var delivered = 0L

    override val name = "Replay"

    override val eventsReceived: Long get() = delivered

    override fun start() {
        started = true
    }

    override fun setWindowFocused(focused: Boolean) {
        this.focused = focused
    }

    /** Declares the replay's time zero (the round start) as the clock's current time and rewinds. */
    fun anchor() {
        origin = clock.nowNanos()
        nextEvent = 0
        nextShot = 0
    }

    /** Number of recorded shots that are due since the last call. */
    fun consumeShots(): Int {
        var count = 0
        while (nextShot < shotOffsets.size && origin + shotOffsets[nextShot] <= clock.nowNanos()) {
            nextShot++
            count++
        }
        return count
    }

    override fun drain(consumer: (MouseEvent) -> Unit) {
        val now = clock.nowNanos()
        val events = recording.mouseEvents
        while (nextEvent < events.size) {
            val event = events[nextEvent]
            val due = origin + (event.timeNanos - recording.startNanos)
            if (due > now) return
            nextEvent++
            if (!started || !focused) continue
            delivered++
            consumer(MouseEvent(due, event.dx, event.dy))
        }
    }

    override fun close() {
        started = false
    }
}
