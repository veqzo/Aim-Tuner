package de.yuuto.aimtune.app

import de.yuuto.aimtune.core.accel.RawAccelProfile

/** What the sensitivity finder (the blind test) found, with how sure it is. */
data class FinderResult(
    val cm360: Double,
    val confidencePercent: Int,
    val rounds: Int,
    /** The result as the game's sensitivity, formatted with the decimals the game takes. */
    val sensitivityText: String,
    /** Stored search rounds at this sensitivity that a new strength search could build on. */
    val searchRounds: Int = 0,
) {
    /** A result below [MIN_CONFIDENCE] may not become the sensitivity of a curve run: the curve is judged at that sensitivity. */
    val confident: Boolean get() = confidencePercent >= MIN_CONFIDENCE

    companion object {
        const val MIN_CONFIDENCE = 50
    }
}

/**
 * The sensitivity the acceleration steps are played at: the player's own game sensitivity, or the finder's result when it
 * is confident and the player switches it on. [gameSearchRounds] are the stored search rounds at the game sensitivity.
 */
data class AccelBaseline(
    val gameCm360: Double,
    /** The configured sensitivity, formatted with the decimals the game takes. */
    val gameSensitivityText: String,
    val gameName: String,
    val finder: FinderResult? = null,
    val gameSearchRounds: Int = 0,
    /** The Raw Accel profile imported in the hub, when the trainer can play it exactly at the DPI of the settings; the analysis path may calibrate against it. */
    val ownCurve: RawAccelProfile? = null,
    /** File and profile name of the imported curve, for the intro line; null without an import. */
    val ownCurveLabel: String? = null,
    /** The imported file's device DPI when it differs from [settingsDpi] (0: none set), so the curve is not used; null when it fits or there is no import. */
    val ownCurveFileDpi: Int? = null,
    val settingsDpi: Double = 0.0,
) {
    /** Whether the finder's result may be chosen at all. */
    val canUseFinder: Boolean get() = finder?.confident == true

    /** The cm/360° the curve runs are played at: the game sensitivity unless the finder is confident and [useFinder] is on. */
    fun cm360(useFinder: Boolean): Double = if (useFinder && finder != null && canUseFinder) finder.cm360 else gameCm360

    /** Stored search rounds that a new search at the chosen sensitivity could build on. */
    fun searchRounds(useFinder: Boolean): Int = if (useFinder && canUseFinder) finder?.searchRounds ?: 0 else gameSearchRounds
}
