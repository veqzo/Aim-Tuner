package de.yuuto.aimtune.app.setup

import de.yuuto.aimtune.core.units.AimStyle
import de.yuuto.aimtune.games.GameProfiles
import de.yuuto.aimtune.storage.AppSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SetupDraftTest {
    private val profiles = GameProfiles.load()

    private val complete = SetupDraft(
        dpiText = "800", gameId = "cs2", sensitivityText = "1.20", aimStyle = AimStyle.MIXED, padPreset = PadPreset.LARGE,
    )

    @Test
    fun `numbers accept dot or comma and reject junk and non positive values`() {
        assertEquals(1.24, parsePositive("1.24"))
        assertEquals(1.24, parsePositive(" 1,24 "))
        assertEquals(800.0, parsePositive("800"))
        assertNull(parsePositive(""))
        assertNull(parsePositive("abc"))
        assertNull(parsePositive("-3"))
        assertNull(parsePositive("0"))
        assertNull(parsePositive("1e999"))
        assertNull(parsePositive("NaN"))
    }

    @Test
    fun `current cm360 is computed live from dpi game and sensitivity`() {
        assertEquals(43.3, assertNotNull(complete.currentCm360(profiles)), 0.05)
        assertNull(complete.copy(dpiText = "").currentCm360(profiles))
        assertNull(complete.copy(sensitivityText = "x").currentCm360(profiles))
        assertNull(complete.copy(gameId = null).currentCm360(profiles))
    }

    @Test
    fun `each step reports what is missing`() {
        val empty = SetupDraft()
        assertEquals(SetupIssue.DPI_INVALID, empty.issue(SetupStep.DPI, profiles))
        assertEquals(SetupIssue.GAME_MISSING, empty.copy(dpiText = "800").issue(SetupStep.GAME, profiles))
        assertEquals(SetupIssue.SENSITIVITY_INVALID, complete.copy(sensitivityText = "").issue(SetupStep.GAME, profiles))
        assertEquals(SetupIssue.STYLE_MISSING, complete.copy(aimStyle = null).issue(SetupStep.STYLE, profiles))
        assertEquals(SetupIssue.PAD_MISSING, complete.copy(padPreset = null).issue(SetupStep.PAD, profiles))
        assertNull(complete.issue(SetupStep.DPI, profiles))
        assertNull(complete.issue(SetupStep.GAME, profiles))
        assertNull(complete.issue(SetupStep.STYLE, profiles))
        assertNull(complete.issue(SetupStep.PAD, profiles))
    }

    @Test
    fun `dpi must be plausible`() {
        assertEquals(SetupIssue.DPI_INVALID, complete.copy(dpiText = "5000000").issue(SetupStep.DPI, profiles))
    }

    @Test
    fun `custom game needs yaw and fov`() {
        val custom = complete.copy(gameId = GAME_CUSTOM)
        assertEquals(SetupIssue.CUSTOM_YAW_INVALID, custom.issue(SetupStep.GAME, profiles))
        assertEquals(SetupIssue.CUSTOM_FOV_INVALID, custom.copy(customYawText = "0.01").issue(SetupStep.GAME, profiles))
        val ok = custom.copy(customYawText = "0.01", customFovText = "100")
        assertNull(ok.issue(SetupStep.GAME, profiles))
        assertEquals(914.4 / (800 * 1.2 * 0.01), assertNotNull(ok.currentCm360(profiles)), 1e-9)
        assertEquals(SetupIssue.CUSTOM_FOV_INVALID, ok.copy(customFovText = "200").issue(SetupStep.GAME, profiles))
    }

    @Test
    fun `custom pad needs width and height`() {
        val custom = complete.copy(padPreset = PadPreset.CUSTOM)
        assertEquals(SetupIssue.PAD_SIZE_INVALID, custom.issue(SetupStep.PAD, profiles))
        val ok = custom.copy(customPadWidthText = "50", customPadHeightText = "40")
        assertNull(ok.issue(SetupStep.PAD, profiles))
        assertEquals(50.0 to 40.0, ok.padSizeCm())
        assertEquals(45.0 to 40.0, complete.padSizeCm())
    }

    @Test
    fun `finishing produces complete settings`() {
        val settings = complete.toSettings(AppSettings(roundSeconds = 60), profiles)
        assertEquals(800.0, settings.dpi)
        assertEquals("cs2", settings.gameId)
        assertEquals(1.2, settings.inGameSensitivity)
        assertEquals("MIXED", settings.aimStyle)
        assertEquals(45.0, settings.padWidthCm)
        assertEquals(40.0, settings.padHeightCm)
        assertEquals(60, settings.roundSeconds)
        assertNull(settings.customYaw)
    }

    @Test
    fun `finishing keeps chosen scenarios, drops range for a game without a Range mode and gives untouched lists the game's defaults`() {
        val chosen = AppSettings(scenarios = listOf("flick", "micro", "range", "flash"))
        assertEquals(listOf("flick", "micro", "range", "flash"), complete.copy(gameId = "valorant").toSettings(chosen, profiles).scenarios)
        assertEquals(listOf("flick", "micro", "flash"), complete.copy(gameId = "cs2").toSettings(chosen, profiles).scenarios)
        val untouched = AppSettings()
        assertEquals(listOf("flick", "range", "micro-strafe", "switch-strafe", "flash"), complete.copy(gameId = "valorant").toSettings(untouched, profiles).scenarios)
        assertEquals(AppSettings.DEFAULT_SCENARIOS, complete.copy(gameId = "cs2").toSettings(untouched, profiles).scenarios)
        // From CS2's defaults back to Valorant: the list was not chosen, so Valorant's own defaults come back.
        assertEquals(AppSettings.defaultScenarios(hasRange = true, hasMix = true), complete.copy(gameId = "valorant").toSettings(AppSettings(scenarios = AppSettings.DEFAULT_SCENARIOS), profiles).scenarios)
    }

    @Test
    fun `an incomplete draft cannot be turned into settings`() {
        assertFailsWith<IllegalStateException> { SetupDraft().toSettings(AppSettings(), profiles) }
    }

    @Test
    fun `settings round trip through a draft`() {
        val settings = complete.copy(gameId = GAME_CUSTOM, customYawText = "0.01", customFovText = "100").toSettings(AppSettings(), profiles)
        val draft = SetupDraft.fromSettings(settings)
        assertEquals("800", draft.dpiText)
        assertEquals("1.2", draft.sensitivityText)
        assertEquals("0.01", draft.customYawText)
        assertEquals(PadPreset.LARGE, draft.padPreset)
        assertEquals(settings, draft.toSettings(AppSettings(), profiles))
    }

    @Test
    fun `an unknown pad size comes back as a custom pad`() {
        val draft = SetupDraft.fromSettings(AppSettings(padWidthCm = 61.0, padHeightCm = 33.0))
        assertEquals(PadPreset.CUSTOM, draft.padPreset)
        assertEquals("61", draft.customPadWidthText)
    }

    @Test
    fun `a sensitivity that makes a turn shorter than 3 cm or longer than 6 m is refused`() {
        assertEquals(SetupIssue.CM360_IMPLAUSIBLE, complete.copy(sensitivityText = "500").issue(SetupStep.GAME, profiles))
        assertEquals(SetupIssue.CM360_IMPLAUSIBLE, complete.copy(sensitivityText = "0.0005").issue(SetupStep.GAME, profiles))
        assertNull(complete.copy(sensitivityText = "0.5").issue(SetupStep.GAME, profiles))
    }
}
