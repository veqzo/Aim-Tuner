package dev.veqzo.aimtune.app.accel

import dev.veqzo.aimtune.core.accel.MovementSample

/** The setup of the newest acceleration round and the plain-round data stored for it. */
data class LastCalibration(val dpi: Double, val cm360: Double, val data: PriorCalibration)

/**
 * Plain-sensitivity movements from the calibration (and plain test) rounds of earlier acceleration runs, so the error
 * analysis can use rounds from several days. They count only at the sensitivity of the new run: a different one
 * changes how far the first movements miss, so [CM360_TOLERANCE] keeps them to rounds that were played at about the same one.
 */
data class PriorCalibration(val rounds: Int, val samples: List<MovementSample>) {
    companion object {
        val NONE = PriorCalibration(0, emptyList())

        /** Share by which an earlier round's sensitivity may differ from the new one (3 %, well below the noise of a single reach). */
        const val CM360_TOLERANCE = 0.03

        /** The newest rounds that are used; older ones add little and make starting a run slow. */
        const val MAX_ROUNDS = 90
    }
}
