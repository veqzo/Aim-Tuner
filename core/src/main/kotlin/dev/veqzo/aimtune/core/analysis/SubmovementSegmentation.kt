package dev.veqzo.aimtune.core.analysis

import dev.veqzo.aimtune.core.input.MouseEvent
import kotlin.math.hypot

/**
 * Tunable thresholds of the segmentation. Speeds are physical (cm/s) so they do not depend on DPI.
 * See docs/ALGORITHMS.md ("Submovement segmentation") for the reasoning behind the defaults.
 */
data class SegmentationParams(
    val binNanos: Long = 1_000_000L,
    val smoothingBins: Int = 7,
    val startSpeedCmPerS: Double = 2.0,
    val endSpeedCmPerS: Double = 1.0,
    val minQuietNanos: Long = 30_000_000L,
    val minMovementNanos: Long = 30_000_000L,
    val splitRatio: Double = 0.4,
    val minSubmovementNanos: Long = 40_000_000L,
) {
    init {
        require(binNanos > 0 && smoothingBins % 2 == 1) { "binNanos must be positive and smoothingBins odd" }
        require(startSpeedCmPerS > endSpeedCmPerS && endSpeedCmPerS > 0) { "need start speed > end speed > 0" }
        require(splitRatio in 0.0..1.0) { "splitRatio must be in [0, 1]" }
    }
}

/** One ballistic or corrective hand movement between two speed minima. Counts are raw mouse counts. */
data class Submovement(
    val startNanos: Long,
    val peakNanos: Long,
    val endNanos: Long,
    val dx: Int,
    val dy: Int,
    val pathCounts: Double,
    val peakSpeedCountsPerMs: Double,
) {
    val durationNanos: Long get() = endNanos - startNanos
}

/**
 * Splits mouse movement into submovements: a smoothed speed profile is cut into movements with
 * hysteresis thresholds, and each movement is split at speed minima that are deep compared with
 * the neighbouring peaks. [events] must be sorted by time.
 */
fun segmentSubmovements(
    events: List<MouseEvent>,
    dpi: Double,
    params: SegmentationParams = SegmentationParams(),
): List<Submovement> {
    require(dpi > 0) { "dpi must be positive" }
    if (events.isEmpty()) return emptyList()
    val profile = SpeedProfile.build(events, params)
    val countsPerMsPerCmPerS = dpi / CM_PER_INCH / MS_PER_S
    val startThreshold = params.startSpeedCmPerS * countsPerMsPerCmPerS
    val endThreshold = params.endSpeedCmPerS * countsPerMsPerCmPerS
    val minQuietBins = (params.minQuietNanos / params.binNanos).toInt().coerceAtLeast(1)
    val minMovementBins = (params.minMovementNanos / params.binNanos).toInt().coerceAtLeast(1)
    val minSubBins = (params.minSubmovementNanos / params.binNanos).toInt().coerceAtLeast(1)

    return findMovements(profile.speed, startThreshold, endThreshold, minQuietBins, minMovementBins).flatMap { (from, to) ->
        val valleys = splitValleys(profile.speed, from, to, startThreshold, params.splitRatio, minSubBins)
        val bounds = listOf(from) + valleys + to
        bounds.zipWithNext().map { (a, b) -> buildSubmovement(events, profile, a, b) }
    }
}

private const val CM_PER_INCH = 2.54
private const val MS_PER_S = 1000.0
private const val EPSILON = 1e-9

/** Speed in counts per ms on a regular time grid, smoothed by a centered moving average of the count vectors. */
private class SpeedProfile(val originNanos: Long, val binNanos: Long, val speed: DoubleArray) {
    fun binStart(index: Int) = originNanos + index * binNanos

    companion object {
        fun build(events: List<MouseEvent>, params: SegmentationParams): SpeedProfile {
            val origin = events.first().timeNanos
            val padding = params.smoothingBins + (params.minQuietNanos / params.binNanos).toInt() + 1
            val bins = ((events.last().timeNanos - origin) / params.binNanos).toInt() + 1 + padding
            val dx = DoubleArray(bins)
            val dy = DoubleArray(bins)
            for (e in events) {
                val i = ((e.timeNanos - origin) / params.binNanos).toInt()
                dx[i] += e.dx.toDouble()
                dy[i] += e.dy.toDouble()
            }
            val sx = movingAverage(dx, params.smoothingBins)
            val sy = movingAverage(dy, params.smoothingBins)
            val binMs = params.binNanos / 1e6
            return SpeedProfile(origin, params.binNanos, DoubleArray(bins) { hypot(sx[it], sy[it]) / binMs })
        }

        private fun movingAverage(values: DoubleArray, window: Int): DoubleArray {
            val half = window / 2
            val prefix = DoubleArray(values.size + 1)
            for (i in values.indices) prefix[i + 1] = prefix[i] + values[i]
            return DoubleArray(values.size) {
                val lo = maxOf(0, it - half)
                val hi = minOf(values.size, it + half + 1)
                (prefix[hi] - prefix[lo]) / window
            }
        }
    }
}

/** Movements as half-open bin ranges: start above [startThr], end after [minQuietBins] below [endThr]. */
private fun findMovements(speed: DoubleArray, startThr: Double, endThr: Double, minQuietBins: Int, minMovementBins: Int): List<Pair<Int, Int>> {
    val movements = mutableListOf<Pair<Int, Int>>()
    var i = 0
    while (i < speed.size) {
        if (speed[i] <= startThr) {
            i++
            continue
        }
        var from = i
        while (from > 0 && speed[from - 1] > endThr) from--
        var j = i
        var quiet = 0
        while (j < speed.size && quiet < minQuietBins) {
            quiet = if (speed[j] < endThr) quiet + 1 else 0
            j++
        }
        val to = if (quiet >= minQuietBins) j - quiet else speed.size
        if (to - from >= minMovementBins) movements += from to to
        i = j
    }
    return movements
}

private data class Extremum(val index: Int, val isPeak: Boolean)

/** Bin indices inside [from, to) at which a movement is split, after discarding shallow or too close valleys. */
private fun splitValleys(speed: DoubleArray, from: Int, to: Int, startThr: Double, splitRatio: Double, minSubBins: Int): List<Int> {
    val extrema = alternatingExtrema(speed, from, to)
    val peaks = extrema.filter { it.isPeak }.toMutableList()
    val valleys = extrema.filter { !it.isPeak }.toMutableList()
    val minPeak = 2 * startThr
    while (valleys.isNotEmpty()) {
        val worst = valleys.indices.maxBy { k -> valleyBadness(speed, peaks, valleys, k, from, to, minPeak, splitRatio, minSubBins) }
        if (valleyBadness(speed, peaks, valleys, worst, from, to, minPeak, splitRatio, minSubBins) <= 1.0) break
        // Merge: drop the valley and the lower of its two neighbouring peaks.
        val lower = if (speed[peaks[worst].index] <= speed[peaks[worst + 1].index]) worst else worst + 1
        peaks.removeAt(lower)
        valleys.removeAt(worst)
    }
    return valleys.map { it.index }
}

/** Values above 1.0 mean the valley is not a real split (too shallow, peaks too weak, or segments too short). */
private fun valleyBadness(
    speed: DoubleArray,
    peaks: List<Extremum>,
    valleys: List<Extremum>,
    k: Int,
    from: Int,
    to: Int,
    minPeak: Double,
    splitRatio: Double,
    minSubBins: Int,
): Double {
    val left = speed[peaks[k].index]
    val right = speed[peaks[k + 1].index]
    val depthRatio = speed[valleys[k].index] / minOf(left, right) / splitRatio
    val weakPeak = if (minOf(left, right) < minPeak) 2.0 else 0.0
    val previousBoundary = if (k == 0) from else valleys[k - 1].index
    val nextBoundary = if (k == valleys.size - 1) to else valleys[k + 1].index
    val tooShort = if (valleys[k].index - previousBoundary < minSubBins || nextBoundary - valleys[k].index < minSubBins) 2.0 else 0.0
    return maxOf(depthRatio, weakPeak, tooShort)
}

/** Local maxima and minima of the speed inside [from, to), starting and ending with a peak. */
private fun alternatingExtrema(speed: DoubleArray, from: Int, to: Int): List<Extremum> {
    val all = mutableListOf<Extremum>()
    var direction = 0
    for (i in from + 1 until to) {
        val d = speed[i] - speed[i - 1]
        val sign = if (d > EPSILON) 1 else if (d < -EPSILON) -1 else 0
        if (sign == 0) continue
        if (direction == 1 && sign == -1) all += Extremum(i - 1, isPeak = true)
        if (direction == -1 && sign == 1) all += Extremum(i - 1, isPeak = false)
        direction = sign
    }
    if (direction == 1) all += Extremum(to - 1, isPeak = true)
    return all.dropWhile { !it.isPeak }.dropLastWhile { !it.isPeak }
}

private fun buildSubmovement(events: List<MouseEvent>, profile: SpeedProfile, fromBin: Int, toBin: Int): Submovement {
    val start = profile.binStart(fromBin)
    val end = profile.binStart(toBin)
    var dx = 0
    var dy = 0
    var path = 0.0
    for (e in events) {
        if (e.timeNanos < start) continue
        if (e.timeNanos >= end) break
        dx += e.dx
        dy += e.dy
        path += hypot(e.dx.toDouble(), e.dy.toDouble())
    }
    val peakBin = (fromBin until toBin).maxBy { profile.speed[it] }
    return Submovement(start, profile.binStart(peakBin) + profile.binNanos / 2, end, dx, dy, path, profile.speed[peakBin])
}
