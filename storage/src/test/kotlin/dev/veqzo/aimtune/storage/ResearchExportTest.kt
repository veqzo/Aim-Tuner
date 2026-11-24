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
import java.nio.file.Files
import java.nio.file.Path
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
        scenarioId = "flick", seed = SEED, dpi = 800.0, cm360 = 40.0, startNanos = origin, endNanos = origin + 45_000 * ms,
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
            val empty = ResearchExport.write(dir.resolve("out"), repo, "0.1.0")
            assertEquals(0 to 0, empty.sessions to empty.rounds)
            assertEquals(0, Json.parseToJsonElement(unzip(empty.file)).jsonObject["sessions"]!!.jsonArray.size)
            fill(repo)
            val result = ResearchExport.write(dir.resolve("out"), repo, "0.1.0")
            assertEquals(2, result.rounds)
            assertTrue(result.bytes > 0)
            // The second export replaced the first under the same name and left no partial file behind.
            assertEquals(empty.file, result.file)
            assertEquals(listOf(ResearchExport.FILE_NAME), Files.list(dir.resolve("out")).use { s -> s.map { it.fileName.toString() }.toList() })
            val sessions: JsonArray = Json.parseToJsonElement(unzip(result.file)).jsonObject["sessions"]!!.jsonArray
            assertEquals(2, sessions.size)
        }
    }

    @Test
    fun `the export leaves out seeds, paths and absolute times`() {
        // A folder name that stands for a user name, to show that no path reaches the file.
        val home = dir.resolve("PrivateUserName").also { Files.createDirectories(it) }
        Database.open(home.resolve("aimtune.db")).use { db ->
            val repo = RoundRepository(db)
            fill(repo)
            val result = ResearchExport.write(home.resolve("exports"), repo, "0.1.0")
            val text = unzip(result.file)
            val root = Json.parseToJsonElement(text).jsonObject
            assertEquals(ResearchExport.FORMAT_VERSION, root["formatVersion"]!!.jsonPrimitive.int)
            val round = root["sessions"]!!.jsonArray[0].jsonObject["rounds"]!!.jsonArray[0].jsonObject
            assertFalse("seed" in round)
            assertFalse(text.contains(SEED.toString()))
            assertFalse(text.contains("PrivateUserName"))
            assertFalse(text.contains(":\\") || text.contains("/Users/") || text.contains("/home/"))
            assertFalse(text.contains("1700000"))
            assertFalse(Regex("""\d{4}-\d{2}-\d{2}""").containsMatchIn(text), "a calendar date")
            System.getProperty("user.name")?.takeIf { it.length >= 3 }?.let { assertFalse(text.contains(it), "the user name") }
            assertEquals(ResearchExport.FILE_NAME, result.file.fileName.toString())
        }
    }

    @Test
    fun `the gzip header holds no time and no file name`() {
        Database.open(dir.resolve("c.db")).use { db ->
            val repo = RoundRepository(db)
            fill(repo)
            val bytes = Files.readAllBytes(ResearchExport.write(dir.resolve("out"), repo, "0.1.0").file)
            // RFC 1952: byte 3 holds the flags (FNAME is 0x08, FCOMMENT 0x10), bytes 4-7 the modification time.
            assertEquals(0, bytes[3].toInt() and 0x18, "file name or comment in the gzip header")
            assertEquals(listOf<Byte>(0, 0, 0, 0), bytes.slice(4..7), "modification time in the gzip header")
        }
    }

    private fun unzip(file: Path) = GZIPInputStream(Files.newInputStream(file)).use { String(it.readBytes(), Charsets.UTF_8) }

    private companion object {
        /** A seed that would stand out in the text if it were written. */
        const val SEED = 918_273_645_501L
    }
}
