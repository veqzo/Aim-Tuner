package de.yuuto.aimtune.core.scenario

import de.yuuto.aimtune.core.score.FlashImportance
import de.yuuto.aimtune.core.score.MetricWeights
import de.yuuto.aimtune.core.score.ScoreMetric
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScenarioScheduleTest {
    private val classic = setOf(ScenarioKind.FLICK, ScenarioKind.MICRO, ScenarioKind.TRACKING, ScenarioKind.SWITCHING)

    private fun counts(schedule: ScenarioSchedule, rounds: Int) = (0 until rounds).groupingBy { schedule.kindFor(it) }.eachCount()

    @Test
    fun `default weights give tracking two ninths of the rounds and split the rest`() {
        val schedule = ScenarioSchedule(classic, MetricWeights.DEFAULT)
        assertEquals(2.0 / 9, schedule.shares.getValue(ScenarioKind.TRACKING), 1e-12)
        assertEquals(7.0 / 27, schedule.shares.getValue(ScenarioKind.FLICK), 1e-12)
        val played = counts(schedule, 135)
        assertEquals(30, played.getValue(ScenarioKind.TRACKING))
        assertTrue(classic.all { (played[it] ?: 0) > 0 })
        assertEquals(135, played.values.sum())
    }

    @Test
    fun `flash rounds are played as often as the importance says and only the dodge weight counts for that`() {
        val enabled = setOf(ScenarioKind.FLICK, ScenarioKind.FLASH)
        fun share(importance: FlashImportance) =
            ScenarioSchedule(enabled, MetricWeights(MetricWeights.DEFAULT.values + (ScoreMetric.FLASH_DODGE to importance.weight))).shares[ScenarioKind.FLASH] ?: 0.0
        assertEquals(0.0, share(FlashImportance.NEVER))
        assertEquals(15.0 / 85, share(FlashImportance.SOMETIMES), 1e-12)
        assertEquals(35.0 / 105, share(FlashImportance.OFTEN), 1e-12)
        assertEquals(ScenarioKind.FLICK, ScenarioSchedule(enabled, MetricWeights.DEFAULT).kindFor(3), "default weights have no dodge weight")
    }

    @Test
    fun `a disabled scenario takes no share and its weight goes to the others`() {
        val noTracking = ScenarioSchedule(setOf(ScenarioKind.FLICK, ScenarioKind.MICRO), MetricWeights.DEFAULT)
        assertEquals(setOf(ScenarioKind.FLICK, ScenarioKind.MICRO), noTracking.shares.keys)
        assertEquals(0.5, noTracking.shares.getValue(ScenarioKind.FLICK), 1e-12)
        val flickAndTracking = ScenarioSchedule(setOf(ScenarioKind.FLICK, ScenarioKind.TRACKING), MetricWeights.DEFAULT)
        assertEquals(7.0 / 9, flickAndTracking.shares.getValue(ScenarioKind.FLICK), 1e-12)
    }

    @Test
    fun `a scenario whose metrics have no weight is not played`() {
        val weights = MetricWeights(mapOf(ScoreMetric.ACCURACY to 1.0, ScoreMetric.TRACKING to 0.0))
        val schedule = ScenarioSchedule(setOf(ScenarioKind.FLICK, ScenarioKind.TRACKING), weights)
        assertEquals(setOf(ScenarioKind.FLICK), schedule.shares.keys)
        assertTrue((0 until 20).all { schedule.kindFor(it) == ScenarioKind.FLICK })
    }

    @Test
    fun `only tracking with zero weight still plays something`() {
        val weights = MetricWeights(mapOf(ScoreMetric.ACCURACY to 1.0, ScoreMetric.TRACKING to 0.0))
        assertEquals(ScenarioKind.TRACKING, ScenarioSchedule(setOf(ScenarioKind.TRACKING), weights).kindFor(0))
        assertEquals(ScenarioKind.FLICK, ScenarioSchedule(emptySet(), weights).kindFor(0))
    }

    @Test
    fun `the plan is spread evenly and never changes`() {
        val schedule = ScenarioSchedule(classic, MetricWeights.DEFAULT)
        val first = (0 until 30).map { schedule.kindFor(it) }
        assertEquals(first, (0 until 30).map { ScenarioSchedule(classic, MetricWeights.DEFAULT).kindFor(it) })
        // Every window of 10 rounds contains tracking, and nothing is played twice in a row.
        for (from in 0..20) assertTrue(first.subList(from, from + 10).contains(ScenarioKind.TRACKING), "window $from")
        assertTrue(first.zipWithNext().none { (a, b) -> a == b }, "no scenario twice in a row: $first")
    }

    private val valorant = ScenarioMix(standingParts = 4.0, movingParts = 1.0)

    @Test
    fun `a mix plays flick 4 to strafe 1 to flash by importance and gives tracking nothing`() {
        val enabled = setOf(ScenarioKind.FLICK, ScenarioKind.TRACKING, ScenarioKind.SWITCH_STRAFE, ScenarioKind.FLASH)
        val sometimes = ScenarioSchedule.forMix(enabled, valorant, FlashImportance.SOMETIMES.mixParts)
        assertEquals(setOf(ScenarioKind.FLICK, ScenarioKind.SWITCH_STRAFE, ScenarioKind.FLASH), sometimes.shares.keys)
        assertEquals(4.0 / 6, sometimes.shares.getValue(ScenarioKind.FLICK), 1e-12)
        assertEquals(1.0 / 6, sometimes.shares.getValue(ScenarioKind.SWITCH_STRAFE), 1e-12)
        assertEquals(1.0 / 6, sometimes.shares.getValue(ScenarioKind.FLASH), 1e-12)
        assertEquals(2.0 / 7, ScenarioSchedule.forMix(enabled, valorant, FlashImportance.OFTEN.mixParts).shares.getValue(ScenarioKind.FLASH), 1e-12)
        assertTrue(ScenarioKind.FLASH !in ScenarioSchedule.forMix(enabled, valorant, FlashImportance.NEVER.mixParts).shares)
        val played = counts(sometimes, 60)
        assertEquals(mapOf(ScenarioKind.FLICK to 40, ScenarioKind.SWITCH_STRAFE to 10, ScenarioKind.FLASH to 10), played)
    }

    @Test
    fun `a group splits its parts among its enabled scenarios and an empty group gives its parts to nobody`() {
        val all = ScenarioSchedule.forMix(setOf(ScenarioKind.FLICK, ScenarioKind.MICRO, ScenarioKind.RANGE, ScenarioKind.MICRO_STRAFE), valorant, 0.0)
        assertEquals(2.0 / 5, all.shares.getValue(ScenarioKind.FLICK), 1e-12)
        assertEquals(2.0 / 5, all.shares.getValue(ScenarioKind.MICRO), 1e-12)
        assertEquals(0.5 / 5, all.shares.getValue(ScenarioKind.RANGE), 1e-12)
        // Without a moving scenario the flicks do not take its part: flash keeps its share next to them.
        val noMoving = ScenarioSchedule.forMix(setOf(ScenarioKind.FLICK, ScenarioKind.FLASH), valorant, 1.0)
        assertEquals(1.0 / 5, noMoving.shares.getValue(ScenarioKind.FLASH), 1e-12)
    }

    @Test
    fun `a mix with only tracking enabled still plays something`() {
        assertEquals(ScenarioKind.TRACKING, ScenarioSchedule.forMix(setOf(ScenarioKind.TRACKING), valorant, 0.0).kindFor(0))
    }

    @Test
    fun `a cycle is the shortest plan in which every scenario gets its exact share`() {
        val enabled = setOf(ScenarioKind.FLICK, ScenarioKind.MICRO_STRAFE, ScenarioKind.SWITCH_STRAFE, ScenarioKind.FLASH)
        val cycle = ScenarioSchedule.forMix(enabled, valorant, 1.0).cycle()
        assertEquals(12, cycle.size)
        assertEquals(mapOf(ScenarioKind.FLICK to 8, ScenarioKind.MICRO_STRAFE to 1, ScenarioKind.SWITCH_STRAFE to 1, ScenarioKind.FLASH to 2), cycle.groupingBy { it }.eachCount())
        assertEquals(14, ScenarioSchedule.forMix(enabled, valorant, 2.0).cycle().size)
        assertEquals(3, ScenarioSchedule(classic, MetricWeights.DEFAULT).cycle(maxRounds = 3).size, "no exact cycle within the limit")
    }
}
