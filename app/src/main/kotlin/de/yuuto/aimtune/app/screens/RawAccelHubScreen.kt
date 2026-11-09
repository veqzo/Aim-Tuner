package de.yuuto.aimtune.app.screens

import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Button
import com.badlogic.gdx.scenes.scene2d.ui.Table
import de.yuuto.aimtune.app.AppContext
import de.yuuto.aimtune.app.accel.ImportedProfile
import de.yuuto.aimtune.app.accel.SettingPages
import de.yuuto.aimtune.app.accel.SettingRow
import de.yuuto.aimtune.app.accel.SettingsAnalyses
import de.yuuto.aimtune.app.ui.Styles
import de.yuuto.aimtune.app.ui.addButton
import de.yuuto.aimtune.app.ui.button
import de.yuuto.aimtune.app.ui.caps
import de.yuuto.aimtune.app.ui.frame
import de.yuuto.aimtune.app.ui.label
import de.yuuto.aimtune.app.ui.onChange
import java.util.concurrent.CompletableFuture

private const val ROW_HEIGHT = 64f
private const val ROW_GAP = 4f
private const val ROW_PADDING = 24f
private const val ROW_VERTICAL_PADDING = 3f
private const val BADGE_WIDTH = 110f
private const val SECTION_GAP = 28f
private const val FOOTER_BUTTON_HEIGHT = 64f

/**
 * The Raw Accel hub (docs/RAWACCEL-HUB.md): the sensitivity every run is played at, the imported profile, the curve tools and
 * one row for each other Raw Accel setting. Two columns when the window is wide enough, one below 1280 design units; the frame
 * scrolls when the content is taller than the window. The analyses are computed in the background, rows show "computing" until
 * they are there.
 */
class RawAccelHubScreen(ctx: AppContext, private val analysesFuture: CompletableFuture<SettingsAnalyses>, private val imported: ImportedProfile?) : UiScreen(ctx) {
    private var analyses: SettingsAnalyses? = null
    private var builtTwoColumns = true

    private val twoColumns: Boolean get() = HubLayout.twoColumns(stage.viewport.worldWidth)

    private fun columnWidth() = HubLayout.columnWidth(stage.viewport.worldWidth, ui.tokens.margin)

    private fun pages() = SettingPages({ key, args -> ctx.i18n.t(key, *args) }, analyses, imported?.profile(ctx.trainerConfig()?.dpi ?: 0.0))

    override fun build(): Actor {
        builtTwoColumns = twoColumns
        val width = columnWidth()
        val left = Table().top().left()
        left.add(ui.label(t("hub.title"), Styles.HEADING)).left().padBottom(SECTION_GAP).row()
        left.add(ctx.runsUseCard(ctx.runsUse(), width) { ctx.showRawAccelHub(analysesFuture) }).left().width(width).padBottom(16f).row()
        left.add(ui.label(profileLine(), Styles.CAPTION_MUTED, wrap = true)).left().width(width).padBottom(SECTION_GAP).row()
        left.add(curveSection(width)).left().width(width)
        val right = Table().top().left()
        right.add(settingsSection(width)).left().width(width)

        val content = Table()
        if (twoColumns) {
            content.add(left).top().left().width(width)
            content.add(right).top().left().width(width).padLeft(HubLayout.COLUMN_GAP)
        } else {
            content.add(left).top().left().width(width).row()
            content.add(right).top().left().width(width).padTop(SECTION_GAP)
        }
        return frame(content, footer())
    }

    override fun render(delta: Float) {
        if (analyses == null && analysesFuture.isDone) {
            analyses = runCatching { analysesFuture.get() }.getOrNull()
            rebuild()
        }
        super.render(delta)
    }

    override fun resize(width: Int, height: Int) {
        super.resize(width, height)
        if (twoColumns != builtTwoColumns) rebuild()
    }

    override fun onEscape() = ctx.showMenu()

    private fun footer(): Table {
        val footer = Table()
        footer.add(ui.button(t("common.back"), Styles.TEXT) { ctx.showMenu() }).left().height(FOOTER_BUTTON_HEIGHT).padLeft(8f)
        footer.add().expandX()
        val copy = ctx.hub.patchedCopy(ctx.trainerConfig()?.dpi ?: 0.0, analyses)
        val export = ui.button(t("hub.export.button"), Styles.SECONDARY) { ctx.showPatchedExport(analysesFuture) }
        export.isDisabled = copy?.hasChanges != true
        footer.addButton(export, minWidth = 360f)
        return footer
    }

    private fun profileLine(): String =
        imported?.let { t("hub.profile.line", it.profileName, it.fileLabel) } ?: t("hub.profile.none")

    private fun curveSection(width: Float): Table {
        val section = Table().left()
        section.add(ui.caps(t("hub.section.curve"))).left().padBottom(12f).row()
        listOf(
            Triple(t("accel.title"), t("hub.curve.find_sub")) { ctx.showAccel() },
            Triple(t("hub.curve.refine"), t("hub.curve.refine_sub") + " " + t("hub.curve.refine_note")) { ctx.showRefine() },
            Triple(t("accel.last_search"), t("hub.curve.last_search_sub")) { ctx.showLastSearch() },
            Triple(t("accel.last"), t("hub.curve.last_analysis_sub")) { ctx.showLastAccelAnalysis() },
        ).forEach { (title, subtitle, action) -> section.add(row(title, subtitle, null, width, action)).left().width(width).minHeight(ROW_HEIGHT).padBottom(ROW_GAP).row() }
        return section
    }

    private fun settingsSection(width: Float): Table {
        val section = Table().left()
        section.add(ui.caps(t("hub.section.settings"))).left().padBottom(12f).row()
        pages().rows().forEach { setting: SettingRow ->
            section.add(row(setting.name, setting.state, setting.badge, width) { ctx.showRawAccelSetting(setting.setting, analysesFuture) })
                .left().width(width).minHeight(ROW_HEIGHT).padBottom(ROW_GAP).row()
        }
        return section
    }

    /** A list row like the menu's: title and a muted line on the left, an optional badge on the right. */
    private fun row(title: String, subtitle: String, badge: String?, width: Float, action: () -> Unit): Button {
        val button = Button(ui.skin, Styles.CARD_BUTTON)
        val text = Table().left()
        val textWidth = width - 2 * ROW_PADDING - (if (badge == null) 0f else BADGE_WIDTH)
        text.add(ui.label(title, Styles.BODY)).left().row()
        text.add(ui.label(subtitle, Styles.CAPTION_MUTED, wrap = true)).left().width(textWidth).padTop(2f)
        button.add(text).growX().left().pad(ROW_VERTICAL_PADDING, ROW_PADDING, ROW_VERTICAL_PADDING, 0f)
        if (badge != null) button.add(ui.caps(badge)).right().width(BADGE_WIDTH - ROW_PADDING / 2).padRight(ROW_PADDING)
        button.children.forEach { it.touchable = Touchable.disabled }
        button.onChange(action)
        return button
    }
}
