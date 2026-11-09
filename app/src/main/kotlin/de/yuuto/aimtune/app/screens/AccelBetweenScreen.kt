package de.yuuto.aimtune.app.screens

import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.ui.Table
import de.yuuto.aimtune.app.AppContext
import de.yuuto.aimtune.app.accel.AccelPhase
import de.yuuto.aimtune.app.accel.AccelRun
import de.yuuto.aimtune.app.ui.Styles
import de.yuuto.aimtune.app.ui.addButton
import de.yuuto.aimtune.app.ui.button
import de.yuuto.aimtune.app.ui.caps
import de.yuuto.aimtune.app.ui.frame
import de.yuuto.aimtune.app.ui.label
import de.yuuto.aimtune.app.ui.progressBar

private const val BAR_WIDTH = 640f
private const val TEXT_WIDTH = 1000f
private const val FOOTER_BUTTON_HEIGHT = 64f

/**
 * Shown before every calibration or test round: how far the run is and what is going on, never which
 * condition the next test round has and never a verdict before the test has decided.
 */
class AccelBetweenScreen(ctx: AppContext, private val run: AccelRun) : UiScreen(ctx) {
    private val calibrating = run.phase == AccelPhase.CALIBRATION
    private val searching = run.phase == AccelPhase.SEARCH
    private val done = when {
        calibrating -> run.calibrationDone
        searching -> run.searchDone
        else -> run.testRoundsDone
    }
    private val total = when {
        calibrating -> run.calibrationRounds
        searching -> run.searchRounds
        else -> run.maxTestRounds
    }

    override fun build(): Actor {
        val content = Table()
        content.add(ui.label(heading(), Styles.HEADING)).left().padBottom(24f).row()
        content.add(ctx.runsUseLine(run.runsUse, TEXT_WIDTH, { ctx.showAccelStep(run) })).left().width(TEXT_WIDTH).padBottom(32f).row()
        content.add(ui.caps(phaseName())).left().padBottom(12f).row()
        content.add(ui.progressBar(done.toFloat() / total, BAR_WIDTH)).left().padBottom(12f).row()
        content.add(ui.label(progressText(), Styles.BODY_MUTED)).left().padBottom(40f).row()
        content.add(ui.label(explanation(), Styles.CAPTION_MUTED, wrap = true)).left().width(TEXT_WIDTH)

        val footer = Table()
        footer.add(ui.button(t("common.stop"), Styles.SECONDARY) { ctx.showMenu() }).left().height(FOOTER_BUTTON_HEIGHT).padLeft(8f)
        footer.add().expandX()
        if (run.canAnalyseNow) {
            footer.addButton(ui.button(t("accel.between.analyse_now"), Styles.SECONDARY) { analyseNow() }, minWidth = 220f).padRight(16f)
        }
        if (run.canFinishSearchNow) {
            footer.addButton(ui.button(t("accel.between.finish_search"), Styles.SECONDARY) { finishSearchNow() }, minWidth = 220f).padRight(16f)
        }
        footer.addButton(ui.button(if (done == 0) t("common.start") else t("common.next_round"), Styles.PRIMARY) { ctx.showAccelRound(run) }, minWidth = 220f)
        return frame(content, footer, Chrome.MINIMAL)
    }

    private fun finishSearchNow() {
        run.finishSearchNow()
        ctx.showAccelStep(run)
    }

    private fun phaseName() = when {
        calibrating -> t("accel.between.calibration")
        searching -> t("accel.between.search")
        else -> t("accel.between.test")
    }

    private fun analyseNow() {
        run.analyseNow()
        ctx.showAccelStep(run)
    }

    override fun onEnter() = ctx.showAccelRound(run)

    override fun onEscape() = ctx.showMenu()

    private fun heading() = when {
        done == 0 -> phaseName()
        else -> t("accel.between.round_done", done)
    }

    private fun progressText() = when {
        calibrating -> t("accel.between.progress_calibration", done, total)
        searching -> t("accel.between.progress_search", done, total)
        else -> t("accel.between.progress_test", done, total)
    }

    private fun explanation() = when {
        calibrating && run.prior.rounds > 0 -> t("accel.between.explain_calibration", AccelRun.MIN_CALIBRATION_ROUNDS) + " " + t("accel.between.prior", run.prior.rounds)
        calibrating -> t("accel.between.explain_calibration", AccelRun.MIN_CALIBRATION_ROUNDS)
        searching && run.priorSearchRounds > 0 -> t("accel.between.explain_search", run.searchMinRounds) + " " + t("accel.between.prior_search", run.priorSearchRounds)
        searching -> t("accel.between.explain_search", run.searchMinRounds)
        else -> t("accel.between.explain_test", run.maxTestRounds / 2)
    }
}
