package de.yuuto.aimtune.core.scenario

import de.yuuto.aimtune.core.math.Vec3
import de.yuuto.aimtune.core.math.ViewAngles
import kotlin.random.Random

/** Geometry of the target-switching scenario. Distances and radius are in scene units (meters). */
data class SwitchingConfig(
    val durationNanos: Long,
    val targetCount: Int = 3,
    val maxYawDegrees: Double = 45.0,
    val maxPitchDegrees: Double = 20.0,
    val minDistance: Double = 8.0,
    val maxDistance: Double = 18.0,
    val targetRadius: Double = 0.3,
    /** Smallest angle between two visible targets, and between a new target and the one just hit. */
    val minSeparationDegrees: Double = 14.0,
    /** When set, targets are head-sized angles and the separation comes from the game's geometry. */
    val geometry: TrainerGeometry? = null,
    /** When set, every visible target strafes (move, stop, move); this is the [ScenarioKind.SWITCH_STRAFE] round. */
    val strafe: StrafeConfig? = null,
) {
    init {
        require(durationNanos > 0) { "durationNanos must be positive" }
        require(targetCount >= 2) { "switching needs at least two targets" }
        require(0.0 < minDistance && minDistance <= maxDistance) { "invalid distance range" }
        require(targetRadius > 0.0) { "targetRadius must be positive" }
    }
}

/**
 * Several targets are visible at once; a hit removes one and a new one appears somewhere else, so
 * the player keeps choosing the next target and switching to it. Hitting any target counts.
 */
class SwitchingScenario(private val config: SwitchingConfig, seed: Long) : Scenario {
    override val kind = if (config.strafe != null) ScenarioKind.SWITCH_STRAFE else ScenarioKind.SWITCHING

    private val random = Random(seed)
    private val strafing = config.strafe?.let { StrafingTargets(it, seed) }
    private val eventLog = mutableListOf<TargetEvent>()
    private val visible = mutableListOf<Target>()
    private var nextId = 0
    private var startNanos: Long? = null

    override val targets: List<Target> get() = visible.toList()
    override val events: List<TargetEvent> get() = eventLog

    override fun start(nowNanos: Long) {
        startNanos = nowNanos
        repeat(config.targetCount) { spawn(nowNanos, avoid = visible.map { it.center }) }
    }

    /** Moves strafing targets to [nowNanos]; static targets do not move. */
    override fun update(nowNanos: Long) {
        val moving = strafing ?: return
        if (startNanos == null) return
        val moved = moving.advance(visible, nowNanos, eventLog)
        visible.clear()
        visible += moved
    }

    override fun isFinished(nowNanos: Long): Boolean {
        val start = startNanos ?: return false
        return nowNanos - start >= config.durationNanos
    }

    override fun fire(origin: Vec3, direction: Vec3, nowNanos: Long): ShotResult {
        if (startNanos == null || isFinished(nowNanos)) return ShotResult.IGNORED
        // The shot goes at the targets where they are now, whether or not the caller updated first.
        update(nowNanos)
        val hit = visible.firstOrNull { it.isHitBy(origin, direction) }
        if (hit == null) {
            eventLog += TargetEvent.Miss(nowNanos)
            return ShotResult.MISS
        }
        eventLog += TargetEvent.Hit(nowNanos, hit.id)
        visible.remove(hit)
        strafing?.remove(hit)
        spawn(nowNanos, avoid = visible.map { it.center } + hit.center)
        return ShotResult.HIT
    }

    private fun spawn(nowNanos: Long, avoid: List<Vec3>) {
        var candidate = randomTarget()
        var attempts = 0
        while (attempts < MAX_PLACEMENT_ATTEMPTS && avoid.any { candidate.center.angleToDegrees(it) < separation }) {
            candidate = randomTarget()
            attempts++
        }
        visible += candidate
        eventLog += TargetEvent.Spawned(nowNanos, candidate)
        strafing?.add(candidate, nowNanos, eventLog)
    }

    private val separation: Double get() = config.geometry?.switchingSeparationDegrees ?: config.minSeparationDegrees

    private fun randomTarget(): Target {
        val g = config.geometry
        val maxYaw = g?.fieldYawDegrees?.let { minOf(it, config.maxYawDegrees) } ?: config.maxYawDegrees
        val maxPitch = g?.fieldPitchDegrees?.let { minOf(it, config.maxPitchDegrees) } ?: config.maxPitchDegrees
        val yaw = random.nextDouble(-maxYaw, maxYaw)
        val distance = if (g != null) g.drawClickDistance(random) else random.nextDouble(config.minDistance, config.maxDistance)
        val pitch = if (g != null) g.elevation.draw(random, g.levelPitchDegrees(distance), maxPitch) else random.nextDouble(-maxPitch, maxPitch)
        val spot = if (g != null) ensureVisible(g, random, snapToFeature(g, random, Spot(yaw, pitch, distance))) else Spot(yaw, pitch, distance)
        val radius = if (g != null) g.clickTargetRadius(random, spot.distance) else config.targetRadius
        return Target(nextId++, ViewAngles.directionOf(spot.yaw, spot.pitch) * spot.distance, radius)
    }

    private companion object {
        const val MAX_PLACEMENT_ATTEMPTS = 200
    }
}
