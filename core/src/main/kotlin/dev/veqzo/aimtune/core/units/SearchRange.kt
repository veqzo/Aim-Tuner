package dev.veqzo.aimtune.core.units

import kotlin.math.ln
import kotlin.math.sqrt

/** How the player moves the mouse during a flick. */
enum class AimStyle { WRIST, MIXED, ARM }

/** The cm/360° interval the optimizer searches. */
data class SearchRange(val minCm360: Double, val maxCm360: Double) {
    init {
        require(minCm360 > 0.0 && maxCm360 > minCm360) { "need 0 < min < max, was $minCm360..$maxCm360" }
    }

    operator fun contains(cm360: Double): Boolean = cm360 in minCm360..maxCm360

    /** Midpoint on the log scale the optimizer works on. */
    val geometricMid: Double get() = sqrt(minCm360 * maxCm360)

    companion object {
        /** The range never gets narrower than this ratio max/min, so there is always something to compare. */
        const val MIN_RATIO = 1.3
    }
}

/** Typical cm/360° window per aim style. Kept as data so it can be tuned or loaded from config later. */
class SearchRangeTable(val ranges: Map<AimStyle, Pair<Double, Double>>) {
    init {
        require(ranges.keys.containsAll(AimStyle.entries)) { "table needs an entry for every aim style" }
        require(ranges.values.all { (lo, hi) -> lo > 0.0 && hi > lo }) { "each entry needs 0 < min < max" }
    }

    companion object {
        /** Initial values from the onboarding mockup: wrist 20-35, mixed 30-50, arm 45-80 cm/360°. */
        val DEFAULT = SearchRangeTable(
            mapOf(AimStyle.WRIST to (20.0 to 35.0), AimStyle.MIXED to (30.0 to 50.0), AimStyle.ARM to (45.0 to 80.0)),
        )
    }
}

/** The range reaches at least this factor below and above the current value. */
private const val CURRENT_VALUE_REACH = 1.6

/** A range never gets wider than this ratio; the side of the style window that is farther from the current value gives way. */
private const val MAX_RATIO = 6.0

/** The current value keeps at least this margin to both ends, also when the range had to be trimmed. */
private const val CURRENT_VALUE_MARGIN = 1.2

/** Half a turn fits within the pad width: the most a player has to cover in one swipe, longer turns need a lift. */
fun doesHalfTurnFitPad(cm360: Double, padWidthCm: Double): Boolean = cm360 / 2.0 <= padWidthCm

/** A full turn fits within the pad width. Informational only; it no longer limits the search range. */
fun doesTurnFitPad(cm360: Double, padWidthCm: Double): Boolean = cm360 <= padWidthCm

/**
 * The search range: centred on the current value in log space (at least 1.6x below and above it, so the
 * optimum can lie on either side), widened to the aim style's window of [table], and trimmed to a ratio
 * of 6 if style and current value are far apart (the style side gives way, the current value stays
 * inside with a margin). The pad width does not shape the range: players lift the mouse for long turns,
 * and cutting the range at the pad width hid optima above the current value for small pads
 * (docs/history/REVIEW.md M1). [padWidthCm] only has to be valid; use [doesHalfTurnFitPad] to warn.
 */
fun searchRange(
    style: AimStyle,
    currentCm360: Double,
    padWidthCm: Double,
    table: SearchRangeTable = SearchRangeTable.DEFAULT,
): SearchRange {
    require(currentCm360.isFinite() && currentCm360 > 0.0) { "currentCm360 must be positive, was $currentCm360" }
    require(padWidthCm.isFinite() && padWidthCm > 0.0) { "padWidthCm must be positive, was $padWidthCm" }
    val (tableLo, tableHi) = table.ranges.getValue(style)
    var lo = minOf(currentCm360 / CURRENT_VALUE_REACH, tableLo)
    var hi = maxOf(currentCm360 * CURRENT_VALUE_REACH, tableHi)
    if (hi / lo > MAX_RATIO) {
        val loSide = ln(currentCm360 / lo)
        val hiSide = ln(hi / currentCm360)
        if (loSide > hiSide) lo = hi / MAX_RATIO else hi = lo * MAX_RATIO
        lo = minOf(lo, currentCm360 / CURRENT_VALUE_MARGIN)
        hi = maxOf(hi, currentCm360 * CURRENT_VALUE_MARGIN)
    }
    return SearchRange(lo, hi)
}
