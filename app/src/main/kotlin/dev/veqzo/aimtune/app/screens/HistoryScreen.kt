package dev.veqzo.aimtune.app.screens

import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox
import com.badlogic.gdx.scenes.scene2d.ui.Table
import dev.veqzo.aimtune.app.AppContext
import dev.veqzo.aimtune.app.history.HistoryEntry
import dev.veqzo.aimtune.app.ui.ChartActor
import dev.veqzo.aimtune.app.ui.ChartDots
import dev.veqzo.aimtune.app.ui.ChartLine
import dev.veqzo.aimtune.app.ui.ChartSpec
import dev.veqzo.aimtune.app.ui.LedState
import dev.veqzo.aimtune.app.ui.ModuleStatus
import dev.veqzo.aimtune.app.ui.Styles
import dev.veqzo.aimtune.app.ui.button
import dev.veqzo.aimtune.app.ui.caps
import dev.veqzo.aimtune.app.ui.label
import dev.veqzo.aimtune.app.ui.modulePanel
import dev.veqzo.aimtune.app.ui.onChange
import dev.veqzo.aimtune.app.ui.readout
import dev.veqzo.aimtune.storage.SessionKind
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.CompletableFuture

private const val FOOTER_BUTTON_HEIGHT = 64f
private const val TOP_HEIGHT = 260f
private const val READOUT_WIDTH = 620f
private const val COLUMN_GAP = 48f
private const val SECTION_GAP = 32f
private const val CHART_PADDING = 20f
private const val FILTER_WIDTH = 320f
private const val ROWS_SHOWN = 6
private const val ROW_PAD = 8f
private const val RULE = 1f

/** Column shares of the table width: date, game, rounds, cm/360°, confidence, type. */
private val COLUMN_SHARES = floatArrayOf(0.18f, 0.2f, 0.1f, 0.15f, 0.15f, 0.22f)
private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)

/**
 * Stored sessions as an opened module: the newest recommendation as the display, the recommended
 * cm/360° after each sensitivity session as a chart that draws on, and a table of the newest sessions,
 * filterable by game. The recommendations are computed in the background ([entriesFuture]) because
 * every session needs its own model fit; the screen fills in when they are done.
 */
class HistoryScreen(ctx: AppContext, private val entriesFuture: CompletableFuture<List<HistoryEntry>>) : UiScreen(ctx) {
    override val utility = Utility.HISTORY

    private var entries: List<HistoryEntry>? = null
    private var gameFilter: String? = null

    /** The display and chart animate when the data first arrives, not again on a filter change. */
    private var revealed = false

    override fun build(): Actor {
        val all = entries
        val shown = all?.filter { gameFilter == null || it.gameId == gameFilter }
        val status = shown?.let { ModuleStatus(LedState.OFF, t("history.count", it.size)) }
        val filter = all?.takeIf { it.isNotEmpty() }?.let(::filterBox)
        val panel = ui.modulePanel(t("history.title"), status, filter, showLed = false)
        when {
            all == null -> panel.add(ui.label(t("history.loading"), Styles.BODY_MUTED)).left().row()
            all.isEmpty() -> panel.add(ui.label(t("history.empty"), Styles.BODY_MUTED)).left().row()
            else -> fill(panel, checkNotNull(shown))
        }
        panel.add().expand()
        val content = Table()
        content.add(panel).grow()

        val footer = Table()
        footer.add(ui.button(t("common.back"), Styles.TEXT) { ctx.showMenu() }).left().height(FOOTER_BUTTON_HEIGHT).padLeft(8f)
        footer.add().expandX()
        footer.add(ui.button(t("history.share"), Styles.SECONDARY) { ctx.showShareData(back = { ctx.showHistory() }) }).height(FOOTER_BUTTON_HEIGHT).minWidth(300f)
        return frame(content, footer)
    }

    override fun render(delta: Float) {
        if (entries == null && entriesFuture.isDone) {
            entries = runCatching { entriesFuture.get() }.getOrDefault(emptyList())
            rebuild()
        }
        super.render(delta)
    }

    override fun onEscape() = ctx.showMenu()

    private fun fill(panel: Table, shown: List<HistoryEntry>) {
        val animate = !revealed
        revealed = true
        val recommended = shown.filter { it.cm360 != null }
        val top = Table()
        top.add(ui.readout(t("history.latest"), recommended.lastOrNull()?.cm360, "cm/360°", animate)).width(READOUT_WIDTH).growY()
        if (recommended.size >= 2) top.add(chartWell(recommended, animate)).grow().padLeft(COLUMN_GAP)
        else top.add().expandX()
        panel.add(top).growX().height(TOP_HEIGHT).row()
        panel.add(table(shown)).growX().top().padTop(SECTION_GAP).row()
    }

    private fun chartWell(recommended: List<HistoryEntry>, animate: Boolean): Table {
        val well = Table()
        well.background(ui.skin.getDrawable(Styles.WELL))
        well.pad(CHART_PADDING)
        well.add(ui.label(t("history.chart"), Styles.LABEL)).left().padLeft(CHART_PADDING).row()
        val chart = ChartActor(ui, trendChart(recommended))
        if (animate) chart.drawOn(ui.tokens.motionSlow * 3, ui.tokens.motionBase)
        well.add(chart).grow()
        return well
    }

    private fun filterBox(all: List<HistoryEntry>): SelectBox<String> {
        val games = all.map { it.gameId }.distinct()
        val select = SelectBox<String>(ui.skin)
        val allGames = t("history.all_games")
        val names = listOf(allGames) + games.map(::gameName)
        select.setItems(*names.toTypedArray())
        select.selected = if (gameFilter == null) allGames else gameName(gameFilter)
        select.onChange {
            gameFilter = if (select.selectedIndex == 0) null else games[select.selectedIndex - 1]
            rebuild()
        }
        return select.also { it.width = FILTER_WIDTH }
    }

    /** The newest sessions first, one rule between rows; the newest row is not muted. */
    private fun table(shown: List<HistoryEntry>): Table {
        val table = Table().top().left()
        val headers = listOf("date", "game", "rounds", "cm", "confidence", "type").map { t("history.col_$it") }
        val header = row(headers) { ui.caps(it) }
        table.add(header).growX().padBottom(ROW_PAD).row()
        shown.asReversed().take(ROWS_SHOWN).forEachIndexed { index, entry ->
            table.add(rule()).growX().height(RULE).row()
            val style = if (index == 0) Styles.MONO else Styles.MONO_MUTED
            table.add(row(cells(entry)) { ui.label(it, style) }).growX().pad(ROW_PAD, 0f, ROW_PAD, 0f).row()
        }
        if (shown.size > ROWS_SHOWN) {
            table.add(rule()).growX().height(RULE).row()
            table.add(ui.label(t("history.older", shown.size - ROWS_SHOWN), Styles.CAPTION_MUTED)).left().padTop(ROW_PAD)
        }
        return table
    }

    /** One table row whose columns take fixed shares of the row width, so header and rows line up. */
    private fun row(texts: List<String>, cell: (String) -> Actor): Table {
        val row = Table()
        texts.forEachIndexed { i, text -> row.add(cell(text)).left().expandX().fillX().uniformX().prefWidth(COLUMN_SHARES[i] * 1000f) }
        return row
    }

    private fun cells(entry: HistoryEntry) = listOf(
        DATE_FORMAT.format(Instant.ofEpochMilli(entry.startedAtMs).atZone(ZoneId.systemDefault())),
        gameName(entry.gameId),
        entry.rounds.toString(),
        entry.cm360?.let { "%.1f".format(Locale.ROOT, it) } ?: "–",
        entry.confidencePercent?.let { "$it%" } ?: "–",
        if (entry.kind != SessionKind.SENSITIVITY) t("history.type_accel") else t("history.type_sensitivity"),
    )

    private fun rule() = Image(ui.whiteSolid).also { it.color.set(ui.tokens.divider) }

    private fun trendChart(recommended: List<HistoryEntry>): ChartSpec {
        val xs = DoubleArray(recommended.size) { it + 1.0 }
        val ys = DoubleArray(recommended.size) { checkNotNull(recommended[it].cm360) }
        val pad = maxOf((ys.max() - ys.min()) * Y_PADDING, MIN_Y_PADDING)
        val ticks = (1..recommended.size).filter { recommended.size <= MAX_TICKS || it % (recommended.size / MAX_TICKS + 1) == 0 }.map { it.toDouble() }
        return ChartSpec(
            xMin = 0.5,
            xMax = recommended.size + 0.5,
            yMin = ys.min() - pad,
            yMax = ys.max() + pad,
            xTicks = ticks,
            formatX = { "#%d".format(Locale.ROOT, it.toInt()) },
            lines = listOf(ChartLine(xs, ys, ui.tokens.accent(300))),
            dots = listOf(ChartDots(xs, ys, ui.tokens.accent(300))),
        )
    }

    private fun gameName(id: String?): String = id?.let { ctx.profiles.find(it)?.name ?: it } ?: "–"

    private companion object {
        const val Y_PADDING = 0.25
        const val MIN_Y_PADDING = 1.0
        const val MAX_TICKS = 10
    }
}
