package de.yuuto.aimtune.core.score

import de.yuuto.aimtune.core.scenario.ScenarioKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The finders' score (M23g4): duel wins for click rounds of a game with duel rules, the metric weights for the rest. */
class RoundScorerTest {
    private val weights = MetricWeights(mapOf(ScoreMetric.ACCURACY to 40.0, ScoreMetric.TIME_TO_HIT to 30.0, ScoreMetric.TRACKING to 20.0))
    private val ms = 1_000_000L

    private fun hit(t: Long) = Engagement(t * ms, firstShotHit = true, lost = false)
    private fun miss(t: Long) = Engagement(t * ms, firstShotHit = false, lost = false)

    // Three flick rounds: the fast, accurate one must win; two tracking rounds and one more at the end.
    private val engagements = listOf(
        listOf(hit(300), hit(320), hit(310)),
        listOf(hit(400), miss(380), hit(420)),
        listOf(hit(500), hit(520), miss(450)),
        null,
        null,
        null,
    )
    private val scenarios = listOf("flick", "flick", "flick", "tracking", "tracking", "tracking")
    private val inputs = listOf(
        mapOf(ScoreMetric.ACCURACY to 0.5, ScoreMetric.TIME_TO_HIT to 900.0),
        mapOf(ScoreMetric.ACCURACY to 0.9, ScoreMetric.TIME_TO_HIT to 700.0),
        mapOf(ScoreMetric.ACCURACY to 0.8, ScoreMetric.TIME_TO_HIT to 800.0),
        mapOf(ScoreMetric.TRACKING to 0.4),
        mapOf(ScoreMetric.TRACKING to 0.6),
        mapOf(ScoreMetric.TRACKING to 0.5),
    )

    @Test
    fun `without duel rules every round keeps the metric score`() {
        val expected = ScoreCalculator(weights).scoreRounds(inputs, scenarios)
        assertEquals(expected, RoundScorer(weights).scoreRounds(inputs, scenarios, engagements))
    }

    @Test
    fun `with duel rules click rounds score duel wins and tracking rounds keep the weights`() {
        val scores = RoundScorer(weights, DuelRules(308 * ms).scorer()).scoreRounds(inputs, scenarios, engagements)
        val duel = DuelScorer(308 * ms).scoreRounds(engagements.take(3).map { checkNotNull(it) }, scenarios.take(3))
        assertEquals(duel, scores.take(3))
        // The metrics prefer round 2 (accurate and quick to hit); the duels prefer round 1, whose first shots all hit fastest.
        assertTrue(assertNotNull(scores[0]) > assertNotNull(scores[1]) && scores[1]!! > scores[2]!!, "$scores")
        assertEquals(ScoreCalculator(weights).scoreRounds(inputs, scenarios).drop(3), scores.drop(3))
    }

    @Test
    fun `tracking rounds have no engagements and a round without engagements scores nothing yet`() {
        assertNull(duelEngagements(ScenarioKind.TRACKING, emptyList()))
        assertEquals(emptyList(), duelEngagements(ScenarioKind.FLICK, emptyList()))
        val scores = RoundScorer(weights, DuelScorer()).scoreRounds(inputs.take(3), scenarios.take(3), listOf(emptyList(), engagements[1], engagements[2]))
        assertNull(scores[0])
    }
}
