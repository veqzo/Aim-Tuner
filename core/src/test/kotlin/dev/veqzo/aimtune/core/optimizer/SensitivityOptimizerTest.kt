package dev.veqzo.aimtune.core.optimizer

import dev.veqzo.aimtune.core.units.SearchRange
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SensitivityOptimizerTest {
    private val range = SearchRange(20.0, 100.0)
    private val truth = 45.0

    /** A noisy score with a known peak at [truth] on the log scale. */
    private fun score(cm360: Double, random: Random, noise: Double = 3.0): Double {
        val d = ln(cm360 / truth)
        return 70.0 - 40.0 * d * d + noise * (random.nextDouble() * 2 - 1)
    }

    private fun run(rounds: Int, seed: Long, noise: Double = 3.0): Pair<SensitivityOptimizer, List<Observation>> {
        val optimizer = SensitivityOptimizer(range, seed)
        val random = Random(seed)
        var history = emptyList<Observation>()
        repeat(rounds) {
            val s = optimizer.suggestNext(history)
            history = history + Observation(0, s.cm360, score(s.cm360, random, noise), step = history.size)
        }
        return optimizer to history
    }

    @Test
    fun `the equivalent range surrounds the optimum and is wider for a flatter curve`() {
        val (optimizer, history) = run(24, seed = 11)
        val sharp = checkNotNull(optimizer.analyze(history))
        assertTrue(sharp.equivalentLowCm360 <= sharp.optimumCm360 + 1e-6 && sharp.optimumCm360 <= sharp.equivalentHighCm360 + 1e-6)

        // The same data with the score differences shrunk to a fifth: everything is "about equal".
        val mean = history.map { it.score }.average()
        val flatHistory = history.map { it.copy(score = mean + (it.score - mean) / 5.0) }
        val flat = checkNotNull(optimizer.analyze(flatHistory))
        assertTrue(
            flat.equivalentHighCm360 / flat.equivalentLowCm360 >= sharp.equivalentHighCm360 / sharp.equivalentLowCm360,
            "flat ${flat.equivalentLowCm360}-${flat.equivalentHighCm360}, sharp ${sharp.equivalentLowCm360}-${sharp.equivalentHighCm360}",
        )
    }

    @Test
    fun `suggestions are deterministic for a history and seed`() {
        val (optimizer, history) = run(9, seed = 3)
        assertEquals(optimizer.suggestNext(history), optimizer.suggestNext(history))
        assertNotEquals(SensitivityOptimizer(range, 4).suggestNext(emptyList()), SensitivityOptimizer(range, 3).suggestNext(emptyList()))
    }

    @Test
    fun `the first rounds spread over the whole range in a hidden random order`() {
        val optimizer = SensitivityOptimizer(range, seed = 11)
        val firsts = (0 until 6).map { optimizer.suggestNext(List(it) { i -> Observation(0, 40.0, 50.0 + i) }) }
        assertTrue(firsts.all { it.kind == SuggestionKind.DESIGN && it.cm360 in range })
        val sorted = firsts.map { it.cm360 }.sorted()
        assertTrue(sorted.first() < 30.0 && sorted.last() > 70.0, "design must cover the range: $sorted")
        assertNotEquals(sorted, firsts.map { it.cm360 }, "order must not be ascending")
    }

    @Test
    fun `later suggestions stay in range, avoid the last point and include repeats`() {
        val (optimizer, history) = run(24, seed = 5)
        assertTrue(history.all { it.cm360 in range })
        assertTrue(history.zipWithNext().none { (a, b) -> abs(a.x - b.x) < 1e-9 }, "no immediate duplicates")
        val repeats = history.indices.count { i -> (0 until i).any { j -> abs(history[i].x - history[j].x) < 0.03 } }
        assertTrue(repeats >= 1, "the optimizer must retest a point to learn the noise")
        assertTrue(optimizer.suggestNext(history).cm360 in range)
    }

    @Test
    fun `it finds the peak of a noisy function and the interval contains it`() {
        var hits = 0
        for (seed in 1L..6L) {
            val (optimizer, history) = run(30, seed)
            val result = assertNotNull(optimizer.analyze(history))
            assertTrue(abs(ln(result.optimumCm360 / truth)) < 0.2, "seed $seed: ${result.optimumCm360}")
            if (truth in result.intervalLowCm360..result.intervalHighCm360) hits++
            assertTrue(result.intervalLowCm360 <= result.optimumCm360 + 1e-9 || result.intervalHighCm360 >= result.optimumCm360 - 1e-9)
        }
        assertTrue(hits >= 4, "interval contained the truth only $hits of 6 times")
    }

    @Test
    fun `confidence is capped early and grows with data, and a recommendation follows it`() {
        val early = assertNotNull(run(6, 2).let { (o, h) -> o.analyze(h) })
        assertTrue(early.confidencePercent <= 50)
        val late = assertNotNull(run(40, 2).let { (o, h) -> o.analyze(h) })
        assertTrue(late.confidencePercent > early.confidencePercent)
        assertEquals(late.confidencePercent < 85, late.recommendMoreRounds)
        assertEquals(late.recommendMoreRounds, late.roundsToTarget > 0)
    }

    @Test
    fun `too few rounds give no result`() {
        assertNull(SensitivityOptimizer(range).analyze(listOf(Observation(0, 40.0, 50.0), Observation(0, 50.0, 55.0))))
    }

    @Test
    fun `scenarios with their own score level combine into one result`() {
        val optimizer = SensitivityOptimizer(range, 9)
        val random = Random(9)
        var history = emptyList<Observation>()
        repeat(32) { i ->
            val group = i % 2
            val s = optimizer.suggestNext(history)
            // Group 1 scores 25 points higher at every sensitivity.
            history = history + Observation(0, s.cm360, score(s.cm360, random) + group * 25.0, group)
        }
        val result = assertNotNull(optimizer.analyze(history))
        assertTrue(abs(ln(result.optimumCm360 / truth)) < 0.25, "optimum ${result.optimumCm360}")
        assertTrue(result.hyper.groupVariance > 0.0, "the offset between the groups must be modelled")
    }

    @Test
    fun `a slope that only ends at the edge of the range is flagged and not confident`() {
        val optimizer = SensitivityOptimizer(range, 5)
        val random = Random(5)
        var history = emptyList<Observation>()
        repeat(30) {
            val s = optimizer.suggestNext(history)
            // The score keeps rising toward the largest cm/360 of the range.
            history = history + Observation(0, s.cm360, 20.0 * ln(s.cm360 / range.minCm360) + 2.0 * (random.nextDouble() - 0.5))
        }
        val result = assertNotNull(optimizer.analyze(history))
        assertTrue(result.atRangeEdge, "optimum ${result.optimumCm360}")
        assertTrue(result.confidencePercent <= 50)
        assertTrue(result.recommendMoreRounds)
        assertTrue(!assertNotNull(run(30, 2).let { (o, h) -> o.analyze(h) }).atRangeEdge)
    }

    @Test
    fun `rounds of several sessions merge into one result`() {
        val optimizer = SensitivityOptimizer(range, 7)
        val random = Random(7)
        var history = emptyList<Observation>()
        repeat(28) { i ->
            val session = if (i < 14) 1L else 2L
            val s = optimizer.suggestNext(history)
            // The second session is a worse day: every score is 10 points lower.
            val offset = if (session == 2L) -10.0 else 0.0
            history = history + Observation(session, s.cm360, score(s.cm360, random) + offset)
        }
        val result = assertNotNull(optimizer.analyze(history))
        assertEquals(2, result.sessions)
        assertTrue(abs(ln(result.optimumCm360 / truth)) < 0.25, "merged optimum ${result.optimumCm360}")
        assertTrue(exp(abs(ln(result.curve.maxBy { it.meanScore }.cm360 / truth))) < 1.3)
    }

    @Test
    fun `a drift of the scores within a session is detected and caps the confidence`() {
        val optimizer = SensitivityOptimizer(range, seed = 5)
        val random = Random(5)
        var history = emptyList<Observation>()
        repeat(30) {
            val s = optimizer.suggestNext(history)
            // The player gets steadily better during the session: +1.2 points per round on top of the sensitivity effect.
            history = history + Observation(0, s.cm360, score(s.cm360, random, noise = 2.0) + 1.2 * history.size, step = history.size)
        }
        val result = assertNotNull(optimizer.analyze(history))
        assertTrue(result.driftDetected, "drift should be seen: ${result.hyper}")
        assertTrue(result.confidencePercent <= 60, "confidence ${result.confidencePercent}")
    }

    @Test
    fun `without a drift the fit does not choose a trend`() {
        val (optimizer, history) = run(24, seed = 8, noise = 1.0)
        val result = assertNotNull(optimizer.analyze(history))
        assertEquals(0.0, result.hyper.trendVariance)
        assertEquals(false, result.driftDetected)
    }

    @Test
    fun `every fourth round after the design is again a space filling point`() {
        val optimizer = SensitivityOptimizer(range, seed = 2)
        val (_, history) = run(12, seed = 2)
        val picks = (7..12).map { n -> n to optimizer.suggestNext(history.take(n).let { if (it.size < n) history else it }).kind }
        assertTrue(picks.filter { (n, _) -> n % 4 == 3 }.all { (_, kind) -> kind == SuggestionKind.DESIGN }, "$picks")
        val designPoints = (6..30).filter { it % 4 == 3 }.map { SensitivityOptimizer(range, 2).suggestNext(List(it) { i -> Observation(0, 40.0 + i, 50.0 + i % 5, step = i) }).cm360 }
        assertEquals(designPoints.size, designPoints.distinct().size, "the extra design points are not repeats")
    }

    @Test
    fun `a long history is fitted with hyperparameters searched on the newest rounds only`() {
        val random = Random(1)
        val history = List(400) { Observation((it / 20).toLong(), 20.0 * exp(random.nextDouble() * ln(5.0)), score(20.0 * exp(random.nextDouble() * ln(5.0)), random), step = it % 20) }
        val started = System.nanoTime()
        assertNotNull(SensitivityOptimizer(range, 1).analyze(history))
        val seconds = (System.nanoTime() - started) / 1e9
        assertTrue(seconds < 20.0, "400 rounds took $seconds s")
    }
}
