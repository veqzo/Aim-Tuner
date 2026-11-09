package de.yuuto.aimtune.app.ui

import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.InputListener
import com.badlogic.gdx.scenes.scene2d.ui.Container
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.utils.Layout
import com.badlogic.gdx.utils.Align

private const val MODULE_PADDING = 40f
private const val HEAD_GAP = 32f
private const val LED_TEXT_GAP = 4f
private const val STATUS_GAP = 28f
private const val FLIP_BUTTON_HEIGHT = 44f

/** The text and LED in a module head: what state the module is in, said in words (the LED only repeats it). */
data class ModuleStatus(val state: LedState, val text: String)

/**
 * A rack module: a raised face with a head (name, status LED, flip key) and a body with two sides.
 * The front holds the main task, the back the advanced settings of it. [flip] turns the module
 * around its vertical axis and swaps the side at the edge, so the eye sees one surface with two
 * faces, not a new screen. Sides are built on demand; [flipped] is the side shown first, so the
 * screen can keep it across rebuilds through [onFlipped].
 */
class RackModule(
    private val ui: UiSkin,
    title: String,
    status: ModuleStatus,
    flipLabels: Pair<String, String>,
    private val front: () -> Actor,
    private val back: () -> Actor,
    flipped: Boolean = false,
    private val onFlipped: (Boolean) -> Unit = {},
) : Table() {
    var flipped = flipped
        private set
    private val body = Container<Actor>().fill().align(Align.topLeft)

    /** The height the front asks for; the back gets the same height and scrolls when it is taller. */
    private val frontHeight: Float by lazy { front().let { (it as? Layout)?.prefHeight ?: it.height } }
    private val flipButton = ui.iconButton(flipLabel(flipLabels), Icon.FLIP) { flip() }
    private val labels = flipLabels

    init {
        isTransform = true
        background(ui.skin.getDrawable(Styles.MODULE))
        pad(MODULE_PADDING)
        val head = Table()
        head.add(ui.label(title, Styles.TITLE)).left()
        head.add(ui.led(status.state)).size(LED_CELL).padLeft(STATUS_GAP)
        head.add(ui.label(status.text, Styles.CAPTION_MUTED)).left().padLeft(LED_TEXT_GAP)
        head.add().expandX()
        head.add(flipButton).height(FLIP_BUTTON_HEIGHT)
        add(head).growX().padBottom(HEAD_GAP).row()
        add(body).grow()
        body.actor = side()
    }

    /** Turns the module to its other side. */
    fun flip() {
        Motion.flip(this, ui.tokens.motionSlow) {
            flipped = !flipped
            body.actor = side()
            flipButton.setText(flipLabel(labels))
            onFlipped(flipped)
        }
    }

    private fun side(): Actor = if (flipped) scrolling(back()) else front()

    /** The back in a scroll pane that asks only for the height of the front, so a flip never changes the module size. */
    private fun scrolling(content: Actor): Actor {
        val scroll = object : ScrollPane(content, ui.skin, Styles.FRAME_SCROLL) {
            override fun getPrefHeight() = frontHeight
            override fun getMinHeight() = 0f
        }
        scroll.setScrollingDisabled(true, false)
        scroll.setOverscroll(false, false)
        scroll.setFlickScroll(false)
        scroll.setFadeScrollBars(false)
        scroll.setScrollbarsOnTop(true)
        // The wheel goes to the back while the pointer is over it, and back to the page scroll when it leaves.
        scroll.addListener(object : InputListener() {
            override fun enter(event: InputEvent, x: Float, y: Float, pointer: Int, fromActor: Actor?) {
                scroll.stage?.scrollFocus = scroll
            }

            override fun exit(event: InputEvent, x: Float, y: Float, pointer: Int, toActor: Actor?) {
                if (toActor != null && toActor.isDescendantOf(scroll)) return
                scroll.stage?.scrollFocus = generateSequence(scroll.parent) { it.parent }.firstOrNull { it is ScrollPane }
            }
        })
        return scroll
    }

    private fun flipLabel(labels: Pair<String, String>) = if (flipped) labels.second else labels.first
}
