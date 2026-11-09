package de.yuuto.aimtune.app.screens

import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.ui.Table
import de.yuuto.aimtune.app.AppContext
import de.yuuto.aimtune.app.accel.LastCalibration
import de.yuuto.aimtune.app.ui.ChartActor
import de.yuuto.aimtune.app.ui.Styles
import de.yuuto.aimtune.app.ui.accelChart
import de.yuuto.aimtune.app.ui.button
import de.yuuto.aimtune.app.ui.caps
import de.yuuto.aimtune.app.ui.frame
import de.yuuto.aimtune.app.ui.label
import de.yuuto.aimtune.core.accel.AccelAnalysis
import de.yuuto.aimtune.core.accel.bounded
import de.yuuto.aimtune.core.accel.fitAccel
import java.util.Locale

private const val TEXT_WIDTH = 1000f
private const val CARD_WIDTH = 900f
private const val CHART_HEIGHT = 300f
private const val FOOTER_BUTTON_HEIGHT = 64f

/**
 * The analysis of the stored calibration rounds, as it would come out now: the curve that fits (or why none does) and the
 * wanted gain per speed. It is computed again from the stored rounds, so it is available on any day without a new run.
 */
class AccelLastAnalysisScreen(ctx: AppContext, private val last: LastCalibration?) : AccelFitScreen(ctx) {
    private val analysis = last?.data?.takeIf { it.rounds > 0 }?.let { AccelAnalysis(it.samples) }
    private val fit = analysis?.let { fitAccel(it).let { result -> if (ctx.boundedCurves) result.bounded(it) else result } }

    override fun build(): Actor {
        val content = Table()
        content.add(ui.label(t("accel.last.title"), Styles.HEADING)).left().padBottom(32f).row()
        if (last == null || analysis == null || fit == null) {
            content.add(ui.label(t("accel.last.none"), Styles.BODY_MUTED, wrap = true)).left().width(TEXT_WIDTH)
        } else {
            val note = t("accel.last.note", last.data.rounds, analysis.used.size, "%.1f".format(Locale.ROOT, last.cm360))
            content.add(ui.label(note, Styles.CAPTION_MUTED, wrap = true)).left().width(TEXT_WIDTH).colspan(2).padBottom(16f).row()
            content.add(ui.label(reasonText(fit), Styles.BODY_MUTED, wrap = true)).left().width(TEXT_WIDTH).colspan(2).padBottom(24f).row()
            val recommended = fit.recommended
            if (recommended != null) {
                // The card about the missing export sits next to the curve, so the screen stays within the window.
                content.add(curveCard(recommended, fit.points, SIDE_CARD_WIDTH)).left().top().width(SIDE_CARD_WIDTH)
                content.add(unconfirmedCard()).left().top().width(EXPORT_CARD_WIDTH).padLeft(24f).row()
            } else if (fit.points.size >= 2) {
                content.add(ui.caps(t("accel.analysis.chart_plain"))).left().padBottom(8f).row()
                content.add(ChartActor(ui, accelChart(ui.tokens, fit.points, null))).left().width(CARD_WIDTH).height(CHART_HEIGHT).row()
            }
        }

        val footer = Table()
        footer.add(ui.button(t("common.back"), Styles.TEXT) { ctx.showRawAccelHub() }).left().height(FOOTER_BUTTON_HEIGHT).padLeft(8f)
        footer.add().expandX()
        fit?.recommended?.let { addCurveActions(footer, it, checkNotNull(last).dpi, last.cm360) }
        return frame(content, footer)
    }

    override fun onEscape() = ctx.showRawAccelHub()
}
