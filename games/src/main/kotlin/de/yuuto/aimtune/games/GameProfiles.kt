package de.yuuto.aimtune.games

import de.yuuto.aimtune.core.scenario.AgentBody
import de.yuuto.aimtune.core.scenario.AngleBucket
import de.yuuto.aimtune.core.scenario.ArenaFeature
import de.yuuto.aimtune.core.scenario.ArenaFeatureKind
import de.yuuto.aimtune.core.scenario.ArenaLayout
import de.yuuto.aimtune.core.scenario.ElevationMix
import de.yuuto.aimtune.core.scenario.FlashGeometry
import de.yuuto.aimtune.core.scenario.GroundMovement
import de.yuuto.aimtune.core.scenario.PositiveRange
import de.yuuto.aimtune.core.scenario.QuantileCurve
import de.yuuto.aimtune.core.scenario.QuantilePoint
import de.yuuto.aimtune.core.scenario.RangeGeometry
import de.yuuto.aimtune.core.scenario.TrainerGeometry
import de.yuuto.aimtune.core.units.Fov
import de.yuuto.aimtune.core.units.FovType
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.name

class GameProfileException(message: String, cause: Throwable? = null) : Exception(message, cause)

@Serializable
private data class AspectDto(val width: Int, val height: Int)

@Serializable
private data class FovDto(val degrees: Double, val type: String, val aspect: AspectDto, val scaling: String, val trainerAspect: AspectDto? = null)

@Serializable
private data class SensitivityDto(val decimals: Int)

@Serializable
private data class GameProfileDto(
    val id: String,
    val name: String,
    val yaw: Double,
    val fov: FovDto,
    val sensitivity: SensitivityDto,
    val sources: List<String> = emptyList(),
    val notes: String? = null,
    val trainer: TrainerDto = TrainerDto(),
)

@Serializable
private data class RangeDto(val min: Double, val max: Double)

/** The `elevation` block of the `trainer` block: where targets appear vertically. `uniform` is the old fully 3D field. */
@Serializable
private data class ElevationDto(
    val spreadDegrees: Double? = null,
    val offLevelShare: Double? = null,
    val offLevelDegrees: RangeDto? = null,
    val loweredShare: Double? = null,
    val uniform: Boolean = false,
    val sources: List<String> = emptyList(),
    val notes: String? = null,
) {
    fun toMix(): ElevationMix {
        val d = ElevationMix()
        return ElevationMix(
            spreadDegrees = spreadDegrees ?: d.spreadDegrees,
            offLevelShare = offLevelShare ?: d.offLevelShare,
            offLevelDegrees = offLevelDegrees?.let { PositiveRange(it.min, it.max) } ?: d.offLevelDegrees,
            loweredShare = loweredShare ?: d.loweredShare,
            uniform = uniform,
        )
    }
}

@Serializable
private data class ArenaFeatureDto(val kind: String, val yawDegrees: Double, val distanceMeters: Double)

/** The `arena` block of the `trainer` block: the minimal map and how often targets stand at its corners and doorways. */
@Serializable
private data class ArenaDto(
    val wallDistanceMeters: Double? = null,
    val featureShare: Double? = null,
    val snapDegrees: Double? = null,
    val features: List<ArenaFeatureDto>? = null,
    val notes: String? = null,
) {
    fun toLayout(): ArenaLayout {
        val d = ArenaLayout()
        return ArenaLayout(
            wallDistanceMeters = wallDistanceMeters ?: d.wallDistanceMeters,
            features = features?.map { ArenaFeature(ArenaFeatureKind.valueOf(it.kind.uppercase()), it.yawDegrees, it.distanceMeters) } ?: d.features,
            featureShare = featureShare ?: d.featureShare,
            snapDegrees = snapDegrees ?: d.snapDegrees,
        )
    }
}

/** One quantile of a measured distance: [share] of the engagements are at most [meters] away. */
@Serializable
private data class QuantilePointDto(val share: Double, val meters: Double)

/** The `clickTargetDistanceQuantiles` block: measured engagement distances; [source] is required because the values are data, not guesses. */
@Serializable
private data class DistanceQuantilesDto(val points: List<QuantilePointDto>, val source: String, val verified: Boolean = false) {
    fun toCurve(): QuantileCurve {
        require(source.isNotBlank()) { "clickTargetDistanceQuantiles needs a source" }
        return QuantileCurve(points.map { QuantilePoint(it.share, it.meters) })
    }
}

@Serializable
private data class AngleBucketDto(val maxDegrees: Double, val weight: Double)

/** The `flash` block of the `trainer` block; `sources` and `notes` are for people reading the profile and are not used. */
@Serializable
private data class FlashDto(
    val popDelaySeconds: RangeDto? = null,
    val cueOffsetDegrees: RangeDto? = null,
    val cueElevationDegrees: Double? = null,
    val distanceMeters: RangeDto? = null,
    val gapSeconds: RangeDto? = null,
    val blindSeconds: Double? = null,
    val returnSpreadDegrees: Double? = null,
    val returnMinDegrees: Double? = null,
    val exitMarginDegrees: Double? = null,
    val verified: Boolean = false,
    val sources: List<String> = emptyList(),
    val notes: String? = null,
) {
    fun toGeometry(horizontalFovDegrees: Double): FlashGeometry {
        val d = FlashGeometry()
        fun RangeDto.range() = PositiveRange(min, max)
        return FlashGeometry(
            popDelaySeconds = popDelaySeconds?.range() ?: d.popDelaySeconds,
            cueOffsetDegrees = cueOffsetDegrees?.range() ?: d.cueOffsetDegrees,
            cueElevationDegrees = cueElevationDegrees ?: d.cueElevationDegrees,
            distanceMeters = distanceMeters?.range() ?: d.distanceMeters,
            gapSeconds = gapSeconds?.range() ?: d.gapSeconds,
            blindSeconds = blindSeconds ?: d.blindSeconds,
            returnSpreadDegrees = returnSpreadDegrees ?: d.returnSpreadDegrees,
            returnMinDegrees = returnMinDegrees ?: d.returnMinDegrees,
            exitMarginDegrees = exitMarginDegrees ?: d.exitMarginDegrees,
            verified = verified,
        ).forHorizontalFov(horizontalFovDegrees)
    }
}

@Serializable
private data class SpeedPresetsDto(val easySeconds: Double, val mediumSeconds: Double, val hardSeconds: Double)

@Serializable
private data class RangeStrafeDto(
    val speedMetersPerSecond: Double,
    val movingSeconds: RangeDto,
    val stopSeconds: RangeDto,
    val reverseProbability: Double,
    val maxOffsetMeters: Double,
)

@Serializable
private data class KillRuleDto(val headHitKills: Boolean, val bodyHitsArmorOn: Int, val bodyHitsArmorOff: Int)

/** Where one value of the `range` block comes from; a value that was not checked in the game says `verified: false`. */
@Serializable
private data class RangeValueNoteDto(val verified: Boolean = false, val source: String = "")

/** A closed interval that may include negative numbers (a bearing). */
@Serializable
private data class BoundsDto(val min: Double, val max: Double)

/**
 * The `range` block of the `trainer` block (Range mode, docs/RANGE.md). Every field is required: a game either has a complete
 * Range mode or none. [valueNotes] names the source of each value and is checked by the loader, so no value goes unexplained.
 */
@Serializable
private data class RangeBlockDto(
    val spawnDelaySeconds: RangeDto,
    val speedPresets: SpeedPresetsDto,
    val bearingDegrees: BoundsDto,
    val distanceMeters: RangeDto,
    val botHeightMeters: Double,
    val headDiameterMeters: Double,
    val strafe: RangeStrafeDto,
    val killRule: KillRuleDto,
    val valueNotes: Map<String, RangeValueNoteDto>,
) {
    fun toGeometry(): RangeGeometry {
        val missing = REQUIRED_NOTES - valueNotes.keys
        require(missing.isEmpty()) { "range.valueNotes is missing a source for ${missing.joinToString()}" }
        require(valueNotes.values.all { it.source.isNotBlank() }) { "every range.valueNotes entry needs a source" }
        fun RangeDto.range() = PositiveRange(min, max)
        return RangeGeometry(
            spawnDelaySeconds = spawnDelaySeconds.range(),
            easyLifetimeSeconds = speedPresets.easySeconds,
            mediumLifetimeSeconds = speedPresets.mediumSeconds,
            hardLifetimeSeconds = speedPresets.hardSeconds,
            bearingMinDegrees = bearingDegrees.min,
            bearingMaxDegrees = bearingDegrees.max,
            distanceMeters = distanceMeters.range(),
            botHeightMeters = botHeightMeters,
            headDiameterMeters = headDiameterMeters,
            strafeSpeedMetersPerSecond = strafe.speedMetersPerSecond,
            strafeMovingSeconds = strafe.movingSeconds.range(),
            strafeStopSeconds = strafe.stopSeconds.range(),
            strafeReverseProbability = strafe.reverseProbability,
            strafeMaxOffsetMeters = strafe.maxOffsetMeters,
            headHitKills = killRule.headHitKills,
            bodyHitsToKillArmorOn = killRule.bodyHitsArmorOn,
            bodyHitsToKillArmorOff = killRule.bodyHitsArmorOff,
            verified = valueNotes.values.all { it.verified },
        )
    }

    companion object {
        /** The values that need a source note; the keys of `valueNotes`. */
        val REQUIRED_NOTES = setOf("spawnDelaySeconds", "speedPresets", "bearingDegrees", "distanceMeters", "botHeightMeters", "headDiameterMeters", "strafe", "killRule")
    }
}

/**
 * The `body` block of the `trainer` block: size and speed of a player of the game (M23). Every field is required, and
 * [valueNotes] names the source of each value, as in the `range` block.
 */
@Serializable
private data class BodyBlockDto(
    val headDiameterMeters: Double,
    val capsuleHeightMeters: Double,
    val eyeStandMeters: Double,
    val eyeCrouchMeters: Double,
    val runSpeedMetersPerSecond: Double,
    val walkSpeedMetersPerSecond: Double,
    val crouchSpeedMetersPerSecond: Double,
    val valueNotes: Map<String, RangeValueNoteDto>,
) {
    fun toBody(): AgentBody {
        val missing = REQUIRED_NOTES - valueNotes.keys
        require(missing.isEmpty()) { "body.valueNotes is missing a source for ${missing.joinToString()}" }
        require(valueNotes.values.all { it.source.isNotBlank() }) { "every body.valueNotes entry needs a source" }
        return AgentBody(
            headDiameterMeters = headDiameterMeters,
            capsuleHeightMeters = capsuleHeightMeters,
            eyeStandMeters = eyeStandMeters,
            eyeCrouchMeters = eyeCrouchMeters,
            runSpeedMetersPerSecond = runSpeedMetersPerSecond,
            walkSpeedMetersPerSecond = walkSpeedMetersPerSecond,
            crouchSpeedMetersPerSecond = crouchSpeedMetersPerSecond,
            verified = valueNotes.values.all { it.verified },
        )
    }

    companion object {
        val REQUIRED_NOTES = setOf(
            "headDiameterMeters", "capsuleHeightMeters", "eyeStandMeters", "eyeCrouchMeters",
            "runSpeedMetersPerSecond", "walkSpeedMetersPerSecond", "crouchSpeedMetersPerSecond",
        )
    }
}

/**
 * The `movement` block of the `trainer` block: ground acceleration and friction of a player (M23). It needs a `body` block
 * for the top speeds. Every field is required, and [valueNotes] names the source of each value, as in the `body` block.
 */
@Serializable
private data class MovementBlockDto(
    val accel: Double,
    val friction: Double,
    val brakingFriction: Double,
    val frictionMin: Double,
    val aboveMaxSpeedDecay: Double,
    val walkAccelFactor: Double,
    val walkBrakingFactor: Double,
    val crouchAccelFactor: Double,
    val crouchFrictionFactor: Double,
    val crouchFrictionMinFactor: Double,
    val valueNotes: Map<String, RangeValueNoteDto>,
) {
    fun toMovement(): GroundMovement {
        val missing = REQUIRED_NOTES - valueNotes.keys
        require(missing.isEmpty()) { "movement.valueNotes is missing a source for ${missing.joinToString()}" }
        require(valueNotes.values.all { it.source.isNotBlank() }) { "every movement.valueNotes entry needs a source" }
        return GroundMovement(
            accel = accel,
            friction = friction,
            brakingFriction = brakingFriction,
            frictionMin = frictionMin,
            aboveMaxSpeedDecay = aboveMaxSpeedDecay,
            walkAccelFactor = walkAccelFactor,
            walkBrakingFactor = walkBrakingFactor,
            crouchAccelFactor = crouchAccelFactor,
            crouchFrictionFactor = crouchFrictionFactor,
            crouchFrictionMinFactor = crouchFrictionMinFactor,
            verified = valueNotes.values.all { it.verified },
        )
    }

    companion object {
        val REQUIRED_NOTES = setOf(
            "accel", "friction", "brakingFriction", "frictionMin", "aboveMaxSpeedDecay",
            "walkAccelFactor", "walkBrakingFactor", "crouchAccelFactor", "crouchFrictionFactor", "crouchFrictionMinFactor",
        )
    }
}

/** The `trainer` block of a profile; every field is optional and falls back to [TrainerGeometry.DEFAULT]. */
@Serializable
private data class TrainerDto(
    val fieldYawDegrees: Double? = null,
    val fieldPitchDegrees: Double? = null,
    val clickTargetDiameterDegrees: RangeDto? = null,
    val clickTargetDistanceMeters: RangeDto? = null,
    val clickTargetDistanceQuantiles: DistanceQuantilesDto? = null,
    val flickAngles: List<AngleBucketDto>? = null,
    val microStepDegrees: RangeDto? = null,
    val switchingSeparationDegrees: Double? = null,
    val trackingDiameterDegrees: RangeDto? = null,
    val trackingDistanceMeters: Double? = null,
    val strafeSpeedMetersPerSecond: RangeDto? = null,
    val stopProbability: Double? = null,
    val flash: FlashDto? = null,
    val eyeHeightMeters: Double? = null,
    val headHeightMeters: Double? = null,
    val elevation: ElevationDto? = null,
    val arena: ArenaDto? = null,
    val range: RangeBlockDto? = null,
    val body: BodyBlockDto? = null,
    val movement: MovementBlockDto? = null,
    val verified: Boolean = false,
) {
    fun toGeometry(horizontalFovDegrees: Double): TrainerGeometry {
        val d = TrainerGeometry.DEFAULT
        fun RangeDto.range() = PositiveRange(min, max)
        return TrainerGeometry(
            fieldYawDegrees = fieldYawDegrees ?: d.fieldYawDegrees,
            fieldPitchDegrees = fieldPitchDegrees ?: d.fieldPitchDegrees,
            clickTargetDiameterDegrees = clickTargetDiameterDegrees?.range() ?: d.clickTargetDiameterDegrees,
            clickTargetDistanceMeters = clickTargetDistanceMeters?.range() ?: d.clickTargetDistanceMeters,
            clickTargetDistanceQuantiles = clickTargetDistanceQuantiles?.toCurve(),
            flickAngles = flickAngles?.map { AngleBucket(it.maxDegrees, it.weight) } ?: d.flickAngles,
            microStepDegrees = microStepDegrees?.range() ?: d.microStepDegrees,
            switchingSeparationDegrees = switchingSeparationDegrees ?: d.switchingSeparationDegrees,
            trackingDiameterDegrees = trackingDiameterDegrees?.range() ?: d.trackingDiameterDegrees,
            trackingDistanceMeters = trackingDistanceMeters ?: d.trackingDistanceMeters,
            strafeSpeedMetersPerSecond = strafeSpeedMetersPerSecond?.range() ?: d.strafeSpeedMetersPerSecond,
            stopProbability = stopProbability ?: d.stopProbability,
            flash = flash?.toGeometry(horizontalFovDegrees) ?: d.flash.forHorizontalFov(horizontalFovDegrees),
            eyeHeightMeters = eyeHeightMeters ?: d.eyeHeightMeters,
            headHeightMeters = headHeightMeters ?: d.headHeightMeters,
            elevation = elevation?.toMix() ?: d.elevation,
            arena = arena?.toLayout() ?: d.arena,
            range = range?.toGeometry(),
            body = body?.toBody(),
            movement = movement?.toMovement(),
            verified = verified,
        )
    }
}

@Serializable
private data class IndexDto(val games: List<String>)

private const val HOR_PLUS = "hor_plus"

private val json = Json { ignoreUnknownKeys = true }

/** Parses one profile file. @throws GameProfileException with a message naming the problem. */
fun parseGameProfile(text: String): GameProfile {
    val dto = try {
        json.decodeFromString<GameProfileDto>(text)
    } catch (e: SerializationException) {
        throw GameProfileException("not a valid game profile: ${e.message}", e)
    }
    try {
        require(dto.fov.scaling == HOR_PLUS) { "fov.scaling '${dto.fov.scaling}' is not supported (only '$HOR_PLUS')" }
        require(dto.fov.aspect.width > 0 && dto.fov.aspect.height > 0) { "fov.aspect must be positive" }
        val type = FovType.entries.firstOrNull { it.name.equals(dto.fov.type, ignoreCase = true) }
            ?: throw IllegalArgumentException("fov.type '${dto.fov.type}' must be horizontal or vertical")
        val fov = Fov(dto.fov.degrees, type)
        val fovAspect = dto.fov.aspect.width.toDouble() / dto.fov.aspect.height
        val trainerAspect = dto.fov.trainerAspect?.let { it.width.toDouble() / it.height } ?: (16.0 / 9.0)
        return GameProfile(
            id = dto.id,
            name = dto.name,
            yawDegreesPerCount = dto.yaw,
            fov = fov,
            fovAspectRatio = fovAspect,
            sensitivityDecimals = dto.sensitivity.decimals,
            sources = dto.sources,
            notes = dto.notes,
            geometry = dto.trainer.toGeometry(horizontalFovAt(fov, fovAspect, trainerAspect)),
            trainerAspectRatio = trainerAspect,
        )
    } catch (e: IllegalArgumentException) {
        throw GameProfileException("invalid game profile '${dto.id}': ${e.message}", e)
    }
}

/** Bundled profiles plus any the user added; [problems] lists user files that were skipped and why. */
class GameProfiles(val profiles: List<GameProfile>, val problems: List<String>) {
    fun find(id: String): GameProfile? = profiles.firstOrNull { it.id == id }

    companion object {
        /**
         * Loads the profiles bundled in the app, then every `*.json` in [userDir] (if given). A user
         * profile with a bundled id replaces it, so values can be corrected without a new release.
         * A broken bundled profile is a bug and fails loudly; a broken user file is only reported.
         */
        fun load(userDir: Path? = null): GameProfiles {
            val byId = LinkedHashMap<String, GameProfile>()
            bundled().forEach { byId[it.id] = it }
            val problems = mutableListOf<String>()
            if (userDir != null && Files.isDirectory(userDir)) {
                Files.list(userDir).use { files -> files.filter { it.extension == "json" }.sorted().toList() }.forEach { file ->
                    try {
                        parseGameProfile(Files.readString(file)).also { byId[it.id] = it }
                    } catch (e: GameProfileException) {
                        problems += "${file.name}: ${e.message}"
                    } catch (e: IOException) {
                        problems += "${file.name}: could not be read (${e.message})"
                    }
                }
            }
            return GameProfiles(byId.values.toList(), problems)
        }

        private fun bundled(): List<GameProfile> {
            val index = json.decodeFromString<IndexDto>(resource("games/index.json"))
            return index.games.map { id ->
                try {
                    parseGameProfile(resource("games/$id.json"))
                } catch (e: GameProfileException) {
                    throw IllegalStateException("bundled game profile '$id' is broken: ${e.message}", e)
                }
            }
        }

        private fun resource(path: String): String =
            (GameProfiles::class.java.classLoader.getResourceAsStream(path) ?: error("missing bundled resource $path"))
                .use { it.readBytes().toString(Charsets.UTF_8) }
    }
}
