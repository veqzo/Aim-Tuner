package dev.veqzo.aimtune.input

import dev.veqzo.aimtune.core.accel.AccelCurve
import dev.veqzo.aimtune.core.input.MouseEvent
import dev.veqzo.aimtune.core.scenario.FlashState
import dev.veqzo.aimtune.core.scenario.ScenarioKind
import dev.veqzo.aimtune.core.scenario.Target
import dev.veqzo.aimtune.core.sim.AIMER_SEED_MIX
import dev.veqzo.aimtune.core.sim.ManualClock
import dev.veqzo.aimtune.core.sim.SimulatedAimer
import dev.veqzo.aimtune.core.sim.SimulatedPlayer
import java.util.ArrayDeque

/**
 * A synthetic player as a [RawMouseSource], for tests and the screenshot tour. It reads the current
 * targets from [activeTargets] (the scenario's, via the trainer) and produces raw counts with
 * timestamps. Time is virtual: the driver advances the [clock] (1 ms per step for 1000 Hz), calls
 * [tick], lets the consumer drain, and fires a shot when [consumeClick] says so. Call [beginRound]
 * right before a round starts. Like every source it delivers nothing while the window is unfocused.
 */
class SimulatedMouseSource(
    player: SimulatedPlayer,
    dpi: Double,
    cm360: Double,
    seed: Long,
    override val clock: ManualClock,
    kind: ScenarioKind = ScenarioKind.FLICK,
    accel: AccelCurve? = null,
    private val activeFlash: () -> FlashState? = { null },
    private val activeTargets: () -> List<Target>,
) : RawMouseSource {
    private val aimer = SimulatedAimer(player, dpi, cm360, seed xor AIMER_SEED_MIX, kind, accel)
    private val queue = ArrayDeque<MouseEvent>()
    private var focused = true
    private var started = false
    private var clickPending = false

    override val name = "Simulated player"

    override var eventsReceived = 0L
        private set

    override fun start() {
        started = true
    }

    override fun setWindowFocused(focused: Boolean) {
        this.focused = focused
    }

    /** Resets the aimer to the trainer's initial view; [sessionOffsetNanos] is the session time spent before this round (fatigue). */
    fun beginRound(sessionOffsetNanos: Long = 0L) {
        aimer.reset(clock.nowNanos(), sessionOffsetNanos)
        queue.clear()
        clickPending = false
    }

    /** Lets the player act at the clock's current time. */
    fun tick() {
        if (!started || !focused) return
        val now = clock.nowNanos()
        val step = aimer.step(now, activeTargets(), activeFlash())
        if (step.dx != 0 || step.dy != 0) {
            queue.add(MouseEvent(now, step.dx, step.dy))
            eventsReceived++
        }
        if (step.click) clickPending = true
    }

    /** True once per click the player made since the last call. */
    fun consumeClick(): Boolean = clickPending.also { clickPending = false }

    override fun drain(consumer: (MouseEvent) -> Unit) {
        while (true) consumer(queue.poll() ?: return)
    }

    override fun close() {
        started = false
    }
}
