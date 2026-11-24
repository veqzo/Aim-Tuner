package dev.veqzo.aimtune.app.audio

import dev.veqzo.aimtune.core.scenario.ShotResult
import kotlin.math.pow

/**
 * Every hit in a row rings a little higher, up a pentatonic scale (which never sounds wrong), and a miss starts the
 * climb again. That small reward for a clean streak is much of what makes hit sounds in aim trainers feel good.
 */
class HitCombo {
    var streak = 0
        private set

    /** The pitch factor for the hit that [result] reports, or null when nothing should play (a miss or an ignored click). */
    fun onShot(result: ShotResult): Float? = when (result) {
        ShotResult.HIT -> pitchFor(streak++)
        ShotResult.MISS -> { streak = 0; null }
        ShotResult.IGNORED -> null
    }

    fun reset() {
        streak = 0
    }

    companion object {
        /** Semitones above the base tone for each step of the streak; the last one repeats. */
        val STEPS = intArrayOf(0, 2, 4, 7, 9, 12)

        fun pitchFor(streak: Int): Float = 2.0.pow(STEPS[streak.coerceIn(0, STEPS.lastIndex)] / 12.0).toFloat()
    }
}
