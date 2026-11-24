package de.yuuto.aimtune.app.screens

import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.ui.Table
import de.yuuto.aimtune.app.AppContext
import de.yuuto.aimtune.app.accel.AccelRun
import de.yuuto.aimtune.app.ui.LedState
import de.yuuto.aimtune.app.ui.ModuleStatus
import de.yuuto.aimtune.app.ui.Styles
import de.yuuto.aimtune.app.ui.accelChart
import de.yuuto.aimtune.app.ui.addButton
import de.yuuto.aimtune.app.ui.button
import de.yuuto.aimtune.app.ui.caps
import de.yuuto.aimtune.app.ui.label
import de.yuuto.aimtune.core.accel.AccelFit
import de.yuuto.aimtune.core.accel.DraftBasis
import java.util.Locale

private const val ACTION_GAP = 16f

/**
 * What the calibration found, in the curve layout: either a curve that fits the way the first movements miss, offered
 * for a blind test, or the reason there is nothing to offer. The display shows the wanted gain per speed with the curve.
 */
class AccelAnalysisScreen(ctx: AppContext, private val run: AccelRun) : AccelFitScreen(ctx) {
    private val candidate: AccelFit? = run.candidate

    override fun build(): Actor {
        val animate = firstBuild()
        val fit = checkNotNull(run.fit)
        val left = Table()
        left.headline(if (candidate == null) t("accel.analysis.no_curve") else t("accel.analysis.curve_found"))
        left.text(reasonText(fit))
        if (candidate != null) left.add(curveBlock(candidate)).left().padTop(CURVE_SECTION_GAP).row()
        if (run.draft != null) left.add(draftBlock()).left().padTop(CURVE_SECTION_GAP).row()
        if (candidate == null) left.text(t("accel.analysis.plain_supported") + " " + t("accel.analysis.trial_hint"), Styles.CAPTION_MUTED, CURVE_SECTION_GAP)
        left.text(t("curve.experimental"), Styles.CAUTION, CURVE_SECTION_GAP)

        val status = if (candidate != null) ModuleStatus(LedState.OK, t("curve.status.found")) else ModuleStatus(LedState.OFF, t("curve.status.none"))
        val chart = fit.points.takeIf { it.size >= 2 }?.let { accelChart(ui.tokens, it, candidate) }
        val caption = t(if (candidate == null) "accel.analysis.chart_plain" else "accel.analysis.chart_curve")
        val display = curveDisplay(caption, chart, animate, t("curve.display.empty_title"), t("curve.display.empty_points"))
        val content = Table()
        content.add(curvePanel(status, left, display)).grow()
        return frame(content, footer(fit.points.size >= 2), Chrome.MINIMAL)
    }

    override fun onEscape() = ctx.showMenu()

    override fun onEnter() {
        if (candidate != null) start() else if (run.draftOffer != null) startDraft()
    }

    private fun footer(hasPoints: Boolean): Table {
        val footer = curveFooter(if (candidate == null) t("common.back") else t("accel.analysis.not_now")) { ctx.showMenu() }
        if (candidate == null && hasPoints) {
            footer.addButton(ui.button(t("accel.analysis.try_falling"), Styles.SECONDARY) { startTrial(rising = false) }, minWidth = 260f).padRight(ACTION_GAP)
            footer.addButton(ui.button(t("accel.analysis.try_rising"), Styles.SECONDARY) { startTrial(rising = true) }, minWidth = 260f).padRight(ACTION_GAP)
        }
        footer.addButton(ui.button(t("accel.analysis.search"), if (candidate == null && run.draftOffer == null) Styles.PRIMARY else Styles.SECONDARY) { startSearch() }, minWidth = 300f)
        if (run.draftOffer != null) {
            footer.addButton(ui.button(t("accel.draft.export"), Styles.SECONDARY) { ctx.showDraftExport(run) }, minWidth = 340f).padLeft(ACTION_GAP)
            footer.addButton(ui.button(t("accel.draft.test"), if (candidate == null) Styles.PRIMARY else Styles.SECONDARY) { startDraft() }, minWidth = 300f).padLeft(ACTION_GAP)
        }
        if (candidate != null) {
            footer.addButton(ui.button(t("accel.analysis.test_blind"), Styles.PRIMARY) { start() }, minWidth = 260f).padLeft(ACTION_GAP)
        }
        return footer
    }

    /** The draft from the movement profile: its first stage, where it ends, and how sure the direction is. */
    private fun draftBlock(): Table {
        val draft = checkNotNull(run.draft)
        fun x(value: Double) = "%.2f".format(Locale.ROOT, value)
        val block = Table().top().left()
        block.add(ui.caps(t("accel.draft.title"))).left().row()
        val plan = draft.felt
        val step = plan?.steps?.firstOrNull()
        val (text, style) = when {
            draft.basis == DraftBasis.MEASURED_WEAKER -> t("accel.draft.basis_weaker", x(draft.estimate)) to Styles.CAPTION_MUTED
            plan == null -> t("accel.draft.felt_span") to Styles.CAPTION_MUTED
            step == null -> t("accel.draft.felt_none", "%.0f".format(Locale.ROOT, plan.maxStep * 100.0)) to Styles.CAPTION_MUTED
            else -> t("accel.draft.felt", "%.0f".format(Locale.ROOT, step * 100.0), "%.0f".format(Locale.ROOT, plan.anchorSlow), "%.0f".format(Locale.ROOT, plan.anchorFast)) to Styles.TITLE
        }
        block.add(ui.label(text, style, wrap = true)).left().width(CURVE_LEFT_WIDTH).padTop(CURVE_SMALL_GAP).row()
        block.add(ui.label(t("accel.draft.hint"), Styles.CAUTION, wrap = true)).left().width(CURVE_LEFT_WIDTH).padTop(CURVE_SMALL_GAP)
        return block
    }

    private fun startDraft() {
        run.startDraftTest()
        ctx.showAccelStep(run)
    }

    private fun startSearch() {
        run.startSearch()
        ctx.showAccelStep(run)
    }

    private fun startTrial(rising: Boolean) {
        run.startTrial(rising)
        ctx.showAccelStep(run)
    }

    private fun start() {
        run.startTest()
        ctx.showAccelStep(run)
    }
}
