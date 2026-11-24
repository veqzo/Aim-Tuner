package dev.veqzo.aimtune.app.screens

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox
import com.badlogic.gdx.scenes.scene2d.ui.Slider
import com.badlogic.gdx.scenes.scene2d.ui.Stack
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.scenes.scene2d.ui.TextField
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener
import dev.veqzo.aimtune.app.AppContext
import dev.veqzo.aimtune.app.ui.CrosshairPreview
import dev.veqzo.aimtune.app.ui.Icon
import dev.veqzo.aimtune.app.ui.LedState
import dev.veqzo.aimtune.app.ui.ModuleStatus
import dev.veqzo.aimtune.app.ui.PreviewBackground
import dev.veqzo.aimtune.app.ui.Styles
import dev.veqzo.aimtune.app.ui.addButton
import dev.veqzo.aimtune.app.ui.button
import dev.veqzo.aimtune.app.ui.caps
import dev.veqzo.aimtune.app.ui.iconButton
import dev.veqzo.aimtune.app.ui.label
import dev.veqzo.aimtune.app.ui.modulePanel
import dev.veqzo.aimtune.app.ui.onChange
import dev.veqzo.aimtune.core.crosshair.Crosshair
import dev.veqzo.aimtune.core.crosshair.CrosshairCode
import dev.veqzo.aimtune.core.crosshair.CrosshairCodeException
import dev.veqzo.aimtune.core.crosshair.CrosshairColor
import dev.veqzo.aimtune.core.crosshair.CrosshairLines
import dev.veqzo.aimtune.storage.AppSettings
import dev.veqzo.aimtune.storage.MAX_CROSSHAIR_NAME
import dev.veqzo.aimtune.storage.SavedCrosshair
import dev.veqzo.aimtune.storage.activeSavedCrosshair
import dev.veqzo.aimtune.storage.withCrosshairCode
import dev.veqzo.aimtune.storage.withCrosshairDeleted
import dev.veqzo.aimtune.storage.withCrosshairDuplicated
import dev.veqzo.aimtune.storage.withCrosshairRenamed
import dev.veqzo.aimtune.storage.withCrosshairSelected
import dev.veqzo.aimtune.storage.withNewCrosshair
import java.util.Locale

private const val COLUMN_GAP = 40f
private const val PROFILE_WIDTH = 460f
private const val GROUP_WIDTH = 520f
private const val GROUP_HEAD_HEIGHT = 44f
private const val CONTROL_HEIGHT = 48f
private const val ROW_GAP = 10f
private const val BLOCK_GAP = 22f
private const val LABEL_WIDTH = 120f
private const val SLIDER_WIDTH = 190f
private const val VALUE_WIDTH = 64f
private const val VALUE_GAP = 12f
private const val COMPACT_PADDING = 10f
private const val TILE_WIDTH = 96f
private const val SWATCH = 44f
private const val SWATCH_FRAME = 6f
private const val PREVIEW_MIN_HEIGHT = 420f
private const val WELL_PADDING = 24f
private const val FOOTER_BUTTON_HEIGHT = 64f
private const val BUTTON_WIDTH = 140f
private const val CODE_BUTTON_WIDTH = 225f

/**
 * Crosshair editor: named profiles, the options of Valorant's crosshair settings with their ranges, a live preview in
 * screen pixels, import and export of Valorant profile codes (docs/CROSSHAIR.md). Edits change a working copy of
 * the settings; Save keeps them, Back and Esc drop them. The selected profile is the one all scenarios draw.
 * One opened module: profiles and code on the left, the big preview in the centre, the options as collapsible groups on the right.
 */
class CrosshairScreen(ctx: AppContext) : UiScreen(ctx) {
    override val utility = Utility.CROSSHAIR

    private var working: AppSettings = ctx.settings.current.let { if (it.crosshairs.isEmpty()) it.withNewCrosshair(t("crosshair.default_name", 1), Crosshair().toCode().format()) else it }
    private var code: CrosshairCode = CrosshairCode.parse(selected().code)
    private var crosshair: Crosshair = Crosshair.fromCode(code)
    private var background = PreviewBackground.TRAINER
    private var message: Pair<String, Boolean>? = null
    private var codeField: TextField? = null
    private var preview: CrosshairPreview? = null

    /** The one option group shown open, so the column fits without scrolling; the inner lines are changed most. */
    private var openGroup: Group? = Group.INNER

    private enum class Group(val key: String) { COLOR("crosshair.color"), OUTLINES("crosshair.outlines"), DOT("crosshair.dot"), INNER("crosshair.inner"), OUTER("crosshair.outer") }

    private fun selected(): SavedCrosshair = checkNotNull(working.activeSavedCrosshair())

    override fun build(): Actor {
        preview?.dispose()
        val body = Table()
        body.add(profileColumn()).top().left().width(PROFILE_WIDTH)
        body.add(previewColumn()).grow().padLeft(COLUMN_GAP).padRight(COLUMN_GAP)
        body.add(groupColumn()).top().right().width(GROUP_WIDTH)
        val panel = ui.modulePanel(t("crosshair.title"), ModuleStatus(LedState.OK, selected().name))
        panel.add(body).grow()
        val content = Table()
        content.add(panel).grow()
        return frame(content, footer())
    }

    override fun onEscape() = ctx.showMenu()

    override fun dispose() {
        preview?.dispose()
        super.dispose()
    }

    private fun footer(): Table {
        val footer = Table()
        footer.add(ui.button(t("common.back"), Styles.TEXT) { ctx.showMenu() }).left().height(FOOTER_BUTTON_HEIGHT).padLeft(8f)
        footer.add().expandX()
        message?.let { (text, ok) -> footer.add(ui.label(text, if (ok) Styles.OK else Styles.CAUTION)).right().padRight(32f) }
        footer.addButton(ui.button(t("settings.save"), Styles.PRIMARY) { save() }, minWidth = 180f)
        return footer
    }

    private fun save() {
        val error = ctx.settings.save(working)
        if (error == null) ctx.showMenu() else say(error, false)
    }

    private fun say(text: String, ok: Boolean) {
        message = text to ok
        rebuild()
    }

    // ---- edits -------------------------------------------------------------------------------------------------

    /** Applies [change] to the crosshair, writes it into the code of the selected profile and refreshes the code field. */
    private fun edit(structural: Boolean = false, change: (Crosshair) -> Crosshair) {
        crosshair = change(crosshair)
        code = crosshair.applyTo(code)
        working = working.withCrosshairCode(selected().id, code.format())
        message = null
        if (structural) rebuild() else codeField?.setText(code.format())
    }

    private fun load(settings: AppSettings) {
        working = settings
        code = CrosshairCode.parse(selected().code)
        crosshair = Crosshair.fromCode(code)
    }

    // ---- profiles and code -------------------------------------------------------------------------------------

    private fun profileColumn(): Table {
        val column = Table()
        column.add(ui.caps(t("crosshair.profiles"))).left().padBottom(ROW_GAP).row()
        column.add(profileSelect()).left().width(PROFILE_WIDTH).height(CONTROL_HEIGHT).padBottom(ROW_GAP).row()
        column.add(nameField()).left().width(PROFILE_WIDTH).height(CONTROL_HEIGHT).padBottom(ROW_GAP).row()
        val actions = Table()
        actions.add(ui.button(t("crosshair.new"), Styles.SECONDARY) { newProfile() }).height(CONTROL_HEIGHT).width(BUTTON_WIDTH).padRight(ROW_GAP)
        actions.add(ui.button(t("crosshair.duplicate"), Styles.SECONDARY) { load(working.withCrosshairDuplicated(selected().id)); rebuild() }).height(CONTROL_HEIGHT).width(BUTTON_WIDTH).padRight(ROW_GAP)
        actions.add(ui.button(t("crosshair.delete"), Styles.SECONDARY) { deleteProfile() }).height(CONTROL_HEIGHT).width(BUTTON_WIDTH)
        column.add(actions).left().padBottom(BLOCK_GAP).row()

        column.add(ui.caps(t("crosshair.code"))).left().padBottom(ROW_GAP).row()
        val field = TextField(code.format(), ui.skin, Styles.INPUT)
        field.maxLength = MAX_CODE_CHARS
        codeField = field
        column.add(field).left().width(PROFILE_WIDTH).height(CONTROL_HEIGHT).padBottom(ROW_GAP).row()
        val codeActions = Table()
        codeActions.add(compact(ui.button(t("crosshair.apply"), Styles.SECONDARY) { applyCode(asNew = false) })).height(CONTROL_HEIGHT).width(CODE_BUTTON_WIDTH).padRight(ROW_GAP)
        codeActions.add(compact(ui.button(t("crosshair.copy"), Styles.SECONDARY) { copyCode() })).height(CONTROL_HEIGHT).width(CODE_BUTTON_WIDTH).row()
        codeActions.add(compact(ui.button(t("crosshair.import_new"), Styles.SECONDARY) { applyCode(asNew = true) })).height(CONTROL_HEIGHT).width(2 * CODE_BUTTON_WIDTH + ROW_GAP).colspan(2).padTop(ROW_GAP)
        column.add(codeActions).left().row()
        return column
    }

    /** The preview fills the centre in a recessed panel, with its backgrounds above it and the error note under it. */
    private fun previewColumn(): Table {
        val column = Table()
        val head = Table()
        head.add(ui.caps(t("crosshair.preview"))).left().expandX()
        head.add(backgroundButtons()).right()
        column.add(head).growX().padBottom(ROW_GAP).row()
        val well = Table()
        well.background(ui.skin.getDrawable(Styles.WELL))
        well.pad(WELL_PADDING)
        val actor = CrosshairPreview({ crosshair }, { background })
        preview = actor
        well.add(actor).grow()
        column.add(well).grow().minHeight(PREVIEW_MIN_HEIGHT).row()
        column.add(ui.label(t("crosshair.error_help"), Styles.CAPTION_MUTED, wrap = true)).growX().padTop(ROW_GAP)
        return column
    }

    /** The options as groups with a chevron head each; opening one closes the others. */
    private fun groupColumn(): Table {
        val column = Table().top()
        Group.entries.forEach { group ->
            val open = group == openGroup
            val head = ui.iconButton(t(group.key), if (open) Icon.CHEVRON_DOWN else Icon.CHEVRON_RIGHT) {
                openGroup = if (open) null else group
                rebuild()
            }
            column.add(head).left().height(GROUP_HEAD_HEIGHT).row()
            if (open) column.add(groupBody(group)).left().padLeft(ROW_GAP).padBottom(BLOCK_GAP).row()
        }
        return column
    }

    private fun groupBody(group: Group): Table = when (group) {
        Group.COLOR -> colorGroup()
        Group.OUTLINES -> outlinesGroup()
        Group.DOT -> dotGroup()
        Group.INNER -> linesGroup(inner = true)
        Group.OUTER -> linesGroup(inner = false)
    }

    private fun profileSelect(): SelectBox<String> {
        val select = SelectBox<String>(ui.skin)
        // Names can repeat; the list shows them with the position so that the selection is unambiguous.
        val labels = working.crosshairs.mapIndexed { i, c -> "${i + 1}  ${c.name}" }
        select.setItems(*labels.toTypedArray())
        select.selected = labels[working.crosshairs.indexOfFirst { it.id == selected().id }]
        select.onChange {
            val index = labels.indexOf(select.selected)
            if (index >= 0 && working.crosshairs[index].id != selected().id) {
                load(working.withCrosshairSelected(working.crosshairs[index].id))
                message = null
                rebuild()
            }
        }
        return select
    }

    private fun nameField(): TextField {
        val field = TextField(selected().name, ui.skin, Styles.INPUT)
        field.maxLength = MAX_CROSSHAIR_NAME
        field.messageText = t("crosshair.name")
        field.onChange {
            working = working.withCrosshairRenamed(selected().id, field.text.ifBlank { selected().name })
            message = null
        }
        return field
    }

    private fun newProfile() {
        val next = working.crosshairs.size + 1
        load(working.withNewCrosshair(t("crosshair.default_name", next), Crosshair().toCode().format()))
        rebuild()
    }

    private fun deleteProfile() {
        val remaining = working.withCrosshairDeleted(selected().id)
        load(if (remaining.crosshairs.isEmpty()) remaining.withNewCrosshair(t("crosshair.default_name", 1), Crosshair().toCode().format()) else remaining)
        rebuild()
    }

    /** Reads the code field: replaces the selected profile's code, or adds a new profile for it. Invalid codes leave everything as it is. */
    private fun applyCode(asNew: Boolean) {
        val text = codeField?.text.orEmpty()
        try {
            val parsed = CrosshairCode.parse(text)
            Crosshair.fromCode(parsed)
            val next = if (asNew) working.withNewCrosshair(t("crosshair.imported_name", working.crosshairs.size + 1), parsed.format()) else working.withCrosshairCode(selected().id, parsed.format())
            load(next)
            say(t(if (asNew) "crosshair.imported" else "crosshair.applied"), true)
        } catch (e: CrosshairCodeException) {
            say(e.message ?: t("crosshair.invalid"), false)
        }
    }

    private fun copyCode() {
        Gdx.app.clipboard.contents = code.format()
        say(t("crosshair.copied"), true)
    }

    private fun backgroundButtons(): Table {
        val table = Table()
        listOf(PreviewBackground.LIGHT to "crosshair.bg_light", PreviewBackground.DARK to "crosshair.bg_dark", PreviewBackground.TRAINER to "crosshair.bg_trainer").forEachIndexed { index, (kind, key) ->
            val button: TextButton = ui.button(t(key), Styles.TILE) { background = kind; rebuild() }
            button.setProgrammaticChangeEvents(false)
            button.isChecked = background == kind
            table.add(button).width(TILE_WIDTH + 20f).height(CONTROL_HEIGHT).padLeft(if (index == 0) 0f else ROW_GAP)
        }
        return table
    }

    // ---- crosshair options -------------------------------------------------------------------------------------

    private fun colorGroup(): Table {
        val column = Table()
        column.add(swatches()).left().padBottom(ROW_GAP).row()
        column.add(customColor()).left().row()
        return column
    }

    private fun outlinesGroup(): Table {
        val column = Table()
        column.add(onOff(crosshair.outlines) { v -> edit(structural = true) { it.copy(outlines = v) } }).left().row()
        if (crosshair.outlines) {
            column.add(slider(t("crosshair.thickness"), 1f, 6f, 1f, crosshair.outlineThickness.toFloat(), { crosshair.outlineThickness.toString() }) { v -> edit { it.copy(outlineThickness = v.toInt()) } }).left().padTop(ROW_GAP).row()
            column.add(slider(t("crosshair.opacity"), 0f, 1f, 0.05f, crosshair.outlineOpacity.toFloat(), { percent(crosshair.outlineOpacity) }) { v -> edit { it.copy(outlineOpacity = v.toDouble()) } }).left().padTop(ROW_GAP).row()
        }
        return column
    }

    private fun dotGroup(): Table {
        val column = Table()
        column.add(onOff(crosshair.dot) { v -> edit(structural = true) { it.copy(dot = v) } }).left().row()
        if (crosshair.dot) {
            column.add(slider(t("crosshair.size"), 1f, 6f, 1f, crosshair.dotThickness.toFloat(), { crosshair.dotThickness.toString() }) { v -> edit { it.copy(dotThickness = v.toInt()) } }).left().padTop(ROW_GAP).row()
            column.add(slider(t("crosshair.opacity"), 0f, 1f, 0.05f, crosshair.dotOpacity.toFloat(), { percent(crosshair.dotOpacity) }) { v -> edit { it.copy(dotOpacity = v.toDouble()) } }).left().padTop(ROW_GAP).row()
        }
        return column
    }

    private fun swatches(): Table {
        val table = Table()
        CrosshairColor.PRESETS.forEachIndexed { index, rgb ->
            val active = crosshair.color.customHex == null && crosshair.color.preset == index
            val frame = Image(ui.whiteSolid).also { it.color = if (active) ui.tokens.accent else ui.tokens.neutral(700) }
            val fill = Image(ui.whiteSolid).also { it.color = Color(rgb shl 8 or 0xFF) }
            val stack = Stack(frame, Table().also { it.add(fill).size(SWATCH - 2 * SWATCH_FRAME + if (active) 0f else 4f) })
            stack.addListener(object : ClickListener() {
                override fun clicked(event: InputEvent, x: Float, y: Float) = edit(structural = true) { it.copy(color = CrosshairColor(preset = index)) }
            })
            table.add(stack).size(SWATCH).padRight(if (index == CrosshairColor.PRESETS.lastIndex) 0f else 6f)
        }
        return table
    }

    private fun customColor(): Table {
        val table = Table()
        val field = TextField(crosshair.color.customHex.orEmpty(), ui.skin, Styles.INPUT)
        field.maxLength = HEX_LENGTH
        field.messageText = t("crosshair.hex")
        field.setTextFieldFilter { _, c -> c.isDigit() || c.lowercaseChar() in 'a'..'f' }
        field.onChange {
            val text = field.text
            if (text.length == HEX_LENGTH) edit { it.copy(color = CrosshairColor(it.color.preset, text.uppercase())) }
        }
        table.add(ui.label(t("crosshair.custom_color"), Styles.BODY_MUTED)).left().width(LABEL_WIDTH + 30f)
        table.add(field).width(SLIDER_WIDTH).height(CONTROL_HEIGHT)
        val clear = ui.button(t("crosshair.preset"), Styles.TEXT) { edit(structural = true) { it.copy(color = CrosshairColor(it.color.preset)) } }
        if (crosshair.color.customHex != null) table.add(clear).height(CONTROL_HEIGHT).padLeft(ROW_GAP)
        return table
    }

    private fun linesGroup(inner: Boolean): Table {
        val lines = if (inner) crosshair.inner else crosshair.outer
        fun change(structural: Boolean = false, f: (CrosshairLines) -> CrosshairLines) =
            edit(structural) { c -> if (inner) c.copy(inner = f(c.inner)) else c.copy(outer = f(c.outer)) }
        val column = Table()
        column.add(onOff(lines.show) { v -> change(structural = true) { it.copy(show = v) } }).left().padBottom(ROW_GAP).row()
        if (lines.show) {
            column.add(slider(t("crosshair.opacity"), 0f, 1f, 0.05f, lines.opacity.toFloat(), { percent(currentLines(inner).opacity) }) { v -> change { it.copy(opacity = v.toDouble()) } }).left().padBottom(ROW_GAP).row()
            column.add(slider(t("crosshair.length"), 0f, 20f, 1f, lines.length.toFloat(), { currentLines(inner).length.toString() }) { v -> change { it.copy(length = v.toInt()) } }).left().padBottom(ROW_GAP).row()
            column.add(verticalLength(lines, ::change)).left().padBottom(ROW_GAP).row()
            column.add(slider(t("crosshair.thickness"), 1f, 10f, 1f, lines.thickness.toFloat(), { currentLines(inner).thickness.toString() }) { v -> change { it.copy(thickness = v.toInt()) } }).left().padBottom(ROW_GAP).row()
            column.add(slider(t("crosshair.offset"), 0f, 20f, 1f, lines.offset.toFloat(), { currentLines(inner).offset.toString() }) { v -> change { it.copy(offset = v.toInt()) } }).left().padBottom(ROW_GAP).row()
            column.add(switchRow(t("crosshair.movement_error"), lines.movementError) { v -> change(structural = true) { it.copy(movementError = v) } }).left().padBottom(ROW_GAP).row()
            column.add(switchRow(t("crosshair.firing_error"), lines.firingError) { v -> change(structural = true) { it.copy(firingError = v) } }).left().padBottom(ROW_GAP).row()
        }
        return column
    }

    private fun currentLines(inner: Boolean) = if (inner) crosshair.inner else crosshair.outer

    /** A switch for a separate vertical length and, when on, its slider. */
    private fun verticalLength(lines: CrosshairLines, change: (Boolean, (CrosshairLines) -> CrosshairLines) -> Unit): Table {
        val block = Table()
        block.add(switchRow(t("crosshair.vertical_length"), lines.verticalLength != null) { v ->
            change(true) { it.copy(verticalLength = if (v) it.length else null) }
        }).left().row()
        val vertical = lines.verticalLength
        if (vertical != null) {
            block.add(slider(t("crosshair.length_vertical"), 0f, 20f, 1f, vertical.toFloat(), { vertical.toString() }) { v -> change(false) { it.copy(verticalLength = v.toInt()) } }).left().padTop(ROW_GAP).row()
        }
        return block
    }

    // ---- widgets -----------------------------------------------------------------------------------------------

    private fun slider(name: String, min: Float, max: Float, step: Float, value: Float, text: () -> String, onValue: (Float) -> Unit): Table {
        val row = Table()
        val shown: Label = ui.label(text(), Styles.MONO)
        row.add(ui.label(name, Styles.BODY_MUTED)).left().width(LABEL_WIDTH)
        val slider = Slider(min, max, step, false, ui.skin)
        slider.value = value
        slider.onChange {
            onValue(slider.value)
            shown.setText(text())
        }
        row.add(slider).width(SLIDER_WIDTH)
        row.add(shown).right().width(VALUE_WIDTH).padLeft(VALUE_GAP)
        return row
    }

    private fun onOff(selected: Boolean, onPick: (Boolean) -> Unit): Table {
        val table = Table()
        listOf(true to t("settings.on"), false to t("settings.off")).forEach { (on, name) ->
            val button: TextButton = ui.button(name, Styles.TILE) { onPick(on) }
            button.setProgrammaticChangeEvents(false)
            button.isChecked = selected == on
            table.add(button).width(TILE_WIDTH).height(CONTROL_HEIGHT).padRight(ROW_GAP)
        }
        return table
    }

    private fun switchRow(name: String, selected: Boolean, onPick: (Boolean) -> Unit): Table {
        val row = Table()
        row.add(ui.label(name, Styles.BODY_MUTED)).left().width(LABEL_WIDTH + 80f)
        row.add(onOff(selected, onPick))
        return row
    }

    /** Less padding for buttons in a narrow row. */
    private fun compact(button: TextButton): TextButton = button.also { it.pad(0f, COMPACT_PADDING, 0f, COMPACT_PADDING) }

    private fun percent(value: Double) = "%.0f %%".format(Locale.ROOT, value * 100)

    private companion object {
        const val MAX_CODE_CHARS = 2000
        const val HEX_LENGTH = 8
    }
}
