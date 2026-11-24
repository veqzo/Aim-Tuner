package de.yuuto.aimtune.core.sim

import de.yuuto.aimtune.core.scenario.ScenarioKind
import de.yuuto.aimtune.core.score.Engagement
import de.yuuto.aimtune.core.score.engagements
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The rushing hand ([SimulatedPlayer.rushClickChance]): faster first shots that miss more, and more so at a fast sensitivity. */
class RushClickTest {
    private fun reaches(player: SimulatedPlayer, cm360: Double): List<Engagement> =
        (1L..6L).flatMap { seed -> engagements(HeadlessRound.of(ScenarioKind.FLICK, player, 800.0, cm360, seed, 45).targetEvents) }

    private fun List<Engagement>.missShare() = count { !it.firstShotHit } / size.toDouble()

    private fun List<Engagement>.medianFirstShot() = mapNotNull { it.firstShotNanos }.sorted().let { it[it.size / 2] }

    @Test
    fun `a chance of 0 plays exactly like the default player`() {
        val plain = HeadlessRound.of(ScenarioKind.FLICK, SimulatedPlayer.forHeadSizedTargets(), 800.0, 50.0, 3L, 45)
        val zero = HeadlessRound.of(ScenarioKind.FLICK, SimulatedPlayer.forHeadSizedTargets().copy(rushClickChance = 0.0), 800.0, 50.0, 3L, 45)
        assertEquals(plain.targetEvents, zero.targetEvents)
    }

    @Test
    fun `rushing shoots earlier and misses more, most at a fast sensitivity`() {
        val patient = SimulatedPlayer.forHeadSizedTargets(50.0)
        val rushing = patient.copy(rushClickChance = 1.0)
        val patientFast = reaches(patient, 20.0)
        val rushingFast = reaches(rushing, 20.0)
        val rushingSlow = reaches(rushing, 100.0)
        val report = "miss share patient %.2f, rushing at 20 cm %.2f, at 100 cm %.2f; median first shot patient %d ms, rushing %d ms".format(
            patientFast.missShare(), rushingFast.missShare(), rushingSlow.missShare(), patientFast.medianFirstShot() / 1_000_000, rushingFast.medianFirstShot() / 1_000_000,
        )
        assertTrue(rushingFast.missShare() > patientFast.missShare() + 0.1, report)
        assertTrue(rushingFast.missShare() > rushingSlow.missShare(), report)
        assertTrue(rushingFast.medianFirstShot() < patientFast.medianFirstShot(), report)
        println(report)
    }
}
