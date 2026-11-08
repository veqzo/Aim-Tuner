package de.yuuto.aimtune.app.blind

import de.yuuto.aimtune.core.metrics.RoundMetrics
import de.yuuto.aimtune.core.optimizer.Observation
import de.yuuto.aimtune.core.optimizer.OptimizationResult
import de.yuuto.aimtune.core.optimizer.OptimizerConfig
import de.yuuto.aimtune.core.optimizer.SensitivityOptimizer
import de.yuuto.aimtune.core.optimizer.Suggestion
import de.yuuto.aimtune.core.scenario.ScenarioKind
import de.yuuto.aimtune.core.score.Engagement
import de.yuuto.aimtune.core.score.RoundScorer
import de.yuuto.aimtune.core.score.ScoreMetric
import de.yuuto.aimtune.core.score.scoreInputs
import de.yuuto.aimtune.core.units.SearchRange
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

/**
 * A stored round of an earlier session, reduced to what the optimizer needs; [scenario] is the scenario id. [engagements]
 * are its duels for the duel score (null for tracking, or when the events were not read).
 */
data class PastRound(
    val sessionId: Long,
    val cm360: Double,
    val inputs: Map<ScoreMetric, Double>,
    val scenario: String = ScenarioKind.FLICK.id,
    val axes: AxisErrors? = null,
    val engagements: List<Engagement>? = null,
)

/** A blind round as the results chart plots it; [score] is null while the round has no usable score yet. */
data class ScoredRound(val cm360: Double, val score: Double?, val sessionId: Long)

/**
 * What the user may see about a finished round; the sensitivity is deliberately not part of it.
 * Click scenarios have hits and time to hit, tracking has the share of time on target.
 */
data class RoundOutcome(
    val scenario: ScenarioKind,
    val hits: Int,
    val shots: Int,
    val accuracy: Double,
    val meanTimeToHitMs: Double?,
    val trackingOnTargetRatio: Double? = null,
) {
    companion object {
        fun of(scenario: ScenarioKind, metrics: RoundMetrics) =
            RoundOutcome(scenario, metrics.hits, metrics.shots, metrics.accuracy, metrics.meanTimeToHitMs, metrics.trackingOnTargetRatio)
    }
}

/** Optimizer output after a round: the analysis of everything so far and the sensitivity to play next. */
data class BlindStep(val result: OptimizationResult?, val next: Suggestion)

/**
 * One blind test run: rounds are played at sensitivities chosen by the optimizer, which the user
 * never sees. Earlier sessions ([past]) are merged into every analysis. Scores are recomputed over
 * all rounds after each round, because a score is relative to the player's own rounds. The model
 * fit runs on a background thread; [nextCm360] and [step] wait for it only when called early.
 */
class BlindTest(
    range: SearchRange,
    private val scorer: RoundScorer,
    private val past: List<PastRound>,
    seed: Long,
    val plannedRounds: Int = DEFAULT_PLANNED_ROUNDS,
    config: OptimizerConfig = OptimizerConfig(),
) : AutoCloseable {
    private val optimizer = SensitivityOptimizer(range, seed, config)
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "aimtune-optimizer").apply { isDaemon = true } }
    private val played = mutableListOf<PastRound>()
    private var job: CompletableFuture<BlindStep>

    /** Rounds finished in this run. */
    val roundsPlayed: Int get() = played.size

    var lastOutcome: RoundOutcome? = null
        private set

    init {
        job = schedule(past)
    }

    /** True once the analysis of the latest round is available. */
    val isReady: Boolean get() = job.isDone

    /** The analysis after the latest round, waiting for it if necessary. */
    fun step(): BlindStep = job.get()

    /** The analysis if it is ready, without waiting. */
    fun resultOrNull(): OptimizationResult? = if (job.isDone) job.get().result else null

    /** Sensitivity of the next round. Blind: callers must not show it. */
    fun nextCm360(): Double = job.get().next.cm360

    /** Every round in the model (earlier sessions and this run) with its current score; null score while it has none. */
    fun scoredRounds(): List<ScoredRound> = scoreRounds(scorer, past + played)

    /** Which axis the misses of all rounds in the model come from; null with fewer than three rounds that have movements. */
    fun axisSummary(): AxisSummary? = AxisSummary.of((past + played).map { it.axes })

    /** Rounds of this run and of all earlier sessions in the model. */
    val totalRounds: Int get() = past.size + played.size

    val sessionCount: Int get() = (past.map { it.sessionId } + if (played.isEmpty()) emptyList() else listOf(CURRENT_RUN)).distinct().size

    /** Adds a finished round of [scenario] played at [cm360] with its [engagements] (see [PastRound]) and starts the analysis. */
    fun recordRound(cm360: Double, metrics: RoundMetrics, scenario: ScenarioKind = ScenarioKind.FLICK, engagements: List<Engagement>? = null) {
        played += PastRound(CURRENT_RUN, cm360, metrics.scoreInputs(), scenario.id, AxisErrors.of(metrics), engagements)
        lastOutcome = RoundOutcome.of(scenario, metrics)
        job = schedule(past + played)
    }

    private fun schedule(rounds: List<PastRound>): CompletableFuture<BlindStep> {
        val snapshot = rounds.toList()
        return CompletableFuture.supplyAsync({
            val scores = scorer.score(snapshot)
            val history = observations(snapshot, scores)
            BlindStep(optimizer.analyze(history), optimizer.suggestNext(history))
        }, executor)
    }

    override fun close() {
        executor.shutdownNow()
    }

    companion object {
        const val DEFAULT_PLANNED_ROUNDS = 10

        /** Session id of the running test; stored sessions have positive database ids. */
        private const val CURRENT_RUN = -1L

        /** A round without a usable score yet (fewer than three rounds) only counts for the design phase. */
        private const val UNKNOWN_SCORE = 50.0

        /** [rounds] with the score the model sees for each, for the results chart. */
        fun scoreRounds(scorer: RoundScorer, rounds: List<PastRound>): List<ScoredRound> {
            val scores = scorer.score(rounds)
            return rounds.indices.map { ScoredRound(rounds[it].cm360, scores[it], rounds[it].sessionId) }
        }

        /** The result of stored rounds alone, for the results screen. */
        fun analyzeStored(
            range: SearchRange,
            scorer: RoundScorer,
            rounds: List<PastRound>,
            config: OptimizerConfig = OptimizerConfig(),
        ): OptimizationResult? {
            if (rounds.isEmpty()) return null
            val scores = scorer.score(rounds)
            return SensitivityOptimizer(range, seed = 0L, config).analyze(observations(rounds, scores))
        }

        private fun RoundScorer.score(rounds: List<PastRound>): List<Double?> =
            scoreRounds(rounds.map { it.inputs }, rounds.map { it.scenario }, rounds.map { it.engagements })

        /** The optimizer's view of [rounds] with their [scores]; each scenario is its own group. */
        private fun observations(rounds: List<PastRound>, scores: List<Double?>): List<Observation> {
            val stepInSession = HashMap<Long, Int>()
            return rounds.indices.map {
                val step = checkNotNull(stepInSession.merge(rounds[it].sessionId, 1, Int::plus)) - 1
                Observation(rounds[it].sessionId, rounds[it].cm360, scores[it] ?: UNKNOWN_SCORE, rounds[it].scenario.hashCode(), step)
            }
        }
    }
}
