package de.yuuto.aimtune.app

import de.yuuto.aimtune.app.accel.PriorCalibration
import de.yuuto.aimtune.core.accel.RawAccelArgs
import de.yuuto.aimtune.core.accel.RawAccelCapMode
import de.yuuto.aimtune.core.accel.RawAccelMode
import de.yuuto.aimtune.core.accel.RawAccelProfile
import de.yuuto.aimtune.core.input.MouseEvent
import de.yuuto.aimtune.core.math.Vec3
import de.yuuto.aimtune.core.recording.RoundRecording
import de.yuuto.aimtune.core.scenario.ScenarioKind
import de.yuuto.aimtune.core.scenario.Target
import de.yuuto.aimtune.core.scenario.TargetEvent
import de.yuuto.aimtune.core.sim.HeadlessRound
import de.yuuto.aimtune.core.sim.SimulatedPlayer
import de.yuuto.aimtune.storage.Database
import de.yuuto.aimtune.storage.RoundLogFile
import de.yuuto.aimtune.storage.RoundRepository
import de.yuuto.aimtune.storage.SessionKind
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoundProcessorTest {
    @TempDir
    lateinit var dir: Path

    private val ms = 1_000_000L

    private fun recording(seed: Long) = RoundRecording(
        "flick", seed, 800.0, 40.0, 0, 5_000 * ms,
        listOf(MouseEvent(100 * ms, 700, 0)),
        listOf(TargetEvent.Spawned(0, Target(0, Vec3(0.0, 0.0, -10.0), 0.25)), TargetEvent.Hit(300 * ms, 0)),
    )

    @Test
    fun `rounds are analysed and stored, with a new session when the game changes`() {
        val file = dir.resolve("a.db")
        val processor = RoundProcessor(Database.open(file))
        processor.submit(recording(1), startedAtMs = 10, blind = false, gameId = "valorant")
        processor.submit(recording(2), startedAtMs = 20, blind = false, gameId = "valorant")
        processor.submit(recording(3), startedAtMs = 30, blind = false, gameId = "cs2")
        processor.close()

        val report = assertIs<RoundReport.Done>(processor.report)
        assertNull(report.saveError)
        assertEquals(1, report.metrics.hits)

        Database.open(file).use { db ->
            val repo = RoundRepository(db)
            assertEquals(listOf("valorant", "cs2"), repo.sessions().map { it.gameId })
            assertEquals(listOf(1L, 2L, 3L), repo.rounds().map { it.seed })
        }
    }

    @Test
    fun `earlier calibration rounds come back as movements for a new acceleration run`() {
        val processor = RoundProcessor(Database.open(dir.resolve("c.db")))
        val kinds = listOf(ScenarioKind.FLICK, ScenarioKind.SWITCHING, ScenarioKind.MICRO)
        kinds.forEachIndexed { i, kind ->
            val round = HeadlessRound.of(kind, SimulatedPlayer(), 800.0, 40.0, 100L + i, 20)
            processor.submit(round, startedAtMs = 10L * (i + 1), blind = false, gameId = "valorant", runKey = 1, kind = SessionKind.ACCEL_AB)
        }
        processor.submit(HeadlessRound.of(ScenarioKind.FLICK, SimulatedPlayer(), 800.0, 40.0, 200L, 20), startedAtMs = 50, blind = false, gameId = "valorant")

        val prior = processor.priorCalibration(800.0, 40.5)
        assertEquals(3, prior.rounds, "only the plain rounds of acceleration sessions count")
        assertTrue(prior.samples.isNotEmpty())
        assertEquals(PriorCalibration.NONE, processor.priorCalibration(800.0, 55.0), "another sensitivity has none")

        val last = checkNotNull(processor.lastCalibration())
        assertEquals(800.0, last.dpi)
        assertEquals(40.0, last.cm360)
        assertEquals(3, last.data.rounds)
        processor.close()
    }

    @Test
    fun `calibration rounds played with the player's own curve come back only for that curve`() {
        val processor = RoundProcessor(Database.open(dir.resolve("own.db")))
        val own = RawAccelProfile("default", RawAccelArgs(RawAccelMode.CLASSIC, gain = true, inputOffset = 10.0, capX = 62.5, capY = 1.6, capMode = RawAccelCapMode.IN_OUT), 600.0, 800.0)
        val other = RawAccelProfile("default", own.args.copy(capY = 1.8), 600.0, 800.0)
        val kinds = listOf(ScenarioKind.FLICK, ScenarioKind.SWITCHING, ScenarioKind.MICRO)
        kinds.forEachIndexed { i, kind ->
            val ownRound = HeadlessRound.of(kind, SimulatedPlayer(), 800.0, 40.0, 100L + i, 20, accel = own)
            processor.submit(ownRound, startedAtMs = 10L * (i + 1), blind = false, gameId = "valorant", runKey = 1, kind = SessionKind.ACCEL_AB)
        }
        processor.submit(HeadlessRound.of(ScenarioKind.FLICK, SimulatedPlayer(), 800.0, 40.0, 200L, 20, accel = other), startedAtMs = 50, blind = false, gameId = "valorant", runKey = 2, kind = SessionKind.ACCEL_AB)
        processor.submit(HeadlessRound.of(ScenarioKind.FLICK, SimulatedPlayer(), 800.0, 40.0, 201L, 20), startedAtMs = 60, blind = false, gameId = "valorant", runKey = 3, kind = SessionKind.ACCEL_AB)

        assertEquals(3, processor.priorCalibration(800.0, 40.5, own).rounds, "exactly the rounds played with this curve")
        assertEquals(1, processor.priorCalibration(800.0, 40.5).rounds, "plain asks for the plain round only")
        assertEquals(PriorCalibration.NONE, processor.priorCalibration(800.0, 40.5, RawAccelProfile("x", own.args.copy(acceleration = 0.01), 600.0, 800.0)))
        processor.close()
    }

    @Test
    fun `without stored acceleration rounds there is no last calibration`() {
        val processor = RoundProcessor(Database.open(dir.resolve("d.db")))
        processor.submit(recording(1), startedAtMs = 10, blind = false, gameId = "valorant")
        assertNull(processor.lastCalibration())
        assertNull(RoundProcessor(null).lastCalibration())
        processor.close()
    }

    @Test
    fun `each blind test run gets its own session`() {
        val file = dir.resolve("b.db")
        val processor = RoundProcessor(Database.open(file))
        processor.submit(recording(1), startedAtMs = 10, blind = true, gameId = "valorant", runKey = 100)
        processor.submit(recording(2), startedAtMs = 20, blind = true, gameId = "valorant", runKey = 100)
        processor.submit(recording(3), startedAtMs = 30, blind = true, gameId = "valorant", runKey = 200)
        processor.close()
        Database.open(file).use { db ->
            val repo = RoundRepository(db)
            assertEquals(2, repo.sessions().size)
            assertEquals(listOf(2, 1), repo.sessions().map { s -> repo.rounds(s.id).size })
        }
    }

    @Test
    fun `rounds are exported as raw event logs when an export folder is set`() {
        val exports = dir.resolve("logs")
        val processor = RoundProcessor(database = null, exportDir = exports)
        processor.submit(recording(7), startedAtMs = 42, blind = false, gameId = null)
        processor.close()

        val back = RoundLogFile.read(exports.resolve("round-42-seed7.json"))
        assertEquals(7L, back.seed)
        assertEquals(1, back.mouseEvents.size)
    }

    @Test
    fun `without a database the round is still analysed`() {
        val processor = RoundProcessor(database = null)
        processor.submit(recording(1), startedAtMs = 10, blind = false, gameId = null)
        processor.close()
        val report = assertIs<RoundReport.Done>(processor.report)
        assertTrue(report.saveError != null)
        assertEquals(1, report.metrics.hits)
    }
}
