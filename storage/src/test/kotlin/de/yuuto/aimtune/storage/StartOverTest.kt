package de.yuuto.aimtune.storage

import de.yuuto.aimtune.core.input.MouseEvent
import de.yuuto.aimtune.core.math.Vec3
import de.yuuto.aimtune.core.metrics.computeRoundMetrics
import de.yuuto.aimtune.core.recording.RoundRecording
import de.yuuto.aimtune.core.scenario.Target
import de.yuuto.aimtune.core.scenario.TargetEvent
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class StartOverTest {
    @TempDir
    lateinit var dir: Path

    private val ms = 1_000_000L

    private fun recording() = RoundRecording(
        scenarioId = "flick", seed = 1, dpi = 800.0, cm360 = 40.0, startNanos = 0, endNanos = 45_000 * ms,
        mouseEvents = listOf(MouseEvent(10 * ms, 120, -4)),
        targetEvents = listOf(TargetEvent.Spawned(0, Target(0, Vec3(5.0, 1.0, -9.0), 0.25)), TargetEvent.Hit(300 * ms, 0)),
    )

    @Test
    fun `start over backs up every round, clears the database and keeps the settings`() {
        val paths = AppPaths(dir)
        Files.writeString(paths.settingsFile, """{"dpi":800}""")
        Database.open(paths.databaseFile).use { db ->
            val repo = RoundRepository(db)
            val first = repo.createSession(1, "valorant", 800.0, SessionKind.SENSITIVITY)
            val second = repo.createSession(2, "valorant", 800.0, SessionKind.ACCEL_REFINE)
            listOf(first, first, second).forEach { repo.saveRound(it, recording(), 1, blind = true, computeRoundMetrics(recording())) }
            repo.setFeel(repo.rounds().last().id, 1)

            val result = repo.startOver(db, paths.backupDir, nowMs = 0, zone = ZoneOffset.UTC)

            assertEquals(StartOverResult(paths.backupDir.resolve("aimtune-19700101-000000.db"), sessions = 2, rounds = 3), result)
            assertTrue(repo.sessions().isEmpty() && repo.rounds().isEmpty())
            Database.open(result.backup).use { backup ->
                val copy = RoundRepository(backup)
                assertEquals(3, copy.rounds().size)
                assertEquals(1, copy.feelOf(copy.rounds().last().id))
                assertEquals(2, copy.loadRecording(copy.rounds().first().id)?.targetEvents?.size)
            }
            // The cleared database still takes new rounds.
            repo.saveRound(repo.createSession(3, "valorant", 800.0, SessionKind.SENSITIVITY), recording(), 3, blind = true, computeRoundMetrics(recording()))
            assertEquals(1, repo.rounds().size)
        }
        assertEquals("""{"dpi":800}""", Files.readString(paths.settingsFile))
    }

    @Test
    fun `nothing is deleted when the backup cannot be written`() {
        Database.open(dir.resolve("a.db")).use { db ->
            val repo = RoundRepository(db)
            repo.saveRound(repo.createSession(1, "valorant", 800.0, SessionKind.SENSITIVITY), recording(), 1, blind = true, computeRoundMetrics(recording()))
            val notADirectory = Files.writeString(dir.resolve("backups"), "a file where the folder should be")
            assertFails { repo.startOver(db, notADirectory, nowMs = 0) }
            assertEquals(1, repo.rounds().size)
        }
    }

    @Test
    fun `a second start over in the same second gets its own backup file`() {
        Database.open(dir.resolve("a.db")).use { db ->
            val repo = RoundRepository(db)
            val a = repo.startOver(db, dir.resolve("backups"), nowMs = 0, zone = ZoneOffset.UTC)
            val b = repo.startOver(db, dir.resolve("backups"), nowMs = 0, zone = ZoneOffset.UTC)
            assertNotEquals(a.backup, b.backup)
            assertTrue(Files.exists(a.backup) && Files.exists(b.backup))
        }
    }
}
