package dev.veqzo.aimtune.app.accel

import dev.veqzo.aimtune.core.accel.RawAccelProfile
import java.io.InputStreamReader
import java.util.Properties

/** The real bundles without LibGDX: the same `{0}` replacement as the app, so tests read the texts a player would. */
internal fun bundle(file: String): Translate {
    val stream = checkNotNull(RawAccelSetting::class.java.classLoader.getResourceAsStream("assets/i18n/$file")) { "missing bundle $file" }
    val properties = Properties().apply { InputStreamReader(stream, Charsets.UTF_8).use(::load) }
    return { key, args ->
        val text = checkNotNull(properties.getProperty(key)) { "no text for $key" }
        args.foldIndexed(text) { i, acc, arg -> acc.replace("{$i}", arg.toString()) }
    }
}

/** The sample `settings.json` of the screenshot tour, imported at 800 DPI. */
internal fun sampleImport(): ImportedProfile {
    val source = RefineImport.parse(checkNotNull(sampleRefineText()), "settings.json").getOrThrow()
    return ImportedProfile(source, source.file.profileNames.first())
}

internal fun sampleProfile(): RawAccelProfile = checkNotNull(sampleImport().profile(800.0))
