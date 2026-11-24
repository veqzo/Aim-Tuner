package dev.veqzo.aimtune.app.ui

import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.InputListener
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.badlogic.gdx.utils.Align

private const val LOGO_SIZE = 32f
private const val BRAND_GAP = 12f
private const val CONTENT_TOP_PADDING = 64f
private const val BUTTON_HEIGHT = 64f
private const val BUTTON_PADDING = 36f
private const val PROGRESS_HEIGHT = 8f

fun UiSkin.label(text: CharSequence, style: String = Styles.BODY, wrap: Boolean = false): Label =
    Label(text, skin, style).also { it.wrap = wrap }

/** Small sentence-case caption above a field or section (Rack labels are never all caps). */
fun UiSkin.caps(text: String): Label = label(text, Styles.LABEL)

fun UiSkin.button(text: String, style: String, onClick: () -> Unit): TextButton =
    TextButton(text, skin, style).also { button ->
        button.pad(0f, BUTTON_PADDING, 0f, BUTTON_PADDING)
        button.onChange(onClick)
    }

fun Actor.onChange(action: () -> Unit) {
    addListener(object : ChangeListener() {
        override fun changed(event: ChangeEvent, actor: Actor) = action()
    })
}

/** A thin progress bar of the given [width]: the part up to [fraction] filled with the accent color. */
fun UiSkin.progressBar(fraction: Float, width: Float, height: Float = PROGRESS_HEIGHT): Table {
    val bar = Table()
    val filled = (width * fraction).coerceIn(0f, width)
    if (filled > 0f) bar.add(Image(stepOn)).width(filled).height(height)
    if (filled < width) bar.add(Image(stepOff)).width(width - filled).height(height)
    return bar
}

/** Fixed height for buttons in the footer row. */
fun Table.addButton(button: TextButton, minWidth: Float = 0f) =
    add(button).height(BUTTON_HEIGHT).minWidth(minWidth)

/**
 * The common screen layout of the design: a header with logo and brand name (and an optional
 * [headerRight]), the [content] filling the middle, and an optional [footer] row, all inside the
 * 72-unit margins.
 */
fun UiSkin.frame(headerRight: Actor?, content: Actor, footer: Actor?): Table {
    val root = Table()
    root.setFillParent(true)
    root.pad(0f, tokens.margin, 0f, tokens.margin)

    val header = Table()
    header.add(Image(logo)).size(LOGO_SIZE)
    header.add(label("AimTune", Styles.BRAND)).padLeft(BRAND_GAP)
    header.add().expandX()
    if (headerRight != null) header.add(headerRight)
    root.add(header).growX().height(tokens.headerHeight).row()

    if (content is Table) content.align(Align.topLeft)
    // Content taller than the window (small screens, UI scale) scrolls instead of pushing the footer out of view.
    val scroll = ScrollPane(content, skin, Styles.FRAME_SCROLL)
    scroll.setScrollingDisabled(true, false)
    scroll.setOverscroll(false, false)
    scroll.setFlickScroll(false)
    scroll.setFadeScrollBars(false)
    // ScrollPane takes the wheel only after a click on it (ScrollPane.addCaptureListener), so hovering is enough here.
    scroll.addListener(object : InputListener() {
        override fun enter(event: InputEvent, x: Float, y: Float, pointer: Int, fromActor: Actor?) {
            scroll.stage?.scrollFocus = scroll
        }
    })
    root.add(scroll).grow().top().left().padTop(CONTENT_TOP_PADDING).row()
    if (footer != null) root.add(footer).growX().height(tokens.footerHeight)
    return root
}
