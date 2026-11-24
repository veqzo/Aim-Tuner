package dev.veqzo.aimtune.core.accel

import dev.veqzo.aimtune.core.input.MouseEvent
import kotlin.math.hypot

/**
 * What the Raw Accel driver does to a stream of mouse reports, for the in-app simulation: the speed
 * of a report is its distance in inches (counts divided by the device [dpi]) over the time since the
 * previous report, clamped to Raw Accel's default limits (`rawaccel-base.hpp`: 1000 / 8000 / 2 ms to
 * 100 ms; `driver.cpp`: the first report after a pause sees the longest time), and the counts of the
 * report are multiplied by the curve's value at that speed. Carrying fractions into the next report
 * is not needed here, because the simulated view is continuous.
 */
class AccelProcessor(private val curve: AccelCurve, private val dpi: Double) {
    init {
        require(dpi > 0.0) { "dpi must be positive" }
    }

    private var lastNanos: Long? = null

    /** Speed of [event] in inches per second as Raw Accel would see it; advances the report clock. */
    fun speedOf(event: MouseEvent): Double {
        val previous = lastNanos
        lastNanos = event.timeNanos
        val ms = if (previous == null) MAX_TIME_MS else ((event.timeNanos - previous) / NANOS_PER_MS).coerceIn(MIN_TIME_MS, MAX_TIME_MS)
        return hypot(event.dx.toDouble(), event.dy.toDouble()) * (NORMALIZED_DPI / dpi) / ms
    }

    /** The factor to multiply the counts of [event] with. */
    fun scaleFor(event: MouseEvent): Double = curve.multiplier(speedOf(event))

    /** Forgets the previous report, as after a pause. */
    fun reset() {
        lastNanos = null
    }

    companion object {
        /** At this DPI one count per ms is one inch per second. */
        const val NORMALIZED_DPI = 1000.0
        const val MIN_TIME_MS = 1000.0 / 8000.0 / 2.0
        const val MAX_TIME_MS = 100.0
        private const val NANOS_PER_MS = 1_000_000.0

        /** Speed in inches per second of a movement of [counts] per ms at [dpi]. */
        fun speedInPerSecond(countsPerMs: Double, dpi: Double): Double = countsPerMs * NORMALIZED_DPI / dpi
    }
}
