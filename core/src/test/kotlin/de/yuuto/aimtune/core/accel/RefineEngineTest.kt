package de.yuuto.aimtune.core.accel

import de.yuuto.aimtune.core.scenario.ScenarioKind
import de.yuuto.aimtune.core.scenario.ScenarioMix
import de.yuuto.aimtune.core.score.MetricWeights
import de.yuuto.aimtune.core.score.ScoreMetric
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RefineEngineTest {
    private val user = RawAccelProfile(
        "default", RawAccelArgs(RawAccelMode.CLASSIC, gain = true, inputOffset = 10.0, capX = 62.5, capY = 1.6, capMode = RawAccelCapMode.IN_OUT), 600.0, 800.0,
    )

    private fun engine(profile: RawAccelProfile = user, flash: Boolean = false) = RefineEngine(profile, 48.0, MetricWeights.DEFAULT, seed = 5, flash = flash)

    private val random = Random(1)

    /** Scores that favour a cap output (`capY`) near [bestCap] for a played [capY], and are noise for null. */
    private fun inputs(capY: Double?, bestCap: Double) = mapOf(
        ScoreMetric.ACCURACY to 0.7 - (if (capY == null) 0.0 else 12.0 * (capY - bestCap) * (capY - bestCap)) + 0.002 * random.nextDouble(),
        ScoreMetric.TIME_TO_HIT to 600.0 + 4.0 * random.nextDouble(),
    )

    private fun playWarmup(e: RefineEngine) {
        repeat(e.warmupRounds) {
            val round = e.nextRound()
            assertEquals(user, round.profile, "the warm-up plays the imported profile")
            assertEquals(48.0, round.cm360, 1e-9, "at the game sensitivity, the output DPI factor is inside the driver model")
            e.record(inputs(null, 1.0))
        }
    }

    @Test
    fun `the run goes warm-up, two stages, test`() {
        val e = engine()
        assertEquals(listOf(RefineParameter.HIGH_GAIN, RefineParameter.TRANSITION), e.stages)
        assertEquals(RefinePhase.WARMUP, e.phase)
        playWarmup(e)
        assertEquals(RefinePhase.SEARCH, e.phase)
        assertEquals(RefineParameter.HIGH_GAIN, e.stage)
        // gain stage: the better the closer the played cap output is to 1.9
        while (e.phase == RefinePhase.SEARCH) {
            val round = e.nextRound()
            assertEquals(user.outputDpi, round.profile.outputDpi, "the base never moves")
            e.record(inputs(round.profile.args.capY, 1.9))
        }
        assertEquals(RefinePhase.STAGE_RESULT, e.phase)
        val result = assertNotNull(e.currentResult)
        assertEquals(RefineParameter.HIGH_GAIN, result.parameter)
        assertEquals(12, result.rounds)
        assertTrue(result.best in RefineParameter.HIGH_GAIN.min..RefineParameter.HIGH_GAIN.max)
        e.continueToNextStage()
        assertEquals(RefinePhase.SEARCH, e.phase)
        assertEquals(RefineParameter.TRANSITION, e.stage)
        e.finishStageNowAfter(8)
        assertEquals(RefinePhase.STAGE_RESULT, e.phase)
        e.goToTest()
        assertEquals(if (e.hasCandidate) RefinePhase.TEST else RefinePhase.DONE, e.phase)
    }

    private fun RefineEngine.skipRestOfStages() {
        while (phase == RefinePhase.SEARCH || phase == RefinePhase.STAGE_RESULT) {
            if (phase == RefinePhase.STAGE_RESULT) {
                continueToNextStage()
            } else {
                nextRound()
                record(mapOf(ScoreMetric.ACCURACY to 0.7 + 0.001 * random.nextDouble(), ScoreMetric.TIME_TO_HIT to 600.0))
            }
        }
    }

    private fun RefineEngine.finishStageNowAfter(rounds: Int) {
        repeat(rounds) {
            nextRound()
            record(inputs(null, 1.0))
        }
        finishStageNow()
    }

    @Test
    fun `a stage cannot end before its minimum`() {
        val e = engine()
        playWarmup(e)
        repeat(RefineSearch.MIN_ROUNDS - 1) {
            e.nextRound()
            e.record(inputs(null, 1.0))
        }
        assertFailsWith<IllegalStateException> { e.finishStageNow() }
    }

    @Test
    fun `a profile without a stage ends right after the warm-up`() {
        val e = engine(RawAccelProfile("flat", RawAccelArgs(RawAccelMode.NOACCEL), 800.0, 800.0))
        assertEquals(emptyList(), e.stages)
        e.skipWarmup()
        assertEquals(RefinePhase.DONE, e.phase)
        assertEquals(e.profile, e.candidate)
        assertNull(e.verdict)
    }

    @Test
    fun `a stage that finds no clear change leaves the profile as it was and the run ends without a test`() {
        val e = engine()
        e.skipWarmup()
        while (e.phase == RefinePhase.SEARCH) {
            e.nextRound()
            e.record(mapOf(ScoreMetric.ACCURACY to 0.7 + 0.001 * random.nextDouble(), ScoreMetric.TIME_TO_HIT to 600.0))
        }
        assertEquals(RefinePhase.STAGE_RESULT, e.phase)
        if (!assertNotNull(e.currentResult).excludesNeutral) {
            e.continueToNextStage()
            e.skipRestOfStages()
            if (!e.hasCandidate) {
                assertEquals(RefinePhase.DONE, e.phase)
                assertNull(e.verdict)
                assertEquals(e.profile, e.candidate)
            }
        }
    }

    @Test
    fun `the test plays the imported profile against the candidate in the same scenario with the same targets`() {
        val e = RefineEngine(user, 48.0, MetricWeights.DEFAULT, seed = 5, stageRounds = 30)
        e.skipWarmup()
        // force a candidate by running the gain stage with a strong preference
        while (e.phase == RefinePhase.SEARCH) {
            val round = e.nextRound()
            e.record(inputs(round.profile.args.capY, 1.95))
        }
        val gain = e.currentResult
        e.goToTest()
        assertEquals(RefinePhase.TEST, e.phase, "a clear gain preference gives a candidate to test: $gain")
        val first = e.nextRound()
        e.record(inputs(null, 1.0))
        val second = e.nextRound()
        assertEquals(first.scenario, second.scenario)
        assertEquals(first.seed, second.seed)
        val curves = setOf(first.profile, second.profile)
        assertEquals(2, curves.size, "one arm is the imported profile, the other the refined one")
        assertTrue(user in curves)
        assertEquals(setOf(48.0), setOf(first.cm360, second.cm360), "both arms play the game sensitivity")
    }

    @Test
    fun `a test whose rounds have no usable score ends at the last planned pair instead of asking for more`() {
        val e = RefineEngine(user, 48.0, MetricWeights.DEFAULT, seed = 5, stageRounds = 30)
        e.skipWarmup()
        while (e.phase == RefinePhase.SEARCH) {
            val round = e.nextRound()
            e.record(inputs(round.profile.args.capY, 1.95))
        }
        e.goToTest()
        assertEquals(RefinePhase.TEST, e.phase)
        var played = 0
        while (e.phase == RefinePhase.TEST) {
            check(played++ <= e.maxTestRounds) { "the test asks for rounds beyond its plan" }
            e.nextRound()
            e.record(emptyMap())
        }
        assertEquals(RefinePhase.DONE, e.phase)
        assertEquals(e.maxTestRounds, played)
    }

    @Test
    fun `flash adds flash rounds to the warm-up and the search`() {
        val e = engine(flash = true)
        val kinds = List(6) { e.nextRound().also { _ -> e.record(inputs(null, 1.0)) }.scenario }
        assertEquals(AccelScenarios.WITH_FLASH, kinds)
        assertTrue(ScenarioKind.FLASH in kinds)
    }

    @Test
    fun `a game's mix gives the curve runs flicks, both strafe scenarios and flash by importance`() {
        val mix = ScenarioMix(standingParts = 4.0, movingParts = 1.0)
        val rotation = AccelScenarios.forMix(mix, flashParts = 1.0)
        assertEquals(12, rotation.size)
        assertEquals(mapOf(ScenarioKind.FLICK to 8, ScenarioKind.MICRO_STRAFE to 1, ScenarioKind.SWITCH_STRAFE to 1, ScenarioKind.FLASH to 2), rotation.groupingBy { it }.eachCount())
        assertTrue(ScenarioKind.FLASH !in AccelScenarios.forMix(mix, flashParts = 0.0))
        val e = RefineEngine(user, 48.0, MetricWeights.DEFAULT, seed = 5, scenarioMix = rotation)
        assertEquals(rotation.take(RefineEngine.DEFAULT_WARMUP_ROUNDS), List(RefineEngine.DEFAULT_WARMUP_ROUNDS) { e.nextRound().also { _ -> e.record(inputs(null, 1.0)) }.scenario })
    }
}
