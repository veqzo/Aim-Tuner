package de.yuuto.aimtune.core.accel

import kotlin.math.sqrt

/** One session played on a stage (or on the player's own curve, as reference): its misfit, its mean score and when it started. */
data class StageSession(val misfit: StageMisfit?, val score: Double, val rounds: Int, val atMillis: Long) {
    /** Enough rounds and bins to say anything. */
    val valid: Boolean get() = misfit != null && rounds >= StagePlan.MIN_ROUNDS
}

enum class StageAction {
    /** Go to the next rung. */
    ADVANCE,

    /** Play this stage on. */
    HOLD,

    /** The stage does not suit the hand: back to the one before (or the player's own curve). */
    BACK,

    /** The last rung the plan may reach: this stage is the result. */
    TOP,
}

/** A session in time order and the rung it was played on: 0 is the player's own curve, 1 the first rung of the ladder and so on. */
data class RungSession(val rung: Int, val session: StageSession)

/** Where a plan stands after its history: the rung to play next, the sessions on it since it was entered, and what the evidence says. */
data class PlanState(val rung: Int, val window: List<StageSession>, val decision: StageDecision?)

/** Why a plan holds, for the text shown to the player. */
enum class HoldReason { ROUNDS, SESSIONS, BASELINE, ADAPTING }

data class StageDecision(val action: StageAction, val reason: HoldReason? = null)

/**
 * The rule to go up one rung of the stage plan (docs/FIND-CURVE-REDESIGN.md section 13). A stage is passed on when (i) the hand has
 * come toward it, or is as used to it as it was to the stage before, and (ii) the score has not fallen below the player's own score
 * over time. It goes back when the hand stays away from it without coming closer, or the score falls twice in a row. Pure function of the evidence; the
 * thresholds are heuristics that real sessions have to confirm.
 */
object StagePlan {
    const val MIN_ROUNDS = 8
    const val MIN_SESSIONS = 2
    const val MIN_BASELINE_SESSIONS = 2
    const val BACK_SESSIONS = 3
    const val MIN_GAP_MILLIS = 8L * 60 * 60 * 1000

    /** Score points (the effect limit of the blind test) the score may lie under the player's own before the stage is a worse one. */
    const val SCORE_MARGIN = 2.0
    const val APPROACH_SE = 1.0
    const val BACK_SE = 2.0
    const val BASELINE_SESSIONS = 5

    /**
     * [current] are the sessions on the stage in time order, [reference] those on the stage before it (the player's own curve for the
     * first stage), [baselineScores] the scores of the last sessions on the reference, and [hasNext] whether a next rung exists
     * and is allowed by the error spread.
     */
    fun decide(current: List<StageSession>, reference: List<StageSession>, baselineScores: List<Double>, hasNext: Boolean): StageDecision {
        val valid = current.filter { it.valid }
        if (valid.isEmpty()) return StageDecision(StageAction.HOLD, HoldReason.ROUNDS)
        val baseline = baselineScores.takeIf { it.size >= MIN_BASELINE_SESSIONS }?.let(::median)
        val late = valid.takeLast(MIN_SESSIONS)
        val lateMisfit = pooled(late)
        val refMisfit = pooled(reference.filter { it.valid }.takeLast(MIN_SESSIONS))

        val first = valid.first().misfit
        val approached = lateMisfit != null && first != null && lateMisfit.contrast - first.contrast >= APPROACH_SE * combined(lateMisfit, first)
        val scoreFellTwice = baseline != null && valid.size >= 2 && valid.takeLast(2).all { it.score < baseline - SCORE_MARGIN }
        val staysAway = !approached && valid.size >= BACK_SESSIONS && lateMisfit != null && refMisfit != null &&
            lateMisfit.contrast < refMisfit.contrast - BACK_SE * combined(lateMisfit, refMisfit)
        if (scoreFellTwice || staysAway) return StageDecision(StageAction.BACK)

        if (valid.size < MIN_SESSIONS || valid.last().atMillis - valid.first().atMillis < MIN_GAP_MILLIS) {
            return StageDecision(StageAction.HOLD, HoldReason.SESSIONS)
        }
        if (baseline == null) return StageDecision(StageAction.HOLD, HoldReason.BASELINE)

        val asUsed = lateMisfit != null && refMisfit != null && lateMisfit.contrast >= refMisfit.contrast - APPROACH_SE * combined(lateMisfit, refMisfit)
        val scoreHolds = late.map { it.score }.average() >= baseline - SCORE_MARGIN
        if (!(approached || asUsed) || !scoreHolds) return StageDecision(StageAction.HOLD, HoldReason.ADAPTING)
        return StageDecision(if (hasNext) StageAction.ADVANCE else StageAction.TOP)
    }

    /**
     * Replays the [history] in time order: the plan starts on rung 1 and every session on the current rung is weighed by [decide]; a
     * rung that passes moves the plan up, one that goes back moves it down (to rung 0, the player's own curve, which ends the plan).
     * Sessions on other rungs only serve as reference and baseline. [hasNext] says whether a rung above the given one may be offered.
     */
    fun replay(history: List<RungSession>, hasNext: (Int) -> Boolean): PlanState {
        var rung = 1
        var window = emptyList<StageSession>()
        var decision: StageDecision? = null
        for (entry in history.sortedBy { it.session.atMillis }) {
            if (rung == 0) break
            if (entry.rung != rung) continue
            window = window + entry.session
            val seen = history.filter { it.session.atMillis <= entry.session.atMillis }
            val reference = seen.filter { it.rung == rung - 1 }.map { it.session }
            decision = decide(window, reference, reference.takeLast(BASELINE_SESSIONS).map { it.score }, hasNext(rung))
            when (decision.action) {
                StageAction.ADVANCE -> { rung += 1; window = emptyList() }
                StageAction.BACK -> { rung -= 1; window = emptyList() }
                StageAction.HOLD, StageAction.TOP -> Unit
            }
        }
        return PlanState(rung, window, decision)
    }

    private fun combined(a: StageMisfit, b: StageMisfit) = sqrt(a.standardError * a.standardError + b.standardError * b.standardError)

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        return if (sorted.size % 2 == 1) sorted[sorted.size / 2] else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
    }

    /** The sessions' misfits pooled with inverse-variance weights; null without any. */
    private fun pooled(sessions: List<StageSession>): StageMisfit? {
        val misfits = sessions.mapNotNull { it.misfit }.filter { it.standardError > 0.0 }
        if (misfits.isEmpty()) return null
        val weights = misfits.map { 1.0 / (it.standardError * it.standardError) }
        val total = weights.sum()
        return StageMisfit(misfits.indices.sumOf { weights[it] * misfits[it].contrast } / total, 1.0 / sqrt(total))
    }
}
