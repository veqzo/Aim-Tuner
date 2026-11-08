package de.yuuto.aimtune.storage

import de.yuuto.aimtune.core.scenario.RangeOptions
import de.yuuto.aimtune.core.scenario.RangeSpeed
import de.yuuto.aimtune.core.scenario.ScenarioKind
import de.yuuto.aimtune.core.score.FlashImportance
import de.yuuto.aimtune.core.score.MetricWeights
import de.yuuto.aimtune.core.score.ScoreMetric
import de.yuuto.aimtune.core.units.AimStyle
import kotlinx.serialization.Serializable

class SettingsException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Which curve finder the acceleration analysis uses. */
@Serializable
enum class CurveFinder { LEGACY, V2 }

/**
 * User settings stored as JSON. Everything personal is nullable and stays null until onboarding
 * fills it; nothing user-specific is defaulted. Metric weights are keyed by [ScoreMetric] name.
 */
@Serializable
data class AppSettings(
    val version: Int = CURRENT_VERSION,
    val language: String? = null,
    val dpi: Double? = null,
    /** Id of a game profile, or `custom` together with [customYaw] and [customFovDegrees]. */
    val gameId: String? = null,
    val customYaw: Double? = null,
    val customFovDegrees: Double? = null,
    val inGameSensitivity: Double? = null,
    /** [AimStyle] name; stored as text so the core enum stays free of serialization. */
    val aimStyle: String? = null,
    val padWidthCm: Double? = null,
    val padHeightCm: Double? = null,
    val roundSeconds: Int = DEFAULT_ROUND_SECONDS,
    val metricWeights: Map<String, Double> = DEFAULT_WEIGHTS,
    /** Ids of the scenarios a blind test plays ([ScenarioKind.id]); how often each is played follows [metricWeights]. */
    val scenarios: List<String> = DEFAULT_SCENARIOS_WITH_RANGE,
    /** Play the cue sounds of the flash scenario (alert, whine, pop, ringing). */
    val flashSound: Boolean = true,
    /** Play a short sound for every hit; it rises with a streak of hits. */
    val hitSound: Boolean = true,
    /** Volume of the flash cue sounds, percent of their built-in level (the pop was the loudest and hurt at 100). */
    val flashVolumePercent: Int = DEFAULT_FLASH_VOLUME_PERCENT,
    /** Volume of the hit sound, percent of its built-in level. */
    val hitVolumePercent: Int = DEFAULT_HIT_VOLUME_PERCENT,
    /** How bright the white-out of a flash is, percent of full white; it was plain white (100) and hurt the eyes. */
    val flashBrightnessPercent: Int = DEFAULT_FLASH_BRIGHTNESS_PERCENT,
    /** Ask GitHub at most once a day whether a newer release exists, and show a hint in the menu if so. */
    val checkForUpdates: Boolean = true,
    /** When the last check ran, successful or not (epoch seconds); a private repository would otherwise be asked at every start. */
    val lastUpdateCheckEpochSeconds: Long = 0L,
    /** The newest release the last successful check found (version and its page), so the hint stays on days without a check. */
    val latestKnownVersion: String? = null,
    val latestKnownUrl: String? = null,
    /** The window is shown fullscreen; F11 switches it and the choice is kept for the next start. */
    val fullscreen: Boolean = false,
    /** The player's Valorant crosshairs, stored as profile codes; none means the default crosshair is drawn. */
    val crosshairs: List<SavedCrosshair> = emptyList(),
    /** Id of the selected entry of [crosshairs]; used in all scenarios. */
    val activeCrosshair: String? = null,
    /**
     * V2 bounds the curve to the measured speeds and exports it as a Raw Accel lookup table (docs/ALGORITHMS.md
     * "Bounded curves"); LEGACY keeps the fitted family.
     */
    val curveFinder: CurveFinder = CurveFinder.V2,
    /** Range mode: how long an unshot bot stays ([RangeSpeed.id]: none, easy, medium, hard). */
    val rangeSpeed: String = RangeSpeed.NONE.id,
    /** Range mode: bots strafe (true) or stand still (false, the range's "Reset"). */
    val rangeBotsStrafe: Boolean = true,
    /** Range mode: bot armor on (the range's default; a body kill needs one more hit). */
    val rangeBotArmor: Boolean = true,
    /** The Raw Accel `settings.json` imported last (its path only, never its content); the Raw Accel hub reads it again on open. */
    val lastImportedProfilePath: String? = null,
) {
    /** @throws SettingsException describing the first invalid field. */
    fun validate() {
        fun positive(name: String, value: Double?) {
            if (value != null && !(value.isFinite() && value > 0.0)) throw SettingsException("$name must be a positive number, was $value")
        }
        positive("dpi", dpi)
        positive("inGameSensitivity", inGameSensitivity)
        positive("customYaw", customYaw)
        positive("customFovDegrees", customFovDegrees)
        positive("padWidthCm", padWidthCm)
        positive("padHeightCm", padHeightCm)
        if (roundSeconds <= 0) throw SettingsException("roundSeconds must be positive, was $roundSeconds")
        aimStyleOrNull()
        toMetricWeights()
        enabledScenarios()
        rangeOptions()
        validateCrosshairs()
    }

    /** The Range mode options of these settings. @throws SettingsException for an unknown speed. */
    fun rangeOptions(): RangeOptions =
        RangeOptions(speed = RangeSpeed.fromId(rangeSpeed) ?: throw SettingsException("unknown range speed '$rangeSpeed'"), botsStrafe = rangeBotsStrafe, armor = rangeBotArmor)

    /** @throws SettingsException for unknown scenario ids or an empty selection. */
    fun enabledScenarios(): Set<ScenarioKind> {
        if (scenarios.isEmpty()) throw SettingsException("at least one scenario must be enabled")
        return scenarios.map { id -> ScenarioKind.fromId(id) ?: throw SettingsException("unknown scenario '$id'") }.toSet()
    }

    /**
     * How much the player cares about flashes: the flash scenario is off, or its weight is the nearest level.
     * The weight is what makes the scenario play (see [de.yuuto.aimtune.core.scenario.ScenarioSchedule]).
     */
    fun flashImportance(): FlashImportance =
        if (ScenarioKind.FLASH.id !in scenarios) FlashImportance.NEVER else FlashImportance.of(metricWeights[ScoreMetric.FLASH_DODGE.name])

    /** These settings with the flash scenario switched on or off and its weight set for [importance]; the other scenarios stay. */
    fun withFlashImportance(importance: FlashImportance): AppSettings {
        val others = scenarios.filter { it != ScenarioKind.FLASH.id }
        val flash = if (importance == FlashImportance.NEVER) emptyList() else listOf(ScenarioKind.FLASH.id)
        return copy(
            metricWeights = metricWeights + (ScoreMetric.FLASH_DODGE.name to importance.weight),
            scenarios = (others + flash).ifEmpty { listOf(ScenarioKind.FLICK.id) },
        )
    }

    /** @throws SettingsException if [aimStyle] is set to something that is not an aim style. */
    fun aimStyleOrNull(): AimStyle? = aimStyle?.let { name ->
        AimStyle.entries.firstOrNull { it.name == name } ?: throw SettingsException("unknown aim style '$name'")
    }

    /** @throws SettingsException for unknown metric names or weights that are negative or all zero. */
    fun toMetricWeights(): MetricWeights {
        val byMetric = metricWeights.mapKeys { (name, _) ->
            ScoreMetric.entries.firstOrNull { it.name == name } ?: throw SettingsException("unknown metric '$name' in metricWeights")
        }
        return try {
            MetricWeights(byMetric)
        } catch (e: IllegalArgumentException) {
            throw SettingsException("invalid metricWeights: ${e.message}", e)
        }
    }

    companion object {
        const val CURRENT_VERSION = 1
        const val DEFAULT_ROUND_SECONDS = 45
        const val DEFAULT_FLASH_VOLUME_PERCENT = 60
        const val DEFAULT_HIT_VOLUME_PERCENT = 100
        const val DEFAULT_FLASH_BRIGHTNESS_PERCENT = 50

        /** The lowest white-out brightness a player can set; black would make the blind round a pause. */
        const val MIN_FLASH_BRIGHTNESS_PERCENT = 10

        /** A percent setting as a factor in 0..1, whatever a hand-edited settings file holds. */
        fun fraction(percent: Int): Float = percent.coerceIn(0, 100) / 100f
        val DEFAULT_WEIGHTS: Map<String, Double> =
            MetricWeights.DEFAULT.values.mapKeys { it.key.name } + (ScoreMetric.FLASH_DODGE.name to FlashImportance.SOMETIMES.weight)

        /** The default scenarios of a game without a Range mode: flick, and flash (its importance decides how often it plays). */
        val DEFAULT_SCENARIOS: List<String> = listOf(ScenarioKind.FLICK.id, ScenarioKind.FLASH.id)

        /** The default scenarios of a game with a Range mode (M15d): flick, range and flash. */
        val DEFAULT_SCENARIOS_WITH_RANGE: List<String> = listOf(ScenarioKind.FLICK.id, ScenarioKind.RANGE.id, ScenarioKind.FLASH.id)

        /** The strafe scenarios a game with a scenario mix adds to its defaults (M23): its moving opponents. */
        val MIX_STRAFE_SCENARIOS: List<String> = listOf(ScenarioKind.MICRO_STRAFE.id, ScenarioKind.SWITCH_STRAFE.id)

        /**
         * The default scenarios for a game that has a Range mode ([hasRange]) or not, and a scenario mix ([hasMix]) or not.
         * A game with a mix also plays its moving opponents in the strafe scenarios, before the flash rounds.
         */
        fun defaultScenarios(hasRange: Boolean, hasMix: Boolean = false): List<String> {
            val base = if (hasRange) DEFAULT_SCENARIOS_WITH_RANGE else DEFAULT_SCENARIOS
            return if (hasMix) base.filter { it != ScenarioKind.FLASH.id } + MIX_STRAFE_SCENARIOS + ScenarioKind.FLASH.id else base
        }

        /** True when [scenarios] is one of the default lists, with or without flash: the player did not choose it. */
        fun isDefaultScenarios(scenarios: List<String>): Boolean {
            fun withoutFlash(list: List<String>) = list.toSet() - ScenarioKind.FLASH.id
            val defaults = listOf(true, false).flatMap { range -> listOf(true, false).map { mix -> withoutFlash(defaultScenarios(range, mix)) } }
            return withoutFlash(scenarios) in defaults
        }
    }
}
