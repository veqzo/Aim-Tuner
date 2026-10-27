package de.yuuto.aimtune.app.screens

import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.ui.Table
import de.yuuto.aimtune.app.AppContext
import de.yuuto.aimtune.app.accel.LastSearch
import de.yuuto.aimtune.app.ui.Styles
import de.yuuto.aimtune.app.ui.button
import de.yuuto.aimtune.app.ui.frame
import de.yuuto.aimtune.app.ui.label
import java.util.Locale

private const val TEXT_WIDTH = 1000f
private const val FOOTER_BUTTON_HEIGHT = 64f

/**
 * The newest blind strength search as it comes out when its stored rounds are analysed again: the strength and its range,
 * and, when it clearly excluded "no curve", the blind test of its curve against plain sensitivity (no export before that test passed).
 */
class AccelLastSearchScreen(ctx: AppContext, private val last: LastSearch?) : AccelFitScreen(ctx) {
    override fun build(): Actor {
        val content = Table()
        content.add(ui.label(t("accel.last_search.title"), Styles.HEADING)).left().padBottom(32f).row()
        val offer = last?.offer
        if (last == null) {
            content.add(ui.label(t("accel.last_search.none"), Styles.BODY_MUTED, wrap = true)).left().width(TEXT_WIDTH)
        } else {
            val note = t("accel.last_search.note", last.rounds, "%.1f".format(Locale.ROOT, last.cm360), last.sessions)
            content.add(ui.label(note, Styles.CAPTION_MUTED, wrap = true)).left().width(TEXT_WIDTH).colspan(2).padBottom(16f).row()
            content.add(ui.label(searchExplanation(last.result, last.clear, last.base != null), Styles.BODY_MUTED, wrap = true)).left().width(TEXT_WIDTH).colspan(2).padBottom(24f).row()
            last.result?.let { content.add(searchCard(it, SIDE_CARD_WIDTH, last.base != null)).left().top().width(SIDE_CARD_WIDTH) }
            if (offer != null) content.add(unconfirmedCard()).left().top().width(EXPORT_CARD_WIDTH).padLeft(24f)
        }

        val footer = Table()
        footer.add(ui.button(t("common.back"), Styles.TEXT) { ctx.showRawAccelHub() }).left().height(FOOTER_BUTTON_HEIGHT).padLeft(8f)
        footer.add().expandX()
        if (last != null && offer != null) addCurveActions(footer, offer, last.dpi, last.cm360)
        return ui.frame(null, content, footer)
    }

    override fun onEscape() = ctx.showRawAccelHub()
}
