package de.yuuto.aimtune.app.screens

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.ui.Table
import de.yuuto.aimtune.app.AppContext
import de.yuuto.aimtune.app.accel.PatchedChange
import de.yuuto.aimtune.app.accel.PatchedCopy
import de.yuuto.aimtune.app.accel.SettingsAnalyses
import de.yuuto.aimtune.app.files.ExportFiles
import de.yuuto.aimtune.app.ui.Styles
import de.yuuto.aimtune.app.ui.addButton
import de.yuuto.aimtune.app.ui.button
import de.yuuto.aimtune.app.ui.caps
import de.yuuto.aimtune.app.ui.frame
import de.yuuto.aimtune.app.ui.label
import de.yuuto.aimtune.storage.RawAccelExport
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

private const val TEXT_WIDTH = 1000f
private const val CARD_WIDTH = 900f
private const val CARD_PADDING = 32f
private const val FOOTER_BUTTON_HEIGHT = 64f

/**
 * The patched copy of the imported `settings.json`: the blind-tested refine result (if any) and the passive recommendations the
 * player ticked, each changed field listed, the passive ones marked "not blind-tested". Save as, the export folder or the
 * clipboard; nothing is written into Raw Accel's own folder.
 */
class RawAccelExportScreen(ctx: AppContext, private val analysesFuture: CompletableFuture<SettingsAnalyses>) : UiScreen(ctx) {
    private var analyses: SettingsAnalyses? = null
    private var exported: Result<Path>? = null

    private fun copy(): PatchedCopy? = ctx.hub.patchedCopy(ctx.trainerConfig()?.dpi ?: 0.0, analyses)

    override fun build(): Actor {
        val copy = copy()
        val content = Table()
        content.add(ui.label(t("hub.export.title"), Styles.HEADING)).left().padBottom(24f).row()
        if (analyses == null) {
            content.add(ui.label(t("hub.state.computing"), Styles.BODY_MUTED)).left()
        } else if (copy == null || !copy.hasChanges) {
            content.add(ui.label(t("hub.export.nothing"), Styles.BODY_MUTED, wrap = true)).left().width(TEXT_WIDTH)
        } else {
            content.add(ui.label(t("hub.export.intro", copy.exportName), Styles.BODY_MUTED, wrap = true)).left().width(TEXT_WIDTH).padBottom(24f).row()
            content.add(changesCard(copy)).left().width(CARD_WIDTH)
        }
        return frame(content, footer(copy))
    }

    private fun footer(copy: PatchedCopy?): Table {
        val footer = Table()
        footer.add(ui.button(t("common.back"), Styles.TEXT) { ctx.showRawAccelHub(analysesFuture) }).left().height(FOOTER_BUTTON_HEIGHT).padLeft(8f)
        footer.add().expandX()
        if (copy != null && copy.hasChanges && exported?.isSuccess != true) {
            footer.addButton(ui.button(t("accel.result.copy"), Styles.SECONDARY) { copyText(copy) }, minWidth = 260f).padRight(16f)
            footer.addButton(ui.button(t("common.save_as"), Styles.SECONDARY) { saveAs(copy) }, minWidth = 240f).padRight(16f)
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

    override fun onEscape() = ctx.showRawAccelHub(analysesFuture)

    private fun changesCard(copy: PatchedCopy): Table {
        val inner = CARD_WIDTH - 2 * CARD_PADDING
        val card = Table()
        card.background(ui.skin.getDrawable(Styles.CARD))
        card.pad(CARD_PADDING)
        card.add(ui.caps(t(if (exported?.isSuccess == true) "refine.result.changed_saved" else "refine.result.changed", copy.exportName))).left().row()
        copy.changes().forEach { card.add(ui.label(line(it), Styles.MONO, wrap = true)).left().width(inner).padTop(8f).row() }
        card.add(ui.label(t("hub.export.note"), Styles.CAPTION_MUTED, wrap = true)).left().width(inner).padTop(16f).row()
        exported?.let { result ->
            val text = result.fold({ t("accel.result.saved", it) }, { t("accel.result.save_failed", it.message.orEmpty()) })
            card.add(ui.label(text, if (result.isSuccess) Styles.OK else Styles.CAUTION, wrap = true)).left().width(inner).padTop(16f)
        }
        return card
    }

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
