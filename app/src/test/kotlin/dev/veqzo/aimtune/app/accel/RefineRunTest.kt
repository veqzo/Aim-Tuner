package dev.veqzo.aimtune.app.accel

import dev.veqzo.aimtune.app.TrainerConfig
import dev.veqzo.aimtune.core.accel.RefinePhase
import dev.veqzo.aimtune.core.accel.RawAccelProfile
import dev.veqzo.aimtune.core.score.MetricWeights
import dev.veqzo.aimtune.core.sim.HeadlessRound
import dev.veqzo.aimtune.core.sim.SimulatedPlayer
import dev.veqzo.aimtune.games.GameProfiles
import dev.veqzo.aimtune.storage.RawAccelExport
import dev.veqzo.aimtune.storage.SessionKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RefineRunTest {
    private val config = TrainerConfig(800.0, 55.0, checkNotNull(GameProfiles.load().find("valorant")), roundSeconds = 20, seed = 0)
    private val text = checkNotNull(sampleRefineText())
    private val source = RefineImport.parse(text, "sample").getOrThrow()

    private fun profile(dpi: Double = 800.0) = RefineImport.choose(source, "default", dpi)

    @Test
    fun `the user profile imports at the settings dpi and is refused at another one`() {
        assertNotNull(profile().profile)
        val other = profile(1600.0)
        assertNull(other.profile)
        assertEquals(800, other.dpiMismatch)
    }

    @Test
    fun `an unreadable text is a failure with a reason`() {
        assertTrue(RefineImport.parse("not json", "x").isFailure)
    }

    @Test
    fun `rounds play the whole profile at the game cm360, warm-up first`() {
        val imported = checkNotNull(profile().profile)
        val run = RefineRun(config, MetricWeights.DEFAULT, 1, 2, source, imported, flash = false)
        val first = run.nextRoundConfig()
        assertEquals(55.0, first.cm360, 1e-9)
        assertEquals(imported, first.accel)
        assertTrue(first.accel is RawAccelProfile)
        assertEquals(800.0, first.dpi)
    }

    @Test
    fun `the run reaches the first stage result and the export name is the profile name with a suffix`() {
        val run = checkNotNull(sampleRefineRun(config))
        assertEquals(RefinePhase.STAGE_RESULT, run.phase)
        assertNotNull(run.engine.currentResult)
        assertEquals("default (AimTune)", run.exportName)
        val player = SimulatedPlayer()
        run.engine.continueToNextStage()
        val next = run.nextRoundConfig()
        run.recordRound(HeadlessRound.of(next.scenario, player, next.dpi, next.cm360, next.seed, next.roundSeconds, accel = next.accel))
        assertEquals(1, run.engine.searchPlayed)
    }

    @Test
    fun `refine rounds are a session kind of their own that stored searches ignore`() {
        assertEquals("accel_refine", SessionKind.ACCEL_REFINE.id)
        assertEquals(SessionKind.ACCEL_REFINE, SessionKind.fromId("accel_refine"))
        assertTrue(StoredSearches.searchRounds(SessionKind.ACCEL_REFINE, emptyList()).isEmpty())
    }

    @Test
    fun `a refined profile exports as a patched copy that keeps the original and lists its changes`() {
        val imported = checkNotNull(profile().profile)
        val refined = imported.copy(args = imported.args.copy(capY = 1.7, inputOffset = 12.0))
        val changes = RefineDiff.changes(imported, refined)
        assertEquals(listOf("Cap / Jump y", "inputOffset"), changes.map { it.field })
        assertEquals("10", changes[1].old)
        assertEquals("12", changes[1].new)
        val root = RawAccelExport.patch(source.file.root, "default", imported, refined, "default (AimTune)")
        assertEquals(listOf("default", "default (AimTune)"), dev.veqzo.aimtune.storage.RawAccelImport.parse(root.toString()).profileNames)
    }
}
