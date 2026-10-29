package de.yuuto.aimtune.core.accel

import de.yuuto.aimtune.core.scenario.ScenarioKind
import de.yuuto.aimtune.core.score.MetricWeights
import de.yuuto.aimtune.core.score.ScoreMetric

/** The scenario rotations of the curve runs: click scenarios, with a flash round between each of them when flash matters. */
object AccelScenarios {
    /** Scenarios with a first movement per target; micro-adjust is slow, flicks and switching fast. */
    val CLICK = listOf(ScenarioKind.FLICK, ScenarioKind.SWITCHING, ScenarioKind.MICRO)

    /** The click scenarios with a flash round between each of them: half of the rounds. */
    val WITH_FLASH = listOf(
        ScenarioKind.FLICK, ScenarioKind.FLASH, ScenarioKind.SWITCHING, ScenarioKind.FLASH, ScenarioKind.MICRO, ScenarioKind.FLASH,
    )

    fun of(flash: Boolean): List<ScenarioKind> = if (flash) WITH_FLASH else CLICK
}

enum class RefinePhase {
    /** Rounds with the imported profile, to settle the hand; not used by the search. */
    WARMUP,

    /** The blind search of one parameter ([RefineEngine.stage]). */
    SEARCH,

    /** A stage is done; the player continues, skips the rest or goes to the test. */
    STAGE_RESULT,

    /** The blind A/B test of the refined profile against the imported one. */
    TEST,

    /** Nothing more to play: the test decided, or no stage found a change. */
    DONE,
}

/** What the next round plays: the whole [profile] at the player's game cm/360 ([cm360]); the output DPI factor is inside the driver model. The blind part is only the factor. */
data class RefineRound(val scenario: ScenarioKind, val seed: Long, val profile: RawAccelProfile, val cm360: Double)

/**
 * "Refine my Raw Accel curve" without UI: warm-up rounds with the imported [profile], one blind search per [RefineParameter]
 * the profile supports (high-speed gain, transition, in this order), and a blind A/B test of the refined profile against
 * the imported one. Every round plays a whole profile through the driver model at the player's game sensitivity [gameCm360].
 *
 * A stage searches the factor of its parameter on top of the factors the earlier stages carried. A stage's best factor is
 * carried on only when its credible range excludes 1 ([RefineResult.carried]); otherwise the stage leaves the profile alone. The
 * test runs only when at least one stage carried a change. In the test the control arm ([AbCondition.PLAIN]) plays the imported
 * profile and the candidate arm ([AbCondition.ACCEL]) the refined one, with the statistics of the plain-versus-curve test.
 */
class RefineEngine(
    val profile: RawAccelProfile,
    val gameCm360: Double,
    private val weights: MetricWeights,
    private val seed: Long,
    flash: Boolean = false,
    val warmupRounds: Int = DEFAULT_WARMUP_ROUNDS,
    private val stageRounds: Int = RefineSearch.DEFAULT_ROUNDS,
    maxPairs: Int = AccelAbPlan.DEFAULT_MAX_PAIRS,
    /** The rotation of the rounds instead of [AccelScenarios.of]; experiments only (the gate's flash-heavy mixes). */
    scenarioMix: List<ScenarioKind>? = null,
) {
    /** The parameters the profile supports, in the order they are searched. */
    val stages: List<RefineParameter> = profile.refineStages()

    private val scenarios = scenarioMix?.also { require(it.isNotEmpty()) { "scenario mix must not be empty" } } ?: AccelScenarios.of(flash)
    private val plan = AccelAbPlan(scenarios, seed, maxPairs)
    private val outcomes = mutableListOf<AbOutcome>()
    private val carried = LinkedHashMap<RefineParameter, Double>()
    private val results = mutableListOf<RefineResult>()
    private var warmupDone = 0
    private var stageIndex = 0
    private var search: RefineSearch? = null
    private var currentValue = RefineParameter.NEUTRAL
    private val roundLog = mutableListOf<RefineRoundLog>()

    var phase = RefinePhase.WARMUP
        private set

    var verdict: AbVerdict? = null
        private set

    /** The results of the finished stages. */
    val stageResults: List<RefineResult> get() = results.toList()

    /** The rounds played so far with what was blind about each and the player's rating, if given. */
    val rounds: List<RefineRoundLog> get() = roundLog.toList()

    /** True while the last played round has no rating yet. */
    val canVote: Boolean get() = roundLog.lastOrNull()?.vote == null && roundLog.isNotEmpty()

    /** Rates the round that was played last. The rating is kept next to the score and never changes what the search or the test decides. */
    fun vote(vote: FeelVote) {
        check(canVote) { "there is no round to rate" }
        roundLog[roundLog.lastIndex] = roundLog.last().copy(vote = vote)
    }

    /** The stage being searched, or the one just finished while its result is shown. */
    val stage: RefineParameter? get() = if (phase == RefinePhase.SEARCH || phase == RefinePhase.STAGE_RESULT) stages.getOrNull(stageIndex) else null

    /** 0-based position of [stage] among [stages]. */
    val stageNumber: Int get() = stageIndex

    val warmupPlayed: Int get() = warmupDone

    val searchPlayed: Int get() = search?.roundsPlayed ?: 0

    val searchPlanned: Int get() = stageRounds

    val searchMinRounds: Int get() = search?.minRounds ?: minOf(stageRounds, RefineSearch.MIN_ROUNDS)

    val canFinishStageNow: Boolean get() = phase == RefinePhase.SEARCH && searchPlayed >= searchMinRounds

    val testRoundsDone: Int get() = outcomes.size

    val maxTestRounds: Int get() = 2 * plan.maxPairs

    /** The result of the stage that just finished (while in [RefinePhase.STAGE_RESULT]). */
    val currentResult: RefineResult? get() = if (phase == RefinePhase.STAGE_RESULT) results.lastOrNull() else null

    /** The profile with every carried change; the imported one while nothing is carried. */
    val candidate: RawAccelProfile get() = profile.adjusted(carried)

    /** True once a stage carried a change, so there is something to test. */
    val hasCandidate: Boolean get() = carried.any { RefineParameter.NEUTRAL != it.value }

    /** The carried factors, only those that change something. */
    val changes: Map<RefineParameter, Double> get() = carried.filterValues { it != RefineParameter.NEUTRAL }

    /** The next round of the current phase. */
    fun nextRound(): RefineRound = when (phase) {
        RefinePhase.WARMUP -> round(scenarios[warmupDone % scenarios.size], seed * WARMUP_SEED_MIX + warmupDone, profile)
        RefinePhase.SEARCH -> {
            val active = checkNotNull(search)
            currentValue = active.nextValue()
            round(scenarios[active.roundsPlayed % scenarios.size], seed * SEARCH_SEED_MIX + stageIndex * STAGE_SEED_MIX + active.roundsPlayed, searchProfile(active.parameter, currentValue))
        }
        RefinePhase.TEST -> {
            val round = plan.round(outcomes.size)
            round(round.scenario, round.seed, if (round.condition == AbCondition.ACCEL) candidate else profile)
        }
        RefinePhase.STAGE_RESULT, RefinePhase.DONE -> error("no round is due in phase $phase")
    }

    /** Takes the score inputs of the round [nextRound] returned and moves on when the phase is complete. */
    fun record(scoreInputs: Map<ScoreMetric, Double>) {
        when (phase) {
            RefinePhase.WARMUP -> {
                roundLog += RefineRoundLog(RefinePhase.WARMUP, null, null, null)
                warmupDone++
                if (warmupDone >= warmupRounds) beginStage(0)
            }
            RefinePhase.SEARCH -> {
                val active = checkNotNull(search)
                roundLog += RefineRoundLog(RefinePhase.SEARCH, active.parameter, currentValue, null)
                active.record(currentValue, scenarios[active.roundsPlayed % scenarios.size].id, scoreInputs)
                if (active.roundsPlayed >= active.plannedRounds) finishStage()
            }
            RefinePhase.TEST -> {
                val round = plan.round(outcomes.size)
                roundLog += RefineRoundLog(RefinePhase.TEST, null, null, round.condition == AbCondition.ACCEL)
                outcomes += AbOutcome(round.pair, round.condition, scoreInputs, round.scenario.id)
                if (outcomes.size % 2 == 0) decide()
            }
            RefinePhase.STAGE_RESULT, RefinePhase.DONE -> error("no round is due in phase $phase")
        }
    }

    /** Ends the warm-up early (the player had enough); the search stages follow. */
    fun skipWarmup() {
        check(phase == RefinePhase.WARMUP) { "not in the warm-up" }
        beginStage(0)
    }

    /** Ends the running stage with the rounds played so far, from [searchMinRounds] on. */
    fun finishStageNow() {
        check(canFinishStageNow) { "too few rounds to end the stage" }
        finishStage()
    }

    /** Goes on with the next stage, or to the test (or the end) when there is none. */
    fun continueToNextStage() {
        check(phase == RefinePhase.STAGE_RESULT) { "no stage result is shown" }
        if (stageIndex + 1 < stages.size) beginStage(stageIndex + 1) else beginTestOrEnd()
    }

    /** Skips the remaining stages and goes to the test (or the end when nothing was found). */
    fun goToTest() {
        check(phase == RefinePhase.STAGE_RESULT) { "no stage result is shown" }
        beginTestOrEnd()
    }

    /** Ends the run. */
    fun finish() {
        phase = RefinePhase.DONE
    }

    private fun round(scenario: ScenarioKind, seed: Long, profile: RawAccelProfile): RefineRound = RefineRound(scenario, seed, profile, gameCm360)

    private fun searchProfile(parameter: RefineParameter, factor: Double): RawAccelProfile = candidate.let { if (it.supports(parameter)) it.adjusted(parameter, factor) else it }

    private fun beginStage(index: Int) {
        if (index >= stages.size) return beginTestOrEnd()
        stageIndex = index
        search = RefineSearch(stages[index], weights, seed * SEARCH_SEED_MIX + index, stageRounds)
        phase = RefinePhase.SEARCH
    }

    private fun finishStage() {
        val active = checkNotNull(search)
        val result = active.result()
        if (result == null) {
            carried[active.parameter] = RefineParameter.NEUTRAL
            results += RefineResult(active.parameter, RefineParameter.NEUTRAL, active.parameter.min, active.parameter.max, 0, active.roundsPlayed, false, false)
        } else {
            carried[active.parameter] = result.carried
            results += result
        }
        phase = RefinePhase.STAGE_RESULT
    }

    private fun beginTestOrEnd() {
        phase = if (hasCandidate) RefinePhase.TEST else RefinePhase.DONE
    }

    private fun decide() {
        val result = evaluateAb(outcomes, weights, maxPairs = plan.maxPairs)
        verdict = result
        // Pairs without a usable score never complete, so the plan's last round ends the test whatever the verdict says.
        if (result.status != AbStatus.NEED_MORE_ROUNDS || outcomes.size >= maxTestRounds) phase = RefinePhase.DONE
    }

    companion object {
        /** Eight rounds with the imported curve before the search, as long as the shortest stage. */
        const val DEFAULT_WARMUP_ROUNDS = 8
        private const val WARMUP_SEED_MIX = 1_000L
        private const val SEARCH_SEED_MIX = 1_300L
        private const val STAGE_SEED_MIX = 100L
    }
}
