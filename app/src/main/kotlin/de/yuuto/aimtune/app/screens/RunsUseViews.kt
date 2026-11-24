package de.yuuto.aimtune.app.screens

import com.badlogic.gdx.scenes.scene2d.ui.Table
import de.yuuto.aimtune.app.AppContext
import de.yuuto.aimtune.app.accel.RunsSource
import de.yuuto.aimtune.app.accel.RunsUse
import de.yuuto.aimtune.app.setup.SetupDraft
import de.yuuto.aimtune.app.ui.Styles
import de.yuuto.aimtune.app.ui.button
import de.yuuto.aimtune.app.ui.caps
import de.yuuto.aimtune.app.ui.label
import java.util.Locale

private const val STAT_GAP = 40f
private const val LINK_HEIGHT = 48f
private const val ROW_GAP = 12f

/** The text under the values: where the sensitivity of the runs comes from. */
private fun AppContext.sourceText(use: RunsUse): String = when (val source = use.source) {
    RunsSource.Settings -> i18n.t("accel.baseline_game")
    is RunsSource.Finder -> i18n.t("accel.baseline_finder", source.rounds, source.confidencePercent)
    RunsSource.Running -> i18n.t("hub.runs.source_running")
}

/** Opens the setup panel over the screen that shows the values. */
private fun AppContext.link() =
    ui.skin.button(i18n.t("hub.runs.change"), Styles.TEXT) { (currentScreen as? UiScreen)?.openSetup() }

/**
 * The same information as two short lines for the flow screens: the values, then the source with the
 * setup link on the right. The link shares the second line so the first one fits a narrow card in
 * every language.
 */
fun AppContext.runsUseLine(use: RunsUse?, width: Float): Table {
    val skin = ui.skin
    val line = Table()
    line.add(skin.caps(i18n.t("hub.runs.title"))).left().padRight(STAT_GAP / 2)
    val values = use?.let { i18n.t("hub.runs.values", it.gameName, it.sensitivityText, "%.1f".format(Locale.ROOT, it.cm360), SetupDraft.formatNumber(it.dpi)) }
    line.add(skin.label(values ?: i18n.t("accel.baseline_none"), Styles.MONO)).left().expandX().row()
    val below = Table()
    if (use != null) below.add(skin.label(sourceText(use), Styles.CAPTION_MUTED, wrap = true)).left().growX() else below.add().expandX()
    below.add(link()).right().height(LINK_HEIGHT).padLeft(STAT_GAP / 2)
    line.add(below).left().width(width).colspan(2).padTop(4f)
    return line
}

/** The same information stacked for a narrow column (the CURVE back): title, values, source, then the setup link. */
fun AppContext.runsUseBlock(use: RunsUse?, width: Float): Table {
    val skin = ui.skin
    val block = Table().left()
    block.add(skin.caps(i18n.t("hub.runs.title"))).left().row()
    val values = use?.let { i18n.t("hub.runs.values", it.gameName, it.sensitivityText, "%.1f".format(Locale.ROOT, it.cm360), SetupDraft.formatNumber(it.dpi)) }
    block.add(skin.label(values ?: i18n.t("accel.baseline_none"), Styles.MONO, wrap = true)).left().width(width).padTop(ROW_GAP / 2).row()
    if (use != null) block.add(skin.label(sourceText(use), Styles.CAPTION_MUTED, wrap = true)).left().width(width).padTop(4f).row()
    block.add(link().also { it.pad(0f) }).left().height(LINK_HEIGHT)
    return block
}
