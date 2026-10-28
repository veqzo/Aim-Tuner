package de.yuuto.aimtune.app.screens

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Button
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.utils.Align
import de.yuuto.aimtune.app.AppContext
import de.yuuto.aimtune.app.setup.SetupDraft
import de.yuuto.aimtune.app.ui.Styles
import de.yuuto.aimtune.app.ui.button
import de.yuuto.aimtune.app.ui.caps
import de.yuuto.aimtune.app.ui.frame
import de.yuuto.aimtune.app.ui.label
import de.yuuto.aimtune.app.ui.onChange
import java.util.Locale

private const val MENU_WIDTH = 760f
private const val MENU_ITEM_HEIGHT = 72f
private const val MENU_GAP = 8f
private const val ITEM_PADDING = 28f
private const val HEADING_GAP = 56f
private const val COLUMN_GAP = 120f
private const val CARD_PADDING = 36f
private const val CARD_WIDTH = 640f
private const val UNIT_GAP = 16f
private const val UNIT_BASELINE_LIFT = 28f
private const val STAT_GAP = 48f
private const val PROBLEM_GAP = 24f
private const val RIGHT_BLOCK_TOP = 130f
private const val FOOTER_BUTTON_HEIGHT = 64f
private const val EXIT_BUTTON_WIDTH = 200f

/** Entry screen: start the test, open settings, and see the current setting at a glance. */
class MainMenuScreen(ctx: AppContext) : UiScreen(ctx) {
    override fun build(): Actor {
        val content = Table()
        val left = Table()
        left.add(ui.label(t("menu.title_1") + "\n" + t("menu.title_2"), Styles.HEADING)).left().padBottom(HEADING_GAP).row()
        left.add(menuItem(t("menu.start"), startSubtitle(), "Enter") { ctx.showStart() })
            .width(MENU_WIDTH).height(MENU_ITEM_HEIGHT).padBottom(MENU_GAP).row()
        if (ctx.hasRangeMode()) {
            left.add(menuItem(t("menu.range"), t("menu.range_sub"), "M") { ctx.showRangeMode() })
                .width(MENU_WIDTH).height(MENU_ITEM_HEIGHT).padBottom(MENU_GAP).row()
        }
        left.add(menuItem(t("menu.results"), resultsSubtitle(), "R") { ctx.showStoredResults() })
            .width(MENU_WIDTH).height(MENU_ITEM_HEIGHT).padBottom(MENU_GAP).row()
        left.add(menuItem(t("menu.acceleration"), t("menu.acceleration_sub"), "A") { ctx.showRawAccelHub() })
            .width(MENU_WIDTH).height(MENU_ITEM_HEIGHT).padBottom(MENU_GAP).row()
        left.add(menuItem(t("menu.history"), t("menu.history_sub"), "H") { ctx.showHistory() })
            .width(MENU_WIDTH).height(MENU_ITEM_HEIGHT).padBottom(MENU_GAP).row()
        left.add(menuItem(t("menu.crosshair"), t("menu.crosshair_sub"), "C") { ctx.showCrosshair() })
            .width(MENU_WIDTH).height(MENU_ITEM_HEIGHT).padBottom(MENU_GAP).row()
        left.add(menuItem(t("menu.settings"), t("menu.settings_sub"), "S") { ctx.showSettings() })
            .width(MENU_WIDTH).height(MENU_ITEM_HEIGHT).left().row()
        ctx.startupProblems.forEach { left.add(ui.label(it, Styles.CAUTION, wrap = true)).width(MENU_WIDTH).left().padTop(PROBLEM_GAP).row() }
        pendingUpdate()?.let { update ->
            left.add(ui.button(t("update.available", update.version.toString()), Styles.TEXT) { openUpdate() }).left().padTop(PROBLEM_GAP).row()
        }
        content.add(left).top().left()
        content.add(currentSettingBlock()).top().left().padLeft(COLUMN_GAP).padTop(RIGHT_BLOCK_TOP)

        val footer = Table()
        footer.add(ui.label(t("menu.footer"), Styles.CAPTION_MUTED)).left().bottom().padBottom(ITEM_PADDING)
        footer.add().expandX()
        footer.add(ui.button(t("menu.exit"), Styles.SECONDARY) { Gdx.app.exit() }).right().bottom().height(FOOTER_BUTTON_HEIGHT).minWidth(EXIT_BUTTON_WIDTH)
        return ui.frame(null, content, footer)
    }

    override fun onEnter() = ctx.showStart()

    override fun onEscape() = Gdx.app.exit()

    override fun onKey(keycode: Int): Boolean = when (keycode) {
        Input.Keys.S -> true.also { ctx.showSettings() }
        Input.Keys.R -> true.also { ctx.showStoredResults() }
        Input.Keys.A -> true.also { ctx.showRawAccelHub() }
        Input.Keys.H -> true.also { ctx.showHistory() }
        Input.Keys.C -> true.also { ctx.showCrosshair() }
        Input.Keys.M -> ctx.hasRangeMode().also { if (it) ctx.showRangeMode() }
        Input.Keys.U -> (pendingUpdate() != null).also { if (it) openUpdate() }
        else -> false
    }

    /** A newer release than this build, unless the player switched the check off in the settings. */
    private fun pendingUpdate() = ctx.availableUpdate?.takeIf { ctx.settings.current.checkForUpdates }

    private fun openUpdate() {
        pendingUpdate()?.let { ctx.showUpdate(it) }
    }

    private val storedRounds by lazy { ctx.storedRoundCount() }

    private fun resultsSubtitle(): String =
        if (storedRounds == 0) t("menu.results_none") else t("menu.results_stored", storedRounds)

    private fun startSubtitle(): String {
        val config = ctx.trainerConfig() ?: return t("menu.start_setup")
        return t("menu.start_sub", config.roundSeconds, config.profile.name)
    }

    private fun menuItem(title: String, subtitle: String, key: String, action: () -> Unit): Button {
        val button = Button(ui.skin, Styles.CARD_BUTTON)
        val text = Table()
        text.left()
        text.add(ui.label(title, Styles.BODY_LG)).left().row()
        text.add(ui.label(subtitle, Styles.CAPTION_MUTED)).left().padTop(4f)
        button.add(text).growX().left().pad(0f, ITEM_PADDING, 0f, 0f)
        button.add(ui.label(key, Styles.MONO_MUTED)).right().padRight(ITEM_PADDING)
        button.children.forEach { it.touchable = Touchable.disabled }
        button.onChange(action)
        return button
    }

    private fun currentSettingBlock(): Table {
        val block = Table()
        block.add(ui.caps(t("menu.current"))).left().padBottom(16f).row()
        val card = Table()
        card.background(ui.skin.getDrawable(Styles.CARD))
        card.pad(CARD_PADDING)
        val config = ctx.trainerConfig()
        if (config == null) {
            card.add(ui.label(t("menu.not_set_up"), Styles.TITLE)).left().row()
            card.add(ui.label(t("menu.not_set_up_sub"), Styles.CAPTION_MUTED)).left().padTop(8f)
        } else {
            val draft = SetupDraft.fromSettings(ctx.settings.current)
            val row = Table()
            row.add(ui.label("%.1f".format(Locale.ROOT, config.cm360), Styles.NUMBER_XL)).bottom()
            row.add(ui.label("cm/360°", Styles.NUMBER_MD).also { it.setAlignment(Align.left) }).bottom().padLeft(UNIT_GAP).padBottom(UNIT_BASELINE_LIFT)
            card.add(row).left().row()
            val stats = Table()
            stats.add(stat(t("menu.stat_game"), config.profile.name)).left().padRight(STAT_GAP)
            stats.add(stat(t("menu.stat_sens"), draft.sensitivity?.let(config.profile::formatSensitivity) ?: "-")).left().padRight(STAT_GAP)
            stats.add(stat(t("menu.stat_dpi"), SetupDraft.formatNumber(config.dpi))).left()
            card.add(stats).left().padTop(UNIT_GAP)
        }
        block.add(card).width(CARD_WIDTH).left()
        return block
    }

    private fun stat(name: String, value: String): Table = Table().apply {
        add(ui.label(name, Styles.CAPTION_MUTED)).left().row()
        add(ui.label(value, Styles.MONO)).left().padTop(4f)
    }
}
