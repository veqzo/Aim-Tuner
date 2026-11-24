package dev.veqzo.aimtune.app.harness

import dev.veqzo.aimtune.app.Phase
import dev.veqzo.aimtune.app.Trainer
import dev.veqzo.aimtune.app.TrainerConfig
import dev.veqzo.aimtune.core.metrics.computeRoundMetrics
import dev.veqzo.aimtune.core.recording.RoundRecording
import dev.veqzo.aimtune.core.sim.ManualClock
import dev.veqzo.aimtune.games.GameProfiles
import dev.veqzo.aimtune.input.ReplayMouseSource
import dev.veqzo.aimtune.storage.RoundLogFile
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Regression fixtures: raw event logs in `src/test/resources/fixtures`. Real sessions are added by
 * running the app with `--record <dir>` and copying a log here; the replay test below then covers
 * it automatically, while the metric values of a new fixture are pinned in [pinnedMetrics].
 */
class RecordedFixtureTest {
    private val fixtures: List<Path> = RecordedFixtureTest::class.java.getResource("/fixtures")
        .let { checkNotNull(it) { "fixtures folder missing" } }
        .let { Path.of(it.toURI()) }
        .toFile().listFiles { f -> f.extension == "json" }.orEmpty().sortedBy { it.name }.map { it.toPath() }

    /** A trainer replaying [log] must reproduce its target events: the scenario, hit test and view math are unchanged. */
    private fun replay(log: RoundRecording): RoundRecording {
        val clock = ManualClock(1_000_000_000L)
        val config = TrainerConfig(
            dpi = log.dpi, cm360 = log.cm360, profile = checkNotNull(GameProfiles.load().find("valorant")),
            roundSeconds = ((log.endNanos - log.startNanos) / 1_000_000_000L).toInt(), seed = log.seed,
            // The fixture was recorded with the fixed geometry of M2-M8.
            geometry = null,
        )
        var finished: RoundRecording? = null
        val source = ReplayMouseSource(log, clock)
        val trainer = Trainer(config, source) { finished = it }
        source.start()
        source.anchor()
        trainer.startRound()
        while (trainer.phase == Phase.RUNNING) {
            clock.advance(1_000_000L)
            trainer.update()
            repeat(source.consumeShots()) { if (trainer.phase == Phase.RUNNING) trainer.fire() }
        }
        return assertNotNull(finished)
    }

    @Test
    fun `there is at least one fixture`() {
        assertEquals(true, fixtures.isNotEmpty())
    }

    @Test
    fun `replaying every fixture reproduces its recorded target events`() {
        for (file in fixtures) {
            val log = RoundLogFile.read(file)
            val replayed = replay(log)
            assertEquals(
                log.targetEvents.map { it::class to it.timeNanos },
                replayed.targetEvents.map { it::class to it.timeNanos - replayed.startNanos },
                file.fileName.toString(),
            )
        }
    }

    /** Pinned analysis results; a change here means the metrics definition changed on purpose (bump `metrics_version`). */
    private val pinnedMetrics = mapOf(
        "sim-flick-seed21.json" to Pinned(hits = 13, shots = 13, meanTimeToHitMs = 1511.6, errorSpread = 0.0501, meanCorrections = 1.69, pathEfficiency = 0.936),
    )

    private data class Pinned(val hits: Int, val shots: Int, val meanTimeToHitMs: Double, val errorSpread: Double, val meanCorrections: Double, val pathEfficiency: Double)

    @Test
    fun `fixture metrics are unchanged`() {
        for (file in fixtures) {
            val name = file.fileName.toString()
            val pinned = assertNotNull(pinnedMetrics[name], "pin the metrics of new fixture $name")
            val m = computeRoundMetrics(RoundLogFile.read(file))
            assertEquals(pinned.hits, m.hits, name)
            assertEquals(pinned.shots, m.shots, name)
            assertEquals(pinned.meanTimeToHitMs, assertNotNull(m.meanTimeToHitMs), 0.1, name)
            assertEquals(pinned.errorSpread, assertNotNull(m.errorSpread), 0.0005, name)
            assertEquals(pinned.meanCorrections, assertNotNull(m.meanCorrectiveSubmovements), 0.005, name)
            assertEquals(pinned.pathEfficiency, assertNotNull(m.meanPathEfficiency), 0.0005, name)
        }
    }
}
