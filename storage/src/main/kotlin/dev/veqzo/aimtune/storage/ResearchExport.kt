package dev.veqzo.aimtune.storage

import dev.veqzo.aimtune.core.recording.RoundRecording
import dev.veqzo.aimtune.core.scenario.TargetEvent
import kotlinx.serialization.json.JsonPrimitive
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.GZIPOutputStream

/** What a research export contains; [rounds] counts only rounds that could be read. */
data class ResearchExportResult(val file: Path, val sessions: Int, val rounds: Int, val bytes: Long)

/**
 * Writes all stored sessions as one gzip-compressed JSON file that a person can send to a researcher
 * (docs/history/ASSESSMENT.md D4: the method has never been run on other people's recordings). It holds, per
 * round, the raw mouse counts with their times, the target spawns, hits and misses, the sensitivity
 * and DPI, the game id and the metrics. It holds no name, no machine or user name, no clock times
 * (sessions only get a day index relative to the first one, rounds an offset within their session)
 * and no file paths. Written as a stream, since a round has tens of thousands of mouse reports.
 */
object ResearchExport {
    const val FORMAT = "aimtune-research"
    const val FORMAT_VERSION = 1
    private const val MILLIS_PER_DAY = 86_400_000.0
    private const val MILLIS_PER_SECOND = 1000.0
    private val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    /** Writes `aimtune-research-<time>.json.gz` into [dir] and returns what was written. */
    fun write(dir: Path, repository: RoundRepository, appVersion: String, now: LocalDateTime = LocalDateTime.now()): ResearchExportResult {
        Files.createDirectories(dir)
        val file = dir.resolve("aimtune-research-${stamp.format(now)}.json.gz")
        var counts = 0 to 0
        BufferedWriter(OutputStreamWriter(GZIPOutputStream(Files.newOutputStream(file)), StandardCharsets.UTF_8)).use { out ->
            counts = writeTo(out, repository, appVersion)
        }
        return ResearchExportResult(file, counts.first, counts.second, Files.size(file))
    }

    /** The same JSON as text; returns the numbers of sessions and rounds written. */
    fun writeTo(out: Appendable, repository: RoundRepository, appVersion: String): Pair<Int, Int> {
        val sessions = repository.sessions()
        val firstStart = sessions.minOfOrNull { it.startedAtMs } ?: 0L
        out.append("{\"format\":").append(quote(FORMAT))
        out.append(",\"formatVersion\":").append(FORMAT_VERSION.toString())
        out.append(",\"appVersion\":").append(quote(appVersion))
        out.append(",\"metricsVersion\":").append(METRICS_VERSION.toString())
        out.append(",\"notes\":").append(quote(NOTES))
        out.append(",\"sessions\":[")
        var sessionCount = 0
        var roundCount = 0
        for (session in sessions) {
            val rounds = repository.rounds(session.id)
            if (rounds.isEmpty()) continue
            if (sessionCount > 0) out.append(',')
            out.append("{\"index\":").append(sessionCount.toString())
            out.append(",\"kind\":").append(quote(session.kind.id))
            out.append(",\"gameId\":").append(session.gameId?.let(::quote) ?: "null")
            out.append(",\"dpi\":").append(number(session.dpi))
            out.append(",\"dayIndex\":").append(Math.floor((session.startedAtMs - firstStart) / MILLIS_PER_DAY).toLong().toString())
            out.append(",\"rounds\":[")
            var written = 0
            for (row in rounds) {
                val recording = repository.loadRecording(row.id) ?: continue
                if (written > 0) out.append(',')
                writeRound(out, recording, row, session.startedAtMs)
                written++
            }
            out.append("]}")
            roundCount += written
            sessionCount++
        }
        out.append("]}")
        return sessionCount to roundCount
    }

    private fun writeRound(out: Appendable, r: RoundRecording, row: RoundRow, sessionStartMs: Long) {
        val origin = r.startNanos
        out.append("{\"scenario\":").append(quote(r.scenarioId))
        out.append(",\"seed\":").append(r.seed.toString())
        out.append(",\"cm360\":").append(number(r.cm360))
        out.append(",\"dpi\":").append(number(r.dpi))
        out.append(",\"blind\":").append(row.blind.toString())
        out.append(",\"accel\":").append(row.accel?.let(::quote) ?: "null")
        out.append(",\"startOffsetSeconds\":").append(number((row.startedAtMs - sessionStartMs) / MILLIS_PER_SECOND))
        out.append(",\"durationNanos\":").append((r.endNanos - origin).toString())

        out.append(",\"mouse\":{\"dtNanos\":[")
        var previous = origin
        r.mouseEvents.forEachIndexed { i, e ->
            if (i > 0) out.append(',')
            out.append((e.timeNanos - previous).toString())
            previous = e.timeNanos
        }
        out.append("],\"dx\":[")
        r.mouseEvents.forEachIndexed { i, e -> (if (i > 0) out.append(',') else out).append(e.dx.toString()) }
        out.append("],\"dy\":[")
        r.mouseEvents.forEachIndexed { i, e -> (if (i > 0) out.append(',') else out).append(e.dy.toString()) }
        out.append("]}")

        out.append(",\"targetEvents\":[")
        r.targetEvents.forEachIndexed { i, event ->
            if (i > 0) out.append(',')
            writeTargetEvent(out, event, origin)
        }
        out.append("],\"metrics\":{")
        row.metrics.entries.sortedBy { it.key }.forEachIndexed { i, (name, value) ->
            if (i > 0) out.append(',')
            out.append(quote(name)).append(':').append(number(value))
        }
        out.append("}}")
    }

    private fun writeTargetEvent(out: Appendable, event: TargetEvent, origin: Long) {
        val t = event.timeNanos - origin
        when (event) {
            is TargetEvent.Spawned -> out.append("{\"type\":\"spawn\",\"tNanos\":$t,\"id\":${event.target.id},\"x\":${number(event.target.center.x)},\"y\":${number(event.target.center.y)},\"z\":${number(event.target.center.z)},\"radius\":${number(event.target.radius)}}")
            is TargetEvent.Hit -> out.append("{\"type\":\"hit\",\"tNanos\":$t,\"id\":${event.targetId}}")
            is TargetEvent.Miss -> out.append("{\"type\":\"miss\",\"tNanos\":$t}")
            is TargetEvent.BodyHit -> out.append("{\"type\":\"bodyHit\",\"tNanos\":$t,\"id\":${event.targetId}}")
            is TargetEvent.Despawned -> out.append("{\"type\":\"despawn\",\"tNanos\":$t,\"id\":${event.targetId}}")
            is TargetEvent.FlashCue -> out.append("{\"type\":\"flashCue\",\"tNanos\":$t,\"id\":${event.flashId},\"yaw\":${number(event.yawDegrees)},\"pitch\":${number(event.pitchDegrees)},\"popTNanos\":${event.popNanos - origin},\"safeAngle\":${number(event.safeAngleDegrees)}}")
            is TargetEvent.FlashPop -> out.append("{\"type\":\"flashPop\",\"tNanos\":$t,\"id\":${event.flashId},\"dodged\":${event.dodged},\"blindUntilTNanos\":${if (event.blindUntilNanos > 0L) event.blindUntilNanos - origin else 0L}}")
            is TargetEvent.Waypoint -> out.append("{\"type\":\"waypoint\",\"tNanos\":$t,\"id\":${event.targetId},\"x\":${number(event.center.x)},\"y\":${number(event.center.y)},\"z\":${number(event.center.z)}}")
        }
    }

    private fun quote(text: String): String = JsonPrimitive(text).toString()

    /** JSON has no NaN or infinity. */
    private fun number(value: Double): String = if (value.isFinite()) value.toString() else "null"

    private const val NOTES =
        "Times are nanoseconds relative to the start of the round; mouse dtNanos is the time since the previous report " +
            "(the first since the round start), dx and dy are raw counts of the mouse. Sensitivity is cm per 360 degrees at the " +
            "stated dpi. Target positions are in metres in the camera frame at the start of the round: x right, y up, -z forward. " +
            "A round with accel not null was played with a simulated Raw Accel curve."
}
