package de.yuuto.aimtune.app.history

import de.yuuto.aimtune.core.metrics.computeRoundMetrics
import de.yuuto.aimtune.core.score.MetricWeights
import de.yuuto.aimtune.core.score.RoundScorer
import de.yuuto.aimtune.core.sim.HeadlessRound
import de.yuuto.aimtune.core.sim.SimulatedPlayer
import de.yuuto.aimtune.core.units.SearchRange
import de.yuuto.aimtune.storage.RoundRow
import de.yuuto.aimtune.storage.SessionKind
import de.yuuto.aimtune.storage.SessionRow
import de.yuuto.aimtune.storage.toNamedValues
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HistoryTest {
    private val range = SearchRange(20.0, 120.0)
    private val player = SimulatedPlayer().withOptimum(50.0)

    private fun round(session: Long, index: Int, cm360: Double, blind: Boolean = true): RoundRow {
        val metrics = computeRoundMetrics(HeadlessRound.flick(player, 800.0, cm360, session * 100 + index, roundSeconds = 30))
        return RoundRow(session * 100 + index, session, "flick", session * 100 + index, cm360, blind, session * 1000 + index, metrics.toNamedValues())
    }

    private fun sensitivityRounds(session: Long, count: Int) = List(count) { round(session, it, 25.0 + it * 12.0) }

    @Test
    fun `each sensitivity session shows the recommendation known after it`() {
        val sessions = listOf(
            SessionRow(1, 1_000, "valorant", 800.0, SessionKind.SENSITIVITY),
            SessionRow(2, 2_000, "valorant", 800.0, SessionKind.SENSITIVITY),
        )
        val rounds = sensitivityRounds(1, 5) + sensitivityRounds(2, 5)
        val history = buildHistory(sessions, rounds, RoundScorer(MetricWeights.DEFAULT), range)
        assertEquals(listOf(1L, 2L), history.map { it.sessionId })
        assertEquals(listOf(5, 5), history.map { it.rounds })
        history.forEach { assertTrue(assertNotNull(it.cm360) in range) }
        assertNotNull(history.last().confidencePercent)
    }

    @Test
    fun `acceleration sessions and empty sessions have no recommendation or no line`() {
        val sessions = listOf(
            SessionRow(1, 1_000, "valorant", 800.0, SessionKind.SENSITIVITY),
            SessionRow(2, 2_000, "valorant", 800.0, SessionKind.ACCEL_AB),
            SessionRow(3, 3_000, "valorant", 800.0, SessionKind.SENSITIVITY),
        )
        val history = buildHistory(sessions, sensitivityRounds(1, 4) + sensitivityRounds(2, 2), RoundScorer(MetricWeights.DEFAULT), range)
        assertEquals(listOf(1L, 2L), history.map { it.sessionId }, "the session without rounds is dropped")
        assertNull(history[1].cm360)
        assertNull(history[1].confidencePercent)
        assertEquals(SessionKind.ACCEL_AB, history[1].kind)
    }

    @Test
    fun `another game does not feed the recommendation`() {
        val sessions = listOf(
            SessionRow(1, 1_000, "cs2", 800.0, SessionKind.SENSITIVITY),
            SessionRow(2, 2_000, "valorant", 800.0, SessionKind.SENSITIVITY),
        )
        val history = buildHistory(sessions, sensitivityRounds(1, 6) + sensitivityRounds(2, 2), RoundScorer(MetricWeights.DEFAULT), range)
        assertNotNull(history[0].cm360)
        assertNull(history[1].cm360, "two valorant rounds are too few for an estimate")
    }
}
