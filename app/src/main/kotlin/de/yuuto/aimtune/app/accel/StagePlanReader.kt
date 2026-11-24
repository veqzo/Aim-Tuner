package de.yuuto.aimtune.app.accel

import de.yuuto.aimtune.app.engagementsOf
import de.yuuto.aimtune.core.metrics.computeRoundMetrics
import de.yuuto.aimtune.core.accel.AccelAnalysis
import de.yuuto.aimtune.core.accel.AccelCurve
import de.yuuto.aimtune.core.accel.AccelSpec
import de.yuuto.aimtune.core.accel.FeltStage
import de.yuuto.aimtune.core.accel.FeltStep
import de.yuuto.aimtune.core.accel.FeltStepFamily
import de.yuuto.aimtune.core.accel.LookupCurve
import de.yuuto.aimtune.core.accel.PlanState
import de.yuuto.aimtune.core.accel.RawAccelProfile
import de.yuuto.aimtune.core.accel.RungSession
import de.yuuto.aimtune.core.accel.StagePlan
import de.yuuto.aimtune.core.accel.StageSession
import de.yuuto.aimtune.core.accel.movementSamples
import de.yuuto.aimtune.core.accel.stageMisfit
import de.yuuto.aimtune.core.score.DuelScorer
import de.yuuto.aimtune.core.score.MetricWeights
import de.yuuto.aimtune.core.score.RoundScorer
import de.yuuto.aimtune.storage.RoundRepository
import de.yuuto.aimtune.storage.RoundRow
import de.yuuto.aimtune.storage.scoreInputs
import kotlin.math.abs

/**
 * Where the stage plan stands (docs/FIND-CURVE-REDESIGN.md section 13): the rung to play next (0 is the player's own curve, which also
 * means the plan ended), the stage of that rung as it was first stored, and how many sessions each rung has.
 */
data class StageProgress(
    val state: PlanState,
    /** The stored table of the rung in [state], word for word; null on rung 0 or when no session was played on it yet. */
    val stage: LookupCurve?,
    val anchors: FeltStage?,
    val sessionsByRung: Map<Int, Int>,
)

/**
 * Reads the stage plan from the stored rounds. A round stores the curve it was played with; a round of the player's own curve is
 * rung 0 and a round of a felt step table of this base ([FeltStepFamily.stageOf]) is the rung of its step on [FeltStep.LADDER].
 * Rounds of other curves, DPIs and sensitivities are not part of the plan. The anchors of the first stored stage stay the plan's.
 */
object StagePlanReader {
    private const val MAX_ROUNDS = 300

    fun read(
        repo: RoundRepository,
        base: RawAccelProfile,
        dpi: Double,
        cm360: Double,
        weights: MetricWeights,
        maxStep: Double = Double.POSITIVE_INFINITY,
        duel: DuelScorer? = null,
    ): StageProgress? {
        val family = FeltStepFamily(base, dpi)
        val baseSpec = AccelSpec.encodeModel(base)
        val scenarios = AccelRun.CLICK_SCENARIOS.map { it.id }.toSet()
        val sessionDpi = repo.sessions().filter { abs(it.dpi - dpi) < 0.5 }.associate { it.id to it.startedAtMs }
        val rounds = sessionDpi.keys.flatMap { repo.rounds(it) }.filter {
            it.accel != null && it.scenario in scenarios && abs(it.cm360 - cm360) <= cm360 * PriorCalibration.CM360_TOLERANCE
        }.takeLast(MAX_ROUNDS)

        val rungOf = HashMap<String, Rung?>()
        fun classify(spec: String): Rung? = rungOf.getOrPut(spec) {
            if (spec == baseSpec) return@getOrPut Rung(0, null, null)
            val curve = try { AccelSpec.decode(spec) } catch (e: IllegalArgumentException) { return@getOrPut null } as? LookupCurve
                ?: return@getOrPut null
            val stage = family.stageOf(curve) ?: return@getOrPut null
            val index = FeltStep.LADDER.indexOfFirst { abs(it - stage.step) < RUNG_TOLERANCE }
            if (index < 0) null else Rung(index + 1, stage, curve)
        }
        val planned = rounds.mapNotNull { row -> classify(checkNotNull(row.accel))?.let { row to it } }
        if (planned.none { it.second.rung > 0 }) return null

        val engagements = if (duel == null) null else planned.map { repo.engagementsOf(it.first) }
        val scores = RoundScorer(weights, duel).scoreRounds(planned.map { it.first.scoreInputs() }, planned.map { it.first.scenario }, engagements)
        val firstStage = planned.filter { it.second.rung > 0 }.minBy { it.first.startedAtMs }.second
        val anchors = checkNotNull(firstStage.stage)

        val history = planned.indices.groupBy { planned[it].first.sessionId to planned[it].second.rung }.map { (key, indices) ->
            val (sessionId, rung) = key
            val delivered: (Double) -> Double = if (rung == 0) { speed -> base.planningGain(speed, dpi) } else { speed -> checkNotNull(planned[indices.first()].second.curve).multiplier(speed) }
            val rows = indices.map { planned[it].first }
            RungSession(rung, StageSession(misfitOf(repo, rows, delivered, dpi, anchors), scores.slice(indices).filterNotNull().averageOrNaN(), rows.size, sessionDpi.getValue(sessionId)))
        }.filterNot { it.session.score.isNaN() }

        val state = StagePlan.replay(history) { rung -> rung < FeltStep.LADDER.size && FeltStep.LADDER[rung] <= maxStep }
        val played = if (state.rung > 0) {
            planned.firstOrNull { it.second.rung == state.rung }?.second?.curve as? LookupCurve
                ?: family.table(FeltStep.LADDER[state.rung - 1], anchors.anchorSlow, anchors.anchorFast)
        } else {
            null
        }
        return StageProgress(state, played, if (state.rung > 0) anchors else null, history.groupingBy { it.rung }.eachCount())
    }

    private class Rung(val rung: Int, val stage: FeltStage?, val curve: AccelCurve?)

    private fun misfitOf(repo: RoundRepository, rows: List<RoundRow>, delivered: (Double) -> Double, dpi: Double, anchors: FeltStage) =
        movementSamples(rows.mapNotNull { row -> repo.loadRecording(row.id)?.let { runCatching { computeRoundMetrics(it) }.getOrNull() } }, dpi)
            .let { samples -> stageMisfit(AccelAnalysis(samples, delivered = delivered), delivered, anchors.anchorSlow, anchors.anchorFast) }

    private fun List<Double>.averageOrNaN(): Double = if (isEmpty()) Double.NaN else average()

    private const val RUNG_TOLERANCE = 5e-3
}
