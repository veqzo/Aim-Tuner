package dev.veqzo.aimtune.core.sim

import dev.veqzo.aimtune.core.harness.TEST_DPI
import dev.veqzo.aimtune.core.metrics.computeRoundMetrics
import dev.veqzo.aimtune.core.scenario.ScenarioKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StrafeAimerTest {
    private val player = SimulatedPlayer().withOptimum(50.0)
    private val seeds = 1L..8L

    private class Totals(val hitRate: Double, val timeToHitMs: Double, val hits: Int)

    private fun play(kind: ScenarioKind): Totals {
        val rounds = seeds.map { computeRoundMetrics(HeadlessRound.of(kind, player, TEST_DPI, 50.0, it, roundSeconds = 30)) }
        return Totals(rounds.sumOf { it.hits }.toDouble() / rounds.sumOf { it.shots }, rounds.mapNotNull { it.meanTimeToHitMs }.average(), rounds.sumOf { it.hits })
    }

    @Test
    fun `hitting a strafing target is harder and slower than hitting a static one but possible`() {
        val still = play(ScenarioKind.MICRO)
        val strafing = play(ScenarioKind.MICRO_STRAFE)
        println("micro: hit rate ${still.hitRate} time ${still.timeToHitMs} ms; micro-strafe: hit rate ${strafing.hitRate} time ${strafing.timeToHitMs} ms (${strafing.hits} hits)")
        assertTrue(strafing.hits > 0)
        assertTrue(strafing.hitRate < still.hitRate, "hit rate ${strafing.hitRate} vs ${still.hitRate}")
        assertTrue(strafing.timeToHitMs > still.timeToHitMs, "time ${strafing.timeToHitMs} vs ${still.timeToHitMs}")
    }

    @Test
    fun `switching between strafing targets works too`() {
        val strafing = play(ScenarioKind.SWITCH_STRAFE)
        assertTrue(strafing.hits > 0)
        assertTrue(strafing.hitRate < play(ScenarioKind.SWITCHING).hitRate)
    }

    @Test
    fun `a round is deterministic for a seed`() {
        fun round() = HeadlessRound.of(ScenarioKind.MICRO_STRAFE, player, TEST_DPI, 50.0, 5L, roundSeconds = 20)
        val a = round()
        val b = round()
        assertEquals(a.mouseEvents, b.mouseEvents)
        assertEquals(a.targetEvents, b.targetEvents)
    }

    @Test
    fun `waiting for a stop raises the hit rate`() {
        val waiting = player.copy(strafeWaitForStop = 1.0)
        val impatient = player.copy(strafeWaitForStop = 0.0)
        fun rate(p: SimulatedPlayer): Double {
            val rounds = seeds.map { computeRoundMetrics(HeadlessRound.of(ScenarioKind.MICRO_STRAFE, p, TEST_DPI, 50.0, it, roundSeconds = 30)) }
            return rounds.sumOf { it.hits }.toDouble() / rounds.sumOf { it.shots }
        }
        assertTrue(rate(waiting) > rate(impatient), "waiting ${rate(waiting)} impatient ${rate(impatient)}")
    }
}
