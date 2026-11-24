package de.yuuto.aimtune.app.screens

import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Button
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.ui.Table
import de.yuuto.aimtune.app.AppContext
import de.yuuto.aimtune.app.setup.SetupDraft
import de.yuuto.aimtune.app.trainerConfigFor
import de.yuuto.aimtune.app.ui.Icon
import de.yuuto.aimtune.app.ui.LED_CELL
import de.yuuto.aimtune.app.ui.LedState
import de.yuuto.aimtune.app.ui.Styles
import de.yuuto.aimtune.app.ui.UiSkin
import de.yuuto.aimtune.app.ui.iconButton
import de.yuuto.aimtune.app.ui.label
import de.yuuto.aimtune.app.ui.led
import de.yuuto.aimtune.app.ui.onChange
import java.util.Locale

private const val PILL_HEIGHT = 48f
private const val PILL_PADDING = 18f
private const val SEGMENT_GAP = 16f
private const val UNIT_GAP = 6f
private const val TICK_HEIGHT = 20f
private const val PILL_TO_BAR = 28f
private const val UTILITY_HEIGHT = 48f
private const val UTILITY_GAP = 4f

/** How much of the shell a screen shows. Screens inside a run hide everything that would leave it. */
enum class Chrome { FULL, MINIMAL }

/** The tools of the utility bar; a screen names its own so the bar marks it. */
enum class Utility { HISTORY, CROSSHAIR, SETTINGS }

/**
 * The right side of the top bar: the setup pill (game, DPI, sensitivity and cm/360° at a glance; a
 * click edits them) and the utility bar. [active] is the tool the current screen belongs to.
 */
internal fun shellBar(ctx: AppContext, ui: UiSkin, active: Utility?, onSetup: () -> Unit, t: (String) -> String): Table {
    val bar = Table()
    bar.add(setupPill(ctx, ui, onSetup, t)).height(PILL_HEIGHT).padRight(PILL_TO_BAR)
    fun tool(utility: Utility, icon: Icon, key: String, open: () -> Unit) {
        val button = ui.iconButton(t(key), icon) { if (utility != active) open() }
        button.isChecked = utility == active
        bar.add(button).height(UTILITY_HEIGHT).padLeft(UTILITY_GAP)
    }
    tool(Utility.HISTORY, Icon.HISTORY, "menu.history") { ctx.showHistory() }
    tool(Utility.CROSSHAIR, Icon.CROSSHAIR, "menu.crosshair") { ctx.showCrosshair() }
    tool(Utility.SETTINGS, Icon.SLIDERS, "menu.settings") { ctx.showSettings() }
    return bar
}

/** The setup as a recessed readout; a click opens the setup editor. */
private fun setupPill(ctx: AppContext, ui: UiSkin, onSetup: () -> Unit, t: (String) -> String): Button {
    val pill = Button(ui.skin, Styles.WELL_BUTTON)
    pill.pad(0f, PILL_PADDING, 0f, PILL_PADDING)
    // A fixed seed: the pill only reads the setup and must not use up the seeds of the next rounds.
    val config = trainerConfigFor(ctx.settings.current, ctx.profiles, 0L)
    if (config == null) {
        pill.add(ui.led(LedState.CAUTION)).size(LED_CELL)
        pill.add(ui.label(t("menu.not_set_up"), Styles.BODY)).padLeft(UNIT_GAP)
    } else {
        val draft = SetupDraft.fromSettings(ctx.settings.current)
        pill.add(ui.led(LedState.OK)).size(LED_CELL)
        pill.add(ui.label(config.profile.name, Styles.BODY)).padLeft(UNIT_GAP)
        segment(pill, ui, SetupDraft.formatNumber(config.dpi), t("menu.stat_dpi"))
        segment(pill, ui, draft.sensitivity?.let(config.profile::formatSensitivity) ?: "-", null)
        segment(pill, ui, "%.1f".format(Locale.ROOT, config.cm360), "cm/360°")
    }
    pill.children.forEach { it.touchable = Touchable.disabled }
    pill.onChange(onSetup)
    return pill
}

private fun segment(pill: Button, ui: UiSkin, value: String, unit: String?) {
    val tick = Image(ui.whiteSolid).also { it.color.set(ui.tokens.divider) }
    pill.add(tick).width(1f).height(TICK_HEIGHT).padLeft(SEGMENT_GAP).padRight(SEGMENT_GAP)
    pill.add(ui.label(value, Styles.MONO))
    if (unit != null) pill.add(ui.label(unit, Styles.CAPTION_MUTED)).padLeft(UNIT_GAP)
}
