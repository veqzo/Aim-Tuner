package dev.veqzo.aimtune.app.accel

import dev.veqzo.aimtune.app.RoundProcessor
import dev.veqzo.aimtune.app.TrainerConfig
import dev.veqzo.aimtune.core.accel.AccelSpec
import dev.veqzo.aimtune.core.accel.CurveStrength
import dev.veqzo.aimtune.core.accel.NaturalCurve
import dev.veqzo.aimtune.core.score.MetricWeights
import dev.veqzo.aimtune.core.sim.HeadlessRound
import dev.veqzo.aimtune.games.GameProfiles
import dev.veqzo.aimtune.storage.Database
import dev.veqzo.aimtune.storage.RoundRow
import dev.veqzo.aimtune.storage.SessionKind
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StoredSearchesTest {
    @TempDir
    lateinit var dir: Path

    private fun row(id: Long, strength: Double?, accel: String? = strength?.let { s -> CurveStrength.curve(s)?.let(AccelSpec::encode) }) =
        RoundRow(id, 1, "flick", id, 34.4, true, id, emptyMap(), accel)

    /** Strengths of a search that wanders, so no two rounds are alike. */
    private fun searchStrengths(n: Int) = (0 until n).map { 0.75 + 0.02 * it }

    private fun rows(strengths: List<Double>) = strengths.mapIndexed { i, s -> row(i.toLong(), s) }

    /** Pairs of one plain and one curve round with one strength, the order alternating like the blind test's. */
    private fun testPairs(strength: Double, pairs: Int) = (0 until pairs).flatMap { if (it % 2 == 0) listOf(1.0, strength) else listOf(strength, 1.0) }

    @Test
    fun `the strength is read back from the stored curve`() {
        assertEquals(1.0, StoredSearches.strengthOf(null))
        assertEquals(0.8, StoredSearches.strengthOf(AccelSpec.encode(NaturalCurve(CurveStrength.DECAY, 0.8))))
        assertNull(StoredSearches.strengthOf(AccelSpec.encode(NaturalCurve(0.3, 0.8))), "another decay is a fitted curve, not the search's")
        assertNull(StoredSearches.strengthOf("garbage"))
    }

    @Test
    fun `a session of the search kind is all search`() {
        val rounds = rows(searchStrengths(5))
        assertEquals(rounds, StoredSearches.searchRounds(SessionKind.ACCEL_SEARCH, rounds))
        assertTrue(StoredSearches.searchRounds(SessionKind.SENSITIVITY, rounds).isEmpty())
    }

    @Test
    fun `an older session holds the search followed by the blind test, and only the search is taken`() {
        val search = searchStrengths(24)
        val all = rows(search + testPairs(0.7, 8))
        assertEquals(24, StoredSearches.searchRounds(SessionKind.ACCEL_AB, all).size)
        val stoppedInsidePair = rows(search + testPairs(0.7, 8) + listOf(1.0))
        assertEquals(24, StoredSearches.searchRounds(SessionKind.ACCEL_AB, stoppedInsidePair).size)
        assertEquals(18, StoredSearches.searchRounds(SessionKind.ACCEL_AB, rows(searchStrengths(18))).size, "a search without a test is all search")
    }

    @Test
    fun `calibration, short and test-only sessions are no search`() {
        assertTrue(StoredSearches.searchRounds(SessionKind.ACCEL_AB, rows(List(15) { 1.0 })).isEmpty(), "plain rounds only")
        assertTrue(StoredSearches.searchRounds(SessionKind.ACCEL_AB, rows(searchStrengths(10))).isEmpty(), "fewer rounds than a search may end with")
        assertTrue(StoredSearches.searchRounds(SessionKind.ACCEL_AB, rows(testPairs(0.7, 10))).isEmpty(), "one strength only")
        val fitted = rows(List(10) { 1.0 }) + (10 until 30).map { row(it.toLong(), null, AccelSpec.encode(NaturalCurve(0.3, 0.8))) }
        assertTrue(StoredSearches.searchRounds(SessionKind.ACCEL_AB, fitted).isEmpty(), "a test of a fitted curve")
    }

    @Test
    fun `the stored search gives the strength the live search found`() {
        val config = TrainerConfig(800.0, 34.4, checkNotNull(GameProfiles.load().find("valorant")), roundSeconds = 30, seed = 0)
        val run = AccelRun(config, MetricWeights.DEFAULT, runKey = 1, seed = 5, startWithSearch = true)
        val processor = RoundProcessor(Database.open(dir.resolve("a.db")))
        var at = 0L
        while (run.phase == AccelPhase.SEARCH) {
            val next = run.nextRoundConfig()
            val round = HeadlessRound.of(next.scenario, fallingGainPlayer, next.dpi, next.cm360, next.seed, next.roundSeconds, accel = next.accel)
            processor.submit(round, startedAtMs = ++at, blind = true, gameId = "valorant", runKey = 1, kind = SessionKind.ACCEL_SEARCH)
            run.recordRound(round)
        }
        val live = assertNotNull(run.searchResult)

        val stored = assertNotNull(processor.lastSearch(MetricWeights.DEFAULT))
        processor.close()
        assertEquals(run.searchRounds, stored.rounds)
        assertEquals(34.4, stored.cm360)
        val result = assertNotNull(stored.result)
        assertTrue(abs(result.strength - live.strength) < 0.03, "stored ${result.strength}, live ${live.strength}")
        assertEquals(live.excludesPlain, result.excludesPlain)
        assertEquals(run.searchOffer != null, stored.clear)
    }

    private fun storeSearch(processor: RoundProcessor, config: TrainerConfig, seed: Long, runKey: Long, startAt: Long): AccelRun {
        val run = AccelRun(config, MetricWeights.DEFAULT, runKey = runKey, seed = seed, startWithSearch = true)
        var at = startAt
        while (run.phase == AccelPhase.SEARCH) {
            val next = run.nextRoundConfig()
            val round = HeadlessRound.of(next.scenario, fallingGainPlayer, next.dpi, next.cm360, next.seed, next.roundSeconds, accel = next.accel)
            processor.submit(round, startedAtMs = ++at, blind = true, gameId = "valorant", runKey = runKey, kind = SessionKind.ACCEL_SEARCH)
            run.recordRound(round)
        }
        return run
    }

    @Test
    fun `searches of several days at the same setup are analysed together, other setups are left out`() {
        val profile = checkNotNull(GameProfiles.load().find("valorant"))
        val config = TrainerConfig(800.0, 34.4, profile, roundSeconds = 30, seed = 0)
        val processor = RoundProcessor(Database.open(dir.resolve("c.db")))
        storeSearch(processor, config.copy(cm360 = 46.0), seed = 1, runKey = 1, startAt = 0)
        storeSearch(processor, config, seed = 2, runKey = 2, startAt = 1_000)
        storeSearch(processor, config.copy(cm360 = 34.9), seed = 3, runKey = 3, startAt = 2_000)
        val newest = storeSearch(processor, config, seed = 4, runKey = 4, startAt = 3_000)

        val pooled = assertNotNull(processor.lastSearch(MetricWeights.DEFAULT))
        processor.close()
        assertEquals(3, pooled.sessions, "the three searches within 3 % of the newest one's sensitivity")
        assertEquals(3 * newest.searchRounds, pooled.rounds)
        assertEquals(34.4, pooled.cm360)
        val result = assertNotNull(pooled.result)
        assertTrue(result.strength < 1.0, "the falling player wants a falling strength, was ${result.strength}")
        assertNotNull(pooled.offer)
    }

    @Test
    fun `an unclear search offers no curve`() {
        fun last(strength: Double, low: Double, high: Double) =
            LastSearch(800.0, 34.4, 30, 2, StrengthResult(strength, low, high, 70, 30, CurveStrength.excludesPlain(strength, low, high), atEdge = false))
        assertNotNull(last(1.2, 1.05, 1.4).offer)
        assertNull(last(1.2, 0.95, 1.4).offer, "the range includes no curve")
        assertNull(last(0.8, 0.7, 0.95).offer, "a falling range that does not end below 0.90")
        assertNotNull(last(0.8, 0.7, 0.85).offer)
    }

    @Test
    fun `a new search builds on the stored ones and needs fewer new rounds`() {
        val profile = checkNotNull(GameProfiles.load().find("valorant"))
        val config = TrainerConfig(800.0, 34.4, profile, roundSeconds = 30, seed = 0)
        val processor = RoundProcessor(Database.open(dir.resolve("d.db")))
        val first = storeSearch(processor, config, seed = 7, runKey = 1, startAt = 0)
        val prior = processor.priorSearch(800.0, 34.4)
        assertEquals(first.searchRounds, prior.size)
        assertTrue(processor.priorSearch(800.0, 50.0).isEmpty(), "another sensitivity has none")
        assertTrue(processor.priorSearch(1600.0, 34.4).isEmpty(), "another DPI has none")

        val second = AccelRun(config, MetricWeights.DEFAULT, runKey = 2, seed = 8, startWithSearch = true, priorSearch = prior)
        assertEquals(StrengthSearch.MIN_PLANNED_WITH_PRIOR, second.searchRounds, "24 earlier rounds leave a day of 8")
        assertEquals(prior.size, second.priorSearchRounds)
        assertTrue(second.searchMinRounds < StrengthSearch.MIN_ROUNDS)
        repeat(second.searchMinRounds - 1) { play(second) }
        assertTrue(!second.canFinishSearchNow)
        while (second.phase == AccelPhase.SEARCH) play(second)
        assertEquals(AccelPhase.SEARCH_RESULT, second.phase)
        val result = assertNotNull(second.searchResult)
        assertEquals(prior.size + second.searchRounds, result.rounds, "the result counts the earlier rounds too")
        assertTrue(result.strength < 1.0 && second.searchOffer != null, "the falling player still gets a falling curve: $result")
        processor.close()
    }

    private fun play(run: AccelRun) {
        val next = run.nextRoundConfig()
        run.recordRound(HeadlessRound.of(next.scenario, fallingGainPlayer, next.dpi, next.cm360, next.seed, next.roundSeconds, accel = next.accel))
    }

    @Test
    fun `a search with flash rounds, or one that builds on such rounds, searches up to the wider strength`() {
        val inputs = mapOf(dev.veqzo.aimtune.core.score.ScoreMetric.ACCURACY to 0.5)
        fun searchOf(flash: Boolean, prior: List<PriorSearchRound> = emptyList()) = StrengthSearch(MetricWeights.DEFAULT, seed = 1L, prior = prior, flash = flash).apply {
            listOf(0.8, 1.0, 1.2, 1.4, 0.9, 1.1).forEachIndexed { i, s -> record(s, "flick", inputs + (dev.veqzo.aimtune.core.score.ScoreMetric.ACCURACY to 0.4 + 0.05 * i)) }
        }
        assertEquals(CurveStrength.MAX, assertNotNull(searchOf(flash = false).result()).rangeMax, 1e-9)
        assertEquals(CurveStrength.MAX_WITH_FLASH, assertNotNull(searchOf(flash = true).result()).rangeMax, 1e-9)
        val earlier = listOf(PriorSearchRound(5L, 0, 1.7, "flash", inputs))
        assertEquals(CurveStrength.MAX_WITH_FLASH, assertNotNull(searchOf(flash = false, prior = earlier).result()).rangeMax, 1e-9)
    }

    @Test
    fun `without a stored search there is none`() {
        val processor = RoundProcessor(Database.open(dir.resolve("b.db")))
        assertNull(processor.lastSearch(MetricWeights.DEFAULT))
        assertNull(RoundProcessor(null).lastSearch(MetricWeights.DEFAULT))
        processor.close()
    }
}
