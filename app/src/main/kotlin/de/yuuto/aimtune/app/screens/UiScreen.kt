package de.yuuto.aimtune.app.screens

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.Screen
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.glutils.FrameBuffer
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.InputListener
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.utils.viewport.ScreenViewport
import de.yuuto.aimtune.app.AppContext
import de.yuuto.aimtune.app.setup.SetupDraft
import de.yuuto.aimtune.app.ui.UiSkin
import de.yuuto.aimtune.app.ui.frame
import kotlin.math.min

private const val MAX_FRAME_SECONDS = 1f / 30f

/**
 * Base of the Scene2D screens. The stage works in design units (1080 high, see docs/DESIGN.md)
 * whatever the window size; [build] creates the widgets and is called again when the skin is
 * rebuilt for a new window size, so screens must keep their state outside the widgets.
 */
abstract class UiScreen(protected val ctx: AppContext) : Screen {
    protected val stage = Stage(ScreenViewport())
    protected val ui: UiSkin get() = ctx.ui.skin

    /** A text of the UI in the language of the settings. */
    protected fun t(key: String, vararg args: Any?): String = ctx.i18n.t(key, *args)

    /** Creates the screen's widget tree (normally via [de.yuuto.aimtune.app.ui.frame]). */
    protected abstract fun build(): Actor

    /** The utility-bar tool this screen belongs to, so the bar marks it; null for the other screens. */
    protected open val utility: Utility? = null

    /**
     * The screen layout of the shell: top bar, scrolling [content], optional [footer]. With [Chrome.MINIMAL]
     * (inside a run) the top bar shows only the brand and [headerRight], so nothing there leaves the run.
     */
    protected fun frame(content: Actor, footer: Actor?, chrome: Chrome = Chrome.FULL, headerRight: Actor? = null): Table {
        val right = if (chrome == Chrome.FULL) shellBar(ctx, ui, utility, ::openSetup) { t(it) } else headerRight
        return ui.frame(right, content, footer)
    }

    protected open fun onEscape() {}

    protected open fun onEnter() {}

    /** Extra keyboard shortcuts; return true if [keycode] was used. */
    protected open fun onKey(keycode: Int): Boolean = false

    /** Called when the window loses focus. */
    open fun onWindowFocusLost() {}

    /** The setup panel over this screen while it is open; it lives outside the widgets so it survives a rebuild. */
    private var setupEditor: SetupEditor? = null

    /** Opens the setup panel from the pill; without a setup yet, the onboarding is the better place. */
    protected fun openSetup() {
        if (!ctx.isSetUp) {
            ctx.showOnboarding(afterFinish = ctx::showMenu)
            return
        }
        setupEditor = SetupEditor(SetupDraft.fromSettings(ctx.settings.current))
        rebuild()
    }

    /** Screenshot tour: the setup panel over this screen. */
    internal fun openSetupForTour() = openSetup()

    private fun closeSetup() {
        stage.unfocusAll()
        setupEditor = null
        rebuild()
    }

    private fun saveSetup() {
        val editor = setupEditor ?: return
        val problem = SetupPanel.problem(ctx, editor.draft) { t(it) }
        if (problem != null) {
            editor.message = problem
            rebuild()
            return
        }
        val error = ctx.settings.save(editor.draft.toSettings(ctx.settings.current, ctx.profiles))
        if (error != null) {
            editor.message = error
            rebuild()
            return
        }
        stage.unfocusAll()
        setupEditor = null
        onSetupSaved()
    }

    /** Called after the setup panel saved new values; screens that show the setup rebuild or reload. */
    protected open fun onSetupSaved() = rebuild()

    fun rebuild() {
        stage.clear()
        stage.addActor(build())
        setupEditor?.let { editor ->
            stage.addActor(SetupPanel(ctx, ui, editor, { key, args -> t(key, *args) }, ::rebuild, ::saveSetup, ::closeSetup).overlay())
        }
    }

    override fun show() {
        stage.addCaptureListener(object : InputListener() {
            override fun keyDown(event: InputEvent, keycode: Int): Boolean {
                val panelOpen = setupEditor != null
                val used = when (keycode) {
                    Input.Keys.ESCAPE -> true.also { if (panelOpen) closeSetup() else onEscape() }
                    Input.Keys.ENTER, Input.Keys.NUMPAD_ENTER -> true.also { if (panelOpen) saveSetup() else onEnter() }
                    // Typing in the panel must not trigger the screen's shortcuts.
                    else -> if (panelOpen) false else onKey(keycode)
                }
                if (used) event.stop()
                return used
            }
        })
        ctx.gate.setAllowed(false)
        updateViewport()
        rebuild()
        Gdx.input.inputProcessor = stage
    }

    override fun render(delta: Float) {
        clear()
        stage.act(min(delta, MAX_FRAME_SECONDS))
        stage.draw()
    }

    private fun clear() {
        val bg = ui.tokens.bg
        Gdx.gl.glClearColor(bg.r, bg.g, bg.b, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
    }

    /**
     * Draws the screen as it is now into a new frame buffer of the window's size, for the screen
     * transition; the caller owns it. Only draws, so it is safe to call from inside the stage's act.
     */
    fun snapshot(): FrameBuffer {
        val fbo = FrameBuffer(Pixmap.Format.RGBA8888, Gdx.graphics.backBufferWidth, Gdx.graphics.backBufferHeight, false)
        fbo.begin()
        clear()
        stage.draw()
        // Blending leaves alpha below 1 under translucent widgets; the snapshot must be opaque.
        Gdx.gl.glColorMask(false, false, false, true)
        Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
        Gdx.gl.glColorMask(true, true, true, true)
        fbo.end()
        return fbo
    }

    override fun resize(width: Int, height: Int) = updateViewport()

    private fun updateViewport() {
        if (Gdx.graphics.height == 0) return
        (stage.viewport as ScreenViewport).unitsPerPixel = ui.tokens.screenHeight / Gdx.graphics.height
        stage.viewport.update(Gdx.graphics.width, Gdx.graphics.height, true)
    }

    override fun pause() = Unit

    override fun resume() = Unit

    override fun hide() {
        stage.unfocusAll()
        if (Gdx.input.inputProcessor === stage) Gdx.input.inputProcessor = null
    }

    override fun dispose() = stage.dispose()
}
