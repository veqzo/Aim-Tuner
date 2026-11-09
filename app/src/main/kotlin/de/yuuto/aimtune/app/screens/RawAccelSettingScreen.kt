package de.yuuto.aimtune.app.screens

import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.ui.CheckBox
import com.badlogic.gdx.scenes.scene2d.ui.Table
import de.yuuto.aimtune.app.AppContext
import de.yuuto.aimtune.app.accel.RawAccelSetting
import de.yuuto.aimtune.app.accel.SettingKind
import de.yuuto.aimtune.app.accel.SettingPage
import de.yuuto.aimtune.app.accel.SettingPages
import de.yuuto.aimtune.app.accel.SettingsAnalyses
import de.yuuto.aimtune.app.ui.Styles
import de.yuuto.aimtune.app.ui.button
import de.yuuto.aimtune.app.ui.caps
import de.yuuto.aimtune.app.ui.frame
import de.yuuto.aimtune.app.ui.label
import de.yuuto.aimtune.app.ui.onChange
import de.yuuto.aimtune.core.accel.settings.PassiveResult
import java.util.concurrent.CompletableFuture

private const val CARD_PADDING = 28f
private const val CARD_GAP = 16f
private const val FOOTER_BUTTON_HEIGHT = 64f
private val FORMULA_SEPARATOR = Regex(" {4,}")

/**
 * One generic page for every setting of the hub, fed by a [SettingPage]: what the setting does (words and formula), the value in
 * the imported profile, the recommended value and why; for an analysis row the result with its range and the number of
 * reaches, for an info row why AimTune does not measure it. A recommendation of rotation, Y/X or L/R can be ticked into the
 * patched copy (off by default, kept for the session).
 */
class RawAccelSettingScreen(ctx: AppContext, private val setting: RawAccelSetting, private val analysesFuture: CompletableFuture<SettingsAnalyses>) : UiScreen(ctx) {
    private var analyses: SettingsAnalyses? = null
    private var builtTwoColumns = true

    private val twoColumns: Boolean get() = HubLayout.twoColumns(stage.viewport.worldWidth)

    private fun page(): SettingPage =
        SettingPages({ key, args -> ctx.i18n.t(key, *args) }, analyses, ctx.hub.imported?.profile(ctx.trainerConfig()?.dpi ?: 0.0)).page(setting)

    override fun build(): Actor {
        builtTwoColumns = twoColumns
        val width = HubLayout.columnWidth(stage.viewport.worldWidth, ui.tokens.margin)
        val page = page()
        val heading = Table()
        heading.add(ui.label(page.name, Styles.HEADING)).left()
        heading.add(ui.caps(page.badge)).left().padLeft(24f).padTop(24f)

        val what = whatCard(page, width)
        val current = card(t("hub.page.current")) { it.add(ui.label(page.current, Styles.MONO, wrap = true)).left().width(innerWidth(width)) }
        val recommended = recommendedCard(page, width)
        val result = resultCard(page, width)
        val toggle = exportToggle(page, width)
        val notMeasured = page.notMeasured?.let { text ->
            card(t("hub.page.not_measured")) { it.add(ui.label(text, Styles.BODY_MUTED, wrap = true)).left().width(innerWidth(width)) }
        }

        val content = Table()
        content.add(heading).left().colspan(2).padBottom(24f).row()
        // Two columns: what the setting is on the left, what to do about it on the right. One column reads top to bottom.
        val columns = if (twoColumns) listOf(listOf(what, current, notMeasured), listOf(recommended, result, toggle)) else listOf(listOf(what, current, recommended, result, toggle, notMeasured))
        columns.forEachIndexed { index, cards ->
            val column = Table().top().left()
            cards.filterNotNull().forEach { column.add(it).left().width(width).padBottom(CARD_GAP).row() }
            val cell = content.add(column).top().left().width(width)
            if (twoColumns && index == 0) cell.padRight(HubLayout.COLUMN_GAP)
        }
        val footer = Table()
        footer.add(ui.button(t("common.back"), Styles.TEXT) { ctx.showRawAccelHub(analysesFuture) }).left().height(FOOTER_BUTTON_HEIGHT).padLeft(8f)
        footer.add().expandX()
        return frame(content, footer)
    }

    private fun innerWidth(width: Float) = width - 2 * CARD_PADDING

    private fun whatCard(page: SettingPage, width: Float) = card(t("hub.page.what")) {
        it.add(ui.label(page.what, Styles.BODY_MUTED, wrap = true)).left().width(innerWidth(width)).row()
        // The formulas of a page are separated by runs of spaces; each gets a line of its own.
        page.formula.split(FORMULA_SEPARATOR).forEach { part -> it.add(ui.label(part, Styles.MONO, wrap = true)).left().width(innerWidth(width)).padTop(8f).row() }
    }

    private fun recommendedCard(page: SettingPage, width: Float) = card(t("hub.page.recommended")) {
        it.add(ui.label(page.recommended, Styles.TITLE, wrap = true)).left().width(innerWidth(width)).row()
        it.add(ui.label(page.why, Styles.BODY_MUTED, wrap = true)).left().width(innerWidth(width)).padTop(12f)
    }

    /** The numbers behind the recommendation, or "computing" until the analyses are there; none for rows without numbers. */
    private fun resultCard(page: SettingPage, width: Float): Table? {
        if (page.result.isEmpty() && analyses != null) return null
        return card(t(if (setting.kind == SettingKind.ANALYSIS) "hub.page.result" else "hub.page.measured")) { box ->
            if (analyses == null) box.add(ui.label(t("hub.state.computing"), Styles.BODY_MUTED)).left()
            page.result.forEach { box.add(ui.label(it, Styles.MONO, wrap = true)).left().width(innerWidth(width)).padBottom(4f).row() }
        }
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

    override fun onEscape() = ctx.showRawAccelHub(analysesFuture)

    private fun card(title: String, fill: (Table) -> Unit): Table {
        val card = Table()
        card.background(ui.skin.getDrawable(Styles.CARD))
        card.pad(CARD_PADDING)
        card.add(ui.caps(title)).left().padBottom(8f).row()
        fill(card)
        return card
    }

    /** The opt-in for the patched copy: off until the player ticks it, and kept while the app runs. Without an imported profile only a note. */
    private fun exportToggle(page: SettingPage, width: Float): Table? {
        if (!setting.passive) return null
        if (page.exportable == null) {
            if (analyses?.result(setting) !is PassiveResult.Recommend) return null
            return Table().also { it.add(ui.label(t("hub.toggle.needs_profile"), Styles.CAPTION_MUTED, wrap = true)).left().width(width) }
        }
        val block = Table().left()
        val box = CheckBox("  " + t("hub.toggle.label"), ui.skin)
        box.isChecked = ctx.hub.isTicked(setting)
        box.onChange { ctx.hub.setTicked(setting, box.isChecked) }
        block.add(box).left().row()
        block.add(ui.label(t("hub.toggle.help"), Styles.CAPTION_MUTED, wrap = true)).left().width(width).padTop(8f)
        return block
    }
}
