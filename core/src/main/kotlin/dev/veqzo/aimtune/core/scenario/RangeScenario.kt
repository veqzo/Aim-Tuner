package dev.veqzo.aimtune.core.scenario

import dev.veqzo.aimtune.core.math.Vec3
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Everything a Range round needs: its length, the game's [geometry] (floor height, bot, timing) and the player's [options]. */
data class RangeConfig(
    val durationNanos: Long,
    val geometry: TrainerGeometry = TrainerGeometry.DEFAULT,
    val range: RangeGeometry = geometry.range ?: RangeGeometry(),
    val options: RangeOptions = RangeOptions.DEFAULT,
) {
    init {
        require(durationNanos > 0) { "durationNanos must be positive" }
    }
}

/**
 * Range mode (docs/RANGE.md, "Behaviour spec"): one bot at a time on the floor in front of the player. A bot spawns a short
 * while after the last one died, strafes (or stands, with the "Reset" option) and lives until it is killed, or until its
 * lifetime ends when a speed preset is set. A head hit kills; body hits kill after a few, depending on the armor. The
 * round runs for its time and counts kills.
 *
 * Events: [TargetEvent.Spawned] of the head sphere (the [Target] is the head, the body hangs below it as [BotShape] says),
 * [TargetEvent.Waypoint]s of its strafe, [TargetEvent.BodyHit] for a body hit that did not kill, [TargetEvent.Hit] for the
 * shot that killed it (so a hit is a kill and time to hit is time to kill), [TargetEvent.Despawned] for a bot that left
 * unshot. Spawns and despawns are stamped with their scheduled times, so the log does not depend on how often it is updated.
 */
class RangeScenario(private val config: RangeConfig, seed: Long) : Scenario {
    override val kind = ScenarioKind.RANGE

    private val range = config.range
    private val shape = range.shape
    private val random = Random(seed)
    private val strafing = if (config.options.botsStrafe) StrafingTargets(range.strafeConfig(), seed) else null
    private val lifetimeNanos: Long? = range.lifetimeSeconds(config.options.speed)?.let { seconds(it) }
    private val bodyHitsToKill = range.bodyHitsToKill(config.options.armor)
    private val eventLog = mutableListOf<TargetEvent>()
    private var bot: Target? = null
    private var bodyHits = 0
    private var spawnedNanos = 0L
    private var nextSpawnNanos = 0L
    private var nextId = 0
    private var startNanos: Long? = null

    override val targets: List<Target> get() = listOfNotNull(bot)
    override val events: List<TargetEvent> get() = eventLog

    override fun start(nowNanos: Long) {
        startNanos = nowNanos
        // The first bot is there at once: the round starts with something to shoot at.
        spawn(nowNanos)
    }

    override fun update(nowNanos: Long) {
        val start = startNanos ?: return
        val end = start + config.durationNanos
        while (true) {
            val current = bot
            val deadline = lifetimeNanos?.let { spawnedNanos + it }
            when {
                current != null && deadline != null && nowNanos >= deadline -> despawn(current, deadline)
                current == null && nextSpawnNanos < end && nowNanos >= nextSpawnNanos -> spawn(nextSpawnNanos)
                else -> break
            }
        }
        val alive = bot ?: return
        if (strafing != null) bot = strafing.advance(listOf(alive), nowNanos, eventLog).first()
    }

    override fun deadlineNanos(target: Target): Long? = lifetimeNanos?.let { spawnedNanos + it }

    override fun isFinished(nowNanos: Long): Boolean {
        val start = startNanos ?: return false
        return nowNanos - start >= config.durationNanos
    }

    override fun fire(origin: Vec3, direction: Vec3, nowNanos: Long): ShotResult {
        // The shot goes at the bot where it is now, whether or not the caller updated first.
        update(nowNanos)
        val target = bot
        if (startNanos == null || isFinished(nowNanos) || target == null) return ShotResult.IGNORED
        val headHit = target.isHitBy(origin, direction)
        if (!headHit && !shape.bodyHitBy(target.center, origin, direction)) {
            eventLog += TargetEvent.Miss(nowNanos)
            return ShotResult.MISS
        }
        bodyHits++
        val kills = (headHit && range.headHitKills) || bodyHits >= bodyHitsToKill
        if (!kills) {
            eventLog += TargetEvent.BodyHit(nowNanos, target.id)
            return ShotResult.MISS
        }
        eventLog += TargetEvent.Hit(nowNanos, target.id)
        retire(target, nowNanos)
        return ShotResult.HIT
    }

    private fun despawn(target: Target, timeNanos: Long) {
        eventLog += TargetEvent.Despawned(timeNanos, target.id)
        retire(target, timeNanos)
    }

    private fun retire(target: Target, timeNanos: Long) {
        strafing?.remove(target)
        bot = null
        nextSpawnNanos = timeNanos + seconds(range.spawnDelaySeconds.uniform(random))
    }

    private fun spawn(timeNanos: Long) {
        val bearing = random.nextDouble(range.bearingMinDegrees, range.bearingMaxDegrees)
        val ground = range.distanceMeters.logUniform(random)
        val yaw = Math.toRadians(bearing)
        val headY = -config.geometry.eyeHeightMeters + shape.headCenterAboveFeet
        val target = Target(nextId++, Vec3(ground * sin(yaw), headY, -ground * cos(yaw)), shape.headRadius)
        bot = target
        bodyHits = 0
        spawnedNanos = timeNanos
        eventLog += TargetEvent.Spawned(timeNanos, target)
        strafing?.add(target, timeNanos, eventLog)
    }

    private fun seconds(value: Double): Long = (value * NANOS_PER_SECOND).toLong()

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000.0
    }
}
