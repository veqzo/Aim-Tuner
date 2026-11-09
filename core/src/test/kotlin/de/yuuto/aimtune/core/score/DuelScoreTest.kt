package de.yuuto.aimtune.core.score

import de.yuuto.aimtune.core.math.Vec3
import de.yuuto.aimtune.core.scenario.Target
import de.yuuto.aimtune.core.scenario.TargetEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DuelScoreTest {
    private fun spawn(ms: Long, id: Int) = TargetEvent.Spawned(ms * MS, Target(id, Vec3(0.0, 0.0, -10.0), 0.25))
    private fun hit(ms: Long, id: Int) = TargetEvent.Hit(ms * MS, id)
    private fun miss(ms: Long) = TargetEvent.Miss(ms * MS)

    @Test
    fun `first-shot hit and first-shot miss are timed from the spawn`() {
        val e = engagements(listOf(spawn(0, 1), hit(500, 1), spawn(1000, 2), miss(1400), miss(1500), hit(1700, 2)))
        assertEquals(listOf(Engagement(500 * MS, true, false, 500 * MS), Engagement(400 * MS, false, false, 700 * MS)), e)
    }

    @Test
    fun `switching reach starts at the previous hit`() {
        val e = engagements(listOf(spawn(0, 1), spawn(0, 2), hit(600, 1), hit(900, 2)))
        assertEquals(listOf(600 * MS, 300 * MS), e.map { it.firstShotNanos })
    }

    @Test
    fun `blinded or unshot engagements are lost and an open reach is dropped`() {
        val blind = TargetEvent.FlashPop(0, flashId = 0, dodged = false, blindUntilNanos = 1500 * MS)
        val e = engagements(listOf(blind, spawn(1500, 1), hit(2000, 1), spawn(2500, 2), TargetEvent.Despawned(3500 * MS, 2), spawn(4000, 3)))
        assertEquals(listOf(true, true), e.map { it.lost })
        assertEquals(listOf(500 * MS, 1000 * MS), e.map { it.reachNanos })
    }

    @Test
    fun `with the round end an open reach is the last engagement`() {
        val events = listOf(spawn(0, 1), hit(500, 1), spawn(1000, 2), miss(1200))
        val e = engagements(events, roundEndNanos = 5000 * MS)
        assertEquals(Engagement(200 * MS, false, false, 4000 * MS, open = true), e.last())
        assertEquals(1, engagements(events).size, "without the round end the open reach is left out")
        assertEquals(1, engagements(listOf(spawn(0, 1), hit(500, 1)), roundEndNanos = 5000 * MS).size, "no target alive, no open reach")
    }

    @Test
    fun `a reach over the duel limit is lost, a short cut-off reach is left out`() {
        val clean = listOf(400L, 500L, 600L).map { Engagement(it * MS, true, false, it * MS) }
        val scorer = DuelScorer(limitNanos = 3000 * MS, minRounds = 2)
        fun score(round: List<Engagement>) = scorer.scoreRounds(listOf(clean, round), listOf("flick", "flick"))[1]
        assertEquals(0.0, score(listOf(Engagement(3500 * MS, true, false, 3500 * MS))), "a hit after the limit loses")
        assertEquals(0.0, score(listOf(Engagement(null, false, false, 4000 * MS, open = true))), "stuck on a target until the round end")
        assertNull(score(listOf(Engagement(null, false, false, 1000 * MS, open = true))), "the round timer cut a short reach off")
        assertNull(DuelScorer(minRounds = 2).scoreRounds(listOf(clean, listOf(Engagement(null, false, false, 9000 * MS, open = true))), listOf("flick", "flick"))[1], "no limit: every cut-off reach is left out")
        val withinLimit = assertNotNull(score(listOf(Engagement(500 * MS, true, false, 2900 * MS))))
        assertEquals(50.0, withinLimit, 1e-6)
    }

    @Test
    fun `a reach over the limit does not enter the opponent baseline`() {
        // The slow hits are lost, so the baseline is built only from the three fast ones.
        val round = listOf(400L, 500L, 600L, 4000L, 5000L).map { Engagement(it * MS, true, false, it * MS) }
        val limited = DuelScorer(limitNanos = 3000 * MS, minRounds = 2).scoreRounds(listOf(round, round), listOf("flick", "flick"))[0]
        assertEquals(100.0 * (0.5 * 3 + 0.0 * 2) / 5, assertNotNull(limited), 1e-6)
    }

    @Test
    fun `a miss costs the follow-up delay and a lost duel scores zero`() {
        val clean = listOf(400L, 500L, 600L).map { Engagement(it * MS, true, false) }
        val scorer = DuelScorer(minRounds = 2)
        val base = DuelScorer(minRounds = 2).scoreRounds(listOf(clean, clean), listOf("flick", "flick"))[0]
        assertNotNull(base)
        assertEquals(50.0, base, 1e-6) // median opponent: the mean of G over symmetric times is one half
        val missed = scorer.scoreRounds(listOf(clean, listOf(Engagement(500 * MS, false, false))), listOf("flick", "flick"))[1]
        assertNotNull(missed)
        assertTrue(missed < 1.0, "308 ms late against an opponent spread of 100 ms almost always loses")
        val lost = scorer.scoreRounds(listOf(clean, listOf(Engagement(null, false, true))), listOf("flick", "flick"))[1]
        assertEquals(0.0, lost)
    }

    @Test
    fun `no baseline without enough first-shot hits`() {
        val round = listOf(Engagement(400 * MS, true, false), Engagement(500 * MS, false, false))
        assertNull(DuelScorer(minRounds = 2).scoreRounds(listOf(round, round), listOf("flick", "flick"))[0])
    }

    @Test
    fun `no baseline from fewer than three rounds`() {
        val clean = listOf(400L, 500L, 600L).map { Engagement(it * MS, true, false) }
        assertNull(DuelScorer().scoreRounds(listOf(clean, clean), listOf("flick", "flick"))[0])
    }

    private companion object {
        const val MS = 1_000_000L
    }
}
