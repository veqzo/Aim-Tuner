package dev.veqzo.aimtune.core.metrics

import dev.veqzo.aimtune.core.analysis.Submovement
import dev.veqzo.aimtune.core.math.ViewAngles
import dev.veqzo.aimtune.core.recording.RoundRecording
import dev.veqzo.aimtune.core.recording.ViewTrack
import dev.veqzo.aimtune.core.scenario.TargetEvent

/** What the player did about one flash. */
data class FlashOutcome(
    val flashId: Int,
    val dodged: Boolean,
    /** Cue until the flash was beyond the safe angle; null if it never was before the pop. */
    val turnNanos: Long?,
    /** Peak hand speed of the fastest submovement between the cue and the pop, in counts per ms; null if the player did not move. */
    val turnPeakSpeedCountsPerMs: Double?,
)

/** The flash events of a round matched with the player's view; the return hit is measured as an ordinary movement. */
data class FlashMetrics(val flashes: List<FlashOutcome>) {
    /** Share of flashes the player had turned away from when they popped; null for a round without a completed flash. */
    val dodgeRate: Double? get() = if (flashes.isEmpty()) null else flashes.count { it.dodged }.toDouble() / flashes.size

    val meanTurnMs: Double? get() = flashes.mapNotNull { it.turnNanos }.takeIf { it.isNotEmpty() }?.average()?.div(1e6)

    val meanTurnPeakSpeed: Double? get() = flashes.mapNotNull { it.turnPeakSpeedCountsPerMs }.takeIf { it.isNotEmpty() }?.average()
}

internal fun computeFlashMetrics(recording: RoundRecording, track: ViewTrack, submovements: List<Submovement>): FlashMetrics {
    val pops = recording.targetEvents.filterIsInstance<TargetEvent.FlashPop>().associateBy { it.flashId }
    val outcomes = recording.targetEvents.filterIsInstance<TargetEvent.FlashCue>().mapNotNull { cue ->
        val pop = pops[cue.flashId] ?: return@mapNotNull null
        val direction = ViewAngles.directionOf(cue.yawDegrees, cue.pitchDegrees)
        val turned = recording.mouseEvents.firstOrNull { event ->
            event.timeNanos in cue.timeNanos..cue.popNanos &&
                track.viewAt(event.timeNanos).direction().angleToDegrees(direction) >= cue.safeAngleDegrees
        }
        val peak = submovements.filter { it.peakNanos in cue.timeNanos..cue.popNanos }.maxOfOrNull { it.peakSpeedCountsPerMs }
        FlashOutcome(cue.flashId, pop.dodged, turned?.let { it.timeNanos - cue.timeNanos }, peak)
    }
    return FlashMetrics(outcomes)
}
