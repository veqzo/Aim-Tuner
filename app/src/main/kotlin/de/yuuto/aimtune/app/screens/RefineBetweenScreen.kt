package de.yuuto.aimtune.app.screens

import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.ui.Table
import de.yuuto.aimtune.app.AppContext
import de.yuuto.aimtune.app.accel.RefineRun
import de.yuuto.aimtune.app.ui.Styles
import de.yuuto.aimtune.app.ui.addButton
import de.yuuto.aimtune.app.ui.button
import de.yuuto.aimtune.app.ui.caps
import de.yuuto.aimtune.app.ui.frame
import de.yuuto.aimtune.app.ui.label
import de.yuuto.aimtune.app.ui.progressBar
import de.yuuto.aimtune.core.accel.RefinePhase

private const val BAR_WIDTH = 640f
private const val TEXT_WIDTH = 1000f
private const val FOOTER_BUTTON_HEIGHT = 64f

/** Shown before each warm-up, search or test round of the refine run: progress and what is going on, never a value or an arm. */
class RefineBetweenScreen(ctx: AppContext, private val run: RefineRun) : UiScreen(ctx) {
    private val engine = run.engine
    private val phase = engine.phase
    private val done = when (phase) {
        RefinePhase.WARMUP -> engine.warmupPlayed
        RefinePhase.SEARCH -> engine.searchPlayed
        else -> engine.testRoundsDone
    }
    private val total = when (phase) {
        RefinePhase.WARMUP -> engine.warmupRounds
        RefinePhase.SEARCH -> engine.searchPlanned
        else -> engine.maxTestRounds
    }

    override fun build(): Actor {
        val content = Table()
        content.add(ui.label(if (done == 0) phaseName() else t("accel.between.round_done", done), Styles.HEADING)).left().padBottom(24f).row()
        content.add(ctx.runsUseLine(run.runsUse, TEXT_WIDTH, { ctx.showRefineStep(run) })).left().width(TEXT_WIDTH).padBottom(32f).row()
        content.add(ui.caps(phaseName())).left().padBottom(12f).row()
        content.add(ui.progressBar(done.toFloat() / total, BAR_WIDTH)).left().padBottom(12f).row()
        content.add(ui.label(t("refine.between.progress", done, total), Styles.BODY_MUTED)).left().padBottom(40f).row()
        content.add(ui.label(explanation(), Styles.CAPTION_MUTED, wrap = true)).left().width(TEXT_WIDTH).padBottom(32f).row()
        if (done > 0) content.add(refineFeelPanel(ctx, run) { rebuild() }).left()

        val footer = Table()
        footer.add(ui.button(t("common.stop"), Styles.SECONDARY) { ctx.showMenu() }).left().height(FOOTER_BUTTON_HEIGHT).padLeft(8f)
        footer.add().expandX()
        if (phase == RefinePhase.WARMUP && done > 0) {
            footer.addButton(ui.button(t("refine.between.skip_warmup"), Styles.SECONDARY) { skipWarmup() }, minWidth = 240f).padRight(16f)
        }
        if (engine.canFinishStageNow) {
            footer.addButton(ui.button(t("refine.between.finish_stage"), Styles.SECONDARY) { finishStage() }, minWidth = 240f).padRight(16f)
        }
        footer.addButton(ui.button(if (done == 0) t("common.start") else t("common.next_round"), Styles.PRIMARY) { ctx.showRefineRound(run) }, minWidth = 220f)
        return ui.frame(null, content, footer)
    }

    private fun skipWarmup() {
        engine.skipWarmup()
        ctx.showRefineStep(run)
    }

    private fun finishStage() {
        engine.finishStageNow()
        ctx.showRefineStep(run)
    }

    override fun onEnter() = ctx.showRefineRound(run)

    override fun onEscape() = ctx.showMenu()

    private fun phaseName() = when (phase) {
        RefinePhase.WARMUP -> t("refine.between.warmup")
        RefinePhase.SEARCH -> t("refine.between.search", engine.stageNumber + 1, engine.stages.size)
        else -> t("refine.between.test")
    }

    private fun explanation() = when (phase) {
        RefinePhase.WARMUP -> t("refine.between.explain_warmup", engine.warmupRounds)
        RefinePhase.SEARCH -> t("refine.between.explain_search", engine.searchMinRounds)
        else -> t("refine.between.explain_test", engine.maxTestRounds / 2)
    }
}
