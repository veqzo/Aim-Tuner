package dev.veqzo.aimtune.storage

import dev.veqzo.aimtune.core.accel.AccelSpec
import dev.veqzo.aimtune.core.math.Vec3
import dev.veqzo.aimtune.core.recording.RoundRecording
import dev.veqzo.aimtune.core.scenario.Target
import dev.veqzo.aimtune.core.scenario.TargetEvent
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

class RoundLogException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * A single round as a self-contained JSON file ("raw event log"): the settings and seed, every mouse
 * report and every target event, with all times relative to the round start. Real sessions saved
 * this way become regression fixtures; `ReplayMouseSource` plays them back. Separate from the
 * database so a fixture can be committed or sent in a bug report.
 */
object RoundLogFile {
    private const val FORMAT_VERSION = 1
    private val json = Json { ignoreUnknownKeys = true }

    fun write(recording: RoundRecording, file: Path) {
        file.toAbsolutePath().parent?.let(Files::createDirectories)
        Files.writeString(file, encode(recording))
    }

    /** @throws RoundLogException if the file is missing, unreadable or not a round log. */
    fun read(file: Path): RoundRecording = try {
        decode(Files.readString(file))
    } catch (e: java.io.IOException) {
        throw RoundLogException("round log $file could not be read: ${e.message}", e)
    }

    fun encode(recording: RoundRecording): String {
        val origin = recording.startNanos
        val dto = RoundLogDto(
            version = FORMAT_VERSION,
            scenario = recording.scenarioId,
            seed = recording.seed,
            dpi = recording.dpi,
            cm360 = recording.cm360,
            durationNanos = recording.endNanos - origin,
            mouseEventCount = recording.mouseEvents.size,
            mouseEvents = Base64.getEncoder().encodeToString(MouseEventCodec.encode(recording.mouseEvents, origin)),
            targetEvents = recording.targetEvents.map { it.toDto(origin) },
            accel = recording.accel?.let(AccelSpec::encodeModel),
        )
        return json.encodeToString(RoundLogDto.serializer(), dto)
    }

    /** The recording of a log always starts at time 0. */
    fun decode(text: String): RoundRecording {
        try {
            val dto = json.decodeFromString<RoundLogDto>(text)
            if (dto.version != FORMAT_VERSION) throw RoundLogException("unsupported round log version ${dto.version}")
            return RoundRecording(
                scenarioId = dto.scenario,
                seed = dto.seed,
                dpi = dto.dpi,
                cm360 = dto.cm360,
                startNanos = 0L,
                endNanos = dto.durationNanos,
                mouseEvents = MouseEventCodec.decode(Base64.getDecoder().decode(dto.mouseEvents), dto.mouseEventCount, 0L),
                targetEvents = dto.targetEvents.map { it.toEvent() },
                accel = dto.accel?.let(AccelSpec::decodeModel),
            )
        } catch (e: SerializationException) {
            throw RoundLogException("not a round log: ${e.message}", e)
        } catch (e: IllegalArgumentException) {
            throw RoundLogException("round log is damaged: ${e.message}", e)
        }
    }
}

@Serializable
private data class RoundLogDto(
    val version: Int,
    val scenario: String,
    val seed: Long,
    val dpi: Double,
    val cm360: Double,
    val durationNanos: Long,
    val mouseEventCount: Int,
    /** [MouseEventCodec] blob, Base64. */
    val mouseEvents: String,
    val targetEvents: List<TargetEventDto>,
    /** [AccelSpec] text of the curve the round was played with; absent for none. */
    val accel: String? = null,
)

@Serializable
private data class TargetEventDto(
    val type: String,
    val t: Long,
    val id: Int? = null,
    val x: Double? = null,
    val y: Double? = null,
    val z: Double? = null,
    val radius: Double? = null,
)

private const val SPAWNED = "spawned"
private const val HIT = "hit"
private const val MISS = "miss"
private const val WAYPOINT = "waypoint"
private const val BODY_HIT = "body_hit"
private const val DESPAWNED = "despawned"
private const val FLASH_CUE = "flash_cue"
private const val FLASH_POP = "flash_pop"

private fun TargetEvent.toDto(origin: Long): TargetEventDto = when (this) {
    is TargetEvent.Spawned -> TargetEventDto(SPAWNED, timeNanos - origin, target.id, target.center.x, target.center.y, target.center.z, target.radius)
    is TargetEvent.Hit -> TargetEventDto(HIT, timeNanos - origin, targetId)
    is TargetEvent.Miss -> TargetEventDto(MISS, timeNanos - origin)
    is TargetEvent.BodyHit -> TargetEventDto(BODY_HIT, timeNanos - origin, targetId)
    is TargetEvent.Despawned -> TargetEventDto(DESPAWNED, timeNanos - origin, targetId)
    is TargetEvent.Waypoint -> TargetEventDto(WAYPOINT, timeNanos - origin, targetId, center.x, center.y, center.z)
    // A flash reuses the numeric fields: id = flash id, x/y = yaw/pitch, z = pop time, radius = safe angle.
    is TargetEvent.FlashCue -> TargetEventDto(FLASH_CUE, timeNanos - origin, flashId, yawDegrees, pitchDegrees, (popNanos - origin).toDouble(), safeAngleDegrees)
    // x = 1 when dodged, y = end of the white-out (0 for none).
    is TargetEvent.FlashPop -> TargetEventDto(FLASH_POP, timeNanos - origin, flashId, if (dodged) 1.0 else 0.0, if (blindUntilNanos > 0L) (blindUntilNanos - origin).toDouble() else 0.0)
}

private fun TargetEventDto.toEvent(): TargetEvent = when (type) {
    SPAWNED -> TargetEvent.Spawned(t, Target(requireField(id, "id"), Vec3(requireField(x, "x"), requireField(y, "y"), requireField(z, "z")), requireField(radius, "radius")))
    HIT -> TargetEvent.Hit(t, requireField(id, "id"))
    MISS -> TargetEvent.Miss(t)
    BODY_HIT -> TargetEvent.BodyHit(t, requireField(id, "id"))
    DESPAWNED -> TargetEvent.Despawned(t, requireField(id, "id"))
    WAYPOINT -> TargetEvent.Waypoint(t, requireField(id, "id"), Vec3(requireField(x, "x"), requireField(y, "y"), requireField(z, "z")))
    FLASH_CUE -> TargetEvent.FlashCue(t, requireField(id, "id"), requireField(x, "x"), requireField(y, "y"), requireField(z, "z").toLong(), requireField(radius, "radius"))
    FLASH_POP -> TargetEvent.FlashPop(t, requireField(id, "id"), requireField(x, "x") > 0.5, requireField(y, "y").toLong())
    else -> throw IllegalArgumentException("unknown target event type '$type'")
}

private fun <T : Any> requireField(value: T?, name: String): T = requireNotNull(value) { "target event is missing '$name'" }
