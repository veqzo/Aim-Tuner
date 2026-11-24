package de.yuuto.aimtune.app.screens

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.ui.Table
import de.yuuto.aimtune.app.AppContext
import de.yuuto.aimtune.app.accel.AccelRun
import de.yuuto.aimtune.app.ui.LedState
import de.yuuto.aimtune.app.ui.ModuleStatus
import de.yuuto.aimtune.app.ui.Motion
import de.yuuto.aimtune.app.ui.Styles
import de.yuuto.aimtune.app.ui.addButton
import de.yuuto.aimtune.app.ui.button
import de.yuuto.aimtune.app.ui.caps
import de.yuuto.aimtune.app.ui.fitChart
import de.yuuto.aimtune.app.ui.label
import de.yuuto.aimtune.core.accel.AbStatus
import de.yuuto.aimtune.core.accel.AbVerdict
import de.yuuto.aimtune.storage.RawAccelExport
import java.nio.file.Path
import java.util.Locale

private const val WELL_PADDING_X = 36f
private const val WELL_PADDING_Y = 24f
private const val ACTION_GAP = 16f

/**
 * The verdict of the blind test, in the curve layout: the score difference as the readout, what it means, and the tested
 * curve on the display. Only when the curve was measurably better is a Raw Accel file offered; otherwise the plain
 * sensitivity stays what AimTune recommends.
 */
class AccelResultScreen(ctx: AppContext, private val run: AccelRun) : AccelFitScreen(ctx) {
    private val verdict: AbVerdict? = run.verdict
    private var exported: Result<Path>? = null

    override fun build(): Actor {
        val animate = firstBuild()
        val left = Table()
        left.headline(heading())
        verdict?.let { left.add(difference(it, animate)).left().width(CURVE_LEFT_WIDTH).padTop(CURVE_SECTION_GAP).row() }
        left.text(explanation(), gap = CURVE_SECTION_GAP)
        if (verdict?.status == AbStatus.ACCEL_BETTER && !run.isDraft) left.add(exportBlock()).left().padTop(CURVE_SECTION_GAP).row()

        val curve = run.candidate
        val display = curveDisplay(t("curve.display.tested"), curve?.let { fitChart(ui.tokens, it) }, animate, t("curve.display.empty_title"), t("curve.display.empty_result"))
        val content = Table()
        content.add(curvePanel(status(), left, display)).grow()
        return frame(content, footer())
    }

    override fun onEscape() = ctx.showMenu()

    private fun footer(): Table {
        val footer = curveFooter(t("common.back_to_menu")) { ctx.showMenu() }
        if (run.isDraft) {
            // A test of a draft only warns: whatever the verdict, the stage can be saved as a copy of the player's own file.
            footer.addButton(ui.button(t("accel.draft.export"), Styles.PRIMARY) { ctx.showDraftExport(run) }, minWidth = 340f)
        } else if (verdict?.status == AbStatus.ACCEL_BETTER) {
            footer.addButton(ui.button(t("accel.result.copy"), Styles.SECONDARY) { copy() }, minWidth = 260f).padRight(ACTION_GAP)
            footer.addButton(ui.button(t("common.save_as"), Styles.SECONDARY) { saveAs() }, minWidth = 240f).padRight(ACTION_GAP)
            footer.addButton(ui.button(t("accel.result.export"), Styles.PRIMARY) { export() }, minWidth = 300f)
        }
        return footer
    }

    private fun status(): ModuleStatus = when (verdict?.status) {
        AbStatus.ACCEL_BETTER -> ModuleStatus(LedState.OK, t("curve.status.better"))
        AbStatus.ACCEL_WORSE -> ModuleStatus(LedState.CAUTION, t("curve.status.worse"))
        AbStatus.NO_DIFFERENCE -> ModuleStatus(LedState.OFF, t("curve.status.same"))
        else -> ModuleStatus(LedState.OFF, t("curve.status.stopped"))
    }

    private fun heading() = when (verdict?.status) {
        AbStatus.ACCEL_BETTER -> t("accel.result.better")
        AbStatus.ACCEL_WORSE -> t("accel.result.worse")
        AbStatus.NO_DIFFERENCE -> t("accel.result.no_difference")
        else -> t("accel.result.stopped")
    }

    /** The recessed readout: the mean score difference of the curve against plain, then the pairs and the interval. */
    private fun difference(v: AbVerdict, animate: Boolean): Table {
        val well = Table().left()
        well.background(ui.skin.getDrawable(Styles.WELL))
        well.pad(WELL_PADDING_Y, WELL_PADDING_X, WELL_PADDING_Y, WELL_PADDING_X)
        well.add(ui.label(t("curve.result.difference"), Styles.LABEL)).left().row()
        val format = { value: Double -> "%+.1f".format(Locale.ROOT, value) }
        val number = ui.label(format(v.meanDifference), Styles.NUMBER_LG)
        if (animate) Motion.countUp(number, 0.0, v.meanDifference, ui.tokens.motionSlow * 2, format = format)
        well.add(number).left().row()
        well.add(ui.label(t("curve.result.interval", v.pairs, format(v.lowerBound), format(v.upperBound)), Styles.MONO_MUTED)).left().padTop(CURVE_SMALL_GAP)
        return well
    }

    /** What the verdict means for the player; the numbers stand in the readout above. */
    private fun explanation(): String {
        val v = verdict ?: return t("accel.result.unfinished")
        val trial = when {
            run.isTrial -> " " + t("accel.result.trial_note")
            run.isDraft -> " " + t("accel.result.draft_note")
            run.isSearch -> " " + t("accel.result.search_note")
            else -> ""
        }
        return when (v.status) {
            AbStatus.ACCEL_BETTER -> t("accel.result.better_note") + trial
            AbStatus.ACCEL_WORSE -> t(if (run.isDraft) "accel.result.draft_worse_note" else "accel.result.worse_note")
            AbStatus.NO_DIFFERENCE -> t("accel.result.no_difference_note")
            AbStatus.NEED_MORE_ROUNDS -> t("curve.result.need_more")
        }
    }

    private fun exportBlock(): Table {
        val block = Table().top().left()
        block.add(ui.caps(t("accel.result.raw_accel_title"))).left().row()
        block.add(ui.label(t("accel.result.raw_accel_text"), Styles.CAPTION_MUTED, wrap = true)).left().width(CURVE_LEFT_WIDTH).padTop(CURVE_SMALL_GAP).row()
        exported?.let { result ->
            val text = result.fold({ t("accel.result.saved", it) }, { t("accel.result.save_failed", it.message.orEmpty()) })
            block.add(ui.label(text, if (result.isSuccess) Styles.OK else Styles.CAUTION, wrap = true)).left().width(CURVE_LEFT_WIDTH).padTop(CURVE_SMALL_GAP * 2)
        }
        return block
    }

    private fun export() {
        exported = ctx.exportAccel(run)
        rebuild()
    }

    /** Asks where to save; a cancelled dialog changes nothing. */
    private fun saveAs() {
        val path = ctx.fileDialogs.saveJson(t("file.dialog.save_title"), ctx.exportDir?.resolve("settings.json")) ?: return
        exported = ctx.saveAccelAs(run, path)
        rebuild()
    }

    private fun copy() {
        val candidate = run.candidate ?: return
        Gdx.app.clipboard.contents = RawAccelExport.settingsJson(candidate.curve, run.dpi, candidate.sensitivityFactor)
    }
}
