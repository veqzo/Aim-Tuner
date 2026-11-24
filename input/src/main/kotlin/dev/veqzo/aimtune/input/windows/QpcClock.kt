package dev.veqzo.aimtune.input.windows

import com.sun.jna.ptr.LongByReference
import dev.veqzo.aimtune.core.input.NanoClock

/** [NanoClock] on QueryPerformanceCounter, the high-resolution timestamp source required for mouse reports. */
class QpcClock : NanoClock {
    private val frequency: Long
    private val scratch = ThreadLocal.withInitial { LongByReference() }

    init {
        val f = LongByReference()
        check(Kernel32Raw.INSTANCE.QueryPerformanceFrequency(f) && f.value > 0) { "QueryPerformanceFrequency failed" }
        frequency = f.value
    }

    override fun nowNanos(): Long {
        val counter = scratch.get()
        check(Kernel32Raw.INSTANCE.QueryPerformanceCounter(counter)) { "QueryPerformanceCounter failed" }
        return ticksToNanos(counter.value, frequency)
    }

    internal companion object {
        private const val NANOS_PER_SECOND = 1_000_000_000L

        /** Converts counter ticks to nanoseconds without overflowing for long uptimes. */
        fun ticksToNanos(ticks: Long, frequency: Long): Long {
            val seconds = ticks / frequency
            val remainder = ticks % frequency
            return seconds * NANOS_PER_SECOND + remainder * NANOS_PER_SECOND / frequency
        }
    }
}
