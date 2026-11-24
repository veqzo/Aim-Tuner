package dev.veqzo.aimtune.core.testing

import dev.veqzo.aimtune.core.input.MouseEvent
import kotlin.math.roundToLong

/** One minimum-jerk reach: displaces ([dx], [dy]) counts over [durationMs], starting at [startMs]. */
data class Reach(val startMs: Double, val durationMs: Double, val dx: Double, val dy: Double = 0.0)

/**
 * Builds mouse reports the way a real mouse would see ideal hand motion: the position is sampled at
 * [pollingHz], rounded to whole counts, and only non-zero count changes are reported.
 */
object SyntheticMouse {
    private const val NANOS_PER_MS = 1_000_000L

    private fun minJerk(tau: Double): Double {
        val t = tau.coerceIn(0.0, 1.0)
        return 10 * t * t * t - 15 * t * t * t * t + 6 * t * t * t * t * t
    }

    fun events(
        reaches: List<Reach>,
        totalMs: Double,
        pollingHz: Int = 1000,
        originNanos: Long = 0L,
    ): List<MouseEvent> {
        val stepNanos = 1_000_000_000L / pollingHz
        val steps = (totalMs * NANOS_PER_MS / stepNanos).toLong()
        val out = ArrayList<MouseEvent>()
        var prevX = 0L
        var prevY = 0L
        for (k in 1..steps) {
            val tMs = k * stepNanos / NANOS_PER_MS.toDouble()
            var x = 0.0
            var y = 0.0
            for (r in reaches) {
                val s = minJerk((tMs - r.startMs) / r.durationMs)
                x += r.dx * s
                y += r.dy * s
            }
            val px = x.roundToLong()
            val py = y.roundToLong()
            if (px != prevX || py != prevY) {
                out += MouseEvent(originNanos + k * stepNanos, (px - prevX).toInt(), (py - prevY).toInt())
                prevX = px
                prevY = py
            }
        }
        return out
    }
}
