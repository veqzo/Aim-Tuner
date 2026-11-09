package de.yuuto.aimtune.app.screens

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.utils.Align
import de.yuuto.aimtune.app.AppContext
import de.yuuto.aimtune.app.blind.AxisSummary
import de.yuuto.aimtune.app.blind.ScoredRound
import de.yuuto.aimtune.app.ui.ChartActor
import de.yuuto.aimtune.app.ui.sensitivityChart
import de.yuuto.aimtune.app.ui.Styles
import de.yuuto.aimtune.app.ui.addButton
import de.yuuto.aimtune.app.ui.button
import de.yuuto.aimtune.app.ui.caps
import de.yuuto.aimtune.app.ui.frame
import de.yuuto.aimtune.app.ui.label
import de.yuuto.aimtune.core.optimizer.OptimizationResult
import de.yuuto.aimtune.games.GameProfile
import java.util.Locale
import kotlin.math.roundToInt

private const val COLUMN_GAP = 96f
private const val CARD_WIDTH = 520f
private const val CARD_PADDING = 32f
private const val UNIT_GAP = 16f
private const val UNIT_BASELINE_LIFT = 28f
private const val FOOTER_BUTTON_HEIGHT = 64f
private const val CHART_MIN_HEIGHT = 240f
private const val AXES_WIDTH = 440f

/** What the results screen shows; built by [AppContext] from a finished run or from stored rounds. */
data class ResultsData(
    val profile: GameProfile,
    val dpi: Double,
    val currentCm360: Double,
    val result: OptimizationResult?,
    val rounds: Int,
    val sessions: Int,
    /** Every round with its score, for the chart dots. */
    val scored: List<ScoredRound> = emptyList(),
    /** Which axis the misses come from; null while too few rounds have movements. */
    val axes: AxisSummary? = null,
)

/**
 * Results · Sensitivity: the recommended in-game sensitivity, its cm/360° with the credible range,
 * and the confidence. The score chart of the design (frame 1j) comes with the results milestone.
 */
class ResultsScreen(ctx: AppContext, private val data: ResultsData) : UiScreen(ctx) {
    private val recommendedSensitivity: String? = data.result?.let { data.profile.formatSensitivity(data.profile.sensitivityFor(data.dpi, it.optimumCm360)) }

    override fun build(): Actor {
        val content = Table()
        content.add(ui.label(t("results.title"), Styles.HEADING)).left().padBottom(24f).row()
        val result = data.result
        if (result == null || recommendedSensitivity == null) {
            content.add(ui.label(emptyText(), Styles.BODY_MUTED, wrap = true)).left().width(CARD_WIDTH * 2)
        } else {
            val columns = Table()
            columns.add(recommendation(result, recommendedSensitivity)).top().left()
            val right = Table()
            right.add(confidenceCard(result)).top().left().width(CARD_WIDTH).row()
            right.add(equivalentText(result)).left().padTop(24f)
            columns.add(right).top().left().padLeft(COLUMN_GAP)
            axesBlock()?.let { columns.add(it).top().left().padLeft(COLUMN_GAP) }
            content.add(columns).left().padBottom(32f).row()
            content.add(chartBlock(result)).left().grow()
        }
        return frame(content, footer(recommendedSensitivity))
    }

    override fun onEscape() = ctx.showMenu()

    override fun onEnter() = ctx.showStart()

    private fun emptyText() =
        if (data.rounds == 0) t("results.none") else t("results.too_few", data.rounds)

    private fun recommendation(result: OptimizationResult, sensitivity: String): Table {
        val block = Table()
        block.add(ui.caps(t("results.recommended"))).left().padBottom(16f).row()
        block.add(ui.label(sensitivity, Styles.NUMBER_XL)).left().row()
        block.add(ui.label(t("results.in_game", data.profile.name, formatDpi()), Styles.CAPTION_MUTED)).left().padBottom(32f).row()
        val cm = Table()
        cm.add(ui.label("%.1f".format(Locale.ROOT, result.optimumCm360), Styles.NUMBER_LG)).bottom()
        cm.add(ui.label("cm/360°", Styles.NUMBER_MD).also { it.setAlignment(Align.left) }).bottom().padLeft(UNIT_GAP).padBottom(UNIT_BASELINE_LIFT / 2)
        block.add(cm).left().padBottom(8f).row()
        val change = ((result.optimumCm360 / data.currentCm360 - 1.0) * 100.0).roundToInt()
        val range = t(
            "results.range", "%.1f".format(Locale.ROOT, result.intervalLowCm360), "%.1f".format(Locale.ROOT, result.intervalHighCm360),
            "%.1f".format(Locale.ROOT, data.currentCm360), "%+d".format(Locale.ROOT, change),
        )
        block.add(ui.label(range, Styles.MONO_MUTED)).left().row()
        return block
    }

    /** What "equal scores" means in cm/360°; under the confidence card so the left column stays short enough for the footer. */
    private fun equivalentText(result: OptimizationResult): Table {
        val equivalent = t(
            if (result.isFlat) "results.equivalent_flat" else "results.equivalent",
            "%.0f".format(Locale.ROOT, result.equivalentLowCm360), "%.0f".format(Locale.ROOT, result.equivalentHighCm360),
        )
        return Table().also { it.add(ui.label(equivalent, Styles.BODY_MUTED, wrap = true)).left().width(CARD_WIDTH) }
    }

    /** Which axis the misses come from, in the free column to the right of the confidence card; null while too few rounds have movements. */
    private fun axesBlock(): Table? {
        val text = axesText() ?: return null
        return Table().also {
            it.add(ui.caps(t("results.axes"))).left().padBottom(12f).row()
            it.add(ui.label(text, Styles.BODY_MUTED, wrap = true)).left().width(AXES_WIDTH)
        }
    }

    /** Which axis the endpoint errors of the first movements come from, e.g. "most of your misses are vertical". */
    private fun axesText(): String? {
        val axes = data.axes ?: return null
        val vertical = (axes.verticalShare * 100).roundToInt()
        return when (axes.verdict) {
            AxisSummary.Verdict.VERTICAL_HIGH -> t("results.axes_vertical_high", vertical)
            AxisSummary.Verdict.VERTICAL_LOW -> t("results.axes_vertical_low", vertical)
            AxisSummary.Verdict.VERTICAL -> t("results.axes_vertical", vertical)
            AxisSummary.Verdict.HORIZONTAL -> t("results.axes_horizontal", 100 - vertical)
            AxisSummary.Verdict.BALANCED -> t("results.axes_balanced", vertical)
        }
    }

    private fun chartBlock(result: OptimizationResult): Table {
        val block = Table()
        block.add(ui.caps(t("results.chart"))).left().padBottom(12f).row()
        block.add(ChartActor(ui, sensitivityChart(ui.tokens, result, data.scored))).grow().minHeight(CHART_MIN_HEIGHT)
        return block
    }

    private fun confidenceCard(result: OptimizationResult): Table {
        val card = Table()
        card.background(ui.skin.getDrawable(Styles.CARD))
        card.pad(CARD_PADDING)
        card.add(ui.caps(t("results.confidence"))).left().row()
        card.add(ui.label("${result.confidencePercent} %", Styles.NUMBER_LG)).left().padTop(8f).row()
        card.add(ui.label(if (data.sessions == 1) t("results.rounds_one", data.rounds) else t("results.rounds_many", data.rounds, data.sessions), Styles.CAPTION_MUTED)).left().padTop(4f).row()
        val advice = when {
            result.atRangeEdge -> t("results.advice_edge")
            result.driftDetected -> t("results.advice_drift")
            result.recommendMoreRounds ->
                if (result.roundsToTarget == 1) t("results.advice_more_one") else t("results.advice_more_many", result.roundsToTarget)
            else -> t("results.advice_done")
        }
        card.add(ui.label(advice, Styles.BODY_MUTED, wrap = true)).left().width(CARD_WIDTH - 2 * CARD_PADDING).padTop(16f)
        return card
    }

    private fun footer(sensitivity: String?): Table {
        val footer = Table()
        footer.add(ui.button(t("common.back"), Styles.TEXT) { ctx.showMenu() }).left().height(FOOTER_BUTTON_HEIGHT).padLeft(8f)
        footer.add().expandX()
        if (sensitivity != null) {
            footer.addButton(ui.button(t("results.copy", sensitivity), Styles.SECONDARY) { Gdx.app.clipboard.contents = sensitivity }, minWidth = 220f).padRight(16f)
        }
        footer.addButton(ui.button(t("results.continue"), Styles.PRIMARY) { ctx.showStart() }, minWidth = 260f)
        return footer
    }

    private fun formatDpi(): String = if (data.dpi == data.dpi.toLong().toDouble()) data.dpi.toLong().toString() else data.dpi.toString()
}
