package de.yuuto.aimtune.storage

import de.yuuto.aimtune.core.units.AimStyle
import de.yuuto.aimtune.core.scenario.ScenarioKind
import de.yuuto.aimtune.core.score.FlashImportance
import de.yuuto.aimtune.core.score.MetricWeights
import de.yuuto.aimtune.core.score.ScoreMetric
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsStoreTest {
    @TempDir
    lateinit var dir: Path

    private fun store() = SettingsStore(dir.resolve("nested").resolve("settings.json"))

    @Test
    fun `missing file gives the defaults without creating anything`() {
        val s = store().load()
        assertEquals(AppSettings(), s)
        assertNull(s.dpi)
        assertEquals(45, s.roundSeconds)
        assertTrue(Files.notExists(dir.resolve("nested")))
    }

    @Test
    fun `settings round trip and parent directories are created`() {
        val store = store()
        val settings = AppSettings(
            language = "de", dpi = 1600.0, gameId = "valorant", inGameSensitivity = 0.35, aimStyle = "MIXED",
            padWidthCm = 45.0, padHeightCm = 40.0, roundSeconds = 60,
            metricWeights = mapOf("ACCURACY" to 50.0, "TIME_TO_HIT" to 50.0), fullscreen = true,
            curveFinder = CurveFinder.LEGACY,
        )
        store.save(settings)
        assertEquals(settings, store.load())
    }

    @Test
    fun `settings files without a curve finder load with v2`() {
        val file = dir.resolve("old.json")
        Files.writeString(file, """{"version":1,"dpi":800.0}""")
        assertEquals(CurveFinder.V2, SettingsStore(file).load().curveFinder)
    }

    @Test
    fun `the last imported profile path round trips and older files load without it`() {
        val file = dir.resolve("old-import.json")
        Files.writeString(file, """{"version":1,"dpi":800.0}""")
        assertNull(SettingsStore(file).load().lastImportedProfilePath)

        val store = store()
        val settings = AppSettings(lastImportedProfilePath = "C:/Users/Jörg/rawaccel/settings.json")
        store.save(settings)
        assertEquals(settings, store.load())
    }

    @Test
    fun `volumes and the flash brightness round trip, older files get the quieter and dimmer defaults, and percents become safe factors`() {
        val file = dir.resolve("old-sound.json")
        Files.writeString(file, """{"version":1,"dpi":800.0}""")
        val old = SettingsStore(file).load()
        assertEquals(AppSettings.DEFAULT_FLASH_VOLUME_PERCENT, old.flashVolumePercent)
        assertEquals(AppSettings.DEFAULT_HIT_VOLUME_PERCENT, old.hitVolumePercent)
        assertEquals(AppSettings.DEFAULT_FLASH_BRIGHTNESS_PERCENT, old.flashBrightnessPercent)

        val store = store()
        store.save(AppSettings(flashVolumePercent = 25, hitVolumePercent = 80, flashBrightnessPercent = 30))
        val back = store.load()
        assertEquals(listOf(25, 80, 30), listOf(back.flashVolumePercent, back.hitVolumePercent, back.flashBrightnessPercent))

        assertEquals(0.25f, AppSettings.fraction(25))
        assertEquals(1f, AppSettings.fraction(400), "a hand-edited value above 100 is not louder than the built-in level")
        assertEquals(0f, AppSettings.fraction(-5))
    }

    @Test
    fun `unknown keys from a newer version are ignored`() {
        val file = dir.resolve("s.json")
        Files.writeString(file, """{"version":1,"dpi":800.0,"somethingNew":{"a":1}}""")
        assertEquals(800.0, SettingsStore(file).load().dpi)
    }

    @Test
    fun `corrupt json is reported and not overwritten`() {
        val file = dir.resolve("s.json")
        Files.writeString(file, "{ not json")
        assertFailsWith<SettingsException> { SettingsStore(file).load() }
        assertEquals("{ not json", Files.readString(file))
    }

    @Test
    fun `invalid values are rejected on load and on save`() {
        val file = dir.resolve("s.json")
        Files.writeString(file, """{"dpi":-5.0}""")
        assertFailsWith<SettingsException> { SettingsStore(file).load() }
        assertFailsWith<SettingsException> { store().save(AppSettings(roundSeconds = 0)) }
        assertFailsWith<SettingsException> { store().save(AppSettings(metricWeights = mapOf("NOPE" to 1.0))) }
        assertFailsWith<SettingsException> { store().save(AppSettings(aimStyle = "SIDEWAYS")) }
    }

    @Test
    fun `aim style converts to the core enum`() {
        assertEquals(AimStyle.ARM, AppSettings(aimStyle = "ARM").aimStyleOrNull())
        assertNull(AppSettings().aimStyleOrNull())
    }

    @Test
    fun `metric weights convert to core weights`() {
        val w: MetricWeights = AppSettings(metricWeights = mapOf("ACCURACY" to 3.0, "CONSISTENCY" to 1.0)).toMetricWeights()
        assertEquals(setOf(ScoreMetric.ACCURACY, ScoreMetric.CONSISTENCY), w.values.keys)
        assertEquals(MetricWeights(MetricWeights.DEFAULT.values + (ScoreMetric.FLASH_DODGE to 15.0)), AppSettings().toMetricWeights())
    }

    @Test
    fun `enabled scenarios default to flick, range and flash, survive a round trip and reject bad selections`() {
        assertEquals(setOf(ScenarioKind.FLICK, ScenarioKind.RANGE, ScenarioKind.FLASH), AppSettings().enabledScenarios())
        val file = dir.resolve("old.json")
        Files.writeString(file, """{"version":1,"dpi":800.0,"scenarios":["flick","flash"]}""")
        assertEquals(setOf(ScenarioKind.FLICK, ScenarioKind.FLASH), SettingsStore(file).load().enabledScenarios())

        val store = store()
        store.save(AppSettings(scenarios = listOf("flick", "tracking")))
        assertEquals(setOf(ScenarioKind.FLICK, ScenarioKind.TRACKING), store.load().enabledScenarios())
        assertFailsWith<SettingsException> { store().save(AppSettings(scenarios = emptyList())) }
        assertFailsWith<SettingsException> { store().save(AppSettings(scenarios = listOf("flick", "juggling"))) }
    }

    @Test
    fun `range bot armor defaults to on, reaches the range options and survives a round trip`() {
        assertTrue(AppSettings().rangeOptions().armor)
        val file = dir.resolve("old.json")
        Files.writeString(file, """{"version":1,"dpi":800.0}""")
        assertTrue(SettingsStore(file).load().rangeOptions().armor)

        val store = store()
        store.save(AppSettings(rangeBotArmor = false))
        assertFalse(store.load().rangeOptions().armor)
    }

    @Test
    fun `the default scenarios depend on whether the game has a Range mode`() {
        assertEquals(listOf("flick", "range", "flash"), AppSettings.defaultScenarios(hasRange = true))
        assertEquals(listOf("flick", "flash"), AppSettings.defaultScenarios(hasRange = false))
        assertEquals(listOf("flick", "range", "micro-strafe", "switch-strafe", "flash"), AppSettings.defaultScenarios(hasRange = true, hasMix = true))
        assertTrue(AppSettings.isDefaultScenarios(listOf("flick", "range")), "a default without flash is still a default")
        assertFalse(AppSettings.isDefaultScenarios(listOf("flick", "micro", "flash")))
    }

    @Test
    fun `saving replaces the file without leaving temp files`() {
        val store = store()
        store.save(AppSettings(dpi = 800.0))
        store.save(AppSettings(dpi = 1600.0))
        assertEquals(1600.0, store.load().dpi)
        Files.list(dir.resolve("nested")).use { files -> assertEquals(listOf("settings.json"), files.map { it.fileName.toString() }.toList()) }
    }

    @Test
    fun `the flash importance switches the scenario and sets its weight, and reads back from the settings`() {
        val base = AppSettings(scenarios = listOf("flick", "tracking"))
        assertEquals(FlashImportance.NEVER, base.flashImportance())
        val often = base.withFlashImportance(FlashImportance.OFTEN)
        assertEquals(setOf(ScenarioKind.FLICK, ScenarioKind.TRACKING, ScenarioKind.FLASH), often.enabledScenarios())
        assertEquals(35.0, often.toMetricWeights().values.getValue(ScoreMetric.FLASH_DODGE))
        assertEquals(FlashImportance.OFTEN, often.flashImportance())
        val never = often.withFlashImportance(FlashImportance.NEVER)
        assertEquals(setOf(ScenarioKind.FLICK, ScenarioKind.TRACKING), never.enabledScenarios())
        assertEquals(FlashImportance.NEVER, never.flashImportance())
        assertEquals(setOf(ScenarioKind.FLICK), AppSettings(scenarios = listOf("flash")).withFlashImportance(FlashImportance.NEVER).enabledScenarios(), "never leaves at least one scenario")
        assertEquals(FlashImportance.SOMETIMES, AppSettings().flashImportance())
        val store = store()
        store.save(often)
        assertEquals(FlashImportance.OFTEN, store.load().flashImportance())
    }
}
