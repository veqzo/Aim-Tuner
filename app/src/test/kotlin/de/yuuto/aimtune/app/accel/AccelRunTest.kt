package de.yuuto.aimtune.app.accel

import de.yuuto.aimtune.app.TrainerConfig
import de.yuuto.aimtune.core.accel.AbStatus
import de.yuuto.aimtune.core.accel.AccelAnalysis
import de.yuuto.aimtune.core.accel.AccelFit
import de.yuuto.aimtune.core.accel.AccelScenarios
import de.yuuto.aimtune.core.accel.FeltStepFamily
import de.yuuto.aimtune.core.accel.LookupCurve
import de.yuuto.aimtune.core.accel.MovementSample
import de.yuuto.aimtune.core.accel.NaturalCurve
import de.yuuto.aimtune.core.accel.RawAccelArgs
import de.yuuto.aimtune.core.accel.RawAccelCapMode
import de.yuuto.aimtune.core.accel.RawAccelMode
import de.yuuto.aimtune.core.accel.RawAccelProfile
import de.yuuto.aimtune.core.accel.trialAccel
import de.yuuto.aimtune.core.scenario.ScenarioKind
import de.yuuto.aimtune.core.score.MetricWeights
import de.yuuto.aimtune.core.sim.HeadlessRound
import de.yuuto.aimtune.core.sim.SimulatedPlayer
import de.yuuto.aimtune.games.GameProfiles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccelRunTest {
    private val base = TrainerConfig(800.0, 55.0, checkNotNull(GameProfiles.load().find("valorant")), roundSeconds = 30, seed = 0)

    @Test
    fun `the curve finder never plays range rounds`() {
        // The M15c gate: range rounds in the strength search raised the false directions for flat players (9/16 to 14/16).
        val valorantMix = checkNotNull(base.profile.geometry.mix)
        val lists = listOf(AccelRun.CLICK_SCENARIOS, AccelRun.SCENARIOS_WITH_FLASH, AccelScenarios.of(true), AccelScenarios.of(false), AccelScenarios.forMix(valorantMix, 2.0))
        lists.forEach { assertFalse(ScenarioKind.RANGE in it) }
    }

    private fun newRun() = AccelRun(base, MetricWeights.DEFAULT, runKey = 1, seed = 3)

    @Test
    fun `with a game's mix the strength search plays its rotation`() {
        val rotation = AccelScenarios.forMix(checkNotNull(base.profile.geometry.mix), flashParts = 1.0)
        val run = AccelRun(base, MetricWeights.DEFAULT, runKey = 1, seed = 5, startWithSearch = true, scenarios = rotation)
        val kinds = List(rotation.size) { run.nextRoundConfig().scenario.also { _ -> play(run, SimulatedPlayer()) } }
        assertEquals(rotation, kinds)
        assertFailsWith<IllegalArgumentException> { AccelRun(base, MetricWeights.DEFAULT, runKey = 1, seed = 5, scenarios = emptyList()) }
    }

    private fun play(run: AccelRun, player: SimulatedPlayer) {
        val next = run.nextRoundConfig()
        run.recordRound(HeadlessRound.of(next.scenario, player, next.dpi, next.cm360, next.seed, next.roundSeconds, accel = next.accel))
    }

    @Test
    fun `calibration rounds are plain, at the base sensitivity and cover all click scenarios`() {
        val run = newRun()
        val configs = (0 until run.calibrationRounds).map {
            run.nextRoundConfig().also { _ -> play(run, SimulatedPlayer()) }
        }
        assertTrue(configs.all { it.accel == null && it.cm360 == 55.0 })
        assertEquals(AccelRun.CLICK_SCENARIOS.toSet(), configs.map { it.scenario }.toSet())
        assertEquals(configs.size, configs.map { it.seed }.toSet().size, "every round has its own targets")
        assertEquals(AccelPhase.ANALYSIS, run.phase)
        assertNotNull(run.fit)
    }

    @Test
    fun `a player who expects a falling gain gets a curve that the blind test confirms`() {
        val run = sampleAccelRun(base, AccelPhase.DONE)
        assertNotNull(run.candidate)
        assertEquals(AbStatus.ACCEL_BETTER, run.verdict?.status)
        assertEquals(AccelPhase.DONE, run.phase)
        assertTrue(run.testRoundsDone in 8..run.maxTestRounds && run.testRoundsDone % 2 == 0)
    }

    @Test
    fun `the candidate is a bounded lookup table unless the legacy curve finder is chosen`() {
        fun candidateOf(bounded: Boolean): AccelFit? {
            val run = AccelRun(base, MetricWeights.DEFAULT, runKey = 1, seed = 3, boundedCurves = bounded)
            while (run.phase == AccelPhase.CALIBRATION) play(run, fallingGainPlayer)
            return run.candidate
        }
        assertTrue(candidateOf(true)?.curve is LookupCurve)
        val legacy = assertNotNull(candidateOf(false))
        assertFalse(legacy.curve is LookupCurve)
    }

    @Test
    fun `a flat player gets no curve and cannot start the test`() {
        val run = newRun()
        while (run.phase == AccelPhase.CALIBRATION) play(run, SimulatedPlayer())
        assertEquals(AccelPhase.ANALYSIS, run.phase)
        assertNull(run.candidate)
        assertFailsWith<IllegalStateException> { run.startTest() }
        assertFailsWith<IllegalStateException> { run.nextRoundConfig() }
    }

    @Test
    fun `a player can test a mild trial curve although the analysis found none`() {
        val run = newRun()
        while (run.phase == AccelPhase.CALIBRATION) play(run, SimulatedPlayer())
        assertNull(run.candidate)
        run.startTrial(rising = true)
        assertEquals(AccelPhase.TEST, run.phase)
        assertTrue(run.isTrial)
        val trial = checkNotNull(run.candidate)
        assertEquals(1.0, trial.sensitivityFactor, "the low-speed sensitivity stays as it is")
        assertTrue(trial.curve.multiplier(30.0) in 1.1..1.16)
        val configs = (0 until 2).map { run.nextRoundConfig().also { _ -> play(run, SimulatedPlayer()) } }
        assertEquals(1, configs.count { it.accel != null }, "one round of a pair has the curve")
        assertTrue(configs.all { it.cm360 == 55.0 })
    }

    private fun searchRun() = AccelRun(base, MetricWeights.DEFAULT, runKey = 1, seed = 5, startWithSearch = true)

    @Test
    fun `the blind search finds the strength a falling player wants and the blind test confirms it`() {
        val run = searchRun()
        assertEquals(AccelPhase.SEARCH, run.phase)
        while (run.phase == AccelPhase.SEARCH) {
            assertEquals("accel.label.search", run.label.key, "the label never shows a strength")
            play(run, fallingGainPlayer)
        }
        assertEquals(AccelPhase.SEARCH_RESULT, run.phase)
        assertEquals(run.searchRounds, run.searchDone)
        val result = assertNotNull(run.searchResult)
        assertTrue(result.strength < 1.0, "strength ${result.strength}")
        assertNotNull(run.searchOffer, "a falling player is offered a curve: $result")

        run.startSearchTest()
        assertTrue(run.isSearch)
        assertFalse(run.isTrial)
        while (run.phase == AccelPhase.TEST) play(run, fallingGainPlayer)
        assertEquals(AbStatus.ACCEL_BETTER, run.verdict?.status)
    }

    @Test
    fun `a flat player ends the search without a confirmed curve`() {
        val run = searchRun()
        while (run.phase == AccelPhase.SEARCH) play(run, SimulatedPlayer())
        if (run.searchOffer != null) {
            run.startSearchTest()
            while (run.phase == AccelPhase.TEST) play(run, SimulatedPlayer())
            assertTrue(run.verdict?.status != AbStatus.ACCEL_BETTER, "a flat player must not be shown a better curve")
        } else {
            assertFailsWith<IllegalStateException> { run.startSearchTest() }
        }
    }

    @Test
    fun `the search can be finished early only from the minimum rounds and also starts after the calibration`() {
        val early = searchRun()
        assertFalse(early.canFinishSearchNow)
        repeat(StrengthSearch.MIN_ROUNDS) { play(early, fallingGainPlayer) }
        assertTrue(early.canFinishSearchNow)
        early.finishSearchNow()
        assertEquals(AccelPhase.SEARCH_RESULT, early.phase)

        val afterCalibration = newRun()
        while (afterCalibration.phase == AccelPhase.CALIBRATION) play(afterCalibration, SimulatedPlayer())
        afterCalibration.startSearch()
        assertEquals(AccelPhase.SEARCH, afterCalibration.phase)
        assertFailsWith<IllegalStateException> { searchRun().startSearch() }
    }

    @Test
    fun `with flash on, half of the search and test rounds are flash rounds and the calibration stays on the click scenarios`() {
        val run = AccelRun(base, MetricWeights.DEFAULT, runKey = 1, seed = 5, startWithSearch = true, flash = true)
        val searchConfigs = (0 until 12).map { run.nextRoundConfig().also { _ -> play(run, SimulatedPlayer()) } }
        assertEquals(6, searchConfigs.count { it.scenario == ScenarioKind.FLASH })
        assertEquals(setOf(ScenarioKind.FLICK, ScenarioKind.SWITCHING, ScenarioKind.MICRO, ScenarioKind.FLASH), searchConfigs.map { it.scenario }.toSet())

        val calibrating = AccelRun(base, MetricWeights.DEFAULT, runKey = 1, seed = 5, flash = true)
        val calibration = (0 until calibrating.calibrationRounds).map { calibrating.nextRoundConfig().also { _ -> play(calibrating, SimulatedPlayer()) } }
        assertTrue(calibration.none { it.scenario == ScenarioKind.FLASH })
    }

    @Test
    fun `a falling trial curve lowers the gain with speed and a found curve cannot be replaced by a trial`() {
        val falling = trialAccel(rising = false)
        assertTrue(falling.curve.multiplier(30.0) < 0.9 && falling.gainRange in 1.1..1.2)
        val run = sampleAccelRun(base, AccelPhase.ANALYSIS)
        assertFailsWith<IllegalStateException> { run.startTrial() }
    }

    @Test
    fun `both rounds of a test pair have the same targets and the curve round uses the factor`() {
        val run = sampleAccelRun(base, AccelPhase.ANALYSIS)
        val candidate = checkNotNull(run.candidate)
        run.startTest()
        repeat(6) { pair ->
            val first = run.nextRoundConfig().also { play(run, fallingGainPlayer) }
            val second = run.nextRoundConfig().also { play(run, fallingGainPlayer) }
            assertEquals(first.seed, second.seed, "pair $pair")
            assertEquals(first.scenario, second.scenario)
            assertTrue((first.accel == null) != (second.accel == null), "one plain, one with the curve")
            val accelerated = if (first.accel != null) first else second
            assertEquals(candidate.curve, accelerated.accel)
            assertEquals(55.0 / candidate.sensitivityFactor, accelerated.cm360, 1e-9)
            assertEquals(55.0, (if (first.accel == null) first else second).cm360, 1e-9)
            if (run.phase == AccelPhase.DONE) return
        }
    }

    @Test
    fun `the label never says which condition a test round has`() {
        val run = sampleAccelRun(base, AccelPhase.ANALYSIS)
        assertEquals("accel.label.calibration", AccelRun(base, MetricWeights.DEFAULT, 1, 3).label.key)
        run.startTest()
        repeat(3) {
            assertEquals("accel.label.test", run.label.key)
            assertEquals(1, run.label.args.size, "only the number of the round")
            play(run, fallingGainPlayer)
        }
    }

    @Test
    fun `the analysis can start early from five rounds and says if they were enough`() {
        val run = newRun()
        repeat(AccelRun.MIN_CALIBRATION_ROUNDS - 1) { play(run, fallingGainPlayer) }
        assertTrue(!run.canAnalyseNow)
        assertFailsWith<IllegalStateException> { run.analyseNow() }
        play(run, fallingGainPlayer)
        assertTrue(run.canAnalyseNow)
        run.analyseNow()
        assertEquals(AccelPhase.ANALYSIS, run.phase)
        assertEquals(AccelRun.MIN_CALIBRATION_ROUNDS, run.calibrationDone)
        assertNotNull(run.fit)
        assertTrue(!run.canAnalyseNow, "no early analysis once the calibration is over")
    }

    private fun playDay(rounds: Int, seed: Long): PriorCalibration {
        val day = AccelRun(base, MetricWeights.DEFAULT, runKey = seed, seed = seed, requestedCalibrationRounds = rounds)
        repeat(rounds) { play(day, fallingGainPlayer) }
        return PriorCalibration(day.calibrationDone, day.calibrationSamples)
    }

    private fun runWith(prior: PriorCalibration, seed: Long) = AccelRun(base, MetricWeights.DEFAULT, runKey = seed, seed = seed, prior = prior)

    @Test
    fun `earlier rounds count toward the calibration, so fewer new rounds are due`() {
        val run = runWith(playDay(rounds = 8, seed = 11), seed = 12)
        assertEquals(AccelRun.DEFAULT_CALIBRATION_ROUNDS - 8, run.calibrationRounds)
        while (run.phase == AccelPhase.CALIBRATION) play(run, fallingGainPlayer)
        assertEquals(7, run.calibrationDone)
        assertTrue(checkNotNull(run.fit).points.isNotEmpty())
    }

    @Test
    fun `a day plays a few new rounds even with plenty of earlier ones`() {
        assertEquals(AccelRun.MIN_NEW_ROUNDS, runWith(PriorCalibration(40, emptyList()), seed = 13).calibrationRounds)
        assertEquals(AccelRun.DEFAULT_CALIBRATION_ROUNDS, runWith(PriorCalibration.NONE, seed = 14).calibrationRounds)
    }

    @Test
    fun `with earlier rounds the analysis can start early only after a day's minimum of new rounds`() {
        val run = runWith(PriorCalibration(6, emptyList()), seed = 15)
        repeat(AccelRun.MIN_NEW_ROUNDS - 1) { play(run, fallingGainPlayer) }
        assertTrue(!run.canAnalyseNow, "too few new rounds")
        play(run, fallingGainPlayer)
        assertTrue(run.canAnalyseNow, "3 new and 6 earlier rounds are enough for an early analysis")
        val few = runWith(PriorCalibration(2, emptyList()), seed = 16)
        repeat(AccelRun.MIN_NEW_ROUNDS) { play(few, fallingGainPlayer) }
        assertTrue(!few.canAnalyseNow, "3 new and 2 earlier rounds are fewer than the 8 the analysis needs")
    }

    @Test
    fun `the earlier days' movements go into the analysis`() {
        val earlier = playDay(rounds = 8, seed = 21)
        val second = runWith(earlier, seed = 22)
        while (second.phase == AccelPhase.CALIBRATION) play(second, fallingGainPlayer)
        assertTrue(earlier.samples.isNotEmpty())
        val alone = AccelAnalysis(second.calibrationSamples).used.size
        val pooled = AccelAnalysis(second.calibrationSamples + earlier.samples).used.size
        assertTrue(pooled > alone, "the pooled analysis has more movements than the new rounds alone")
        assertNotNull(second.fit?.recommended, "a player who expects a falling gain gets a curve from the two days together")
    }

    @Test
    fun `a curve can be tested blind straight away, without a calibration`() {
        val analysed = sampleAccelRun(base, AccelPhase.ANALYSIS)
        val curve = checkNotNull(analysed.candidate)
        val run = AccelRun(base, MetricWeights.DEFAULT, runKey = 9, seed = 9, startWithTest = curve)
        assertEquals(AccelPhase.TEST, run.phase)
        assertEquals(curve, run.candidate)
        assertEquals(0, run.calibrationDone)
        val configs = (0 until 2).map { run.nextRoundConfig().also { _ -> play(run, fallingGainPlayer) } }
        assertEquals(1, configs.count { it.accel != null }, "one round of a pair has the curve")
        while (run.phase == AccelPhase.TEST) play(run, fallingGainPlayer)
        assertEquals(AbStatus.ACCEL_BETTER, run.verdict?.status)
    }

    @Test
    fun `tracking is never part of the acceleration rounds`() {
        assertTrue(ScenarioKind.TRACKING !in AccelRun.CLICK_SCENARIOS)
    }

    // --- a base curve of the player's own (docs/FIND-CURVE-REDESIGN.md step 1)

    private val own = RawAccelProfile(
        "default", RawAccelArgs(RawAccelMode.CLASSIC, gain = true, inputOffset = 10.0, capX = 62.5, capY = 1.6, capMode = RawAccelCapMode.IN_OUT), 600.0, 800.0,
    )

    @Test
    fun `calibration rounds are played with the base curve when there is one`() {
        val run = AccelRun(base, MetricWeights.DEFAULT, runKey = 1, seed = 3, baseCurve = own)
        val configs = (0 until run.calibrationRounds).map { run.nextRoundConfig().also { _ -> play(run, SimulatedPlayer()) } }
        assertTrue(configs.all { it.accel == own && it.cm360 == 55.0 }, "the player's curve at the game sensitivity, nothing plain")
        assertEquals(AccelPhase.ANALYSIS, run.phase)
    }

    @Test
    fun `a wish that rises more than the own curve is found on top of it and keeps the level of the base`() {
        val wish = NaturalCurve(0.05, 1.3)
        val player = SimulatedPlayer(gainPreference = { v -> own.planningGain(v, base.dpi) * wish.multiplier(v) })
        val run = AccelRun(base, MetricWeights.DEFAULT, runKey = 1, seed = 3, baseCurve = own)
        while (run.phase == AccelPhase.CALIBRATION) play(run, player)
        val candidate = checkNotNull(run.fit?.recommended) { "the correction is a clear rise" }
        val speeds = run.calibrationSamples.map { it.speed }.sorted()
        val low = speeds[speeds.size / 20]
        val mid = speeds[speeds.size / 2]
        val high = speeds[speeds.size * 19 / 20]
        assertTrue(kotlin.math.abs(candidate.totalGain(mid) / own.planningGain(mid, base.dpi) - 1.0) < 0.10, "the level at the typical speed stays the base's")
        val baseRise = own.planningGain(high, base.dpi) / own.planningGain(low, base.dpi)
        assertTrue(candidate.totalGain(high) / candidate.totalGain(low) > baseRise * 1.05, "it rises more than the base does")
    }

    @Test
    fun `the control arm of the blind test is the base curve, not plain`() {
        val candidate = checkNotNull(sampleAccelRun(base, AccelPhase.ANALYSIS).candidate)
        val run = AccelRun(base, MetricWeights.DEFAULT, runKey = 9, seed = 9, startWithTest = candidate, baseCurve = own)
        val configs = (0 until 4).map { run.nextRoundConfig().also { _ -> play(run, SimulatedPlayer()) } }
        assertEquals(2, configs.count { it.accel == own }, "half of the rounds play the base")
        assertEquals(2, configs.count { it.accel == candidate.curve }, "half play the candidate")
    }

    @Test
    fun `a simulated hand that moves at about one speed gets a draft but no felt step to test`() {
        val run = AccelRun(base, MetricWeights.DEFAULT, runKey = 1, seed = 3, baseCurve = own)
        while (run.phase == AccelPhase.CALIBRATION) play(run, SimulatedPlayer())
        val draft = assertNotNull(run.draft, "a draft is made for every analysis on the player's own curve")
        // The simulated hand's first reaches are all within a narrow band of speeds, so fast cannot be told from slow (FeltStep.MIN_SPAN).
        assertNull(draft.felt)
        assertNull(run.draftOffer)
        assertFailsWith<IllegalStateException> { run.startDraftTest() }
    }

    /** Earlier reaches of a loose hand over a wide band of speeds, which a simulated player does not make. */
    private fun widePrior(): PriorCalibration {
        val random = kotlin.random.Random(5)
        val samples = List(1200) {
            val v = kotlin.math.exp(random.nextDouble(kotlin.math.ln(3.5), kotlin.math.ln(25.0)))
            MovementSample(v, kotlin.math.exp(0.45 * (random.nextDouble() + random.nextDouble() + random.nextDouble() - 1.5) * 2.0) - 1.0)
        }
        return PriorCalibration(40, samples)
    }

    @Test
    fun `a hand with a wide band of speeds is offered a felt step to test blind against the own curve`() {
        val run = AccelRun(base, MetricWeights.DEFAULT, runKey = 1, seed = 3, prior = widePrior(), baseCurve = own)
        while (run.phase == AccelPhase.CALIBRATION) play(run, SimulatedPlayer())
        val plan = assertNotNull(assertNotNull(run.draft).felt, "the speeds span enough")
        val offer = assertNotNull(run.draftOffer, "steps up to ${plan.maxStep}")
        assertTrue(offer.curve is LookupCurve)
        run.startDraftTest()
        assertTrue(run.isDraft && !run.isTrial && !run.isSearch)
        val configs = (0 until 2).map { run.nextRoundConfig().also { _ -> play(run, SimulatedPlayer()) } }
        assertEquals(setOf<Any?>(own, offer.curve), configs.map { it.accel }.toSet(), "the step against the player's curve")
    }

    @Test
    fun `a run on a stored stage plays that table word for word and keeps its anchors`() {
        val table = FeltStepFamily(own, 800.0).table(0.15, 9.0, 22.0)
        val run = AccelRun(base, MetricWeights.DEFAULT, runKey = 1, seed = 3, prior = widePrior(), baseCurve = own, stage = table)
        val config = run.nextRoundConfig()
        assertTrue(config.accel === table, "the calibration plays the stage")
        while (run.phase == AccelPhase.CALIBRATION) play(run, SimulatedPlayer())
        assertNull(run.draft, "no new anchors are drawn from the speeds of a stage")
        val offer = assertNotNull(run.draftOffer, "the stage itself is what is offered")
        assertTrue(offer.curve === table)
    }

    @Test
    fun `a plain mouse has no draft to make`() {
        val run = newRun()
        while (run.phase == AccelPhase.CALIBRATION) play(run, SimulatedPlayer())
        assertNull(run.draft)
        assertNull(run.draftOffer)
        assertFailsWith<IllegalStateException> { run.startDraftTest() }
    }
}
