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
        assertEquals(listOf(Engagement(500 * MS, true, false), Engagement(400 * MS, false, false)), e)
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
