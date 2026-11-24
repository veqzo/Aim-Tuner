package dev.veqzo.aimtune.app.harness

import dev.veqzo.aimtune.app.Phase
import dev.veqzo.aimtune.app.Trainer
import dev.veqzo.aimtune.app.TrainerConfig
import dev.veqzo.aimtune.core.accel.AccelCurve
import dev.veqzo.aimtune.core.accel.NaturalCurve
import dev.veqzo.aimtune.core.recording.RoundRecording
import dev.veqzo.aimtune.core.scenario.ScenarioKind
import dev.veqzo.aimtune.core.scenario.TargetEvent
import dev.veqzo.aimtune.core.sim.HeadlessRound
import dev.veqzo.aimtune.core.sim.ManualClock
import dev.veqzo.aimtune.core.sim.SimulatedPlayer
import dev.veqzo.aimtune.games.GameProfiles
import dev.veqzo.aimtune.input.ReplayMouseSource
import dev.veqzo.aimtune.input.SimulatedMouseSource
import dev.veqzo.aimtune.storage.RoundLogFile
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val NANOS_PER_MS = 1_000_000L
private const val NANOS_PER_SECOND = 1_000_000_000L
private const val ROUND_SECONDS = 20
private const val CLOCK_ORIGIN = 5 * NANOS_PER_SECOND

/** Runs the real [Trainer] with the harness sources on a virtual clock. */
class TrainerHarnessTest {
    private val dpi = 800.0
    private val cm360 = 45.0
    private val player = SimulatedPlayer()

    private fun config(seed: Long) = TrainerConfig(
        dpi = dpi, cm360 = cm360, profile = checkNotNull(GameProfiles.load().find("valorant")), roundSeconds = ROUND_SECONDS, seed = seed,
    )

    /** With a curve the player expects a rising gain, so the true view differs from the planned one. */
    private fun playerFor(accel: AccelCurve?) = if (accel == null) player else wantsRisingGain

    private val wantsRisingGain = SimulatedPlayer(gainPreference = { v -> 1.0 + 0.5 * (1.0 - kotlin.math.exp(-v / 6.0)) })

    private fun playWithSimulatedSource(seed: Long, kind: ScenarioKind = ScenarioKind.FLICK, accel: AccelCurve? = null): RoundRecording {
        val clock = ManualClock(CLOCK_ORIGIN)
        var finished: RoundRecording? = null
        lateinit var trainer: Trainer
        val source = SimulatedMouseSource(playerFor(accel), dpi, cm360, seed, clock, kind, accel, activeFlash = { trainer.activeFlash }) { trainer.activeTargets }
        trainer = Trainer(config(seed).copy(scenario = kind, accel = accel), source) { finished = it }
        source.start()
        source.beginRound()
        trainer.startRound()
        while (trainer.phase == Phase.RUNNING) {
            clock.advance(NANOS_PER_MS)
            source.tick()
            trainer.update()
            if (trainer.phase == Phase.RUNNING && source.consumeClick()) trainer.fire()
        }
        return assertNotNull(finished)
    }

    private fun replay(recording: RoundRecording, seed: Long): RoundRecording {
        val clock = ManualClock(CLOCK_ORIGIN)
        var finished: RoundRecording? = null
        val source = ReplayMouseSource(recording, clock)
        val trainer = Trainer(config(seed), source) { finished = it }
        source.start()
        source.anchor()
        trainer.startRound()
        while (trainer.phase == Phase.RUNNING) {
            clock.advance(NANOS_PER_MS)
            trainer.update()
            repeat(source.consumeShots()) { if (trainer.phase == Phase.RUNNING) trainer.fire() }
        }
        return assertNotNull(finished)
    }

    @Test
    fun `the trainer driven by the simulated source records exactly what the headless runner records`() {
        for (seed in listOf(1L, 2L, 3L)) {
            assertEquals(HeadlessRound.of(ScenarioKind.FLICK, player, dpi, cm360, seed, ROUND_SECONDS, geometry = config(seed).geometry), playWithSimulatedSource(seed), "seed $seed")
        }
    }

    @Test
    fun `every scenario recorded by the trainer equals the headless runner`() {
        for (kind in ScenarioKind.entries) {
            assertEquals(HeadlessRound.of(kind, player, dpi, cm360, 5, ROUND_SECONDS, geometry = config(5).geometry), playWithSimulatedSource(5, kind), kind.id)
        }
    }

    @Test
    fun `a round with simulated acceleration is recorded alike by the trainer and the headless runner`() {
        val curve = NaturalCurve(0.2, 1.5)
        val headless = HeadlessRound.of(ScenarioKind.FLICK, wantsRisingGain, dpi, cm360, 6, ROUND_SECONDS, accel = curve, geometry = config(6).geometry)
        assertEquals(headless, playWithSimulatedSource(6, accel = curve))
        assertEquals(curve, headless.accel)
    }

    @Test
    fun `replaying a saved log through the trainer reproduces the round`() {
        val original = playWithSimulatedSource(4)
        val file = Files.createTempDirectory("replay").resolve("round.json")
        RoundLogFile.write(original, file)

        val replayed = replay(RoundLogFile.read(file), seed = 4)

        assertTrue(original.targetEvents.any { it is TargetEvent.Hit })
        assertEquals(original.mouseEvents.map { it.dx to it.dy }, replayed.mouseEvents.map { it.dx to it.dy })
        assertEquals(
            original.targetEvents.map { it.timeNanos - original.startNanos },
            replayed.targetEvents.map { it.timeNanos - replayed.startNanos },
        )
        assertEquals(original.targetEvents.map { it::class }, replayed.targetEvents.map { it::class })
    }
}
