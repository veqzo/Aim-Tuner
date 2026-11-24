package dev.veqzo.aimtune.storage.review

import dev.veqzo.aimtune.storage.Database
import dev.veqzo.aimtune.storage.ResearchExport
import dev.veqzo.aimtune.storage.RoundRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertEquals

/** Exports a copy of a real database (AIMTUNE_REAL_DB) to check size and speed at real scale. */
@EnabledIfEnvironmentVariable(named = "AIMTUNE_REAL_DB", matches = ".+")
class RealDataExportTest {
    @Test
    fun exportsTheRealDatabase() {
        val out = Files.createTempDirectory("aimtune-export")
        Database.open(Path.of(System.getenv("AIMTUNE_REAL_DB"))).use { db ->
            val started = System.nanoTime()
            val result = ResearchExport.write(out, RoundRepository(db), "test")
            val text = GZIPInputStream(Files.newInputStream(result.file)).use { String(it.readBytes(), Charsets.UTF_8) }
            val parsed = Json.parseToJsonElement(text).jsonObject["sessions"]!!.jsonArray
            println("export: ${result.sessions} sessions, ${result.rounds} rounds, ${result.bytes / 1024} KB gz, ${text.length / 1024} KB json, ${(System.nanoTime() - started) / 1_000_000} ms")
            assertEquals(result.sessions, parsed.size)
        }
    }
}
