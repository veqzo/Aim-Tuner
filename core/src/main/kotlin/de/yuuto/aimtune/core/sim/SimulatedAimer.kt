package de.yuuto.aimtune.core.sim

import de.yuuto.aimtune.core.accel.AccelModel
import de.yuuto.aimtune.core.input.MouseEvent
import de.yuuto.aimtune.core.math.Vec3
import de.yuuto.aimtune.core.scenario.FlashState
import de.yuuto.aimtune.core.scenario.ScenarioKind
import de.yuuto.aimtune.core.scenario.Target
import de.yuuto.aimtune.core.units.degreesPerCount
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sqrt
import kotlin.random.Random

/** What the aimer does in one poll: mouse counts to report (0, 0 = none) and whether it clicks. */
data class AimerStep(val dx: Int, val dy: Int, val click: Boolean) {
    companion object {
        val IDLE = AimerStep(0, 0, false)
    }
}

/**
 * Deterministic synthetic aimer. Per target it waits a reaction time, makes a minimum-jerk primary
 * reach with a noisy endpoint, then repeatedly checks its error after a feedback delay and corrects
 * until it is on the target, and clicks. With several targets visible it goes for the one nearest
 * to its aim and sticks to it. For [ScenarioKind.TRACKING] it follows the target instead (see
 * [trackStep]) and never clicks. It never reads a clock: the caller passes the time into
 * [step], which the headless runner and `SimulatedMouseSource` both do. The aimer assumes the view
 * starts at yaw 0 / pitch 0 when [reset] is called, like the trainer.
 */
class SimulatedAimer(
    private val player: SimulatedPlayer,
    dpi: Double,
    private val cm360: Double,
    seed: Long,
    private val kind: ScenarioKind = ScenarioKind.FLICK,
    accel: AccelModel? = null,
) {
    /**
     * A planned movement: [dYaw]/[dPitch] degrees the player intends, delivered by a hand movement
     * that is [handScale] times the hand movement a linear mouse would need. The player plans with
     * the gain it expects, so [handScale] is 1 divided by that expected gain.
     */
    private class Reach(
        val startNanos: Long,
        val durationNanos: Long,
        val dYaw: Double,
        val dPitch: Double,
        val handScale: Double = 1.0,
        /** Vertical hand drift per horizontal hand movement ([HandGeometry.driftSlopeSd]); the player does not know about it. */
        val handTilt: Double = 0.0,
    ) {
        val endNanos: Long get() = startNanos + durationNanos
    }

    private data class Offset(val x: Double, val y: Double)

    private class Observation(val timeNanos: Long, val center: Vec3)

    private enum class Mode { NO_TARGET, REACTING, MOVING, SETTLING, CLICKING, FOLLOWING }

    private val random = Random(seed)
    private val degPerCount = degreesPerCount(dpi, cm360)
    private val dpiValue = dpi
    private val accelModel = accel
    private val modifier = accel?.newModifier(dpi)
    private val reaches = ArrayList<Reach>()
    private var baseYaw = 0.0
    private var basePitch = 0.0
    private var baseHandYaw = 0.0
    private var baseHandPitch = 0.0

    /** What the player sees: the emitted counts run through the acceleration into a view, like the trainer does. */
    private var trueYaw = 0.0
    private var truePitch = 0.0
    private var emittedX = 0L
    private var emittedY = 0L

    /** The part of a count that the hand's deviation ([HandGeometry]) has not yet turned into a whole count. */
    private var handResidualX = 0.0
    private var handResidualY = 0.0
    private var mode = Mode.NO_TARGET
    private var targetId = -1

    /** Strafing click targets: where the player has seen each target recently, and whether it times the click for a stop. */
    private val strafing = kind.strafes
    private val histories = HashMap<Int, ArrayDeque<Observation>>()
    private var waitForStop = false
    private var clickReadyNanos = 0L
    private var wakeNanos = 0L
    private var roundStartNanos = 0L
    private var sessionOffsetNanos = 0L
    private var corrections = 0
    private val seenTargets = HashSet<Int>()
    private val observations = ArrayDeque<Observation>()
    private var nextControlNanos = 0L
    private var flashHandled = -1
    private var flashTurnPlanned = false
    private var flashWakeNanos = 0L

    /** Starts a round: the view is at (0, 0); [sessionOffsetNanos] is the time already spent in the session (fatigue). */
    fun reset(nowNanos: Long, sessionOffsetNanos: Long = 0L) {
        reaches.clear()
        baseYaw = 0.0
        basePitch = 0.0
        baseHandYaw = 0.0
        baseHandPitch = 0.0
        trueYaw = 0.0
        truePitch = 0.0
        modifier?.reset()
        emittedX = 0
        emittedY = 0
        handResidualX = 0.0
        handResidualY = 0.0
        mode = Mode.NO_TARGET
        targetId = -1
        roundStartNanos = nowNanos
        this.sessionOffsetNanos = sessionOffsetNanos
        seenTargets.clear()
        observations.clear()
        histories.clear()
        nextControlNanos = nowNanos
        flashHandled = -1
        flashTurnPlanned = false
    }

    fun step(nowNanos: Long, target: Target?): AimerStep = step(nowNanos, listOfNotNull(target))

    /** One poll with all [targets] currently visible; [deadlineOf] says when a target disappears unless hit (speed rounds). */
    fun step(nowNanos: Long, targets: List<Target>, flash: FlashState? = null, deadlineOf: (Target) -> Long? = { null }): AimerStep {
        if (kind == ScenarioKind.TRACKING) return trackStep(nowNanos, targets.firstOrNull())
        if (kind == ScenarioKind.FLASH && targets.isEmpty()) return flashStep(nowNanos, flash)
        if (strafing) observe(nowNanos, targets)
        val target = choose(nowNanos, targets)
        deadlineNanos = target?.let(deadlineOf)
        val seenBefore = target != null && target.id in seenTargets
        targets.forEach { seenTargets += it.id }
        return if (kind == ScenarioKind.RANGE) rangeStep(nowNanos, target, seenBefore) else clickStep(nowNanos, target, seenBefore)
    }

    /**
     * Between the hit and the enemy: reacts to a visible flash after a reaction time by turning away from it
     * (a crude movement over the angle that takes it beyond the edge of the screen plus its own margin), then
     * waits for the pop. While blinded or in the pause before a flash it does nothing.
     */
    private fun flashStep(nowNanos: Long, flash: FlashState?): AimerStep {
        if (flash != null && !flash.popped) {
            if (flash.id != flashHandled) {
                flashHandled = flash.id
                flashTurnPlanned = false
                val reaction = player.flashTurnReactionMs + player.reactionSdMs * gaussian()
                flashWakeNanos = nowNanos + ms(max(MIN_REACTION_MS, reaction) * fatigue(nowNanos))
            }
            if (!flashTurnPlanned && nowNanos >= flashWakeNanos) {
                flashTurnPlanned = true
                planTurnAway(nowNanos, flash)
            }
        }
        return emitCounts(nowNanos, click = false)
    }

    private fun planTurnAway(nowNanos: Long, flash: FlashState) {
        // The offset from the flash to the view on the screen plane is the direction to turn in.
        val awayX = (trueYaw - flash.yawDegrees) * cos(Math.toRadians(truePitch))
        val awayY = truePitch - flash.pitchDegrees
        val now = hypot(awayX, awayY)
        val needed = flash.safeAngleDegrees + player.flashTurnMarginDegrees - now
        if (needed <= 0.0) return
        val unitX = if (now > 1e-6) awayX / now else 1.0
        val unitY = if (now > 1e-6) awayY / now else 0.0
        planReachBy(nowNanos, Offset(unitX * needed, unitY * needed), FLASH_TURN_WIDTH_DEGREES, primary = true)
    }

    /** Keeps the current target while it is there, otherwise the one nearest to the aim. */
    private fun choose(nowNanos: Long, targets: List<Target>): Target? {
        targets.firstOrNull { it.id == targetId }?.let { return it }
        return targets.minByOrNull { target -> errorTo(nowNanos, target).let { hypot(it.x, it.y) } }
    }

    /**
     * Follows the target like a person does: it sees the target's position [SimulatedPlayer.trackingLagMs]
     * late, partly anticipates the motion during that delay, and every 40 ms corrects a share of the
     * remaining error. Corrections are noisy and limited by the hand's smooth speed.
     */
    private fun trackStep(nowNanos: Long, target: Target?): AimerStep {
        if (target == null) return emitCounts(nowNanos, click = false)
        observations.addLast(Observation(nowNanos, target.center))
        while (observations.size > 1 && observations.first().timeNanos < nowNanos - ms(TRACK_HISTORY_MS)) observations.removeFirst()
        if (nowNanos >= nextControlNanos) {
            controlTracking(nowNanos)
            nextControlNanos = nowNanos + ms(TRACK_STEP_MS)
        }
        return emitCounts(nowNanos, click = false)
    }

    private fun controlTracking(nowNanos: Long) {
        val fatigue = fatigue(nowNanos)
        val lagNanos = ms(player.trackingLagMs * fatigue)
        val delayed = observationAt(nowNanos - lagNanos) ?: return
        val earlier = observationAt(nowNanos - lagNanos - ms(VELOCITY_WINDOW_MS)) ?: delayed
        val seen = anglesOf(delayed.center)
        val before = anglesOf(earlier.center)
        val seconds = (delayed.timeNanos - earlier.timeNanos) / NANOS_PER_SECOND
        // The hand needs about half a reach to deliver a command, so that delay is anticipated too.
        val lead = player.trackingPrediction * (lagNanos + ms(TRACK_REACH_MS) / 2) / NANOS_PER_SECOND
        val predictedYaw = if (seconds > 0.0) seen.first + (seen.first - before.first) / seconds * lead else seen.first
        val predictedPitch = if (seconds > 0.0) seen.second + (seen.second - before.second) / seconds * lead else seen.second

        val (yaw, pitch) = believedAngles(nowNanos)
        val errorX = (predictedYaw - yaw) * cos(Math.toRadians(predictedPitch))
        val errorY = predictedPitch - pitch
        var moveX = errorX * player.trackingGain
        var moveY = errorY * player.trackingGain
        var amplitude = hypot(moveX, moveY)

        val stepSeconds = TRACK_STEP_MS / MS_PER_S
        val (expected, delivered) = gains(amplitude, stepSeconds, peak = false)
        val effectiveCm360 = cm360 / (player.optimumScale * delivered)
        val reachLimit = player.trackingMaxHandSpeedCmPerS * stepSeconds * FULL_TURN / effectiveCm360
        if (amplitude > reachLimit) {
            moveX *= reachLimit / amplitude
            moveY *= reachLimit / amplitude
            amplitude = reachLimit
        }
        val sigma = player.noiseLevel * fatigue *
            sqrt(sq(player.signalNoise * amplitude) + sq(player.handNoiseCm * player.trackingNoiseShare * FULL_TURN / effectiveCm360))
        moveX += sigma * gaussian()
        moveY += sigma * gaussian()
        foldFinished(nowNanos)
        reaches += Reach(nowNanos, ms(TRACK_REACH_MS), moveX / cos(Math.toRadians(pitch + moveY / 2.0)), moveY, 1.0 / expected)
    }

    /**
     * The gain the player expects and the gain the mouse delivers for a movement of [amplitude]
     * degrees in [seconds]: both depend on the hand speed, which in turn depends on the hand
     * movement the player plans with its expected gain, so the speed is found by iteration. The
     * speed is the peak speed of a minimum-jerk movement when [peak] is set, else the average.
     */
    private fun gains(amplitude: Double, seconds: Double, peak: Boolean): Pair<Double, Double> {
        var expected = 1.0
        var delivered = accelModel?.planningGain(0.0, dpiValue) ?: 1.0
        repeat(GAIN_ITERATIONS) {
            val handInches = amplitude / (degPerCount * expected) / dpiValue
            val speed = (if (peak) PEAK_TO_MEAN_SPEED else 1.0) * handInches / seconds
            expected = player.gainPreference(speed) * (player.adaptation?.factor(speed) ?: 1.0)
            delivered = accelModel?.planningGain(speed, dpiValue) ?: 1.0
            lastSpeed = speed
        }
        return expected to delivered
    }

    /** The hand speed of the last [gains] call, for the adaptation after a first reach. */
    private var lastSpeed = 0.0

    /** When the chosen target disappears unless hit; null when it stays. */
    private var deadlineNanos: Long? = null

    /**
     * The longest a first reach may take so that the correction and the click still fit before the deadline, or null
     * without one. The player plans for one look and the click after the reach.
     */
    private fun reachBudgetMs(nowNanos: Long): Double? = deadlineNanos?.let { deadline ->
        (deadline - nowNanos) / NANOS_PER_MS - player.feedbackDelayMs - player.clickDelayMs
    }

    /** Where the aim will be once every reach under way has finished, as the player believes. */
    private fun believedAngles(nowNanos: Long): Pair<Double, Double> {
        var yaw = trueYaw
        var pitch = truePitch
        for (r in reaches) {
            val remaining = 1.0 - minimumJerk((nowNanos - r.startNanos).toDouble() / r.durationNanos)
            yaw += r.dYaw * remaining
            pitch += r.dPitch * remaining
        }
        return yaw to pitch
    }

    private fun observationAt(timeNanos: Long, history: ArrayDeque<Observation> = observations): Observation? =
        history.lastOrNull { it.timeNanos <= timeNanos }

    private fun anglesOf(center: Vec3): Pair<Double, Double> =
        Math.toDegrees(atan2(center.x, -center.z)) to Math.toDegrees(asin(center.y / center.length))

    private fun clickStep(nowNanos: Long, target: Target?, seenBefore: Boolean): AimerStep {
        if (target == null) {
            mode = Mode.NO_TARGET
            return emitCounts(nowNanos, click = false)
        }
        if (target.id != targetId) beginTarget(nowNanos, target, seenBefore)
        var click = false
        when (mode) {
            Mode.REACTING -> if (nowNanos >= wakeNanos) planReach(nowNanos, target, primary = true)
            Mode.MOVING -> if (nowNanos >= lastEnd()) {
                if (corrections == 0 && rushes()) {
                    clickNow(nowNanos)
                } else {
                    mode = Mode.SETTLING
                    wakeNanos = nowNanos + ms(player.feedbackDelayMs * fatigue(nowNanos))
                }
            }
            Mode.SETTLING -> if (nowNanos >= wakeNanos) decide(nowNanos, target)
            Mode.CLICKING -> if (nowNanos >= wakeNanos) {
                if (holdsForStop(nowNanos, target)) {
                    wakeNanos = nowNanos + ms(STOP_RECHECK_MS)
                } else {
                    click = true
                    twitch(nowNanos)
                    // After a miss the target stays; look again shortly.
                    mode = Mode.SETTLING
                    wakeNanos = nowNanos + ms(MISS_RECHECK_MS)
                }
            }
            Mode.NO_TARGET, Mode.FOLLOWING -> Unit
        }
        return emitCounts(nowNanos, click)
    }

    /**
     * Range bots: a flick to the bot, then following it (a smooth correction every 40 ms toward where it is predicted to be,
     * or a corrective reach when the crosshair is far off) and a click once the crosshair is on the head as the player
     * predicts it. This is how people shoot a strafing bot; the closed loop of [clickStep] (look, reach, look again) never
     * catches up with a target that turns every 0.3 s.
     */
    private fun rangeStep(nowNanos: Long, target: Target?, seenBefore: Boolean): AimerStep {
        if (target == null) {
            mode = Mode.NO_TARGET
            observations.clear()
            return emitCounts(nowNanos, click = false)
        }
        if (target.id != targetId) {
            beginTarget(nowNanos, target, seenBefore)
            observations.clear()
        }
        observations.addLast(Observation(nowNanos, target.center))
        while (observations.size > 1 && observations.first().timeNanos < nowNanos - ms(TRACK_HISTORY_MS)) observations.removeFirst()
        var click = false
        when (mode) {
            Mode.REACTING -> if (nowNanos >= wakeNanos) planReach(nowNanos, target, primary = true)
            Mode.MOVING -> if (nowNanos >= lastEnd()) {
                mode = Mode.FOLLOWING
                nextControlNanos = nowNanos + ms(player.feedbackDelayMs * fatigue(nowNanos))
            }
            Mode.FOLLOWING -> if (nowNanos >= nextControlNanos) {
                nextControlNanos = nowNanos + ms(TRACK_STEP_MS)
                follow(nowNanos, target)
            }
            Mode.CLICKING -> if (nowNanos >= wakeNanos) {
                if (holdsForStop(nowNanos, target)) {
                    wakeNanos = nowNanos + ms(STOP_RECHECK_MS)
                } else {
                    click = true
                    twitch(nowNanos)
                    mode = Mode.FOLLOWING
                    nextControlNanos = nowNanos + ms(MISS_RECHECK_MS)
                }
            }
            Mode.SETTLING, Mode.NO_TARGET -> Unit
        }
        return emitCounts(nowNanos, click)
    }

    private fun follow(nowNanos: Long, target: Target) {
        val error = aimError(nowNanos, target, noisy = false)
        val off = hypot(error.x, error.y)
        when {
            off <= angularRadius(target) -> {
                mode = Mode.CLICKING
                // The click is prepared while following: it goes out as soon as the crosshair is where the player predicts the head to be.
                wakeNanos = nowNanos
                clickReadyNanos = wakeNanos
            }
            off > RANGE_CORRECTION_DEGREES && corrections < player.maxCorrections -> {
                corrections++
                planReach(nowNanos, target, primary = false)
            }
            else -> controlTracking(nowNanos)
        }
    }

    private fun beginTarget(nowNanos: Long, target: Target, seenBefore: Boolean) {
        targetId = target.id
        corrections = 0
        mode = Mode.REACTING
        val mean = when {
            kind == ScenarioKind.FLASH -> player.flashReturnReactionMs
            seenBefore -> player.reacquireMs
            else -> player.reactionMeanMs
        }
        val reaction = mean + player.reactionSdMs * gaussian()
        wakeNanos = nowNanos + ms(max(MIN_REACTION_MS * if (seenBefore) REACQUIRE_FLOOR else 1.0, reaction) * fatigue(nowNanos))
        if (strafing) waitForStop = random.nextDouble() < player.strafeWaitForStop
    }

    private fun decide(nowNanos: Long, target: Target) {
        val error = aimError(nowNanos, target)
        val onTarget = hypot(error.x, error.y) <= angularRadius(target)
        if (onTarget || corrections >= player.maxCorrections) {
            clickNow(nowNanos)
        } else {
            corrections++
            planReach(nowNanos, target, primary = false)
        }
    }

    private fun clickNow(nowNanos: Long) {
        mode = Mode.CLICKING
        val delay = max(0.0, player.clickDelayMs + player.clickDelaySdMs * gaussian())
        wakeNanos = nowNanos + ms(delay * fatigue(nowNanos))
        clickReadyNanos = wakeNanos
    }

    /**
     * Whether the player clicks at the end of its first reach without waiting to see where it landed
     * ([SimulatedPlayer.rushClickChance]). No random number is drawn when the chance is 0, so the default player plays
     * exactly as before.
     */
    private fun rushes(): Boolean = player.rushClickChance > 0.0 && random.nextDouble() < player.rushClickChance

    private fun planReach(nowNanos: Long, target: Target, primary: Boolean) {
        planReachBy(nowNanos, aimError(nowNanos, target), max(2.0 * angularRadius(target), MIN_WIDTH_DEGREES), primary)
    }

    /** Remembers where [targets] are, for the lagged view of strafing targets; targets that are gone are forgotten. */
    private fun observe(nowNanos: Long, targets: List<Target>) {
        histories.keys.retainAll(targets.map { it.id }.toSet())
        for (target in targets) {
            val history = histories.getOrPut(target.id) { ArrayDeque() }
            history.addLast(Observation(nowNanos, target.center))
            while (history.size > 1 && history.first().timeNanos < nowNanos - ms(TRACK_HISTORY_MS)) history.removeFirst()
        }
    }

    /** What the player sees of a strafing target: where it was one tracking lag ago and how fast it moved then, in degrees and degrees per second. */
    private class Seen(val yaw: Double, val pitch: Double, val yawSpeed: Double, val pitchSpeed: Double)

    private fun seenOf(nowNanos: Long, target: Target): Seen? {
        val history = histories[target.id] ?: return null
        val lagNanos = ms(player.trackingLagMs * fatigue(nowNanos))
        val delayed = observationAt(nowNanos - lagNanos, history) ?: return null
        val earlier = observationAt(nowNanos - lagNanos - ms(VELOCITY_WINDOW_MS), history) ?: delayed
        val seen = anglesOf(delayed.center)
        val before = anglesOf(earlier.center)
        val seconds = (delayed.timeNanos - earlier.timeNanos) / NANOS_PER_SECOND
        if (seconds <= 0.0) return Seen(seen.first, seen.second, 0.0, 0.0)
        return Seen(seen.first, seen.second, (seen.first - before.first) / seconds, (seen.second - before.second) / seconds)
    }

    /**
     * The offset to aim at: for a static target the target itself. A strafing target is seen one tracking lag late, and the
     * player extends the motion it saw over that lag and the time the reach takes, scaled by how well it anticipates
     * ([SimulatedPlayer.trackingPrediction]) and randomly off ([SimulatedPlayer.strafePredictionNoise]).
     */
    private fun aimError(nowNanos: Long, target: Target, noisy: Boolean = true): Offset {
        val plain = errorTo(nowNanos, target)
        if (!strafing) return plain
        val seen = seenOf(nowNanos, target) ?: return plain
        val width = max(2.0 * angularRadius(target), MIN_WIDTH_DEGREES)
        val reachMs = player.fittsInterceptMs + player.fittsSlopeMs * log2(1.0 + hypot(plain.x, plain.y) / width)
        val lagMs = player.trackingLagMs * fatigue(nowNanos)
        val noise = if (noisy) player.strafePredictionNoise * gaussian() else 0.0
        val lead = player.trackingPrediction * max(0.0, 1.0 + noise) * (lagMs + reachMs) / MS_PER_S
        val yaw = seen.yaw + seen.yawSpeed * lead
        val pitch = seen.pitch + seen.pitchSpeed * lead
        return Offset((yaw - trueYaw) * cos(Math.toRadians(pitch)), pitch - truePitch)
    }

    /** A player who times the click for a stop keeps waiting (for a while) as long as it sees the target move. */
    private fun holdsForStop(nowNanos: Long, target: Target): Boolean {
        if (!strafing || !waitForStop || nowNanos - clickReadyNanos > ms(MAX_STOP_WAIT_MS)) return false
        val seen = seenOf(nowNanos, target) ?: return false
        return hypot(seen.yawSpeed * cos(Math.toRadians(seen.pitch)), seen.pitchSpeed) > STOP_SPEED_DEGREES_PER_SECOND
    }

    /** A reach over [error] degrees (screen plane) to something [width] degrees wide. */
    private fun planReachBy(nowNanos: Long, error: Offset, width: Double, primary: Boolean) {
        foldFinished(nowNanos)
        val amplitude = hypot(error.x, error.y)
        val fatigue = fatigue(nowNanos)
        val fitts = (player.fittsInterceptMs + player.fittsSlopeMs * log2(1.0 + amplitude / width)) *
            (if (primary) 1.0 else CORRECTION_SPEEDUP)
        // Time, noise and gain depend on each other through the hand speed; three rounds settle them.
        var expected = 1.0
        var delivered = accelModel?.planningGain(0.0, dpiValue) ?: 1.0
        var effectiveCm360 = cm360 / (player.optimumScale * delivered)
        var durationMs = MIN_REACH_MS
        // Under a deadline a first reach is compressed into the time left: faster, and noisier in proportion (Schmidt et al. 1979).
        var speedup = 1.0
        val budget = if (primary) reachBudgetMs(nowNanos) else null
        repeat(GAIN_ITERATIONS) {
            effectiveCm360 = cm360 / (player.optimumScale * delivered)
            val handCm = amplitude * effectiveCm360 / FULL_TURN
            durationMs = max(MIN_REACH_MS, fitts + handCm / player.maxHandSpeedCmPerS * MS_PER_S) * fatigue
            speedup = 1.0
            if (budget != null && durationMs > max(budget, MIN_REACH_MS)) {
                val compressed = max(budget, MIN_REACH_MS)
                speedup = durationMs / compressed
                durationMs = compressed
            }
            val gains = gains(amplitude, durationMs / MS_PER_S, peak = true)
            expected = gains.first
            delivered = gains.second
        }
        // The hand learns from the gain mismatch of a first reach (it sees where the reach ended); noise is not part of it.
        if (primary) player.adaptation?.update(lastSpeed, expected, delivered)
        val sigma = player.noiseLevel * fatigue *
            sqrt(sq(player.signalNoise * amplitude * speedup) + sq(player.handNoiseCm * FULL_TURN / effectiveCm360))
        val pressure = 1.0 - player.pressureUndershoot * (speedup - 1.0)
        val gain = if (primary) (1.0 - player.undershootBias) * rangeFactor(amplitude) * max(pressure, MIN_PRESSURE_GAIN) else 1.0
        var noiseX = sigma * gaussian()
        var noiseY = sigma * gaussian()
        if ((player.extentNoiseScale != 1.0 || player.directionNoiseScale != 1.0) && amplitude > 0.0) {
            // Split the noise along and across the reach and scale the parts separately (to match the spread of real rounds).
            val ux = error.x / amplitude
            val uy = error.y / amplitude
            val along = (noiseX * ux + noiseY * uy) * player.extentNoiseScale
            val across = (-noiseX * uy + noiseY * ux) * player.directionNoiseScale
            noiseX = along * ux - across * uy
            noiseY = along * uy + across * ux
        }
        val moveX = error.x * gain + noiseX
        val moveY = error.y * gain + noiseY
        if (player.noiseModel == NoiseModel.DECOUPLED && amplitude > 0.0) {
            // Same peak speed as planned: the time stretches with the distance the noise produced along the reach.
            val along = (moveX * error.x + moveY * error.y) / amplitude
            durationMs *= max(along, MIN_DECOUPLED_SHARE * amplitude * gain) / (amplitude * gain)
        }
        // Screen-plane x is yaw scaled by cos(pitch); convert back to a yaw change.
        val pitchMid = truePitch + moveY / 2.0
        val tilt = if (primary && player.hand.driftSlopeSd > 0.0) player.hand.driftSlopeSd * gaussian() else 0.0
        reaches += Reach(nowNanos, ms(durationMs), moveX / cos(Math.toRadians(pitchMid)), moveY, 1.0 / expected, tilt)
        mode = Mode.MOVING
    }

    /** The range effect's scale of a first reach over [amplitude] degrees; 1 without a range bias. */
    private fun rangeFactor(amplitude: Double): Double =
        if (player.rangeBias == 0.0) 1.0 else (player.rangeReferenceDegrees / max(amplitude, MIN_RANGE_AMPLITUDE)).pow(player.rangeBias)

    /** Hand tremor at the moment of the click: a small displacement that can turn a marginal hit into a miss. */
    private fun twitch(nowNanos: Long) {
        val effectiveCm360 = cm360 / player.optimumScale
        val sigma = player.noiseLevel * fatigue(nowNanos) * TWITCH_FRACTION * player.handNoiseCm * FULL_TURN / effectiveCm360
        foldFinished(nowNanos)
        reaches += Reach(nowNanos, ms(TWITCH_MS), sigma * gaussian(), sigma * gaussian())
    }

    private fun fatigue(nowNanos: Long): Double {
        val minutes = (sessionOffsetNanos + nowNanos - roundStartNanos) / NANOS_PER_MINUTE
        return 1.0 + player.fatiguePerMinute * minutes
    }

    /** Remaining offset to the target on the screen plane in degrees, from the view the player sees. */
    private fun errorTo(nowNanos: Long, target: Target): Offset {
        val yaw = trueYaw
        val pitch = truePitch
        val c = target.center
        val targetYaw = Math.toDegrees(atan2(c.x, -c.z))
        val targetPitch = Math.toDegrees(asin(c.y / c.length))
        return Offset((targetYaw - yaw) * cos(Math.toRadians(targetPitch)), targetPitch - pitch)
    }

    private fun angularRadius(target: Target): Double = Math.toDegrees(atan(target.radius / target.center.length))

    /** The hand position at [nowNanos] in degrees a linear mouse would have turned, summed over the reaches. */
    private fun handPosition(nowNanos: Long): Pair<Double, Double> {
        var yaw = baseHandYaw
        var pitch = baseHandPitch
        for (r in reaches) {
            val s = minimumJerk((nowNanos - r.startNanos).toDouble() / r.durationNanos)
            yaw += r.dYaw * r.handScale * s
            pitch += r.dPitch * r.handScale * s
            if (r.handTilt != 0.0) pitch += r.dYaw * r.handScale * r.handTilt * s
        }
        return yaw to pitch
    }

    private fun foldFinished(nowNanos: Long) {
        val done = reaches.filter { it.endNanos <= nowNanos }
        for (r in done) {
            baseYaw += r.dYaw
            basePitch += r.dPitch
            baseHandYaw += r.dYaw * r.handScale
            baseHandPitch += r.dPitch * r.handScale
            if (r.handTilt != 0.0) baseHandPitch += r.dYaw * r.handScale * r.handTilt
        }
        reaches.removeAll(done.toSet())
    }

    private fun lastEnd(): Long = reaches.maxOfOrNull { it.endNanos } ?: 0L

    private fun emitCounts(nowNanos: Long, click: Boolean): AimerStep {
        val (yaw, pitch) = handPosition(nowNanos)
        val x = (yaw / degPerCount).roundToLong()
        val y = (pitch / degPerCount).roundToLong()
        var dx = (x - emittedX).toInt()
        var dy = -(y - emittedY).toInt() // mouse down (positive dy) looks down
        emittedX = x
        emittedY = y
        if (player.hand.distortsCounts && (dx != 0 || dy != 0)) {
            val (hx, hy) = player.hand.distort(dx.toDouble(), dy.toDouble())
            val carriedX = hx + handResidualX
            val carriedY = hy + handResidualY
            dx = carriedX.roundToInt()
            dy = carriedY.roundToInt()
            handResidualX = carriedX - dx
            handResidualY = carriedY - dy
        }
        if (dx != 0 || dy != 0) {
            val move = modifier?.modify(MouseEvent(nowNanos, dx, dy))
            trueYaw += (move?.dx ?: dx.toDouble()) * degPerCount
            truePitch -= (move?.dy ?: dy.toDouble()) * degPerCount
        }
        return if (dx == 0 && dy == 0 && !click) AimerStep.IDLE else AimerStep(dx, dy, click)
    }

    private fun gaussian(): Double {
        val u1 = 1.0 - random.nextDouble()
        return sqrt(-2.0 * ln(u1)) * cos(2.0 * PI * random.nextDouble())
    }

    private fun ms(value: Double): Long = (value * NANOS_PER_MS).toLong()

    private fun sq(v: Double) = v * v

    private fun log2(v: Double) = ln(v) / LN2

    private fun minimumJerk(tau: Double): Double {
        val t = tau.coerceIn(0.0, 1.0)
        return t * t * t * (10 + t * (-15 + 6 * t))
    }

    private companion object {
        const val FULL_TURN = 360.0

        /** Reaches shorter than this get the range effect of this length, so a tiny correction-like reach is not blown up. */
        const val MIN_RANGE_AMPLITUDE = 2.0

        /** A decoupled reach keeps at least this share of its planned time, even when the noise nearly cancels it. */
        const val MIN_DECOUPLED_SHARE = 0.2

        /** A rushed first reach is never scaled below this share of its distance by [SimulatedPlayer.pressureUndershoot]. */
        const val MIN_PRESSURE_GAIN = 0.5
        const val MS_PER_S = 1000.0
        const val NANOS_PER_MS = 1_000_000.0
        const val NANOS_PER_MINUTE = 60_000_000_000.0
        const val NANOS_PER_SECOND = 1_000_000_000.0
        const val MIN_REACTION_MS = 120.0

        /** A target that was already in view may be re-acquired faster than the usual minimum reaction. */
        const val REACQUIRE_FLOOR = 0.5
        const val TRACK_STEP_MS = 40.0
        const val TRACK_REACH_MS = 80.0
        const val TRACK_HISTORY_MS = 600.0
        const val VELOCITY_WINDOW_MS = 100.0
        const val GAIN_ITERATIONS = 3

        /** Peak speed of a minimum-jerk movement relative to its average speed. */
        const val PEAK_TO_MEAN_SPEED = 1.875
        const val MIN_REACH_MS = 60.0
        const val MIN_WIDTH_DEGREES = 0.3

        /** A turn away from a flash does not aim at anything; it only has to get far enough, so it is as sloppy as a wide target. */
        const val FLASH_TURN_WIDTH_DEGREES = 25.0
        const val CORRECTION_SPEEDUP = 0.6

        /** A following player far further off than this makes a corrective reach instead of a smooth correction. */
        const val RANGE_CORRECTION_DEGREES = 3.0

        const val MISS_RECHECK_MS = 100.0

        /** A strafing target seen moving faster than this is not stopped; the player waits at most [MAX_STOP_WAIT_MS] for it, looking again every [STOP_RECHECK_MS]. */
        const val STOP_SPEED_DEGREES_PER_SECOND = 1.5
        const val MAX_STOP_WAIT_MS = 800.0
        const val STOP_RECHECK_MS = 20.0
        const val TWITCH_FRACTION = 0.25
        const val TWITCH_MS = 30.0
        val LN2 = ln(2.0)
    }
}
