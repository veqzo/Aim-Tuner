package de.yuuto.aimtune.app.screens

import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.ui.Table
import de.yuuto.aimtune.app.AppContext
import de.yuuto.aimtune.app.accel.AccelRun
import de.yuuto.aimtune.app.ui.ChartActor
import de.yuuto.aimtune.app.ui.Styles
import de.yuuto.aimtune.app.ui.accelChart
import de.yuuto.aimtune.app.ui.addButton
import de.yuuto.aimtune.app.ui.button
import de.yuuto.aimtune.app.ui.caps
import de.yuuto.aimtune.app.ui.frame
import de.yuuto.aimtune.app.ui.label
import de.yuuto.aimtune.core.accel.AccelFit
import de.yuuto.aimtune.core.accel.DraftBasis
import java.util.Locale

private const val TEXT_WIDTH = 1000f
private const val CARD_WIDTH = 900f
private const val FOOTER_BUTTON_HEIGHT = 64f
private const val CHART_HEIGHT = 300f
private const val DRAFT_CARD_PADDING = 32f

/**
 * What the calibration found: either a curve that fits the way the first movements miss, offered for
 * a blind test, or the reason there is nothing to offer. The chart shows the wanted gain per speed next to the curve.
 */
class AccelAnalysisScreen(ctx: AppContext, private val run: AccelRun) : AccelFitScreen(ctx) {
    private val candidate: AccelFit? = run.candidate

    override fun build(): Actor {
        val content = Table()
        content.add(ui.label(if (candidate == null) t("accel.analysis.no_curve") else t("accel.analysis.curve_found"), Styles.HEADING)).left().padBottom(32f).row()
        val fit = checkNotNull(run.fit)
        content.add(ui.label(reasonText(fit), Styles.BODY_MUTED, wrap = true)).left().width(TEXT_WIDTH).padBottom(16f).row()
        content.add(ui.label(t("accel.experimental"), Styles.CAPTION_MUTED, wrap = true)).left().width(TEXT_WIDTH).padBottom(32f).row()
        if (candidate != null) content.add(curveCard(candidate, fit.points)).left().width(CARD_WIDTH).padBottom(24f).row()
        if (run.draft != null) content.add(draftCard()).left().width(CARD_WIDTH).padBottom(24f).row()
        if (candidate == null && fit.points.size >= 2) {
            content.add(ui.caps(t("accel.analysis.chart_plain"))).left().padBottom(8f).row()
            content.add(ChartActor(ui, accelChart(ui.tokens, fit.points, null))).left().width(CARD_WIDTH).height(CHART_HEIGHT).padBottom(16f).row()
        }
        if (candidate == null) {
            content.add(
                ui.label(t("accel.analysis.plain_supported") + " " + t("accel.analysis.trial_hint"), Styles.CAPTION_MUTED, wrap = true),
            ).left().width(TEXT_WIDTH)
        }

        val footer = Table()
        footer.add(ui.button(if (candidate == null) t("common.back") else t("accel.analysis.not_now"), Styles.TEXT) { ctx.showMenu() }).left().height(FOOTER_BUTTON_HEIGHT).padLeft(8f)
        footer.add().expandX()
        if (candidate == null && fit.points.size >= 2) {
            footer.addButton(ui.button(t("accel.analysis.try_falling"), Styles.SECONDARY) { startTrial(rising = false) }, minWidth = 260f).padRight(16f)
            footer.addButton(ui.button(t("accel.analysis.try_rising"), Styles.SECONDARY) { startTrial(rising = true) }, minWidth = 260f).padRight(16f)
        }
        footer.addButton(ui.button(t("accel.analysis.search"), if (candidate == null && run.draftOffer == null) Styles.PRIMARY else Styles.SECONDARY) { startSearch() }, minWidth = 300f)
        if (run.draftOffer != null) {
            footer.addButton(ui.button(t("accel.draft.export"), Styles.SECONDARY) { ctx.showDraftExport(run) }, minWidth = 340f).padLeft(16f)
            footer.addButton(ui.button(t("accel.draft.test"), if (candidate == null) Styles.PRIMARY else Styles.SECONDARY) { startDraft() }, minWidth = 300f).padLeft(16f)
        }
        if (candidate != null) {
            footer.addButton(ui.button(t("accel.analysis.test_blind"), Styles.PRIMARY) { start() }, minWidth = 260f).padLeft(16f)
        }
        return ui.frame(null, content, footer)
    }

    override fun onEscape() = ctx.showMenu()

    override fun onEnter() {
        if (candidate != null) start() else if (run.draftOffer != null) startDraft()
    }

    /** The draft from the movement profile: its first stage, where it ends, and how sure the direction is. */
    private fun draftCard(): Table {
        val draft = checkNotNull(run.draft)
        fun x(value: Double) = "%.2f".format(Locale.ROOT, value)
        val card = Table()
        card.background(ui.skin.getDrawable(Styles.CARD))
        card.pad(DRAFT_CARD_PADDING)
        card.add(ui.caps(t("accel.draft.title"))).left().row()
        val plan = draft.felt
        val step = plan?.steps?.firstOrNull()
        val (text, style) = when {
            draft.basis == DraftBasis.MEASURED_WEAKER -> t("accel.draft.basis_weaker", x(draft.estimate)) to Styles.CAPTION_MUTED
            plan == null -> t("accel.draft.felt_span") to Styles.CAPTION_MUTED
            step == null -> t("accel.draft.felt_none", "%.0f".format(Locale.ROOT, plan.maxStep * 100.0)) to Styles.CAPTION_MUTED
            else -> t("accel.draft.felt", "%.0f".format(Locale.ROOT, step * 100.0), "%.0f".format(Locale.ROOT, plan.anchorSlow), "%.0f".format(Locale.ROOT, plan.anchorFast)) to Styles.TITLE
        }
        card.add(ui.label(text, style, wrap = true)).left().width(CARD_WIDTH - 2 * DRAFT_CARD_PADDING).padTop(8f).row()
        card.add(ui.label(t("accel.draft.hint"), Styles.CAUTION, wrap = true)).left().width(CARD_WIDTH - 2 * DRAFT_CARD_PADDING).padTop(12f)
        return card
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
