package de.yuuto.aimtune.app.accel

import de.yuuto.aimtune.app.TrainerConfig
import de.yuuto.aimtune.app.i18n.UiText
import de.yuuto.aimtune.core.accel.AbCondition
import de.yuuto.aimtune.core.accel.AbOutcome
import de.yuuto.aimtune.core.accel.AbStatus
import de.yuuto.aimtune.core.accel.AbVerdict
import de.yuuto.aimtune.core.accel.AccelAbPlan
import de.yuuto.aimtune.core.accel.AccelAnalysis
import de.yuuto.aimtune.core.accel.AccelFit
import de.yuuto.aimtune.core.accel.AccelFitResult
import de.yuuto.aimtune.core.accel.AccelScenarios
import de.yuuto.aimtune.core.accel.bounded
import de.yuuto.aimtune.core.accel.evaluateAb
import de.yuuto.aimtune.core.accel.CurveStrength
import de.yuuto.aimtune.core.accel.MovementSample
import de.yuuto.aimtune.core.accel.RawAccelProfile
import de.yuuto.aimtune.core.accel.scaledBy
import de.yuuto.aimtune.core.accel.fitAccel
import de.yuuto.aimtune.core.accel.strengthAccel
import de.yuuto.aimtune.core.accel.trialAccel
import de.yuuto.aimtune.core.accel.movementSamples
import de.yuuto.aimtune.core.metrics.RoundMetrics
import de.yuuto.aimtune.core.metrics.computeRoundMetrics
import de.yuuto.aimtune.core.recording.RoundRecording
import de.yuuto.aimtune.core.scenario.ScenarioKind
import de.yuuto.aimtune.core.score.MetricWeights
import de.yuuto.aimtune.core.score.scoreInputs

enum class AccelPhase {
    /** Rounds with the plain sensitivity, to see how first movements miss at different speeds. */
    CALIBRATION,

    /** The calibration is done and the fit is available; the user decides whether to test a curve. */
    ANALYSIS,

    /** Rounds with different curve strengths, blind, to find the strength that scores best. */
    SEARCH,

    /** The search is done; the user decides whether to test the strength it found against plain sensitivity. */
    SEARCH_RESULT,

    /** The blind A/B test of plain against the fitted curve. */
    TEST,

    /** The test has decided (or there was nothing to test). */
    DONE,
}

/**
 * The acceleration steps as one state machine without any UI: calibration rounds at the recommended
 * sensitivity, the analysis and fit, and the blind A/B test. [baseConfig] holds the sensitivity to
 * start from (the optimizer's recommendation). All click scenarios are played, because flicks alone
 * cover too narrow a range of hand speeds to judge a curve; tracking has no first movements.
 * With [flash] the blind search and the blind test also play flash turn-away rounds in half of their rounds:
 * the fast turns back are where a curve matters most, and in simulations they made the search settle at "no
 * curve" for players without a preference and the test confirm real curves more often (docs/ALGORITHMS.md
 * "Flash turn-away"). The error analysis does not use them: pooled with the other scenarios they only move the
 * measured speed range (docs/ALGORITHMS.md).
 * Nothing here reveals which condition a test round has.
 */
class AccelRun(
    private val baseConfig: TrainerConfig,
    private val weights: MetricWeights,
    val runKey: Long,
    private val seed: Long,
    requestedCalibrationRounds: Int = DEFAULT_CALIBRATION_ROUNDS,
    /** Plain rounds from earlier runs at this sensitivity; they count toward the calibration, so fewer new rounds are due. */
    val prior: PriorCalibration = PriorCalibration.NONE,
    maxPairs: Int = AccelAbPlan.DEFAULT_MAX_PAIRS,
    /** Skip the calibration and begin with the blind search of the curve strength. */
    startWithSearch: Boolean = false,
    /** Play flash turn-away rounds in the strength search and the blind test. */
    flash: Boolean = false,
    /** Begin with the blind test of this curve, e.g. one the stored calibration rounds gave; no calibration or search. */
    startWithTest: AccelFit? = null,
    /** Rounds of earlier searches at this sensitivity; the search builds on them, so it plays fewer new rounds. */
    priorSearch: List<PriorSearchRound> = emptyList(),
    /** Bound the recommended curve to the measured speeds as a lookup table (curve finder v2); false keeps the fitted family. */
    private val boundedCurves: Boolean = true,
    /**
     * The player's own Raw Accel curve, when they play one: the calibration and the control arm of the blind test are played with it,
     * the analysis divides it out and the strength search scales its rise (docs/FIND-CURVE-REDESIGN.md steps 1 and 2). Null is a
     * plain mouse: the search then plays natural curves over plain.
     */
    val baseCurve: RawAccelProfile? = null,
) {
    var phase = when {
        startWithTest != null -> AccelPhase.TEST
        startWithSearch -> AccelPhase.SEARCH
        else -> AccelPhase.CALIBRATION
    }
        private set

    var fit: AccelFitResult? = null
        private set

    var verdict: AbVerdict? = null
        private set

    /** New rounds to play: the requested number less the earlier rounds, but at least [MIN_NEW_ROUNDS] when there are any. */
    val calibrationRounds: Int =
        if (prior.rounds == 0) requestedCalibrationRounds else maxOf(requestedCalibrationRounds - prior.rounds, minOf(MIN_NEW_ROUNDS, requestedCalibrationRounds))

    private val calibration = mutableListOf<RoundMetrics>()
    private val testScenarios = if (flash) SCENARIOS_WITH_FLASH else CLICK_SCENARIOS
    private val plan = AccelAbPlan(testScenarios, seed, maxPairs)
    private val outcomes = mutableListOf<AbOutcome>()

    val baseCm360: Double get() = baseConfig.cm360

    val dpi: Double get() = baseConfig.dpi

    /** The sensitivity this run was started with, for the "Runs use" line. */
    val runsUse: RunsUse get() = RunsUse.running(baseConfig)

    /** Where the curve under test came from. */
    enum class Source { ANALYSIS, TRIAL, SEARCH }

    private var tested: AccelFit? = startWithTest
    private var source = Source.ANALYSIS

    private val searchBase = baseCurve?.let { SearchBase(it, baseConfig.dpi) }
    private val search = StrengthSearch(weights, seed, plannedRounds = StrengthSearch.plannedFor(priorSearch.size), prior = priorSearch, flash = flash, base = searchBase)
    private var searchCandidate: AccelFit? = null
    private var currentStrength = CurveStrength.NEUTRAL

    /** What the blind search found, once it is finished. */
    var searchResult: StrengthResult? = null
        private set

    /** The curve under test; before a test has started, the one the analysis recommended. */
    val candidate: AccelFit? get() = tested ?: fit?.recommended

    /** The strength search's curve, offered after the search when its range excludes "no curve". */
    val searchOffer: AccelFit? get() = searchCandidate

    /** True when the curve under test is a hypothesis of the player's and not a finding of the analysis. */
    val isTrial: Boolean get() = tested != null && source == Source.TRIAL

    /** True when the curve under test is the result of the blind strength search. */
    val isSearch: Boolean get() = tested != null && source == Source.SEARCH

    val searchDone: Int get() = search.roundsPlayed

    /** How many rounds of earlier searches this search builds on. */
    val priorSearchRounds: Int get() = search.priorRounds

    /** From this many new rounds on the search can be ended early. */
    val searchMinRounds: Int get() = search.minRounds

    val searchRounds: Int get() = search.plannedRounds

    /** True once enough search rounds are played to finish early. */
    val canFinishSearchNow: Boolean get() = phase == AccelPhase.SEARCH && search.roundsPlayed >= search.minRounds

    /** Whether the wanted gain the analysis measured rises with speed; a guess for the direction of a trial curve. */
    val trialRises: Boolean
        get() {
            val points = fit?.points.orEmpty()
            return points.size < 2 || points.last().logGain >= points.first().logGain
        }

    val calibrationDone: Int get() = calibration.size

    /** The first movements of this run's own calibration rounds; a later run can build on them as [PriorCalibration]. */
    val calibrationSamples: List<MovementSample> get() = movementSamples(calibration, dpi)

    val testRoundsDone: Int get() = outcomes.size

    val maxTestRounds: Int get() = 2 * plan.maxPairs

    /** HUD label: where the user is, without the condition of a test round. */
    val label: UiText
        get() = when (phase) {
            AccelPhase.CALIBRATION -> UiText("accel.label.calibration", calibration.size + 1, calibrationRounds)
            AccelPhase.TEST -> UiText("accel.label.test", outcomes.size + 1)
            AccelPhase.SEARCH -> UiText("accel.label.search", search.roundsPlayed + 1, search.plannedRounds)
            else -> UiText("")
        }

    /** The config of the next round of the current phase. */
    fun nextRoundConfig(): TrainerConfig = when (phase) {
        AccelPhase.CALIBRATION -> baseConfig.copy(
            scenario = CLICK_SCENARIOS[calibration.size % CLICK_SCENARIOS.size],
            seed = seed * CALIBRATION_SEED_MIX + calibration.size,
            accel = baseCurve,
        )
        AccelPhase.TEST -> {
            val round = plan.round(outcomes.size)
            val candidate = checkNotNull(candidate) { "no curve to test" }
            val accelerated = round.condition == AbCondition.ACCEL
            baseConfig.copy(
                cm360 = if (accelerated) baseConfig.cm360 / candidate.sensitivityFactor else baseConfig.cm360,
                scenario = round.scenario,
                seed = round.seed,
                accel = if (accelerated) candidate.curve else baseCurve,
            )
        }
        AccelPhase.SEARCH -> {
            currentStrength = search.nextStrength()
            baseConfig.copy(
                scenario = testScenarios[search.roundsPlayed % testScenarios.size],
                seed = seed * SEARCH_SEED_MIX + search.roundsPlayed,
                accel = searchBase?.strengths?.model(currentStrength) ?: CurveStrength.curve(currentStrength),
            )
        }
        AccelPhase.ANALYSIS, AccelPhase.SEARCH_RESULT, AccelPhase.DONE -> error("no round is due in phase $phase")
    }

    /** Takes a finished round of the current phase and moves on when the phase is complete. */
    fun recordRound(recording: RoundRecording) {
        val metrics = computeRoundMetrics(recording)
        when (phase) {
            AccelPhase.CALIBRATION -> {
                calibration += metrics
                if (calibration.size >= calibrationRounds) finishCalibration()
            }
            AccelPhase.TEST -> {
                val round = plan.round(outcomes.size)
                outcomes += AbOutcome(round.pair, round.condition, metrics.scoreInputs(), round.scenario.id)
                if (outcomes.size % 2 == 0) decide()
            }
            AccelPhase.SEARCH -> {
                search.record(currentStrength, testScenarios[search.roundsPlayed % testScenarios.size], metrics)
                if (search.roundsPlayed >= search.plannedRounds) finishSearch()
            }
            AccelPhase.ANALYSIS, AccelPhase.SEARCH_RESULT, AccelPhase.DONE -> error("no round is due in phase $phase")
        }
    }

    /** True once enough calibration rounds are played to analyse early, before all of them. */
    val canAnalyseNow: Boolean
        get() = phase == AccelPhase.CALIBRATION &&
            calibration.size >= minOf(if (prior.rounds == 0) MIN_CALIBRATION_ROUNDS else MIN_NEW_ROUNDS, calibrationRounds) &&
            calibration.size + prior.rounds >= minOf(MIN_CALIBRATION_ROUNDS, calibrationRounds)

    /** Ends the calibration with the rounds played so far; the analysis says if they were enough. */
    fun analyseNow() {
        check(canAnalyseNow) { "too few calibration rounds for an analysis" }
        finishCalibration()
    }

    /** Begins the blind test of the recommended curve. */
    fun startTest() {
        check(phase == AccelPhase.ANALYSIS && fit?.recommended != null) { "there is no curve to test" }
        tested = fit?.recommended
        source = Source.ANALYSIS
        phase = AccelPhase.TEST
    }

    /** Begins the blind search of the curve strength after the calibration. */
    fun startSearch() {
        check(phase == AccelPhase.ANALYSIS) { "the search starts after the analysis" }
        phase = AccelPhase.SEARCH
    }

    /** Ends the search with the rounds played so far (from [StrengthSearch.MIN_ROUNDS] on) and shows what it found. */
    fun finishSearchNow() {
        check(canFinishSearchNow) { "too few search rounds to finish" }
        finishSearch()
    }

    /** Begins the blind test of the strength the search found against plain sensitivity. */
    fun startSearchTest() {
        check(phase == AccelPhase.SEARCH_RESULT && searchCandidate != null) { "the search found no curve to test" }
        tested = searchCandidate
        source = Source.SEARCH
        phase = AccelPhase.TEST
    }

    /** Begins the blind test of a mild trial curve although the analysis found none. */
    fun startTrial(rising: Boolean = trialRises) {
        check(phase == AccelPhase.ANALYSIS && fit?.recommended == null) { "the analysis already has a curve, or it is not done" }
        tested = trialAccel(rising)
        source = Source.TRIAL
        phase = AccelPhase.TEST
    }

    /** Ends the run after the analysis when no curve was recommended or the user does not want to test it. */
    fun finish() {
        phase = AccelPhase.DONE
    }

    private fun finishSearch() {
        val result = search.result()
        searchResult = result
        searchCandidate = result?.takeIf { it.excludesPlain }?.let { searchBase?.strengths?.offer(it.strength) ?: strengthAccel(it.strength) }
        phase = AccelPhase.SEARCH_RESULT
    }

    private fun finishCalibration() {
        val base = baseCurve
        val analysis = AccelAnalysis(calibrationSamples + prior.samples, delivered = base?.let { b -> { speed: Double -> b.planningGain(speed, dpi) } })
        val result = fitAccel(analysis)
        // On top of a curve of their own the fit is always a table: the base's level at the typical speed does not fit a family that starts at 1.
        fit = if (base == null) {
            if (boundedCurves) result.bounded(analysis) else result
        } else {
            val level = base.planningGain(analysis.speedQuantile(0.5), dpi)
            result.bounded(analysis).let { bounded -> bounded.copy(recommended = bounded.recommended?.scaledBy(level)) }
        }
        phase = AccelPhase.ANALYSIS
    }

    private fun decide() {
        val result = evaluateAb(outcomes, weights, maxPairs = plan.maxPairs)
        verdict = result
        if (result.status != AbStatus.NEED_MORE_ROUNDS) phase = AccelPhase.DONE
    }

    companion object {
        const val DEFAULT_CALIBRATION_ROUNDS = 15

        /** From this many rounds on the analysis can be started early; 8 rounds found rising curves far less often than 15 (docs/history/ASSESSMENT.md B3). */
        const val MIN_CALIBRATION_ROUNDS = 8

        /** With earlier rounds to build on, a day still plays at least this many new ones (one pass through the click scenarios). */
        const val MIN_NEW_ROUNDS = 3
        private const val CALIBRATION_SEED_MIX = 1_000L
        private const val SEARCH_SEED_MIX = 1_300L

        /** Scenarios with a first movement per target; micro-adjust is slow, flicks and switching fast. */
        val CLICK_SCENARIOS = AccelScenarios.CLICK

        /** The click scenarios with a flash round between each of them: half of the rounds. */
        val SCENARIOS_WITH_FLASH = AccelScenarios.WITH_FLASH
    }
}
