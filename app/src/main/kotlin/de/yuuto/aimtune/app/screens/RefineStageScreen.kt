package de.yuuto.aimtune.app.screens

import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.ui.Table
import de.yuuto.aimtune.app.AppContext
import de.yuuto.aimtune.app.accel.RefineRun
import de.yuuto.aimtune.app.ui.LedState
import de.yuuto.aimtune.app.ui.ModuleStatus
import de.yuuto.aimtune.app.ui.Styles
import de.yuuto.aimtune.app.ui.addButton
import de.yuuto.aimtune.app.ui.button
import de.yuuto.aimtune.app.ui.compareChart
import de.yuuto.aimtune.app.ui.label
import de.yuuto.aimtune.core.accel.RefineParameter
import de.yuuto.aimtune.core.accel.RefineResult
import kotlin.math.abs
import kotlin.math.roundToInt

private const val READOUT_PADDING = 28f
private const val ACTION_GAP = 16f
private const val FEEL_WIDTH = 900f

/**
 * The result of one refine stage, in the curve layout: the best value of the parameter as a change against the imported
 * curve in the readout, its credible range and whether it is kept, on the left; the imported curve against the refined
 * one so far on the display, with the feel question, the ratings of the stage and the sensitivity of the runs under it. The player continues, skips the remaining stages (to the test) or stops.
 */
class RefineStageScreen(ctx: AppContext, private val run: RefineRun) : AccelFitScreen(ctx) {
    private val engine = run.engine
    private val result: RefineResult? = engine.currentResult
    private val lastStage = engine.stageNumber + 1 >= engine.stages.size

    override fun build(): Actor {
        val animate = firstBuild()
        val left = Table()
        left.headline(t("refine.stage.title", parameterName(result?.parameter)))
        left.text(t("refine.stage.explain"))
        result?.let { left.add(readout(it)).left().width(CURVE_LEFT_WIDTH).padTop(CURVE_SECTION_GAP).row() }

        val display = curveDisplay(t("curve.display.refined"), compareChart(ui.tokens, run.profile, engine.candidate), animate, t("curve.display.empty_title"), "")
        val right = Table()
        right.add(display).grow().row()
        right.add(refineFeelPanel(ctx, run, FEEL_WIDTH) { rebuild() }).left().padTop(CURVE_SECTION_GAP).row()
        result?.let { right.add(refineFeelStageCard(ctx, run, it.parameter, FEEL_WIDTH)).left().padTop(CURVE_SMALL_GAP).row() }
        right.add(ctx.runsUseLine(run.runsUse, FEEL_WIDTH)).left().width(FEEL_WIDTH).padTop(CURVE_SECTION_GAP)

        val content = Table()
        content.add(curvePanel(status(), left, right)).grow()
        return frame(content, footer(), Chrome.MINIMAL)
    }

    override fun onEnter() = next()

    override fun onEscape() = ctx.showMenu()

    private fun status(): ModuleStatus = when (result?.excludesNeutral) {
        true -> ModuleStatus(LedState.OK, t("curve.status.kept"))
        else -> ModuleStatus(LedState.OFF, t("curve.status.unclear"))
    }

    private fun footer(): Table {
        val footer = curveFooter(t("common.stop")) { ctx.showMenu() }
        if (!lastStage) footer.addButton(ui.button(t("refine.stage.to_test"), Styles.SECONDARY) { toTest() }, minWidth = 300f).padRight(ACTION_GAP)
        val nextLabel = if (lastStage) t("refine.stage.finish") else t("refine.stage.next", parameterName(engine.stages[engine.stageNumber + 1]))
        footer.addButton(ui.button(nextLabel, Styles.PRIMARY) { next() }, minWidth = 340f)
        return footer
    }

    private fun next() {
        engine.continueToNextStage()
        ctx.showRefineStep(run)
    }

    private fun toTest() {
        engine.goToTest()
        ctx.showRefineStep(run)
    }

    /** The recessed readout: the best change in big numbers, its range, and whether it is kept or at the edge. */
    private fun readout(r: RefineResult): Table {
        val inner = CURVE_LEFT_WIDTH - 2 * READOUT_PADDING
        val well = Table().left()
        well.background(ui.skin.getDrawable(Styles.WELL))
        well.pad(READOUT_PADDING)
        well.add(ui.label(t("refine.stage.card_title"), Styles.LABEL)).left().row()
        well.add(ui.label(change(r.best), Styles.NUMBER_LG)).left().row()
        well.add(ui.label(t("refine.stage.range", change(r.low), change(r.high), r.confidencePercent, r.rounds), Styles.MONO_MUTED, wrap = true))
            .left().width(inner).padTop(CURVE_SMALL_GAP).row()
        well.add(ui.label(if (r.excludesNeutral) t("refine.stage.kept") else t("refine.stage.unclear"), if (r.excludesNeutral) Styles.OK else Styles.BODY_MUTED, wrap = true))
            .left().width(inner).padTop(CURVE_SMALL_GAP).row()
        if (r.atEdge) well.add(ui.label(t("refine.stage.edge"), Styles.CAUTION, wrap = true)).left().width(inner).padTop(CURVE_SMALL_GAP)
        return well
    }

    private fun parameterName(p: RefineParameter?) = when (p) {
        RefineParameter.HIGH_GAIN -> t("refine.param.high_gain")
        RefineParameter.TRANSITION -> t("refine.param.transition")
        null -> ""
    }

    /** A factor as a signed percent change: 1.08 is "+8 %". */
    private fun change(factor: Double): String {
        val percent = ((factor - 1.0) * 100.0).roundToInt()
        return if (percent == 0) "0 %" else "${if (percent > 0) "+" else "-"}${abs(percent)} %"
    }
}
