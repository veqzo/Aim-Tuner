package dev.veqzo.aimtune.core.scenario

/**
 * The scenarios the trainer can run. [id] is what is stored with a round. Tracking is followed with
 * the crosshair alone (the on-target time is measured), so it does not use clicks.
 */
enum class ScenarioKind(val id: String, val displayName: String, val usesClicks: Boolean, val strafes: Boolean = false) {
    FLICK("flick", "Flick", true),
    MICRO("micro", "Micro-adjust", true),
    TRACKING("tracking", "Tracking", false),
    SWITCHING("switching", "Target switching", true),
    FLASH("flash", "Flash turn-away", true),

    /**
     * [MICRO] and [SWITCHING] with targets that strafe. Their own kinds, so their rounds have their own ids and score baselines
     * and do not mix with the static ones, and the error analysis can tell them apart (their reaches aim at moving targets).
     */
    MICRO_STRAFE("micro-strafe", "Micro-adjust (strafing)", true, strafes = true),
    SWITCH_STRAFE("switch-strafe", "Target switching (strafing)", true, strafes = true),

    /** Range mode: one bot at a time on the floor, strafing, with a head and a body (docs/RANGE.md). Uses the game's `range` block, or its defaults. */
    RANGE("range", "Range", true, strafes = true),
    ;

    /**
     * A new seeded round of this scenario. Without [geometry] the fixed legacy values are used (they
     * are what the simulation harness was calibrated on); with it the targets follow the game's
     * angular sizes, distances and speeds.
     */
    fun create(durationNanos: Long, seed: Long, geometry: TrainerGeometry? = null, rangeOptions: RangeOptions = RangeOptions.DEFAULT): Scenario = when (this) {
        FLICK -> FlickScenario(FlickConfig(durationNanos = durationNanos, geometry = geometry), seed)
        MICRO -> MicroScenario(MicroConfig(durationNanos = durationNanos, geometry = geometry), seed)
        MICRO_STRAFE -> MicroScenario(MicroConfig(durationNanos = durationNanos, geometry = geometry, strafe = strafeFor(geometry)), seed)
        TRACKING -> TrackingScenario(geometry?.let { TrackingConfig.from(it, durationNanos) } ?: TrackingConfig(durationNanos = durationNanos), seed)
        SWITCHING -> SwitchingScenario(SwitchingConfig(durationNanos = durationNanos, geometry = geometry), seed)
        SWITCH_STRAFE -> SwitchingScenario(SwitchingConfig(durationNanos = durationNanos, geometry = geometry, strafe = strafeFor(geometry)), seed)
        FLASH -> FlashScenario(durationNanos, geometry ?: TrainerGeometry.DEFAULT, seed)
        RANGE -> RangeScenario(RangeConfig(durationNanos, geometry ?: TrainerGeometry.DEFAULT, options = rangeOptions), seed)
    }

    private fun strafeFor(geometry: TrainerGeometry?): StrafeConfig = geometry?.let { StrafeConfig.from(it) } ?: StrafeConfig()

    companion object {
        fun fromId(id: String): ScenarioKind? = entries.firstOrNull { it.id == id }
    }
}
