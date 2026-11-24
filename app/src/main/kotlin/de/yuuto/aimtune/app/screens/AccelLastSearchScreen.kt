package de.yuuto.aimtune.app.screens

import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.ui.Table
import de.yuuto.aimtune.app.AppContext
import de.yuuto.aimtune.app.accel.LastSearch
import de.yuuto.aimtune.app.ui.LedState
import de.yuuto.aimtune.app.ui.ModuleStatus
import de.yuuto.aimtune.app.ui.Styles
import de.yuuto.aimtune.app.ui.fitChart
import java.util.Locale

/**
 * The newest blind strength search as it comes out when its stored rounds are analysed again, in the curve layout: the
 * strength and its range, and, when it clearly excluded "no curve", its curve on the display and the blind test of it
 * against plain sensitivity (no export before that test passed).
 */
class AccelLastSearchScreen(ctx: AppContext, private val last: LastSearch?) : AccelFitScreen(ctx) {
    override fun build(): Actor {
        val animate = firstBuild()
        val offer = last?.offer
        val left = Table()
        left.headline(t("accel.last_search.title"))
        if (last == null) {
            left.text(t("accel.last_search.none"))
        } else {
            left.text(searchExplanation(last.result, last.clear, last.base != null))
            left.text(t("accel.last_search.note", last.rounds, "%.1f".format(Locale.ROOT, last.cm360), last.sessions), Styles.CAPTION_MUTED, CURVE_SMALL_GAP)
            last.result?.let { left.add(searchBlock(it, last.base != null)).left().padTop(CURVE_SECTION_GAP).row() }
            if (offer != null) left.add(unconfirmedBlock()).left().padTop(CURVE_SECTION_GAP).row()
        }

        val status = when {
            last == null -> ModuleStatus(LedState.OFF, t("curve.status.empty"))
            offer != null -> ModuleStatus(LedState.OK, t("curve.status.found"))
            else -> ModuleStatus(LedState.OFF, t("curve.status.none"))
        }
        val emptyText = if (last == null) t("accel.last_search.none") else t("curve.display.empty_search")
        val display = curveDisplay(t("curve.display.offer"), offer?.let { fitChart(ui.tokens, it) }, animate, t("curve.display.empty_title"), emptyText)
        val content = Table()
        content.add(curvePanel(status, left, display)).grow()
        val footer = curveFooter(t("common.back")) { ctx.showMenu() }
        if (last != null && offer != null) addCurveActions(footer, offer, last.dpi, last.cm360)
        return frame(content, footer)
    }

    override fun onEscape() = ctx.showMenu()
}
