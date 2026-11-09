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

private const val CARD_PADDING = 28f
private const val STAT_GAP = 40f
private const val LINK_HEIGHT = 48f
private const val ROW_GAP = 12f

/** The text under the values: where the sensitivity of the runs comes from. */
private fun AppContext.sourceText(use: RunsUse): String = when (val source = use.source) {
    RunsSource.Settings -> i18n.t("accel.baseline_game")
    is RunsSource.Finder -> i18n.t("accel.baseline_finder", source.rounds, source.confidencePercent)
    RunsSource.Running -> i18n.t("hub.runs.source_running")
}

private fun AppContext.link(returnTo: () -> Unit) =
    ui.skin.button(i18n.t("hub.runs.change"), Styles.TEXT) { showSettings(returnTo) }

/**
 * The "Runs use" card: game, game sensitivity, cm/360° and DPI that every Raw Accel run is played at, where they come from,
 * and the link to the settings (which return to [returnTo] on Save and Back). [use] null means the setup is not finished.
 * [width] is the card's outer width.
 */
fun AppContext.runsUseCard(use: RunsUse?, width: Float, returnTo: () -> Unit): Table {
    val skin = ui.skin
    val card = Table()
    card.background(skin.skin.getDrawable(if (use == null) Styles.CARD_CAUTION else Styles.CARD))
    card.pad(CARD_PADDING)
    val inner = width - 2 * CARD_PADDING
    card.add(skin.caps(i18n.t("hub.runs.title"))).left()
    card.add().expandX()
    card.add(link(returnTo)).right().height(LINK_HEIGHT).row()
    if (use == null) {
        card.add(skin.label(i18n.t("accel.baseline_none"), Styles.TITLE)).left().colspan(3).padTop(ROW_GAP).row()
        card.add(skin.label(i18n.t("accel.baseline_none_help"), Styles.CAPTION_MUTED, wrap = true)).left().width(inner).colspan(3).padTop(4f)
        return card
    }
    val stats = Table()
    listOf(
        i18n.t("menu.stat_game") to use.gameName,
        i18n.t("menu.stat_sens") to use.sensitivityText,
        "cm/360°" to "%.1f".format(Locale.ROOT, use.cm360),
        i18n.t("menu.stat_dpi") to SetupDraft.formatNumber(use.dpi),
    ).forEach { (name, value) ->
        val stat = Table()
        stat.add(skin.label(name, Styles.CAPTION_MUTED)).left().row()
        stat.add(skin.label(value, Styles.MONO)).left().padTop(4f)
        stats.add(stat).left().padRight(STAT_GAP)
    }
    card.add(stats).left().colspan(3).padTop(ROW_GAP).row()
    card.add(skin.label(sourceText(use), Styles.CAPTION_MUTED, wrap = true)).left().width(inner).colspan(3).padTop(ROW_GAP)
    return card
}

/** The same information as one short line for the flow screens: values, the source below, and the settings link on the right. */
fun AppContext.runsUseLine(use: RunsUse?, width: Float, returnTo: () -> Unit): Table {
    val skin = ui.skin
    val line = Table()
    line.add(skin.caps(i18n.t("hub.runs.title"))).left().padRight(STAT_GAP / 2)
    val values = use?.let { i18n.t("hub.runs.values", it.gameName, it.sensitivityText, "%.1f".format(Locale.ROOT, it.cm360), SetupDraft.formatNumber(it.dpi)) }
    line.add(skin.label(values ?: i18n.t("accel.baseline_none"), Styles.MONO)).left()
    line.add().expandX()
    line.add(link(returnTo)).right().height(LINK_HEIGHT).row()
    if (use != null) line.add(skin.label(sourceText(use), Styles.CAPTION_MUTED, wrap = true)).left().width(width).colspan(4).padTop(4f)
    return line
}

/** The same information stacked for a narrow column (the CURVE back): title, values, source, then the settings link. */
fun AppContext.runsUseBlock(use: RunsUse?, width: Float, returnTo: () -> Unit): Table {
    val skin = ui.skin
    val block = Table().left()
    block.add(skin.caps(i18n.t("hub.runs.title"))).left().row()
    val values = use?.let { i18n.t("hub.runs.values", it.gameName, it.sensitivityText, "%.1f".format(Locale.ROOT, it.cm360), SetupDraft.formatNumber(it.dpi)) }
    block.add(skin.label(values ?: i18n.t("accel.baseline_none"), Styles.MONO, wrap = true)).left().width(width).padTop(ROW_GAP / 2).row()
    if (use != null) block.add(skin.label(sourceText(use), Styles.CAPTION_MUTED, wrap = true)).left().width(width).padTop(4f).row()
    block.add(link(returnTo).also { it.pad(0f) }).left().height(LINK_HEIGHT)
    return block
}
