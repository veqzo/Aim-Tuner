package de.yuuto.aimtune.app.ui

import com.badlogic.gdx.scenes.scene2d.ui.Table

private const val MORE_GAP = 6f

/**
 * A note in one sentence with "Learn more" under it; the click opens the full text below, with a
 * short fade, and the link turns into "Show less". [expanded] is the state to start in, so a
 * screen can keep it across rebuilds through [onToggle].
 */
class Disclosure(
    private val ui: UiSkin,
    short: String,
    private val more: String,
    private val labels: Pair<String, String>,
    private val width: Float,
    expanded: Boolean = false,
    private val onToggle: (Boolean) -> Unit = {},
) : Table() {
    private var expanded = expanded
    private val details = Table()
    private val link = ui.button(linkText(), Styles.TEXT) { toggle() }

    init {
        add(ui.label(short, Styles.CAPTION_MUTED, wrap = true)).left().width(width).row()
        add(details).left().row()
        link.pad(0f)
        add(link).left().padTop(MORE_GAP)
        fill()
    }

    private fun fill() {
        details.clearChildren()
        if (!expanded) return
        val text = ui.label(more, Styles.CAPTION_MUTED, wrap = true)
        details.add(text).left().width(width).padTop(MORE_GAP)
        Motion.fadeIn(text, ui.tokens.motionBase)
    }

    private fun toggle() {
        expanded = !expanded
        link.setText(linkText())
        fill()
        onToggle(expanded)
    }

    private fun linkText() = if (expanded) labels.second else labels.first
}
