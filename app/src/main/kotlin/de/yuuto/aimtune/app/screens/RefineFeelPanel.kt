package de.yuuto.aimtune.app.screens

import com.badlogic.gdx.scenes.scene2d.ui.Table
import de.yuuto.aimtune.app.AppContext
import de.yuuto.aimtune.app.accel.RefineRun
import de.yuuto.aimtune.app.ui.Styles
import de.yuuto.aimtune.app.ui.addButton
import de.yuuto.aimtune.app.ui.button
import de.yuuto.aimtune.app.ui.label
import de.yuuto.aimtune.core.accel.FactorBand
import de.yuuto.aimtune.core.accel.FeelCount
import de.yuuto.aimtune.core.accel.FeelVote
import de.yuuto.aimtune.core.accel.RefineFeel
import de.yuuto.aimtune.core.accel.RefineParameter

private const val PANEL_WIDTH = 1000f

/**
 * The question after a refine round: how did it feel? The rating is kept next to the round and shown grouped at the end of a
 * stage and of the test; the score stays what a change is kept on. Empty when there is nothing to rate (or it is rated already
 * and only a short note is shown). [rebuild] redraws the screen after a rating.
 */
fun refineFeelPanel(ctx: AppContext, run: RefineRun, rebuild: () -> Unit): Table {
    val ui = ctx.ui.skin
    val panel = Table()
    val last = run.engine.rounds.lastOrNull() ?: return panel
    if (last.vote != null) {
        panel.add(ui.label(ctx.i18n.t("refine.feel.saved"), Styles.CAPTION_MUTED)).left()
        return panel
    }
    panel.add(ui.label(ctx.i18n.t("refine.feel.ask"), Styles.BODY, wrap = true)).left().width(PANEL_WIDTH).padBottom(12f).row()
    val buttons = Table()
    for ((vote, key) in listOf(FeelVote.BETTER to "refine.feel.better", FeelVote.SAME to "refine.feel.same", FeelVote.WORSE to "refine.feel.worse")) {
        buttons.addButton(
            ui.button(ctx.i18n.t(key), Styles.SECONDARY) {
                run.engine.vote(vote)
                ctx.processor.recordFeel(run.lastSeed, vote.value)
                rebuild()
            },
            minWidth = 300f,
        ).padRight(16f)
    }
    panel.add(buttons).left()
    return panel
}

/** The ratings of a stage grouped by how far the played factor was from the curve, with a note when they lean one way; empty without ratings. */
fun refineFeelStageCard(ctx: AppContext, run: RefineRun, parameter: RefineParameter, width: Float): Table {
    val ui = ctx.ui.skin
    val counts = RefineFeel.byBand(run.engine.rounds, parameter)
    val card = Table()
    if (counts.isEmpty()) return card
    card.add(ui.label(ctx.i18n.t("refine.feel.title"), Styles.CAPTION_MUTED)).left().row()
    for ((band, count) in counts) card.add(ui.label(feelLine(ctx, bandName(ctx, band), count), Styles.BODY_MUTED, wrap = true)).left().width(width).padTop(6f).row()
    RefineFeel.leaning(counts)?.let { band ->
        card.add(ui.label(ctx.i18n.t("refine.feel.lean", bandName(ctx, band), counts.getValue(band).net), Styles.CAUTION, wrap = true)).left().width(width).padTop(10f).row()
    }
    return card
}

/** The ratings of the test rounds by arm, after the verdict; empty without ratings. */
fun refineFeelTestCard(ctx: AppContext, run: RefineRun, width: Float): Table {
    val ui = ctx.ui.skin
    val (yours, refined) = RefineFeel.byArm(run.engine.rounds)
    val card = Table()
    if (yours.total + refined.total == 0) return card
    card.add(ui.label(ctx.i18n.t("refine.feel.title"), Styles.CAPTION_MUTED)).left().row()
    card.add(ui.label(feelLine(ctx, ctx.i18n.t("refine.feel.arm_yours"), yours), Styles.BODY_MUTED, wrap = true)).left().width(width).padTop(6f).row()
    card.add(ui.label(feelLine(ctx, ctx.i18n.t("refine.feel.arm_refined"), refined), Styles.BODY_MUTED, wrap = true)).left().width(width).padTop(6f).row()
    return card
}

private fun feelLine(ctx: AppContext, name: String, count: FeelCount) = ctx.i18n.t("refine.feel.line", name, count.better, count.same, count.worse)

private fun bandName(ctx: AppContext, band: FactorBand) = when (band) {
    FactorBand.WEAKER -> ctx.i18n.t("refine.feel.band_weaker")
    FactorBand.LIKE_YOURS -> ctx.i18n.t("refine.feel.band_like")
    FactorBand.STRONGER -> ctx.i18n.t("refine.feel.band_stronger")
}
