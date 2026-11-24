package dev.veqzo.aimtune.app.ui

import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton

private const val CHIP_HEIGHT = 40f
private const val CHIP_GAP = 8f
private const val CHIP_PADDING = 16f
private const val CHIP_LED_GAP = 2f
private const val DETAIL_GAP = 12f
private const val ROW_GAP = 20f
private const val BUTTON_HEIGHT = 72f

/** One check at the start button: its state in words, and what a click does ([onClick] null: the chip only informs). */
data class CheckChip(val text: String, val state: LedState, val onClick: (() -> Unit)? = null)

/** A small rounded key with a status LED in front of its text. */
class ChipButton(text: String, state: LedState, ui: UiSkin) : TextButton(text, ui.skin, Styles.CHIP) {
    init {
        clearChildren()
        add(ui.led(state)).size(LED_CELL)
        add(label).padLeft(CHIP_LED_GAP)
        pad(0f, CHIP_PADDING / 2, 0f, CHIP_PADDING)
        children.forEach { it.touchable = Touchable.disabled }
    }
}

/**
 * The main action of a module: the checks that matter before it, as chips in one row, an optional
 * [detail] (normally a [Disclosure]) that says what the open check means, and the amber button. The checks never block
 * the button; a click on a chip confirms or re-reads it. Only a run that cannot work without a check (the curve runs need a
 * plain mouse) passes [enabled] false until it is given.
 */
fun UiSkin.primaryAction(chips: List<CheckChip>, detail: Actor?, text: String, width: Float, enabled: Boolean = true, onStart: () -> Unit): Table {
    val table = Table()
    if (chips.isNotEmpty()) table.add(chipRows(chips, width)).left().row()
    if (detail != null) table.add(detail).left().width(width).padTop(DETAIL_GAP).row()
    val start = button(text, Styles.PRIMARY) { if (enabled) onStart() }
    start.isDisabled = !enabled
    table.add(start).left().width(width).height(BUTTON_HEIGHT).padTop(if (chips.isEmpty() && detail == null) 0f else ROW_GAP)
    return table
}

/** The chips in rows no wider than [width]: a long language (German) wraps to a second row instead of widening the module. */
private fun UiSkin.chipRows(chips: List<CheckChip>, width: Float): Table {
    val rows = Table()
    var row = Table()
    var used = 0f
    chips.forEach { chip ->
        val button = ChipButton(chip.text, chip.state, this)
        val action = chip.onClick
        if (action == null) button.touchable = Touchable.disabled else button.onChange(action)
        val chipWidth = button.prefWidth + CHIP_GAP
        if (used > 0f && used + chipWidth - CHIP_GAP > width) {
            rows.add(row).left().padBottom(CHIP_GAP).row()
            row = Table()
            used = 0f
        }
        row.add(button).height(CHIP_HEIGHT).padRight(CHIP_GAP)
        used += chipWidth
    }
    rows.add(row).left()
    return rows
}
