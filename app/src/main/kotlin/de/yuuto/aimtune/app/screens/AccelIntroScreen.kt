package de.yuuto.aimtune.app.screens

import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.ui.CheckBox
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener
import de.yuuto.aimtune.app.AccelBaseline
import de.yuuto.aimtune.app.AppContext
import de.yuuto.aimtune.app.FinderResult
import de.yuuto.aimtune.app.accel.AccelRun
import de.yuuto.aimtune.app.accel.StageProgress
import de.yuuto.aimtune.app.accel.StrengthSearch
import de.yuuto.aimtune.app.pretest.PreTestChecks
import de.yuuto.aimtune.app.ui.Disclosure
import de.yuuto.aimtune.app.ui.LedState
import de.yuuto.aimtune.app.ui.ModuleStatus
import de.yuuto.aimtune.app.ui.Styles
import de.yuuto.aimtune.app.ui.button
import de.yuuto.aimtune.app.ui.caps
import de.yuuto.aimtune.app.ui.label
import de.yuuto.aimtune.app.ui.onChange
import de.yuuto.aimtune.app.ui.primaryAction
import de.yuuto.aimtune.app.ui.profileChart
import de.yuuto.aimtune.core.accel.FeltStep
import de.yuuto.aimtune.core.accel.HoldReason
import de.yuuto.aimtune.core.accel.StageAction
import de.yuuto.aimtune.input.windows.WindowsPointerSettings
import de.yuuto.aimtune.input.windows.WindowsRawInputSource
import java.util.Locale

private const val SECONDARY_HEIGHT = 56f
private const val SECONDARY_GAP = 12f
private const val SIDE_WIDTH = 480f
private const val SIDE_GAP = 40f
private const val BOX_GAP = 12f

/**
 * Entry of the acceleration steps, in the curve layout. Left: what the search does, the sensitivity the runs are played at
 * (the game sensitivity, or the finder's result when it is confident and switched on), the options, and the start with its
 * checks as chips. The curve runs need a plain mouse, so the start stays off until Raw Accel is confirmed off or linear.
 * Right: the player's own curve when one is imported, else what the display will show.
 */
class AccelIntroScreen(
    ctx: AppContext,
    private val baseline: AccelBaseline? = ctx.accelBaseline(),
    private val plan: StageProgress? = ctx.stagePlan(baseline),
) : AccelFitScreen(ctx) {
    private var pointerAcceleration = WindowsPointerSettings.readAcceleration()
    private var introOpen = false
    private var checkOpen = false

    override fun build(): Actor {
        val animate = firstBuild()
        val left = Table()
        left.headline(t("accel.title"))
        left.add(
            Disclosure(ui, t("curve.intro.short"), introText(), t("dash.more") to t("dash.less"), CURVE_LEFT_WIDTH, introOpen) { introOpen = it },
        ).left().padTop(CURVE_SECTION_GAP / 2).row()
        left.text(t("curve.experimental"), Styles.CAUTION, CURVE_SMALL_GAP)
        if (!ctx.source.hasReportTimestamps) left.text(t("accel.needs_raw_input", ctx.source.name), Styles.CAUTION, CURVE_SECTION_GAP)
        left.add(start()).left().padTop(CURVE_SECTION_GAP).row()
        left.add(otherWays()).left().padTop(SECONDARY_GAP).row()

        val content = Table()
        content.add(curvePanel(status(), left, right(animate))).grow()
        return frame(content, curveFooter(t("common.back")) { ctx.showMenu() })
    }

    override fun onEscape() = ctx.showMenu()

    override fun onEnter() {
        if (canStart()) ctx.startAccelRun(search = true)
    }

    private fun canStart() = baseline != null && ctx.rawAccelConfirmed && ctx.source.hasReportTimestamps

    private fun introText() = t("accel.intro", StrengthSearch.DEFAULT_ROUNDS, AccelRun.DEFAULT_CALIBRATION_ROUNDS) + "\n\n" + t("accel.experimental")

    private fun status(): ModuleStatus = when {
        baseline == null -> ModuleStatus(LedState.CAUTION, t("dash.sens.status.setup"))
        !ctx.source.hasReportTimestamps -> ModuleStatus(LedState.CAUTION, t("curve.status.no_input"))
        !ctx.rawAccelConfirmed -> ModuleStatus(LedState.CAUTION, t("curve.status.check"))
        else -> ModuleStatus(LedState.OK, t("curve.status.ready"))
    }

    /** The blind search as the main action, with the checks that matter for it; the Raw Accel chip is the confirmation. */
    private fun start(): Table {
        val rows = PreTestChecks.rows(pointerAcceleration, ctx.rawAccelConfirmed, ctx.source.name, ctx.source is WindowsRawInputSource)
        val chips = rows.map { row -> ctx.checkChip(row, { pointerAcceleration = WindowsPointerSettings.readAcceleration() }, ::rebuild) }
        val detail = ctx.checkDetail(ui, rows, CURVE_LEFT_WIDTH, checkOpen) { checkOpen = it }
        return ui.primaryAction(chips, detail, t("accel.start_search"), CURVE_LEFT_WIDTH, canStart()) { ctx.startAccelRun(search = true) }
    }

    /** The calibration path and, while a stage plan runs, its next stage, as quieter buttons under the start. */
    private fun otherWays(): Table {
        val table = Table()
        val buttons = buildList {
            if (plan != null && plan.state.rung > 0) add(ui.button(t("accel.plan.play", plan.state.rung), Styles.SECONDARY) { if (canStart()) ctx.startStageRun() })
            add(ui.button(t("accel.start_calibration"), Styles.SECONDARY) { if (canStart()) ctx.startAccelRun() })
        }
        val width = (CURVE_LEFT_WIDTH - SECONDARY_GAP * (buttons.size - 1)) / buttons.size
        buttons.forEachIndexed { index, button ->
            button.isDisabled = !canStart()
            table.add(button).width(width).height(SECONDARY_HEIGHT).padLeft(if (index == 0) 0f else SECONDARY_GAP)
        }
        return table
    }

    /** The display on top; under it the sensitivity of the runs and the options, side by side, so the status column stays short. */
    private fun right(animate: Boolean): Table {
        val setup = Table().top().left()
        setup.add(ctx.runsUseBlock(ctx.runsUse(baseline), SIDE_WIDTH) { ctx.showAccel() }).top().left().width(SIDE_WIDTH).row()
        plan?.let { setup.add(planBlock(it)).left().padTop(CURVE_SECTION_GAP).row() }
        val below = Table()
        below.add(setup).top().left().width(SIDE_WIDTH)
        below.add(options() ?: Table()).top().left().width(SIDE_WIDTH).padLeft(SIDE_GAP)
        below.add().expandX()
        val right = Table()
        right.add(display(animate)).grow().row()
        right.add(below).growX().padTop(CURVE_SECTION_GAP)
        return right
    }

    /** The own curve when the trainer can play it, else an empty display that says what goes there. */
    private fun display(animate: Boolean): Table {
        val own = baseline?.ownCurve
        return curveDisplay(t("curve.display.own"), own?.let { profileChart(ui.tokens, it) }, animate, t("curve.display.empty_title"), t("curve.display.empty_intro"))
    }

    /** The switches for the runs: the finder's result, the own curve and the stored searches; null when none applies. */
    private fun options(): Table? {
        val blocks = listOfNotNull(
            baseline?.takeIf { it.canUseFinder }?.let(::finderCheck),
            baseline?.takeIf { it.ownCurveLabel != null }?.let(::ownCurveBlock),
            (baseline?.searchRounds(ctx.useFinderResult) ?: 0).takeIf { it > 0 }?.let(::priorSearchCheck),
        )
        if (blocks.isEmpty()) return null
        val table = Table()
        table.add(ui.caps(t("curve.options"))).left().row()
        blocks.forEach { table.add(it).left().padTop(CURVE_SECTION_GAP / 2).row() }
        return table
    }

    /** A checkbox with its text wrapped beside it (a CheckBox label does not wrap) and the help under the text; a click on the text toggles too. */
    private fun checkBlock(text: String, checked: Boolean, help: String, onToggle: (Boolean) -> Unit): Table {
        val block = Table()
        val box = CheckBox("", ui.skin)
        box.isChecked = checked
        box.onChange { onToggle(box.isChecked) }
        val textWidth = SIDE_WIDTH - box.prefWidth - BOX_GAP
        val label = ui.label(text, Styles.BODY, wrap = true)
        label.addListener(object : ClickListener() {
            override fun clicked(event: InputEvent, x: Float, y: Float) = box.toggle()
        })
        block.add(box).top().left()
        block.add(label).left().width(textWidth).padLeft(BOX_GAP).row()
        block.add()
        block.add(ui.label(help, Styles.CAPTION_MUTED, wrap = true)).left().width(textWidth).padLeft(BOX_GAP).padTop(CURVE_SMALL_GAP / 2)
        return block
    }

    /** Lets the player play the curve runs at the finder's result; shown only when the finder is confident enough. */
    private fun finderCheck(baseline: AccelBaseline): Table {
        val finder = checkNotNull(baseline.finder)
        val label = t("accel.use_finder", "%.1f".format(Locale.ROOT, finder.cm360), baseline.gameName, finder.sensitivityText, finder.rounds, finder.confidencePercent)
        return checkBlock(label, ctx.useFinderResult, t("accel.use_finder_help", FinderResult.MIN_CONFIDENCE)) {
            ctx.useFinderResult = it
            rebuild()
        }
    }

    /**
     * The curve imported on the dashboard: a checkbox to calibrate against it when the trainer can play it at the DPI of the
     * settings, otherwise the reason it is not used.
     */
    private fun ownCurveBlock(baseline: AccelBaseline): Table {
        val label = checkNotNull(baseline.ownCurveLabel)
        val fileDpi = baseline.ownCurveFileDpi
        if (baseline.ownCurve == null || fileDpi != null) {
            val reason = when (fileDpi) {
                null -> t("accel.own_refused", label)
                0 -> t("accel.own_dpi_unset", label)
                else -> t("accel.own_dpi_mismatch", label, fileDpi, "%.0f".format(Locale.ROOT, baseline.settingsDpi))
            }
            val block = Table()
            block.add(ui.label(reason, Styles.CAPTION_MUTED, wrap = true)).left().width(SIDE_WIDTH)
            return block
        }
        return checkBlock(t("accel.use_own", label), ctx.useOwnCurve, t("accel.use_own_help")) { ctx.useOwnCurve = it }
    }

    /** Lets the player start a strength search that ignores the stored ones (they are kept, not deleted). */
    private fun priorSearchCheck(rounds: Int): Table =
        checkBlock(t("accel.use_prior", rounds), ctx.usePriorSearch, t("accel.use_prior_help")) { ctx.usePriorSearch = it }

    /** Where the stage plan stands: the rung, its step and anchors, the sessions played and what the evidence says so far. */
    private fun planBlock(plan: StageProgress): Table {
        val block = Table()
        block.add(ui.caps(t("accel.plan.title"))).left().row()
        val rung = plan.state.rung
        val anchors = plan.anchors
        if (rung == 0 || anchors == null) {
            block.add(ui.label(t("accel.plan.ended"), Styles.CAPTION_MUTED, wrap = true)).left().width(SIDE_WIDTH).padTop(CURVE_SMALL_GAP)
            return block
        }
        fun n(value: Double) = "%.0f".format(Locale.ROOT, value)
        block.add(ui.label(t("accel.plan.stage", rung, FeltStep.LADDER.size, n(anchors.step * 100.0), n(anchors.anchorSlow), n(anchors.anchorFast)), Styles.TITLE, wrap = true))
            .left().width(SIDE_WIDTH).padTop(CURVE_SMALL_GAP).row()
        block.add(ui.label(t("accel.plan.sessions", plan.sessionsByRung[0] ?: 0, plan.sessionsByRung[rung] ?: 0), Styles.CAPTION_MUTED, wrap = true))
            .left().width(SIDE_WIDTH).padTop(CURVE_SMALL_GAP).row()
        val decision = plan.state.decision
        val status = when {
            decision == null -> "accel.plan.start"
            decision.action == StageAction.ADVANCE -> "accel.plan.advance"
            decision.action == StageAction.BACK -> "accel.plan.back"
            decision.action == StageAction.TOP -> "accel.plan.top"
            else -> when (decision.reason ?: HoldReason.ADAPTING) {
                HoldReason.ROUNDS -> "accel.plan.hold.rounds"
                HoldReason.SESSIONS -> "accel.plan.hold.sessions"
                HoldReason.BASELINE -> "accel.plan.hold.baseline"
                HoldReason.ADAPTING -> "accel.plan.hold.adapting"
            }
        }
        block.add(ui.label(t(status), Styles.CAPTION_MUTED, wrap = true)).left().width(SIDE_WIDTH).padTop(CURVE_SMALL_GAP).row()
        block.add(ui.label(t("accel.plan.hint"), Styles.CAUTION, wrap = true)).left().width(SIDE_WIDTH).padTop(CURVE_SMALL_GAP)
        return block
    }
}
