package dev.veqzo.aimtune.core.sim

import dev.veqzo.aimtune.core.input.NanoClock

/** A clock that only moves when told to; lets headless runs and replays be fast and deterministic. */
class ManualClock(startNanos: Long = 0L) : NanoClock {
    private var now = startNanos

    override fun nowNanos(): Long = now

    fun advance(nanos: Long) {
        require(nanos >= 0) { "a clock cannot run backwards" }
        now += nanos
    }
}
