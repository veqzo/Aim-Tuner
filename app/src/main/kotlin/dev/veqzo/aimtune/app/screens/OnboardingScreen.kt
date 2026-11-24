package dev.veqzo.aimtune.app.screens

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.scenes.scene2d.ui.TextField
import dev.veqzo.aimtune.app.AppContext
import dev.veqzo.aimtune.app.setup.GAME_CUSTOM
import dev.veqzo.aimtune.app.setup.PadPreset
import dev.veqzo.aimtune.app.setup.SetupDraft
import dev.veqzo.aimtune.app.setup.SetupStep
import dev.veqzo.aimtune.app.ui.Disclosure
import dev.veqzo.aimtune.app.ui.FitCheckPanel
import dev.veqzo.aimtune.app.ui.Motion
import dev.veqzo.aimtune.app.ui.Styles
import dev.veqzo.aimtune.app.ui.addButton
import dev.veqzo.aimtune.app.ui.button
import dev.veqzo.aimtune.app.ui.caps
import dev.veqzo.aimtune.app.ui.label
import dev.veqzo.aimtune.app.ui.stagger
import dev.veqzo.aimtune.core.score.FlashImportance
import dev.veqzo.aimtune.core.units.AimStyle
import dev.veqzo.aimtune.core.units.SearchRangeTable
import java.util.Locale

private const val TITLE_GAP = 12f
private const val SECTION_GAP = 32f
private const val CAPTION_GAP = 8f
private const val FIELD_GAP = 16f
private const val TILE_GAP = 8f
private const val GAME_TILE_HEIGHT = 64f
private const val TILE_HEIGHT = 52f
private const val FIELD_WIDTH = 360f
private const val FIELD_HEIGHT = 84f
private const val WELL_PADDING_X = 36f
private const val WELL_PADDING_Y = 24f
private const val UNIT_GAP = 14f
private const val UNIT_LIFT = 22f
private const val FIT_GAP = 16f
private const val COUNT_FROM = 0.6
private const val ENTER_STEP = 0.08f
private const val FINISH_MIN_WIDTH = 260f
private const val FORMULA = "360 × 2.54 ÷ (DPI × sens × yaw)"
private val DPI_PRESETS = listOf("400", "800", "1600", "3200")

/**
 * First-launch setup on one screen. The left column asks for the three values that matter (game,
 * DPI, sensitivity); the right column shows the live cm/360° they give and the preset choices
 * (aim style, mousepad, flash) the player can keep. Nothing is saved before Finish.
 */
class OnboardingScreen(
    ctx: AppContext,
    draft: SetupDraft,
    private val afterFinish: () -> Unit,
) : UiScreen(ctx) {
    private var draft: SetupDraft = draft.withDefaults()
    private var edited = false
    private var saveError: String? = null
    private var dpiHelpOpen = false
    private var entered = false

    /** True once the live number counted up, so it does not replay on every rebuild. */
    private var counted = false

    private var finishButton: TextButton? = null
    private var issueLabel: Label? = null
    private var liveNumber: Label? = null
    private var hintLabel: Label? = null
    private var dpiPresets: Table? = null
    private var fitHolder: Table? = null

    private val twoColumns: Boolean get() = HubLayout.twoColumns(stage.viewport.worldWidth)

    private fun columnWidth(): Float {
        val stageWidth = stage.viewport.worldWidth
        if (!twoColumns) return HubLayout.columnWidth(stageWidth, ui.tokens.margin)
        return (stageWidth - 2 * ui.tokens.margin - HubLayout.COLUMN_GAP) / 2
    }

    override fun build(): Actor {
        val width = columnWidth()
        val columns = listOf<Actor>(essentials(width), live(width))
        val shown = if (entered) columns else stagger(columns, ui.tokens.motionSlow, ENTER_STEP)
        entered = true
        val content = Table().top().left()
        if (twoColumns) {
            content.add(shown[0]).top().left().width(width)
            content.add(shown[1]).top().left().width(width).padLeft(HubLayout.COLUMN_GAP)
        } else {
            content.add(shown[0]).top().left().width(width).row()
            content.add(shown[1]).top().left().width(width).padTop(SECTION_GAP)
        }
        val root = frame(content, footer(), Chrome.MINIMAL)
        refresh()
        focusFirstEmpty()
        return root
    }

    override fun onEscape() = ctx.showMenu()

    override fun onEnter() = finish()

    private fun finish() {
        if (issue() != null) {
            edited = true
            refresh()
            return
        }
        val error = ctx.settings.save(draft.toSettings(ctx.settings.current, ctx.profiles))
        if (error == null) {
            afterFinish()
        } else {
            saveError = error
            refresh()
        }
    }

    /** The first problem of the whole draft, in the order the screen shows the values. */
    private fun issue() = listOf(SetupStep.GAME, SetupStep.DPI, SetupStep.STYLE, SetupStep.PAD)
        .firstNotNullOfOrNull { draft.issue(it, ctx.profiles) }

    private fun edit(change: SetupDraft) {
        draft = change
        edited = true
        saveError = null
        refresh()
    }

    /** Picking a tile changes which fields exist (custom game, custom pad), so the screen is built again. */
    private fun pick(change: SetupDraft) {
        draft = change
        saveError = null
        rebuild()
    }

    // ---- left column: the three values ------------------------------------------------------

    private fun essentials(width: Float): Table {
        val table = Table().top().left()
        table.add(ui.label(t("onboarding.title"), Styles.HEADING)).left().padBottom(TITLE_GAP).row()
        table.add(ui.label(t("onboarding.subtitle"), Styles.BODY_MUTED, wrap = true)).left().width(width).padBottom(SECTION_GAP + 8f).row()

        table.add(ui.caps(t("onboarding.game.game"))).left().padBottom(CAPTION_GAP).row()
        val games = ctx.profiles.profiles.map { it.id to it.name } + (GAME_CUSTOM to t("onboarding.game.custom"))
        table.add(ui.choiceTiles(games, width, GAME_TILE_HEIGHT, TILE_GAP, { it.first == draft.gameId }, { it.second }) { pick(draft.copy(gameId = it.first)) })
            .left().padBottom(SECTION_GAP).row()
        if (draft.gameId == GAME_CUSTOM) {
            table.add(customGame(width)).left().padBottom(SECTION_GAP).row()
        }

        table.add(ui.caps("DPI")).left().padBottom(CAPTION_GAP).row()
        table.add(dpiRow(width)).left().width(width).padBottom(CAPTION_GAP + 4f).row()
        table.add(Disclosure(ui, t("onboarding.dpi.short"), t("onboarding.dpi.help_text"), t("dash.more") to t("dash.less"), width, dpiHelpOpen) { dpiHelpOpen = it })
            .left().padBottom(SECTION_GAP).row()

        table.add(ui.caps(t("onboarding.game.sensitivity"))).left().padBottom(CAPTION_GAP).row()
        val sens = Table()
        sens.add(field(draft.sensitivityText, SENSITIVITY) { edit(draft.copy(sensitivityText = it)) }).width(FIELD_WIDTH).height(FIELD_HEIGHT)
        hintLabel = ui.label("", Styles.MONO_MUTED).also { sens.add(it).left().bottom().padLeft(FIELD_GAP + 8f).padBottom(FIELD_GAP) }
        table.add(sens).left()
        return table
    }

    private fun dpiRow(width: Float): Table {
        val row = Table()
        val field = field(draft.dpiText, DPI) { edit(draft.copy(dpiText = it)) }
        row.add(field).width(FIELD_WIDTH).height(FIELD_HEIGHT)
        val presetsWidth = width - FIELD_WIDTH - FIELD_GAP
        val presets = ui.choiceTiles(DPI_PRESETS, presetsWidth, FIELD_HEIGHT, TILE_GAP, { it == draft.dpiText.trim() }, { it }) { preset ->
            field.text = preset
            field.setCursorPosition(preset.length)
            edit(draft.copy(dpiText = preset))
        }
        dpiPresets = presets
        row.add(presets).padLeft(FIELD_GAP)
        return row
    }

    private fun customGame(width: Float): Table {
        val half = (width - FIELD_GAP) / 2
        val table = Table()
        table.add(ui.label(t("onboarding.game.custom_yaw"), Styles.CAPTION_MUTED, wrap = true)).left().bottom().width(half).padBottom(CAPTION_GAP)
        table.add(ui.label(t("onboarding.game.custom_fov"), Styles.CAPTION_MUTED, wrap = true)).left().bottom().width(half).padLeft(FIELD_GAP).padBottom(CAPTION_GAP).row()
        table.add(field(draft.customYawText) { edit(draft.copy(customYawText = it)) }).width(half).height(TILE_HEIGHT)
        table.add(field(draft.customFovText) { edit(draft.copy(customFovText = it)) }).width(half).height(TILE_HEIGHT).padLeft(FIELD_GAP)
        return table
    }

    /** Fields by role, so the first empty one can take the keyboard focus after a rebuild. */
    private val fields = mutableMapOf<String, TextField>()

    private fun field(text: String, role: String? = null, onText: (String) -> Unit): TextField {
        val field = ui.numberField(text, if (role == null) Styles.INPUT else Styles.INPUT_LG, onText)
        role?.let { fields[it] = field }
        return field
    }

    private fun focusFirstEmpty() {
        val target = when {
            draft.dpiText.isBlank() -> fields[DPI]
            draft.sensitivityText.isBlank() -> fields[SENSITIVITY]
            else -> null
        } ?: return
        Gdx.app.postRunnable {
            stage.keyboardFocus = target
            target.setCursorPosition(target.text.length)
        }
    }

    // ---- right column: live value and presets -----------------------------------------------

    private fun live(width: Float): Table {
        val table = Table().top().left()
        table.add(liveWell(width)).left().width(width).padBottom(SECTION_GAP - 4f).row()
        table.add(ui.caps(t("onboarding.defaults"))).left().padBottom(CAPTION_GAP / 2).row()
        table.add(ui.label(t("onboarding.defaults_note"), Styles.CAPTION_MUTED, wrap = true)).left().width(width).padBottom(FIELD_GAP + 4f).row()

        table.add(ui.caps(t("setup.panel.style"))).left().padBottom(CAPTION_GAP).row()
        table.add(ui.choiceTiles(AimStyle.entries, width, TILE_HEIGHT, TILE_GAP, { it == draft.aimStyle }, { t("onboarding.style.${it.name.lowercase()}") }) { pick(draft.copy(aimStyle = it)) })
            .left().padBottom(CAPTION_GAP).row()
        draft.aimStyle?.let { table.add(ui.label(styleNote(it), Styles.CAPTION_MUTED, wrap = true)).left().width(width).padBottom(FIELD_GAP + 4f).row() }

        table.add(ui.caps(t("onboarding.pad.title"))).left().padBottom(CAPTION_GAP).row()
        table.add(ui.choiceTiles(PadPreset.entries, width, TILE_HEIGHT, TILE_GAP, { it == draft.padPreset }, { t("onboarding.pad.${it.name.lowercase()}") }) { pick(draft.copy(padPreset = it)) })
            .left().padBottom(FIELD_GAP + 4f).row()
        if (draft.padPreset == PadPreset.CUSTOM) table.add(customPad(width)).left().padBottom(FIELD_GAP + 4f).row()

        table.add(ui.caps(t("settings.flash"))).left().padBottom(CAPTION_GAP).row()
        table.add(ui.choiceTiles(FlashImportance.entries, width, TILE_HEIGHT, TILE_GAP, { it == draft.flashImportance }, { t("flash.importance.${it.name.lowercase()}") }) { pick(draft.copy(flashImportance = it)) })
            .left()
        return table
    }

    /** The recessed display: the live cm/360°, how it is computed, and whether one turn fits the pad. */
    private fun liveWell(width: Float): Table {
        val well = Table()
        well.background(ui.skin.getDrawable(Styles.WELL))
        well.pad(WELL_PADDING_Y, WELL_PADDING_X, WELL_PADDING_Y, WELL_PADDING_X)
        val inner = width - 2 * WELL_PADDING_X
        well.add(ui.label(t("onboarding.game.current"), Styles.LABEL)).left().colspan(2).row()
        liveNumber = ui.label("--", Styles.NUMBER_XL).also { well.add(it).left().bottom() }
        well.add(ui.label("cm/360°", Styles.NUMBER_MD)).left().bottom().padLeft(UNIT_GAP).padBottom(UNIT_LIFT).expandX().row()
        well.add(ui.label(FORMULA, Styles.MONO_MUTED)).left().colspan(2).row()
        fitHolder = Table().also { well.add(it).left().colspan(2).width(inner).padTop(FIT_GAP) }
        return well
    }

    private fun styleNote(style: AimStyle): String {
        val (low, high) = SearchRangeTable.DEFAULT.ranges.getValue(style)
        return t("onboarding.style.${style.name.lowercase()}_desc") + " " + t("onboarding.style.typical", low.toInt(), high.toInt())
    }

    private fun customPad(width: Float): Table {
        val half = (width - FIELD_GAP) / 2
        val table = Table()
        table.add(ui.caps(t("setup.panel.pad_width"))).left().width(half).padBottom(CAPTION_GAP)
        table.add(ui.caps(t("setup.panel.pad_height"))).left().width(half).padLeft(FIELD_GAP).padBottom(CAPTION_GAP).row()
        table.add(field(draft.customPadWidthText) { edit(draft.copy(customPadWidthText = it)) }).width(half).height(TILE_HEIGHT)
        table.add(field(draft.customPadHeightText) { edit(draft.copy(customPadHeightText = it)) }).width(half).height(TILE_HEIGHT).padLeft(FIELD_GAP)
        return table
    }

    // ---- footer and in-place updates --------------------------------------------------------

    private fun footer(): Table {
        val footer = Table()
        footer.add(ui.button(t("common.back"), Styles.TEXT) { ctx.showMenu() }).left().height(GAME_TILE_HEIGHT).padLeft(8f)
        footer.add().expandX()
        issueLabel = ui.label("", Styles.CAUTION).also { footer.add(it).right().padRight(SECTION_GAP) }
        val finish = ui.button(t("onboarding.finish"), Styles.PRIMARY) { finish() }
        finishButton = finish
        footer.addButton(finish, minWidth = FINISH_MIN_WIDTH)
        return footer
    }

    /** Typing changes only the live value, the hints and the footer; the fields keep their focus. */
    private fun refresh() {
        val issue = issue()
        finishButton?.isDisabled = issue != null
        issueLabel?.setText(saveError ?: issue?.takeIf { edited }?.let { t(it.key) }.orEmpty())
        hintLabel?.setText(draft.profile(ctx.profiles)?.let { t("onboarding.game.hint_yaw", SetupDraft.formatNumber(it.yawDegreesPerCount)) }.orEmpty())
        dpiPresets?.children?.filterIsInstance<TextButton>()?.forEachIndexed { i, tile -> tile.isChecked = draft.dpiText.trim() == DPI_PRESETS[i] }
        val cm360 = draft.currentCm360(ctx.profiles)
        updateNumber(cm360)
        updateFit(cm360)
    }

    private fun updateNumber(cm360: Double?) {
        val number = liveNumber ?: return
        number.clearActions()
        val format: (Double) -> String = { "%.1f".format(Locale.ROOT, it) }
        when {
            cm360 == null -> number.setText("--")
            !counted -> Motion.countUp(number, cm360 * COUNT_FROM, cm360, ui.tokens.motionSlow * 2, format = format)
            else -> number.setText(format(cm360))
        }
        if (cm360 != null) counted = true
    }

    private fun updateFit(cm360: Double?) {
        val holder = fitHolder ?: return
        holder.clearChildren()
        val pad = draft.padSizeCm()
        if (cm360 == null || pad == null) return
        val width = SetupDraft.formatNumber(pad.first)
        val fit = FitCheckPanel(ui, cm360, pad.first, t("onboarding.fit.turn", "%.1f".format(Locale.ROOT, cm360)), t("onboarding.fit.pad", width))
        holder.add(fit).growX().row()
        val message = if (fit.fits) ui.label(t("onboarding.pad.fits"), Styles.OK, wrap = true) else ui.label(t("onboarding.pad.too_small"), Styles.CAUTION, wrap = true)
        holder.add(message).left().growX().padTop(CAPTION_GAP)
    }

    private companion object {
        const val DPI = "dpi"
        const val SENSITIVITY = "sensitivity"
    }
}
