package dev.veqzo.aimtune.app.update

import dev.veqzo.aimtune.core.update.Version
import java.util.Properties

/** The version of this build: `version.properties` is filled from `-PappVersion` (the release tag) by `processResources`. */
object AppVersion {
    val version: Version = read() ?: Version(0, 0, 0)
    val current: String get() = version.toString()

    private fun read(): Version? = try {
        AppVersion::class.java.getResourceAsStream("/version.properties")?.use { stream ->
            Properties().also { it.load(stream) }.getProperty("version")?.let(Version::parse)
        }
    } catch (e: java.io.IOException) {
        null
    }
}
