package dev.veqzo.aimtune.storage

import dev.veqzo.aimtune.core.input.MouseEvent
import dev.veqzo.aimtune.core.math.Vec3
import dev.veqzo.aimtune.core.metrics.computeRoundMetrics
import dev.veqzo.aimtune.core.recording.RoundRecording
import dev.veqzo.aimtune.core.scenario.Target
import dev.veqzo.aimtune.core.scenario.TargetEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.LocalDateTime
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ResearchExportTest {
    @TempDir
    lateinit var dir: Path

    private val ms = 1_000_000L
    private val day = 86_400_000L

    private fun recording(origin: Long) = RoundRecording(
        scenarioId = "flick", seed = 42, dpi = 800.0, cm360 = 40.0, startNanos = origin, endNanos = origin + 45_000 * ms,
        mouseEvents = listOf(MouseEvent(origin + 10 * ms, 120, -4), MouseEvent(origin + 11 * ms, 60, 0), MouseEvent(origin + 400 * ms, -2, 3)),
        targetEvents = listOf(
            TargetEvent.Spawned(origin, Target(0, Vec3(5.0, 1.0, -9.0), 0.25)),
            TargetEvent.Miss(origin + 100 * ms),
            TargetEvent.Hit(origin + 300 * ms, 0),
        ),
    )

    private fun fill(repo: RoundRepository) {
        val first = repo.createSession(1_700_000_000_000, "valorant", 800.0, SessionKind.SENSITIVITY)
        repo.saveRound(first, recording(9_000_000_000), startedAtMs = 1_700_000_005_000, blind = true, metrics = computeRoundMetrics(recording(9_000_000_000)))
        val second = repo.createSession(1_700_000_000_000 + 2 * day + 3_600_000, "valorant", 800.0, SessionKind.SENSITIVITY)
        repo.saveRound(second, recording(5_000_000_000), startedAtMs = 1_700_000_000_000 + 2 * day + 3_600_000 + 60_000, blind = true, metrics = computeRoundMetrics(recording(5_000_000_000)))
    }

    @Test
    fun `the export holds every round with its raw events and no clock times`() {
        Database.open(dir.resolve("a.db")).use { db ->
            val repo = RoundRepository(db)
            fill(repo)
            val text = StringBuilder()
            val (sessions, rounds) = ResearchExport.writeTo(text, repo, "0.1.0")
            assertEquals(2 to 2, sessions to rounds)

            val root = Json.parseToJsonElement(text.toString()).jsonObject
            assertEquals(ResearchExport.FORMAT, root["format"]!!.jsonPrimitive.content)
            val parsed = root["sessions"]!!.jsonArray
            assertEquals(listOf(0L, 2L), parsed.map { it.jsonObject["dayIndex"]!!.jsonPrimitive.long })

            val round = parsed[0].jsonObject["rounds"]!!.jsonArray[0].jsonObject
            val mouse = round["mouse"]!!.jsonObject
            assertEquals(listOf(10 * ms, ms, 389 * ms), mouse["dtNanos"]!!.jsonArray.map { it.jsonPrimitive.long })
            assertEquals(listOf(120, 60, -2), mouse["dx"]!!.jsonArray.map { it.jsonPrimitive.int })
            assertEquals(listOf("spawn", "miss", "hit"), round["targetEvents"]!!.jsonArray.map { it.jsonObject["type"]!!.jsonPrimitive.content })
            assertEquals(5.0, round["startOffsetSeconds"]!!.jsonPrimitive.content.toDouble())
            assertTrue((round["metrics"] as JsonObject).isNotEmpty())

            // No absolute times or other identifiers.
            assertFalse(text.contains("startedAt"))
            assertFalse(text.contains("1700000"))
        }
    }

    @Test
    fun `write produces a readable gzip file and an empty database gives an empty export`() {
        Database.open(dir.resolve("b.db")).use { db ->
            val repo = RoundRepository(db)
            val empty = ResearchExport.write(dir.resolve("out"), repo, "0.1.0", LocalDateTime.of(2026, 1, 2, 3, 4, 5))
            assertEquals(0, empty.rounds)
            assertTrue(empty.file.fileName.toString().startsWith("aimtune-research-20260102-030405"))
            fill(repo)
            val result = ResearchExport.write(dir.resolve("out"), repo, "0.1.0", LocalDateTime.of(2026, 1, 2, 3, 4, 6))
            assertEquals(2, result.rounds)
            assertTrue(result.bytes > 0)
            val text = GZIPInputStream(java.nio.file.Files.newInputStream(result.file)).use { String(it.readBytes(), Charsets.UTF_8) }
            val sessions: JsonArray = Json.parseToJsonElement(text).jsonObject["sessions"]!!.jsonArray
            assertEquals(2, sessions.size)
        }
    }
}
