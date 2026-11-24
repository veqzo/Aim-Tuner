package dev.veqzo.aimtune.core.scenario

/** How long an unshot bot stays: never (the default of the range), or one of the speed presets of docs/RANGE.md. */
enum class RangeSpeed(val id: String) {
    NONE("none"),
    EASY("easy"),
    MEDIUM("medium"),
    HARD("hard"),
    ;

    companion object {
        fun fromId(id: String): RangeSpeed? = entries.firstOrNull { it.id == id }
    }
}

/** The settings of a Range round the player can change; the rest is the game profile's [RangeGeometry]. */
data class RangeOptions(
    val speed: RangeSpeed = RangeSpeed.NONE,
    /** Bots strafe (move, stop, move); false is the range's "Reset" setting, bots stand still. */
    val botsStrafe: Boolean = true,
    /** Bot armor on: a head hit kills, body hits need [RangeGeometry.bodyHitsToKillArmorOn]. */
    val armor: Boolean = true,
) {
    companion object {
        val DEFAULT = RangeOptions()
    }
}

/**
 * Range mode in the game's terms (docs/RANGE.md, "Behaviour spec"): one bot at a time, spawning a short while after the
 * last one was killed at a bearing and distance from the counter, strafing like the range's bots, with a head and a body to
 * hit. Everything here is a measurement from the user's clips or an assumption, and [verified] says it has not been checked
 * in the game; the profile's `range` block names the source of each value.
 */
data class RangeGeometry(
    /** Time from a kill (or a despawn) to the next bot. */
    val spawnDelaySeconds: PositiveRange = PositiveRange(0.2, 0.8),
    /** Lifetime of an unshot bot per speed preset; [RangeSpeed.NONE] has none. */
    val easyLifetimeSeconds: Double = 3.0,
    val mediumLifetimeSeconds: Double = 2.0,
    val hardLifetimeSeconds: Double = 1.2,
    /** Bearing of a new bot from the counter's forward direction (positive is right), uniform. */
    val bearingMinDegrees: Double = -50.0,
    val bearingMaxDegrees: Double = 80.0,
    /** Distance of a new bot on the floor from the player, log-uniform. */
    val distanceMeters: PositiveRange = PositiveRange(5.0, 20.0),
    val botHeightMeters: Double = 1.8,
    /** Width of the head sphere that is hit; its angular size follows from the distance. */
    val headDiameterMeters: Double = 0.25,
    /** Strafe of a bot; the yaw limit is the game's screen, so a bot may leave the screen like the range's bots. */
    val strafeSpeedMetersPerSecond: Double = 2.0,
    val strafeMovingSeconds: PositiveRange = PositiveRange(0.25, 0.45),
    val strafeStopSeconds: PositiveRange = PositiveRange(0.3, 0.7),
    val strafeReverseProbability: Double = 0.6,
    val strafeMaxOffsetMeters: Double = 2.0,
    /** A head hit kills the bot (with armor on and off). If false a head hit counts as a body hit. */
    val headHitKills: Boolean = true,
    val bodyHitsToKillArmorOn: Int = 4,
    val bodyHitsToKillArmorOff: Int = 3,
    val verified: Boolean = false,
) {
    init {
        require(bearingMinDegrees < bearingMaxDegrees && bearingMinDegrees >= -180.0 && bearingMaxDegrees <= 180.0) { "invalid bearing range" }
        require(botHeightMeters in 1.0..3.0) { "bot height must be in 1..3 m" }
        require(headDiameterMeters > 0.0 && headDiameterMeters < botHeightMeters / 3.0) { "invalid head width" }
        require(easyLifetimeSeconds > 0.0 && mediumLifetimeSeconds > 0.0 && hardLifetimeSeconds > 0.0) { "lifetimes must be positive" }
        require(bodyHitsToKillArmorOn >= 1 && bodyHitsToKillArmorOff >= 1) { "a bot needs at least one body hit to die" }
        require(strafeSpeedMetersPerSecond > 0.0 && strafeMaxOffsetMeters > 0.0 && strafeReverseProbability in 0.0..1.0) { "invalid strafe" }
    }

    fun lifetimeSeconds(speed: RangeSpeed): Double? = when (speed) {
        RangeSpeed.NONE -> null
        RangeSpeed.EASY -> easyLifetimeSeconds
        RangeSpeed.MEDIUM -> mediumLifetimeSeconds
        RangeSpeed.HARD -> hardLifetimeSeconds
    }

    fun bodyHitsToKill(armor: Boolean): Int = if (armor) bodyHitsToKillArmorOn else bodyHitsToKillArmorOff

    val shape: BotShape get() = BotShape(botHeightMeters, headDiameterMeters)

    /** The strafe of the bots; they may walk out of the picture, so the yaw limit is wide. */
    fun strafeConfig(): StrafeConfig = StrafeConfig(
        speedMetersPerSecond = strafeSpeedMetersPerSecond,
        movingSeconds = strafeMovingSeconds,
        stopSeconds = strafeStopSeconds,
        reverseProbability = strafeReverseProbability,
        maxOffsetMeters = strafeMaxOffsetMeters,
        maxAbsYawDegrees = 90.0,
    )
}
