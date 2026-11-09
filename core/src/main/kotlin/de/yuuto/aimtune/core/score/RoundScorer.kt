package de.yuuto.aimtune.core.score

import de.yuuto.aimtune.core.scenario.ScenarioKind
import de.yuuto.aimtune.core.scenario.TargetEvent

/**
 * The score both finders maximize. Without [duel] every round is scored by [weights] ([ScoreCalculator]). With [duel] (a game
 * with duel rules, M23g4) a round that has engagements is scored by its duel-win chance ([DuelScorer]), and the others
 * (tracking) keep the weights. Both scores use per-scenario baselines, and a scenario is either a click or a tracking
 * scenario, so the two scores never share a baseline.
 */
class RoundScorer(val weights: MetricWeights, private val duel: DuelScorer? = null) {
    /**
     * One score per round, or null when the round has no usable baseline yet. [engagements] holds each round's engagements,
     * null for a round that has none to score (tracking, or a caller without events); without [duel] it is not used.
     */
    fun scoreRounds(
        inputs: List<Map<ScoreMetric, Double>>,
        scenarios: List<String>?,
        engagements: List<List<Engagement>?>? = null,
    ): List<Double?> {
        val metric = ScoreCalculator(weights).scoreRounds(inputs, scenarios)
        if (duel == null || engagements == null) return metric
        require(engagements.size == inputs.size) { "one engagement list per round expected" }
        val scenarioOf = scenarios ?: List(inputs.size) { "" }
        val duelled = inputs.indices.filter { engagements[it] != null }
        val duelScores = duel.scoreRounds(duelled.map { checkNotNull(engagements[it]) }, duelled.map { scenarioOf[it] })
        val byRound = duelled.zip(duelScores).toMap()
        return inputs.indices.map { if (it in byRound) byRound[it] else metric[it] }
    }
}

/**
 * The engagements of a round of [scenario] for the duel score; null for tracking, which has no first shots to duel with.
 * [roundEndNanos] is the end of the round, so that a reach the round end cut off can count as a lost duel when it was too long.
 */
fun duelEngagements(scenario: ScenarioKind, events: List<TargetEvent>, roundEndNanos: Long?): List<Engagement>? =
    if (scenario == ScenarioKind.TRACKING) null else engagements(events, roundEndNanos)
