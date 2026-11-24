package dev.veqzo.aimtune.app.screens

import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.ui.Table
import dev.veqzo.aimtune.app.AppContext
import dev.veqzo.aimtune.app.accel.PatchedChange
import dev.veqzo.aimtune.app.accel.RefineRun
import dev.veqzo.aimtune.app.accel.SettingsAnalyses
import dev.veqzo.aimtune.app.files.ExportFiles
import dev.veqzo.aimtune.app.ui.Styles
import dev.veqzo.aimtune.app.ui.addButton
import dev.veqzo.aimtune.app.ui.button
import dev.veqzo.aimtune.app.ui.caps
import dev.veqzo.aimtune.app.ui.compareChart
import dev.veqzo.aimtune.app.ui.label
import dev.veqzo.aimtune.core.accel.AbStatus
import dev.veqzo.aimtune.core.accel.RefinePhase
import java.nio.file.Path

private const val ACTION_GAP = 16f
private const val FEEL_WIDTH = 900f

/**
 * The end of a refine run, in the curve layout: the verdict of the blind test of the refined profile against the imported
 * one as the readout, and the two curves on the display. Only a candidate that was measurably better is offered for export,
 * as a copy of the player's file with one added profile; the changed fields are listed before and after saving. Passive
 * recommendations ticked on the setting pages go into the same copy and are marked "not blind-tested". Nothing is written
 * into Raw Accel's own folder.
 */
class RefineResultScreen(ctx: AppContext, private val run: RefineRun) : AccelFitScreen(ctx) {
    private val verdict = run.engine.verdict
    private val better = verdict?.status == AbStatus.ACCEL_BETTER
    private var exported: Result<Path>? = null
    private val analysesFuture = ctx.hub.analyses()
    private var analyses: SettingsAnalyses? = null

    private fun copy() = ctx.patchedCopy(run, analyses)

    override fun build(): Actor {
        val animate = firstBuild()
        val left = Table()
        left.headline(heading())
        verdict?.let { left.add(verdictReadout(it, animate)).left().width(CURVE_LEFT_WIDTH).padTop(CURVE_SECTION_GAP).row() }
        left.text(explanation(), gap = CURVE_SECTION_GAP)
        if (better) left.add(changesBlock()).left().width(CURVE_LEFT_WIDTH).padTop(CURVE_SECTION_GAP).row()

        val chart = if (run.engine.hasCandidate) compareChart(ui.tokens, run.profile, run.engine.candidate) else null
        val display = curveDisplay(t("curve.display.refined"), chart, animate, t("curve.display.empty_title"), t("curve.display.empty_result"))
        val right = Table()
        right.add(display).grow().row()
        val below = Table().top().left()
        if (run.engine.rounds.lastOrNull()?.phase == RefinePhase.TEST) below.add(refineFeelPanel(ctx, run, FEEL_WIDTH) { rebuild() }).left().row()
        below.add(refineFeelTestCard(ctx, run, FEEL_WIDTH)).left().padTop(CURVE_SMALL_GAP)
        right.add(below).left().padTop(CURVE_SECTION_GAP)

        val content = Table()
        content.add(curvePanel(verdictStatus(verdict), left, right)).grow()
        return frame(content, footer())
    }

    override fun render(delta: Float) {
        if (analyses == null && analysesFuture.isDone) {
            analyses = runCatching { analysesFuture.get() }.getOrNull()
            rebuild()
        }
        super.render(delta)
    }

    override fun onEscape() = ctx.showMenu()

    private fun footer(): Table {
        val footer = curveFooter(t("common.back_to_menu")) { ctx.showMenu() }
        if (better && exported?.isSuccess != true) {
            footer.addButton(ui.button(t("common.save_as"), Styles.SECONDARY) { saveAs() }, minWidth = 240f).padRight(ACTION_GAP)
            footer.addButton(ui.button(t("refine.result.export", run.exportName), Styles.PRIMARY) { export() }, minWidth = 420f)
        }
        return footer
    }

    private fun heading() = when {
        better -> t("refine.result.better")
        verdict?.status == AbStatus.ACCEL_WORSE -> t("refine.result.worse")
        verdict?.status == AbStatus.NO_DIFFERENCE -> t("refine.result.no_difference")
        verdict == null && !run.engine.hasCandidate -> t("refine.result.nothing")
        else -> t("refine.result.stopped")
    }

    /** What the verdict means; the numbers stand in the readout above. */
    private fun explanation(): String {
        val v = verdict ?: return if (run.engine.hasCandidate) t("accel.result.unfinished") else t("refine.result.nothing_note")
        return when (v.status) {
            AbStatus.ACCEL_BETTER -> t("refine.result.better_note")
            AbStatus.ACCEL_WORSE -> t("refine.result.worse_note")
            AbStatus.NO_DIFFERENCE -> t("refine.result.no_difference_note")
            AbStatus.NEED_MORE_ROUNDS -> t("curve.result.need_more")
        }
    }

    /** The fields the copy changes, and after saving where it went. */
    private fun changesBlock(): Table {
        val block = Table().top().left()
        val saved = exported?.isSuccess == true
        block.add(ui.caps(t(if (saved) "refine.result.changed_saved" else "refine.result.changed", run.exportName))).left().row()
        val lines = copy().changes().joinToString("\n") { line(it) }
        block.add(ui.label(lines, Styles.MONO, wrap = true)).left().width(CURVE_LEFT_WIDTH).padTop(CURVE_SMALL_GAP).row()
        block.add(ui.label(t("refine.result.export_note"), Styles.CAPTION_MUTED, wrap = true)).left().width(CURVE_LEFT_WIDTH).padTop(CURVE_SMALL_GAP).row()
        exported?.let { result ->
            val text = result.fold({ t("accel.result.saved", it) }, { t("accel.result.save_failed", it.message.orEmpty()) })
            block.add(ui.label(text, if (result.isSuccess) Styles.OK else Styles.CAUTION, wrap = true)).left().width(CURVE_LEFT_WIDTH).padTop(CURVE_SMALL_GAP * 2)
        }
        return block
    }

    private fun line(change: PatchedChange): String {
        val text = "${change.field}:  ${change.old}  ->  ${change.new}"
        return if (change.passive) "$text   (${t("hub.export.not_tested")})" else text
    }

    /** Asks where to save; a cancelled dialog changes nothing. */
    private fun saveAs() {
        val suggestion = ctx.exportDir?.resolve(ExportFiles.patchedFileName(run.exportName))
        val path = ctx.fileDialogs.saveJson(t("file.dialog.save_title"), suggestion) ?: return
        exported = ctx.savePatchedAs(copy(), path)
        rebuild()
    }

    private fun export() {
        exported = ctx.exportPatched(copy())
        rebuild()
    }
}
