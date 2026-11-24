package dev.veqzo.aimtune.app.ui

import com.badlogic.gdx.graphics.Color
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/** A shaded area between [lo] and [hi] along [xs], for uncertainty bands. */
class ChartBand(val xs: DoubleArray, val lo: DoubleArray, val hi: DoubleArray, val color: Color) {
    init {
        require(xs.size == lo.size && xs.size == hi.size) { "band arrays differ in size" }
    }
}

/** A polyline, optionally dashed; dashes carry the series identity next to the color. */
class ChartLine(val xs: DoubleArray, val ys: DoubleArray, val color: Color, val dashed: Boolean = false) {
    init {
        require(xs.size == ys.size) { "line arrays differ in size" }
    }
}

class ChartDots(val xs: DoubleArray, val ys: DoubleArray, val color: Color) {
    init {
        require(xs.size == ys.size) { "dot arrays differ in size" }
    }
}

/** A dashed vertical line at [x] with a [label] at its top. */
class ChartMarker(val x: Double, val label: String, val color: Color)

/** Everything a [ChartActor] draws; ranges are the visible data window, ticks come from [niceTicks]. */
class ChartSpec(
    val xMin: Double,
    val xMax: Double,
    val yMin: Double,
    val yMax: Double,
    val xTicks: List<Double> = niceTicks(xMin, xMax),
    val yTicks: List<Double> = niceTicks(yMin, yMax),
    val formatX: (Double) -> String = ::formatTick,
    val formatY: (Double) -> String = ::formatTick,
    val bands: List<ChartBand> = emptyList(),
    val lines: List<ChartLine> = emptyList(),
    val dots: List<ChartDots> = emptyList(),
    val markers: List<ChartMarker> = emptyList(),
    /** Horizontal reference lines (for example the multiplier 1 of an acceleration curve). */
    val referenceYs: List<Double> = emptyList(),
) {
    init {
        require(xMax > xMin && yMax > yMin) { "empty chart range" }
    }
}

/** Round tick values inside [lo]..[hi], about [target] of them, at 1, 2 or 5 times a power of ten. */
fun niceTicks(lo: Double, hi: Double, target: Int = 5): List<Double> {
    require(hi > lo && target >= 2) { "invalid tick range" }
    val rough = (hi - lo) / (target - 1)
    val magnitude = 10.0.pow(floor(log10(rough)))
    val step = listOf(1.0, 2.0, 5.0, 10.0).map { it * magnitude }.first { it >= rough }
    val first = kotlin.math.ceil(lo / step - EPSILON) * step
    val ticks = generateSequence(first) { it + step }.takeWhile { it <= hi + step * EPSILON }.toList()
    return ticks.map { if (abs(it) < step * EPSILON) 0.0 else it }
}

/** A tick value without trailing zeros: 40, 2.5, 0.8. */
fun formatTick(value: Double): String {
    val text = "%.3f".format(java.util.Locale.ROOT, value).trimEnd('0').trimEnd('.')
    return if (text == "-0") "0" else text
}

private const val EPSILON = 1e-9
