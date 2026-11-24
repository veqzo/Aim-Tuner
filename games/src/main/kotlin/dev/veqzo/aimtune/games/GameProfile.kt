package dev.veqzo.aimtune.games

import dev.veqzo.aimtune.core.scenario.TrainerGeometry
import dev.veqzo.aimtune.core.units.Fov
import dev.veqzo.aimtune.core.units.FovType
import dev.veqzo.aimtune.core.units.cm360
import dev.veqzo.aimtune.core.units.sensitivityFor
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.atan
import kotlin.math.tan

/**
 * What the app needs to know about a game: its yaw (degrees per count at sensitivity 1), its FOV
 * and how the FOV behaves for other window shapes, and how sensitivities are written. Profiles
 * are plain JSON files (see CONTRIBUTING.md), so adding a game needs no code.
 *
 * Only "Hor+" scaling is supported: the vertical FOV stays constant and the horizontal one grows
 * with the aspect ratio, so the trainer derives one vertical FOV from [fov] at [fovAspectRatio].
 */
data class GameProfile(
    val id: String,
    val name: String,
    val yawDegreesPerCount: Double,
    val fov: Fov,
    /** Width / height of the screen shape that [fov] refers to, e.g. 16/9 or 4/3. */
    val fovAspectRatio: Double,
    /** Decimal places of the sensitivity value a player can type into the game. */
    val sensitivityDecimals: Int,
    val sources: List<String> = emptyList(),
    val notes: String? = null,
    /**
     * Width / height of the picture the trainer draws, whatever the window's shape: the scene is letterboxed
     * to it, so the horizontal FOV is exactly what the game shows at this aspect ratio. How a game scales
     * its FOV for other shapes is not documented for Valorant (docs/history/REVIEW.md M7), so the trainer does not guess.
     */
    val trainerAspectRatio: Double = WIDESCREEN_ASPECT,
    /** Angular target sizes, engagement angles and movement speeds the trainer uses; defaults are unverified assumptions. */
    val geometry: TrainerGeometry = TrainerGeometry.DEFAULT,
) {
    init {
        require(ID_PATTERN.matches(id)) { "game id must match ${ID_PATTERN.pattern}, was '$id'" }
        require(name.isNotBlank()) { "game name must not be blank" }
        require(yawDegreesPerCount.isFinite() && yawDegreesPerCount > 0.0) { "yaw must be positive, was $yawDegreesPerCount" }
        require(fovAspectRatio.isFinite() && fovAspectRatio > 0.0) { "fov aspect ratio must be positive, was $fovAspectRatio" }
        require(trainerAspectRatio.isFinite() && trainerAspectRatio in 1.0..3.0) { "trainer aspect ratio must be in 1..3, was $trainerAspectRatio" }
        require(sensitivityDecimals in 0..MAX_DECIMALS) { "sensitivity decimals must be in 0..$MAX_DECIMALS, was $sensitivityDecimals" }
    }

    /** Vertical FOV in degrees that reproduces the game's view in a renderer taking a vertical FOV. */
    val verticalFovDegrees: Double get() = fov.verticalDegrees(fovAspectRatio)

    /** Physical distance for one full turn at [dpi] and in-game [sensitivity]. */
    fun cm360(dpi: Double, sensitivity: Double): Double = cm360(dpi, sensitivity, yawDegreesPerCount)

    /** Exact (unrounded) in-game sensitivity that gives [cm360] at [dpi]. */
    fun sensitivityFor(dpi: Double, cm360: Double): Double = sensitivityFor(dpi, yawDegreesPerCount, cm360)

    /** [sensitivity] rounded half up to the number of decimals the game takes, going by its decimal text (1.2345 -> 1.235). */
    fun roundSensitivity(sensitivity: Double): Double =
        BigDecimal.valueOf(sensitivity).setScale(sensitivityDecimals, RoundingMode.HALF_UP).toDouble()

    /** [sensitivity] as text with exactly the profile's decimals, independent of the system locale. */
    fun formatSensitivity(sensitivity: Double): String =
        BigDecimal.valueOf(sensitivity).setScale(sensitivityDecimals, RoundingMode.HALF_UP).toPlainString()

    companion object {
        const val CUSTOM_ID = "custom"
        private const val MAX_DECIMALS = 8
        private const val DEFAULT_CUSTOM_DECIMALS = 3
        private const val WIDESCREEN_ASPECT = 16.0 / 9.0
        private val ID_PATTERN = Regex("[a-z0-9][a-z0-9_-]*")

        /** A profile for a game that has no bundled profile; the FOV is taken as horizontal at 16:9. */
        fun custom(yawDegreesPerCount: Double, horizontalFovDegrees: Double): GameProfile = GameProfile(
            id = CUSTOM_ID,
            name = "Custom",
            yawDegreesPerCount = yawDegreesPerCount,
            fov = Fov(horizontalFovDegrees, FovType.HORIZONTAL),
            fovAspectRatio = WIDESCREEN_ASPECT,
            sensitivityDecimals = DEFAULT_CUSTOM_DECIMALS,
            geometry = TrainerGeometry.DEFAULT.run { copy(flash = flash.forHorizontalFov(horizontalFovDegrees)) },
        )
    }
}

/**
 * Horizontal field of view in degrees of the picture the trainer draws: the game's vertical FOV (kept for
 * every aspect ratio, Hor+) widened to [trainerAspect].
 */
fun horizontalFovAt(fov: Fov, fovAspectRatio: Double, trainerAspect: Double): Double =
    Math.toDegrees(2.0 * atan(tan(Math.toRadians(fov.verticalDegrees(fovAspectRatio)) / 2.0) * trainerAspect))
