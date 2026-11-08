package de.yuuto.aimtune.app.screens

import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox
import com.badlogic.gdx.scenes.scene2d.ui.Slider
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.scenes.scene2d.ui.TextField
import de.yuuto.aimtune.app.AppContext
import de.yuuto.aimtune.app.update.AppVersion
import de.yuuto.aimtune.app.i18n.Language
import de.yuuto.aimtune.app.i18n.scenarioName
import de.yuuto.aimtune.app.setup.GAME_CUSTOM
import de.yuuto.aimtune.app.setup.SettingsExit
import de.yuuto.aimtune.app.setup.SetupDraft
import de.yuuto.aimtune.app.setup.SetupStep
import de.yuuto.aimtune.app.ui.Styles
import de.yuuto.aimtune.app.ui.addButton
import de.yuuto.aimtune.app.ui.button
import de.yuuto.aimtune.app.ui.caps
import de.yuuto.aimtune.app.ui.frame
import de.yuuto.aimtune.app.ui.label
import de.yuuto.aimtune.app.ui.onChange
import de.yuuto.aimtune.core.scenario.RangeSpeed
import de.yuuto.aimtune.core.scenario.ScenarioKind
import de.yuuto.aimtune.core.score.FlashImportance
import de.yuuto.aimtune.core.score.ScoreMetric
import de.yuuto.aimtune.storage.AppSettings
import java.util.Locale

private const val LABEL_WIDTH = 300f
private const val FIELD_WIDTH = 300f
private const val CONTROL_HEIGHT = 56f
private const val ROW_GAP = 28f
private const val COLUMN_GAP = 160f
private const val WEIGHTS_WIDTH = 640f
private const val SLIDER_ROW_GAP = 28f
private const val SEGMENT_WIDTH = 100f
private const val SEGMENT_GAP = 8f
private const val SCENARIO_WIDTH = 300f
private const val SCENARIOS_GAP = 40f
private const val FOOTER_BUTTON_HEIGHT = 64f
private val ROUND_LENGTHS = listOf(30, 45, 60, 90)
private const val FLASH_SEGMENT_WIDTH = 150f
private const val FLASH_HELP_WIDTH = 760f
private const val SOUND_TILE_WIDTH = 200f
private const val UPDATE_TILE_WIDTH = 440f
private const val UPDATE_BUTTON_WIDTH = 440f
private const val RANGE_SELECT_WIDTH = 170f
private const val RANGE_SEGMENT_HEIGHT = 44f

private val METRIC_KEYS = mapOf(
    ScoreMetric.ACCURACY to "metric.accuracy",
    ScoreMetric.TIME_TO_HIT to "metric.time_to_hit",
    ScoreMetric.TRACKING to "metric.tracking",
)

/**
 * Edit game, DPI, sensitivity, round length and score weights; aim style and pad via "Run setup again". Save, Back and Escape go to
 * [returnTo] when another screen linked here (the Raw Accel hub), else to the menu.
 */
class SettingsScreen(ctx: AppContext, private val returnTo: (() -> Unit)? = null) : UiScreen(ctx) {
    private val exit = SettingsExit(returnTo, ctx::showMenu)
    private var draft = SetupDraft.fromSettings(ctx.settings.current)
    private var roundSeconds = ctx.settings.current.roundSeconds
    private var language = Language.fromCode(ctx.settings.current.language)
    // Weights of metrics that do not score rounds (consistency, from older settings) are dropped on the first save.
    private val weights = ctx.settings.current.metricWeights.filterKeys { name -> ScoreMetric.entries.firstOrNull { it.name == name }?.scoresRounds == true }.toMutableMap()
    private val scenarios = ctx.settings.current.scenarios.toMutableSet()
    private var flashSound = ctx.settings.current.flashSound
    private var rangeSpeed = RangeSpeed.fromId(ctx.settings.current.rangeSpeed) ?: RangeSpeed.NONE
    private var rangeStrafe = ctx.settings.current.rangeBotsStrafe
    private var rangeArmor = ctx.settings.current.rangeBotArmor
    private var hitSound = ctx.settings.current.hitSound
    private var checkForUpdates = ctx.settings.current.checkForUpdates
    private var message: Pair<String, Boolean>? = null
    private var updateStatus: String? = null
    private var startOver = StartOverState.IDLE
    private var liveCm: Label? = null
    private var totalLabel: Label? = null

    override fun build(): Actor {
        val left = Table()
        left.add(ui.label(t("settings.title"), Styles.HEADING)).left().padBottom(ROW_GAP + 8f).colspan(2).row()
        addRow(left, t("settings.game"), gameSelect())
        if (draft.gameId == GAME_CUSTOM) addRow(left, t("settings.yaw"), textField(draft.customYawText) { draft = draft.copy(customYawText = it) })
        if (draft.gameId == GAME_CUSTOM) addRow(left, t("settings.fov"), textField(draft.customFovText) { draft = draft.copy(customFovText = it) })
        addRow(left, t("settings.dpi"), textField(draft.dpiText) { draft = draft.copy(dpiText = it) })
        addRow(left, t("settings.sensitivity"), sensitivityControl())
        addRow(left, t("settings.round_length"), roundLengthButtons())
        addRow(left, t("settings.language"), languageSelect())
        addRow(left, t("settings.flash"), flashImportanceButtons(draft.flashImportance) { draft = draft.copy(flashImportance = it); message = null; rebuild() })
        left.add(ui.label(t("settings.flash_help"), Styles.CAPTION_MUTED, wrap = true)).colspan(2).left().width(FLASH_HELP_WIDTH).padBottom(ROW_GAP).row()
        addRow(left, t("settings.sounds"), soundToggles())
        val runSetup = ui.button(t("settings.run_setup"), Styles.SECONDARY) { ctx.showOnboarding(SetupStep.DPI, afterFinish = { ctx.showSettings(returnTo) }) }
        addRow(left, t("settings.style_pad"), Table().also { it.add(runSetup).height(CONTROL_HEIGHT) })
        addRow(left, t("settings.data"), Table().also { it.add(startOverButton()).width(START_OVER_WIDTH).height(CONTROL_HEIGHT) })

        val right = Table()
        right.add(weightsBlock()).left().row()
        right.add(scenariosBlock()).left().padTop(SCENARIOS_GAP).row()
        val content = Table()
        content.add(left).top().left()
        content.add(right).top().left().padLeft(COLUMN_GAP).padTop(ui.tokens.footerHeight)
        return ui.frame(null, content, footer())
    }

    override fun onEscape() = exit.leave()

    private fun footer(): Table {
        val footer = Table()
        footer.add(ui.button(t("common.back"), Styles.TEXT) { exit.leave() }).left().height(FOOTER_BUTTON_HEIGHT).padLeft(8f)
        // Next to the sound switches it would widen the left column; the footer has room.
        footer.add(ui.button(t("settings.sound_levels"), Styles.SECONDARY) { ctx.showSoundSettings() }).left().height(FOOTER_BUTTON_HEIGHT).padLeft(16f)
        footer.add(updateToggle()).left().height(FOOTER_BUTTON_HEIGHT).padLeft(16f)
        // The result is the label of the button, not a footer message: a longer footer would be wider than the window.
        footer.add(ui.button(updateStatus ?: t("settings.update_now"), Styles.SECONDARY) { checkUpdateNow() }).left().width(UPDATE_BUTTON_WIDTH).height(FOOTER_BUTTON_HEIGHT).padLeft(16f)
        footer.add().expandX()
        message?.let { (text, ok) -> footer.add(ui.label(text, if (ok) Styles.OK else Styles.CAUTION)).right().padRight(32f) }
        footer.add(ui.button(t("settings.reset"), Styles.TEXT) { reset() }).height(FOOTER_BUTTON_HEIGHT).padRight(16f)
        footer.addButton(ui.button(t("settings.save"), Styles.PRIMARY) { save() }, minWidth = 180f)
        return footer
    }

    private fun addRow(table: Table, name: String, control: Actor) {
        table.add(ui.label(name, Styles.BODY_MUTED)).left().width(LABEL_WIDTH).padBottom(ROW_GAP)
        table.add(control).left().padBottom(ROW_GAP).row()
    }

    /** A number field in a fixed-size cell, for the label/control rows. */
    private fun textField(text: String, onText: (String) -> Unit): Table =
        Table().also { it.add(rawField(text, onText)).width(FIELD_WIDTH).height(CONTROL_HEIGHT) }

    private fun rawField(text: String, onText: (String) -> Unit): TextField {
        val field = TextField(text, ui.skin, Styles.INPUT)
        field.setTextFieldFilter { _, c -> c.isDigit() || c == '.' || c == ',' }
        field.maxLength = MAX_INPUT_CHARS
        field.onChange {
            message = null
            onText(field.text)
            liveCm?.setText(liveText())
        }
        return field
    }

    private fun gameSelect(): Table {
        val select = SelectBox<String>(ui.skin)
        val options = ctx.profiles.profiles.map { it.id to it.name } + (GAME_CUSTOM to t("onboarding.game.custom"))
        select.setItems(*options.map { it.second }.toTypedArray())
        select.selected = options.firstOrNull { it.first == draft.gameId }?.second
        select.onChange {
            val id = options.first { it.second == select.selected }.first
            draft = draft.copy(gameId = id)
            message = null
            rebuild()
        }
        val table = Table()
        table.add(select).width(FIELD_WIDTH + 80f).height(CONTROL_HEIGHT)
        val yaw = draft.profile(ctx.profiles)?.yawDegreesPerCount
        table.add(ui.label(yaw?.let { t("settings.yaw_hint", SetupDraft.formatNumber(it)) } ?: "", Styles.MONO_MUTED)).padLeft(16f)
        return table
    }

    private fun languageSelect(): Table {
        val select = SelectBox<String>(ui.skin)
        select.setItems(*Language.entries.map { it.displayName }.toTypedArray())
        select.selected = language.displayName
        select.onChange {
            language = Language.entries.first { it.displayName == select.selected }
            message = null
            // The language applies at once; Save keeps it. Leaving without saving restores the stored one.
            ctx.previewLanguage(language)
            rebuild()
        }
        val table = Table()
        table.add(select).width(FIELD_WIDTH + 80f).height(CONTROL_HEIGHT)
        return table
    }

    private fun sensitivityControl(): Table {
        val table = Table()
        table.add(rawField(draft.sensitivityText) { draft = draft.copy(sensitivityText = it) }).width(FIELD_WIDTH).height(CONTROL_HEIGHT)
        liveCm = ui.label(liveText(), Styles.MONO_MUTED).also { table.add(it).padLeft(24f) }
        return table
    }

    private fun liveText(): String = draft.currentCm360(ctx.profiles)?.let { t("settings.live_cm", "%.1f".format(Locale.ROOT, it)) } ?: ""

    private fun roundLengthButtons(): Table {
        val table = Table()
        ROUND_LENGTHS.forEach { seconds ->
            val button: TextButton = ui.button("$seconds s", Styles.TILE) {
                roundSeconds = seconds
                message = null
                rebuild()
            }
            button.setProgrammaticChangeEvents(false)
            button.isChecked = roundSeconds == seconds
            table.add(button).width(SEGMENT_WIDTH).height(CONTROL_HEIGHT).padRight(SEGMENT_GAP)
        }
        return table
    }

    private fun weightsBlock(): Table {
        val block = Table()
        block.add(ui.caps(t("settings.weights"))).left().padBottom(8f).row()
        block.add(ui.label(t("settings.weights_help"), Styles.CAPTION_MUTED)).left().padBottom(32f).row()
        METRIC_KEYS.forEach { (metric, key) -> block.add(weightRow(metric, t(key))).left().width(WEIGHTS_WIDTH).padBottom(SLIDER_ROW_GAP).row() }
        totalLabel = ui.label(totalText(), Styles.MONO_MUTED).also { block.add(it).left() }
        return block
    }

    private fun weightRow(metric: ScoreMetric, name: String): Table {
        val row = Table()
        val value = ui.label(percentText(metric), Styles.MONO)
        row.add(ui.label(name, Styles.BODY)).left().expandX()
        row.add(value).right().row()
        val slider = Slider(0f, 100f, 1f, false, ui.skin)
        slider.value = (weights[metric.name] ?: 0.0).toFloat()
        slider.onChange {
            weights[metric.name] = slider.value.toDouble()
            message = null
            value.setText(percentText(metric))
            totalLabel?.setText(totalText())
        }
        row.add(slider).growX().colspan(2).padTop(12f)
        return row
    }

    /** Asks GitHub now and says in the footer what came back; a new version is then also offered on the main menu. */
    private fun checkUpdateNow() {
        updateStatus = t("settings.update_checking")
        rebuild()
        ctx.checkForUpdateNow { release ->
            updateStatus = when {
                release == null -> t("settings.update_failed")
                release.version > AppVersion.version -> t("settings.update_found", release.version.toString())
                else -> t("settings.update_none", AppVersion.current)
            }
            if (ctx.currentScreen === this) rebuild()
        }
    }

    /**
     * Deletes all rounds after a backup; the settings stay. It takes two clicks: the first only arms it and says what will happen.
     * The outcome is the label of the button, as for the update check, so the footer keeps its width.
     */
    private fun startOverButton(): TextButton {
        val text = when (startOver) {
            StartOverState.IDLE -> t("settings.start_over")
            StartOverState.ARMED -> t("settings.start_over_confirm")
            StartOverState.RUNNING -> t("settings.start_over_running")
            StartOverState.DONE -> t("settings.start_over_done")
            StartOverState.FAILED -> t("settings.start_over_failed")
        }
        return ui.button(text, Styles.SECONDARY) {
            when (startOver) {
                StartOverState.RUNNING -> Unit
                StartOverState.ARMED -> {
                    startOver = StartOverState.RUNNING
                    ctx.startOver { result ->
                        startOver = if (result.isSuccess) StartOverState.DONE else StartOverState.FAILED
                        if (ctx.currentScreen === this) rebuild()
                    }
                }
                else -> startOver = StartOverState.ARMED
            }
            rebuild()
        }
    }

    /** The switch for the daily update check, in the footer: both columns are as tall as the window. */
    private fun updateToggle(): Table {
        val button: TextButton = ui.button(t("settings.update_check", t(if (checkForUpdates) "settings.on" else "settings.off")), Styles.TILE) { checkForUpdates = !checkForUpdates; message = null; rebuild() }
        button.setProgrammaticChangeEvents(false)
        button.isChecked = checkForUpdates
        return Table().also { it.add(button).width(UPDATE_TILE_WIDTH).height(CONTROL_HEIGHT) }
    }

    private fun scenariosBlock(): Table {
        val block = Table()
        block.add(ui.caps(t("settings.scenarios"))).left().padBottom(8f).row()
        block.add(ui.label(t("settings.scenarios_help"), Styles.CAPTION_MUTED)).left().padBottom(24f).row()
        val grid = Table()
        // The flash scenario is switched by the importance control below, not by a tile.
        val hasRange = draft.profile(ctx.profiles)?.geometry?.range != null
        ScenarioKind.entries.filter { it != ScenarioKind.FLASH && (it != ScenarioKind.RANGE || hasRange) }.forEachIndexed { index, kind ->
            val button: TextButton = ui.button(ctx.i18n.scenarioName(kind), Styles.TILE) { toggleScenario(kind) }
            button.setProgrammaticChangeEvents(false)
            button.isChecked = kind.id in scenarios
            grid.add(button).width(SCENARIO_WIDTH).height(CONTROL_HEIGHT).padRight(SEGMENT_GAP).padBottom(SEGMENT_GAP)
            if (index % 2 == 1) grid.row()
        }
        block.add(grid).left().row()
        if (ScenarioKind.RANGE.id in scenarios && hasRange) {
            block.add(rangeOptions()).left().padTop(SEGMENT_GAP)
        }
        return block
    }

    /** Range mode: how long an unshot bot stays, and whether bots strafe; two selects in a row and the armor select below, under the scenario tiles. */
    private fun rangeOptions(): Table {
        val table = Table()
        val speeds = RangeSpeed.entries.map { t("settings.range_speed.${it.id}") }
        val speedSelect = SelectBox<String>(ui.skin)
        speedSelect.setItems(*speeds.toTypedArray())
        speedSelect.selected = speeds[rangeSpeed.ordinal]
        speedSelect.onChange { rangeSpeed = RangeSpeed.entries[speeds.indexOf(speedSelect.selected)]; message = null }
        val bots = listOf(t("settings.range_bots.strafe"), t("settings.range_bots.reset"))
        val botsSelect = SelectBox<String>(ui.skin)
        botsSelect.setItems(*bots.toTypedArray())
        botsSelect.selected = bots[if (rangeStrafe) 0 else 1]
        botsSelect.onChange { rangeStrafe = bots.indexOf(botsSelect.selected) == 0; message = null }
        val armors = listOf(t("settings.range_armor.on"), t("settings.range_armor.off"))
        val armorSelect = SelectBox<String>(ui.skin)
        armorSelect.setItems(*armors.toTypedArray())
        armorSelect.selected = armors[if (rangeArmor) 0 else 1]
        armorSelect.onChange { rangeArmor = armors.indexOf(armorSelect.selected) == 0; message = null }
        table.add(ui.label(t("settings.range_speed"), Styles.CAPTION_MUTED)).left().padRight(SEGMENT_GAP)
        table.add(speedSelect).width(RANGE_SELECT_WIDTH).height(RANGE_SEGMENT_HEIGHT).padRight(SCENARIOS_GAP / 2)
        table.add(ui.label(t("settings.range_bots"), Styles.CAPTION_MUTED)).left().padRight(SEGMENT_GAP)
        table.add(botsSelect).width(RANGE_SELECT_WIDTH).height(RANGE_SEGMENT_HEIGHT).row()
        table.add(ui.label(t("settings.range_armor"), Styles.CAPTION_MUTED)).left().padRight(SEGMENT_GAP).padTop(SEGMENT_GAP)
        table.add(armorSelect).width(RANGE_SELECT_WIDTH).height(RANGE_SEGMENT_HEIGHT).left().padTop(SEGMENT_GAP)
        return table
    }

    private fun toggleScenario(kind: ScenarioKind) {
        if (kind.id in scenarios && scenarios.size == 1) {
            message = t("settings.one_scenario") to false
        } else {
            if (!scenarios.remove(kind.id)) scenarios += kind.id
            message = null
        }
        rebuild()
    }

    private fun flashImportanceButtons(selected: FlashImportance, onPick: (FlashImportance) -> Unit): Table =
        ui.flashImportanceButtons(flashImportanceLabels(), selected, onPick, segmentWidth = FLASH_SEGMENT_WIDTH)

    private fun flashImportanceLabels() = FlashImportance.entries.associateWith { t("flash.importance.${it.name.lowercase()}") }

    /** Both sound switches in one row, so the left column stays short enough for the footer. */
    private fun soundToggles(): Table {
        val table = Table()
        listOf(t("settings.flash_sound") to flashSound, t("settings.hit_sound") to hitSound).forEachIndexed { index, (name, on) ->
            val button: TextButton = ui.button(name, Styles.TILE) {
                if (index == 0) flashSound = !flashSound else hitSound = !hitSound
                message = null
                rebuild()
            }
            button.setProgrammaticChangeEvents(false)
            button.isChecked = on
            table.add(button).width(SOUND_TILE_WIDTH).height(CONTROL_HEIGHT).padRight(SEGMENT_GAP)
        }
        return table
    }

    private fun percentText(metric: ScoreMetric) = "%.0f %%".format(Locale.ROOT, weights[metric.name] ?: 0.0)

    private fun totalText() = t("settings.total", "%.0f".format(Locale.ROOT, METRIC_KEYS.keys.sumOf { weights[it.name] ?: 0.0 }))

    private fun save() {
        val problem = listOf(SetupStep.DPI, SetupStep.GAME).firstNotNullOfOrNull { draft.issue(it, ctx.profiles) }
        if (problem != null) {
            message = t(problem.key) to false
            rebuild()
            return
        }
        val enabled = ScenarioKind.entries.map { it.id }.filter { it in scenarios }
        val base: AppSettings = ctx.settings.current.copy(roundSeconds = roundSeconds, metricWeights = weights.toMap(), scenarios = enabled, language = language.code, flashSound = flashSound, hitSound = hitSound, checkForUpdates = checkForUpdates, rangeSpeed = rangeSpeed.id, rangeBotsStrafe = rangeStrafe, rangeBotArmor = rangeArmor)
        val error = ctx.settings.save(draft.toSettings(base, ctx.profiles))
        if (error == null) {
            exit.leave()
        } else {
            message = error to false
            rebuild()
        }
    }

    private fun reset() {
        roundSeconds = AppSettings.DEFAULT_ROUND_SECONDS
        weights.clear()
        weights.putAll(AppSettings.DEFAULT_WEIGHTS)
        scenarios.clear()
        val geometry = draft.profile(ctx.profiles)?.geometry
        scenarios.addAll(AppSettings.defaultScenarios(geometry?.range != null, geometry?.mix != null))
        draft = draft.copy(flashImportance = FlashImportance.SOMETIMES)
        flashSound = true
        hitSound = true
        rangeSpeed = RangeSpeed.NONE
        rangeStrafe = true
        rangeArmor = true
        checkForUpdates = true
        message = t("settings.reset_done") to true
        rebuild()
    }

    private enum class StartOverState { IDLE, ARMED, RUNNING, DONE, FAILED }

    private companion object {
        const val MAX_INPUT_CHARS = 10
        const val START_OVER_WIDTH = 520f
    }
}
