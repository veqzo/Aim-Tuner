package dev.veqzo.aimtune.app

/**
 * Counts events inside a sliding time window to show the effective mouse report rate in the debug
 * overlay. Not thread-safe; use it from the render thread only.
 */
class EventRateMeter(private val windowNanos: Long = 1_000_000_000L) {
    private val times = ArrayDeque<Long>()

    fun record(timeNanos: Long) {
        times.addLast(timeNanos)
    }

    /** Events per second over the window ending at [nowNanos]. */
    fun ratePerSecond(nowNanos: Long): Double {
        while (times.isNotEmpty() && nowNanos - times.first() > windowNanos) times.removeFirst()
        return times.size * 1e9 / windowNanos
    }
}
