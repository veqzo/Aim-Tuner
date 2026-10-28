package de.yuuto.aimtune.core.accel

import kotlin.test.Test
import kotlin.test.assertEquals

class StagePlanTest {
    private val hour = 3_600_000L

    private fun session(contrast: Double, score: Double = 50.0, at: Long = 0, rounds: Int = 12, se: Double = 0.01) =
        StageSession(StageMisfit(contrast, se), score, rounds, at * hour)

    private val reference = listOf(session(-0.001), session(0.001, at = -20))
    private val baseline = listOf(50.0, 51.0, 49.0)

    private fun decide(current: List<StageSession>, ref: List<StageSession> = reference, base: List<Double> = baseline, next: Boolean = true) =
        StagePlan.decide(current, ref, base, next)

    @Test
    fun `a hand that comes toward the stage and keeps its score goes up`() {
        val d = decide(listOf(session(-0.08, at = 0), session(-0.03, at = 10), session(-0.02, at = 24)))
        assertEquals(StageAction.ADVANCE, d.action)
    }

    @Test
    fun `a hand that is as used to the stage as to the one before goes up even without a trend`() {
        assertEquals(StageAction.ADVANCE, decide(listOf(session(0.0, at = 0), session(0.001, at = 10))).action)
    }

    @Test
    fun `the last rung is the result`() {
        assertEquals(StageAction.TOP, decide(listOf(session(0.0, at = 0), session(0.0, at = 10)), next = false).action)
    }

    @Test
    fun `too little data holds with its reason`() {
        assertEquals(HoldReason.ROUNDS, decide(listOf(session(0.0, rounds = 5))).reason)
        assertEquals(HoldReason.SESSIONS, decide(listOf(session(0.0))).reason)
        assertEquals(HoldReason.SESSIONS, decide(listOf(session(0.0, at = 0), session(0.0, at = 2))).reason, "two sessions on one day")
        assertEquals(HoldReason.BASELINE, decide(listOf(session(0.0, at = 0), session(0.0, at = 10)), base = listOf(50.0)).reason)
    }

    @Test
    fun `a hand that stays away from the stage holds, and goes back after three sessions`() {
        val away = listOf(session(-0.08, at = 0), session(-0.08, at = 10))
        assertEquals(StageAction.HOLD, decide(away).action)
        assertEquals(HoldReason.ADAPTING, decide(away).reason)
        assertEquals(StageAction.BACK, decide(away + session(-0.09, at = 24)).action)
    }

    @Test
    fun `a score that falls under the player's own twice sends the stage back, once it only holds`() {
        val worse = listOf(session(0.0, score = 40.0, at = 0), session(0.0, score = 41.0, at = 10))
        assertEquals(StageAction.BACK, decide(worse).action)
        val once = listOf(session(0.0, score = 40.0, at = 0), session(0.0, score = 52.0, at = 10))
        assertEquals(StageDecision(StageAction.HOLD, HoldReason.ADAPTING), decide(once))
    }

    @Test
    fun `a score within the margin of the baseline does not block`() {
        assertEquals(StageAction.ADVANCE, decide(listOf(session(0.0, score = 48.5, at = 0), session(0.0, score = 48.5, at = 10))).action)
    }

    private fun at(rung: Int, s: StageSession) = RungSession(rung, s)

    private val own = listOf(at(0, session(0.0, at = -50)), at(0, session(0.0, at = -40)))

    @Test
    fun `a replay moves up a rung when the evidence passes and starts the next window empty`() {
        val history = own + listOf(at(1, session(0.0, at = 0)), at(1, session(0.0, at = 10)))
        val state = StagePlan.replay(history) { true }
        assertEquals(2, state.rung)
        assertEquals(emptyList(), state.window)
    }

    @Test
    fun `a replay stays on a rung that holds and keeps the sessions of its window`() {
        val state = StagePlan.replay(own + at(1, session(0.0, at = 0))) { true }
        assertEquals(1, state.rung)
        assertEquals(1, state.window.size)
        assertEquals(HoldReason.SESSIONS, state.decision?.reason)
    }

    @Test
    fun `a replay that goes back ends the plan on the own curve and does not climb again from old sessions`() {
        val bad = own + listOf(at(1, session(0.0, score = 40.0, at = 0)), at(1, session(0.0, score = 40.0, at = 10)), at(0, session(0.0, at = 20)), at(1, session(0.0, at = 30)))
        val state = StagePlan.replay(bad) { true }
        assertEquals(0, state.rung)
    }

    @Test
    fun `the top rung is kept`() {
        val state = StagePlan.replay(own + listOf(at(1, session(0.0, at = 0)), at(1, session(0.0, at = 10)))) { false }
        assertEquals(1, state.rung)
        assertEquals(StageAction.TOP, state.decision?.action)
    }
}
