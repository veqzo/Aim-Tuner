package de.yuuto.aimtune.app.screens

import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox
import com.badlogic.gdx.scenes.scene2d.ui.Slider
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import de.yuuto.aimtune.app.AppContext
import de.yuuto.aimtune.app.i18n.scenarioName
import de.yuuto.aimtune.app.pretest.CheckLevel
import de.yuuto.aimtune.app.pretest.CheckRow
import de.yuuto.aimtune.app.pretest.PreTestChecks
import de.yuuto.aimtune.app.trainerConfigFor
import de.yuuto.aimtune.app.ui.CheckChip
import de.yuuto.aimtune.app.ui.Disclosure
import de.yuuto.aimtune.app.ui.LedState
import de.yuuto.aimtune.app.ui.ModuleStatus
import de.yuuto.aimtune.app.ui.Styles
import de.yuuto.aimtune.app.ui.UiSkin
import de.yuuto.aimtune.app.ui.button
import de.yuuto.aimtune.app.ui.caps
import de.yuuto.aimtune.app.ui.label
import de.yuuto.aimtune.app.ui.onChange
import de.yuuto.aimtune.app.ui.primaryAction
import de.yuuto.aimtune.app.ui.readout
import de.yuuto.aimtune.core.scenario.RangeSpeed
import de.yuuto.aimtune.core.scenario.ScenarioKind
import de.yuuto.aimtune.core.score.ScoreMetric
import de.yuuto.aimtune.input.windows.PointerAcceleration
import de.yuuto.aimtune.input.windows.WindowsPointerSettings
import de.yuuto.aimtune.input.windows.WindowsRawInputSource
import de.yuuto.aimtune.storage.AppSettings
import java.util.Locale

private const val GAP = 24f
private const val SMALL_GAP = 8f
private const val CAPTION_GAP = 10f
private const val TILE_HEIGHT = 48f
private const val VARIANT_HEIGHT = 44f
private const val LINK_HEIGHT = 44f
private const val COLUMN_GAP = 40f
private const val SLIDER_GAP = 20f
private val ROUND_LENGTHS = listOf(30, 45, 60, 90)

private val METRIC_KEYS = mapOf(
    ScoreMetric.ACCURACY to "metric.accuracy",
    ScoreMetric.TIME_TO_HIT to "metric.time_to_hit",
    ScoreMetric.TRACKING to "metric.tracking",
)

/** What the dashboard keeps of the SENS module between rebuilds. */
internal class SensState {
    var flipped = false
    var rangeMode = false
    var acceleration: PointerAcceleration? = WindowsPointerSettings.readAcceleration()
    var detailOpen = false
    var message: Pair<String, Boolean>? = null
}

/**
 * The SENS module of the dashboard. Front: the current cm/360° as the display, the checks that can
 * distort a test as chips, the Blind/Range variant and "Find my sens". Back: what a test plays and
 * how it scores (round length, scenarios, weights, Range options); every change is saved at once.
 */
internal class SensModule(
    private val ctx: AppContext,
    private val ui: UiSkin,
    private val state: SensState,
    private val width: Float,
    private val animate: Boolean,
    private val rebuild: () -> Unit,
) {
    private fun t(key: String, vararg args: Any?) = ctx.i18n.t(key, *args)

    // A fixed seed: the dashboard only reads the setup and must not use up the seeds of the next rounds.
    private val config = trainerConfigFor(ctx.settings.current, ctx.profiles, 0L)
    private val hasRange = config?.profile?.geometry?.range != null

    val status: ModuleStatus
        get() = if (ctx.isSetUp) ModuleStatus(LedState.OK, t("dash.sens.status.ready")) else ModuleStatus(LedState.CAUTION, t("dash.sens.status.setup"))

    /** Starts the test in the chosen variant; without a setup the onboarding comes first. */
    fun start() {
        if (!ctx.isSetUp) return ctx.showOnboarding(afterFinish = ctx::showMenu)
        ctx.startBlindTest(state.rangeMode && hasRange)
    }

    fun toggleRange(): Boolean {
        if (!hasRange) return false
        state.rangeMode = !state.rangeMode
        rebuild()
        return true
    }

    fun front(): Actor {
        val table = Table().top().left()
        // The display takes the free height of the module, so the front reads as one instrument and not as a gap.
        table.add(ui.readout(t("dash.sens.readout"), config?.cm360, "cm/360°", animate)).left().width(width).expandY().fillY().row()
        if (!ctx.isSetUp) {
            table.add(ui.label(t("menu.not_set_up_sub"), Styles.CAPTION_MUTED, wrap = true)).left().width(width).padTop(GAP).row()
        }
        if (hasRange) table.add(variantSwitch()).left().padTop(GAP).row()
        val rows = checkRows()
        val open = rows.firstOrNull { it.level == CheckLevel.ATTENTION }
        val detail = open?.let { row ->
            val short = t("dash.check.${row.id}.short", row.detailArg)
            val more = if (row.detailArg == null) t(row.detail) else t(row.detail, row.detailArg)
            Disclosure(ui, short, more, t("dash.more") to t("dash.less"), width, state.detailOpen) { state.detailOpen = it }
        }
        val label = if (ctx.isSetUp) t("dash.sens.start") else t("dash.sens.setup")
        table.add(ui.primaryAction(rows.map(::chip), detail, label, width) { start() }).left().padTop(GAP).row()
        table.add(links()).left().padTop(SMALL_GAP)
        return table
    }

    private fun checkRows(): List<CheckRow> = PreTestChecks.rows(
        state.acceleration, ctx.rawAccelConfirmed, ctx.source.name, ctx.source is WindowsRawInputSource,
        targetsVerified = config?.profile?.geometry?.verified, gameName = config?.profile?.name.orEmpty(),
    )

    private fun chip(row: CheckRow): CheckChip {
        val led = when (row.level) {
            CheckLevel.OK -> LedState.OK
            CheckLevel.ATTENTION -> LedState.CAUTION
            CheckLevel.UNKNOWN -> LedState.OFF
        }
        val text = t("dash.check.${row.id}") + ": " + t(row.state)
        val action: (() -> Unit)? = when (row.id) {
            // Raw Accel cannot be detected; the click is the player's word that it is off or linear, a second click takes it back.
            PreTestChecks.RAW_ACCEL -> { { ctx.rawAccelConfirmed = !ctx.rawAccelConfirmed; rebuild() } }
            PreTestChecks.POINTER -> { { state.acceleration = WindowsPointerSettings.readAcceleration(); rebuild() } }
            else -> null
        }
        return CheckChip(text, led, action)
    }

    private fun variantSwitch(): Table {
        val table = Table()
        listOf(false to "dash.sens.variant.blind", true to "dash.sens.variant.range").forEach { (range, key) ->
            val button: TextButton = ui.button(t(key), Styles.CHIP) {
                state.rangeMode = range
                rebuild()
            }
            button.setProgrammaticChangeEvents(false)
            button.isChecked = state.rangeMode == range
            button.pad(0f, 20f, 0f, 20f)
            table.add(button).height(VARIANT_HEIGHT).padRight(SMALL_GAP)
        }
        if (state.rangeMode) {
            val options = runCatching { ctx.settings.current.rangeOptions() }.getOrNull()
            options?.let {
                val bots = t(if (it.botsStrafe) "settings.range_bots.strafe" else "settings.range_bots.reset")
                val text = t("dash.sens.range_line", t("settings.range_speed.${it.speed.id}"), bots)
                table.add(ui.label(text, Styles.CAPTION_MUTED)).padLeft(SMALL_GAP)
            }
        }
        return table
    }

    private fun links(): Table {
        val table = Table()
        val count = ctx.storedRoundCount()
        val results = ui.button(if (count == 0) t("dash.sens.results_none") else t("dash.sens.results", count), Styles.TEXT) { ctx.showStoredResults() }
        results.isDisabled = count == 0
        results.pad(0f, 0f, 0f, 0f)
        table.add(results).height(LINK_HEIGHT)
        return table
    }

    // ---- back side: tuning, saved at once ----

    fun back(): Actor {
        val current = ctx.settings.current
        val column = (width - COLUMN_GAP) / 2
        val left = Table().top().left()
        left.add(ui.caps(t("settings.round_length"))).left().padBottom(CAPTION_GAP).row()
        left.add(roundLengths(current, column)).left().padBottom(GAP).row()
        left.add(ui.caps(t("settings.scenarios"))).left().padBottom(CAPTION_GAP).row()
        left.add(ui.label(scenariosHelp(), Styles.CAPTION_MUTED, wrap = true)).left().width(column).padBottom(CAPTION_GAP).row()
        left.add(scenarioTiles(current, column)).left().row()
        if (hasRange && ScenarioKind.RANGE.id in current.scenarios) left.add(rangeOptions(current, column)).left().padTop(GAP).row()

        val right = Table().top().left()
        right.add(ui.caps(t("settings.weights"))).left().padBottom(CAPTION_GAP).row()
        right.add(ui.label(weightsHelp(), Styles.CAPTION_MUTED, wrap = true)).left().width(column).padBottom(GAP).row()
        METRIC_KEYS.forEach { (metric, key) -> right.add(weightRow(current, metric, t(key))).left().width(column).padBottom(SLIDER_GAP).row() }

        val table = Table().top().left()
        table.add(left).top().left().width(column)
        table.add(right).top().left().width(column).padLeft(COLUMN_GAP).row()
        table.add().expandY().colspan(2).row()
        val foot = Table()
        state.message?.let { (text, ok) -> foot.add(ui.label(text, if (ok) Styles.OK else Styles.CAUTION, wrap = true)).left().growX() }
            ?: foot.add().growX()
        foot.add(ui.button(t("dash.tune.reset"), Styles.TEXT) { reset() }).height(LINK_HEIGHT)
        table.add(foot).colspan(2).width(width).padTop(GAP)
        return table
    }

    private fun save(settings: AppSettings) {
        val error = ctx.settings.save(settings)
        state.message = error?.let { it to false }
    }

    private fun roundLengths(current: AppSettings, column: Float): Table {
        val table = Table()
        val tileWidth = (column - SMALL_GAP * (ROUND_LENGTHS.size - 1)) / ROUND_LENGTHS.size
        ROUND_LENGTHS.forEachIndexed { index, seconds ->
            val button: TextButton = ui.button("$seconds s", Styles.TILE) {
                save(ctx.settings.current.copy(roundSeconds = seconds))
                rebuild()
            }
            button.setProgrammaticChangeEvents(false)
            button.isChecked = current.roundSeconds == seconds
            table.add(button).width(tileWidth).height(TILE_HEIGHT).padLeft(if (index == 0) 0f else SMALL_GAP)
        }
        return table
    }

    private fun scenarioTiles(current: AppSettings, column: Float): Table {
        val grid = Table()
        // One tile per row: the names ("Target switching, strafing") do not fit half a column, above all in German.
        // The flash scenario follows the flash importance of the setup, not a tile.
        ScenarioKind.entries.filter { it != ScenarioKind.FLASH && (it != ScenarioKind.RANGE || hasRange) }.forEach { kind ->
            val button: TextButton = ui.button(ctx.i18n.scenarioName(kind), Styles.TILE) { toggleScenario(kind) }
            button.setProgrammaticChangeEvents(false)
            button.isChecked = kind.id in current.scenarios
            grid.add(button).width(column).height(TILE_HEIGHT).padBottom(SMALL_GAP).row()
        }
        return grid
    }

    private fun toggleScenario(kind: ScenarioKind) {
        val current = ctx.settings.current.scenarios
        if (kind.id in current && current.size == 1) {
            state.message = t("settings.one_scenario") to false
        } else {
            val next = if (kind.id in current) current - kind.id else current + kind.id
            // The stored order follows the scenario list, as the settings screen wrote it.
            save(ctx.settings.current.copy(scenarios = ScenarioKind.entries.map { it.id }.filter { it in next }))
        }
        rebuild()
    }

    private fun weightsHelp(): String {
        val profile = config?.profile
        return if (profile?.geometry?.duel != null) t("settings.weights_help_duel", profile.name) else t("settings.weights_help")
    }

    private fun scenariosHelp(): String {
        val profile = config?.profile
        return if (profile?.geometry?.mix != null) t("settings.scenarios_help_mix", profile.name) else t("settings.scenarios_help")
    }

    private fun scoringWeights(current: AppSettings): Map<String, Double> =
        // Weights of metrics that do not score rounds (consistency, from older settings) are dropped on the first change.
        current.metricWeights.filterKeys { name -> ScoreMetric.entries.firstOrNull { it.name == name }?.scoresRounds == true }

    private fun weightRow(current: AppSettings, metric: ScoreMetric, name: String): Table {
        val row = Table()
        val weight = scoringWeights(current)[metric.name] ?: 0.0
        val value = ui.label(percent(weight), Styles.MONO)
        row.add(ui.label(name, Styles.BODY)).left().expandX()
        row.add(value).right().row()
        val slider = Slider(0f, 100f, 1f, false, ui.skin)
        slider.value = weight.toFloat()
        slider.onChange {
            value.setText(percent(slider.value.toDouble()))
            // Saved when the drag ends (the slider fires once more on release), not on every step of it.
            if (!slider.isDragging) {
                val weights = scoringWeights(ctx.settings.current) + (metric.name to slider.value.toDouble())
                save(ctx.settings.current.copy(metricWeights = weights))
                if (state.message != null) rebuild()
            }
        }
        row.add(slider).growX().colspan(2).padTop(SMALL_GAP)
        return row
    }

    private fun percent(value: Double) = "%.0f %%".format(Locale.ROOT, value)

    private fun rangeOptions(current: AppSettings, column: Float): Table {
        val table = Table()
        val selectWidth = column / 2 - SMALL_GAP
        fun select(caption: String, items: List<String>, selected: Int, onPick: (Int) -> AppSettings) {
            val box = SelectBox<String>(ui.skin)
            box.setItems(*items.toTypedArray())
            box.selected = items[selected]
            box.onChange {
                save(onPick(items.indexOf(box.selected)))
                rebuild()
            }
            table.add(ui.label(caption, Styles.CAPTION_MUTED)).left().width(column / 2).padBottom(SMALL_GAP / 2)
            table.add(box).width(selectWidth).height(VARIANT_HEIGHT).padBottom(SMALL_GAP).row()
        }
        val speed = RangeSpeed.fromId(current.rangeSpeed) ?: RangeSpeed.NONE
        select(t("settings.range_speed"), RangeSpeed.entries.map { t("settings.range_speed.${it.id}") }, speed.ordinal) {
            ctx.settings.current.copy(rangeSpeed = RangeSpeed.entries[it].id)
        }
        select(t("settings.range_bots"), listOf(t("settings.range_bots.strafe"), t("settings.range_bots.reset")), if (current.rangeBotsStrafe) 0 else 1) {
            ctx.settings.current.copy(rangeBotsStrafe = it == 0)
        }
        select(t("settings.range_armor"), listOf(t("settings.range_armor.on"), t("settings.range_armor.off")), if (current.rangeBotArmor) 0 else 1) {
            ctx.settings.current.copy(rangeBotArmor = it == 0)
        }
        return table
    }

    /** Back to the defaults of round length, weights, scenarios and Range options; the setup stays. */
    private fun reset() {
        val geometry = config?.profile?.geometry
        save(
            ctx.settings.current.copy(
                roundSeconds = AppSettings.DEFAULT_ROUND_SECONDS,
                metricWeights = AppSettings.DEFAULT_WEIGHTS,
                scenarios = AppSettings.defaultScenarios(geometry?.range != null, geometry?.mix != null),
                rangeSpeed = RangeSpeed.NONE.id,
                rangeBotsStrafe = true,
                rangeBotArmor = true,
            ),
        )
        if (state.message == null) state.message = t("dash.tune.reset_done") to true
        rebuild()
    }
}
