package de.yuuto.aimtune.app.screens

import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.ui.CheckBox
import com.badlogic.gdx.scenes.scene2d.ui.Table
import de.yuuto.aimtune.app.AccelBaseline
import de.yuuto.aimtune.app.AppContext
import de.yuuto.aimtune.app.FinderResult
import de.yuuto.aimtune.app.accel.AccelRun
import de.yuuto.aimtune.app.accel.StrengthSearch
import de.yuuto.aimtune.app.ui.Styles
import de.yuuto.aimtune.app.ui.addButton
import de.yuuto.aimtune.app.ui.button
import de.yuuto.aimtune.app.ui.frame
import de.yuuto.aimtune.app.ui.label
import de.yuuto.aimtune.app.ui.onChange
import de.yuuto.aimtune.input.windows.WindowsPointerSettings
import java.util.Locale

private const val TEXT_WIDTH = 880f
private const val CARD_WIDTH = 840f
private const val COLUMN_GAP = 56f
private const val FOOTER_BUTTON_HEIGHT = 64f

/**
 * Entry of the acceleration steps: what they are, the sensitivity they are played at (the player's game sensitivity, or
 * the finder's result when it is confident and switched on), and the check that Raw Accel is off, since the calibration
 * needs a plain mouse.
 */
class AccelIntroScreen(ctx: AppContext, private val baseline: AccelBaseline? = ctx.accelBaseline()) : UiScreen(ctx) {
    private val pointerAcceleration = WindowsPointerSettings.readAcceleration()

    override fun build(): Actor {
        // Two columns, so the screen fits 1080 lines: what the steps are on the left, the setup they start from on the right.
        val left = Table()
        left.add(ui.label(t("accel.title"), Styles.HEADING)).left().padBottom(24f).row()
        left.add(
            ui.label(t("accel.intro", StrengthSearch.DEFAULT_ROUNDS, AccelRun.DEFAULT_CALIBRATION_ROUNDS), Styles.BODY_MUTED, wrap = true),
        ).left().width(TEXT_WIDTH).padBottom(16f).row()
        left.add(ui.label(t("accel.experimental"), Styles.CAUTION, wrap = true)).left().width(TEXT_WIDTH).row()

        val right = Table()
        right.add(ctx.runsUseCard(ctx.runsUse(baseline), CARD_WIDTH) { ctx.showAccel() }).left().width(CARD_WIDTH).padBottom(24f).row()
        right.add(rawAccelCheck()).left().row()
        if (baseline != null && baseline.canUseFinder) right.add(finderCheck(baseline)).left().padTop(24f).row()
        if (baseline?.ownCurveLabel != null) right.add(ownCurveBlock(baseline)).left().padTop(24f).row()
        val searchRounds = baseline?.searchRounds(ctx.useFinderResult) ?: 0
        if (searchRounds > 0) right.add(priorSearchCheck(searchRounds)).left().padTop(24f).row()
        if (!ctx.source.hasReportTimestamps) {
            right.add(ui.label(t("accel.needs_raw_input", ctx.source.name), Styles.CAUTION, wrap = true)).left().width(CARD_WIDTH).padTop(24f)
        }

        val content = Table()
        content.add(left).top().left().width(TEXT_WIDTH)
        content.add(right).top().left().width(CARD_WIDTH).padLeft(COLUMN_GAP)

        val footer = Table()
        footer.add(ui.button(t("common.back"), Styles.TEXT) { ctx.showRawAccelHub() }).left().height(FOOTER_BUTTON_HEIGHT).padLeft(8f)
        footer.add().expandX()
        val calibrate = ui.button(t("accel.start_calibration"), Styles.SECONDARY) { if (canStart()) ctx.startAccelRun() }
        calibrate.isDisabled = !canStart()
        footer.addButton(calibrate, minWidth = 340f).padRight(16f)
        val search = ui.button(t("accel.start_search"), Styles.PRIMARY) { if (canStart()) ctx.startAccelRun(search = true) }
        search.isDisabled = !canStart()
        footer.addButton(search, minWidth = 340f)
        return ui.frame(null, content, footer)
    }

    override fun onEscape() = ctx.showRawAccelHub()

    override fun onEnter() {
        if (canStart()) ctx.startAccelRun(search = true)
    }

    private fun canStart() = baseline != null && ctx.rawAccelConfirmed && ctx.source.hasReportTimestamps

    private fun rawAccelCheck(): Table {
        val block = Table()
        val box = CheckBox("  " + t("pretest.raw_accel_checked"), ui.skin)
        box.isChecked = ctx.rawAccelConfirmed
        box.onChange {
            ctx.rawAccelConfirmed = box.isChecked
            rebuild()
        }
        block.add(box).left().row()
        val windows = if (pointerAcceleration?.enabled == true) t("accel.windows_on") else ""
        if (windows.isNotEmpty()) block.add(ui.label(windows, Styles.CAUTION, wrap = true)).left().width(CARD_WIDTH).padTop(8f)
        return block
    }

    /** Lets the player play the curve runs at the finder's result; shown only when the finder is confident enough. */
    private fun finderCheck(baseline: AccelBaseline): Table {
        val finder = checkNotNull(baseline.finder)
        val block = Table()
        val label = t("accel.use_finder", "%.1f".format(Locale.ROOT, finder.cm360), baseline.gameName, finder.sensitivityText, finder.rounds, finder.confidencePercent)
        val box = CheckBox("  $label", ui.skin)
        box.isChecked = ctx.useFinderResult
        box.onChange {
            ctx.useFinderResult = box.isChecked
            rebuild()
        }
        block.add(box).left().row()
        block.add(ui.label(t("accel.use_finder_help", FinderResult.MIN_CONFIDENCE), Styles.CAPTION_MUTED, wrap = true)).left().width(CARD_WIDTH).padTop(8f)
        return block
    }

    /**
     * The curve imported in the hub: a checkbox to calibrate against it when the trainer can play it at the DPI of the settings,
     * otherwise the reason it is not used.
     */
    private fun ownCurveBlock(baseline: AccelBaseline): Table {
        val block = Table()
        val label = checkNotNull(baseline.ownCurveLabel)
        val fileDpi = baseline.ownCurveFileDpi
        if (baseline.ownCurve == null || fileDpi != null) {
            val reason = when (fileDpi) {
                null -> t("accel.own_refused", label)
                0 -> t("accel.own_dpi_unset", label)
                else -> t("accel.own_dpi_mismatch", label, fileDpi, "%.0f".format(Locale.ROOT, baseline.settingsDpi))
            }
            block.add(ui.label(reason, Styles.CAPTION_MUTED, wrap = true)).left().width(CARD_WIDTH)
            return block
        }
        val box = CheckBox("  " + t("accel.use_own", label), ui.skin)
        box.isChecked = ctx.useOwnCurve
        box.onChange { ctx.useOwnCurve = box.isChecked }
        block.add(box).left().row()
        block.add(ui.label(t("accel.use_own_help"), Styles.CAPTION_MUTED, wrap = true)).left().width(CARD_WIDTH).padTop(8f)
        return block
    }

    /** Lets the player start a strength search that ignores the stored ones (they are kept, not deleted). */
    private fun priorSearchCheck(rounds: Int): Table {
        val block = Table()
        val box = CheckBox("  " + t("accel.use_prior", rounds), ui.skin)
        box.isChecked = ctx.usePriorSearch
        box.onChange { ctx.usePriorSearch = box.isChecked }
        block.add(box).left().row()
        block.add(ui.label(t("accel.use_prior_help"), Styles.CAPTION_MUTED, wrap = true)).left().width(CARD_WIDTH).padTop(8f)
        return block
    }
}
