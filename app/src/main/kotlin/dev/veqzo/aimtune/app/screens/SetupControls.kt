package dev.veqzo.aimtune.app.screens

import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.scenes.scene2d.ui.TextField
import dev.veqzo.aimtune.app.ui.Styles
import dev.veqzo.aimtune.app.ui.UiSkin
import dev.veqzo.aimtune.app.ui.button
import dev.veqzo.aimtune.app.ui.onChange

private const val MAX_INPUT_CHARS = 10
private const val TILE_TEXT_PADDING = 4f

// The controls the setup panel and the onboarding share, so both edit a SetupDraft the same way.

/** A field for a positive number; a comma works as decimal separator. [onText] gets every edit. */
internal fun UiSkin.numberField(text: String, style: String = Styles.INPUT, onText: (String) -> Unit): TextField {
    val field = TextField(text, skin, style)
    field.setTextFieldFilter { _, c -> c.isDigit() || c == '.' || c == ',' }
    field.maxLength = MAX_INPUT_CHARS
    field.onChange { onText(field.text) }
    return field
}

/** One latched tile per entry in a row of [width]; the tile of the selected entry is checked. */
internal fun <T> UiSkin.choiceTiles(
    entries: List<T>,
    width: Float,
    height: Float,
    gap: Float,
    selected: (T) -> Boolean,
    name: (T) -> String,
    pick: (T) -> Unit,
): Table {
    val table = Table()
    val tileWidth = (width - gap * (entries.size - 1)) / entries.size
    entries.forEachIndexed { index, entry ->
        val tile: TextButton = button(name(entry), Styles.TILE) { pick(entry) }
        tile.pad(0f, TILE_TEXT_PADDING, 0f, TILE_TEXT_PADDING)
        tile.setProgrammaticChangeEvents(false)
        tile.isChecked = selected(entry)
        table.add(tile).width(tileWidth).height(height).padLeft(if (index == 0) 0f else gap)
    }
    return table
}
