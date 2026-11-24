package dev.veqzo.aimtune.app.ui

import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.ui.TextButton

private const val ICON_GAP = 10f
private const val ICON_PADDING = 14f

/**
 * A text button with a line icon in front. The icon takes the text's current color, so hover,
 * pressed and checked states tint both. An empty [text] gives an icon-only button.
 */
class IconButton(text: String, icon: Icon, ui: UiSkin, style: String, iconSize: Float = UiSkin.ICON_SIZE) :
    TextButton(text, ui.skin, style) {
    private val image = Image(ui.icon(icon, iconSize))

    init {
        clearChildren()
        add(image).size(iconSize)
        if (text.isNotEmpty()) add(label).padLeft(ICON_GAP)
        pad(0f, ICON_PADDING, 0f, ICON_PADDING)
    }

    override fun draw(batch: Batch, parentAlpha: Float) {
        fontColor?.let(image.color::set)
        super.draw(batch, parentAlpha)
    }
}

/** An [IconButton] that runs [onClick] when pressed. */
fun UiSkin.iconButton(text: String, icon: Icon, style: String = Styles.GHOST, onClick: () -> Unit): IconButton =
    IconButton(text, icon, this, style).also { it.onChange(onClick) }
