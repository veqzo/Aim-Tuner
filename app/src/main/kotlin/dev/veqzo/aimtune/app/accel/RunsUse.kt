package dev.veqzo.aimtune.app.accel

import dev.veqzo.aimtune.app.AccelBaseline
import dev.veqzo.aimtune.app.TrainerConfig

/** Where the sensitivity of the runs comes from. */
sealed interface RunsSource {
    /** The game sensitivity of the settings. */
    data object Settings : RunsSource

    /** The finder's optimum, chosen with the box in "Find a curve". */
    data class Finder(val rounds: Int, val confidencePercent: Int) : RunsSource

    /** A run that is under way: it keeps the values it started with. */
    data object Running : RunsSource
}

/** The sensitivity every Raw Accel run is played at, for the "Runs use" card and line. */
data class RunsUse(val gameName: String, val sensitivityText: String, val cm360: Double, val dpi: Double, val source: RunsSource) {
    companion object {
        /** The sensitivity a new run would use: the finder's optimum when [useFinder] is on and allowed, else the settings. */
        fun of(baseline: AccelBaseline, dpi: Double, useFinder: Boolean): RunsUse {
            val finder = baseline.finder?.takeIf { useFinder && baseline.canUseFinder }
            return if (finder != null) {
                RunsUse(baseline.gameName, finder.sensitivityText, finder.cm360, dpi, RunsSource.Finder(finder.rounds, finder.confidencePercent))
            } else {
                RunsUse(baseline.gameName, baseline.gameSensitivityText, baseline.gameCm360, dpi, RunsSource.Settings)
            }
        }

        /** The values a run started with. */
        fun running(config: TrainerConfig): RunsUse {
            val profile = config.profile
            return RunsUse(profile.name, profile.formatSensitivity(profile.sensitivityFor(config.dpi, config.cm360)), config.cm360, config.dpi, RunsSource.Running)
        }
    }
}
