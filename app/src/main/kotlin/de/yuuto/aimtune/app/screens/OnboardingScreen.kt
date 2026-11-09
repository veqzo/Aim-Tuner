package de.yuuto.aimtune.app.screens

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.ui.Button
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.scenes.scene2d.ui.TextField
import com.badlogic.gdx.utils.Align
import de.yuuto.aimtune.app.AppContext
import de.yuuto.aimtune.app.setup.GAME_CUSTOM
import de.yuuto.aimtune.app.setup.PadPreset
import de.yuuto.aimtune.app.setup.SetupDraft
import de.yuuto.aimtune.app.setup.SetupStep
import de.yuuto.aimtune.app.ui.FitCheckPanel
import de.yuuto.aimtune.app.ui.Styles
import de.yuuto.aimtune.app.ui.addButton
import de.yuuto.aimtune.app.ui.button
import de.yuuto.aimtune.app.ui.caps
import de.yuuto.aimtune.app.ui.frame
import de.yuuto.aimtune.app.ui.label
import de.yuuto.aimtune.app.ui.onChange
import de.yuuto.aimtune.core.score.FlashImportance
import de.yuuto.aimtune.core.units.AimStyle
import de.yuuto.aimtune.core.units.SearchRangeTable
import java.util.Locale

private const val TITLE_GAP = 24f
private const val DESCRIPTION_WIDTH = 760f
private const val SECTION_GAP = 40f
private const val CAPTION_GAP = 12f
private const val FIELD_WIDTH_LG = 760f
private const val FIELD_HEIGHT_LG = 88f
private const val PRESET_WIDTH = 120f
private const val PRESET_HEIGHT = 64f
private const val TILE_WIDTH = 280f
private const val TILE_GAP = 16f
private const val TILES_PER_ROW = 3
private const val RIGHT_COLUMN_GAP = 140f
private const val INFO_CARD_WIDTH = 560f
private const val INFO_CARD_PADDING = 28f
private const val STYLE_CARD_WIDTH = 380f
private const val STYLE_CARD_HEIGHT = 220f
private const val STYLE_CARD_PADDING = 28f
private const val PAD_ROW_WIDTH = 760f
private const val PAD_ROW_HEIGHT = 64f
private const val PAD_ROW_PADDING = 24f
private const val SMALL_FIELD_WIDTH = 260f
private const val SMALL_FIELD_HEIGHT = 56f
private const val FIT_PANEL_WIDTH = 640f
private const val FIT_PANEL_HEIGHT = 300f
private const val STEP_BAR_WIDTH = 56f
private const val STEP_BAR_HEIGHT = 6f
private const val STEP_BAR_GAP = 8f
private const val ACCENT_RULE_WIDTH = 2f
private const val CURRENT_SETTING_HEIGHT = 300f
private const val RIGHT_BLOCK_PADDING = 40f
private const val FORMULA = "360 × 2.54 ÷ (DPI × sens × yaw)"
private val DPI_PRESETS = listOf("400", "800", "1600", "3200")

/**
 * First-launch setup in four steps (also reachable later from settings): DPI, game and current
 * sensitivity, aim style, mousepad. All values live in a [SetupDraft]; nothing is saved until the
 * last step is finished.
 */
class OnboardingScreen(
    ctx: AppContext,
    private var draft: SetupDraft,
    private var step: SetupStep,
    private val afterFinish: () -> Unit,
) : UiScreen(ctx) {
    private var continueButton: TextButton? = null
    private var liveCm: Label? = null
    private var sensitivityHint: Label? = null
    private var issueLabel: Label? = null
    private var presetButtons: List<TextButton> = emptyList()
    private var fitHolder: Table? = null
    private var edited = false
    private var saveError: String? = null

    override fun build(): Actor {
        val content = when (step) {
            SetupStep.DPI -> dpiStep()
            SetupStep.GAME -> gameStep()
            SetupStep.STYLE -> styleStep()
            SetupStep.PAD -> padStep()
        }
        val root = frame(content, footer(), Chrome.MINIMAL, stepIndicator())
        refresh()
        return root
    }

    override fun onEscape() = back()

    override fun onEnter() = next()

    // ---- navigation -------------------------------------------------------------------------

    private fun back() {
        if (step.ordinal == 0) {
            ctx.showMenu()
            return
        }
        step = SetupStep.entries[step.ordinal - 1]
        resetStepState()
        rebuild()
    }

    private fun next() {
        if (draft.issue(step, ctx.profiles) != null) return
        if (step == SetupStep.entries.last()) {
            finish()
        } else {
            step = SetupStep.entries[step.ordinal + 1]
            resetStepState()
            rebuild()
        }
    }

    private fun finish() {
        val error = ctx.settings.save(draft.toSettings(ctx.settings.current, ctx.profiles))
        if (error == null) {
            afterFinish()
        } else {
            saveError = error
            rebuild()
        }
    }

    private fun resetStepState() {
        edited = false
        saveError = null
    }

    /** Re-evaluates what the current input allows: Continue state, live distance and messages. */
    private fun refresh() {
        val issue = draft.issue(step, ctx.profiles)
        continueButton?.isDisabled = issue != null
        liveCm?.setText(draft.currentCm360(ctx.profiles)?.let { "%.1f".format(Locale.ROOT, it) } ?: "-")
        sensitivityHint?.setText(sensitivityHintText())
        issueLabel?.setText(if (issue != null && edited) t(issue.key) else saveError.orEmpty())
        presetButtons.forEachIndexed { i, button ->
            button.setProgrammaticChangeEvents(false)
            button.isChecked = draft.dpi == DPI_PRESETS[i].toDouble()
        }
    }

    // ---- shared pieces ----------------------------------------------------------------------

    private fun stepIndicator(): Table {
        val table = Table()
        table.add(ui.label(t("onboarding.step", step.ordinal + 1, SetupStep.entries.size), Styles.MONO_MUTED)).padRight(16f)
        SetupStep.entries.forEach { s ->
            val segment = Image(if (s.ordinal <= step.ordinal) ui.stepOn else ui.stepOff)
            table.add(segment).width(STEP_BAR_WIDTH).height(STEP_BAR_HEIGHT).padLeft(STEP_BAR_GAP)
        }
        return table
    }

    private fun footer(): Table {
        val footer = Table()
        footer.add(ui.button(t("common.back"), Styles.TEXT) { back() }).left().height(PRESET_HEIGHT).padLeft(8f)
        footer.add().expandX()
        issueLabel = ui.label("", Styles.CAUTION).also { footer.add(it).right().padRight(32f) }
        val continueButton = ui.button(if (step == SetupStep.entries.last()) t("onboarding.finish") else t("common.continue"), Styles.PRIMARY) { next() }
        this.continueButton = continueButton
        footer.addButton(continueButton, minWidth = 220f)
        return footer
    }

    private fun title(text: String, wrapWidth: Float = DESCRIPTION_WIDTH): Label = ui.label(text, Styles.HEADING, wrap = true).also { it.width = wrapWidth }

    private fun numberField(text: String, style: String, onText: (String) -> Unit): TextField {
        val field = TextField(text, ui.skin, style)
        field.setTextFieldFilter { _, c -> c.isDigit() || c == '.' || c == ',' }
        field.maxLength = MAX_INPUT_CHARS
        field.onChange {
            edited = true
            onText(field.text)
            refresh()
        }
        return field
    }

    private fun focusLater(field: TextField) {
        Gdx.app.postRunnable {
            stage.keyboardFocus = field
            field.setCursorPosition(field.text.length)
        }
    }

    private fun tile(text: String, checked: Boolean, onClick: () -> Unit): TextButton {
        val button = ui.button(text, Styles.TILE, onClick)
        button.setProgrammaticChangeEvents(false)
        button.isChecked = checked
        return button
    }

    // ---- step 1: DPI ------------------------------------------------------------------------

    private fun dpiStep(): Table {
        val left = Table()
        left.add(title(t("onboarding.dpi.title"))).left().width(DESCRIPTION_WIDTH).padBottom(TITLE_GAP).row()
        left.add(ui.label(t("onboarding.dpi.description"), Styles.BODY_MUTED, wrap = true))
            .left().width(DESCRIPTION_WIDTH).padBottom(SECTION_GAP).row()
        left.add(ui.caps("DPI")).left().padBottom(CAPTION_GAP).row()
        val field = numberField(draft.dpiText, Styles.INPUT_LG) { draft = draft.copy(dpiText = it) }
        left.add(field).left().width(FIELD_WIDTH_LG).height(FIELD_HEIGHT_LG).padBottom(TILE_GAP + 8f).row()
        val presets = Table()
        presetButtons = DPI_PRESETS.map { preset ->
            tile(preset, draft.dpiText == preset) {
                draft = draft.copy(dpiText = preset)
                field.text = preset
                field.setCursorPosition(preset.length)
                edited = true
                refresh()
            }
        }
        presetButtons.forEach { presets.add(it).width(PRESET_WIDTH).height(PRESET_HEIGHT).padRight(TILE_GAP) }
        left.add(presets).left()
        focusLater(field)

        val info = Table()
        info.background(ui.skin.getDrawable(Styles.CARD))
        info.pad(INFO_CARD_PADDING)
        info.add(ui.label(t("onboarding.dpi.help_title"), Styles.BODY_LG)).left().padBottom(TILE_GAP).row()
        info.add(ui.label(t("onboarding.dpi.help_text"), Styles.BODY_MUTED, wrap = true))
            .width(INFO_CARD_WIDTH - 2 * INFO_CARD_PADDING).left()

        val content = Table()
        content.add(left).top().left()
        content.add(info).top().left().padLeft(RIGHT_COLUMN_GAP).padTop(RIGHT_BLOCK_PADDING).width(INFO_CARD_WIDTH)
        return content
    }

    // ---- step 2: game and sensitivity -------------------------------------------------------

    private fun gameStep(): Table {
        val left = Table()
        left.add(title(t("onboarding.game.title"), wrapWidth = 640f)).left().width(640f).padBottom(SECTION_GAP).row()
        left.add(ui.caps(t("onboarding.game.game"))).left().padBottom(CAPTION_GAP).row()
        left.add(gameTiles()).left().padBottom(SECTION_GAP).row()
        if (draft.gameId == GAME_CUSTOM) left.add(customFields()).left().padBottom(SECTION_GAP).row()
        left.add(ui.caps(t("onboarding.game.sensitivity"))).left().padBottom(CAPTION_GAP).row()
        val field = numberField(draft.sensitivityText, Styles.INPUT_LG) { draft = draft.copy(sensitivityText = it) }
        left.add(field).left().width(340f).height(FIELD_HEIGHT_LG).padBottom(CAPTION_GAP).row()
        sensitivityHint = ui.label("", Styles.MONO_MUTED).also { left.add(it).left() }

        val right = Table()
        right.add(Image(ui.whiteSolid).also { it.color = ui.tokens.accent(700) }).width(ACCENT_RULE_WIDTH).height(CURRENT_SETTING_HEIGHT).top().padRight(RIGHT_BLOCK_PADDING)
        val block = Table()
        block.add(ui.caps(t("onboarding.game.current"))).left().padBottom(CAPTION_GAP).row()
        liveCm = ui.label("-", Styles.NUMBER_XL).also { block.add(it).left().row() }
        block.add(ui.label("cm / 360°", Styles.NUMBER_MD)).left().padBottom(TILE_GAP).row()
        block.add(ui.label(t("onboarding.game.current_help"), Styles.BODY_MUTED, wrap = true)).left().width(560f).padBottom(8f).row()
        block.add(ui.label(FORMULA, Styles.MONO_MUTED)).left()
        right.add(block).top().left()

        val content = Table()
        content.add(left).top().left()
        content.add(right).top().left().padLeft(RIGHT_COLUMN_GAP / 2).padTop(RIGHT_BLOCK_PADDING)
        if (draft.gameId == null || draft.dpi == null) Unit else focusLater(field)
        return content
    }

    private fun gameTiles(): Table {
        val grid = Table()
        val options = ctx.profiles.profiles.map { it.id to it.name } + (GAME_CUSTOM to t("onboarding.game.custom"))
        options.forEachIndexed { index, (id, name) ->
            val tile = tile(name, draft.gameId == id) {
                draft = draft.copy(gameId = id)
                rebuild()
            }
            grid.add(tile).width(TILE_WIDTH).height(PRESET_HEIGHT).padRight(TILE_GAP).padBottom(TILE_GAP)
            if ((index + 1) % TILES_PER_ROW == 0) grid.row()
        }
        return grid
    }

    private fun customFields(): Table {
        val table = Table()
        table.add(ui.caps(t("onboarding.game.custom_yaw"))).left().padBottom(CAPTION_GAP).padRight(TILE_GAP * 2)
        table.add(ui.caps(t("onboarding.game.custom_fov"))).left().padBottom(CAPTION_GAP).row()
        table.add(numberField(draft.customYawText, Styles.INPUT) { draft = draft.copy(customYawText = it) }).left().width(SMALL_FIELD_WIDTH).height(SMALL_FIELD_HEIGHT).padRight(TILE_GAP * 2)
        table.add(numberField(draft.customFovText, Styles.INPUT) { draft = draft.copy(customFovText = it) }).left().width(SMALL_FIELD_WIDTH).height(SMALL_FIELD_HEIGHT)
        return table
    }

    private fun sensitivityHintText(): String {
        val profile = draft.profile(ctx.profiles)
        val yaw = profile?.let { t("onboarding.game.hint_yaw", SetupDraft.formatNumber(it.yawDegreesPerCount)) } ?: t("onboarding.game.hint_yaw", "-")
        val dpi = draft.dpi?.let { " " + t("onboarding.game.hint_dpi", SetupDraft.formatNumber(it)) }.orEmpty()
        return yaw + dpi
    }

    // ---- step 3: aim style ------------------------------------------------------------------

    private fun styleStep(): Table {
        val content = Table()
        content.add(title(t("onboarding.style.title"))).left().width(DESCRIPTION_WIDTH).padBottom(TITLE_GAP).row()
        content.add(ui.label(t("onboarding.style.description"), Styles.BODY_MUTED, wrap = true))
            .left().width(DESCRIPTION_WIDTH).padBottom(SECTION_GAP).row()
        val cards = Table()
        listOf(AimStyle.WRIST to t("onboarding.style.wrist"), AimStyle.MIXED to t("onboarding.style.mixed"), AimStyle.ARM to t("onboarding.style.arm")).forEach { (style, name) ->
            cards.add(styleCard(style, name)).width(STYLE_CARD_WIDTH).height(STYLE_CARD_HEIGHT).padRight(TILE_GAP * 2).top()
        }
        content.add(cards).left().padBottom(SECTION_GAP).row()
        content.add(ui.label(t("onboarding.flash.title"), Styles.TITLE)).left().padBottom(TILE_GAP / 2).row()
        content.add(ui.label(t("onboarding.flash.description"), Styles.BODY_MUTED, wrap = true)).left().width(DESCRIPTION_WIDTH).padBottom(TILE_GAP).row()
        val labels = FlashImportance.entries.associateWith { t("flash.importance.${it.name.lowercase()}") }
        content.add(ui.flashImportanceButtons(labels, draft.flashImportance, onPick = { draft = draft.copy(flashImportance = it); rebuild() })).left()
        return content
    }

    private fun styleCard(style: AimStyle, name: String): Button {
        val (low, high) = SearchRangeTable.DEFAULT.ranges.getValue(style)
        val description = when (style) {
            AimStyle.WRIST -> t("onboarding.style.wrist_desc")
            AimStyle.MIXED -> t("onboarding.style.mixed_desc")
            AimStyle.ARM -> t("onboarding.style.arm_desc")
        } + " " + t("onboarding.style.typical", low.toInt(), high.toInt())
        val card = Button(ui.skin, Styles.CARD_BUTTON)
        card.pad(STYLE_CARD_PADDING)
        card.align(Align.topLeft)
        card.add(ui.label(name, Styles.TITLE)).left().top().padBottom(TILE_GAP).row()
        card.add(ui.label(description, Styles.BODY_MUTED, wrap = true)).left().top().growX()
        card.children.forEach { it.touchable = com.badlogic.gdx.scenes.scene2d.Touchable.disabled }
        card.setProgrammaticChangeEvents(false)
        card.isChecked = draft.aimStyle == style
        card.onChange {
            draft = draft.copy(aimStyle = style)
            rebuild()
        }
        return card
    }

    // ---- step 4: mousepad -------------------------------------------------------------------

    private fun padStep(): Table {
        val left = Table()
        left.add(title(t("onboarding.pad.title"))).left().width(DESCRIPTION_WIDTH).padBottom(TITLE_GAP).row()
        left.add(ui.label(t("onboarding.pad.description"), Styles.BODY_MUTED, wrap = true))
            .left().width(DESCRIPTION_WIDTH).padBottom(SECTION_GAP).row()
        PadPreset.entries.forEach { preset ->
            left.add(padRow(preset)).left().width(PAD_ROW_WIDTH).height(PAD_ROW_HEIGHT).padBottom(TILE_GAP / 2).row()
            if (preset == PadPreset.CUSTOM && draft.padPreset == PadPreset.CUSTOM) left.add(customPadFields()).left().padTop(TILE_GAP / 2).row()
        }

        val right = Table()
        right.add(ui.caps(t("onboarding.pad.fit_check"))).left().padBottom(CAPTION_GAP).row()
        val holder = Table()
        fitHolder = holder
        right.add(holder).left().width(FIT_PANEL_WIDTH).height(FIT_PANEL_HEIGHT)
        fillFitCheck()

        val content = Table()
        content.add(left).top().left()
        content.add(right).top().left().padLeft(RIGHT_COLUMN_GAP).padTop(TITLE_GAP * 5)
        return content
    }

    private fun padRow(preset: PadPreset): Button {
        val row = Button(ui.skin, Styles.CARD_BUTTON)
        val name = when (preset) {
            PadPreset.SMALL -> t("onboarding.pad.small")
            PadPreset.MEDIUM -> t("onboarding.pad.medium")
            PadPreset.LARGE -> t("onboarding.pad.large")
            PadPreset.DESK_MAT -> t("onboarding.pad.desk_mat")
            PadPreset.CUSTOM -> t("onboarding.pad.custom")
        }
        val dims = if (preset.widthCm != null) {
            "≈ ${SetupDraft.formatNumber(preset.widthCm)} × ${SetupDraft.formatNumber(checkNotNull(preset.heightCm))} cm"
        } else {
            t("onboarding.pad.dimensions")
        }
        row.add(ui.label(name, Styles.BODY_LG)).left().padLeft(PAD_ROW_PADDING).expandX()
        row.add(ui.label(dims, Styles.MONO_MUTED)).right().padRight(PAD_ROW_PADDING)
        row.children.forEach { it.touchable = com.badlogic.gdx.scenes.scene2d.Touchable.disabled }
        row.setProgrammaticChangeEvents(false)
        row.isChecked = draft.padPreset == preset
        row.onChange {
            draft = draft.copy(padPreset = preset)
            rebuild()
        }
        return row
    }

    private fun customPadFields(): Table {
        val table = Table()
        table.add(numberField(draft.customPadWidthText, Styles.INPUT) { draft = draft.copy(customPadWidthText = it); fillFitCheck() })
            .width(SMALL_FIELD_WIDTH).height(SMALL_FIELD_HEIGHT)
        table.add(ui.label("×", Styles.BODY_LG)).padLeft(TILE_GAP).padRight(TILE_GAP)
        table.add(numberField(draft.customPadHeightText, Styles.INPUT) { draft = draft.copy(customPadHeightText = it); fillFitCheck() })
            .width(SMALL_FIELD_WIDTH).height(SMALL_FIELD_HEIGHT)
        table.add(ui.label("cm", Styles.MONO_MUTED)).padLeft(TILE_GAP)
        return table
    }

    private fun fillFitCheck() {
        val holder = fitHolder ?: return
        holder.clearChildren()
        val cm360 = draft.currentCm360(ctx.profiles)
        val pad = draft.padSizeCm()
        if (cm360 == null || pad == null) {
            holder.add(ui.label(t("onboarding.pad.choose"), Styles.BODY_MUTED, wrap = true)).width(FIT_PANEL_WIDTH).top().left()
            return
        }
        val panel = FitCheckPanel(ui, cm360, pad.first)
        holder.add(panel).growX().height(FIT_PANEL_HEIGHT - 80f).row()
        val message = if (panel.fits) {
            ui.label(t("onboarding.pad.fits"), Styles.OK)
        } else {
            ui.label(t("onboarding.pad.too_small"), Styles.CAUTION, wrap = true)
        }
        holder.add(message).left().width(FIT_PANEL_WIDTH).padTop(TILE_GAP)
    }

    private companion object {
        const val MAX_INPUT_CHARS = 10
    }
}
