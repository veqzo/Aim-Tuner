package dev.veqzo.aimtune.core.scenario

/**
 * The flash turn-away scenario in the game's terms: how long a flash takes to pop after it becomes
 * visible, where it appears, how long a player who looks at it is blind, and where the enemy shows up
 * afterwards. Angles are degrees; times are seconds.
 *
 * The windups of the Valorant flashes (valorant.fandom.com) are Flashpoint 0.5 s, Arc Rose 0.5 s, Curveball 0.6 s,
 * Blindside 0.6 s (KAY/O's FLASH/drive 1.0 s lobbed and 1.6 s thrown, Skye's Guiding Light up to 2 s are longer) and
 * the blind durations 1.5-2.25 s
 * (docs/ALGORITHMS.md "Flash turn-away"). The time a player sees a flash before it pops also contains part
 * of its flight, which no source gives; the default range is an assumption and [verified] says so.
 */
data class FlashGeometry(
    /** Time from the moment the flash is visible until it pops. */
    val popDelaySeconds: PositiveRange = PositiveRange(0.5, 0.65),
    /** Angle between the view and the flash when it becomes visible; it always lies inside the field of view. */
    val cueOffsetDegrees: PositiveRange = PositiveRange(10.0, 40.0),
    /** The flash sits within this angle above or below the horizontal from the view. */
    val cueElevationDegrees: Double = 12.0,
    /** Distance the flash is drawn at; only the depth of the scene. */
    val distanceMeters: PositiveRange = PositiveRange(6.0, 20.0),
    /** Pause between a hit and the next flash. */
    val gapSeconds: PositiveRange = PositiveRange(0.4, 1.0),
    /** How long a player who still sees the flash when it pops cannot see the target. */
    val blindSeconds: Double = 1.5,
    /** The enemy appears within this angle of where the view was when the flash became visible. */
    val returnSpreadDegrees: Double = 8.0,
    val returnMinDegrees: Double = 1.5,
    /** Half of the horizontal field of view; the profile sets it. A flash further from the view than this is not seen. */
    val halfFovDegrees: Double = 51.5,
    /** The flash must be this far beyond the edge of the screen when it pops: its glare reaches past the edge. */
    val exitMarginDegrees: Double = 10.0,
    val verified: Boolean = false,
) {
    init {
        require(blindSeconds > 0.0 && blindSeconds <= 5.0) { "blind duration must be in (0, 5] s" }
        require(returnMinDegrees in 0.0..returnSpreadDegrees && returnSpreadDegrees <= 30.0) { "invalid return spread" }
        require(halfFovDegrees in 20.0..89.0 && exitMarginDegrees in 0.0..30.0) { "invalid field of view or margin" }
        require(cueElevationDegrees in 0.0..60.0) { "elevation must be in 0..60 degrees" }
        require(cueOffsetDegrees.max < halfFovDegrees) { "the flash must appear inside the field of view" }
    }

    /** The angle between view and flash at which the flash no longer reaches the player's eyes. */
    val safeAngleDegrees: Double get() = halfFovDegrees + exitMarginDegrees

    /** The same geometry for a game whose horizontal field of view is [fullFovDegrees]; the flash offsets stay inside it. */
    fun forHorizontalFov(fullFovDegrees: Double): FlashGeometry {
        val half = (fullFovDegrees / 2.0).coerceIn(30.0, 89.0)
        val offsets = PositiveRange(minOf(cueOffsetDegrees.min, half * 0.8), minOf(cueOffsetDegrees.max, half * 0.8))
        return copy(halfFovDegrees = half, cueOffsetDegrees = offsets)
    }
}
