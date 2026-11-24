package dev.veqzo.aimtune.app

import dev.veqzo.aimtune.app.setup.SetupDraft
import dev.veqzo.aimtune.app.setup.SetupStep
import dev.veqzo.aimtune.core.accel.AccelModel
import dev.veqzo.aimtune.core.scenario.RangeOptions
import dev.veqzo.aimtune.core.scenario.ScenarioKind
import dev.veqzo.aimtune.core.scenario.TrainerGeometry
import dev.veqzo.aimtune.games.GameProfile
import dev.veqzo.aimtune.games.GameProfiles
import dev.veqzo.aimtune.storage.AppSettings

/** Everything one trainer session needs; [cm360] is the fixed sensitivity of the rounds until the optimizer (M5) varies it. */
data class TrainerConfig(
    val dpi: Double,
    val cm360: Double,
    val profile: GameProfile,
    val roundSeconds: Int,
    val seed: Long,
    val scenario: ScenarioKind = ScenarioKind.FLICK,
    /** Raw Accel simulated in the trainer (a finder curve or a whole profile); null plays with the plain sensitivity. */
    val accel: AccelModel? = null,
    /** Target sizes and distances of the rounds; the game's own by default, null only for the fixed legacy values (old recordings). */
    val geometry: TrainerGeometry? = profile.geometry,
    /** Speed and bot movement of Range mode rounds; the other scenarios ignore it. */
    val rangeOptions: RangeOptions = RangeOptions.DEFAULT,
)

/** The trainer config for saved [settings], or null while onboarding is incomplete. */
fun trainerConfigFor(settings: AppSettings, profiles: GameProfiles, seed: Long): TrainerConfig? {
    val draft = SetupDraft.fromSettings(settings)
    if (SetupStep.entries.any { draft.issue(it, profiles) != null }) return null
    return TrainerConfig(
        dpi = checkNotNull(draft.dpi),
        cm360 = checkNotNull(draft.currentCm360(profiles)),
        profile = checkNotNull(draft.profile(profiles)),
        roundSeconds = settings.roundSeconds,
        seed = seed,
        rangeOptions = runCatching { settings.rangeOptions() }.getOrDefault(RangeOptions.DEFAULT),
    )
}
