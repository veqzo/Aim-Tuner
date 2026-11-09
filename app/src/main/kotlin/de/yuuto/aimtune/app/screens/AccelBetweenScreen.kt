package de.yuuto.aimtune.app.screens

import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import de.yuuto.aimtune.app.AppContext
import de.yuuto.aimtune.app.accel.AccelPhase
import de.yuuto.aimtune.app.accel.AccelRun
import de.yuuto.aimtune.app.ui.Styles
import de.yuuto.aimtune.app.ui.button
import de.yuuto.aimtune.app.ui.label
import de.yuuto.aimtune.app.ui.progressBar

private const val ROW_GAP = 24f

/**
 * The overlay before every calibration, search or test round of the curve run: how far the run is and
 * what is going on, never which condition the next test round has and never a verdict before the test
 * has decided.
 */
class AccelBetweenScreen(ctx: AppContext, private val run: AccelRun) : RoundOverlayScreen(ctx) {
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

    override val phaseName: String get() = when {
        calibrating -> t("accel.between.calibration")
        searching -> t("accel.between.search")
        else -> t("accel.between.test")
    }
    override val heading: String get() = if (done == 0) t("overlay.first_round") else t("accel.between.round_done", done)
    override val roundsDone: Int get() = done
    override val primaryText: String get() = if (done == 0) t("common.start") else t("common.next_round")
    override val stopText: String get() = t("common.stop")

    override fun next(autoStart: Boolean) = ctx.showAccelRound(run, autoStart)

    override fun stopRun() = ctx.showMenu()

    override fun body(width: Float): Actor {
        val body = Table()
        body.add(ctx.runsUseLine(run.runsUse, width, { ctx.showAccelStep(run) })).left().width(width).padBottom(ROW_GAP).row()
        body.add(ui.progressBar(done.toFloat() / total, width)).left().padBottom(8f).row()
        body.add(ui.label(progressText(), Styles.BODY_MUTED)).left().padBottom(ROW_GAP).row()
        body.add(ui.label(explanation(), Styles.CAPTION_MUTED, wrap = true)).left().width(width)
        return body
    }

    override fun extraActions(): List<TextButton> = listOfNotNull(
        if (run.canAnalyseNow) ui.button(t("accel.between.analyse_now"), Styles.SECONDARY) { analyseNow() } else null,
        if (run.canFinishSearchNow) ui.button(t("accel.between.finish_search"), Styles.SECONDARY) { finishSearchNow() } else null,
    )

    private fun finishSearchNow() {
        run.finishSearchNow()
        ctx.showAccelStep(run)
    }

    private fun analyseNow() {
        run.analyseNow()
        ctx.showAccelStep(run)
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
