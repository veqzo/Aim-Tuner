package dev.veqzo.aimtune.app.screens

import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.ui.Table
import dev.veqzo.aimtune.app.AppContext
import dev.veqzo.aimtune.app.accel.RawAccelSetting
import dev.veqzo.aimtune.app.accel.SettingKind
import dev.veqzo.aimtune.app.accel.SettingPage
import dev.veqzo.aimtune.app.accel.SettingPages
import dev.veqzo.aimtune.app.accel.SettingsAnalyses
import dev.veqzo.aimtune.app.ui.LedState
import dev.veqzo.aimtune.app.ui.ModuleStatus
import dev.veqzo.aimtune.app.ui.Styles
import dev.veqzo.aimtune.app.ui.label
import dev.veqzo.aimtune.core.accel.settings.PassiveResult
import java.util.concurrent.CompletableFuture

private const val WELL_INNER_WIDTH = 900f
private val FORMULA_SEPARATOR = Regex(" {4,}")

/**
 * One generic page for every setting on the back of the CURVE module, in the curve layout, fed by a [SettingPage]. Left:
 * what the setting does (words and formula), the value in the imported profile, and for an info row why AimTune does not
 * measure it. Right, in a recessed panel: the recommended value and why, the result with its range for an analysis row, and
 * for rotation, Y/X or L/R the tick that puts the recommendation into the patched copy (off by default, kept for the session).
 */
class RawAccelSettingScreen(ctx: AppContext, private val setting: RawAccelSetting, private val analysesFuture: CompletableFuture<SettingsAnalyses>) : AccelFitScreen(ctx) {
    private var analyses: SettingsAnalyses? = null

    private fun page(): SettingPage =
        SettingPages({ key, args -> ctx.i18n.t(key, *args) }, analyses, ctx.hub.imported?.profile(ctx.trainerConfig()?.dpi ?: 0.0)).page(setting)

    override fun build(): Actor {
        val page = page()
        val left = Table()
        left.headline(page.name)
        left.caps(t("hub.page.what"))
        left.text(page.what, gap = CURVE_SMALL_GAP)
        // The formulas of a page are separated by runs of spaces; each gets a line of its own.
        page.formula.split(FORMULA_SEPARATOR).forEach { left.text(it, Styles.MONO, CURVE_SMALL_GAP) }
        left.caps(t("hub.page.current"))
        left.text(page.current, Styles.MONO, CURVE_SMALL_GAP)
        page.notMeasured?.let {
            left.caps(t("hub.page.not_measured"))
            left.text(it, gap = CURVE_SMALL_GAP)
        }

        val content = Table()
        content.add(curvePanel(ModuleStatus(LedState.OFF, page.badge), left, right(page))).grow()
        return frame(content, curveFooter(t("common.back")) { back() })
    }

    /** The recommendation, its numbers and the export tick, in a recessed panel. */
    private fun right(page: SettingPage): Table {
        val well = infoWell()
        well.add(ui.label(t("hub.page.recommended"), Styles.LABEL)).left().row()
        well.add(ui.label(page.recommended, Styles.TITLE, wrap = true)).left().width(WELL_INNER_WIDTH).padTop(CURVE_SMALL_GAP).row()
        well.add(ui.label(page.why, Styles.BODY_MUTED, wrap = true)).left().width(WELL_INNER_WIDTH).padTop(CURVE_SMALL_GAP).row()
        if (page.result.isNotEmpty() || analyses == null) {
            well.add(ui.label(t(if (setting.kind == SettingKind.ANALYSIS) "hub.page.result" else "hub.page.measured"), Styles.LABEL)).left().padTop(CURVE_SECTION_GAP).row()
            if (analyses == null) well.add(ui.label(t("hub.state.computing"), Styles.BODY_MUTED)).left().padTop(CURVE_SMALL_GAP).row()
            page.result.forEach { well.add(ui.label(it, Styles.MONO, wrap = true)).left().width(WELL_INNER_WIDTH).padTop(CURVE_SMALL_GAP).row() }
        }
        exportToggle(page)?.let { well.add(it).left().padTop(CURVE_SECTION_GAP).row() }
        well.add().expand()
        return well
    }

    override fun render(delta: Float) {
        if (analyses == null && analysesFuture.isDone) {
            analyses = runCatching { analysesFuture.get() }.getOrNull()
            rebuild()
        }
        super.render(delta)
    }

    override fun onEscape() = back()

    private fun back() = ctx.showMenu(curveBack = true)

    /** The opt-in for the patched copy: off until the player ticks it, and kept while the app runs. Without an imported profile only a note. */
    private fun exportToggle(page: SettingPage): Table? {
        if (!setting.passive) return null
        if (page.exportable == null) {
            if (analyses?.result(setting) !is PassiveResult.Recommend) return null
            return Table().also { it.add(ui.label(t("hub.toggle.needs_profile"), Styles.CAPTION_MUTED, wrap = true)).left().width(WELL_INNER_WIDTH) }
        }
        return checkBlock(t("hub.toggle.label"), ctx.hub.isTicked(setting), t("hub.toggle.help"), WELL_INNER_WIDTH) { ctx.hub.setTicked(setting, it) }
    }
}
