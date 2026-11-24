package dev.veqzo.aimtune.app.screens

import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import dev.veqzo.aimtune.app.AppContext
import dev.veqzo.aimtune.app.accel.RefineRun
import dev.veqzo.aimtune.app.ui.Styles
import dev.veqzo.aimtune.app.ui.button
import dev.veqzo.aimtune.app.ui.label
import dev.veqzo.aimtune.app.ui.progressBar
import dev.veqzo.aimtune.core.accel.RefinePhase

private const val ROW_GAP = 24f

/**
 * The overlay before each warm-up, search or test round of the refine run: progress and what is going
 * on, never a value or an arm. The countdown waits until the feel question of the last round is
 * answered, so it never cuts the player off mid-answer.
 */
class RefineBetweenScreen(ctx: AppContext, private val run: RefineRun) : RoundOverlayScreen(ctx) {
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

    override val phaseName: String get() = when (phase) {
        RefinePhase.WARMUP -> t("refine.between.warmup")
        RefinePhase.SEARCH -> t("refine.between.search", engine.stageNumber + 1, engine.stages.size)
        else -> t("refine.between.test")
    }
    override val heading: String get() = if (done == 0) t("overlay.first_round") else t("accel.between.round_done", done)
    override val roundsDone: Int get() = done
    override val primaryText: String get() = if (done == 0) t("common.start") else t("common.next_round")
    override val stopText: String get() = t("common.stop")

    /** An open feel question comes first; the vote rebuilds the screen and the countdown starts then. */
    override val countdownWanted: Boolean get() = engine.rounds.lastOrNull()?.vote != null

    override fun next(autoStart: Boolean) = ctx.showRefineRound(run, autoStart)

    override fun stopRun() = ctx.showMenu()

    override fun body(width: Float): Actor {
        val body = Table()
        body.add(ctx.runsUseLine(run.runsUse, width)).left().width(width).padBottom(ROW_GAP).row()
        body.add(ui.progressBar(done.toFloat() / total, width)).left().padBottom(8f).row()
        body.add(ui.label(t("refine.between.progress", done, total), Styles.BODY_MUTED)).left().padBottom(ROW_GAP).row()
        body.add(ui.label(explanation(), Styles.CAPTION_MUTED, wrap = true)).left().width(width).row()
        if (done > 0) body.add(refineFeelPanel(ctx, run, width) { rebuild() }).left().padTop(ROW_GAP)
        return body
    }

    override fun extraActions(): List<TextButton> = listOfNotNull(
        if (phase == RefinePhase.WARMUP && done > 0) ui.button(t("refine.between.skip_warmup"), Styles.SECONDARY) { skipWarmup() } else null,
        if (engine.canFinishStageNow) ui.button(t("refine.between.finish_stage"), Styles.SECONDARY) { finishStage() } else null,
    )

    private fun skipWarmup() {
        engine.skipWarmup()
        ctx.showRefineStep(run)
    }

    private fun finishStage() {
        engine.finishStageNow()
        ctx.showRefineStep(run)
    }

    private fun explanation() = when (phase) {
        RefinePhase.WARMUP -> t("refine.between.explain_warmup", engine.warmupRounds)
        RefinePhase.SEARCH -> t("refine.between.explain_search", engine.searchMinRounds)
        else -> t("refine.between.explain_test", engine.maxTestRounds / 2)
    }
}
