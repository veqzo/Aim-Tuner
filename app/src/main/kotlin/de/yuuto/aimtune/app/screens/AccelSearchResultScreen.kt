package de.yuuto.aimtune.app.screens

import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.ui.Table
import de.yuuto.aimtune.app.AppContext
import de.yuuto.aimtune.app.accel.AccelRun
import de.yuuto.aimtune.app.accel.StrengthResult
import de.yuuto.aimtune.app.ui.Styles
import de.yuuto.aimtune.app.ui.addButton
import de.yuuto.aimtune.app.ui.button
import de.yuuto.aimtune.app.ui.frame
import de.yuuto.aimtune.app.ui.label

private const val TEXT_WIDTH = 1000f
private const val CARD_WIDTH = 900f
private const val FOOTER_BUTTON_HEIGHT = 64f

/**
 * What the blind strength search found: the strength that scored best with its credible range, and whether
 * it stands clearly on one side of "no curve". Nothing is exported here; a found strength still goes
 * through the blind A/B test against plain sensitivity.
 */
class AccelSearchResultScreen(ctx: AppContext, private val run: AccelRun) : AccelFitScreen(ctx) {
    private val result: StrengthResult? = run.searchResult

    override fun build(): Actor {
        val offer = run.searchOffer
        val content = Table()
        val own = run.baseCurve != null
        val heading = when {
            offer != null -> t("accel.search.found")
            own -> t("accel.search.own_none")
            else -> t("accel.search.none")
        }
        content.add(ui.label(heading, Styles.HEADING)).left().padBottom(32f).row()
        content.add(ui.label(searchExplanation(result, offer != null, own), Styles.BODY_MUTED, wrap = true)).left().width(TEXT_WIDTH).padBottom(32f).row()
        result?.let { content.add(searchCard(it, own = own)).left().width(CARD_WIDTH).row() }

        val footer = Table()
        footer.add(ui.button(if (offer == null) t("common.back") else t("accel.analysis.not_now"), Styles.TEXT) { leave() }).left().height(FOOTER_BUTTON_HEIGHT).padLeft(8f)
        footer.add().expandX()
        if (offer != null) {
            footer.addButton(ui.button(t("accel.analysis.test_blind"), Styles.PRIMARY) { test() }, minWidth = 260f)
        }
        return ui.frame(null, content, footer)
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
