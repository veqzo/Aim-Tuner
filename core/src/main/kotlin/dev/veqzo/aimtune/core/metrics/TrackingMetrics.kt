package dev.veqzo.aimtune.core.metrics

import dev.veqzo.aimtune.core.recording.RoundRecording
import dev.veqzo.aimtune.core.recording.ViewTrack
import dev.veqzo.aimtune.core.scenario.TargetEvent
import dev.veqzo.aimtune.core.scenario.TargetPath
import kotlin.math.atan

/** How well the crosshair followed a moving target. */
data class TrackingMetrics(val onTargetRatio: Double, val meanDistanceDegrees: Double)

private const val SAMPLE_NANOS = 10_000_000L

/**
 * Samples the round every 10 ms from the target's spawn: the crosshair is on target when its angle
 * to the target's center is within the target's angular radius. Returns null if no target spawned.
 */
fun computeTrackingMetrics(recording: RoundRecording, track: ViewTrack = ViewTrack(recording)): TrackingMetrics? {
    val spawn = recording.targetEvents.filterIsInstance<TargetEvent.Spawned>().firstOrNull() ?: return null
    val path = TargetPath(recording.targetEvents)
    val radiusDegrees = Math.toDegrees(atan(spawn.target.radius / spawn.target.center.length))
    var samples = 0
    var onTarget = 0
    var distanceSum = 0.0
    var t = spawn.timeNanos
    while (t <= recording.endNanos) {
        val center = path.positionAt(spawn.target.id, t) ?: break
        val distance = track.viewAt(t).direction().angleToDegrees(center)
        samples++
        if (distance <= radiusDegrees) onTarget++
        distanceSum += distance
        t += SAMPLE_NANOS
    }
    if (samples == 0) return null
    return TrackingMetrics(onTarget.toDouble() / samples, distanceSum / samples)
}
