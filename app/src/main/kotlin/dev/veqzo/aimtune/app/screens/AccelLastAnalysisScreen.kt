package dev.veqzo.aimtune.app.screens

import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.ui.Table
import dev.veqzo.aimtune.app.AppContext
import dev.veqzo.aimtune.app.accel.LastCalibration
import dev.veqzo.aimtune.app.ui.LedState
import dev.veqzo.aimtune.app.ui.ModuleStatus
import dev.veqzo.aimtune.app.ui.Styles
import dev.veqzo.aimtune.app.ui.accelChart
import dev.veqzo.aimtune.core.accel.AccelAnalysis
import dev.veqzo.aimtune.core.accel.bounded
import dev.veqzo.aimtune.core.accel.fitAccel
import java.util.Locale

/**
 * The analysis of the stored calibration rounds as it would come out now, in the curve layout: the curve that fits (or why
 * none does) on the left, the wanted gain per speed on the display. It is computed again from the stored rounds, so it is
 * available on any day without a new run.
 */
class AccelLastAnalysisScreen(ctx: AppContext, private val last: LastCalibration?) : AccelFitScreen(ctx) {
    private val analysis = last?.data?.takeIf { it.rounds > 0 }?.let { AccelAnalysis(it.samples) }
    private val fit = analysis?.let { fitAccel(it).let { result -> if (ctx.boundedCurves) result.bounded(it) else result } }

    override fun build(): Actor {
        val animate = firstBuild()
        val recommended = fit?.recommended
        val left = Table()
        left.headline(t("accel.last.title"))
        if (last == null || analysis == null || fit == null) {
            left.text(t("accel.last.none"))
        } else {
            left.text(reasonText(fit))
            left.text(t("accel.last.note", last.data.rounds, analysis.used.size, "%.1f".format(Locale.ROOT, last.cm360)), Styles.CAPTION_MUTED, CURVE_SMALL_GAP)
            if (recommended != null) {
                left.add(curveBlock(recommended)).left().padTop(CURVE_SECTION_GAP).row()
                left.add(unconfirmedBlock()).left().padTop(CURVE_SECTION_GAP).row()
            }
        }

        val status = when {
            fit == null -> ModuleStatus(LedState.OFF, t("curve.status.empty"))
            recommended != null -> ModuleStatus(LedState.OK, t("curve.status.found"))
            else -> ModuleStatus(LedState.OFF, t("curve.status.none"))
        }
        val chart = fit?.points?.takeIf { it.size >= 2 }?.let { accelChart(ui.tokens, it, recommended) }
        val caption = t(if (recommended == null) "accel.analysis.chart_plain" else "accel.analysis.chart_curve")
        val emptyText = if (fit == null) t("accel.last.none") else t("curve.display.empty_points")
        val display = curveDisplay(caption, chart, animate, t("curve.display.empty_title"), emptyText)
        val content = Table()
        content.add(curvePanel(status, left, display)).grow()
        val footer = curveFooter(t("common.back")) { ctx.showMenu() }
        if (last != null && recommended != null) addCurveActions(footer, recommended, last.dpi, last.cm360)
        return frame(content, footer)
    }

    override fun onEscape() = ctx.showMenu()
}
