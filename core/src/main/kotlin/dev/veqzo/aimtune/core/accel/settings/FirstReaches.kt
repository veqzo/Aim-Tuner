package dev.veqzo.aimtune.core.accel.settings

import dev.veqzo.aimtune.core.analysis.Submovement
import dev.veqzo.aimtune.core.analysis.segmentSubmovements
import dev.veqzo.aimtune.core.math.Vec3
import dev.veqzo.aimtune.core.metrics.MetricsParams
import dev.veqzo.aimtune.core.recording.RoundRecording
import dev.veqzo.aimtune.core.recording.ViewTrack
import dev.veqzo.aimtune.core.scenario.ScenarioKind
import dev.veqzo.aimtune.core.scenario.TargetEvent
import dev.veqzo.aimtune.core.scenario.TargetPath
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot

/**
 * The first movement toward a static target, in mouse counts (x right, y down), next to the movement a linear mouse would have
 * needed: [needX]/[needY] from the view at the start of the reach to the target (yaw and pitch difference divided by
 * [degreesPerCount]), [gotX]/[gotY] the raw counts of the first submovement. The window [startNanos, endNanos) is what
 * [SnapReplay] replays.
 */
data class FirstReach(
    val scenarioId: String,
    val needX: Double,
    val needY: Double,
    val gotX: Double,
    val gotY: Double,
    val degreesPerCount: Double,
    val startNanos: Long,
    val endNanos: Long,
    val roundIndex: Int = 0,
)

/** Scenarios whose targets are static and one reach long: flick and switching. Micro is too short, strafing kinds and range move. */
internal val PASSIVE_KINDS = setOf(ScenarioKind.FLICK, ScenarioKind.SWITCHING)

/** Reaches shorter than this (screen-plane degrees) are tremor-sized next to the hand's noise and are not used. */
internal const val MIN_REACH_DEGREES = 10.0

/**
 * The first reaches of a round played without acceleration (empty for any other round or scenario). The reach to a target starts
 * at the later of its spawn and the previous hit, like `computeRoundMetrics`, and its first submovement is chosen by the same
 * rule (peak after the reaction limit for a new target, covers 25 % of the distance); [params] are those of the metrics.
 */
fun firstReaches(recording: RoundRecording, roundIndex: Int = 0, params: MetricsParams = MetricsParams()): List<FirstReach> {
    val kind = ScenarioKind.fromId(recording.scenarioId)
    if (recording.accel != null || kind !in PASSIVE_KINDS) return emptyList()
    val submovements = segmentSubmovements(recording.mouseEvents, recording.dpi, params.segmentation)
    val track = ViewTrack(recording)
    val path = TargetPath(recording.targetEvents)
    val spawns = recording.targetEvents.filterIsInstance<TargetEvent.Spawned>().associateBy { it.target.id }
    val hits = recording.targetEvents.filterIsInstance<TargetEvent.Hit>()
    val dpc = recording.degreesPerCount
    return hits.mapIndexedNotNull { index, hit ->
        val spawn = spawns[hit.targetId] ?: return@mapIndexedNotNull null
        val from = maxOf(spawn.timeNanos, hits.getOrNull(index - 1)?.timeNanos ?: spawn.timeNanos)
        if (path.movesDuring(hit.targetId, from, hit.timeNanos)) return@mapIndexedNotNull null
        val start = track.viewAt(from)
        val (targetYaw, targetPitch) = anglesOf(spawn.target.center)
        val dYaw = wrap(targetYaw - start.yawDegrees)
        val dPitch = targetPitch - start.pitchDegrees
        val distance = hypot(dYaw * cos(Math.toRadians(targetPitch)), dPitch)
        if (distance < MIN_REACH_DEGREES) return@mapIndexedNotNull null
        val newTargetAt = if (from == spawn.timeNanos) from else null
        val first = submovements.filter { it.peakNanos in from..hit.timeNanos }
            .firstOrNull { isReach(it, track, newTargetAt, dYaw, dPitch, targetPitch, distance, params) }
            ?: return@mapIndexedNotNull null
        FirstReach(recording.scenarioId, dYaw / dpc, -dPitch / dpc, first.dx.toDouble(), first.dy.toDouble(), dpc, first.startNanos, first.endNanos, roundIndex)
    }
}

/** The first reaches of all [recordings], each tagged with its index in the list. */
fun firstReaches(recordings: List<RoundRecording>, params: MetricsParams = MetricsParams()): List<FirstReach> =
    recordings.flatMapIndexed { index, recording -> firstReaches(recording, index, params) }

private fun isReach(
    sub: Submovement,
    track: ViewTrack,
    newTargetAt: Long?,
    dYaw: Double,
    dPitch: Double,
    targetPitch: Double,
    distance: Double,
    params: MetricsParams,
): Boolean {
    if (newTargetAt != null && sub.peakNanos < newTargetAt + params.minReactionPeakNanos) return false
    val from = track.viewAt(sub.startNanos - 1)
    val to = track.viewAt(sub.endNanos - 1)
    val scale = cos(Math.toRadians(targetPitch))
    val movedX = wrap(to.yawDegrees - from.yawDegrees) * scale
    val movedY = to.pitchDegrees - from.pitchDegrees
    val along = (movedX * dYaw * scale + movedY * dPitch) / distance
    return along >= params.minFirstReachFraction * distance
}

private fun anglesOf(c: Vec3): Pair<Double, Double> = Math.toDegrees(atan2(c.x, -c.z)) to Math.toDegrees(asin(c.y / c.length))

private fun wrap(angle: Double): Double {
    var a = angle % 360.0
    if (a > 180.0) a -= 360.0
    if (a < -180.0) a += 360.0
    return a
}
