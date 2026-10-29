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
import de.yuuto.aimtune.core.accel.RefineParameter
import de.yuuto.aimtune.core.accel.RefineResult
import kotlin.math.abs
import kotlin.math.roundToInt

private const val TEXT_WIDTH = 1000f
private const val CARD_WIDTH = 900f
private const val CARD_PADDING = 32f
private const val FOOTER_BUTTON_HEIGHT = 64f

/**
 * The result of one refine stage: the best value of the parameter as a change against the imported curve, its credible range
 * and confidence, and whether it will be kept. The player continues, skips the remaining stages (to the test) or stops.
 */
class RefineStageScreen(ctx: AppContext, private val run: RefineRun) : UiScreen(ctx) {
    private val engine = run.engine
    private val result: RefineResult? = engine.currentResult
    private val lastStage = engine.stageNumber + 1 >= engine.stages.size

    override fun build(): Actor {
        val content = Table()
        val parameter = result?.parameter
        content.add(ui.label(t("refine.stage.title", parameterName(parameter)), Styles.HEADING)).left().padBottom(16f).row()
        content.add(ctx.runsUseLine(run.runsUse, TEXT_WIDTH, { ctx.showRefineStep(run) })).left().width(TEXT_WIDTH).padBottom(24f).row()
        content.add(ui.label(t("refine.stage.explain"), Styles.BODY_MUTED, wrap = true)).left().width(TEXT_WIDTH).padBottom(24f).row()
        result?.let { content.add(card(it)).left().width(CARD_WIDTH).padBottom(24f).row() }
        content.add(refineFeelPanel(ctx, run) { rebuild() }).left().padBottom(16f).row()
        result?.let { content.add(refineFeelStageCard(ctx, run, it.parameter, CARD_WIDTH)).left().row() }

        val footer = Table()
        footer.add(ui.button(t("common.stop"), Styles.TEXT) { ctx.showMenu() }).left().height(FOOTER_BUTTON_HEIGHT).padLeft(8f)
        footer.add().expandX()
        if (!lastStage) {
            footer.addButton(ui.button(t("refine.stage.to_test"), Styles.SECONDARY) { toTest() }, minWidth = 300f).padRight(16f)
        }
        val nextLabel = if (lastStage) t("refine.stage.finish") else t("refine.stage.next", parameterName(engine.stages[engine.stageNumber + 1]))
        footer.addButton(ui.button(nextLabel, Styles.PRIMARY) { next() }, minWidth = 340f)
        return ui.frame(null, content, footer)
    }

    override fun onEnter() = next()

    override fun onEscape() = ctx.showMenu()

    private fun next() {
        engine.continueToNextStage()
        ctx.showRefineStep(run)
    }

    private fun toTest() {
        engine.goToTest()
        ctx.showRefineStep(run)
    }

    private fun card(r: RefineResult): Table {
        val inner = CARD_WIDTH - 2 * CARD_PADDING
        val card = Table()
        card.background(ui.skin.getDrawable(Styles.CARD))
        card.pad(CARD_PADDING)
        card.add(ui.caps(t("refine.stage.card_title"))).left().row()
        card.add(ui.label(change(r.best), Styles.TITLE, wrap = true)).left().width(inner).padTop(8f).row()
        card.add(ui.label(t("refine.stage.range", change(r.low), change(r.high), r.confidencePercent, r.rounds), Styles.CAPTION_MUTED, wrap = true))
            .left().width(inner).padTop(12f).row()
        card.add(ui.label(if (r.excludesNeutral) t("refine.stage.kept") else t("refine.stage.unclear"), if (r.excludesNeutral) Styles.OK else Styles.BODY_MUTED, wrap = true))
            .left().width(inner).padTop(12f).row()
        if (r.atEdge) card.add(ui.label(t("refine.stage.edge"), Styles.CAUTION, wrap = true)).left().width(inner).padTop(12f)
        return card
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
