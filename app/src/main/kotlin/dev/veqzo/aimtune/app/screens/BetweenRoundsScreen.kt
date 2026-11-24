package dev.veqzo.aimtune.app.screens

import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.ui.Table
import dev.veqzo.aimtune.app.AppContext
import dev.veqzo.aimtune.app.blind.BlindRun
import dev.veqzo.aimtune.app.blind.RoundOutcome
import dev.veqzo.aimtune.app.i18n.scenarioName
import dev.veqzo.aimtune.app.i18n.t
import dev.veqzo.aimtune.app.ui.Styles
import dev.veqzo.aimtune.app.ui.caps
import dev.veqzo.aimtune.app.ui.label
import dev.veqzo.aimtune.app.ui.progressBar
import java.util.Locale

private const val WELL_PADDING = 20f
private const val STAT_GAP = 48f
private const val ROW_GAP = 24f
private const val UNIT_GAP = 6f
private const val UNIT_LIFT = 14f

/**
 * The overlay after every blind round: what the round looked like and how sure the optimizer is. The
 * sensitivity is never shown; the confidence arrives when the background analysis has finished. The
 * countdown holds back while the result asks the player to decide (target reached, scores drifting).
 */
class BetweenRoundsScreen(ctx: AppContext, private val run: BlindRun) : RoundOverlayScreen(ctx) {
    private var resultShown = run.test.isReady

    override val phaseName: String get() = run.test.lastOutcome?.let { ctx.i18n.scenarioName(it.scenario) } ?: ""
    override val heading: String get() = t("between.complete", ctx.i18n.t(run.completedRoundLabel()))
    override val roundsDone: Int get() = run.test.totalRounds
    override val primaryText: String get() = t("common.next_round")
    override val stopText: String get() = t("between.stop")

    override val countdownWanted: Boolean
        get() {
            val result = run.test.resultOrNull() ?: return true
            return !result.driftDetected && result.confidencePercent < TARGET_PERCENT
        }

    override fun next(autoStart: Boolean) = ctx.showBlindRound(run, autoStart)

    override fun stopRun() = ctx.stopBlindTest(run)

    override fun beforeFrame() {
        if (!resultShown && run.test.isReady) {
            resultShown = true
            rebuild()
        }
    }

    override fun body(width: Float): Actor {
        val body = Table()
        run.test.lastOutcome?.let { body.add(statWell(it)).left().growX().padBottom(ROW_GAP).row() }
        body.add(confidenceBlock(width)).left().growX().padBottom(ROW_GAP).row()
        body.add(ui.label(t("between.hidden"), Styles.CAPTION_MUTED, wrap = true)).left().width(width)
        return body
    }

    private fun statWell(outcome: RoundOutcome): Table {
        val well = Table()
        well.background(ui.skin.getDrawable(Styles.WELL))
        well.pad(WELL_PADDING)
        if (!outcome.scenario.usesClicks) {
            val onTarget = outcome.trackingOnTargetRatio?.let { "%.0f%%".format(Locale.ROOT, it * 100) } ?: "-"
            well.add(stat(t("between.time_on_target"), onTarget)).left()
        } else {
            val time = outcome.meanTimeToHitMs?.let { "%.0f ms".format(Locale.ROOT, it) } ?: "-"
            well.add(stat(t("between.hits"), "${outcome.hits} / ${outcome.shots}")).left().padRight(STAT_GAP)
            well.add(stat(t("between.accuracy"), "%.0f%%".format(Locale.ROOT, outcome.accuracy * 100))).left().padRight(STAT_GAP)
            well.add(stat(t("between.time_to_hit"), time)).left()
        }
        well.add().expandX()
        return well
    }

    private fun stat(name: String, value: String): Table {
        val cell = Table()
        cell.add(ui.caps(name)).left().row()
        cell.add(ui.label(value, Styles.NUMBER_MD)).left().padTop(6f)
        return cell
    }

    private fun confidenceBlock(width: Float): Table {
        val block = Table()
        block.add(ui.caps(t("between.confidence"))).left().colspan(2).padBottom(6f).row()
        val result = run.test.resultOrNull()
        if (result == null) {
            val text = if (run.test.isReady) t("between.not_enough") else t("between.analysing")
            block.add(ui.label(text, Styles.BODY_MUTED)).left()
            return block
        }
        val note = when {
            result.atRangeEdge -> t("between.note_edge")
            result.driftDetected -> t("between.note_drift")
            result.confidencePercent >= TARGET_PERCENT -> t("between.note_reached", TARGET_PERCENT)
            else -> t("between.note_more", roundsText(result.roundsToTarget), TARGET_PERCENT)
        }
        // Number and sign are two labels: in the mono face a space before % reads as a wide gap.
        val number = Table()
        number.add(ui.label(result.confidencePercent.toString(), Styles.NUMBER_LG)).bottom()
        number.add(ui.label("%", Styles.NUMBER_MD)).bottom().padLeft(UNIT_GAP).padBottom(UNIT_LIFT)
        block.add(number).left().padRight(ROW_GAP)
        block.add(ui.progressBar(result.confidencePercent / 100f, width * 2 / 3)).left().expandX().row()
        block.add(ui.label(note, Styles.BODY_MUTED, wrap = true)).left().width(width).colspan(2).padTop(8f)
        return block
    }

    private fun roundsText(count: Int) = if (count == 1) t("between.more_one") else t("between.more_many", count)

    private companion object {
        const val TARGET_PERCENT = 85
    }
}
