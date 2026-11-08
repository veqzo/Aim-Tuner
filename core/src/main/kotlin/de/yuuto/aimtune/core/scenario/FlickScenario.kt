package de.yuuto.aimtune.core.scenario

import de.yuuto.aimtune.core.math.Vec3
import de.yuuto.aimtune.core.math.ViewAngles
import kotlin.random.Random

/** Geometry of the flick scenario. Distances and radius are in scene units (meters). */
data class FlickConfig(
    val durationNanos: Long,
    val maxYawDegrees: Double = 50.0,
    val maxPitchDegrees: Double = 25.0,
    val minDistance: Double = 8.0,
    val maxDistance: Double = 20.0,
    val targetRadius: Double = 0.25,
    val minSeparationDegrees: Double = 15.0,
    /** When set, targets follow the game's realistic geometry (angular sizes, flick-angle mix) instead of the fixed values above. */
    val geometry: TrainerGeometry? = null,
    /**
     * When set, a target that is not hit within this time disappears and the next one appears (a speed round): the hand
     * has to move faster for the same distances, which separates the speed of a reach from its distance for the curve
     * calibration (docs/ALGORITHMS.md, "Speed rounds"). Null keeps targets until they are hit.
     */
    val targetLifetimeNanos: Long? = null,
) {
    init {
        require(durationNanos > 0) { "durationNanos must be positive" }
        require(targetLifetimeNanos == null || targetLifetimeNanos > 0) { "targetLifetimeNanos must be positive" }
        require(minDistance > 0.0 && minDistance <= maxDistance) { "invalid distance range" }
        require(targetRadius > 0.0) { "targetRadius must be positive" }
    }
}

/**
 * One static target at a time at a random direction and distance in front of the player; a hit
 * spawns the next one. Targets are at least [FlickConfig.minSeparationDegrees] from the previous
 * one so every target needs a real flick.
 */
class FlickScenario(private val config: FlickConfig, seed: Long) : Scenario {
    override val kind = ScenarioKind.FLICK

    private val random = Random(seed)
    private val eventLog = mutableListOf<TargetEvent>()
    private var current: Target? = null
    private var nextId = 0
    private var startNanos: Long? = null
    private var spawnedAt = 0L

    override val targets: List<Target> get() = listOfNotNull(current)
    override val events: List<TargetEvent> get() = eventLog

    override fun start(nowNanos: Long) {
        startNanos = nowNanos
        spawn(nowNanos, previous = null)
    }

    override fun isFinished(nowNanos: Long): Boolean {
        val start = startNanos ?: return false
        return nowNanos - start >= config.durationNanos
    }

    override fun deadlineNanos(target: Target): Long? =
        config.targetLifetimeNanos?.takeIf { target == current }?.let { spawnedAt + it }

    /** Replaces targets whose lifetime ran out, at the moment it ran out, so the log does not depend on the frame rate. */
    override fun update(nowNanos: Long) {
        val lifetime = config.targetLifetimeNanos ?: return
        while (true) {
            val target = current ?: return
            val expiry = spawnedAt + lifetime
            if (nowNanos < expiry || isFinished(expiry)) return
            spawn(expiry, previous = target)
        }
    }

    override fun fire(origin: Vec3, direction: Vec3, nowNanos: Long): ShotResult {
        // A shot after a target's lifetime goes at its successor, whether or not the caller updated first.
        if (startNanos != null) update(nowNanos)
        val target = current
        if (startNanos == null || isFinished(nowNanos) || target == null) return ShotResult.IGNORED
        if (!target.isHitBy(origin, direction)) {
            eventLog += TargetEvent.Miss(nowNanos)
            return ShotResult.MISS
        }
        eventLog += TargetEvent.Hit(nowNanos, target.id)
        spawn(nowNanos, previous = target)
        return ShotResult.HIT
    }

    private fun spawn(nowNanos: Long, previous: Target?) {
        var candidate = randomTarget(previous)
        var attempts = 0
        while (config.geometry == null && previous != null && attempts < MAX_PLACEMENT_ATTEMPTS &&
            candidate.center.angleToDegrees(previous.center) < config.minSeparationDegrees
        ) {
            candidate = randomTarget(previous)
            attempts++
        }
        current = candidate
        spawnedAt = nowNanos
        eventLog += TargetEvent.Spawned(nowNanos, candidate)
    }

    private fun randomTarget(previous: Target? = null): Target {
        config.geometry?.let { return realisticTarget(it, previous) }
        val yaw = random.nextDouble(-config.maxYawDegrees, config.maxYawDegrees)
        val pitch = random.nextDouble(-config.maxPitchDegrees, config.maxPitchDegrees)
        val distance = random.nextDouble(config.minDistance, config.maxDistance)
        return Target(nextId++, ViewAngles.directionOf(yaw, pitch) * distance, config.targetRadius)
    }

    /** The next target [TrainerGeometry.drawFlickAngle] degrees from the previous one (from the starting view for the first). */
    private fun realisticTarget(g: TrainerGeometry, previous: Target?): Target {
        val (fromYaw, fromPitch) = previous?.let { anglesOf(it.center) } ?: (0.0 to 0.0)
        val distance = g.drawClickDistance(random)
        val (drawnYaw, drawnPitch) = placeNearLevel(g, fromYaw, fromPitch, g.drawFlickAngle(random), distance, random, g.fieldYawDegrees, g.fieldPitchDegrees)
        val moved = ensureVisible(g, random, snapToFeature(g, random, Spot(drawnYaw, drawnPitch, distance)))
        // A snap or a sidestep must not turn a flick into a target on the crosshair.
        val tooClose = ViewAngles.directionOf(moved.yaw, moved.pitch).angleToDegrees(ViewAngles.directionOf(fromYaw, fromPitch)) < TrainerGeometry.MIN_FLICK_DEGREES
        val (yaw, pitch, atDistance) = if (tooClose && previous != null) ensureVisible(g, random, Spot(drawnYaw, drawnPitch, distance)) else moved
        val radius = g.clickTargetRadius(random, atDistance)
        return Target(nextId++, ViewAngles.directionOf(yaw, pitch) * atDistance, radius)
    }

    private companion object {
        const val MAX_PLACEMENT_ATTEMPTS = 100
    }
}
