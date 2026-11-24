package dev.veqzo.aimtune.app.files

import dev.veqzo.aimtune.storage.RawAccelExport
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Writes an export to a path the player chose. The dialog has already asked about overwriting, so an existing file is replaced. */
object ExportFiles {
    /** The name [RawAccelExport.write] gives a patched copy, so the save dialog suggests what the export button would write. */
    fun patchedFileName(profileName: String): String =
        "settings-" + profileName.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_').ifEmpty { "profile" } + ".json"

    fun saveText(path: Path, text: String): Result<Path> = runCatching {
        path.toAbsolutePath().parent?.let { Files.createDirectories(it) }
        Files.writeString(path, text)
        path
    }

    /** Writes the patched [root] to [path] in the format of [RawAccelExport.write], which only takes a folder, so it goes through a temp folder. */
    fun savePatched(path: Path, root: JsonObject, profileName: String): Result<Path> = runCatching {
        val temp = Files.createTempDirectory("aimtune-export")
        try {
            val written = RawAccelExport.write(temp, root, profileName)
            path.toAbsolutePath().parent?.let { Files.createDirectories(it) }
            Files.move(written, path, StandardCopyOption.REPLACE_EXISTING)
            path
        } finally {
            temp.toFile().deleteRecursively()
        }
    }
}
