package dev.veqzo.aimtune.app.screens

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.ui.Table
import dev.veqzo.aimtune.app.AppContext
import dev.veqzo.aimtune.app.ui.LedState
import dev.veqzo.aimtune.app.ui.ModuleStatus
import dev.veqzo.aimtune.app.ui.Styles
import dev.veqzo.aimtune.app.ui.addButton
import dev.veqzo.aimtune.app.ui.button
import dev.veqzo.aimtune.app.ui.caps
import dev.veqzo.aimtune.app.ui.label
import dev.veqzo.aimtune.app.ui.modulePanel
import dev.veqzo.aimtune.storage.ResearchExportResult
import java.util.concurrent.CompletableFuture

private const val LEFT_WIDTH = 760f
private const val COLUMN_GAP = 64f
private const val WELL_PADDING = 36f
private const val WELL_WIDTH = 860f
private const val TEXT_GAP = 20f
private const val CAPTION_GAP = 8f
private const val FOOTER_BUTTON_HEIGHT = 64f
private const val BYTES_PER_KB = 1024

/** What is stored, for the counts line. */
data class StoredCounts(val sessions: Int, val rounds: Int)

/**
 * Exports the stored rounds as one file for a researcher (docs/history/ASSESSMENT.md D4), as one opened module. Nothing is sent
 * anywhere: the file is written to the data folder and the person sends it themselves. The screen says what the file holds and
 * what it does not hold before anything is written. [back] leads to the screen that opened it (History or Settings).
 */
class ShareDataScreen(ctx: AppContext, private val back: () -> Unit, private val counts: CompletableFuture<StoredCounts>) : UiScreen(ctx) {
    private var stored: StoredCounts? = null
    private var export: CompletableFuture<Result<ResearchExportResult>>? = null
    private var result: Result<ResearchExportResult>? = null

    override fun build(): Actor {
        val left = Table().top().left()
        left.add(ui.label(t("share.intro"), Styles.BODY, wrap = true)).left().width(LEFT_WIDTH).row()
        statusLabel()?.let { left.add(it).left().width(LEFT_WIDTH).padTop(TEXT_GAP * 2).row() }

        val body = Table().top().left()
        body.add(left).top().left().width(LEFT_WIDTH)
        body.add(infoWell()).top().left().width(WELL_WIDTH).padLeft(COLUMN_GAP)
        body.add().expandX()
        val panel = ui.modulePanel(t("share.title"), status())
        panel.add(body).grow()
        val content = Table()
        content.add(panel).grow()
        return frame(content, footer())
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

    override fun onEscape() = back()

    private fun footer(): Table {
        val footer = Table()
        footer.add(ui.button(t("common.back"), Styles.TEXT) { back() }).left().height(FOOTER_BUTTON_HEIGHT).padLeft(8f)
        footer.add().expandX()
        val saved = result?.getOrNull()
        if (saved != null) {
            footer.addButton(ui.button(t("share.copy_path"), Styles.SECONDARY) { Gdx.app.clipboard.contents = saved.file.toString() }, minWidth = 220f).padRight(16f)
            footer.addButton(ui.button(t("share.open_folder"), Styles.SECONDARY) { Gdx.net.openURI(saved.file.parent.toUri().toString()) }, minWidth = 240f).padRight(16f)
        }
        val busy = export != null && result == null
        val button = ui.button(t("share.export"), Styles.PRIMARY) { startExport() }
        button.isDisabled = (stored?.rounds ?: 0) == 0 || busy
        footer.addButton(button, minWidth = 260f)
        return footer
    }

    /** The counts in the head of the module: lit once there is something to export. */
    private fun status(): ModuleStatus? {
        val s = stored ?: return null
        return if (s.rounds == 0) ModuleStatus(LedState.OFF, t("share.none")) else ModuleStatus(LedState.OK, t("share.counts", s.sessions, s.rounds))
    }

    private fun startExport() {
        if (export != null && result == null) return
        result = null
        export = ctx.exportResearch()
        rebuild()
    }

    /** What the file holds and what it does not hold, in a recessed panel. */
    private fun infoWell(): Table {
        val inner = WELL_WIDTH - 2 * WELL_PADDING
        val well = Table().top().left()
        well.background(ui.skin.getDrawable(Styles.WELL))
        well.pad(WELL_PADDING)
        well.add(ui.caps(t("share.contains_title"))).left().row()
        well.add(ui.label(t("share.contains"), Styles.BODY_MUTED, wrap = true)).left().width(inner).padTop(CAPTION_GAP).padBottom(TEXT_GAP * 1.5f).row()
        well.add(ui.caps(t("share.not_contains_title"))).left().row()
        well.add(ui.label(t("share.not_contains"), Styles.BODY_MUTED, wrap = true)).left().width(inner).padTop(CAPTION_GAP)
        return well
    }

    private fun statusLabel() = when {
        export != null && result == null -> ui.label(t("share.working"), Styles.BODY_MUTED)
        else -> result?.fold(
            { ui.label(t("share.done", it.file, it.sessions, it.rounds, it.bytes / BYTES_PER_KB), Styles.OK, wrap = true) },
            { ui.label(t("share.failed", it.message.orEmpty()), Styles.CAUTION, wrap = true) },
        )
    }
}
