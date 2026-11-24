package dev.veqzo.aimtune.app.blind

import dev.veqzo.aimtune.core.metrics.RoundMetrics
import dev.veqzo.aimtune.storage.RoundRow

/** Endpoint error of the first movements of one round per axis, in degrees (the vertical bias is signed: > 0 is too high). */
data class AxisErrors(val horizontalDegrees: Double, val verticalDegrees: Double, val verticalBiasDegrees: Double) {
    companion object {
        fun of(metrics: RoundMetrics): AxisErrors? {
            val h = metrics.meanAbsFirstErrorHDegrees ?: return null
            val v = metrics.meanAbsFirstErrorVDegrees ?: return null
            return AxisErrors(h, v, metrics.meanFirstErrorVDegrees ?: 0.0)
        }

        fun of(row: RoundRow): AxisErrors? {
            val h = row.metrics["mean_abs_first_error_h_deg"] ?: return null
            val v = row.metrics["mean_abs_first_error_v_deg"] ?: return null
            return AxisErrors(h, v, row.metrics["mean_first_error_v_deg"] ?: 0.0)
        }
    }
}

/** Which axis the misses come from, over several rounds. */
data class AxisSummary(val verticalShare: Double, val verticalBiasDegrees: Double, val rounds: Int) {
    enum class Verdict { VERTICAL_HIGH, VERTICAL_LOW, VERTICAL, HORIZONTAL, BALANCED }

    val verdict: Verdict
        get() = when {
            verticalShare >= DOMINANT && verticalBiasDegrees >= BIAS_DEGREES -> Verdict.VERTICAL_HIGH
            verticalShare >= DOMINANT && verticalBiasDegrees <= -BIAS_DEGREES -> Verdict.VERTICAL_LOW
            verticalShare >= DOMINANT -> Verdict.VERTICAL
            verticalShare <= 1.0 - DOMINANT -> Verdict.HORIZONTAL
            else -> Verdict.BALANCED
        }

    companion object {
        /** An axis counts as "most of the misses" from this share of the summed endpoint error. */
        const val DOMINANT = 0.6

        /** The vertical bias is only named when the average endpoint is this far above or below the target. */
        const val BIAS_DEGREES = 0.3
        const val MIN_ROUNDS = 3

        fun of(rounds: List<AxisErrors?>): AxisSummary? {
            val data = rounds.filterNotNull()
            if (data.size < MIN_ROUNDS) return null
            val h = data.sumOf { it.horizontalDegrees }
            val v = data.sumOf { it.verticalDegrees }
            if (h + v <= 0.0) return null
            return AxisSummary(v / (h + v), data.map { it.verticalBiasDegrees }.average(), data.size)
        }
    }
}
