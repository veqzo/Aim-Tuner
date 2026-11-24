package dev.veqzo.aimtune.core.accel.settings

import kotlin.math.abs
import kotlin.math.roundToInt

/** The three output ratios of Raw Accel that an axis analysis can recommend. */
enum class AxisRatio { Y_X, L_R, U_D }

/**
 * Raw Accel's Y/X, L/R and U/D ratios from the length of first movements (docs/ALGORITHMS.md, "Passive setting analyses").
 *
 * For every direction (right, left, down, up) the gain G is how far the first movement went along that axis compared with how far
 * the target was: the weighted mean of `got / need` over the reaches whose target lies at least [MIN_COMPONENT_DEGREES] away along
 * that axis in that direction (ratio clipped to [[CLIP_LOW], [CLIP_HIGH]], weight `need^2`). The common undershoot of first
 * reaches is in every G and cancels in the ratios Raw Accel takes. The driver multiplies all vertical movement by Y/X, then upward movement by U/D and leftward movement
 * by L/R (`rawaccel.hpp:412-422`), so right and down are the directions no ratio touches: Y/X = G_right / G_down,
 * L/R = G_right / G_left, U/D = G_down / G_up (up multiplies by Y/X x U/D, so U/D is measured against down). A pure L/R or U/D
 * bias therefore does not move the Y/X estimate.
 *
 * Thresholds, fixed before the gate ran: at least [MIN_PER_SIDE] reaches on each side; interval = 95 % bootstrap of the ratio;
 * recommend only if the interval excludes 1 and the change is at least [MIN_CHANGE]; value rounded to 0.01.
 */
object AxisRatioAnalysis {
    const val MIN_PER_SIDE = 30
    const val MIN_COMPONENT_DEGREES = 4.0
    const val MIN_CHANGE = 0.05
    const val CLIP_LOW = 0.25
    const val CLIP_HIGH = 2.5
    private const val ROUNDING_STEP = 0.01

    /** One reach's contribution to a direction: the target distance along the axis and what the hand did, both positive in that direction. */
    internal class Sample(val need: Double, val got: Double) {
        val weight: Double get() = need * need
        val ratio: Double get() = (got / need).coerceIn(CLIP_LOW, CLIP_HIGH)
    }

    fun analyze(reaches: List<FirstReach>, ratio: AxisRatio): PassiveResult {
        val right = samples(reaches, horizontal = true, positive = true)
        val left = samples(reaches, horizontal = true, positive = false)
        val down = samples(reaches, horizontal = false, positive = true)
        val up = samples(reaches, horizontal = false, positive = false)
        val (top, bottom) = when (ratio) {
            AxisRatio.Y_X -> right to down
            AxisRatio.L_R -> right to left
            AxisRatio.U_D -> down to up
        }
        val smaller = minOf(top.size, bottom.size)
        if (smaller < MIN_PER_SIDE) return PassiveResult.NotEnoughData(smaller, MIN_PER_SIDE)
        val estimate = gain(top) / gain(bottom)
        val random = kotlin.random.Random(top.size * 31L + bottom.size)
        val draws = List(BOOTSTRAP_RESAMPLES) { gain(resample(top, random)) / gain(resample(bottom, random)) }
        val low = draws.percentile(INTERVAL_LOW)
        val high = draws.percentile(INTERVAL_HIGH)
        val n = top.size + bottom.size
        val excludesOne = low > 1.0 || high < 1.0
        if (!excludesOne || abs(estimate - 1.0) < MIN_CHANGE) return PassiveResult.NoChange(estimate, low, high, n)
        return PassiveResult.Recommend((estimate / ROUNDING_STEP).roundToInt() * ROUNDING_STEP, low, high, n)
    }

    internal fun samples(reaches: List<FirstReach>, horizontal: Boolean, positive: Boolean): List<Sample> = reaches.mapNotNull { r ->
        val sign = if (positive) 1.0 else -1.0
        val need = sign * (if (horizontal) r.needX else r.needY)
        val got = sign * (if (horizontal) r.gotX else r.gotY)
        if (need * r.degreesPerCount >= MIN_COMPONENT_DEGREES) Sample(need, got) else null
    }

    internal fun gain(samples: List<Sample>): Double = samples.sumOf { it.weight * it.ratio } / samples.sumOf { it.weight }

    private fun resample(samples: List<Sample>, random: kotlin.random.Random): List<Sample> = List(samples.size) { samples[random.nextInt(samples.size)] }
}
