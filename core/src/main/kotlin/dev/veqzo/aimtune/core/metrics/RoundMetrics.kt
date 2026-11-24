package dev.veqzo.aimtune.core.metrics

import dev.veqzo.aimtune.core.analysis.SegmentationParams
import dev.veqzo.aimtune.core.analysis.Submovement
import dev.veqzo.aimtune.core.analysis.segmentSubmovements
import dev.veqzo.aimtune.core.recording.RoundRecording
import dev.veqzo.aimtune.core.math.Vec3
import dev.veqzo.aimtune.core.recording.ViewTrack
import dev.veqzo.aimtune.core.scenario.ScenarioKind
import dev.veqzo.aimtune.core.scenario.TargetEvent
import dev.veqzo.aimtune.core.scenario.TargetPath
import dev.veqzo.aimtune.core.scenario.summarize
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sqrt

data class MetricsParams(
    val segmentation: SegmentationParams = SegmentationParams(),
    /** Targets closer than this to the crosshair at spawn time are not analysed as movements. */
    val minTargetDistanceDegrees: Double = 2.0,
    /**
     * The speed peak of the first reach comes at least this long after a target appeared. A reach
     * whose peak comes earlier is the hand still moving from the previous click, not a reaction
     * (docs/history/REVIEW.md C2). The peak, not the start, because the start of a smooth reach is found
     * early (the slow ramp-up is above the low end threshold). Not applied to targets that were
     * already visible, where the player may be on the way before the previous hit.
     */
    val minReactionPeakNanos: Long = 120_000_000L,
    /** The first reach covers at least this share of the distance to the target; smaller movements are tremor, not a reach. */
    val minFirstReachFraction: Double = 0.25,
    /**
     * Range rounds: half of the horizontal field of view (Valorant's 103 degrees at 16:9). A bot that spawns outside it counts
     * as appearing when it enters the view; the search turn before that is not part of its time to hit and its first reach.
     */
    val rangeHalfFovDegrees: Double = 51.5,
)

/** One reach from a target's spawn to the hit on it. */
data class MovementMetric(
    val targetId: Int,
    val timeToTargetNanos: Long,
    val initialDistanceDegrees: Double,
    /** Spawn until the first submovement starts, if any submovement was detected. */
    val reactionNanos: Long?,
    /** Signed endpoint error of the first submovement relative to the target distance: > 0 overshoot, < 0 undershoot. */
    val firstErrorRatio: Double?,
    val firstPeakSpeedCountsPerMs: Double?,
    val correctiveSubmovements: Int,
    /** Straight angular distance divided by the angular path actually travelled, in (0, 1]. */
    val pathEfficiency: Double?,
    /** Where the first submovement ended relative to the target, on the screen plane in degrees: > 0 is right of the target. */
    val firstErrorHDegrees: Double? = null,
    /** Same for the vertical axis: > 0 is above the target (the crosshair ended too high). */
    val firstErrorVDegrees: Double? = null,
    /**
     * The target moved between the start of the reach and the end of its first submovement. The errors of such a reach are
     * measured against the target's position at the end of that submovement, and the speed-error analysis leaves it out
     * (the hand aimed at a moving target, not at a place; docs/ALGORITHMS.md "Moving targets").
     */
    val targetMoved: Boolean = false,
    /** Only for a moving target: the endpoint error along the target's motion in degrees, > 0 is ahead of the target (a lead that was too long). */
    val leadErrorDegrees: Double? = null,
    /** Only for a moving target: the endpoint error across its motion in degrees, > 0 is to the left of the direction it moves in on the screen. */
    val aimErrorDegrees: Double? = null,
)

data class RoundMetrics(
    val hits: Int,
    val shots: Int,
    val accuracy: Double,
    /** Share of reaches whose first click hit (null without clicks); the accuracy the score uses. */
    val firstShotAccuracy: Double?,
    val movements: List<MovementMetric>,
    val meanTimeToHitMs: Double?,
    val meanFirstError: Double?,
    val meanAbsFirstError: Double?,
    /** Sample standard deviation of the signed first-submovement errors; the "consistency" metric (lower is better). */
    val errorSpread: Double?,
    val meanCorrectiveSubmovements: Double?,
    val meanPathEfficiency: Double?,
    /** Share of the round the crosshair was on the moving target; only for tracking rounds. */
    val trackingOnTargetRatio: Double? = null,
    /** Mean angle between crosshair and the moving target's center in degrees; only for tracking rounds. */
    val trackingMeanDistanceDegrees: Double? = null,
    /** Share of flashes the player had turned away from when they popped; only for flash rounds. */
    val flashDodgeRate: Double? = null,
    /** Mean time from a flash becoming visible until it was beyond the safe angle, over the flashes dodged in time. */
    val flashMeanTurnMs: Double? = null,
    /** Mean peak speed of the turn away in counts per ms; the curve analysis uses the return movements, this one is a diagnostic. */
    val flashTurnPeakSpeed: Double? = null,
    /** Mean absolute horizontal and vertical endpoint error of the first submovements in degrees; they show which axis the misses come from. */
    val meanAbsFirstErrorHDegrees: Double? = null,
    val meanAbsFirstErrorVDegrees: Double? = null,
    /** Mean signed vertical endpoint error in degrees: > 0 means the first movement tends to end above the target. */
    val meanFirstErrorVDegrees: Double? = null,
    /** Moving targets only: mean signed (> 0 ahead of the target), and mean absolute, endpoint error along the target's motion in degrees. */
    val meanLeadErrorDegrees: Double? = null,
    val meanAbsLeadErrorDegrees: Double? = null,
    /** Moving targets only: mean absolute endpoint error across the target's motion in degrees. */
    val meanAbsAimErrorDegrees: Double? = null,
    /** Range rounds only: bots killed, the median time from a bot's spawn to its kill, and clicks per kill. */
    val rangeKills: Int? = null,
    val rangeMedianTimeToKillMs: Double? = null,
    val rangeShotsPerKill: Double? = null,
    /** Range rounds only: bots that spawned outside the field of view, and the median time until a bot was in view (over those). */
    val rangeOffScreenSpawns: Int? = null,
    val rangeMedianSearchMs: Double? = null,
) {
    /** Share of the endpoint error (summed over both axes) that is vertical, null without first movements. */
    val verticalErrorShare: Double?
        get() {
            val h = meanAbsFirstErrorHDegrees ?: return null
            val v = meanAbsFirstErrorVDegrees ?: return null
            return if (h + v > 0.0) v / (h + v) else null
        }
}

/** Computes the metrics of a round from its recording, as defined in docs/ALGORITHMS.md. */
fun computeRoundMetrics(recording: RoundRecording, params: MetricsParams = MetricsParams()): RoundMetrics {
    val submovements = segmentSubmovements(recording.mouseEvents, recording.dpi, params.segmentation)
    val track = ViewTrack(recording)
    val spawns = recording.targetEvents.filterIsInstance<TargetEvent.Spawned>().associateBy { it.target.id }
    val path = TargetPath(recording.targetEvents)
    val isRange = ScenarioKind.fromId(recording.scenarioId) == ScenarioKind.RANGE
    val appearedAt = if (isRange) botAppearances(recording, track, path, params) else emptyMap()
    val summary = summarize(recording.targetEvents, appearedAt)
    val hitEvents = recording.targetEvents.filterIsInstance<TargetEvent.Hit>()
    val movements = hitEvents.mapIndexedNotNull { index, hit ->
        val spawn = spawns[hit.targetId] ?: return@mapIndexedNotNull null
        // The reach starts at the later of the target's spawn and the previous hit (several targets can wait at once).
        val appeared = appearedAt[spawn.target.id] ?: spawn.timeNanos
        val from = maxOf(appeared, hitEvents.getOrNull(index - 1)?.timeNanos ?: spawn.timeNanos)
        movementMetric(recording, track, submovements, path, spawn, from, hit.timeNanos, params, appeared)
    }
    val tracking = if (ScenarioKind.fromId(recording.scenarioId) == ScenarioKind.TRACKING) computeTrackingMetrics(recording, track) else null
    val flash = if (ScenarioKind.fromId(recording.scenarioId) == ScenarioKind.FLASH) computeFlashMetrics(recording, track, submovements) else null
    val range = if (isRange) computeRangeMetrics(recording, appearedAt) else null
    val errors = movements.mapNotNull { it.firstErrorRatio }
    return RoundMetrics(
        hits = summary.hits,
        shots = summary.shots,
        accuracy = summary.accuracy,
        firstShotAccuracy = summary.firstShotAccuracy,
        movements = movements,
        meanTimeToHitMs = summary.meanTimeToHitNanos?.let { it / 1e6 },
        meanFirstError = errors.averageOrNull(),
        meanAbsFirstError = errors.map { abs(it) }.averageOrNull(),
        errorSpread = errors.sampleStdDevOrNull(),
        meanCorrectiveSubmovements = movements.map { it.correctiveSubmovements.toDouble() }.averageOrNull(),
        meanPathEfficiency = movements.mapNotNull { it.pathEfficiency }.averageOrNull(),
        trackingOnTargetRatio = tracking?.onTargetRatio,
        trackingMeanDistanceDegrees = tracking?.meanDistanceDegrees,
        flashDodgeRate = flash?.dodgeRate,
        flashMeanTurnMs = flash?.meanTurnMs,
        flashTurnPeakSpeed = flash?.meanTurnPeakSpeed,
        meanAbsFirstErrorHDegrees = movements.mapNotNull { it.firstErrorHDegrees }.map { abs(it) }.averageOrNull(),
        meanAbsFirstErrorVDegrees = movements.mapNotNull { it.firstErrorVDegrees }.map { abs(it) }.averageOrNull(),
        meanFirstErrorVDegrees = movements.mapNotNull { it.firstErrorVDegrees }.averageOrNull(),
        meanLeadErrorDegrees = movements.mapNotNull { it.leadErrorDegrees }.averageOrNull(),
        meanAbsLeadErrorDegrees = movements.mapNotNull { it.leadErrorDegrees }.map { abs(it) }.averageOrNull(),
        meanAbsAimErrorDegrees = movements.mapNotNull { it.aimErrorDegrees }.map { abs(it) }.averageOrNull(),
        rangeKills = range?.kills,
        rangeMedianTimeToKillMs = range?.medianTimeToKillMs,
        rangeShotsPerKill = range?.shotsPerKill,
        rangeOffScreenSpawns = range?.offScreenSpawns,
        rangeMedianSearchMs = range?.medianSearchMs,
    )
}

/**
 * The first reach toward every target of a round with one target at a time, also toward targets that disappeared before
 * they were hit (speed rounds, `FlickConfig.targetLifetimeNanos`): such a target's window ends where the next one
 * appeared. [RoundMetrics.movements] keeps only hit targets; under time pressure that would keep the reaches that
 * happened to land well and bias their error toward zero.
 */
fun allFirstReaches(recording: RoundRecording, params: MetricsParams = MetricsParams()): List<MovementMetric> {
    val submovements = segmentSubmovements(recording.mouseEvents, recording.dpi, params.segmentation)
    val track = ViewTrack(recording)
    val path = TargetPath(recording.targetEvents)
    val hits = recording.targetEvents.filterIsInstance<TargetEvent.Hit>().associate { it.targetId to it.timeNanos }
    val despawns = recording.targetEvents.filterIsInstance<TargetEvent.Despawned>().associate { it.targetId to it.timeNanos }
    val spawns = recording.targetEvents.filterIsInstance<TargetEvent.Spawned>()
    val roundEnd = recording.mouseEvents.lastOrNull()?.timeNanos ?: return emptyList()
    return spawns.mapIndexedNotNull { index, spawn ->
        val end = hits[spawn.target.id] ?: despawns[spawn.target.id] ?: spawns.getOrNull(index + 1)?.timeNanos ?: roundEnd
        if (end <= spawn.timeNanos) null else movementMetric(recording, track, submovements, path, spawn, spawn.timeNanos, end, params)
    }
}

/** Offset on the screen plane, in degrees, with yaw scaled by cos(pitch) so both axes are true angles. */
private data class Offset(val x: Double, val y: Double) {
    val length: Double get() = hypot(x, y)
}

private fun offset(fromYaw: Double, fromPitch: Double, toYaw: Double, toPitch: Double, referencePitchDegrees: Double): Offset {
    var dYaw = (toYaw - fromYaw) % 360.0
    if (dYaw > 180.0) dYaw -= 360.0
    if (dYaw < -180.0) dYaw += 360.0
    return Offset(dYaw * cos(Math.toRadians(referencePitchDegrees)), toPitch - fromPitch)
}

private fun movementMetric(
    recording: RoundRecording,
    track: ViewTrack,
    submovements: List<Submovement>,
    path: TargetPath,
    spawn: TargetEvent.Spawned,
    fromNanos: Long,
    untilNanos: Long,
    params: MetricsParams,
    /** When the target came into view; later than the spawn for a Range bot that spawned outside the field of view. */
    appearedNanos: Long = spawn.timeNanos,
): MovementMetric? {
    val t0 = fromNanos
    val t1 = untilNanos
    val start = track.viewAt(t0)
    // A static target stays where it spawned; a moving one is followed from its waypoints.
    val startCenter = path.positionAt(spawn.target.id, t0) ?: spawn.target.center
    val (targetYaw, targetPitch) = directionAngles(startCenter)
    val toTarget = offset(start.yawDegrees, start.pitchDegrees, targetYaw, targetPitch, targetPitch)
    val distance = toTarget.length
    if (distance < params.minTargetDistanceDegrees) return null

    // A reach right after a hit starts on the way (no reaction limit); one toward a target that only now came into view has it.
    val newTargetAt = when {
        appearedNanos > spawn.timeNanos && fromNanos <= appearedNanos -> appearedNanos
        fromNanos > spawn.timeNanos -> null
        else -> t0
    }
    val inWindow = submovements.filter { it.peakNanos in t0..t1 }
    val firstIndex = inWindow.indexOfFirst { isFirstReach(it, track, newTargetAt, toTarget, distance, targetPitch, params) }
    val first = inWindow.getOrNull(firstIndex)
    val firstMoved = first?.let {
        val end = track.viewAt(it.endNanos - 1)
        offset(start.yawDegrees, start.pitchDegrees, end.yawDegrees, end.pitchDegrees, targetPitch)
    }
    // The error of a reach is measured against the target where it was when the reach ended, not where it was when the reach began.
    val firstEndNanos = first?.let { it.endNanos - 1 }
    val targetMoved = path.movesDuring(spawn.target.id, t0, firstEndNanos ?: t1)
    val toEnd = if (targetMoved) {
        val (endYaw, endPitch) = directionAngles(path.positionAt(spawn.target.id, firstEndNanos ?: t1) ?: startCenter)
        offset(start.yawDegrees, start.pitchDegrees, endYaw, endPitch, targetPitch)
    } else {
        toTarget
    }
    val reach = toEnd.length
    val firstError = firstMoved?.let { moved ->
        // A target that came to rest on the crosshair leaves no distance to relate the error to.
        if (targetMoved && reach < params.minTargetDistanceDegrees) return@let null
        val along = (moved.x * toEnd.x + moved.y * toEnd.y) / reach
        (along - reach) / reach
    }
    val errorH = firstMoved?.let { it.x - toEnd.x }
    val errorV = firstMoved?.let { it.y - toEnd.y }
    val motion = Offset(toEnd.x - toTarget.x, toEnd.y - toTarget.y)
    val components = if (targetMoved && errorH != null && errorV != null && motion.length >= MIN_MOTION_DEGREES) {
        val ux = motion.x / motion.length
        val uy = motion.y / motion.length
        (errorH * ux + errorV * uy) to (-errorH * uy + errorV * ux)
    } else {
        null
    }
    val path = angularPath(recording, track, t0, t1, targetPitch)
    return MovementMetric(
        targetId = spawn.target.id,
        timeToTargetNanos = t1 - t0,
        initialDistanceDegrees = distance,
        reactionNanos = first?.let { it.startNanos - t0 },
        firstErrorRatio = firstError,
        firstPeakSpeedCountsPerMs = first?.peakSpeedCountsPerMs,
        correctiveSubmovements = if (first == null) maxOf(0, inWindow.size - 1) else inWindow.size - 1 - firstIndex,
        pathEfficiency = if (path > 0.0) minOf(1.0, distance / path) else null,
        firstErrorHDegrees = errorH,
        firstErrorVDegrees = errorV,
        targetMoved = targetMoved,
        leadErrorDegrees = components?.first,
        aimErrorDegrees = components?.second,
    )
}

/** A submovement that is a reach toward the target rather than leftover motion or tremor. */
private fun isFirstReach(sub: Submovement, track: ViewTrack, newTargetAtNanos: Long?, toTarget: Offset, distance: Double, targetPitch: Double, params: MetricsParams): Boolean {
    if (newTargetAtNanos != null && sub.peakNanos < newTargetAtNanos + params.minReactionPeakNanos) return false
    val from = track.viewAt(sub.startNanos - 1)
    val to = track.viewAt(sub.endNanos - 1)
    val moved = offset(from.yawDegrees, from.pitchDegrees, to.yawDegrees, to.pitchDegrees, targetPitch)
    val along = (moved.x * toTarget.x + moved.y * toTarget.y) / distance
    return along >= params.minFirstReachFraction * distance
}

private fun angularPath(recording: RoundRecording, track: ViewTrack, t0: Long, t1: Long, referencePitchDegrees: Double): Double {
    val dpc = recording.degreesPerCount
    val yawScale = cos(Math.toRadians(referencePitchDegrees))
    return recording.mouseEvents.indices.asSequence()
        .filter { recording.mouseEvents[it].timeNanos in t0..t1 }
        .sumOf { i ->
            hypot(track.movedX[i] * dpc * yawScale, track.movedY[i] * dpc)
        }
}

/** A target that moved less than this on the screen plane has no direction of motion to split the error along. */
private const val MIN_MOTION_DEGREES = 0.1

private fun directionAngles(c: Vec3): Pair<Double, Double> {
    val yaw = Math.toDegrees(atan2(c.x, -c.z))
    val pitch = Math.toDegrees(asin(c.y / c.length))
    return yaw to pitch
}

private fun List<Double>.averageOrNull(): Double? = if (isEmpty()) null else average()

private fun List<Double>.sampleStdDevOrNull(): Double? {
    if (size < 2) return null
    val mean = average()
    return sqrt(sumOf { (it - mean) * (it - mean) } / (size - 1))
}

/** When each bot of a Range round came into view, by target id: at its spawn, or later when it spawned outside the field of view. */
private fun botAppearances(recording: RoundRecording, track: ViewTrack, path: TargetPath, params: MetricsParams): Map<Int, Long> {
    val events = recording.targetEvents
    val ends = events.filterIsInstance<TargetEvent.Hit>().associate { it.targetId to it.timeNanos } +
        events.filterIsInstance<TargetEvent.Despawned>().associate { it.targetId to it.timeNanos }
    val roundEnd = recording.mouseEvents.lastOrNull()?.timeNanos ?: 0L
    return events.filterIsInstance<TargetEvent.Spawned>().mapNotNull { spawn ->
        val end = maxOf(ends[spawn.target.id] ?: roundEnd, spawn.timeNanos)
        firstVisibleNanos(track, path, spawn, end, params.rangeHalfFovDegrees)?.let { spawn.target.id to it }
    }.toMap()
}
