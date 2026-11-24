package dev.veqzo.aimtune.app.files

import dev.veqzo.aimtune.app.accel.RefineImport
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** What reading a file the player chose gave. */
sealed interface ImportFileResult {
    /** A readable Raw Accel `settings.json`; [text] is its content. */
    data class Ok(val text: String) : ImportFileResult

    /** The file could not be opened or read; [reason] is the system's wording. */
    data class Unreadable(val reason: String) : ImportFileResult

    data object TooLarge : ImportFileResult

    data object NotJson : ImportFileResult

    /** Valid JSON that is not a Raw Accel settings file; [reason] is the importer's English diagnostic. */
    data class NotRawAccel(val reason: String) : ImportFileResult
}

/** Reads the file chosen in the open dialog and checks it before it reaches the importer. */
object ImportFileReader {
    /** A real `settings.json` is a few KB; anything near a megabyte is the wrong file and not worth reading into memory. */
    const val MAX_BYTES = 1_000_000L

    fun read(path: Path): ImportFileResult {
        val text = try {
            if (Files.size(path) > MAX_BYTES) return ImportFileResult.TooLarge
            Files.readString(path)
        } catch (e: IOException) {
            return ImportFileResult.Unreadable(e.message ?: e.javaClass.simpleName)
        }
        try {
            Json.parseToJsonElement(text)
        } catch (e: SerializationException) {
            return ImportFileResult.NotJson
        }
        return RefineImport.parse(text, path.fileName.toString()).fold(
            { ImportFileResult.Ok(text) },
            { ImportFileResult.NotRawAccel(it.message.orEmpty()) },
        )
    }
}
