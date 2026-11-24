package de.yuuto.aimtune.app.screens

import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.ui.Table
import de.yuuto.aimtune.app.AppContext
import de.yuuto.aimtune.app.accel.AccelRun
import de.yuuto.aimtune.app.accel.StrengthResult
import de.yuuto.aimtune.app.ui.LedState
import de.yuuto.aimtune.app.ui.ModuleStatus
import de.yuuto.aimtune.app.ui.Styles
import de.yuuto.aimtune.app.ui.addButton
import de.yuuto.aimtune.app.ui.button
import de.yuuto.aimtune.app.ui.fitChart

/**
 * What the blind strength search found, in the curve layout: the strength that scored best with its credible range, and
 * whether it stands clearly on one side of "no curve"; the display draws the offered curve. Nothing is exported here; a
 * found strength still goes through the blind A/B test against plain sensitivity.
 */
class AccelSearchResultScreen(ctx: AppContext, private val run: AccelRun) : AccelFitScreen(ctx) {
    private val result: StrengthResult? = run.searchResult

    override fun build(): Actor {
        val animate = firstBuild()
        val offer = run.searchOffer
        val own = run.baseCurve != null
        val left = Table()
        left.headline(
            when {
                offer != null -> t("accel.search.found")
                own -> t("accel.search.own_none")
                else -> t("accel.search.none")
            },
        )
        left.text(searchExplanation(result, offer != null, own))
        result?.let { left.add(searchBlock(it, own)).left().padTop(CURVE_SECTION_GAP).row() }

        val status = if (offer != null) ModuleStatus(LedState.OK, t("curve.status.found")) else ModuleStatus(LedState.OFF, t("curve.status.none"))
        val display = curveDisplay(t("curve.display.offer"), offer?.let { fitChart(ui.tokens, it) }, animate, t("curve.display.empty_title"), t("curve.display.empty_search"))
        val content = Table()
        content.add(curvePanel(status, left, display)).grow()
        val footer = curveFooter(if (offer == null) t("common.back") else t("accel.analysis.not_now")) { leave() }
        if (offer != null) footer.addButton(ui.button(t("accel.analysis.test_blind"), Styles.PRIMARY) { test() }, minWidth = 260f)
        return frame(content, footer)
    }

    override fun onEscape() = leave()

    override fun onEnter() {
        if (run.searchOffer != null) test()
    }

    private fun leave() {
        run.finish()
        ctx.showMenu()
    }

    private fun test() {
        run.startSearchTest()
        ctx.showAccelStep(run)
    }
}
