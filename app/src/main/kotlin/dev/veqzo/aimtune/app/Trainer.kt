package dev.veqzo.aimtune.app

import dev.veqzo.aimtune.core.accel.MouseModifier
import dev.veqzo.aimtune.core.input.MouseEvent
import dev.veqzo.aimtune.core.math.Vec3
import dev.veqzo.aimtune.core.math.ViewAngles
import dev.veqzo.aimtune.core.recording.RoundRecording
import dev.veqzo.aimtune.core.scenario.FlashState
import dev.veqzo.aimtune.core.scenario.RoundSummary
import dev.veqzo.aimtune.core.scenario.Scenario
import dev.veqzo.aimtune.core.scenario.ShotResult
import dev.veqzo.aimtune.core.scenario.Target
import dev.veqzo.aimtune.core.scenario.summarize
import dev.veqzo.aimtune.core.units.degreesPerCount
import dev.veqzo.aimtune.input.RawMouseSource

enum class Phase { READY, RUNNING, FINISHED }

/**
 * Round state machine of the trainer. Free of rendering calls; everything runs on the render
 * thread. Pausing mid-round is not supported: aborting returns to READY and discards the round,
 * so a measured round is never interrupted silently. A completed round is handed to
 * [onRoundFinished] as a [RoundRecording].
 */
class Trainer(
    private val config: TrainerConfig,
    private val source: RawMouseSource,
    private val onRoundFinished: (RoundRecording) -> Unit = {},
) {
    private val log = System.getLogger("aimtune.trainer")
    private val degreesPerCount = degreesPerCount(config.dpi, config.cm360)
    private val clock = source.clock
    private val roundNanos = config.roundSeconds * 1_000_000_000L
    private var roundStartNanos = 0L
    private var roundSeed = 0L
    private var nextSeed = config.seed
    private var scenario: Scenario? = null
    private val recordedMouse = ArrayList<MouseEvent>()
    private var modifier: MouseModifier? = null

    var phase = Phase.READY
        private set
    var view = ViewAngles()
        private set
    var summary: RoundSummary? = null
        private set
    var lastEvent: MouseEvent? = null
        private set
    val rate = EventRateMeter()

    /** Called for every shot with its result, whether the click came from the window or from the report stream. */
    /** Called when a round begins. */
    var onRoundStarted: (() -> Unit)? = null

    var onShot: ((ShotResult) -> Unit)? = null

    val degreesPerCountValue: Double get() = degreesPerCount
    val activeTargets: List<Target> get() = if (phase == Phase.RUNNING) scenario?.targets.orEmpty() else emptyList()
    val activeFlash: FlashState? get() = if (phase == Phase.RUNNING) scenario?.flash else null
    val hits: Int get() = scenario?.let { summarize(it.events).hits } ?: 0
    val misses: Int get() = scenario?.let { summarize(it.events).misses } ?: 0

    fun nowNanos(): Long = clock.nowNanos()

    fun timeLeftNanos(): Long =
        if (phase == Phase.RUNNING) (roundStartNanos + roundNanos - nowNanos()).coerceAtLeast(0L) else 0L

    fun startRound() {
        roundSeed = nextSeed++
        onRoundStarted?.invoke()
        log.log(System.Logger.Level.INFO, "starting ${config.scenario.id} round, seed $roundSeed")
        source.drain { }
        view = ViewAngles()
        summary = null
        recordedMouse.clear()
        modifier = config.accel?.newModifier(config.dpi)
        scenario = config.scenario.create(roundNanos, roundSeed, config.geometry, config.rangeOptions)
        roundStartNanos = nowNanos()
        scenario?.start(roundStartNanos)
        phase = Phase.RUNNING
    }

    /** Aborting (Esc, focus loss) discards the running round. */
    fun abort() {
        if (phase != Phase.RUNNING) return
        log.log(System.Logger.Level.INFO, "round aborted")
        scenario = null
        recordedMouse.clear()
        phase = Phase.READY
    }

    /** Applies queued mouse input and ends the round when its time is up. Call once per frame. */
    fun update() {
        applyPendingMouse()
        val s = scenario ?: return
        if (phase != Phase.RUNNING) return
        val now = nowNanos()
        s.update(now, view)
        if (s.isFinished(now)) finishRound(s)
    }

    fun fire(): ShotResult {
        val s = scenario ?: return ShotResult.IGNORED
        if (phase != Phase.RUNNING) return ShotResult.IGNORED
        // With a source that reports clicks, shots come from the report stream (see applyPendingMouse).
        if (source.reportsClicks) return ShotResult.IGNORED
        // Bring the view and moving targets up to date first so the shot uses the latest state.
        applyPendingMouse()
        return shoot(s, nowNanos())
    }

    private fun finishRound(s: Scenario) {
        val end = roundStartNanos + roundNanos
        summary = summarize(s.events)
        phase = Phase.FINISHED
        onRoundFinished(
            RoundRecording(
                scenarioId = s.id,
                seed = roundSeed,
                dpi = config.dpi,
                cm360 = config.cm360,
                startNanos = roundStartNanos,
                endNanos = end,
                // Reports that arrived after the round's end belong to no round.
                mouseEvents = recordedMouse.filter { it.timeNanos <= end },
                targetEvents = s.events.toList(),
                accel = config.accel,
            ),
        )
    }

    private fun applyPendingMouse() {
        source.drain { event ->
            lastEvent = event
            rate.record(event.timeNanos)
            if (phase == Phase.RUNNING) {
                if (event.dx != 0 || event.dy != 0) {
                    val move = modifier?.modify(event)
                    view = view.rotatedByCounts(move?.dx ?: event.dx.toDouble(), move?.dy ?: event.dy.toDouble(), degreesPerCount)
                    recordedMouse += if (event.leftPressed) event.copy(leftPressed = false) else event
                }
                // A click in the report stream is shot at the view of that very report. A click within the
                // first moments is the one that started the round (it can arrive after the queue was cleared).
                if (event.leftPressed && source.reportsClicks && event.timeNanos - roundStartNanos >= MIN_SHOT_DELAY_NANOS) {
                    scenario?.let { shoot(it, event.timeNanos) }
                }
            }
        }
    }

    private fun shoot(s: Scenario, nowNanos: Long): ShotResult {
        s.update(nowNanos, view)
        return s.fire(Vec3.ZERO, view.direction(), nowNanos).also { onShot?.invoke(it) }
    }

    private companion object {
        /** No target can be answered sooner than this after the round began. */
        const val MIN_SHOT_DELAY_NANOS = 150_000_000L
    }
}
