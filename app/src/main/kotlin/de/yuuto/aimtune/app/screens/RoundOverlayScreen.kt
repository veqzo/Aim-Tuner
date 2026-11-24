package de.yuuto.aimtune.app.screens

import com.badlogic.gdx.Input
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.scenes.scene2d.ui.Widget
import com.badlogic.gdx.scenes.scene2d.utils.Drawable
import de.yuuto.aimtune.app.AppContext
import de.yuuto.aimtune.app.RoundCountdown
import de.yuuto.aimtune.app.ui.Motion
import de.yuuto.aimtune.app.ui.Styles
import de.yuuto.aimtune.app.ui.addButton
import de.yuuto.aimtune.app.ui.button
import de.yuuto.aimtune.app.ui.caps
import de.yuuto.aimtune.app.ui.label

private const val CARD_WIDTH = 1000f
private const val CARD_PADDING = 40f
private const val SECTION_GAP = 28f
private const val ACTION_GAP = 16f
private const val PRIMARY_MIN_WIDTH = 240f
private const val BAR_HEIGHT = 6f
private const val COUNTDOWN_NUMBER_WIDTH = 72f
private const val COUNTDOWN_SECONDS = 3f

/** How dark the frozen room behind the card is. */
private const val BACKDROP_DIM = 0.55f

/**
 * What comes between two rounds of a run (blind test, curve calibration, search and test, refine): a
 * compact card over the trainer room of the last round, dimmed and vignetted, so the player stays in
 * the room. From the second round of a phase on a short countdown starts the next round by itself;
 * Space pauses it, Enter or the primary button go on at once, Esc stops the run.
 */
abstract class RoundOverlayScreen(ctx: AppContext) : UiScreen(ctx) {
    /** Small line above the heading: the phase of the run. */
    protected abstract val phaseName: String

    protected abstract val heading: String

    /** Rounds already played in this phase; the countdown runs only from the second round on. */
    protected abstract val roundsDone: Int

    protected abstract val primaryText: String

    protected abstract val stopText: String

    /** What the run shows between the heading and the countdown, at most [width] wide. */
    protected abstract fun body(width: Float): Actor

    /** Extra actions next to the primary one (finish a stage now, analyse now). */
    protected open fun extraActions(): List<TextButton> = emptyList()

    /** False while the player should decide or answer something first; read every frame. */
    protected open val countdownWanted: Boolean get() = true

    /** Plays the next round; with [autoStart] it starts without a click. */
    protected abstract fun next(autoStart: Boolean)

    protected abstract fun stopRun()

    /** Offered once per screen; gone for good when the run asks the player to decide first. */
    private var countdown: RoundCountdown? = null
    private var countdownOffered = false
    private var frozenForTour = false
    private var entered = false

    private var secondsLabel: Label? = null
    private var drainBar: DrainBar? = null

    override fun build(): Actor {
        val inner = CARD_WIDTH - 2 * CARD_PADDING
        val card = Table()
        card.background(ui.skin.getDrawable(Styles.MODULE))
        card.pad(CARD_PADDING)
        card.add(ui.caps(phaseName)).left().row()
        card.add(ui.label(heading, Styles.HEADING)).left().padTop(4f).padBottom(SECTION_GAP).row()
        card.add(body(inner)).left().width(inner).row()
        countdown?.let { card.add(countdownStrip(it, inner)).left().width(inner).padTop(SECTION_GAP).row() }
        card.add(actions()).growX().padTop(SECTION_GAP)

        val root = Table()
        root.setFillParent(true)
        root.add(card).width(CARD_WIDTH)
        // Only the first build fades in; a rebuild (pause, analysis arrived) must not flash the card.
        if (!entered) Motion.fadeIn(card, ctx.ui.tokens.motionBase)
        entered = true
        return root
    }

    private fun actions(): Table {
        val row = Table()
        row.addButton(ui.button(stopText, Styles.SECONDARY) { stopRun() })
        row.add().expandX()
        extraActions().forEach { row.addButton(it).padRight(ACTION_GAP) }
        row.addButton(ui.button(primaryText, Styles.PRIMARY) { next(autoStart = false) }, minWidth = PRIMARY_MIN_WIDTH)
        return row
    }

    private fun countdownStrip(countdown: RoundCountdown, width: Float): Table {
        val strip = Table()
        val seconds = ui.label(if (countdown.paused) "–" else countdown.shownSeconds.toString(), Styles.READOUT)
        val caption = if (countdown.paused) t("overlay.paused") else t("overlay.countdown")
        val toggle = ui.button(if (countdown.paused) t("overlay.resume") else t("overlay.pause"), Styles.GHOST) { toggleCountdown() }
        strip.add(seconds).width(COUNTDOWN_NUMBER_WIDTH).left()
        strip.add(ui.label(caption, Styles.BODY_MUTED, wrap = true)).growX().left().padLeft(ACTION_GAP)
        strip.add(toggle).right().padLeft(ACTION_GAP).row()
        val bar = DrainBar(ui.stepOn, ui.stepOff).also { it.fraction = countdown.fraction }
        strip.add(bar).colspan(3).width(width).height(BAR_HEIGHT).padTop(12f)
        secondsLabel = seconds
        drainBar = bar
        return strip
    }

    private fun toggleCountdown() {
        countdown?.toggle() ?: return
        rebuild()
    }

    override fun onEnter() = next(autoStart = false)

    override fun onEscape() = stopRun()

    override fun onKey(keycode: Int): Boolean {
        if (keycode != Input.Keys.SPACE || countdown == null) return false
        toggleCountdown()
        return true
    }

    /** The player left the window: the next round must not start without them. */
    override fun onWindowFocusLost() {
        val running = countdown?.takeIf { !it.paused } ?: return
        running.pause()
        rebuild()
    }

    /** Screenshot tour: the countdown shown and held so the capture never starts a round. */
    internal fun freezeCountdownForTour() {
        frozenForTour = true
    }

    /** Called every frame before the countdown is advanced; screens that wait for a background result check it here. */
    protected open fun beforeFrame() {}

    override fun render(delta: Float) {
        beforeFrame()
        if (updateCountdown(delta)) {
            next(autoStart = true)
            return
        }
        super.render(delta)
    }

    /** True on the frame the countdown ends. */
    private fun updateCountdown(delta: Float): Boolean {
        val wanted = roundsDone > 0 && countdownWanted
        val current = countdown
        if (current == null) {
            if (wanted && !countdownOffered) {
                countdownOffered = true
                countdown = RoundCountdown(COUNTDOWN_SECONDS)
                rebuild()
            }
            return false
        }
        if (!wanted) {
            countdown = null
            rebuild()
            return false
        }
        if (frozenForTour) return false
        val shown = current.shownSeconds
        if (current.tick(delta)) return true
        if (current.shownSeconds != shown) secondsLabel?.setText(current.shownSeconds.toString())
        drainBar?.fraction = current.fraction
        return false
    }

    override fun drawBackground() = ctx.roundBackdrop.draw(BACKDROP_DIM)

    /** A thin bar whose filled part shrinks with the countdown; drawn directly, so it can change every frame. */
    private class DrainBar(private val filled: Drawable, private val empty: Drawable) : Widget() {
        var fraction = 1f

        override fun draw(batch: Batch, parentAlpha: Float) {
            batch.setColor(color.r, color.g, color.b, color.a * parentAlpha)
            empty.draw(batch, x, y, width, height)
            val part = width * fraction.coerceIn(0f, 1f)
            // A pill narrower than its height has no room for its round ends.
            if (part >= height) filled.draw(batch, x, y, part, height)
        }
    }
}
