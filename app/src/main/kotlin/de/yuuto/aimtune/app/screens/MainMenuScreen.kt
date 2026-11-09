package de.yuuto.aimtune.app.screens

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.ui.Table
import de.yuuto.aimtune.app.AppContext
import de.yuuto.aimtune.app.accel.SettingsAnalyses
import de.yuuto.aimtune.app.ui.RackModule
import de.yuuto.aimtune.app.ui.Styles
import de.yuuto.aimtune.app.ui.button
import de.yuuto.aimtune.app.ui.label
import de.yuuto.aimtune.app.ui.stagger
import java.util.concurrent.CompletableFuture

private const val NOTICE_GAP = 12f
private const val FOOTER_BUTTON_HEIGHT = 56f
private const val EXIT_BUTTON_WIDTH = 180f
private const val MODULE_PADDING = 40f
private const val ENTER_STEP = 0.08f

/**
 * The dashboard: two equal rack modules, SENS (find the sensitivity) and CURVE (find or refine a
 * Raw Accel curve). Each front holds the main task, its back the advanced settings of it. The setup
 * is edited in the panel of the setup pill, the tools sit in the utility bar of the top bar.
 */
class MainMenuScreen(ctx: AppContext) : UiScreen(ctx) {
    private val sensState = SensState()
    private val curveState = CurveState()
    private val analysesFuture: CompletableFuture<SettingsAnalyses> = ctx.hub.analyses()

    /** The readout counts up and the modules enter only on the first build, not on every rebuild after a click. */
    private var entered = false
    private var builtTwoColumns = true
    private var sensModule: RackModule? = null
    private var curveModule: RackModule? = null

    private val twoColumns: Boolean get() = HubLayout.twoColumns(stage.viewport.worldWidth)

    override fun build(): Actor {
        builtTwoColumns = twoColumns
        val width = moduleWidth()
        val inner = width - 2 * MODULE_PADDING
        val animate = !entered
        val sens = SensModule(ctx, ui, sensState, inner, animate, ::rebuild)
        val curve = CurveModule(ctx, ui, curveState, inner, analysesFuture) { ctx.showMenu() }
        val sensRack = RackModule(ui, t("dash.sens.title"), sens.status, t("dash.flip.tune") to t("dash.flip.back"), sens::front, sens::back, sensState.flipped) { sensState.flipped = it }
        val curveRack = RackModule(ui, t("dash.curve.title"), curve.status, t("dash.flip.tune") to t("dash.flip.back"), curve::front, curve::back, curveState.flipped) { curveState.flipped = it }
        sensModule = sensRack
        curveModule = curveRack
        val modules: List<Actor> = if (animate) stagger(listOf(sensRack, curveRack), ui.tokens.motionSlow, ENTER_STEP) else listOf(sensRack, curveRack)
        entered = true

        val content = Table().top().left()
        if (twoColumns) {
            content.add(modules[0]).top().left().width(width).fillY().expandY()
            content.add(modules[1]).top().left().width(width).fillY().expandY().padLeft(HubLayout.COLUMN_GAP).row()
        } else {
            content.add(modules[0]).top().left().width(width).row()
            content.add(modules[1]).top().left().width(width).padTop(HubLayout.COLUMN_GAP).row()
        }
        notices()?.let { content.add(it).left().colspan(2).padTop(NOTICE_GAP) }
        return frame(content, footer())
    }

    /** The two modules share the whole room between the margins; the column cap of the hub screens would leave a gap at the right. */
    private fun moduleWidth(): Float {
        val stageWidth = stage.viewport.worldWidth
        if (!twoColumns) return HubLayout.columnWidth(stageWidth, ui.tokens.margin)
        return (stageWidth - 2 * ui.tokens.margin - HubLayout.COLUMN_GAP) / 2
    }

    /** Problems found on start and a newer release, under the modules; null when there is nothing to say. */
    private fun notices(): Table? {
        val update = pendingUpdate()
        if (ctx.startupProblems.isEmpty() && update == null) return null
        val table = Table().left()
        ctx.startupProblems.forEach { table.add(ui.label(it, Styles.CAUTION, wrap = true)).left().growX().row() }
        update?.let { table.add(ui.button(t("update.available", it.version.toString()), Styles.TEXT) { openUpdate() }).left().row() }
        return table
    }

    private fun footer(): Table {
        val footer = Table()
        footer.add(ui.label(t("menu.footer"), Styles.CAPTION_MUTED)).left()
        footer.add().expandX()
        footer.add(ui.button(t("menu.exit"), Styles.SECONDARY) { Gdx.app.exit() }).right().height(FOOTER_BUTTON_HEIGHT).minWidth(EXIT_BUTTON_WIDTH)
        return footer
    }

    override fun render(delta: Float) {
        // The analyses of the stored rounds arrive in the background; the CURVE back shows them once they are there.
        if (curveState.analyses == null && analysesFuture.isDone) {
            curveState.analyses = runCatching { analysesFuture.get() }.getOrNull()
            if (curveState.flipped) rebuild()
        }
        super.render(delta)
    }

    override fun resize(width: Int, height: Int) {
        super.resize(width, height)
        if (twoColumns != builtTwoColumns) rebuild()
    }

    private fun sens() = SensModule(ctx, ui, sensState, 0f, false, ::rebuild)

    override fun onEnter() = sens().start()

    override fun onEscape() = Gdx.app.exit()

    override fun onKey(keycode: Int): Boolean = when (keycode) {
        Input.Keys.A -> true.also { CurveModule(ctx, ui, curveState, 0f, analysesFuture) { ctx.showMenu() }.start() }
        Input.Keys.F -> true.also { sensModule?.flip() }
        Input.Keys.G -> true.also { curveModule?.flip() }
        Input.Keys.M -> sens().toggleRange()
        Input.Keys.S -> true.also { ctx.showSettings() }
        Input.Keys.R -> (ctx.storedRoundCount() > 0).also { if (it) ctx.showStoredResults() }
        Input.Keys.H -> true.also { ctx.showHistory() }
        Input.Keys.C -> true.also { ctx.showCrosshair() }
        Input.Keys.U -> (pendingUpdate() != null).also { if (it) openUpdate() }
        else -> false
    }

    /** Screenshot tour: shows the chosen sides of the modules. */
    internal fun showSidesForTour(sensBack: Boolean, curveBack: Boolean) {
        sensState.flipped = sensBack
        curveState.flipped = curveBack
        rebuild()
    }

    /** A newer release than this build, unless the player switched the check off in the settings. */
    private fun pendingUpdate() = ctx.availableUpdate?.takeIf { ctx.settings.current.checkForUpdates }

    private fun openUpdate() {
        pendingUpdate()?.let { ctx.showUpdate(it) }
    }
}
