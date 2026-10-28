package de.yuuto.aimtune.app.accel

import de.yuuto.aimtune.core.accel.FeltStepFamily
import de.yuuto.aimtune.core.accel.LookupCurve
import de.yuuto.aimtune.app.SettingsHolder
import de.yuuto.aimtune.core.accel.settings.PassiveResult
import de.yuuto.aimtune.storage.AppSettings
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class HubSessionTest {
    @TempDir
    lateinit var dir: Path

    private var loads = 0
    private var rounds = 0
    private val settings = SettingsHolder(null, AppSettings())
    private val hub = HubSession(settings, { loads++; CompletableFuture.completedFuture(sampleSettingsAnalyses()) }, { rounds })

    private fun writeSample(name: String = "settings.json"): Path = dir.resolve(name).also { Files.writeString(it, checkNotNull(sampleRefineText())) }

    @Test
    fun `an imported file keeps only its path in the settings and is read again on the next start`() {
        val path = writeSample()
        val source = RefineImport.parse(Files.readString(path), "settings.json").getOrThrow()
        hub.setImported(ImportedProfile(source, source.file.profileNames.first(), path))
        assertEquals(path.toString(), settings.current.lastImportedProfilePath)

        val restarted = HubSession(settings, { CompletableFuture.completedFuture(sampleSettingsAnalyses()) }, { 0 })
        assertNull(restarted.imported)
        val back = assertNotNull(restarted.restoreImported())
        assertEquals("settings.json", back.fileLabel)
        assertEquals(source.file.profileNames.first(), back.profileName)
    }

    @Test
    fun `a missing, unreadable or foreign file shows nothing special`() {
        settings.save(settings.current.copy(lastImportedProfilePath = dir.resolve("gone.json").toString()))
        assertNull(hub.restoreImported())
        val notRawAccel = dir.resolve("other.json").also { Files.writeString(it, "{\"a\": 1}") }
        settings.save(settings.current.copy(lastImportedProfilePath = notRawAccel.toString()))
        assertNull(hub.restoreImported())
        settings.save(settings.current.copy(lastImportedProfilePath = "\u0000not a path"))
        assertNull(hub.restoreImported())
        assertNull(hub.imported)
    }

    @Test
    fun `a clipboard import has no path, so the next start shows no profile`() {
        val path = writeSample()
        hub.setImported(ImportedProfile(sampleImport().source, "default", path))
        hub.setImported(ImportedProfile(sampleImport().source, "default", null))
        assertNull(settings.current.lastImportedProfilePath)
    }

    @Test
    fun `ticks are off by default, kept, and only a current recommendation is written`() {
        val analyses = sampleSettingsAnalyses()
        assertTrue(hub.passiveChanges(analyses).isEmpty)
        hub.setTicked(RawAccelSetting.ROTATION, true)
        hub.setTicked(RawAccelSetting.L_R, true)
        val changes = hub.passiveChanges(analyses)
        assertEquals(-1.5, changes.rotationDegrees)
        assertNull(changes.lrRatio, "L/R has no recommendation in the sample, so the tick adds nothing")
        assertNull(hub.passiveChanges(analyses.copy(rotation = PassiveResult.NotEnoughData(1, 60))).rotationDegrees)
        hub.setTicked(RawAccelSetting.ROTATION, false)
        assertTrue(hub.passiveChanges(analyses).isEmpty)
        assertFailsWith<IllegalArgumentException> { hub.setTicked(RawAccelSetting.SNAP, true) }
    }

    @Test
    fun `the analyses are read again only when rounds were stored`() {
        val first = hub.analyses()
        assertSame(first, hub.analyses())
        assertEquals(1, loads)
        rounds = 3
        hub.analyses()
        assertEquals(2, loads)
    }

    @Test
    fun `the patched copy needs an imported profile and a refine result or a ticked recommendation`() {
        assertNull(hub.patchedCopy(800.0, sampleSettingsAnalyses()))
        hub.useSample(sampleImport())
        assertEquals(false, hub.patchedCopy(800.0, sampleSettingsAnalyses())?.hasChanges)
        hub.setTicked(RawAccelSetting.Y_X, true)
        assertEquals(true, hub.patchedCopy(800.0, sampleSettingsAnalyses())?.hasChanges)
    }

    @Test
    fun `importing another profile drops a refine result of the old one`() {
        val imported = sampleImport()
        val profile = sampleProfile()
        hub.setImported(imported)
        hub.rememberRefine(RefineOutcome(imported.source, profile, profile))
        hub.setImported(ImportedProfile(imported.source, imported.profileName))
        assertNotNull(hub.refine, "the same profile of the same file keeps its result")
        hub.setImported(ImportedProfile(sampleImport().source, imported.profileName))
        assertNull(hub.refine)
    }

    @Test
    fun `a draft stage goes into the patched copy for its profile and is dropped when another profile is imported`() {
        val imported = sampleImport()
        val profile = sampleProfile()
        val table = FeltStepFamily(profile, 800.0).table(0.15, 9.0, 22.0)
        hub.useSample(imported)
        hub.rememberStage(DraftStage(imported.source, imported.profileName, table, 0.15, 9.0, 22.0))
        assertEquals(true, hub.patchedCopy(800.0, sampleSettingsAnalyses())?.changes()?.any { it.field == "curve" })
        hub.setImported(ImportedProfile(sampleImport().source, "default", null))
        assertEquals(false, hub.patchedCopy(800.0, sampleSettingsAnalyses())?.hasChanges, "another read of the file is another source")
    }
}
