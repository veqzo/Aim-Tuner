package de.yuuto.aimtune.core.accel

import de.yuuto.aimtune.core.score.MetricWeights
import de.yuuto.aimtune.core.score.ScoreMetric
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The player's ratings of refine rounds: logged next to the blind factor, grouped by how far it was from the curve, never part of the score. */
class RefineFeelTest {
    private val user = RawAccelProfile(
        "default", RawAccelArgs(RawAccelMode.CLASSIC, gain = true, inputOffset = 10.0, capX = 62.5, capY = 1.6, capMode = RawAccelCapMode.IN_OUT), 600.0, 800.0,
    )

    private fun engine() = RefineEngine(user, 48.0, MetricWeights.DEFAULT, seed = 5)

    private val inputs = mapOf(ScoreMetric.ACCURACY to 0.7, ScoreMetric.TIME_TO_HIT to 600.0)

    @Test
    fun `a round can be rated once, after it was played`() {
        val e = engine()
        assertFalse(e.canVote, "nothing played yet")
        assertFailsWith<IllegalStateException> { e.vote(FeelVote.BETTER) }
        e.nextRound()
        e.record(inputs)
        assertTrue(e.canVote)
        e.vote(FeelVote.WORSE)
        assertFalse(e.canVote, "rated")
        assertEquals(FeelVote.WORSE, e.rounds.single().vote)
        assertEquals(RefinePhase.WARMUP, e.rounds.single().phase)
    }

    @Test
    fun `search rounds log their parameter and factor, and the ratings group by band`() {
        val e = engine()
        repeat(e.warmupRounds) { e.nextRound(); e.record(inputs) }
        val votes = listOf(FeelVote.BETTER, FeelVote.SAME, FeelVote.WORSE)
        repeat(9) { i ->
            e.nextRound()
            e.record(inputs)
            e.vote(votes[i % 3])
        }
        val search = e.rounds.filter { it.phase == RefinePhase.SEARCH }
        assertEquals(9, search.size)
        assertTrue(search.all { it.parameter == RefineParameter.HIGH_GAIN && it.factor != null && it.vote != null })
        val byBand = RefineFeel.byBand(e.rounds, RefineParameter.HIGH_GAIN)
        assertEquals(9, byBand.values.sumOf { it.total })
        assertTrue(RefineFeel.byBand(e.rounds, RefineParameter.TRANSITION).isEmpty())
    }

    @Test
    fun `a band leans only with enough ratings that are clearly better than worse and more so than the others`() {
        fun counts(vararg pairs: Pair<FactorBand, FeelCount>) = pairs.toMap()
        assertEquals(FactorBand.STRONGER, RefineFeel.leaning(counts(FactorBand.STRONGER to FeelCount(better = 4, worse = 1), FactorBand.LIKE_YOURS to FeelCount(better = 1, worse = 1))))
        assertNull(RefineFeel.leaning(counts(FactorBand.STRONGER to FeelCount(better = 2, worse = 0))), "too few ratings")
        assertNull(RefineFeel.leaning(counts(FactorBand.STRONGER to FeelCount(better = 3, same = 1, worse = 3))), "no clear surplus")
        assertNull(RefineFeel.leaning(counts(FactorBand.STRONGER to FeelCount(better = 5, worse = 1), FactorBand.WEAKER to FeelCount(better = 5, worse = 1))), "two bands lean the same")
    }

    @Test
    fun `factors are banded around one`() {
        assertEquals(FactorBand.WEAKER, FactorBand.of(0.7))
        assertEquals(FactorBand.LIKE_YOURS, FactorBand.of(0.95))
        assertEquals(FactorBand.LIKE_YOURS, FactorBand.of(1.1))
        assertEquals(FactorBand.STRONGER, FactorBand.of(1.3))
    }

    @Test
    fun `the gain stage now searches half to double`() {
        assertEquals(0.5, RefineParameter.HIGH_GAIN.min)
        assertEquals(2.0, RefineParameter.HIGH_GAIN.max)
        val adjusted = user.adjusted(RefineParameter.HIGH_GAIN, 2.0)
        assertEquals(1.0 + 0.6 * 2.0, adjusted.args.capY, 1e-12)
        assertEquals(user.outputDpi, adjusted.outputDpi)
    }
}
