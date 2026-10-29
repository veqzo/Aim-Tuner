package de.yuuto.aimtune.core.accel

/** How the player rated a round after playing it; asked next to the blind score, never used as a score. */
enum class FeelVote(val value: Int) {
    WORSE(-1),
    SAME(0),
    BETTER(1),
    ;

    companion object {
        fun of(value: Int): FeelVote? = entries.firstOrNull { it.value == value }
    }
}

/** One played round of a refine run as far as the ratings need it: what was blind about it, and the player's rating once given. */
data class RefineRoundLog(
    val phase: RefinePhase,
    /** The parameter and factor of a search round; null in the warm-up and the test. */
    val parameter: RefineParameter?,
    val factor: Double?,
    /** In the test: true when the round played the refined profile, false for the imported one; null elsewhere. */
    val refinedArm: Boolean?,
    val vote: FeelVote? = null,
)

/** Where a factor lies against the imported curve, for grouping ratings. */
enum class FactorBand {
    WEAKER,
    LIKE_YOURS,
    STRONGER,
    ;

    companion object {
        /** Factors within 10 % of 1 are "like yours": that is a change the player can hardly tell from the curve itself. */
        const val LIKE_BAND = 0.1

        fun of(factor: Double): FactorBand = when {
            factor < 1.0 - LIKE_BAND -> WEAKER
            factor > 1.0 + LIKE_BAND -> STRONGER
            else -> LIKE_YOURS
        }
    }
}

data class FeelCount(val better: Int = 0, val same: Int = 0, val worse: Int = 0) {
    val total: Int get() = better + same + worse
    val net: Int get() = better - worse

    operator fun plus(vote: FeelVote) = when (vote) {
        FeelVote.BETTER -> copy(better = better + 1)
        FeelVote.SAME -> copy(same = same + 1)
        FeelVote.WORSE -> copy(worse = worse + 1)
    }
}

/** What the ratings of a run say, grouped; read-only help next to the score, which stays the only thing a change is kept on. */
object RefineFeel {
    /** A band is a lean when it has at least this many ratings and at least this many more "better" than "worse". */
    const val LEAN_MIN_VOTES = 4
    const val LEAN_MIN_NET = 3

    /** The ratings of the search rounds of [parameter] by how far the played factor was from 1. Bands without a rating are left out. */
    fun byBand(log: List<RefineRoundLog>, parameter: RefineParameter): Map<FactorBand, FeelCount> {
        val counts = LinkedHashMap<FactorBand, FeelCount>()
        for (round in log) {
            val vote = round.vote ?: continue
            if (round.phase != RefinePhase.SEARCH || round.parameter != parameter) continue
            val band = FactorBand.of(round.factor ?: continue)
            counts[band] = (counts[band] ?: FeelCount()) + vote
        }
        return FactorBand.entries.filter { it in counts }.associateWith { counts.getValue(it) }
    }

    /** The ratings of the test rounds: the imported profile first, the refined one second. */
    fun byArm(log: List<RefineRoundLog>): Pair<FeelCount, FeelCount> {
        var yours = FeelCount()
        var refined = FeelCount()
        for (round in log) {
            val vote = round.vote ?: continue
            if (round.phase != RefinePhase.TEST) continue
            if (round.refinedArm == true) refined += vote else yours += vote
        }
        return yours to refined
    }

    /** The band the ratings lean to (clearly more "better" than "worse", and more so than any other band), or null. */
    fun leaning(counts: Map<FactorBand, FeelCount>): FactorBand? {
        val best = counts.filterValues { it.total >= LEAN_MIN_VOTES && it.net >= LEAN_MIN_NET }.maxByOrNull { it.value.net } ?: return null
        return best.key.takeIf { key -> counts.all { (band, count) -> band == key || count.net < best.value.net } }
    }
}
