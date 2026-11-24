package de.yuuto.aimtune.app.screens

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.ui.Table
import de.yuuto.aimtune.app.AppContext
import de.yuuto.aimtune.app.accel.PatchedChange
import de.yuuto.aimtune.app.accel.PatchedCopy
import de.yuuto.aimtune.app.accel.SettingsAnalyses
import de.yuuto.aimtune.app.files.ExportFiles
import de.yuuto.aimtune.app.ui.LedState
import de.yuuto.aimtune.app.ui.ModuleStatus
import de.yuuto.aimtune.app.ui.Styles
import de.yuuto.aimtune.app.ui.addButton
import de.yuuto.aimtune.app.ui.button
import de.yuuto.aimtune.app.ui.label
import de.yuuto.aimtune.storage.RawAccelExport
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

private const val WELL_INNER_WIDTH = 900f
private const val ACTION_GAP = 16f

/**
 * The patched copy of the imported `settings.json`, in the curve layout: the blind-tested refine result (if any) and the
 * passive recommendations the player ticked. Left: what the copy is and whether it was saved; right, in a recessed panel,
 * each changed field, the passive ones marked "not blind-tested". Save as, the export folder or the clipboard; nothing is
 * written into Raw Accel's own folder.
 */
class RawAccelExportScreen(ctx: AppContext, private val analysesFuture: CompletableFuture<SettingsAnalyses>) : AccelFitScreen(ctx) {
    private var analyses: SettingsAnalyses? = null
    private var exported: Result<Path>? = null

    private fun copy(): PatchedCopy? = ctx.hub.patchedCopy(ctx.trainerConfig()?.dpi ?: 0.0, analyses)

    override fun build(): Actor {
        val copy = copy()
        val changes = copy?.takeIf { it.hasChanges }
        val left = Table()
        left.headline(t("hub.export.title"))
        when {
            analyses == null -> left.text(t("hub.state.computing"))
            changes == null -> left.text(t("hub.export.nothing"))
            else -> {
                left.text(t("hub.export.intro", changes.exportName))
                left.text(t("hub.export.note"), Styles.CAPTION_MUTED, CURVE_SECTION_GAP)
            }
        }
        exported?.let { result ->
            val text = result.fold({ t("accel.result.saved", it) }, { t("accel.result.save_failed", it.message.orEmpty()) })
            left.text(text, if (result.isSuccess) Styles.OK else Styles.CAUTION, CURVE_SECTION_GAP)
        }

        val status = if (changes == null) ModuleStatus(LedState.OFF, t("curve.status.no_changes")) else ModuleStatus(LedState.OK, t("curve.status.copy_ready"))
        val content = Table()
        content.add(curvePanel(status, left, right(changes))).grow()
        return frame(content, footer(changes))
    }

    /** The changed fields of the copy, one per line. */
    private fun right(copy: PatchedCopy?): Table {
        val well = infoWell()
        if (copy == null) {
            well.add(ui.label(t("hub.export.empty"), Styles.CAPTION_MUTED, wrap = true)).left().width(WELL_INNER_WIDTH).row()
        } else {
            val title = t(if (exported?.isSuccess == true) "refine.result.changed_saved" else "refine.result.changed", copy.exportName)
            well.add(ui.label(title, Styles.LABEL, wrap = true)).left().width(WELL_INNER_WIDTH).row()
            copy.changes().forEach { well.add(ui.label(line(it), Styles.MONO, wrap = true)).left().width(WELL_INNER_WIDTH).padTop(CURVE_SMALL_GAP).row() }
        }
        well.add().expand()
        return well
    }

    private fun footer(copy: PatchedCopy?): Table {
        val footer = curveFooter(t("common.back")) { back() }
        if (copy != null && exported?.isSuccess != true) {
            footer.addButton(ui.button(t("accel.result.copy"), Styles.SECONDARY) { copyText(copy) }, minWidth = 260f).padRight(ACTION_GAP)
            footer.addButton(ui.button(t("common.save_as"), Styles.SECONDARY) { saveAs(copy) }, minWidth = 240f).padRight(ACTION_GAP)
            footer.addButton(ui.button(t("refine.result.export", copy.exportName), Styles.PRIMARY) { export(copy) }, minWidth = 420f)
        }
        return footer
    }

    override fun render(delta: Float) {
        if (analyses == null && analysesFuture.isDone) {
            analyses = runCatching { analysesFuture.get() }.getOrNull()
            rebuild()
        }
        super.render(delta)
    }

    override fun onEscape() = back()

    private fun back() = ctx.showMenu(curveBack = true)

    private fun line(change: PatchedChange): String {
        val text = "${change.field}:  ${change.old}  ->  ${change.new}"
        return if (change.passive) "$text   (${t("hub.export.not_tested")})" else text
    }

    private fun copyText(copy: PatchedCopy) {
        runCatching { copy.root() }.fold(
            { Gdx.app.clipboard.contents = RawAccelExport.patchedText(it) },
            { exported = Result.failure(it); rebuild() },
        )
    }

    /** Asks where to save; a cancelled dialog changes nothing. */
    private fun saveAs(copy: PatchedCopy) {
        val path = ctx.fileDialogs.saveJson(t("file.dialog.save_title"), ctx.exportDir?.resolve(ExportFiles.patchedFileName(copy.exportName))) ?: return
        exported = ctx.savePatchedAs(copy, path)
        rebuild()
    }

    private fun export(copy: PatchedCopy) {
        exported = ctx.exportPatched(copy)
        rebuild()
    }
}
