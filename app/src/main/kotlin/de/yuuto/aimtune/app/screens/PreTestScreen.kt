package de.yuuto.aimtune.app.screens

import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.ui.CheckBox
import com.badlogic.gdx.scenes.scene2d.ui.Table
import de.yuuto.aimtune.app.AppContext
import de.yuuto.aimtune.app.pretest.CheckLevel
import de.yuuto.aimtune.app.pretest.CheckRow
import de.yuuto.aimtune.app.pretest.PreTestChecks
import de.yuuto.aimtune.app.ui.Styles
import de.yuuto.aimtune.app.ui.addButton
import de.yuuto.aimtune.app.ui.button
import de.yuuto.aimtune.app.ui.frame
import de.yuuto.aimtune.app.ui.label
import de.yuuto.aimtune.app.ui.onChange
import de.yuuto.aimtune.input.windows.WindowsPointerSettings
import de.yuuto.aimtune.input.windows.WindowsRawInputSource

private const val DESCRIPTION_WIDTH = 900f
private const val ROW_WIDTH = 1100f
private const val ROW_PADDING = 28f
private const val ROW_GAP = 12f
private const val DETAIL_WIDTH = 820f
private const val FOOTER_BUTTON_HEIGHT = 64f

/**
 * Tells the user what could distort the measurement: Windows pointer acceleration (read from the
 * system), Raw Accel (confirmed by hand, it cannot be detected) and the mouse input source. It
 * only informs; the test can always be started.
 */
class PreTestScreen(ctx: AppContext, private val rangeMode: Boolean = false) : UiScreen(ctx) {
    private var acceleration = WindowsPointerSettings.readAcceleration()

    override fun build(): Actor {
        val content = Table()
        content.add(ui.label(t("pretest.title"), Styles.HEADING)).left().padBottom(24f).row()
        content.add(
            ui.label(t("pretest.description"), Styles.BODY_MUTED, wrap = true),
        ).left().width(DESCRIPTION_WIDTH).padBottom(48f).row()
        if (rangeMode) content.add(rangeInfo()).left().width(ROW_WIDTH).padBottom(ROW_GAP).row()
        val profile = ctx.trainerConfig()?.profile
        val rows = PreTestChecks.rows(
            acceleration, ctx.rawAccelConfirmed, ctx.source.name, ctx.source is WindowsRawInputSource,
            targetsVerified = profile?.geometry?.verified, gameName = profile?.name.orEmpty(),
        )
        rows.forEach { content.add(checkRow(it)).left().width(ROW_WIDTH).padBottom(ROW_GAP).row() }

        val footer = Table()
        footer.add(ui.button(t("common.back"), Styles.TEXT) { ctx.showMenu() }).left().height(FOOTER_BUTTON_HEIGHT).padLeft(8f)
        footer.add().expandX()
        footer.addButton(ui.button(t("pretest.recheck"), Styles.SECONDARY) { recheck() }, minWidth = 180f).padRight(16f)
        footer.addButton(ui.button(t("pretest.start"), Styles.PRIMARY) { ctx.startBlindTest(rangeMode) }, minWidth = 220f)
        return frame(content, footer, Chrome.MINIMAL)
    }

    override fun onEscape() = ctx.showMenu()

    override fun onEnter() = ctx.startBlindTest(rangeMode)

    /** One line: this is the Range mode, and the speed, bots and armor settings it will use. */
    private fun rangeInfo(): Table {
        val options = ctx.settings.current.rangeOptions()
        val table = Table()
        table.background(ui.skin.getDrawable(Styles.CARD))
        table.pad(ROW_PADDING)
        val bots = t(if (options.botsStrafe) "settings.range_bots.strafe" else "settings.range_bots.reset")
        table.add(ui.label(t("pretest.range_mode", t("settings.range_speed.${options.speed.id}"), bots, t(if (options.armor) "settings.range_armor.on" else "settings.range_armor.off")), Styles.BODY, wrap = true)).left().growX()
        return table
    }

    private fun recheck() {
        acceleration = WindowsPointerSettings.readAcceleration()
        rebuild()
    }

    private fun checkRow(row: CheckRow): Table {
        val table = Table()
        val attention = row.level == CheckLevel.ATTENTION
        table.background(ui.skin.getDrawable(if (attention) Styles.CARD_CAUTION else Styles.CARD))
        table.pad(ROW_PADDING)
        val text = Table()
        text.add(ui.label(t(row.title), Styles.BODY_LG)).left().row()
        text.add(ui.label(if (row.detailArg == null) t(row.detail) else t(row.detail, row.detailArg), Styles.CAPTION_MUTED, wrap = true)).left().width(DETAIL_WIDTH).padTop(4f).row()
        if (row.id == PreTestChecks.RAW_ACCEL) text.add(rawAccelCheckBox()).left().padTop(12f)
        table.add(text).left().expandX()
        val stateStyle = when (row.level) {
            CheckLevel.OK -> Styles.OK
            CheckLevel.ATTENTION -> Styles.CAUTION
            CheckLevel.UNKNOWN -> Styles.MONO_MUTED
        }
        table.add(ui.label(t(row.state), stateStyle)).right().top()
        return table
    }

    private fun rawAccelCheckBox(): CheckBox {
        val box = CheckBox("  " + t("pretest.raw_accel_checked"), ui.skin)
        box.isChecked = ctx.rawAccelConfirmed
        box.onChange {
            ctx.rawAccelConfirmed = box.isChecked
            rebuild()
        }
        return box
    }
}
