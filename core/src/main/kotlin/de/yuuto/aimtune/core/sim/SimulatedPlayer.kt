package de.yuuto.aimtune.core.sim

/**
 * Parameters of the synthetic aimer used to verify the pipeline without a human. See
 * docs/ALGORITHMS.md ("Verification harness") for the model.
 *
 * The player is a hand with limited precision and speed: the endpoint error of a reach is
 * `sqrt((signalNoise * amplitude)^2 + (handNoiseCm * 360 / cm360)^2)` degrees (signal-dependent
 * noise plus a fixed hand precision, which hurts at high sensitivity), and the movement time adds
 * `distance / maxHandSpeed` (which hurts at low sensitivity). Their balance gives an interior
 * optimum cm/360 that [withOptimum] places where a test wants it.
 */
data class SimulatedPlayer(
    val reactionMeanMs: Double = 230.0,
    val reactionSdMs: Double = 30.0,
    val fittsInterceptMs: Double = 60.0,
    val fittsSlopeMs: Double = 90.0,
    val signalNoise: Double = 0.03,
    val handNoiseCm: Double = 0.07,
    val maxHandSpeedCmPerS: Double = 30.0,
    /** Mean fraction by which first reaches fall short of the target. */
    val undershootBias: Double = 0.03,
    val feedbackDelayMs: Double = 140.0,
    val clickDelayMs: Double = 60.0,
    val clickDelaySdMs: Double = 15.0,
    /** Multiplies every noise term; the sweeps use it as the "noise level". */
    val noiseLevel: Double = 1.0,
    /** Noise and times grow by this fraction per minute of session time. */
    val fatiguePerMinute: Double = 0.0,
    /** Moves the optimum: the player behaves as if cm/360 were divided by this. 1.0 is the base model. */
    val optimumScale: Double = 1.0,
    /**
     * The gain the player expects from the mouse as a function of the hand speed in inches per
     * second (peak speed of a movement; 1.0 = what the plain sensitivity gives). The player plans
     * its hand movements with it: where it expects more gain than it gets it undershoots, where it
     * expects less it overshoots, and an acceleration curve that delivers the expected gain removes
     * the error. This is what the acceleration analysis has to find. Keep it 1.0 at low speeds, as
     * the Raw Accel curves do.
     */
    val gainPreference: (Double) -> Double = { 1.0 },
    val maxCorrections: Int = 8,
    /** Reaction to a target that was already visible (switching), instead of one that just appeared. */
    val reacquireMs: Double = 110.0,
    /** Delay between what the target does and what the hand reacts to while tracking. */
    val trackingLagMs: Double = 150.0,
    /** Share of the target's motion during that delay the player anticipates (0 = none, 1 = perfect). */
    val trackingPrediction: Double = 0.8,
    /**
     * Strafing click targets (micro-strafe, switch-strafe): the player aims at the position the target is predicted to have
     * (the tracking lag and prediction above), and the predicted lead is off by this relative standard deviation, because a
     * target that moves, stops and moves again is not predictable.
     */
    val strafePredictionNoise: Double = 0.35,
    /** Share of strafing targets the player does not click while they are seen moving: it times the click for the stop. */
    val strafeWaitForStop: Double = 0.4,
    /** Fraction of the remaining error corrected per control step (every 40 ms). */
    val trackingGain: Double = 0.7,
    /** Fastest the hand can move while tracking smoothly; slower than a flick, so a high cm/360 falls behind fast targets. */
    val trackingMaxHandSpeedCmPerS: Double = 4.0,
    /** Hand noise per control step as a multiple of the flick hand noise (smooth pursuit is less precise than a flick). */
    val trackingNoiseShare: Double = 2.0,
    /** How far beyond the minimum the player turns away from a flash, in degrees; people do not turn just enough. */
    val flashTurnMarginDegrees: Double = 10.0,
    /** Reaction to the enemy that appears when a flash pops; the pop is expected, so this is quicker than a surprise. */
    val flashReturnReactionMs: Double = 170.0,
    /** Reaction to a flash that becomes visible: the player expects flashes and is ready to turn, so this is quicker than a surprise. */
    val flashTurnReactionMs: Double = 180.0,
    /** Whether a noisy reach is also faster ([NoiseModel.PLANNED]) or keeps its planned speed. */
    val noiseModel: NoiseModel = NoiseModel.PLANNED,
    /**
     * Range effect: first reaches are scaled by `(rangeReferenceDegrees / amplitude)^rangeBias`, so short ones overshoot and
     * long ones fall short, whatever the gain. 0 is off. A bias that depends on the distance looks like a speed-dependent
     * gain to an analysis, because distance and speed go together.
     */
    val rangeBias: Double = 0.0,
    val rangeReferenceDegrees: Double = 20.0,
    /**
     * Extra undershoot of a first reach that is rushed by a deadline: the reach is scaled by `1 - pressureUndershoot *
     * (speedup - 1)`, where speedup is how much faster than unhurried it had to be. 0 (the default) means time pressure
     * only adds noise. A real hand may do this; a speed round then looks like a wish for more gain at speed, which is the
     * case the curve calibration cannot tell apart from a real one (docs/ALGORITHMS.md, "Speed rounds").
     */
    val pressureUndershoot: Double = 0.0,
    /**
     * Motor adaptation of the expected gain; null (the default) is a hand that never learns the curve it plays with. The
     * instance is mutable and carries the learning from round to round, so give every simulated person its own one.
     */
    val adaptation: HandAdaptation? = null,
    /** Deviations of the hand's counts that a Raw Accel rotation, Y/X, L/R, U/D ratio or angle snapping corrects; neutral by default. */
    val hand: HandGeometry = HandGeometry.NEUTRAL,
    /**
     * Scale of the endpoint noise of a reach along its direction (the extent) and across it (the direction); 1 (the default)
     * is the isotropic noise of the base model. Real first movements have a larger extent than direction spread; the passive
     * setting gate scales them to the spread of stored rounds.
     */
    val extentNoiseScale: Double = 1.0,
    val directionNoiseScale: Double = 1.0,
    /**
     * Time pressure of a duel: the share of click targets the player shoots at the end of its first reach without waiting
     * the [feedbackDelayMs] to see where the reach landed. It saves that look and any correction, and misses when the
     * reach's endpoint error is larger than the head; after a miss the player corrects as usual. 0 (the default) is the
     * patient player, who clicks only when it sees the crosshair on the target, so its first shots nearly always hit.
     */
    val rushClickChance: Double = 0.0,
) {
    init {
        require(noiseLevel >= 0.0 && fatiguePerMinute >= 0.0 && optimumScale > 0.0) { "invalid player parameters" }
        require(extentNoiseScale > 0.0 && directionNoiseScale > 0.0) { "noise scales must be positive" }
        require(rushClickChance in 0.0..1.0) { "rush click chance must be in [0, 1]" }
        require(rangeBias >= 0.0 && rangeReferenceDegrees > 0.0) { "invalid range bias" }
        require(undershootBias in 0.0..<1.0) { "undershoot bias must be in [0, 1)" }
        require(pressureUndershoot >= 0.0) { "pressure undershoot must not be negative" }
        require(strafePredictionNoise >= 0.0 && strafeWaitForStop in 0.0..1.0) { "invalid strafe parameters" }
        require(handNoiseCm > 0.0 && maxHandSpeedCmPerS > 0.0) { "hand parameters must be positive" }
    }

    /** The same player with its optimum at [cm360] (valid because all effects depend on `cm360 / optimumScale` only). */
    fun withOptimum(cm360: Double): SimulatedPlayer {
        require(cm360 > 0.0) { "cm360 must be positive" }
        return copy(optimumScale = cm360 / BASE_OPTIMUM_CM360)
    }

    /** The optimum this player was configured for. */
    val declaredOptimumCm360: Double get() = BASE_OPTIMUM_CM360 * optimumScale

    companion object {
        /**
         * Optimum of the default parameters, measured with the reference sweep (a test guards it).
         * The tolerance of that test is the accuracy of this constant.
         */
        const val BASE_OPTIMUM_CM360 = 50.0

        /**
         * Optimum of the default parameters with the head-sized targets of the game profiles (0.4-1.9 degrees, flick
         * scenario), measured on a 40-640 cm/360 sweep (docs/history/ASSESSMENT.md C). The default hand was calibrated on
         * large targets and wants about 156 cm/360 there, far outside what people use (pros 33-65 cm/360, controlled
         * study 20-80); see [forHeadSizedTargets].
         */
        const val BASE_OPTIMUM_HEAD_TARGETS_CM360 = 156.0

        /**
         * A hand whose optimum with the profile's head-sized targets lies at [optimumCm360] (about where people are).
         * All effects depend on `cm360 / optimumScale` only, so scaling the default hand is exact.
         */
        fun forHeadSizedTargets(optimumCm360: Double = 50.0): SimulatedPlayer {
            require(optimumCm360 > 0.0) { "cm360 must be positive" }
            return SimulatedPlayer(optimumScale = optimumCm360 / BASE_OPTIMUM_HEAD_TARGETS_CM360)
        }
    }
}
