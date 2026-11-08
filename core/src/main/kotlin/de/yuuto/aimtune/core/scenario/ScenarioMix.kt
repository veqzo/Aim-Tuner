package de.yuuto.aimtune.core.scenario

/**
 * How often a game's engagement types occur (M23), as parts of the rounds: [standingParts] for opponents that stand
 * ([ScenarioGroup.STANDING]), [movingParts] for opponents that move ([ScenarioGroup.MOVING]) and [trackingParts] for
 * tracking. Flash rounds are not part of the mix: their parts come from the player's flash importance. The profile's
 * `mix` block names the source of each value. With a mix the schedule uses these parts instead of the metric weights.
 */
data class ScenarioMix(
    val standingParts: Double,
    val movingParts: Double,
    val trackingParts: Double = 0.0,
    val verified: Boolean = false,
) {
    init {
        require(listOf(standingParts, movingParts, trackingParts).all { it.isFinite() && it >= 0.0 }) { "mix parts must not be negative" }
        require(standingParts + movingParts + trackingParts > 0.0) { "a mix needs at least one part" }
    }

    /** The parts of [group]; [flashParts] is the player's flash importance in parts. */
    fun partsOf(group: ScenarioGroup, flashParts: Double): Double = when (group) {
        ScenarioGroup.STANDING -> standingParts
        ScenarioGroup.MOVING -> movingParts
        ScenarioGroup.TRACKING -> trackingParts
        ScenarioGroup.FLASH -> flashParts
    }
}

/** The engagement type a scenario stands for; a [ScenarioMix] gives each group its parts. */
enum class ScenarioGroup { STANDING, MOVING, TRACKING, FLASH }

/** The engagement type of this scenario's targets. */
val ScenarioKind.group: ScenarioGroup
    get() = when (this) {
        ScenarioKind.FLICK, ScenarioKind.MICRO, ScenarioKind.SWITCHING -> ScenarioGroup.STANDING
        ScenarioKind.MICRO_STRAFE, ScenarioKind.SWITCH_STRAFE, ScenarioKind.RANGE -> ScenarioGroup.MOVING
        ScenarioKind.TRACKING -> ScenarioGroup.TRACKING
        ScenarioKind.FLASH -> ScenarioGroup.FLASH
    }
