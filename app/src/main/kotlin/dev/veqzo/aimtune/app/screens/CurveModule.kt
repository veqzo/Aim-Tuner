package dev.veqzo.aimtune.app.screens

import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Button
import com.badlogic.gdx.scenes.scene2d.ui.Table
import dev.veqzo.aimtune.app.AppContext
import dev.veqzo.aimtune.app.accel.ImportedProfile
import dev.veqzo.aimtune.app.accel.SettingPages
import dev.veqzo.aimtune.app.accel.SettingsAnalyses
import dev.veqzo.aimtune.app.trainerConfigFor
import dev.veqzo.aimtune.app.ui.ChartActor
import dev.veqzo.aimtune.app.ui.Disclosure
import dev.veqzo.aimtune.app.ui.LedState
import dev.veqzo.aimtune.app.ui.ModuleStatus
import dev.veqzo.aimtune.app.ui.Styles
import dev.veqzo.aimtune.app.ui.UiSkin
import dev.veqzo.aimtune.app.ui.button
import dev.veqzo.aimtune.app.ui.caps
import dev.veqzo.aimtune.app.ui.label
import dev.veqzo.aimtune.app.ui.onChange
import dev.veqzo.aimtune.app.ui.primaryAction
import dev.veqzo.aimtune.app.ui.profileChart
import dev.veqzo.aimtune.core.accel.RawAccelProfile
import java.util.concurrent.CompletableFuture

private const val GAP = 24f
private const val SMALL_GAP = 8f
private const val CAPTION_GAP = 10f
private const val CHART_HEIGHT = 300f
private const val WELL_PADDING = 24f
private const val LINK_HEIGHT = 44f
private const val ROW_HEIGHT = 52f
private const val ROW_GAP = 4f
private const val ROW_PADDING = 20f
private const val BADGE_WIDTH = 120f
private const val COLUMN_GAP = 40f
private const val EXPORT_HEIGHT = 56f

/** What the dashboard keeps of the CURVE module between rebuilds. */
internal class CurveState {
    var flipped = false
    var detailOpen = false
    var analyses: SettingsAnalyses? = null
}

/**
 * The CURVE module of the dashboard. Front: the imported Raw Accel profile as a chart, and one
 * main action that the app picks (Refine with a profile, Find without one; the other stays as a
 * link). Back: what the curve tools need besides that (the sensitivity of the runs, import, last
 * search and analysis, the other Raw Accel settings, the patched copy).
 */
internal class CurveModule(
    private val ctx: AppContext,
    private val ui: UiSkin,
    private val state: CurveState,
    private val width: Float,
    private val analysesFuture: CompletableFuture<SettingsAnalyses>,
) {
    private fun t(key: String, vararg args: Any?) = ctx.i18n.t(key, *args)

    private val imported: ImportedProfile? = ctx.hub.restoreImported()
    private val dpi = trainerConfigFor(ctx.settings.current, ctx.profiles, 0L)?.dpi ?: 0.0
    private val profile: RawAccelProfile? by lazy { imported?.profile(dpi) }

    val status: ModuleStatus
        get() = imported?.let { ModuleStatus(LedState.OK, t("dash.curve.status.profile", it.profileName)) }
            ?: ModuleStatus(LedState.OFF, t("dash.curve.status.none"))

    /** The main action: refine the imported curve, or find one when there is none. */
    fun start() = if (imported != null) ctx.showRefine() else ctx.showAccel()

    fun front(): Actor {
        val table = Table().top().left()
        // The display takes the free height of the module, as on the SENS front.
        table.add(display()).left().width(width).expandY().fillY().row()
        val detail = if (imported != null) {
            Disclosure(ui, t("hub.curve.refine_sub"), t("hub.curve.refine_note"), t("dash.more") to t("dash.less"), width, state.detailOpen) { state.detailOpen = it }
        } else {
            Disclosure(ui, t("hub.curve.find_sub"), t("dash.curve.find_more"), t("dash.more") to t("dash.less"), width, state.detailOpen) { state.detailOpen = it }
        }
        val label = if (imported != null) t("hub.curve.refine") else t("accel.title")
        table.add(ui.primaryAction(emptyList(), detail, label, width) { start() }).left().padTop(GAP).row()
        val alt = if (imported != null) {
            ui.button(t("dash.curve.alt_find"), Styles.TEXT) { ctx.showAccel() }
        } else {
            ui.button(t("dash.curve.import"), Styles.TEXT) { ctx.showRefine() }
        }
        alt.pad(0f)
        table.add(alt).left().height(LINK_HEIGHT).padTop(SMALL_GAP)
        return table
    }

    /** The display well: the imported curve, or what goes there once there is one. */
    private fun display(): Table {
        val well = Table()
        well.background(ui.skin.getDrawable(Styles.WELL))
        well.pad(WELL_PADDING)
        well.top().left()
        val current = profile
        if (current != null) {
            well.add(ui.label(t("dash.curve.chart"), Styles.LABEL)).left().padBottom(CAPTION_GAP).row()
            well.add(ChartActor(ui, profileChart(ui.tokens, current))).grow().minHeight(CHART_HEIGHT - 2 * WELL_PADDING)
        } else {
            val inner = width - 2 * WELL_PADDING
            well.add(ui.label(t("dash.curve.empty_title"), Styles.BODY_LG)).left().row()
            well.add(ui.label(t("dash.curve.empty"), Styles.CAPTION_MUTED, wrap = true)).left().width(inner).padTop(SMALL_GAP).row()
            well.add().expandY().minHeight(CHART_HEIGHT - 2 * WELL_PADDING - 80f)
        }
        return well
    }

    // ---- back side ----

    fun back(): Actor {
        val column = (width - COLUMN_GAP) / 2
        val left = Table().top().left()
        left.add(ctx.runsUseBlock(ctx.runsUse(), column)).left().width(column).padBottom(GAP).row()
        left.add(ui.caps(t("hub.section.curve"))).left().padBottom(CAPTION_GAP).row()
        listOf(
            Triple(t("dash.curve.import"), imported?.let { t("hub.profile.line", it.profileName, it.fileLabel) } ?: t("hub.profile.none")) { ctx.showRefine() },
            Triple(t("accel.last_search"), t("hub.curve.last_search_sub")) { ctx.showLastSearch() },
            Triple(t("accel.last"), t("hub.curve.last_analysis_sub")) { ctx.showLastAccelAnalysis() },
        ).forEach { (title, subtitle, action) -> left.add(row(title, subtitle, null, column, action)).left().width(column).minHeight(ROW_HEIGHT).padBottom(ROW_GAP).row() }

        val right = Table().top().left()
        right.add(ui.caps(t("hub.section.settings"))).left().padBottom(CAPTION_GAP).row()
        SettingPages({ key, args -> ctx.i18n.t(key, *args) }, state.analyses, profile).rows().forEach { setting ->
            right.add(row(setting.name, setting.state, setting.badge, column) { ctx.showRawAccelSetting(setting.setting, analysesFuture) })
                .left().width(column).minHeight(ROW_HEIGHT).padBottom(ROW_GAP).row()
        }

        val table = Table().top().left()
        table.add(left).top().left().width(column)
        table.add(right).top().left().width(column).padLeft(COLUMN_GAP).row()
        table.add().expandY().colspan(2).row()
        val copy = ctx.hub.patchedCopy(dpi, state.analyses)
        val export = ui.button(t("hub.export.button"), Styles.SECONDARY) { ctx.showPatchedExport(analysesFuture) }
        export.isDisabled = copy?.hasChanges != true
        table.add(export).colspan(2).right().height(EXPORT_HEIGHT).minWidth(360f).padTop(GAP)
        return table
    }

    /** A compact list row: title and a muted line on the left, an optional badge on the right. */
    private fun row(title: String, subtitle: String, badge: String?, width: Float, action: () -> Unit): Button {
        val button = Button(ui.skin, Styles.CARD_BUTTON)
        val text = Table().left()
        val textWidth = width - 2 * ROW_PADDING - (if (badge == null) 0f else BADGE_WIDTH)
        text.add(ui.label(title, Styles.BODY, wrap = true)).left().width(textWidth).row()
        text.add(ui.label(subtitle, Styles.CAPTION_MUTED, wrap = true)).left().width(textWidth).padTop(2f)
        button.add(text).growX().left().pad(4f, ROW_PADDING, 4f, 0f)
        if (badge != null) button.add(ui.caps(badge)).right().width(BADGE_WIDTH - ROW_PADDING / 2).padRight(ROW_PADDING)
        button.children.forEach { it.touchable = Touchable.disabled }
        button.onChange(action)
        return button
    }
}
