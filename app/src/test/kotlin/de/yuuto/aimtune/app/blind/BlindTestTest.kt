package de.yuuto.aimtune.app.blind

import de.yuuto.aimtune.app.TrainerConfig
import de.yuuto.aimtune.core.metrics.computeRoundMetrics
import de.yuuto.aimtune.core.scenario.ScenarioKind
import de.yuuto.aimtune.core.scenario.ScenarioSchedule
import de.yuuto.aimtune.core.score.MetricWeights
import de.yuuto.aimtune.core.score.RoundScorer
import de.yuuto.aimtune.core.score.scoreInputs
import de.yuuto.aimtune.core.sim.HeadlessRound
import de.yuuto.aimtune.core.sim.SimulatedPlayer
import de.yuuto.aimtune.core.units.SearchRange
import de.yuuto.aimtune.games.GameProfiles
import kotlin.math.abs
import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BlindTestTest {
    private val range = SearchRange(20.0, 120.0)
    private val player = SimulatedPlayer().withOptimum(50.0)

    /** Plays [rounds] simulated rounds and returns the test plus what it asked for. */
    private fun play(test: BlindTest, rounds: Int, seedBase: Long): List<PastRound> {
        val played = mutableListOf<PastRound>()
        repeat(rounds) { i ->
            val cm360 = test.nextCm360()
            val metrics = computeRoundMetrics(HeadlessRound.flick(player, 800.0, cm360, seedBase + i, roundSeconds = 30))
            test.recordRound(cm360, metrics)
            played += PastRound(1, cm360, metrics.scoreInputs())
        }
        return played
    }

    @Test
    fun `a run reports its progress and a result after a dozen rounds`() {
        BlindTest(range, RoundScorer(MetricWeights.DEFAULT), emptyList(), seed = 1).use { test ->
            assertEquals(0, test.roundsPlayed)
            play(test, 12, seedBase = 100)
            assertEquals(12, test.roundsPlayed)
            assertEquals(12, test.totalRounds)
            assertEquals(1, test.sessionCount)
            val outcome = assertNotNull(test.lastOutcome)
            assertTrue(outcome.shots >= outcome.hits)
            val result = assertNotNull(test.step().result)
            assertTrue(result.optimumCm360 in range)
            assertEquals(12, result.rounds)
            assertTrue(test.nextCm360() in range)
        }
    }

    @Test
    fun `a later run merges the rounds of the earlier one`() {
        val firstRun = BlindTest(range, RoundScorer(MetricWeights.DEFAULT), emptyList(), seed = 2).use { test ->
            play(test, 14, seedBase = 200)
        }
        BlindTest(range, RoundScorer(MetricWeights.DEFAULT), firstRun, seed = 3).use { second ->
            assertEquals(14, second.totalRounds)
            // Before playing anything the model already knows 14 rounds and suggests from them.
            assertTrue(second.nextCm360() in range)
            play(second, 6, seedBase = 300)
            val result = assertNotNull(second.step().result)
            assertEquals(20, result.rounds)
            assertEquals(2, result.sessions)
            assertTrue(abs(ln(result.optimumCm360 / 50.0)) < 0.5, "merged optimum ${result.optimumCm360}")
        }
    }

    @Test
    fun `a run that rotates scenarios scores each against its own rounds and keeps the outcome per scenario`() {
        val schedule = ScenarioSchedule(ScenarioKind.entries.toSet(), MetricWeights.DEFAULT)
        BlindTest(range, RoundScorer(MetricWeights.DEFAULT), emptyList(), seed = 5).use { test ->
            repeat(16) { i ->
                val kind = schedule.kindFor(i)
                val cm360 = test.nextCm360()
                val metrics = computeRoundMetrics(HeadlessRound.of(kind, player, 800.0, cm360, 600L + i, roundSeconds = 20))
                test.recordRound(cm360, metrics, kind)
                val outcome = assertNotNull(test.lastOutcome)
                assertEquals(kind, outcome.scenario)
                assertEquals(kind == ScenarioKind.TRACKING, outcome.trackingOnTargetRatio != null)
            }
            val result = assertNotNull(test.step().result)
            assertEquals(16, result.rounds)
            assertTrue(result.optimumCm360 in range)
        }
    }

    @Test
    fun `the scenario of the next round follows the schedule and the sensitivity stays in range`() {
        val schedule = ScenarioSchedule(setOf(ScenarioKind.FLICK, ScenarioKind.TRACKING), MetricWeights.DEFAULT)
        val config = TrainerConfig(800.0, 45.0, checkNotNull(GameProfiles.load().find("valorant")), 20, seed = 1)
        BlindTest(range, RoundScorer(MetricWeights.DEFAULT), emptyList(), seed = 6).use { test ->
            val run = BlindRun(test, runKey = 1, baseConfig = config, nextSeed = 100, schedule = schedule)
            repeat(6) { i ->
                val next = run.nextRoundConfig()
                assertEquals(schedule.kindFor(i), next.scenario)
                assertTrue(next.cm360 in range)
                assertEquals(100L + i, next.seed)
                test.recordRound(next.cm360, computeRoundMetrics(HeadlessRound.of(next.scenario, player, 800.0, next.cm360, next.seed, roundSeconds = 10)), next.scenario)
            }
        }
    }

    @Test
    fun `stored rounds alone give a result for the results screen`() {
        val rounds = BlindTest(range, RoundScorer(MetricWeights.DEFAULT), emptyList(), seed = 4).use { play(it, 12, seedBase = 400) }
        val result = assertNotNull(BlindTest.analyzeStored(range, RoundScorer(MetricWeights.DEFAULT), rounds))
        assertEquals(12, result.rounds)
        assertNull(BlindTest.analyzeStored(range, RoundScorer(MetricWeights.DEFAULT), emptyList()))
        assertNull(BlindTest.analyzeStored(range, RoundScorer(MetricWeights.DEFAULT), rounds.take(2)))
    }
}
