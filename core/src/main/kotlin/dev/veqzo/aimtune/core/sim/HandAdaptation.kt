package dev.veqzo.aimtune.core.sim

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow

/**
 * Two-state motor adaptation (Smith, Ghazizadeh & Shadmehr 2006) of the gain a hand expects, as a function of the hand
 * speed. After every first reach the hand compares the gain it expected with the one the mouse delivered, and a fast and a
 * slow process each move toward it ([fastRate], [slowRate]) while both decay back toward the player's preference
 * ([fastRetention], [slowRetention]). A mismatch is learned at the speed where it happened and, with [generalization]
 * (in ln(in/s)), at nearby speeds. The published fit values are the defaults; they come from reaching studies, not from
 * aiming with a mouse, which is why this is an option of the simulated player and not its default.
 *
 * The decay toward the preference keeps a part of every constant mismatch ([steadyResidual], 25 % with the defaults):
 * a preference stays measurable, but smaller than without adaptation, and a new curve first gives an aftereffect.
 *
 * Mutable: one instance belongs to one simulated person and carries its state from round to round.
 */
class HandAdaptation(
    val fastRetention: Double = 0.59,
    val fastRate: Double = 0.21,
    val slowRetention: Double = 0.992,
    val slowRate: Double = 0.02,
    val generalization: Double = 0.35,
) {
    init {
        require(fastRetention in 0.0..<1.0 && slowRetention in 0.0..<1.0) { "retention must be in [0, 1)" }
        require(fastRate >= 0.0 && slowRate >= 0.0 && generalization > 0.0) { "invalid adaptation rates" }
    }

    private val fast = DoubleArray(KNOTS)
    private val slow = DoubleArray(KNOTS)

    /** Long-run share of a constant mismatch that stays. */
    val steadyResidual: Double get() = 1.0 / (1.0 + fastGain + slowGain)

    private val fastGain get() = fastRate / (1.0 - fastRetention)
    private val slowGain get() = slowRate / (1.0 - slowRetention)

    /** Factor on the preferred gain the hand expects at [speed] in/s; 1 without adaptation. */
    fun factor(speed: Double): Double {
        val position = (ln(max(speed, MIN_SPEED)) - LN_MIN) / STEP
        val i = position.toInt().coerceIn(0, KNOTS - 2)
        val t = (position - i).coerceIn(0.0, 1.0)
        val at = { k: Int -> fast[k] + slow[k] }
        return exp(at(i) * (1 - t) + at(i + 1) * t)
    }

    /** One first reach at [speed] in/s where the mouse delivered [delivered] while the hand expected [expected]. */
    fun update(speed: Double, expected: Double, delivered: Double) {
        if (expected <= 0.0 || delivered <= 0.0) return
        val error = ln(delivered / expected)
        val lnSpeed = ln(max(speed, MIN_SPEED))
        for (k in 0 until KNOTS) {
            val d = (lnSpeed - knot(k)) / generalization
            val weight = exp(-0.5 * d * d)
            // Retention per exposure: a speed that is not played keeps what was learned there.
            fast[k] = fastRetention.pow(weight) * fast[k] + fastRate * weight * error
            slow[k] = slowRetention.pow(weight) * slow[k] + slowRate * weight * error
        }
    }

    private fun knot(k: Int) = LN_MIN + k * STEP

    companion object {
        private const val MIN_SPEED = 0.5
        private val LN_MIN = ln(MIN_SPEED)
        private const val STEP = 0.1
        /** Knots from 0.5 to about 135 in/s. */
        private const val KNOTS = 57

        /**
         * A hand that has played long with [delivered] (gain at a hand speed) while preferring [preference]: the steady
         * state of the two processes, the expected situation of a player who comes from the game with plain sensitivity.
         */
        fun adaptedTo(
            preference: (Double) -> Double,
            delivered: (Double) -> Double = { 1.0 },
            template: HandAdaptation = HandAdaptation(),
        ): HandAdaptation {
            val hand = HandAdaptation(template.fastRetention, template.fastRate, template.slowRetention, template.slowRate, template.generalization)
            for (k in 0 until KNOTS) {
                val speed = exp(hand.knot(k))
                val residualLog = hand.steadyResidual * ln(delivered(speed) / preference(speed))
                hand.fast[k] = hand.fastGain * residualLog
                hand.slow[k] = hand.slowGain * residualLog
            }
            return hand
        }
    }
}
