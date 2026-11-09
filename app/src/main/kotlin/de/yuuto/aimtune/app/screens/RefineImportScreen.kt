package de.yuuto.aimtune.app.screens

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.ui.CheckBox
import com.badlogic.gdx.scenes.scene2d.ui.Table
import de.yuuto.aimtune.app.AppContext
import de.yuuto.aimtune.app.accel.ImportedProfile
import de.yuuto.aimtune.app.accel.RefineChoice
import de.yuuto.aimtune.app.accel.RefineDiff
import de.yuuto.aimtune.app.accel.RefineImport
import de.yuuto.aimtune.app.accel.RefineSource
import de.yuuto.aimtune.app.files.ImportFileReader
import de.yuuto.aimtune.app.files.ImportFileResult
import de.yuuto.aimtune.app.ui.ChartActor
import de.yuuto.aimtune.app.ui.Styles
import de.yuuto.aimtune.app.ui.addButton
import de.yuuto.aimtune.app.ui.button
import de.yuuto.aimtune.app.ui.caps
import de.yuuto.aimtune.app.ui.frame
import de.yuuto.aimtune.app.ui.label
import de.yuuto.aimtune.app.ui.onChange
import de.yuuto.aimtune.app.ui.profileChart
import de.yuuto.aimtune.core.accel.refineStages
import java.nio.file.Files
import java.nio.file.Path

private const val LEFT_WIDTH = 820f
private const val CARD_WIDTH = 900f
private const val CARD_PADDING = 28f
private const val CHART_HEIGHT = 250f
private const val FOOTER_BUTTON_HEIGHT = 64f
private const val MAX_FILE_BUTTONS = 4

/**
 * First step of "Refine my Raw Accel curve": the player's own `settings.json`, pasted from the clipboard or taken from the
 * import folder. Shows the profiles, what was read, the curve, why a profile is refused, and starts the run only for a profile
 * the trainer reproduces exactly at the DPI of the settings.
 */
class RefineImportScreen(ctx: AppContext, sampleText: String? = null, imported: ImportedProfile? = null) : UiScreen(ctx) {
    private var source: RefineSource? = null
    private var error: String? = null
    private var selected: String? = null
    private var path: Path? = null

    init {
        if (sampleText != null) {
            load(sampleText, "refine-sample.json", null)
        } else if (imported != null) {
            // The file imported earlier (also the one the hub read again from the settings) is still the choice.
            source = imported.source
            selected = imported.profileName
            path = imported.path
        }
    }

    private val settingsDpi: Double? get() = ctx.trainerConfig()?.dpi

    private fun choice(): RefineChoice? {
        val s = source ?: return null
        val name = selected ?: return null
        return RefineImport.choose(s, name, settingsDpi ?: return null)
    }

    override fun build(): Actor {
        val choice = choice()
        val content = Table()
        content.add(ui.label(t("refine.import.title"), Styles.HEADING)).left().colspan(2).padBottom(12f).row()
        content.add(ctx.runsUseLine(ctx.runsUse(), LEFT_WIDTH + CARD_WIDTH, { ctx.showRefine() })).left().width(LEFT_WIDTH + CARD_WIDTH + 40f).colspan(2).padBottom(16f).row()
        val left = Table().top().left()
        left.add(ui.label(t("refine.import.intro"), Styles.BODY_MUTED, wrap = true)).left().width(LEFT_WIDTH).padBottom(16f).row()
        left.add(sourceBlock()).left().width(LEFT_WIDTH).padBottom(16f).row()
        source?.let { left.add(profileBlock(it)).left().width(LEFT_WIDTH).padBottom(16f).row() }
        error?.let { left.add(ui.label(t("refine.import.error", it), Styles.CAUTION, wrap = true)).left().width(LEFT_WIDTH).padBottom(12f).row() }
        left.add(rawAccelCheck()).left().padBottom(12f).row()
        content.add(left).top().left()
        content.add(if (choice == null) hintCard() else profileCard(choice)).top().left().padLeft(40f)

        val footer = Table()
        footer.add(ui.button(t("common.back"), Styles.TEXT) { ctx.showRawAccelHub() }).left().height(FOOTER_BUTTON_HEIGHT).padLeft(8f)
        footer.add().expandX()
        val start = ui.button(t("refine.import.start"), Styles.PRIMARY) { start(choice) }
        start.isDisabled = !canStart(choice)
        footer.addButton(start, minWidth = 340f)
        return frame(content, footer)
    }

    override fun onEscape() = ctx.showRawAccelHub()

    override fun onEnter() = start(choice())

    private fun canStart(choice: RefineChoice?) = choice?.profile?.refineStages()?.isNotEmpty() == true && ctx.rawAccelConfirmed && ctx.source.hasReportTimestamps

    private fun start(choice: RefineChoice?) {
        val profile = choice?.profile ?: return
        val file = source ?: return
        if (canStart(choice)) ctx.startRefineRun(file, profile)
    }

    private fun sourceBlock(): Table {
        val block = Table().left()
        block.add(ui.button(t("refine.import.open"), Styles.PRIMARY) { openFile() }).left().height(FOOTER_BUTTON_HEIGHT).colspan(2 + MAX_FILE_BUTTONS).padBottom(12f).row()
        block.add(ui.button(t("refine.import.paste"), Styles.SECONDARY) { paste() }).left().height(FOOTER_BUTTON_HEIGHT).padRight(16f)
        val files = RefineImport.listFiles(ctx.importDir)
        files.take(MAX_FILE_BUTTONS).forEach { path ->
            block.add(ui.button(path.fileName.toString(), Styles.SECONDARY) { loadFile(path) }).left().height(FOOTER_BUTTON_HEIGHT).padRight(12f)
        }
        block.row()
        val folder = ctx.importDir?.toString() ?: t("refine.import.no_folder")
        val text = if (files.isEmpty()) t("refine.import.folder_empty", folder) else t("refine.import.folder", folder)
        block.add(ui.label(text, Styles.CAPTION_MUTED, wrap = true)).left().colspan(2 + MAX_FILE_BUTTONS).width(LEFT_WIDTH).padTop(8f)
        return block
    }

    private fun profileBlock(s: RefineSource): Table {
        val block = Table().left()
        block.add(ui.caps(t("refine.import.profiles", s.label))).left().colspan(8).padBottom(8f).row()
        s.file.profileNames.forEach { name ->
            val style = if (name == selected) Styles.PRIMARY else Styles.SECONDARY
            block.add(ui.button(name, style) { selected = name; ctx.importProfile(ImportedProfile(s, name, path)); rebuild() }).left().height(FOOTER_BUTTON_HEIGHT).padRight(12f)
        }
        return block
    }

    private fun hintCard(): Table {
        val card = Table()
        card.background(ui.skin.getDrawable(Styles.CARD))
        card.pad(CARD_PADDING)
        card.add(ui.label(t("refine.import.hint"), Styles.BODY_MUTED, wrap = true)).left().width(CARD_WIDTH - 2 * CARD_PADDING)
        return card
    }

    private fun profileCard(choice: RefineChoice): Table {
        val card = Table()
        val refused = choice.profile == null
        card.background(ui.skin.getDrawable(if (refused) Styles.CARD_CAUTION else Styles.CARD))
        card.pad(CARD_PADDING)
        val inner = CARD_WIDTH - 2 * CARD_PADDING
        val profile = choice.profile
        if (profile != null) {
            card.add(ui.caps(t("refine.import.parsed"))).left().row()
            card.add(ui.label(RefineDiff.describe(profile), Styles.MONO, wrap = true)).left().width(inner).padTop(8f).row()
            card.add(ui.label(t("refine.import.factor", "%.3f".format(java.util.Locale.ROOT, profile.outputDpi / profile.effectiveDeviceDpi)), Styles.CAPTION_MUTED, wrap = true))
                .left().width(inner).padTop(8f).row()
            card.add(ChartActor(ui, profileChart(ui.tokens, profile))).left().width(inner).height(CHART_HEIGHT).padTop(12f).row()
            if (profile.refineStages().isEmpty()) card.add(ui.label(t("refine.import.nothing"), Styles.CAUTION, wrap = true)).left().width(inner).padTop(12f).row()
        } else {
            card.add(ui.caps(t("refine.import.refused"))).left().row()
        }
        choice.dpiMismatch?.let { dpi ->
            val shown = settingsDpi?.let { "%.0f".format(java.util.Locale.ROOT, it) } ?: "-"
            card.add(ui.label(t("refine.import.dpi_mismatch", dpi, shown), Styles.CAUTION, wrap = true)).left().width(inner).padTop(12f).row()
        }
        choice.refusals.forEach { card.add(ui.label("- $it", Styles.CAUTION, wrap = true)).left().width(inner).padTop(6f).row() }
        choice.warnings.forEach { card.add(ui.label("- $it", Styles.CAPTION_MUTED, wrap = true)).left().width(inner).padTop(6f).row() }
        return card
    }

    private fun rawAccelCheck(): Table {
        val block = Table().left()
        val box = CheckBox("  " + t("pretest.raw_accel_checked"), ui.skin)
        box.isChecked = ctx.rawAccelConfirmed
        box.onChange {
            ctx.rawAccelConfirmed = box.isChecked
            rebuild()
        }
        block.add(box).left().row()
        block.add(ui.label(t("refine.import.raw_accel_off"), Styles.CAPTION_MUTED, wrap = true)).left().width(LEFT_WIDTH).padTop(6f)
        return block
    }

    /** Asks for a file with the native dialog; a cancelled dialog changes nothing. */
    private fun openFile() {
        val path = ctx.fileDialogs.openJson(t("file.dialog.open_title"), ctx.importDir) ?: return
        when (val result = ImportFileReader.read(path)) {
            is ImportFileResult.Ok -> load(result.text, path.fileName.toString(), path)
            is ImportFileResult.Unreadable -> error = t("refine.import.file_unreadable", result.reason)
            ImportFileResult.TooLarge -> error = t("refine.import.file_too_large")
            ImportFileResult.NotJson -> error = t("refine.import.file_not_json")
            is ImportFileResult.NotRawAccel -> error = t("refine.import.file_not_raw_accel", result.reason)
        }
        rebuild()
    }

    private fun paste() {
        val text = try {
            Gdx.app.clipboard.contents
        } catch (e: RuntimeException) {
            null
        }
        if (text.isNullOrBlank()) {
            error = t("refine.import.clipboard_empty")
            rebuild()
        } else {
            load(text, t("refine.import.clipboard"), null)
            rebuild()
        }
    }

    private fun loadFile(path: Path) {
        try {
            load(Files.readString(path), path.fileName.toString(), path)
        } catch (e: java.io.IOException) {
            error = e.message.orEmpty()
        }
        rebuild()
    }

    /** Reads [text]; a file that parses becomes the imported profile of the hub (its [file] is remembered in the settings), a clipboard text has none. */
    private fun load(text: String, label: String, file: Path?) {
        RefineImport.parse(text, label).fold(
            {
                source = it
                path = file
                error = null
                selected = it.file.profileNames.firstOrNull()
                selected?.let { name -> ctx.importProfile(ImportedProfile(it, name, file)) }
            },
            { error = it.message.orEmpty() },
        )
    }
}
