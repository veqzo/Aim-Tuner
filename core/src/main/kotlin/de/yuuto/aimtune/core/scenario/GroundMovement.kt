package de.yuuto.aimtune.core.scenario

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

/** How a player moves on the ground: running, walking (shift) or crouched. Crouch wins when both keys are held. */
enum class Gait { RUN, WALK, CROUCH }

/**
 * Ground acceleration and friction of a player in the game (M23, docs/VALORANT-WEIGHTS.md): how fast a strafing opponent
 * reaches its top speed, how fast it stops when it lets go of the key, and how a counter-strafe turns it around. The units
 * are m/s² for [accel] and [frictionMin], 1/s for the friction terms that scale with the speed. The `walk` and `crouch`
 * factors scale the run values. The profile's `movement` block names the source of each value; [verified] says nobody
 * checked them in the game.
 */
data class GroundMovement(
    /** Acceleration toward the wished velocity while a movement key is held. */
    val accel: Double,
    /** Extra acceleration per m/s of the velocity part that points away from the wished direction (turning, counter-strafe). */
    val friction: Double,
    /** Deceleration without a movement key: [brakingFriction] x speed + [frictionMin]. */
    val brakingFriction: Double,
    val frictionMin: Double,
    /** How fast a speed above the gait's top speed decays, for example after switching from running to walking. */
    val aboveMaxSpeedDecay: Double,
    val walkAccelFactor: Double,
    val walkBrakingFactor: Double,
    val crouchAccelFactor: Double,
    val crouchFrictionFactor: Double,
    val crouchFrictionMinFactor: Double,
    val verified: Boolean = false,
) {
    init {
        require(listOf(accel, friction, brakingFriction, frictionMin, aboveMaxSpeedDecay).all { it.isFinite() && it >= 0.0 }) {
            "movement rates must be finite and not negative"
        }
        require(accel > 0.0 && brakingFriction + frictionMin > 0.0) { "a player must be able to start and to stop" }
        require(listOf(walkAccelFactor, walkBrakingFactor, crouchAccelFactor, crouchFrictionFactor, crouchFrictionMinFactor).all { it > 0.0 && it <= 2.0 }) {
            "gait factors must be in (0, 2]"
        }
    }
}

/**
 * The ground motion of one player along one axis (a strafe across the line of sight), stepped at a fixed tick. It is a
 * re-derivation of the ground rules that open-aim uses for its player (commit 807f557, read only, no code copied):
 * with a key held, the velocity moves toward the wished velocity by at most (accel + friction x the opposing part) x dt;
 * without one, the speed drops by (brakingFriction x speed + frictionMin) x dt; a speed above the gait's top speed decays
 * with aboveMaxSpeedDecay. Pure and deterministic: the same inputs give the same path.
 */
class Locomotion(val body: AgentBody, val movement: GroundMovement, val tickSeconds: Double = DEFAULT_TICK_SECONDS) {
    init {
        require(tickSeconds > 0.0 && tickSeconds <= 0.05) { "tick must be in (0, 0.05] s" }
    }

    fun topSpeed(gait: Gait): Double = when (gait) {
        Gait.RUN -> body.runSpeedMetersPerSecond
        Gait.WALK -> body.walkSpeedMetersPerSecond
        Gait.CROUCH -> body.crouchSpeedMetersPerSecond
    }

    /**
     * The velocity one tick after [velocity] (m/s, signed along the axis) with [input] -1, 0 or +1 (the strafe key held,
     * or none) in [gait].
     */
    fun step(velocity: Double, input: Int, gait: Gait): Double {
        require(input in -1..1) { "input must be -1, 0 or +1" }
        val dt = tickSeconds
        val top = topSpeed(gait)
        val next = if (input != 0) accelerate(velocity, input, gait, top, dt) else brake(velocity, gait, dt)
        val speed = abs(next)
        if (speed <= top) return next
        return sign(next) * max(top, speed - movement.aboveMaxSpeedDecay * dt)
    }

    private fun accelerate(velocity: Double, input: Int, gait: Gait, top: Double, dt: Double): Double {
        val wish = input * top
        // The part of the velocity that does not point along the key; along one axis it is all of it when moving against the key.
        val opposing = if (velocity * input < 0.0) 2.0 * abs(velocity) else 0.0
        val accelFactor = when (gait) {
            Gait.RUN -> 1.0
            Gait.WALK -> movement.walkAccelFactor
            Gait.CROUCH -> movement.crouchAccelFactor
        }
        val frictionFactor = if (gait == Gait.CROUCH) movement.crouchFrictionFactor else 1.0
        val maxChange = (movement.accel * accelFactor + movement.friction * frictionFactor * opposing) * dt
        val change = wish - velocity
        return velocity + sign(change) * min(abs(change), maxChange)
    }

    private fun brake(velocity: Double, gait: Gait, dt: Double): Double {
        val braking = movement.brakingFriction * (if (gait == Gait.WALK) movement.walkBrakingFactor else 1.0)
        val minimum = movement.frictionMin * (if (gait == Gait.CROUCH) movement.crouchFrictionMinFactor else 1.0)
        val speed = abs(velocity)
        return sign(velocity) * max(0.0, speed - (braking * speed + minimum) * dt)
    }

    /** Ticks to reach the gait's top speed from standing still with the key held. */
    fun ticksToTopSpeed(gait: Gait): Int = ticksUntil(0.0, 1, gait) { it >= topSpeed(gait) - SPEED_EPSILON }

    /** Ticks and meters until a player at [velocity] stands still after letting go of the key ([input] 0) or after a counter-strafe until the velocity crosses zero. */
    fun stop(velocity: Double, gait: Gait, counterStrafe: Boolean = false): Stop {
        val input = if (counterStrafe) -sign(velocity).toInt() else 0
        var v = velocity
        var meters = 0.0
        var ticks = 0
        while (v * velocity > 0.0 && ticks < MAX_TICKS) {
            val next = step(v, input, gait)
            // A counter-strafe crosses zero inside a tick; count only the way until the crossing.
            val share = if (next * velocity < 0.0) v / (v - next) else 1.0
            meters += abs(v + (if (share < 1.0) 0.0 else next)) / 2.0 * tickSeconds * share
            v = next
            ticks++
        }
        return Stop(ticks, meters)
    }

    private fun ticksUntil(start: Double, input: Int, gait: Gait, done: (Double) -> Boolean): Int {
        var v = start
        var ticks = 0
        while (!done(v) && ticks < MAX_TICKS) {
            v = step(v, input, gait)
            ticks++
        }
        return ticks
    }

    /** How long and how far a stop takes. */
    data class Stop(val ticks: Int, val meters: Double)

    companion object {
        /** open-aim steps its movement at 128 Hz, the tick rate of Valorant servers. */
        const val DEFAULT_TICK_SECONDS = 1.0 / 128.0
        private const val MAX_TICKS = 100_000
        private const val SPEED_EPSILON = 1e-9
    }
}
