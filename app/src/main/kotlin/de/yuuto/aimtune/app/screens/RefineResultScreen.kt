package de.yuuto.aimtune.app.screens

import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.ui.Table
import de.yuuto.aimtune.app.AppContext
import de.yuuto.aimtune.app.accel.PatchedChange
import de.yuuto.aimtune.app.accel.RefineRun
import de.yuuto.aimtune.app.accel.SettingsAnalyses
import de.yuuto.aimtune.app.files.ExportFiles
import de.yuuto.aimtune.app.ui.Styles
import de.yuuto.aimtune.app.ui.addButton
import de.yuuto.aimtune.app.ui.button
import de.yuuto.aimtune.app.ui.caps
import de.yuuto.aimtune.app.ui.frame
import de.yuuto.aimtune.app.ui.label
import de.yuuto.aimtune.core.accel.AbStatus
import java.nio.file.Path
import java.util.Locale

private const val TEXT_WIDTH = 1000f
private const val CARD_WIDTH = 900f
private const val CARD_PADDING = 32f
private const val FOOTER_BUTTON_HEIGHT = 64f

/**
 * The end of a refine run: the verdict of the blind test of the refined profile against the imported one. Only a candidate
 * that was measurably better is offered for export, as a copy of the player's file with one added profile; the changed fields
 * are listed before and after saving. Passive recommendations ticked on the setting pages go into the same copy and are marked
 * "not blind-tested". Nothing is written into Raw Accel's own folder.
 */
class RefineResultScreen(ctx: AppContext, private val run: RefineRun) : UiScreen(ctx) {
    private val verdict = run.engine.verdict
    private val better = verdict?.status == AbStatus.ACCEL_BETTER
    private var exported: Result<Path>? = null
    private val analysesFuture = ctx.hub.analyses()
    private var analyses: SettingsAnalyses? = null

    private fun copy() = ctx.patchedCopy(run, analyses)

    override fun build(): Actor {
        val content = Table()
        content.add(ui.label(heading(), Styles.HEADING)).left().padBottom(24f).row()
        content.add(ui.label(explanation(), Styles.BODY_MUTED, wrap = true)).left().width(TEXT_WIDTH).padBottom(24f).row()
        if (better) content.add(diffCard()).left().width(CARD_WIDTH).padBottom(24f).row()
        if (run.engine.rounds.lastOrNull()?.phase == de.yuuto.aimtune.core.accel.RefinePhase.TEST) content.add(refineFeelPanel(ctx, run) { rebuild() }).left().padBottom(16f).row()
        content.add(refineFeelTestCard(ctx, run, CARD_WIDTH)).left().row()

        val footer = Table()
        footer.add(ui.button(t("common.back_to_menu"), Styles.TEXT) { ctx.showMenu() }).left().height(FOOTER_BUTTON_HEIGHT).padLeft(8f)
        footer.add().expandX()
        if (better && exported?.isSuccess != true) {
            footer.addButton(ui.button(t("common.save_as"), Styles.SECONDARY) { saveAs() }, minWidth = 240f).padRight(16f)
            footer.addButton(ui.button(t("refine.result.export", run.exportName), Styles.PRIMARY) { export() }, minWidth = 420f)
        }
        return ui.frame(null, content, footer)
    }

    override fun render(delta: Float) {
        if (analyses == null && analysesFuture.isDone) {
            analyses = runCatching { analysesFuture.get() }.getOrNull()
            rebuild()
        }
        super.render(delta)
    }

    override fun onEscape() = ctx.showMenu()

    private fun heading() = when {
        better -> t("refine.result.better")
        verdict?.status == AbStatus.ACCEL_WORSE -> t("refine.result.worse")
        verdict?.status == AbStatus.NO_DIFFERENCE -> t("refine.result.no_difference")
        verdict == null && !run.engine.hasCandidate -> t("refine.result.nothing")
        else -> t("refine.result.stopped")
    }

    private fun explanation(): String {
        val v = verdict ?: return if (run.engine.hasCandidate) t("accel.result.unfinished") else t("refine.result.nothing_note")
        val numbers = t(
            "accel.result.numbers", v.pairs, "%+.1f".format(Locale.ROOT, v.meanDifference), "%+.1f".format(Locale.ROOT, v.lowerBound), "%+.1f".format(Locale.ROOT, v.upperBound),
        )
        return when (v.status) {
            AbStatus.ACCEL_BETTER -> "$numbers " + t("refine.result.better_note")
            AbStatus.ACCEL_WORSE -> "$numbers " + t("refine.result.worse_note")
            AbStatus.NO_DIFFERENCE -> "$numbers " + t("refine.result.no_difference_note")
            AbStatus.NEED_MORE_ROUNDS -> numbers
        }
    }

    private fun diffCard(): Table {
        val inner = CARD_WIDTH - 2 * CARD_PADDING
        val card = Table()
        card.background(ui.skin.getDrawable(Styles.CARD))
        card.pad(CARD_PADDING)
        val saved = exported?.isSuccess == true
        card.add(ui.caps(t(if (saved) "refine.result.changed_saved" else "refine.result.changed", run.exportName))).left().row()
        val lines = copy().changes().joinToString("\n") { line(it) }
        card.add(ui.label(lines, Styles.MONO, wrap = true)).left().width(inner).padTop(8f).row()
        card.add(ui.label(t("refine.result.export_note"), Styles.CAPTION_MUTED, wrap = true)).left().width(inner).padTop(12f).row()
        exported?.let { result ->
            val text = result.fold({ t("accel.result.saved", it) }, { t("accel.result.save_failed", it.message.orEmpty()) })
            card.add(ui.label(text, if (result.isSuccess) Styles.OK else Styles.CAUTION, wrap = true)).left().width(inner).padTop(16f)
        }
        return card
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
