package de.yuuto.aimtune.app.ui

import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.ui.Table

internal const val MODULE_PADDING = 40f
internal const val MODULE_HEAD_GAP = 32f
private const val LED_TEXT_GAP = 4f
private const val STATUS_GAP = 28f
private const val HEAD_ACTION_HEIGHT = 44f

/** The text and LED in a module head: what state the module is in, said in words (the LED only repeats it). */
data class ModuleStatus(val state: LedState, val text: String)

/**
 * The head row of a rack module: the name, an optional status (LED and text, or the text alone with
 * [showLed] false) and an optional control on the right edge (flip key, filter).
 */
fun UiSkin.moduleHead(title: String, status: ModuleStatus?, right: Actor? = null, showLed: Boolean = true): Table {
    val head = Table()
    head.add(label(title, Styles.TITLE)).left()
    if (status != null) {
        if (showLed) head.add(led(status.state)).size(LED_CELL).padLeft(STATUS_GAP)
        head.add(label(status.text, Styles.CAPTION_MUTED)).left().padLeft(if (showLed) LED_TEXT_GAP else STATUS_GAP)
    }
    head.add().expandX()
    if (right != null) head.add(right).height(HEAD_ACTION_HEIGHT)
    return head
}

/**
 * A rack module with one side: the raised face with its head, for screens that open a module to its
 * full size (results, history). The caller adds the body below the head.
 */
fun UiSkin.modulePanel(title: String, status: ModuleStatus?, right: Actor? = null, showLed: Boolean = true): Table {
    val panel = Table().top().left()
    panel.background(skin.getDrawable(Styles.MODULE))
    panel.pad(MODULE_PADDING)
    panel.add(moduleHead(title, status, right, showLed)).growX().padBottom(MODULE_HEAD_GAP).row()
    return panel
}
