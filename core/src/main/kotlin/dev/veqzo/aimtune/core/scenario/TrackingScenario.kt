package dev.veqzo.aimtune.core.scenario

import dev.veqzo.aimtune.core.math.Vec3
import dev.veqzo.aimtune.core.math.ViewAngles
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin
import kotlin.random.Random

/** Geometry and motion of the tracking scenario. Distances and radius are in scene units (meters). */
data class TrackingConfig(
    val durationNanos: Long,
    val maxYawDegrees: Double = 35.0,
    val maxPitchDegrees: Double = 15.0,
    val distance: Double = 14.0,
    val targetRadius: Double = 0.5,
    /** Angular speed of each straight segment, drawn uniformly. */
    val minSpeedDegreesPerSecond: Double = 6.0,
    val maxSpeedDegreesPerSecond: Double = 22.0,
    val minSegmentSeconds: Double = 1.2,
    val maxSegmentSeconds: Double = 2.8,
    /** Share of segments in which the target stands still (a counter-strafe) instead of moving. */
    val stopProbability: Double = 0.0,
    /** Vertical share of the movement of each segment; strafing on a flat floor is almost purely horizontal. */
    val pitchShare: Double = 0.5,
) {
    init {
        require(durationNanos > 0) { "durationNanos must be positive" }
        require(0.0 < minSpeedDegreesPerSecond && minSpeedDegreesPerSecond <= maxSpeedDegreesPerSecond) { "invalid speed range" }
        require(0.0 < minSegmentSeconds && minSegmentSeconds <= maxSegmentSeconds) { "invalid segment length range" }
        require(distance > 0.0 && targetRadius > 0.0) { "distance and radius must be positive" }
        require(stopProbability in 0.0..0.9) { "stop probability must be in 0..0.9" }
    }

    companion object {
        private const val REALISTIC_MAX_PITCH = 4.0
        private const val REALISTIC_PITCH_SHARE = 0.1

        /** Tracking with the angular size, strafe speed and stops of a game's [geometry]. */
        fun from(geometry: TrainerGeometry, durationNanos: Long): TrackingConfig {
            val speeds = geometry.trackingSpeedDegreesPerSecond
            return TrackingConfig(
                durationNanos = durationNanos,
                maxYawDegrees = minOf(35.0, geometry.fieldYawDegrees),
                maxPitchDegrees = minOf(if (geometry.elevation.uniform) 15.0 else REALISTIC_MAX_PITCH, geometry.fieldPitchDegrees),
                pitchShare = if (geometry.elevation.uniform) 0.5 else REALISTIC_PITCH_SHARE,
                distance = geometry.trackingDistanceMeters,
                targetRadius = geometry.radiusFor(geometry.trackingDiameterDegrees.mid, geometry.trackingDistanceMeters),
                minSpeedDegreesPerSecond = speeds.min,
                maxSpeedDegreesPerSecond = speeds.max,
                minSegmentSeconds = 0.8,
                maxSegmentSeconds = 2.2,
                stopProbability = geometry.stopProbability,
            )
        }
    }
}

/**
 * One target moves through the field in straight segments with changing speed and heading; the
 * player keeps the crosshair on it. No clicks are needed: how long the crosshair is on the target
 * is measured. The motion is planned from the seed at construction. Each segment's end is logged as
 * a [TargetEvent.Waypoint] when the segment starts (stamped with its arrival time), so the log
 * always covers the segment in progress when a round ends.
 */
class TrackingScenario(private val config: TrackingConfig, seed: Long) : Scenario {
    override val kind = ScenarioKind.TRACKING

    private class Point(val offsetNanos: Long, val center: Vec3)

    private val random = Random(seed)
    private val plan = buildPlan()
    private val eventLog = mutableListOf<TargetEvent>()
    private var startNanos: Long? = null
    private var currentNanos = 0L
    private var loggedPoints = 0

    override val targets: List<Target>
        get() = startNanos?.let { listOf(Target(TARGET_ID, positionAt(currentNanos - it), config.targetRadius)) } ?: emptyList()

    override val events: List<TargetEvent> get() = eventLog

    override fun start(nowNanos: Long) {
        startNanos = nowNanos
        currentNanos = nowNanos
        eventLog += TargetEvent.Spawned(nowNanos, Target(TARGET_ID, plan.first().center, config.targetRadius))
        loggedPoints = 1
        logAnnouncedWaypoints(nowNanos)
    }

    override fun update(nowNanos: Long) {
        if (startNanos == null) return
        currentNanos = nowNanos
        logAnnouncedWaypoints(nowNanos)
    }

    override fun isFinished(nowNanos: Long): Boolean {
        val start = startNanos ?: return false
        return nowNanos - start >= config.durationNanos
    }

    /** Tracking is not about clicking; shots do nothing. */
    override fun fire(origin: Vec3, direction: Vec3, nowNanos: Long): ShotResult = ShotResult.IGNORED

    /** Logs the waypoint that ends the segment [nowNanos] is in, and any earlier one not logged yet. */
    private fun logAnnouncedWaypoints(nowNanos: Long) {
        val start = checkNotNull(startNanos)
        val relative = nowNanos - start
        while (loggedPoints < plan.size && plan[loggedPoints - 1].offsetNanos <= relative) {
            val point = plan[loggedPoints]
            eventLog += TargetEvent.Waypoint(start + point.offsetNanos, TARGET_ID, point.center)
            loggedPoints++
        }
    }

    private fun positionAt(relativeNanos: Long): Vec3 {
        val after = plan.indexOfFirst { it.offsetNanos > relativeNanos }
        if (after == -1) return plan.last().center
        val a = plan[after - 1]
        val b = plan[after]
        return TargetPath.interpolate(a.center, b.center, (relativeNanos - a.offsetNanos).toDouble() / (b.offsetNanos - a.offsetNanos))
    }

    private fun buildPlan(): List<Point> {
        var yaw = random.nextDouble(-config.maxYawDegrees, config.maxYawDegrees) * START_SPREAD
        var pitch = random.nextDouble(-config.maxPitchDegrees, config.maxPitchDegrees) * START_SPREAD
        val durations = mutableListOf<Double>()
        var total = 0.0
        while (total * NANOS_PER_SECOND <= config.durationNanos) {
            durations += random.nextDouble(config.minSegmentSeconds, config.maxSegmentSeconds)
            total += durations.last()
        }
        // Speeds and headings are spread evenly over their ranges (in random order), so every round
        // is about equally hard and rounds differ in shape, not in difficulty.
        val speeds = stratified(durations.size, config.minSpeedDegreesPerSecond, config.maxSpeedDegreesPerSecond)
            .map { if (random.nextDouble() < config.stopProbability) 0.0 else it }
        val headings = stratified(durations.size, 0.0, 2 * Math.PI)
        val points = mutableListOf(Point(0L, center(yaw, pitch)))
        var offset = 0L
        durations.indices.forEach { i ->
            val (nextYaw, nextPitch) = nextAngles(yaw, pitch, reach = speeds[i] * durations[i], preferredHeading = headings[i])
            yaw = nextYaw
            pitch = nextPitch
            offset += (durations[i] * NANOS_PER_SECOND).toLong()
            points += Point(offset, center(yaw, pitch))
        }
        return points
    }

    /** [count] values, one random value from each of [count] equal slices of [low]..[high], in random order. */
    private fun stratified(count: Int, low: Double, high: Double): List<Double> =
        List(count) { low + (high - low) * (it + random.nextDouble()) / count }.shuffled(random)

    /** The preferred heading for [reach] degrees if it stays in the field, otherwise another one, or a turn back toward the middle. */
    private fun nextAngles(yaw: Double, pitch: Double, reach: Double, preferredHeading: Double): Pair<Double, Double> {
        repeat(MAX_HEADING_ATTEMPTS) { attempt ->
            val heading = if (attempt == 0) preferredHeading else random.nextDouble(0.0, 2 * Math.PI)
            val nextYaw = yaw + reach * cos(heading)
            val nextPitch = pitch + reach * sin(heading) * config.pitchShare
            if (abs(nextYaw) <= config.maxYawDegrees && abs(nextPitch) <= config.maxPitchDegrees) return nextYaw to nextPitch
        }
        return (yaw - yaw.sign * min(abs(yaw), reach)) to (pitch - pitch.sign * min(abs(pitch), reach * config.pitchShare))
    }

    private fun center(yaw: Double, pitch: Double): Vec3 = ViewAngles.directionOf(yaw, pitch) * config.distance

    private companion object {
        const val TARGET_ID = 0
        const val NANOS_PER_SECOND = 1_000_000_000.0
        const val MAX_HEADING_ATTEMPTS = 30

        /** The target starts in the inner part of the field. */
        const val START_SPREAD = 0.6
    }
}
