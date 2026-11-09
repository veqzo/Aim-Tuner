package de.yuuto.aimtune.app.screens

import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.ui.Table
import de.yuuto.aimtune.app.AppContext
import de.yuuto.aimtune.app.blind.BlindRun
import de.yuuto.aimtune.app.blind.RoundOutcome
import de.yuuto.aimtune.app.i18n.scenarioName
import de.yuuto.aimtune.app.i18n.t
import de.yuuto.aimtune.app.ui.Styles
import de.yuuto.aimtune.app.ui.addButton
import de.yuuto.aimtune.app.ui.button
import de.yuuto.aimtune.app.ui.caps
import de.yuuto.aimtune.app.ui.frame
import de.yuuto.aimtune.app.ui.label
import de.yuuto.aimtune.app.ui.progressBar
import java.util.Locale

private const val CARD_WIDTH = 300f
private const val CARD_GAP = 16f
private const val CARD_PADDING = 24f
private const val BAR_WIDTH = 640f
private const val SECTION_GAP = 40f
private const val FOOTER_BUTTON_HEIGHT = 64f

/**
 * Shown after every blind round: what the round looked like and how sure the optimizer is. The
 * sensitivity is never shown; the confidence arrives when the background analysis has finished.
 */
class BetweenRoundsScreen(ctx: AppContext, private val run: BlindRun) : UiScreen(ctx) {
    private var resultShown = run.test.isReady

    override fun build(): Actor {
        val test = run.test
        val content = Table()
        content.add(ui.label(t("between.complete", ctx.i18n.t(run.completedRoundLabel())), Styles.HEADING)).left().padBottom(SECTION_GAP).row()
        test.lastOutcome?.let {
            content.add(ui.caps(ctx.i18n.scenarioName(it.scenario))).left().padBottom(12f).row()
            content.add(statCards(it)).left().padBottom(24f).row()
        }
        content.add(ui.label(t("between.hidden"), Styles.CAPTION_MUTED)).left().padBottom(SECTION_GAP).row()
        content.add(confidenceBlock()).left()

        val footer = Table()
        footer.add(ui.button(t("between.stop"), Styles.SECONDARY) { ctx.stopBlindTest(run) }).left().height(FOOTER_BUTTON_HEIGHT).padLeft(8f)
        footer.add().expandX()
        footer.addButton(ui.button(t("common.next_round"), Styles.PRIMARY) { ctx.showBlindRound(run) }, minWidth = 220f)
        return frame(content, footer, Chrome.MINIMAL)
    }

    override fun render(delta: Float) {
        if (!resultShown && run.test.isReady) {
            resultShown = true
            rebuild()
        }
        super.render(delta)
    }

    override fun onEnter() = ctx.showBlindRound(run)

    override fun onEscape() = ctx.stopBlindTest(run)

    private fun statCards(outcome: RoundOutcome): Table {
        val row = Table()
        if (!outcome.scenario.usesClicks) {
            val onTarget = outcome.trackingOnTargetRatio?.let { "%.0f %%".format(Locale.ROOT, it * 100) } ?: "-"
            row.add(statCard(t("between.time_on_target"), onTarget)).width(CARD_WIDTH)
            return row
        }
        val time = outcome.meanTimeToHitMs?.let { "%.0f ms".format(Locale.ROOT, it) } ?: "-"
        row.add(statCard(t("between.hits"), "${outcome.hits} / ${outcome.shots}")).width(CARD_WIDTH).padRight(CARD_GAP)
        row.add(statCard(t("between.accuracy"), "%.0f %%".format(Locale.ROOT, outcome.accuracy * 100))).width(CARD_WIDTH).padRight(CARD_GAP)
        row.add(statCard(t("between.time_to_hit"), time)).width(CARD_WIDTH)
        return row
    }

    private fun statCard(name: String, value: String): Table {
        val card = Table()
        card.background(ui.skin.getDrawable(Styles.CARD))
        card.pad(CARD_PADDING)
        card.left()
        card.add(ui.caps(name)).left().row()
        card.add(ui.label(value, Styles.NUMBER_MD)).left().padTop(8f)
        return card
    }

    private fun confidenceBlock(): Table {
        val block = Table()
        block.add(ui.caps(t("between.confidence"))).left().padBottom(8f).row()
        val result = run.test.resultOrNull()
        if (result == null) {
            val text = if (run.test.isReady) t("between.not_enough") else t("between.analysing")
            block.add(ui.label(text, Styles.BODY_MUTED)).left()
            return block
        }
        val target = TARGET_PERCENT
        block.add(ui.label("${result.confidencePercent} %", Styles.NUMBER_XL)).left().padBottom(8f).row()
        block.add(ui.progressBar(result.confidencePercent / 100f, BAR_WIDTH)).left().padBottom(8f).row()
        val note = when {
            result.atRangeEdge -> t("between.note_edge")
            result.driftDetected -> t("between.note_drift")
            result.confidencePercent >= target -> t("between.note_reached", target)
            else -> t("between.note_more", roundsText(result.roundsToTarget), target)
        }
        block.add(ui.label(note, Styles.BODY_MUTED)).left()
        return block
    }

    private fun roundsText(count: Int) = if (count == 1) t("between.more_one") else t("between.more_many", count)

    private companion object {
        const val TARGET_PERCENT = 85
    }
}
