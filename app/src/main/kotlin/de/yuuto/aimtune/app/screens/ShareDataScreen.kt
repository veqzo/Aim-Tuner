package de.yuuto.aimtune.app.screens

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.ui.Table
import de.yuuto.aimtune.app.AppContext
import de.yuuto.aimtune.app.ui.Styles
import de.yuuto.aimtune.app.ui.addButton
import de.yuuto.aimtune.app.ui.button
import de.yuuto.aimtune.app.ui.caps
import de.yuuto.aimtune.app.ui.frame
import de.yuuto.aimtune.app.ui.label
import de.yuuto.aimtune.storage.ResearchExportResult
import java.util.concurrent.CompletableFuture

private const val TEXT_WIDTH = 1000f
private const val CARD_WIDTH = 900f
private const val CARD_PADDING = 32f
private const val FOOTER_BUTTON_HEIGHT = 64f
private const val BYTES_PER_KB = 1024

/** What is stored, for the counts line. */
data class StoredCounts(val sessions: Int, val rounds: Int)

/**
 * Exports the stored rounds as one file for a researcher (docs/history/ASSESSMENT.md D4). Nothing is sent
 * anywhere: the file is written to the data folder and the person sends it themselves. The screen says
 * what the file holds and what it does not hold before anything is written.
 */
class ShareDataScreen(ctx: AppContext, private val counts: CompletableFuture<StoredCounts>) : UiScreen(ctx) {
    private var stored: StoredCounts? = null
    private var export: CompletableFuture<Result<ResearchExportResult>>? = null
    private var result: Result<ResearchExportResult>? = null

    override fun build(): Actor {
        val content = Table()
        content.add(ui.label(t("share.title"), Styles.HEADING)).left().padBottom(24f).row()
        content.add(ui.label(t("share.intro"), Styles.BODY_MUTED, wrap = true)).left().width(TEXT_WIDTH).padBottom(24f).row()
        content.add(infoCard()).left().width(CARD_WIDTH).padBottom(24f).row()
        content.add(ui.label(countsText(), Styles.BODY_MUTED, wrap = true)).left().width(TEXT_WIDTH).padBottom(16f).row()
        statusLabel()?.let { content.add(it).left().width(TEXT_WIDTH).row() }

        val footer = Table()
        footer.add(ui.button(t("common.back"), Styles.TEXT) { ctx.showHistory() }).left().height(FOOTER_BUTTON_HEIGHT).padLeft(8f)
        footer.add().expandX()
        val saved = result?.getOrNull()
        if (saved != null) {
            footer.addButton(ui.button(t("share.copy_path"), Styles.SECONDARY) { Gdx.app.clipboard.contents = saved.file.toString() }, minWidth = 220f).padRight(16f)
            footer.addButton(ui.button(t("share.open_folder"), Styles.SECONDARY) { Gdx.net.openURI(saved.file.parent.toUri().toString()) }, minWidth = 240f).padRight(16f)
        }
        val rounds = stored?.rounds ?: 0
        val busy = export != null && result == null
        val button = ui.button(t("share.export"), Styles.PRIMARY) { startExport() }
        button.isDisabled = rounds == 0 || busy
        footer.addButton(button, minWidth = 260f)
        return frame(content, footer)
    }

    override fun render(delta: Float) {
        if (stored == null && counts.isDone) {
            stored = runCatching { counts.get() }.getOrDefault(StoredCounts(0, 0))
            rebuild()
        }
        val running = export
        if (running != null && result == null && running.isDone) {
            result = runCatching { running.get() }.getOrElse { Result.failure(it) }
            rebuild()
        }
        super.render(delta)
    }

    override fun onEscape() = ctx.showHistory()

    private fun startExport() {
        if (export != null && result == null) return
        result = null
        export = ctx.exportResearch()
        rebuild()
    }

    private fun infoCard(): Table {
        val card = Table()
        card.background(ui.skin.getDrawable(Styles.CARD))
        card.pad(CARD_PADDING)
        card.add(ui.caps(t("share.contains_title"))).left().row()
        card.add(ui.label(t("share.contains"), Styles.CAPTION_MUTED, wrap = true)).left().width(CARD_WIDTH - 2 * CARD_PADDING).padTop(8f).padBottom(20f).row()
        card.add(ui.caps(t("share.not_contains_title"))).left().row()
        card.add(ui.label(t("share.not_contains"), Styles.CAPTION_MUTED, wrap = true)).left().width(CARD_WIDTH - 2 * CARD_PADDING).padTop(8f)
        return card
    }

    private fun countsText(): String {
        val s = stored ?: return ""
        return if (s.rounds == 0) t("share.none") else t("share.counts", s.sessions, s.rounds)
    }

    private fun statusLabel() = when {
        export != null && result == null -> ui.label(t("share.working"), Styles.BODY_MUTED)
        else -> result?.fold(
            { ui.label(t("share.done", it.file, it.sessions, it.rounds, it.bytes / BYTES_PER_KB), Styles.OK, wrap = true) },
            { ui.label(t("share.failed", it.message.orEmpty()), Styles.CAUTION, wrap = true) },
        )
    }
}
