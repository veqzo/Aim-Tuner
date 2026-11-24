package dev.veqzo.aimtune.app

import kotlin.math.ceil

/**
 * The countdown between two rounds of a run: when it runs out the next round starts by itself. A
 * pause stops it; a resume starts it again from the full [seconds], so a player who took the hand off
 * the mouse always gets the whole lead-in.
 */
class RoundCountdown(val seconds: Float) {
    init {
        require(seconds > 0f) { "countdown needs a positive length, was $seconds" }
    }

    var remaining: Float = seconds
        private set

    var paused: Boolean = false
        private set

    var finished: Boolean = false
        private set

    /** The whole seconds still to wait, as shown: 3, 2, 1. */
    val shownSeconds: Int get() = ceil(remaining).toInt().coerceAtLeast(1)

    /** Part of the countdown still left, from 1 down to 0. */
    val fraction: Float get() = remaining / seconds

    /** Advances by [delta] seconds; true exactly once, on the frame the countdown ends. */
    fun tick(delta: Float): Boolean {
        if (paused || finished) return false
        remaining = (remaining - delta).coerceAtLeast(0f)
        if (remaining > 0f) return false
        finished = true
        return true
    }

    fun pause() {
        if (!finished) paused = true
    }

    fun resume() {
        if (!paused) return
        paused = false
        remaining = seconds
    }

    fun toggle() = if (paused) resume() else pause()
}
