package de.yuuto.aimtune.core.scenario

import de.yuuto.aimtune.core.math.Vec3
import de.yuuto.aimtune.core.math.ViewAngles
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Turning away from a flash and coming back: a flash becomes visible inside the field of view and pops
 * after a short delay. A player who has turned far enough that the flash is beyond the edge of the screen
 * (plus a margin) when it pops is not blinded; one who still sees it cannot see anything for the blind
 * duration. When it pops, an enemy (a head-sized target) appears close to where the player was looking when
 * the flash became visible, so the player has to turn back and hit it; a blinded player sees it only once the
 * white-out is over, and its time to hit counts from then, so the blindness is paid for by the dodge metric
 * alone and not a second time by the time to hit. A hit starts the next flash relative to the view at that moment.
 *
 * Whether the flash was seen is decided from the view the runner passes with every update, at the first
 * update after the scheduled pop; events are stamped with the scheduled time.
 */
class FlashScenario(private val durationNanos: Long, private val geometry: TrainerGeometry, seed: Long) : Scenario {
    override val kind = ScenarioKind.FLASH

    private enum class Phase { GAP, CUED, BLIND, TARGET }

    private class Cue(
        val state: FlashState,
        val direction: Vec3,
        /** Where the player looked when the flash became visible; the enemy appears around it. */
        val anchor: ViewAngles,
    )

    init {
        require(durationNanos > 0) { "durationNanos must be positive" }
    }

    private val g = geometry.flash
    private val random = Random(seed)
    private val eventLog = mutableListOf<TargetEvent>()
    private var phase = Phase.GAP
    private var startNanos: Long? = null
    private var nextCueNanos = 0L
    private var now = 0L
    private var view = ViewAngles()
    private var cue: Cue? = null
    private var target: Target? = null
    private var blindUntilNanos = 0L
    private var nextFlashId = 0
    private var nextTargetId = 0

    override val events: List<TargetEvent> get() = eventLog

    /** The enemy, once the flash popped and the player is not blind. */
    override val targets: List<Target>
        get() = target?.takeIf { phase == Phase.TARGET }?.let { listOf(it) }.orEmpty()

    override val flash: FlashState?
        get() {
            val c = cue ?: return null
            return when {
                phase == Phase.CUED -> c.state
                phase == Phase.BLIND -> c.state.copy(popped = true, blindUntilNanos = blindUntilNanos)
                else -> null
            }
        }

    override fun start(nowNanos: Long) {
        startNanos = nowNanos
        now = nowNanos
        nextCueNanos = nowNanos + seconds(g.gapSeconds.uniform(random))
    }

    override fun isFinished(nowNanos: Long): Boolean {
        val start = startNanos ?: return false
        return nowNanos - start >= durationNanos
    }

    override fun update(nowNanos: Long) = update(nowNanos, view)

    override fun update(nowNanos: Long, view: ViewAngles) {
        if (startNanos == null) return
        this.view = view
        now = nowNanos
        while (true) {
            val c = cue
            when {
                phase == Phase.GAP && now >= nextCueNanos -> beginCue(nextCueNanos)
                phase == Phase.CUED && c != null && now >= c.state.popNanos -> pop(c, c.state.popNanos)
                phase == Phase.BLIND && now >= blindUntilNanos -> showEnemy(blindUntilNanos)
                else -> return
            }
        }
    }

    override fun fire(origin: Vec3, direction: Vec3, nowNanos: Long): ShotResult {
        update(nowNanos)
        val t = targets.firstOrNull()
        if (startNanos == null || isFinished(nowNanos) || t == null) return ShotResult.IGNORED
        if (!t.isHitBy(origin, direction)) {
            eventLog += TargetEvent.Miss(nowNanos)
            return ShotResult.MISS
        }
        eventLog += TargetEvent.Hit(nowNanos, t.id)
        target = null
        phase = Phase.GAP
        nextCueNanos = nowNanos + seconds(g.gapSeconds.uniform(random))
        return ShotResult.HIT
    }

    private fun beginCue(timeNanos: Long) {
        val side = if (random.nextBoolean()) 1.0 else -1.0
        val offset = g.cueOffsetDegrees.uniform(random)
        val elevation = Math.toRadians(random.nextDouble(-g.cueElevationDegrees, g.cueElevationDegrees))
        val pitch = (view.pitchDegrees + offset * sin(elevation)).coerceIn(-MAX_FLASH_PITCH, MAX_FLASH_PITCH)
        val yaw = view.yawDegrees + side * offset * cos(elevation) / cos(Math.toRadians(pitch))
        val distance = g.distanceMeters.uniform(random)
        val pop = timeNanos + seconds(g.popDelaySeconds.uniform(random))
        val id = nextFlashId++
        val state = FlashState(id, yaw, pitch, distance, pop, g.safeAngleDegrees, popped = false, blindUntilNanos = 0L)
        cue = Cue(state, ViewAngles.directionOf(yaw, pitch), view)
        phase = Phase.CUED
        eventLog += TargetEvent.FlashCue(timeNanos, id, yaw, pitch, pop, g.safeAngleDegrees)
    }

    private fun pop(c: Cue, timeNanos: Long) {
        val dodged = view.direction().angleToDegrees(c.direction) >= c.state.safeAngleDegrees
        blindUntilNanos = if (dodged) 0L else timeNanos + seconds(g.blindSeconds)
        target = enemyAround(c.anchor)
        eventLog += TargetEvent.FlashPop(timeNanos, c.state.id, dodged, blindUntilNanos)
        if (dodged) showEnemy(timeNanos) else phase = Phase.BLIND
    }

    private fun showEnemy(timeNanos: Long) {
        phase = Phase.TARGET
        eventLog += TargetEvent.Spawned(timeNanos, checkNotNull(target))
    }

    /** A head-sized target [FlashGeometry.returnMinDegrees]..[FlashGeometry.returnSpreadDegrees] from the anchor in a random direction. */
    private fun enemyAround(anchor: ViewAngles): Target {
        val angle = random.nextDouble(g.returnMinDegrees, maxOf(g.returnSpreadDegrees, g.returnMinDegrees + 0.01))
        val distance = geometry.drawClickDistance(random)
        val (drawnYaw, drawnPitch) = placeNearLevel(geometry, anchor.yawDegrees, anchor.pitchDegrees, angle, distance, random, NO_YAW_LIMIT, MAX_FLASH_PITCH)
        val spot = ensureVisible(geometry, random, Spot(wrapYaw(drawnYaw), drawnPitch, distance))
        val radius = geometry.clickTargetRadius(random, spot.distance)
        return Target(nextTargetId++, ViewAngles.directionOf(spot.yaw, spot.pitch) * spot.distance, radius)
    }

    /** Yaw in -180..180, so that it can be compared with the map's features. */
    private fun wrapYaw(yaw: Double): Double = ((yaw + 180.0) % 360.0 + 360.0) % 360.0 - 180.0

    private fun seconds(value: Double): Long = (value * NANOS_PER_SECOND).toLong()

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000.0
        const val MAX_FLASH_PITCH = 60.0
        const val NO_YAW_LIMIT = 100_000.0
    }
}
