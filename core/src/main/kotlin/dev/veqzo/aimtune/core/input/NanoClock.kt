package dev.veqzo.aimtune.core.input

/** Monotonic time source in nanoseconds. Origin is arbitrary; all components of a session share one instance. */
fun interface NanoClock {
    fun nowNanos(): Long
}

/** [NanoClock] backed by [System.nanoTime], for tests and non-Windows sources. */
object SystemNanoClock : NanoClock {
    override fun nowNanos(): Long = System.nanoTime()
}
