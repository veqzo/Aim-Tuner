package de.yuuto.aimtune.app.screens

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.ui.Table
import de.yuuto.aimtune.app.AppContext
import de.yuuto.aimtune.app.blind.AxisSummary
import de.yuuto.aimtune.app.blind.ScoredRound
import de.yuuto.aimtune.app.ui.ChartActor
import de.yuuto.aimtune.app.ui.Disclosure
import de.yuuto.aimtune.app.ui.LedState
import de.yuuto.aimtune.app.ui.ModuleStatus
import de.yuuto.aimtune.app.ui.Motion
import de.yuuto.aimtune.app.ui.Reveal
import de.yuuto.aimtune.app.ui.Styles
import de.yuuto.aimtune.app.ui.addButton
import de.yuuto.aimtune.app.ui.button
import de.yuuto.aimtune.app.ui.caps
import de.yuuto.aimtune.app.ui.label
import de.yuuto.aimtune.app.ui.modulePanel
import de.yuuto.aimtune.app.ui.progressBar
import de.yuuto.aimtune.app.ui.readout
import de.yuuto.aimtune.app.ui.sensitivityChart
import de.yuuto.aimtune.core.optimizer.OptimizationResult
import de.yuuto.aimtune.games.GameProfile
import java.util.Locale
import kotlin.math.roundToInt

private const val LEFT_WIDTH = 620f
private const val COLUMN_GAP = 48f
private const val WELL_PADDING_X = 36f
private const val WELL_PADDING_Y = 24f
private const val SECTION_GAP = 24f
private const val SMALL_GAP = 8f
private const val UNIT_GAP = 12f
private const val UNIT_LIFT = 14f
private const val CHART_PADDING = 20f
private const val CHART_MIN_HEIGHT = 300f
private const val FOOTER_BUTTON_HEIGHT = 64f
private const val COUNT_FROM = 0.6

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
 * Results · Sensitivity: the SENS module opened to full size. The display shows the recommended
 * in-game sensitivity and its cm/360° with the credible range; both count up once, while the score
 * chart draws on beside them. The head LED says whether more rounds would still help; the advice is
 * one sentence with the longer explanation behind "Learn more".
 */
class ResultsScreen(ctx: AppContext, private val data: ResultsData) : UiScreen(ctx) {
    private val recommendedSensitivity: String? = data.result?.let { data.profile.formatSensitivity(data.profile.sensitivityFor(data.dpi, it.optimumCm360)) }

    /** The reveal plays on the first build only; a rebuild shows the end state. */
    private var revealed = false
    private var detailOpen = false

    override fun build(): Actor {
        val animate = !revealed
        revealed = true
        val result = data.result
        val content = Table()
        if (result == null || recommendedSensitivity == null) {
            content.add(emptyPanel()).grow()
        } else {
            content.add(resultPanel(result, animate)).grow()
        }
        return frame(content, footer(recommendedSensitivity))
    }

    override fun onEscape() = ctx.showMenu()

    override fun onEnter() = ctx.startBlindTest()

    private fun emptyText() =
        if (data.rounds == 0) t("results.none") else t("results.too_few", data.rounds)

    private fun emptyPanel(): Table {
        val panel = ui.modulePanel(t("results.title"), null)
        panel.add(ui.readout(t("results.recommended"), null, "", animate = false)).left().width(LEFT_WIDTH).row()
        panel.add(ui.label(emptyText(), Styles.BODY_MUTED, wrap = true)).left().width(LEFT_WIDTH).padTop(SECTION_GAP).row()
        panel.add().expand()
        return panel
    }

    private fun resultPanel(result: OptimizationResult, animate: Boolean): Table {
        val panel = ui.modulePanel(t("results.title"), status(result))
        val left = Table().top().left()
        left.add(display(result, animate)).left().width(LEFT_WIDTH).row()
        val confidence = confidenceBlock(result, animate)
        left.add(if (animate) Reveal(confidence).play(ui.tokens.motionSlow, ui.tokens.motionSlow) else confidence).left().width(LEFT_WIDTH).padTop(SECTION_GAP).row()
        left.add().expandY()

        val body = Table()
        body.add(left).top().left().width(LEFT_WIDTH).growY()
        body.add(chartBlock(result, animate)).grow().padLeft(COLUMN_GAP)
        panel.add(body).grow()
        return panel
    }

    /** The head status: whether the result has settled, said in words and repeated by the LED. */
    private fun status(result: OptimizationResult): ModuleStatus = when {
        result.atRangeEdge -> ModuleStatus(LedState.CAUTION, t("results.status.edge"))
        result.driftDetected -> ModuleStatus(LedState.CAUTION, t("results.status.drift"))
        result.recommendMoreRounds -> ModuleStatus(LedState.CAUTION, t("results.status.more"))
        else -> ModuleStatus(LedState.OK, t("results.status.done"))
    }

    /** The recessed display: the sensitivity as the big number, then its cm/360° and the range. */
    private fun display(result: OptimizationResult, animate: Boolean): Table {
        val well = Table().left()
        well.background(ui.skin.getDrawable(Styles.WELL))
        well.pad(WELL_PADDING_Y, WELL_PADDING_X, WELL_PADDING_Y, WELL_PADDING_X)
        well.add(ui.label(t("results.recommended"), Styles.LABEL)).left().row()
        val target = data.profile.sensitivityFor(data.dpi, result.optimumCm360)
        well.add(counted(target, Styles.NUMBER_XL, animate) { data.profile.formatSensitivity(it) }).left().row()
        well.add(ui.label(t("results.in_game", data.profile.name, formatDpi()), Styles.CAPTION_MUTED)).left().padBottom(SECTION_GAP).row()
        val cm = Table()
        // The in-game value is the hero; cm/360° is its game-independent twin, one step smaller.
        cm.add(counted(result.optimumCm360, Styles.NUMBER_MD, animate) { "%.1f".format(Locale.ROOT, it) }).bottom()
        cm.add(ui.label("cm/360°", Styles.MONO_MUTED)).bottom().padLeft(UNIT_GAP / 2).padBottom(UNIT_GAP / 2)
        well.add(cm).left().row()
        val change = ((result.optimumCm360 / data.currentCm360 - 1.0) * 100.0).roundToInt()
        val range = t(
            "results.range", "%.1f".format(Locale.ROOT, result.intervalLowCm360), "%.1f".format(Locale.ROOT, result.intervalHighCm360),
            "%.1f".format(Locale.ROOT, data.currentCm360), "%+d".format(Locale.ROOT, change),
        )
        well.add(ui.label(range, Styles.MONO_MUTED)).left().padTop(SMALL_GAP)
        return well
    }

    /** A number that counts up to [value] on the reveal and stands at it on a rebuild. */
    private fun counted(value: Double, style: String, animate: Boolean, format: (Double) -> String) =
        ui.label(format(value), style).also {
            if (animate) Motion.countUp(it, value * COUNT_FROM, value, ui.tokens.motionSlow * 2, format = format)
        }

    /**
     * Confidence as number and bar, the rounds behind it, then the advice in one sentence; what equal
     * scores mean in cm/360° is the longer text behind "Learn more".
     */
    private fun confidenceBlock(result: OptimizationResult, animate: Boolean): Table {
        val block = Table().top().left()
        block.add(ui.caps(t("results.confidence"))).left().row()
        val number = Table()
        // Number and sign are two labels: in the mono face a space before % reads as a wide gap.
        number.add(counted(result.confidencePercent.toDouble(), Styles.NUMBER_LG, animate) { "%.0f".format(Locale.ROOT, it) }).bottom()
        number.add(ui.label("%", Styles.NUMBER_MD)).bottom().padLeft(UNIT_GAP / 2).padBottom(UNIT_LIFT)
        number.add(ui.label(roundsText(), Styles.CAPTION_MUTED)).bottom().right().expandX().padBottom(UNIT_LIFT)
        block.add(number).growX().row()
        block.add(ui.progressBar(result.confidencePercent / 100f, LEFT_WIDTH)).left().padTop(SMALL_GAP).row()
        val disclosure = Disclosure(ui, advice(result), equivalentText(result), t("dash.more") to t("dash.less"), LEFT_WIDTH, detailOpen) { detailOpen = it }
        block.add(disclosure).left().padTop(SECTION_GAP / 2)
        return block
    }

    private fun roundsText() =
        if (data.sessions == 1) t("results.rounds_one", data.rounds) else t("results.rounds_many", data.rounds, data.sessions)

    private fun advice(result: OptimizationResult) = when {
        result.atRangeEdge -> t("results.advice_edge")
        result.driftDetected -> t("results.advice_drift")
        result.recommendMoreRounds ->
            if (result.roundsToTarget == 1) t("results.advice_more_one") else t("results.advice_more_many", result.roundsToTarget)
        else -> t("results.advice_done")
    }

    /** What "equal scores" means in cm/360°. */
    private fun equivalentText(result: OptimizationResult) = t(
        if (result.isFlat) "results.equivalent_flat" else "results.equivalent",
        "%.0f".format(Locale.ROOT, result.equivalentLowCm360), "%.0f".format(Locale.ROOT, result.equivalentHighCm360),
    )

    /** The score chart in its own display, drawing on from left to right; the miss axes under it. */
    private fun chartBlock(result: OptimizationResult, animate: Boolean): Table {
        val block = Table().top().left()
        val well = Table()
        well.background(ui.skin.getDrawable(Styles.WELL))
        well.pad(CHART_PADDING)
        well.add(ui.label(t("results.chart"), Styles.LABEL)).left().padLeft(WELL_PADDING_X - CHART_PADDING).padTop(WELL_PADDING_Y - CHART_PADDING).row()
        val chart = ChartActor(ui, sensitivityChart(ui.tokens, result, data.scored))
        if (animate) chart.drawOn(ui.tokens.motionSlow * 3, ui.tokens.motionBase)
        well.add(chart).grow().minHeight(CHART_MIN_HEIGHT)
        block.add(well).grow().row()
        axesText()?.let {
            val axes = Table()
            axes.add(ui.caps(t("results.axes"))).left().padRight(COLUMN_GAP / 2)
            axes.add(ui.label(it, Styles.BODY_MUTED, wrap = true)).left().growX()
            block.add(axes).growX().padTop(SECTION_GAP / 2)
        }
        return block
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

    private fun footer(sensitivity: String?): Table {
        val footer = Table()
        footer.add(ui.button(t("common.back"), Styles.TEXT) { ctx.showMenu() }).left().height(FOOTER_BUTTON_HEIGHT).padLeft(8f)
        footer.add().expandX()
        if (sensitivity != null) {
            footer.addButton(ui.button(t("results.copy", sensitivity), Styles.SECONDARY) { Gdx.app.clipboard.contents = sensitivity }, minWidth = 220f).padRight(16f)
        }
        footer.addButton(ui.button(t("results.continue"), Styles.PRIMARY) { ctx.startBlindTest() }, minWidth = 260f)
        return footer
    }

    private fun formatDpi(): String = if (data.dpi == data.dpi.toLong().toDouble()) data.dpi.toLong().toString() else data.dpi.toString()
}
