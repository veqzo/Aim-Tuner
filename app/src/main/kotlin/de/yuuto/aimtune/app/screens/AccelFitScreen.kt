package de.yuuto.aimtune.app.screens

import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.ui.Table
import de.yuuto.aimtune.app.AppContext
import de.yuuto.aimtune.app.accel.LastCalibration
import de.yuuto.aimtune.app.accel.PriorCalibration
import de.yuuto.aimtune.app.accel.StrengthResult
import de.yuuto.aimtune.app.ui.ChartActor
import de.yuuto.aimtune.app.ui.ChartSpec
import de.yuuto.aimtune.app.ui.ModuleStatus
import de.yuuto.aimtune.app.ui.Styles
import de.yuuto.aimtune.app.ui.addButton
import de.yuuto.aimtune.app.ui.button
import de.yuuto.aimtune.app.ui.caps
import de.yuuto.aimtune.app.ui.label
import de.yuuto.aimtune.app.ui.modulePanel
import de.yuuto.aimtune.core.accel.AccelFit
import de.yuuto.aimtune.core.accel.AccelFitResult
import de.yuuto.aimtune.core.accel.AccelReason
import de.yuuto.aimtune.core.accel.ClassicCurve
import de.yuuto.aimtune.core.accel.CurveStrength
import de.yuuto.aimtune.core.accel.LookupCurve
import de.yuuto.aimtune.core.accel.NaturalCurve
import de.yuuto.aimtune.core.accel.PowerCurve
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Width of the status column of the curve panel; the display takes the rest. */
internal const val CURVE_LEFT_WIDTH = 640f
internal const val CURVE_SECTION_GAP = 28f
internal const val CURVE_SMALL_GAP = 8f
internal const val CURVE_FOOTER_BUTTON_HEIGHT = 64f
private const val COLUMN_GAP = 48f
private const val CHART_PADDING = 20f
private const val CAPTION_PADDING_X = 16f
private const val CAPTION_PADDING_Y = 4f
private const val CHART_MIN_HEIGHT = 300f
private const val EMPTY_PADDING = 36f

/**
 * The "curve expanded" layout that every screen of the curve flow shares (find, analysis, search result, verdict, last
 * search, last analysis): the CURVE module opened to full size, status and the facts on the left, the curve display on
 * the right. It also holds the blocks those screens have in common: the found curve, the strength of the blind search,
 * the reason text and the note that an unconfirmed curve is not exported.
 */
abstract class AccelFitScreen(ctx: AppContext) : UiScreen(ctx) {
    private var revealed = false

    /** True on the first build only: the display draws on once, a rebuild shows the end state. */
    protected fun firstBuild(): Boolean = (!revealed).also { revealed = true }

    /** The open CURVE module: [left] as the status column, [display] filling the rest. */
    protected fun curvePanel(status: ModuleStatus, left: Table, display: Actor): Table {
        val panel = ui.modulePanel(t("dash.curve.title"), status)
        left.top().left()
        left.add().expandY()
        val body = Table()
        body.add(left).top().left().width(CURVE_LEFT_WIDTH).growY()
        body.add(display).grow().padLeft(COLUMN_GAP)
        panel.add(body).grow()
        return panel
    }

    /** The headline of the status column. */
    protected fun Table.headline(text: String) {
        add(ui.label(text, Styles.HEADING, wrap = true)).left().width(CURVE_LEFT_WIDTH).row()
    }

    /** A text in the status column, [gap] below the row above it. */
    protected fun Table.text(text: String, style: String = Styles.BODY_MUTED, gap: Float = CURVE_SECTION_GAP / 2) {
        add(ui.label(text, style, wrap = true)).left().width(CURVE_LEFT_WIDTH).padTop(gap).row()
    }

    /**
     * The recessed curve display: [caption] over [chart], which draws on when [animate]; without a chart the
     * [emptyTitle] and [emptyText] say what will be shown there.
     */
    protected fun curveDisplay(caption: String, chart: ChartSpec?, animate: Boolean, emptyTitle: String, emptyText: String): Table {
        val well = Table().top().left()
        well.background(ui.skin.getDrawable(Styles.WELL))
        if (chart == null) {
            well.pad(EMPTY_PADDING)
            well.add(ui.label(emptyTitle, Styles.BODY_LG)).left().row()
            well.add(ui.label(emptyText, Styles.CAPTION_MUTED, wrap = true)).left().growX().padTop(CURVE_SMALL_GAP).row()
            well.add().expand()
            return well
        }
        well.pad(CHART_PADDING)
        well.add(ui.label(caption, Styles.LABEL, wrap = true)).left().growX().padLeft(CAPTION_PADDING_X).padTop(CAPTION_PADDING_Y).row()
        val actor = ChartActor(ui, chart)
        if (animate) actor.drawOn(ui.tokens.motionSlow * 3, ui.tokens.motionBase)
        well.add(actor).grow().minHeight(CHART_MIN_HEIGHT).padTop(CURVE_SMALL_GAP)
        return well
    }

    /** The found curve: its Raw Accel family and parameters, and how much its gain changes. */
    protected fun curveBlock(fit: AccelFit): Table {
        val block = Table().top().left()
        block.add(ui.caps(t("accel.analysis.curve_title", fit.family))).left().row()
        block.add(ui.label(parameters(fit), Styles.MONO, wrap = true)).left().width(CURVE_LEFT_WIDTH).padTop(CURVE_SMALL_GAP).row()
        block.add(
            ui.label(
                t("accel.analysis.curve_note", "%.2f".format(Locale.ROOT, fit.gainRange), "%.2f".format(Locale.ROOT, fit.sensitivityFactor)),
                Styles.CAPTION_MUTED, wrap = true,
            ),
        ).left().width(CURVE_LEFT_WIDTH).padTop(CURVE_SMALL_GAP)
        return block
    }

    /** What the blind strength search found: the best strength, its credible range and a warning when it is at the edge of the range. */
    protected fun searchBlock(r: StrengthResult, own: Boolean = false): Table {
        val block = Table().top().left()
        block.add(ui.caps(t("accel.search.card_title"))).left().row()
        val percent = ((r.strength - 1.0) * 100.0).roundToInt()
        val strength = "%.2f".format(Locale.ROOT, r.strength)
        // On top of the player's own curve a strength is how much of that curve's rise is played, not a gain against a plain mouse.
        val text = when {
            !CurveStrength.isCurve(r.strength) -> t(if (own) "accel.search.own_strength_same" else "accel.search.strength_plain")
            own && r.strength > 1.0 -> t("accel.search.own_strength_stronger", strength)
            own -> t("accel.search.own_strength_weaker", strength)
            percent > 0 -> t("accel.search.strength_rising", percent)
            else -> t("accel.search.strength_falling", abs(percent))
        }
        block.add(ui.label(text, Styles.TITLE, wrap = true)).left().width(CURVE_LEFT_WIDTH).padTop(CURVE_SMALL_GAP).row()
        val range = t(if (own) "accel.search.own_range" else "accel.search.range", "%.2f".format(Locale.ROOT, r.low), "%.2f".format(Locale.ROOT, r.high), r.confidencePercent)
        block.add(ui.label(range, Styles.CAPTION_MUTED, wrap = true)).left().width(CURVE_LEFT_WIDTH).padTop(CURVE_SMALL_GAP).row()
        if (r.atEdge) {
            val edge = t(if (own) "accel.search.own_edge" else "accel.search.edge", "%.2f".format(Locale.ROOT, r.rangeMin), "%.2f".format(Locale.ROOT, r.rangeMax))
            block.add(ui.label(edge, Styles.CAUTION, wrap = true)).left().width(CURVE_LEFT_WIDTH).padTop(CURVE_SMALL_GAP)
        }
        return block
    }

    /** The sentence under the headline of a search result. */
    protected fun searchExplanation(r: StrengthResult?, offered: Boolean, own: Boolean = false): String {
        if (r == null) return t("accel.search.too_few")
        val inRange = r.low <= CurveStrength.NEUTRAL && r.high >= CurveStrength.NEUTRAL
        val weaker = CurveStrength.isCurve(r.strength) && r.strength < CurveStrength.NEUTRAL
        return when {
            offered && own -> t("accel.search.own_offer_note", r.rounds)
            offered -> t("accel.search.offer_note", r.rounds)
            inRange && own -> t("accel.search.own_reason_range")
            inRange -> t("accel.search.reason_range")
            weaker && own -> t("accel.search.own_reason_weaker")
            weaker -> t("accel.search.reason_falling")
            own -> t("accel.search.own_reason_plain")
            else -> t("accel.search.reason_plain")
        }
    }

    protected fun reasonText(result: AccelFitResult): String = when (result.reasonCode) {
        AccelReason.NOT_ENOUGH_MOVEMENTS -> t("accel.reason.not_enough", result.reasonValue)
        AccelReason.NARROW_SPEED_RANGE -> t("accel.reason.narrow_speed")
        AccelReason.NO_FIT -> t("accel.reason.no_fit")
        AccelReason.NOT_BETTER_THAN_NONE -> t("accel.reason.not_better")
        AccelReason.SMALL_CHANGE -> t("accel.reason.small_change", result.reasonValue)
        AccelReason.FITS -> t("accel.reason.fits", result.recommended?.family.orEmpty())
    }

    /** Says that curves from an analysis or a search are not exported until a blind test passed, and why the test button may be off. */
    protected fun unconfirmedBlock(): Table {
        val block = Table().top().left()
        block.add(ui.caps(t("accel.unconfirmed.title"))).left().row()
        block.add(ui.label(t("accel.unconfirmed.text"), Styles.CAUTION, wrap = true)).left().width(CURVE_LEFT_WIDTH).padTop(CURVE_SMALL_GAP).row()
        if (!canTestBlind()) block.add(ui.label(t("accel.last.test_blocked"), Styles.CAPTION_MUTED, wrap = true)).left().width(CURVE_LEFT_WIDTH).padTop(CURVE_SMALL_GAP)
        return block
    }

    /** A footer with the Back button on the left that runs [back]; the caller adds the actions on the right. */
    protected fun curveFooter(backText: String, back: () -> Unit): Table {
        val footer = Table()
        footer.add(ui.button(backText, Styles.TEXT) { back() }).left().height(CURVE_FOOTER_BUTTON_HEIGHT).padLeft(CURVE_SMALL_GAP)
        footer.add().expandX()
        return footer
    }

    /** The blind-test button for [fit], found at [dpi] and [cm360]; it goes into the footer after the Back button. There is no export here: that comes after a passed test. */
    protected fun addCurveActions(footer: Table, fit: AccelFit, dpi: Double, cm360: Double) {
        val test = ui.button(t("accel.last.test"), Styles.PRIMARY) { if (canTestBlind()) ctx.startAccelTest(fit, LastCalibration(dpi, cm360, PriorCalibration.NONE)) }
        test.isDisabled = !canTestBlind()
        footer.addButton(test, minWidth = 300f)
    }

    /** The blind test compares with the plain mouse, so it needs the Raw Accel confirmation and a mouse with report timestamps. */
    protected fun canTestBlind() = ctx.rawAccelConfirmed && ctx.source.hasReportTimestamps

    private fun number(format: String, value: Double) = format.format(Locale.ROOT, value)

    private fun parameters(fit: AccelFit): String = when (val curve = fit.curve) {
        is ClassicCurve -> t("accel.analysis.params_classic", number("%.4f", curve.acceleration), number("%.2f", curve.exponent), number("%.2f", curve.cap))
        is NaturalCurve -> t("accel.analysis.params_natural", number("%.3f", curve.decayRate), number("%.2f", curve.limit))
        is PowerCurve -> t("accel.analysis.params_power", number("%.4f", curve.scale), number("%.2f", curve.exponent))
        is LookupCurve -> t("accel.analysis.params_table", curve.speeds.size, number("%.1f", curve.speeds.first()), number("%.1f", curve.speeds.last()))
        else -> ""
    }
}
