package dev.veqzo.aimtune.core.accel

import kotlin.math.abs
import kotlin.math.pow

/** The smallest `capY` / `limit` / multiplier an adjusted profile gets, so that a falling curve scaled up never reaches or crosses 0. */
internal const val MIN_HIGH_OUTPUT = 0.05

/** The speed (in/s) at which the base invariant reads "the multiplier at very low speed". */
const val LOW_SPEED_PROBE = 1e-6

private const val SYNC_FLOOR_TOLERANCE = 1e-13
private const val EDGE_MARGIN = 1e-9

/** The stages the profile has, in the order they are searched. Empty when there is nothing to refine without touching the base. */
fun RawAccelProfile.refineStages(): List<RefineParameter> = RefineParameter.entries.filter { supports(it) }

/** The accel blocks the driver reads: the whole one, or both when the profile is by component. */
private fun RawAccelProfile.axes(): List<RawAccelArgs> = if (speed.whole) listOf(args) else listOf(args, accelY)

/**
 * True when the profile has something [parameter] could change without touching the base. A by-component profile supports a
 * stage only if both axes do (the same factor goes to both).
 */
fun RawAccelProfile.supports(parameter: RefineParameter): Boolean = axes().all { it.supportsStage(parameter) }

private fun RawAccelArgs.supportsStage(parameter: RefineParameter): Boolean = when (mode) {
    RawAccelMode.CLASSIC -> when (parameter) {
        RefineParameter.HIGH_GAIN -> hasHighOutput()
        RefineParameter.TRANSITION -> inputOffset > 0.0 && hasHighOutput()
    }
    RawAccelMode.NATURAL -> limit != 1.0
    RawAccelMode.LUT -> RefineLut.rises(this)
    // A smooth step has a small but real multiplier at low speed (stepY / (1 + e^(2 pi / smooth))) that scaling the height would move.
    RawAccelMode.JUMP -> capY != 1.0 && (parameter == RefineParameter.TRANSITION || !jumpIsSmooth())
    RawAccelMode.SYNCHRONOUS -> parameter == RefineParameter.TRANSITION && syncFloorHeld()
    RawAccelMode.POWER, RawAccelMode.NOACCEL -> false
}

/** A classic curve whose cap output is the point 1, or that has no effect at all, has no high gain to scale. */
private fun RawAccelArgs.hasHighOutput(): Boolean =
    if (capMode == RawAccelCapMode.IN_OUT || (capMode == RawAccelCapMode.OUTPUT && capY > 0.0)) capY != 1.0 else true

/** `accel-jump.hpp:17-24`: the step is smooth when `smooth * cap.x >= 1`. */
private fun RawAccelArgs.jumpIsSmooth(): Boolean = smooth * capX >= 1.0

/**
 * The synchronous low-speed multiplier is `1 / motivity` only while the sigmoid is saturated there, which depends on `syncSpeed`,
 * `gamma` and `smooth`. The stage is offered only when scaling `syncSpeed` over its whole range leaves the multiplier at
 * [LOW_SPEED_PROBE] equal to the imported one.
 */
private fun RawAccelArgs.syncFloorHeld(): Boolean {
    val floor = RawAccelCurve(this).multiplier(LOW_SPEED_PROBE)
    return listOf(RefineParameter.TRANSITION.min, RefineParameter.TRANSITION.max).all {
        abs(RawAccelCurve(copy(syncSpeed = syncSpeed * it)).multiplier(LOW_SPEED_PROBE) - floor) <= SYNC_FLOOR_TOLERANCE
    }
}

/**
 * The profile with [parameter] changed by [factor] (1 is the profile itself). Only the fields of [RefineShape] change; the result
 * always passes Raw Accel's validation (`RawAccelArgs`): a scaled cap output is kept above [MIN_HIGH_OUTPUT], and the cap point
 * never falls below the offset.
 * @throws IllegalArgumentException if the profile does not [supports] the parameter or [factor] is not positive.
 */
fun RawAccelProfile.adjusted(parameter: RefineParameter, factor: Double): RawAccelProfile {
    require(factor > 0.0 && factor.isFinite()) { "the factor must be positive" }
    require(supports(parameter)) { "${args.mode.json} profiles do not support $parameter" }
    if (factor == 1.0) return this
    return copy(args = args.adjusted(parameter, factor), accelY = if (speed.whole) accelY else accelY.adjusted(parameter, factor))
}

/** The profile with several adjustments applied in the order of [RefineParameter]; a parameter it does not support is skipped. */
fun RawAccelProfile.adjusted(factors: Map<RefineParameter, Double>): RawAccelProfile =
    RefineParameter.entries.fold(this) { profile, parameter ->
        val factor = factors[parameter]
        if (factor == null || !profile.supports(parameter)) profile else profile.adjusted(parameter, factor)
    }

private fun RawAccelArgs.adjusted(parameter: RefineParameter, factor: Double): RawAccelArgs = when (parameter) {
    RefineParameter.HIGH_GAIN -> withHighGain(factor)
    RefineParameter.TRANSITION -> withTransition(factor)
}

private fun RawAccelArgs.withHighGain(s: Double): RawAccelArgs = when (mode) {
    RawAccelMode.CLASSIC -> {
        val capsOutput = capMode == RawAccelCapMode.IN_OUT || (capMode == RawAccelCapMode.OUTPUT && capY > 0.0)
        if (capsOutput) copy(capY = (1.0 + (capY - 1.0) * s).coerceAtLeast(MIN_HIGH_OUTPUT))
        else copy(acceleration = acceleration * s.pow(1.0 / (exponentClassic - 1.0)))
    }
    RawAccelMode.NATURAL -> copy(limit = (1.0 + (limit - 1.0) * s).coerceAtLeast(MIN_HIGH_OUTPUT), decayRate = decayRate * s)
    RawAccelMode.LUT -> RefineLut.highGain(this, s)
    RawAccelMode.JUMP -> copy(capY = (1.0 + (capY - 1.0) * s).coerceAtLeast(MIN_HIGH_OUTPUT))
    RawAccelMode.SYNCHRONOUS, RawAccelMode.POWER, RawAccelMode.NOACCEL -> error("unreachable: $mode has no high gain")
}

private fun RawAccelArgs.withTransition(t: Double): RawAccelArgs = when (mode) {
    RawAccelMode.CLASSIC -> {
        val offset = inputOffset * t
        val movesCap = capMode != RawAccelCapMode.OUTPUT && capX > 0.0 && t > 1.0
        copy(inputOffset = offset, capX = if (movesCap) capX * t else capX)
    }
    RawAccelMode.NATURAL -> copy(decayRate = decayRate / t)
    RawAccelMode.LUT -> RefineLut.transition(this, t)
    RawAccelMode.JUMP -> copy(capX = jumpCapX(t))
    RawAccelMode.SYNCHRONOUS -> copy(syncSpeed = syncSpeed * t)
    RawAccelMode.POWER, RawAccelMode.NOACCEL -> error("unreachable: $mode has no transition")
}

/**
 * `capX * t`, but never across `1 / smooth` (`accel-jump.hpp:17-24`): a step that turns smooth or hard would change the multiplier
 * at low speed, and the base does not move.
 */
private fun RawAccelArgs.jumpCapX(t: Double): Double {
    if (smooth <= 0.0) return capX * t
    val edge = 1.0 / smooth
    return if (jumpIsSmooth()) (capX * t).coerceAtLeast(edge * (1.0 + EDGE_MARGIN)) else (capX * t).coerceAtMost(edge * (1.0 - EDGE_MARGIN))
}
