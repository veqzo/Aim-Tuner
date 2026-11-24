package dev.veqzo.aimtune.app.accel

import dev.veqzo.aimtune.core.accel.RawAccelProfile
import dev.veqzo.aimtune.storage.RawAccelFile
import dev.veqzo.aimtune.storage.RawAccelImport
import dev.veqzo.aimtune.storage.RawAccelImportException
import dev.veqzo.aimtune.storage.RawAccelImportResult
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs

/** A `settings.json` the player brought in, with the place it came from for the screen. */
class RefineSource(val label: String, val file: RawAccelFile)

/**
 * What choosing one profile of a [RefineSource] gave. [profile] is set only when the trainer can play it exactly and the file's
 * device DPI is the DPI of the settings; [refusals] are the reasons in English (diagnostics stay English), [dpiMismatch] the
 * file's device DPI when it differs from the settings (the screen words that one in the player's language).
 */
class RefineChoice(
    val profile: RawAccelProfile?,
    val refusals: List<String>,
    val warnings: List<String>,
    val dpiMismatch: Int? = null,
)

object RefineImport {
    /** @return the parsed file, or the reason the text is not a Raw Accel settings file. */
    fun parse(text: String, label: String): Result<RefineSource> = try {
        Result.success(RefineSource(label, RawAccelImport.parse(text)))
    } catch (e: RawAccelImportException) {
        Result.failure(e)
    }

    /**
     * Imports [name] of [source]. The curve's speed axis belongs to the DPI in the file and the trainer plays at the DPI of the
     * settings, so a different DPI (or 0, no normalization) is refused: the curve would act at other speeds than in the game.
     */
    fun choose(source: RefineSource, name: String, settingsDpi: Double): RefineChoice {
        val dpi = source.file.deviceDpi
        val mismatch = if (dpi == 0 || abs(dpi - settingsDpi) > 0.5) dpi else null
        val result = try {
            RawAccelImport.profile(source.file, name, settingsDpi)
        } catch (e: RawAccelImportException) {
            return RefineChoice(null, listOf(e.message.orEmpty()), emptyList(), mismatch)
        }
        return when (result) {
            is RawAccelImportResult.Imported -> RefineChoice(result.profile.takeIf { mismatch == null }, emptyList(), result.warnings, mismatch)
            is RawAccelImportResult.Refused -> RefineChoice(null, result.reasons, result.warnings, mismatch)
        }
    }

    /** The `*.json` files in [dir], by name; empty when the folder does not exist or cannot be read. */
    fun listFiles(dir: Path?): List<Path> = try {
        if (dir == null || !Files.isDirectory(dir)) {
            emptyList()
        } else {
            Files.list(dir).use { stream -> stream.filter { it.fileName.toString().endsWith(".json", ignoreCase = true) }.sorted().toList() }
        }
    } catch (e: java.io.IOException) {
        emptyList()
    }
}
