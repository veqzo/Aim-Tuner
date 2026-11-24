package dev.veqzo.aimtune.core.recording

import dev.veqzo.aimtune.core.accel.AccelModel
import dev.veqzo.aimtune.core.input.MouseEvent
import dev.veqzo.aimtune.core.math.ViewAngles
import dev.veqzo.aimtune.core.scenario.TargetEvent
import dev.veqzo.aimtune.core.units.degreesPerCount

/**
 * Everything needed to recompute a round's metrics: raw mouse counts with timestamps and the target
 * events, plus the sensitivity that was active. The view always starts at yaw 0, pitch 0 at
 * [startNanos]. Times are nanoseconds on one monotonic clock.
 */
data class RoundRecording(
    val scenarioId: String,
    val seed: Long,
    val dpi: Double,
    val cm360: Double,
    val startNanos: Long,
    val endNanos: Long,
    val mouseEvents: List<MouseEvent>,
    val targetEvents: List<TargetEvent>,
    /** The acceleration the round was played with (Raw Accel simulated in the trainer, the finder's curve or a whole profile); null for none. */
    val accel: AccelModel? = null,
) {
    val degreesPerCount: Double get() = degreesPerCount(dpi, cm360)
}

/** The camera orientation over time, rebuilt from the recorded counts. */
class ViewTrack(recording: RoundRecording) {
    private val times = LongArray(recording.mouseEvents.size)
    private val views: Array<ViewAngles>
    private val start = ViewAngles()

    /** The factor the acceleration applied to each report's counts (1.0 without acceleration; `|out| / |in|` for a Raw Accel profile). */
    val scales = DoubleArray(recording.mouseEvents.size) { 1.0 }

    /** The movement in counts the view got for each report, after the acceleration (the integer driver output for a profile). */
    val movedX = DoubleArray(recording.mouseEvents.size)
    val movedY = DoubleArray(recording.mouseEvents.size)

    init {
        var view = start
        val dpc = recording.degreesPerCount
        val modifier = recording.accel?.newModifier(recording.dpi)
        views = Array(recording.mouseEvents.size) { i ->
            val e = recording.mouseEvents[i]
            times[i] = e.timeNanos
            val move = modifier?.modify(e)
            val dx = move?.dx ?: e.dx.toDouble()
            val dy = move?.dy ?: e.dy.toDouble()
            scales[i] = move?.scale ?: 1.0
            movedX[i] = dx
            movedY[i] = dy
            view = view.rotatedByCounts(dx, dy, dpc)
            view
        }
    }

    /** Orientation after every event with time <= [timeNanos]. */
    fun viewAt(timeNanos: Long): ViewAngles {
        var lo = 0
        var hi = times.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (times[mid] <= timeNanos) lo = mid + 1 else hi = mid
        }
        return if (lo == 0) start else views[lo - 1]
    }
}
