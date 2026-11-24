package dev.veqzo.aimtune.core.scenario

import dev.veqzo.aimtune.core.math.Vec3
import dev.veqzo.aimtune.core.math.ViewAngles
import kotlin.math.sqrt

data class Target(val id: Int, val center: Vec3, val radius: Double) {
    /** True if the ray from [origin] along the normalized [direction] intersects this sphere. */
    fun isHitBy(origin: Vec3, direction: Vec3): Boolean {
        val toCenter = center - origin
        val projection = toCenter dot direction
        if (projection < 0.0) return false
        val closestSq = (toCenter dot toCenter) - projection * projection
        return sqrt(maxOf(closestSq, 0.0)) <= radius
    }
}

sealed interface TargetEvent {
    val timeNanos: Long

    data class Spawned(override val timeNanos: Long, val target: Target) : TargetEvent
    data class Hit(override val timeNanos: Long, val targetId: Int) : TargetEvent
    data class Miss(override val timeNanos: Long) : TargetEvent

    /** A shot hit the body of target [targetId] (range bots) but did not kill it; it counts as a shot that missed the head. */
    data class BodyHit(override val timeNanos: Long, val targetId: Int) : TargetEvent

    /** Target [targetId] disappeared without being hit (range bots with a speed preset). */
    data class Despawned(override val timeNanos: Long, val targetId: Int) : TargetEvent

    /**
     * A moving target is at [center] at [timeNanos]. Between two waypoints of the same target it
     * moves on the path [TargetPath] defines; the [Spawned] event is its first waypoint.
     */
    data class Waypoint(override val timeNanos: Long, val targetId: Int, val center: Vec3) : TargetEvent

    /**
     * A flash became visible at [yawDegrees]/[pitchDegrees] and will pop at [popNanos]; it only reaches the
     * player's eyes while the view is within [safeAngleDegrees] of it at that moment.
     */
    data class FlashCue(
        override val timeNanos: Long,
        val flashId: Int,
        val yawDegrees: Double,
        val pitchDegrees: Double,
        val popNanos: Long,
        val safeAngleDegrees: Double,
    ) : TargetEvent

    /** The flash popped. [dodged] says the player had turned away; otherwise the target stays hidden until [blindUntilNanos]. */
    data class FlashPop(override val timeNanos: Long, val flashId: Int, val dodged: Boolean, val blindUntilNanos: Long) : TargetEvent
}

enum class ShotResult { HIT, MISS, IGNORED }

/**
 * A flash as the renderer and the simulated player see it: while it has not popped it is a visible
 * orb the player must turn away from; once it has popped and the player looked, [blindUntilNanos] is the
 * end of the white-out (0 when nobody was blinded).
 */
data class FlashState(
    val id: Int,
    val yawDegrees: Double,
    val pitchDegrees: Double,
    val distanceMeters: Double,
    val popNanos: Long,
    val safeAngleDegrees: Double,
    val popped: Boolean,
    val blindUntilNanos: Long,
)

/**
 * A short aim round. Implementations are deterministic for a given seed and never read a clock;
 * the caller passes the current time (nanoseconds, any monotonic origin) into every call.
 */
interface Scenario {
    val kind: ScenarioKind
    val id: String get() = kind.id
    val targets: List<Target>
    val events: List<TargetEvent>

    fun start(nowNanos: Long)

    /**
     * Brings moving targets up to [nowNanos]; call it before reading [targets] or firing. Events are
     * stamped with their scheduled time, so the log does not depend on how often this is called.
     */
    fun update(nowNanos: Long) = Unit

    /** [update] for scenarios that react to where the player looks; every runner calls this one with the current view. */
    fun update(nowNanos: Long, view: ViewAngles) = update(nowNanos)

    /** The flash the player has to deal with right now (visible, or blinding), or null; only the flash scenario has one. */
    val flash: FlashState? get() = null

    /** When [target] disappears unless it is hit before, or null when it stays until hit (the default). */
    fun deadlineNanos(target: Target): Long? = null

    fun isFinished(nowNanos: Long): Boolean
    fun fire(origin: Vec3, direction: Vec3, nowNanos: Long): ShotResult
}
