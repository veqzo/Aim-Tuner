package dev.veqzo.aimtune.app.accel

import dev.veqzo.aimtune.core.accel.RawAccelProfile
import dev.veqzo.aimtune.storage.RawAccelImport
import dev.veqzo.aimtune.storage.RawAccelImportException
import dev.veqzo.aimtune.storage.RawAccelImportResult
import java.nio.file.Path

/** The Raw Accel profile the player imported last: the parsed file, the chosen profile and, for a file import, its [path]. */
class ImportedProfile(val source: RefineSource, val profileName: String, val path: Path? = null) {
    /** The file's name, or the label of the source (the clipboard). */
    val fileLabel: String get() = path?.fileName?.toString() ?: source.label

    /**
     * The profile as the driver would run it, read at [dpi] of the settings; null when Raw Accel itself would refuse it. A device
     * DPI that differs from the settings does not matter here: the hub only shows values and patches the file.
     */
    fun profile(dpi: Double): RawAccelProfile? = try {
        (RawAccelImport.profile(source.file, profileName, dpi) as? RawAccelImportResult.Imported)?.profile
    } catch (e: RawAccelImportException) {
        null
    }
}
