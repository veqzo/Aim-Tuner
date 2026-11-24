package dev.veqzo.aimtune.core.accel

import dev.veqzo.aimtune.core.input.MouseEvent
import kotlin.math.hypot

/**
 * What the view gets from one mouse report: the movement in counts ([dx], [dy], positive y = down) and the per-report factor
 * [scale] that the analyses and `ViewTrack.scales` use (1.0 without acceleration). For the finder's curves the movement is the
 * report's counts times [scale] as doubles; for a Raw Accel profile it is the driver's integer output, and [scale] is
 * `|out| / |in|` (1.0 for a zero report).
 */
data class ModifiedMove(val dx: Double, val dy: Double, val scale: Double)

/**
 * The stateful step between the raw mouse and the view, one report at a time. A modifier holds the report clock, the smoothers and
 * the integer carry, so it is created per round ([AccelModel.newModifier]) and a stored round replays from fresh state. Not thread safe.
 */
interface MouseModifier {
    /** The movement the view gets for [event]; advances the report clock and the state. */
    fun modify(event: MouseEvent): ModifiedMove

    /** Forgets everything, as after a pause or a settings reload. */
    fun reset()
}

/**
 * What a round is played with: one of the finder's [AccelCurve]s (continuous, scale times counts) or a whole [RawAccelProfile]
 * (the driver's exact integer pipeline). Models are immutable values; the state lives in the [MouseModifier] they create.
 */
sealed interface AccelModel {
    /** A fresh modifier for a mouse of [dpi] counts per inch (a profile uses the device DPI of its own file instead). */
    fun newModifier(dpi: Double): MouseModifier

    /** The finder's curve when this model is one; null for a profile, so a curve analysis never treats a profile round as a finder round. */
    val finderCurve: AccelCurve?

    /**
     * The factor the player's planning expects at a hand speed of [speed] inches per second at [dpi]: the curve itself, or for a
     * profile the horizontal curve times output DPI / device DPI. An approximation for planning only (no smoothing, no Y/X, no
     * rotation); the movement the view gets always comes from [newModifier].
     */
    fun planningGain(speed: Double, dpi: Double): Double
}

/** The finder's arithmetic: the counts times the curve's value at the report's speed ([AccelProcessor]), unchanged. */
internal class CurveModifier(curve: AccelCurve, dpi: Double) : MouseModifier {
    private val processor = AccelProcessor(curve, dpi)

    override fun modify(event: MouseEvent): ModifiedMove {
        val scale = processor.scaleFor(event)
        return ModifiedMove(event.dx * scale, event.dy * scale, scale)
    }

    override fun reset() = processor.reset()
}

/** [RawAccelModifier] as a [MouseModifier]: integer counts with the carry, the scale is the length ratio. */
internal class ProfileModifier(profile: RawAccelProfile) : MouseModifier {
    private val driver = RawAccelModifier(profile)

    override fun modify(event: MouseEvent): ModifiedMove {
        val out = driver.modify(event)
        val inLength = hypot(event.dx.toDouble(), event.dy.toDouble())
        val scale = if (inLength == 0.0) 1.0 else hypot(out.dx.toDouble(), out.dy.toDouble()) / inLength
        return ModifiedMove(out.dx.toDouble(), out.dy.toDouble(), scale)
    }

    override fun reset() = driver.reset()
}
