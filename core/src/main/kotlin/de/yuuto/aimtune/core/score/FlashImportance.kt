package de.yuuto.aimtune.core.score

/**
 * How much the player cares about turning away from flashes and coming back. It is the weight of
 * [ScoreMetric.FLASH_DODGE] next to the default weights (accuracy 40, time to hit 30, tracking 20), and
 * through the weights it decides how often flash rounds are played: 0 %, 18 % and 33 % of the rounds
 * when flicks are the only other scenario. A game with a scenario mix (M23) uses [mixParts] instead:
 * the flash rounds' parts next to the mix's parts (Valorant: flick 4, strafe 1, so 0 %, 17 % and 29 %).
 */
enum class FlashImportance(val weight: Double, val mixParts: Double) {
    NEVER(0.0, 0.0),
    SOMETIMES(15.0, 1.0),
    OFTEN(35.0, 2.0),
    ;

    companion object {
        /** The importance closest to [weight]; a missing weight means [NEVER]. */
        fun of(weight: Double?): FlashImportance = entries.minBy { kotlin.math.abs(it.weight - (weight ?: 0.0)) }
    }
}
