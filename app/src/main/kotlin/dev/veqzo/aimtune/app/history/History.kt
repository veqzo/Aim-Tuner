package dev.veqzo.aimtune.app.history

import dev.veqzo.aimtune.app.blind.BlindTest
import dev.veqzo.aimtune.app.blind.AxisErrors
import dev.veqzo.aimtune.app.blind.PastRound
import dev.veqzo.aimtune.core.optimizer.OptimizerConfig
import dev.veqzo.aimtune.core.score.Engagement
import dev.veqzo.aimtune.core.score.RoundScorer
import dev.veqzo.aimtune.core.units.SearchRange
import dev.veqzo.aimtune.storage.RoundRow
import dev.veqzo.aimtune.storage.SessionKind
import dev.veqzo.aimtune.storage.SessionRow
import dev.veqzo.aimtune.storage.scoreInputs

/**
 * One line of the history: a stored session. For a sensitivity session [cm360] and [confidencePercent]
 * are what the optimizer recommended with all rounds of that game and DPI up to and including this session
 * (null while there were too few rounds); acceleration sessions have neither.
 */
data class HistoryEntry(
    val sessionId: Long,
    val startedAtMs: Long,
    val gameId: String?,
    val kind: SessionKind,
    val rounds: Int,
    val cm360: Double?,
    val confidencePercent: Int?,
)

/**
 * The stored sessions oldest first, each with the recommendation known at the time. Sessions without rounds are left out.
 * [engagements] holds the duels of the blind rounds by round id, for a [scorer] with duel rules.
 */
fun buildHistory(
    sessions: List<SessionRow>,
    rounds: List<RoundRow>,
    scorer: RoundScorer,
    range: SearchRange,
    config: OptimizerConfig = OptimizerConfig(),
    engagements: Map<Long, List<Engagement>?> = emptyMap(),
): List<HistoryEntry> {
    val bySession = rounds.groupBy { it.sessionId }
    val ordered = sessions.sortedWith(compareBy({ it.startedAtMs }, { it.id }))
    return ordered.mapNotNull { session ->
        val own = bySession[session.id].orEmpty()
        if (own.isEmpty()) return@mapNotNull null
        val result = if (session.kind == SessionKind.SENSITIVITY) {
            val upTo = ordered.takeWhile { it.id != session.id } + session
            val merged = upTo.filter { it.kind == SessionKind.SENSITIVITY && it.gameId == session.gameId && it.dpi == session.dpi }
                .flatMap { s -> bySession[s.id].orEmpty().filter { it.blind } }
                .map { PastRound(it.sessionId, it.cm360, it.scoreInputs(), it.scenario, AxisErrors.of(it), engagements[it.id]) }
            BlindTest.analyzeStored(range, scorer, merged, config)
        } else {
            null
        }
        HistoryEntry(session.id, session.startedAtMs, session.gameId, session.kind, own.size, result?.optimumCm360, result?.confidencePercent)
    }
}
