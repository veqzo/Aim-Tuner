package de.yuuto.aimtune.core.scenario

/**
 * Size and speed of a player in the game (M23, docs/VALORANT-WEIGHTS.md): what a bot of the trainer should be to look and
 * move like an opponent. The scenarios do not read it yet; the movement model and the scenario sampling build on it.
 * The profile's `body` block names the source of each value, and [verified] says nobody checked it in the game.
 */
data class AgentBody(
    /** Width of the head hit sphere; its angular size follows from the distance. */
    val headDiameterMeters: Double,
    /** Height of the movement capsule, from the soles to its top. */
    val capsuleHeightMeters: Double,
    /** Eye height above the soles, standing and crouched; the head centre of a standing bot is at the standing eye height. */
    val eyeStandMeters: Double,
    val eyeCrouchMeters: Double,
    /** Top speed with a rifle (Vandal), walking and crouched. */
    val runSpeedMetersPerSecond: Double,
    val walkSpeedMetersPerSecond: Double,
    val crouchSpeedMetersPerSecond: Double,
    val verified: Boolean = false,
) {
    init {
        require(capsuleHeightMeters in 1.0..3.0) { "capsule height must be in 1..3 m, was $capsuleHeightMeters" }
        require(headDiameterMeters > 0.0 && headDiameterMeters < capsuleHeightMeters / 3.0) { "invalid head width $headDiameterMeters" }
        require(eyeCrouchMeters > 0.0 && eyeCrouchMeters < eyeStandMeters) { "crouched eye must be below the standing eye" }
        require(eyeStandMeters + headDiameterMeters / 2.0 <= capsuleHeightMeters) { "a standing head must fit in the capsule" }
        require(crouchSpeedMetersPerSecond > 0.0 && crouchSpeedMetersPerSecond <= walkSpeedMetersPerSecond && walkSpeedMetersPerSecond <= runSpeedMetersPerSecond) {
            "need 0 < crouch <= walk <= run speed"
        }
        require(runSpeedMetersPerSecond <= MAX_SPEED) { "run speed over $MAX_SPEED m/s is not a player" }
    }

    /** A standing bot whose head centre is at the standing eye height, for the hit model of [BotShape]. */
    val standingShape: BotShape get() = BotShape(eyeStandMeters + headDiameterMeters / 2.0, headDiameterMeters)

    private companion object {
        const val MAX_SPEED = 15.0
    }
}
