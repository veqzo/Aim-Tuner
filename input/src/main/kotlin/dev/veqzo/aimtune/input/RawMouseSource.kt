package dev.veqzo.aimtune.input

import dev.veqzo.aimtune.core.input.MouseEvent
import dev.veqzo.aimtune.core.input.NanoClock

/**
 * Source of raw relative mouse movement. Implementations deliver [MouseEvent]s from their own
 * thread into an internal queue; the consumer drains it from the render thread.
 *
 * Hard rule: a source must deliver input only while the application window is focused. The caller
 * reports focus through [setWindowFocused]; while unfocused, no events may be queued.
 */
interface RawMouseSource : AutoCloseable {
    val name: String

    /** Clock that stamps the events; the consumer should use it for any time it compares with them. */
    val clock: NanoClock

    /**
     * True when every event is stamped when its own device report arrived (Raw Input on its own
     * thread). Sources that deliver in the frame loop stamp a whole frame of reports with one time; the
     * speed of a report, which the acceleration simulation and its analysis need, is then meaningless
     * (docs/history/REVIEW.md M4), so acceleration is only offered when this is true.
     */
    val hasReportTimestamps: Boolean get() = true

    /**
     * True when the source reports the left button in [MouseEvent.leftPressed], in the same ordered
     * stream as the movement. The trainer then shoots at the view of the click's own report instead of
     * at the view of the frame in which the click happened to be processed (docs/history/REVIEW.md M8).
     */
    val reportsClicks: Boolean get() = false

    /** Reports that were lost because the consumer did not keep up (diagnostics). */
    val droppedEvents: Long get() = 0L

    /** Total events accepted since [start] (diagnostics). */
    val eventsReceived: Long

    fun start()

    fun setWindowFocused(focused: Boolean)

    /** Hands all queued events, oldest first, to [consumer] and removes them from the queue. */
    fun drain(consumer: (MouseEvent) -> Unit)

    override fun close()
}
