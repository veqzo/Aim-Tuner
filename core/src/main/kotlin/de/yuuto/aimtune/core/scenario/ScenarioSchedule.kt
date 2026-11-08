package de.yuuto.aimtune.core.scenario

import de.yuuto.aimtune.core.score.MetricWeights
import de.yuuto.aimtune.core.score.ScoreMetric
import kotlin.math.abs
import kotlin.math.roundToInt

/** The score metrics a round of this scenario produces. */
val ScenarioKind.producedMetrics: Set<ScoreMetric>
    get() = when (this) {
        ScenarioKind.TRACKING -> setOf(ScoreMetric.TRACKING)
        // Accuracy and time to hit of the return hit also score a flash round, but they do not make it play more often.
        ScenarioKind.FLASH -> setOf(ScoreMetric.FLASH_DODGE)
        ScenarioKind.FLICK, ScenarioKind.MICRO, ScenarioKind.SWITCHING, ScenarioKind.MICRO_STRAFE, ScenarioKind.SWITCH_STRAFE, ScenarioKind.RANGE ->
            setOf(ScoreMetric.ACCURACY, ScoreMetric.TIME_TO_HIT)
    }

/**
 * Which scenario each round of a blind test plays. A round only measures the metrics of its own
 * scenario, so the weights decide how often a scenario is played: the weight of each metric is
 * split among the enabled scenarios that produce it, and a scenario's share of the rounds is the
 * sum of its metrics' weights. With the default weights and all scenarios on, tracking gets 20 % of
 * the rounds and the three click scenarios 26.7 % each. A scenario whose metrics all have weight 0
 * is not played. A game with a [ScenarioMix] uses [forMix] instead. Rounds are spread evenly (smooth
 * weighted round robin), and the plan for a round index never changes, so a run can be resumed.
 */
class ScenarioSchedule private constructor(kinds: List<ScenarioKind>, raw: Map<ScenarioKind, Double>) {
    constructor(enabled: Set<ScenarioKind>, weights: MetricWeights) : this(enabledKinds(enabled), weightParts(enabledKinds(enabled), weights))

    /** Share of the rounds per scenario, summing to 1; only scenarios that are played. */
    val shares: Map<ScenarioKind, Double>

    private val played: List<ScenarioKind>

    init {
        val usable = raw.filterValues { it > 0.0 }.ifEmpty { kinds.associateWith { 1.0 } }.ifEmpty { mapOf(ScenarioKind.FLICK to 1.0) }
        val total = usable.values.sum()
        shares = usable.mapValues { it.value / total }
        played = shares.keys.toList()
    }

    fun kindFor(roundIndex: Int): ScenarioKind {
        require(roundIndex >= 0) { "round index must not be negative" }
        val credit = played.associateWith { 0.0 }.toMutableMap()
        var pick = played.first()
        repeat(roundIndex + 1) {
            played.forEach { credit[it] = credit.getValue(it) + shares.getValue(it) }
            pick = played.maxBy { credit.getValue(it) }
            credit[pick] = credit.getValue(pick) - 1.0
        }
        return pick
    }

    /**
     * The first rounds of the plan up to the shortest length in which every scenario gets exactly its share (at most
     * [maxRounds]): a rotation for runs that cycle through a list, such as the curve runs.
     */
    fun cycle(maxRounds: Int = MAX_CYCLE_ROUNDS): List<ScenarioKind> {
        require(maxRounds >= 1) { "a cycle needs at least one round" }
        val length = (1..maxRounds).firstOrNull { n -> shares.values.all { abs(it * n - (it * n).roundToInt()) < CYCLE_TOLERANCE } } ?: maxRounds
        return (0 until length).map(::kindFor)
    }

    companion object {
        private const val MAX_CYCLE_ROUNDS = 60
        private const val CYCLE_TOLERANCE = 1e-9

        /**
         * The schedule of a game with a [mix] (M23): each engagement group gets its parts, and the parts are split evenly
         * among the enabled scenarios of the group; [flashParts] is the flash group's share. A group without an enabled
         * scenario is not played, and its parts are not moved to another group.
         */
        fun forMix(enabled: Set<ScenarioKind>, mix: ScenarioMix, flashParts: Double): ScenarioSchedule {
            require(flashParts.isFinite() && flashParts >= 0.0) { "flash parts must not be negative" }
            val kinds = enabledKinds(enabled)
            val raw = kinds.associateWith { kind -> mix.partsOf(kind.group, flashParts) / kinds.count { it.group == kind.group } }
            return ScenarioSchedule(kinds, raw)
        }

        private fun enabledKinds(enabled: Set<ScenarioKind>) = ScenarioKind.entries.filter { it in enabled }

        private fun weightParts(kinds: List<ScenarioKind>, weights: MetricWeights) = kinds.associateWith { kind ->
            kind.producedMetrics.sumOf { metric ->
                val producers = kinds.count { metric in it.producedMetrics }
                (weights.values[metric] ?: 0.0) / producers
            }
        }
    }
}
