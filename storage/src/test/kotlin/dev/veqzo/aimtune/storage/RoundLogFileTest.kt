package dev.veqzo.aimtune.storage

import dev.veqzo.aimtune.core.sim.HeadlessRound
import dev.veqzo.aimtune.core.sim.SimulatedPlayer
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RoundLogFileTest {
    private val recording = HeadlessRound.flick(SimulatedPlayer(), 800.0, 45.0, seed = 11, roundSeconds = 10)

    @Test
    fun `a round survives a trip through a file with times relative to its start`() {
        val file = Files.createTempDirectory("roundlog").resolve("nested/round.json")
        RoundLogFile.write(recording, file)
        val back = RoundLogFile.read(file)

        assertEquals(0L, back.startNanos)
        assertEquals(recording.endNanos - recording.startNanos, back.endNanos)
        assertEquals(recording.seed, back.seed)
        assertEquals(recording.dpi, back.dpi)
        assertEquals(recording.cm360, back.cm360)
        assertEquals(
            recording.mouseEvents.map { Triple(it.timeNanos - recording.startNanos, it.dx, it.dy) },
            back.mouseEvents.map { Triple(it.timeNanos, it.dx, it.dy) },
        )
        assertEquals(recording.targetEvents.map { it.timeNanos - recording.startNanos }, back.targetEvents.map { it.timeNanos })
        assertEquals(recording.targetEvents.map { it::class }, back.targetEvents.map { it::class })
    }

    @Test
    fun `the acceleration of a round survives the log and old logs without it still load`() {
        val curve = dev.veqzo.aimtune.core.accel.ClassicCurve(0.05, cap = 1.8)
        val accelerated = recording.copy(accel = curve)
        assertEquals(curve, RoundLogFile.decode(RoundLogFile.encode(accelerated)).accel)
        assertEquals(null, RoundLogFile.decode(RoundLogFile.encode(recording)).accel)
        assertTrue(!RoundLogFile.encode(recording).contains("accel"), "a round without acceleration writes no accel field")
    }

    @Test
    fun `decoding then encoding again gives the same text`() {
        val text = RoundLogFile.encode(recording)
        assertEquals(text, RoundLogFile.encode(RoundLogFile.decode(text)))
    }

    @Test
    fun `garbage, unknown versions and missing files are reported`() {
        assertFailsWith<RoundLogException> { RoundLogFile.decode("not json") }
        assertFailsWith<RoundLogException> {
            RoundLogFile.decode(RoundLogFile.encode(recording).replace("\"version\":1", "\"version\":99"))
        }
        assertFailsWith<RoundLogException> { RoundLogFile.read(Files.createTempDirectory("roundlog").resolve("missing.json")) }
    }
}
