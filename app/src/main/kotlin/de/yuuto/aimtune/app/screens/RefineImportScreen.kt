package de.yuuto.aimtune.app.screens

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.ui.Table
import de.yuuto.aimtune.app.AppContext
import de.yuuto.aimtune.app.accel.ImportedProfile
import de.yuuto.aimtune.app.accel.RefineChoice
import de.yuuto.aimtune.app.accel.RefineDiff
import de.yuuto.aimtune.app.accel.RefineImport
import de.yuuto.aimtune.app.accel.RefineSource
import de.yuuto.aimtune.app.files.ImportFileReader
import de.yuuto.aimtune.app.files.ImportFileResult
import de.yuuto.aimtune.app.pretest.PreTestChecks
import de.yuuto.aimtune.app.ui.LedState
import de.yuuto.aimtune.app.ui.ModuleStatus
import de.yuuto.aimtune.app.ui.Styles
import de.yuuto.aimtune.app.ui.button
import de.yuuto.aimtune.app.ui.caps
import de.yuuto.aimtune.app.ui.label
import de.yuuto.aimtune.app.ui.primaryAction
import de.yuuto.aimtune.app.ui.profileChart
import de.yuuto.aimtune.core.accel.refineStages
import de.yuuto.aimtune.input.windows.WindowsPointerSettings
import de.yuuto.aimtune.input.windows.WindowsRawInputSource
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

private const val SIDE_WIDTH = 480f
private const val SIDE_GAP = 40f
private const val BUTTON_HEIGHT = 56f
private const val BUTTON_GAP = 12f
private const val MAX_FILE_BUTTONS = 4
private const val PROFILES_PER_ROW = 3

/**
 * First step of "Refine my Raw Accel curve", in the curve layout: the player's own `settings.json`, opened, pasted from
 * the clipboard or taken from the import folder, and its profiles on the left with the start; the curve of the chosen
 * profile on the display, what was read and why a profile is refused under it. The run starts only for a profile the
 * trainer reproduces exactly at the DPI of the settings.
 */
class RefineImportScreen(ctx: AppContext, sampleText: String? = null, imported: ImportedProfile? = null) : AccelFitScreen(ctx) {
    private var source: RefineSource? = null
    private var error: String? = null
    private var selected: String? = null
    private var path: Path? = null
    private var pointerAcceleration = WindowsPointerSettings.readAcceleration()
    private var checkOpen = false

    init {
        if (sampleText != null) {
            load(sampleText, "refine-sample.json", null)
        } else if (imported != null) {
            // The file imported earlier (also the one the dashboard read again from the settings) is still the choice.
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
        val animate = firstBuild()
        val choice = choice()
        val left = Table()
        left.headline(t("refine.import.title"))
        left.text(t("refine.import.intro"))
        left.add(sourceBlock()).left().width(CURVE_LEFT_WIDTH).padTop(CURVE_SECTION_GAP).row()
        source?.let { left.add(profileBlock(it)).left().width(CURVE_LEFT_WIDTH).padTop(CURVE_SECTION_GAP).row() }
        error?.let { left.text(t("refine.import.error", it), Styles.CAUTION) }
        left.add(start(choice)).left().padTop(CURVE_SECTION_GAP).row()

        val content = Table()
        content.add(curvePanel(status(choice), left, right(choice, animate))).grow()
        return frame(content, curveFooter(t("common.back")) { ctx.showMenu() })
    }

    override fun onEscape() = ctx.showMenu()

    override fun onEnter() = begin(choice())

    private fun status(choice: RefineChoice?): ModuleStatus = when {
        choice == null -> ModuleStatus(LedState.OFF, t("curve.status.no_file"))
        choice.profile?.refineStages().isNullOrEmpty() -> ModuleStatus(LedState.CAUTION, t("curve.status.refused"))
        !ctx.source.hasReportTimestamps -> ModuleStatus(LedState.CAUTION, t("curve.status.no_input"))
        !ctx.rawAccelConfirmed -> ModuleStatus(LedState.CAUTION, t("curve.status.check"))
        else -> ModuleStatus(LedState.OK, t("curve.status.ready"))
    }

    private fun canStart(choice: RefineChoice?) = choice?.profile?.refineStages()?.isNotEmpty() == true && ctx.rawAccelConfirmed && ctx.source.hasReportTimestamps

    private fun begin(choice: RefineChoice?) {
        val profile = choice?.profile ?: return
        val file = source ?: return
        if (canStart(choice)) ctx.startRefineRun(file, profile)
    }

    /** The checks as chips and the start; it stays off until a profile can be refined and Raw Accel is confirmed off. */
    private fun start(choice: RefineChoice?): Table {
        val rows = PreTestChecks.rows(pointerAcceleration, ctx.rawAccelConfirmed, ctx.source.name, ctx.source is WindowsRawInputSource)
        val chips = rows.map { row -> ctx.checkChip(row, { pointerAcceleration = WindowsPointerSettings.readAcceleration() }, ::rebuild) }
        val detail = ctx.checkDetail(ui, rows, CURVE_LEFT_WIDTH, checkOpen) { checkOpen = it }
        return ui.primaryAction(chips, detail, t("refine.import.start"), CURVE_LEFT_WIDTH, canStart(choice)) { begin(choice) }
    }

    /** Open and paste side by side, the files of the import folder as quieter buttons under them, then where that folder is. */
    private fun sourceBlock(): Table {
        val block = Table().left()
        val half = (CURVE_LEFT_WIDTH - BUTTON_GAP) / 2
        block.add(ui.button(t("refine.import.open"), Styles.SECONDARY) { openFile() }).left().width(half).height(BUTTON_HEIGHT).padRight(BUTTON_GAP)
        block.add(ui.button(t("refine.import.paste"), Styles.SECONDARY) { paste() }).left().width(half).height(BUTTON_HEIGHT).row()
        val files = RefineImport.listFiles(ctx.importDir)
        files.take(MAX_FILE_BUTTONS).forEach { path ->
            val button = ui.button(path.fileName.toString(), Styles.TEXT) { loadFile(path) }
            button.pad(0f)
            block.add(button).left().colspan(2).padTop(CURVE_SMALL_GAP).row()
        }
        val folder = ctx.importDir?.toString() ?: t("refine.import.no_folder")
        val text = if (files.isEmpty()) t("refine.import.folder_empty", folder) else t("refine.import.folder", folder)
        block.add(ui.label(text, Styles.CAPTION_MUTED, wrap = true)).left().colspan(2).width(CURVE_LEFT_WIDTH).padTop(CURVE_SMALL_GAP)
        return block
    }

    /** The profiles of the file as buttons, the chosen one marked, a few to a row. */
    private fun profileBlock(s: RefineSource): Table {
        val block = Table().left()
        block.add(ui.caps(t("refine.import.profiles", s.label))).left().colspan(PROFILES_PER_ROW).padBottom(CURVE_SMALL_GAP).row()
        val width = (CURVE_LEFT_WIDTH - BUTTON_GAP * (PROFILES_PER_ROW - 1)) / PROFILES_PER_ROW
        s.file.profileNames.forEachIndexed { index, name ->
            val style = if (name == selected) Styles.PRIMARY else Styles.SECONDARY
            val cell = block.add(ui.button(name, style) { selected = name; ctx.importProfile(ImportedProfile(s, name, path)); rebuild() })
                .left().width(width).height(BUTTON_HEIGHT).padBottom(BUTTON_GAP)
            if (index % PROFILES_PER_ROW == PROFILES_PER_ROW - 1) cell.row() else cell.padRight(BUTTON_GAP)
        }
        return block
    }

    /** The display with the chosen profile; under it what was read beside the sensitivity of the runs and why Raw Accel must be off. */
    private fun right(choice: RefineChoice?, animate: Boolean): Table {
        val profile = choice?.profile
        val display = curveDisplay(t("curve.display.own"), profile?.let { profileChart(ui.tokens, it) }, animate, t("curve.display.empty_title"), t("refine.import.hint"))
        val below = Table()
        below.add(choice?.let(::facts) ?: Table()).top().left().width(SIDE_WIDTH)
        val setup = Table().top().left()
        setup.add(ctx.runsUseBlock(ctx.runsUse(), SIDE_WIDTH) { ctx.showRefine() }).left().width(SIDE_WIDTH).row()
        setup.add(ui.label(t("refine.import.raw_accel_off"), Styles.CAPTION_MUTED, wrap = true)).left().width(SIDE_WIDTH).padTop(CURVE_SECTION_GAP)
        below.add(setup).top().left().width(SIDE_WIDTH).padLeft(SIDE_GAP)
        below.add().expandX()
        val right = Table()
        right.add(display).grow().row()
        right.add(below).growX().padTop(CURVE_SECTION_GAP)
        return right
    }

    /** What was read from the chosen profile, or why it is refused, with the warnings. */
    private fun facts(choice: RefineChoice): Table {
        val block = Table().top().left()
        val profile = choice.profile
        if (profile != null) {
            block.add(ui.caps(t("refine.import.parsed"))).left().row()
            block.add(ui.label(RefineDiff.describe(profile), Styles.MONO, wrap = true)).left().width(SIDE_WIDTH).padTop(CURVE_SMALL_GAP).row()
            val factor = "%.3f".format(Locale.ROOT, profile.outputDpi / profile.effectiveDeviceDpi)
            block.add(ui.label(t("refine.import.factor", factor), Styles.CAPTION_MUTED, wrap = true)).left().width(SIDE_WIDTH).padTop(CURVE_SMALL_GAP).row()
            if (profile.refineStages().isEmpty()) block.add(ui.label(t("refine.import.nothing"), Styles.CAUTION, wrap = true)).left().width(SIDE_WIDTH).padTop(CURVE_SMALL_GAP).row()
        } else {
            block.add(ui.caps(t("refine.import.refused"))).left().row()
        }
        choice.dpiMismatch?.let { dpi ->
            val shown = settingsDpi?.let { "%.0f".format(Locale.ROOT, it) } ?: "-"
            block.add(ui.label(t("refine.import.dpi_mismatch", dpi, shown), Styles.CAUTION, wrap = true)).left().width(SIDE_WIDTH).padTop(CURVE_SMALL_GAP).row()
        }
        choice.refusals.forEach { block.add(ui.label("- $it", Styles.CAUTION, wrap = true)).left().width(SIDE_WIDTH).padTop(CURVE_SMALL_GAP).row() }
        choice.warnings.forEach { block.add(ui.label("- $it", Styles.CAPTION_MUTED, wrap = true)).left().width(SIDE_WIDTH).padTop(CURVE_SMALL_GAP).row() }
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
        } else {
            load(text, t("refine.import.clipboard"), null)
        }
        rebuild()
    }

    private fun loadFile(path: Path) {
        try {
            load(Files.readString(path), path.fileName.toString(), path)
        } catch (e: java.io.IOException) {
            error = e.message.orEmpty()
        }
        rebuild()
    }

    /** Reads [text]; a file that parses becomes the imported profile of the dashboard (its [file] is remembered in the settings), a clipboard text has none. */
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
