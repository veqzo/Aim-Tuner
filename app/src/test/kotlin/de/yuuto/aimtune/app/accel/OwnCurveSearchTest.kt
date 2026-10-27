package de.yuuto.aimtune.app.accel

import de.yuuto.aimtune.app.RoundProcessor
import de.yuuto.aimtune.app.TrainerConfig
import de.yuuto.aimtune.core.accel.AccelSpec
import de.yuuto.aimtune.core.accel.CurveStrength
import de.yuuto.aimtune.core.accel.LookupCurve
import de.yuuto.aimtune.core.accel.NaturalCurve
import de.yuuto.aimtune.core.accel.OwnCurveStrength
import de.yuuto.aimtune.core.accel.RawAccelArgs
import de.yuuto.aimtune.core.accel.RawAccelCapMode
import de.yuuto.aimtune.core.accel.RawAccelMode
import de.yuuto.aimtune.core.accel.RawAccelProfile
import de.yuuto.aimtune.core.score.MetricWeights
import de.yuuto.aimtune.core.sim.HeadlessRound
import de.yuuto.aimtune.core.sim.SimulatedPlayer
import de.yuuto.aimtune.games.GameProfiles
import de.yuuto.aimtune.storage.Database
import de.yuuto.aimtune.storage.SessionKind
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The strength search on top of the player's own curve (docs/FIND-CURVE-REDESIGN.md step 2): played, stored, read back and pooled. */
class OwnCurveSearchTest {
    @TempDir
    lateinit var dir: Path

    private val own = RawAccelProfile(
        "default", RawAccelArgs(RawAccelMode.CLASSIC, gain = true, inputOffset = 10.0, capX = 62.5, capY = 1.6, capMode = RawAccelCapMode.IN_OUT), 600.0, 800.0,
    )
    private val config = TrainerConfig(800.0, 34.4, checkNotNull(GameProfiles.load().find("valorant")), roundSeconds = 30, seed = 0)
    private val searchBase = SearchBase(own, 800.0)

    private fun run(seed: Long = 7, base: RawAccelProfile? = own, prior: List<PriorSearchRound> = emptyList()) =
        AccelRun(config, MetricWeights.DEFAULT, runKey = seed, seed = seed, startWithSearch = true, baseCurve = base, priorSearch = prior)

    private fun store(processor: RoundProcessor, run: AccelRun, startAt: Long) {
        var at = startAt
        while (run.phase == AccelPhase.SEARCH) {
            val next = run.nextRoundConfig()
            val round = HeadlessRound.of(next.scenario, SimulatedPlayer(), next.dpi, next.cm360, next.seed, next.roundSeconds, accel = next.accel)
            processor.submit(round, startedAtMs = ++at, blind = true, gameId = "valorant", runKey = run.runKey, kind = SessionKind.ACCEL_SEARCH)
            run.recordRound(round)
        }
    }

    @Test
    fun `the strength is read back from the stored round on top of the own curve`() {
        val table = AccelSpec.encodeModel(searchBase.strengths.model(1.3))
        assertEquals(1.3, assertNotNull(StoredSearches.strengthOf(table, searchBase)), 1e-3)
        assertEquals(1.0, StoredSearches.strengthOf(AccelSpec.encodeModel(own), searchBase), "the neutral band plays the curve itself")
        assertNull(StoredSearches.strengthOf(null, searchBase), "a plain round is no round of this search")
        assertNull(StoredSearches.strengthOf(AccelSpec.encode(NaturalCurve(CurveStrength.DECAY, 0.8)), searchBase))
        assertNull(StoredSearches.strengthOf(table), "without the base the table is no strength of the plain search")
    }

    @Test
    fun `a search on top of the own curve plays the base or a scaled table, searches half to double and offers a table`() {
        val run = run()
        val configs = mutableListOf<Any?>()
        while (run.phase == AccelPhase.SEARCH) {
            val next = run.nextRoundConfig()
            configs += next.accel
            run.recordRound(HeadlessRound.of(next.scenario, SimulatedPlayer(), next.dpi, next.cm360, next.seed, next.roundSeconds, accel = next.accel))
        }
        assertTrue(configs.all { it === own || it is LookupCurve }, "never a plain round and never a natural curve")
        assertTrue(configs.any { it is LookupCurve })
        val result = assertNotNull(run.searchResult)
        assertEquals(OwnCurveStrength.MIN, result.rangeMin, 1e-9)
        assertEquals(OwnCurveStrength.MAX, result.rangeMax, 1e-9)
        run.searchOffer?.let { assertIs<LookupCurve>(it.curve) }
    }

    @Test
    fun `stored searches on top of the own curve come back for that curve only`() {
        val processor = RoundProcessor(Database.open(dir.resolve("s.db")))
        val first = run(seed = 7)
        store(processor, first, startAt = 0)
        store(processor, run(seed = 8, base = null), startAt = 1_000)

        assertEquals(first.searchRounds, processor.priorSearch(800.0, 34.4, own).size, "the rounds of the own-curve search, not the plain one")
        assertEquals(first.searchRounds, processor.priorSearch(800.0, 34.4).size, "the plain query reads the plain search and skips the other")
        val other = RawAccelProfile("default", own.args.copy(capY = 1.8), 600.0, 800.0)
        assertTrue(processor.priorSearch(800.0, 34.4, other).isEmpty(), "another base has none")
        assertNotNull(processor.lastSearch(MetricWeights.DEFAULT, own))
        assertNotNull(processor.lastSearch(MetricWeights.DEFAULT))
        processor.close()
    }

    @Test
    fun `a run that builds on earlier own-curve rounds plays fewer new ones`() {
        val processor = RoundProcessor(Database.open(dir.resolve("t.db")))
        store(processor, run(seed = 7), startAt = 0)
        val prior = processor.priorSearch(800.0, 34.4, own)
        val second = run(seed = 9, prior = prior)
        assertEquals(StrengthSearch.MIN_PLANNED_WITH_PRIOR, second.searchRounds)
        assertSame(own, second.baseCurve)
        processor.close()
    }
}
