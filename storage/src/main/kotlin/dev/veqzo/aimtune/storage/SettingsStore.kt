package dev.veqzo.aimtune.storage

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Reads and writes [AppSettings] as JSON. A corrupt file is reported, never silently replaced. */
class SettingsStore(private val file: Path) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** Defaults when the file does not exist yet. @throws SettingsException if it cannot be read or is invalid. */
    fun load(): AppSettings {
        if (Files.notExists(file)) return AppSettings()
        val settings = try {
            json.decodeFromString<AppSettings>(Files.readString(file))
        } catch (e: SerializationException) {
            throw SettingsException("settings file $file is not valid: ${e.message}", e)
        } catch (e: IOException) {
            throw SettingsException("settings file $file could not be read: ${e.message}", e)
        }
        settings.validate()
        return settings
    }

    /** Validates, then replaces the file atomically so a crash cannot leave a half-written file. */
    fun save(settings: AppSettings) {
        settings.validate()
        val directory = file.toAbsolutePath().parent
        Files.createDirectories(directory)
        val temp = Files.createTempFile(directory, "settings", ".tmp")
        try {
            Files.writeString(temp, json.encodeToString(AppSettings.serializer(), settings))
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: IOException) {
            Files.deleteIfExists(temp)
            throw SettingsException("settings file $file could not be written: ${e.message}", e)
        }
    }
}
