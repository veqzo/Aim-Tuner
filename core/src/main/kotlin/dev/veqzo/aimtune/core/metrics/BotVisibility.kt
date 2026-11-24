package dev.veqzo.aimtune.core.metrics

import dev.veqzo.aimtune.core.recording.ViewTrack
import dev.veqzo.aimtune.core.scenario.TargetEvent
import dev.veqzo.aimtune.core.scenario.TargetPath
import kotlin.math.abs
import kotlin.math.atan2

/** How often the view and the bot are compared while a bot is out of sight; a bot moves a few centimeters in this time. */
private const val STEP_NANOS = 5_000_000L

/**
 * The first moment at or after the spawn at which the bot is inside the horizontal field of view (yaw within
 * [halfFovDegrees] of the view), or null if it never is before [untilNanos]. A bot that spawns in sight is visible at its spawn;
 * one that spawns to the side or behind becomes visible only when the player turns or the bot strafes into view. Pitch is
 * not checked: Range bots stand on the floor within a few degrees of the horizon.
 */
internal fun firstVisibleNanos(track: ViewTrack, path: TargetPath, spawn: TargetEvent.Spawned, untilNanos: Long, halfFovDegrees: Double): Long? {
    var t = spawn.timeNanos
    while (t <= untilNanos) {
        if (isInView(track, path, spawn, t, halfFovDegrees)) return t
        t += STEP_NANOS
    }
    return if (isInView(track, path, spawn, untilNanos, halfFovDegrees)) untilNanos else null
}

private fun isInView(track: ViewTrack, path: TargetPath, spawn: TargetEvent.Spawned, timeNanos: Long, halfFovDegrees: Double): Boolean {
    val center = path.positionAt(spawn.target.id, timeNanos) ?: spawn.target.center
    val targetYaw = Math.toDegrees(atan2(center.x, -center.z))
    var offset = (targetYaw - track.viewAt(timeNanos).yawDegrees) % 360.0
    if (offset > 180.0) offset -= 360.0
    if (offset < -180.0) offset += 360.0
    return abs(offset) <= halfFovDegrees
}
