package de.yuuto.aimtune.app.setup

import de.yuuto.aimtune.core.scenario.ScenarioKind
import de.yuuto.aimtune.core.score.FlashImportance
import de.yuuto.aimtune.core.units.AimStyle
import de.yuuto.aimtune.games.GameProfile
import de.yuuto.aimtune.games.GameProfiles
import de.yuuto.aimtune.storage.AppSettings
import java.math.BigDecimal

const val GAME_CUSTOM = GameProfile.CUSTOM_ID

private const val MAX_DPI = 100_000.0
private const val MAX_FOV_DEGREES = 179.0

/** A turn of the view that takes less or more mouse travel than this is a typing mistake, not a sensitivity. */
const val MIN_PLAUSIBLE_CM360 = 3.0
const val MAX_PLAUSIBLE_CM360 = 600.0

enum class SetupStep { DPI, GAME, STYLE, PAD }

/** What is wrong with the current input of a step; [key] is the text in the i18n bundles. */
enum class SetupIssue(val key: String) {
    DPI_INVALID("setup.issue.dpi"),
    GAME_MISSING("setup.issue.game"),
    SENSITIVITY_INVALID("setup.issue.sensitivity"),
    CM360_IMPLAUSIBLE("setup.issue.cm360"),
    CUSTOM_YAW_INVALID("setup.issue.custom_yaw"),
    CUSTOM_FOV_INVALID("setup.issue.custom_fov"),
    STYLE_MISSING("setup.issue.style"),
    PAD_MISSING("setup.issue.pad"),
    PAD_SIZE_INVALID("setup.issue.pad_size"),
}

/** Mousepad sizes of the onboarding step (approximate outer sizes in cm); [CUSTOM] takes typed values. */
enum class PadPreset(val widthCm: Double?, val heightCm: Double?) {
    SMALL(27.0, 21.0),
    MEDIUM(32.0, 27.0),
    LARGE(45.0, 40.0),
    DESK_MAT(90.0, 40.0),
    CUSTOM(null, null),
}

/** A positive finite number typed by the user; a comma works as decimal separator. Null if it is none. */
fun parsePositive(text: String): Double? =
    text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }

/**
 * The editable values of onboarding and settings, as typed. Immutable: every edit makes a copy.
 * All validation and derived values (live cm/360°, pad size) live here so they can be tested
 * without any UI.
 */
data class SetupDraft(
    val dpiText: String = "",
    val gameId: String? = null,
    val customYawText: String = "",
    val customFovText: String = "",
    val sensitivityText: String = "",
    val aimStyle: AimStyle? = null,
    val padPreset: PadPreset? = null,
    val customPadWidthText: String = "",
    val customPadHeightText: String = "",
    /** How much flashes matter to the player; applied to the weights and scenarios on save. */
    val flashImportance: FlashImportance = FlashImportance.SOMETIMES,
) {
    val dpi: Double? get() = parsePositive(dpiText)?.takeIf { it <= MAX_DPI }
    val sensitivity: Double? get() = parsePositive(sensitivityText)

    private val customFov: Double? get() = parsePositive(customFovText)?.takeIf { it <= MAX_FOV_DEGREES }

    fun profile(profiles: GameProfiles): GameProfile? = when (gameId) {
        null -> null
        GAME_CUSTOM -> {
            val yaw = parsePositive(customYawText)
            val fov = customFov
            if (yaw == null || fov == null) null else GameProfile.custom(yaw, fov)
        }
        else -> profiles.find(gameId)
    }

    /** Distance for one full turn with the current input, or null while something is missing. */
    fun currentCm360(profiles: GameProfiles): Double? {
        val dpi = dpi ?: return null
        val sensitivity = sensitivity ?: return null
        return profile(profiles)?.cm360(dpi, sensitivity)
    }

    /** Pad width and height in cm, or null while not chosen or not valid. */
    fun padSizeCm(): Pair<Double, Double>? = when (padPreset) {
        null -> null
        PadPreset.CUSTOM -> {
            val w = parsePositive(customPadWidthText)
            val h = parsePositive(customPadHeightText)
            if (w == null || h == null) null else w to h
        }
        else -> padPreset.widthCm!! to padPreset.heightCm!!
    }

    /** The first problem with [step], or null if the step can be left. */
    fun issue(step: SetupStep, profiles: GameProfiles): SetupIssue? = when (step) {
        SetupStep.DPI -> if (dpi == null) SetupIssue.DPI_INVALID else null
        SetupStep.GAME -> gameIssue(profiles)
        SetupStep.STYLE -> if (aimStyle == null) SetupIssue.STYLE_MISSING else null
        SetupStep.PAD -> padIssue()
    }

    private fun gameIssue(profiles: GameProfiles): SetupIssue? = when {
        gameId == null -> SetupIssue.GAME_MISSING
        gameId == GAME_CUSTOM && parsePositive(customYawText) == null -> SetupIssue.CUSTOM_YAW_INVALID
        gameId == GAME_CUSTOM && customFov == null -> SetupIssue.CUSTOM_FOV_INVALID
        profile(profiles) == null -> SetupIssue.GAME_MISSING
        sensitivity == null -> SetupIssue.SENSITIVITY_INVALID
        currentCm360(profiles)?.let { it < MIN_PLAUSIBLE_CM360 || it > MAX_PLAUSIBLE_CM360 } == true -> SetupIssue.CM360_IMPLAUSIBLE
        else -> null
    }

    private fun padIssue(): SetupIssue? = when {
        padPreset == null -> SetupIssue.PAD_MISSING
        padSizeCm() == null -> SetupIssue.PAD_SIZE_INVALID
        else -> null
    }

    /** Applies this draft to [base]. @throws IllegalStateException if any step still has a problem. */
    fun toSettings(base: AppSettings, profiles: GameProfiles): AppSettings {
        SetupStep.entries.forEach { step -> issue(step, profiles)?.let { check(false) { "setup incomplete: $it" } } }
        val (padWidth, padHeight) = checkNotNull(padSizeCm())
        val custom = gameId == GAME_CUSTOM
        // A list the player did not choose becomes the game's own defaults; a chosen one stays, but a game without a
        // Range mode cannot play range rounds, so that scenario does not stay listed.
        val geometry = profile(profiles)?.geometry
        val hasRange = geometry?.range != null
        val scenarios = when {
            AppSettings.isDefaultScenarios(base.scenarios) -> AppSettings.defaultScenarios(hasRange, geometry?.mix != null)
            hasRange -> base.scenarios
            else -> base.scenarios.filter { it != ScenarioKind.RANGE.id }.ifEmpty { listOf(ScenarioKind.FLICK.id) }
        }
        return base.copy(scenarios = scenarios).withFlashImportance(flashImportance).copy(
            dpi = dpi,
            gameId = gameId,
            inGameSensitivity = sensitivity,
            customYaw = if (custom) parsePositive(customYawText) else null,
            customFovDegrees = if (custom) customFov else null,
            aimStyle = aimStyle?.name,
            padWidthCm = padWidth,
            padHeightCm = padHeight,
        )
    }

    companion object {
        fun fromSettings(settings: AppSettings): SetupDraft {
            val width = settings.padWidthCm
            val height = settings.padHeightCm
            val preset = if (width == null || height == null) null else {
                PadPreset.entries.firstOrNull { it.widthCm == width && it.heightCm == height } ?: PadPreset.CUSTOM
            }
            return SetupDraft(
                dpiText = settings.dpi?.let(::formatNumber).orEmpty(),
                gameId = settings.gameId,
                customYawText = settings.customYaw?.let(::formatNumber).orEmpty(),
                customFovText = settings.customFovDegrees?.let(::formatNumber).orEmpty(),
                sensitivityText = settings.inGameSensitivity?.let(::formatNumber).orEmpty(),
                aimStyle = runCatching { settings.aimStyleOrNull() }.getOrNull(),
                padPreset = preset,
                flashImportance = settings.flashImportance(),
                customPadWidthText = if (preset == PadPreset.CUSTOM) formatNumber(checkNotNull(width)) else "",
                customPadHeightText = if (preset == PadPreset.CUSTOM) formatNumber(checkNotNull(height)) else "",
            )
        }

        /** 800.0 -> "800", 1.20 -> "1.2". */
        fun formatNumber(value: Double): String = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
    }
}
