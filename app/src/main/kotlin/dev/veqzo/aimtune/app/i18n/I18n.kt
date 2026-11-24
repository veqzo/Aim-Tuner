package dev.veqzo.aimtune.app.i18n

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.utils.I18NBundle
import java.util.Locale
import java.util.MissingResourceException

/** The languages the app is translated to; [code] is what the settings file stores. */
enum class Language(val code: String, val displayName: String, val locale: Locale) {
    ENGLISH("en", "English", Locale.ROOT),
    GERMAN("de", "Deutsch", Locale.GERMAN),
    ;

    companion object {
        /** The stored [code], or English for null and unknown codes; the app never guesses from the system locale. */
        fun fromCode(code: String?): Language = entries.firstOrNull { it.code == code } ?: ENGLISH
    }
}

/**
 * Texts of the UI from the bundles in `assets/i18n` (LibGDX [I18NBundle]; English is the base bundle).
 * Arguments replace `{0}`, `{1}`, ... and are formatted by the caller, so numbers keep a stable
 * decimal format. A missing key shows as `!key!` instead of crashing a screen; a test keeps the bundles in step.
 */
class I18n private constructor(val language: Language, private val bundle: I18NBundle) {
    fun t(key: String, vararg args: Any?): String = try {
        if (args.isEmpty()) bundle.get(key) else bundle.format(key, *args)
    } catch (e: MissingResourceException) {
        "!$key!"
    }

    companion object {
        private const val BASE = "assets/i18n/messages"

        /** Loads [language]; needs LibGDX to be initialized. */
        fun load(language: Language): I18n {
            // Plain {0} replacement: MessageFormat would treat apostrophes in German texts as quotes. Read when the bundle is created.
            I18NBundle.setSimpleFormatter(true)
            // Locale.ROOT selects the base bundle directly; any other locale would first fall back to the system's language.
            val bundle = I18NBundle.createBundle(Gdx.files.classpath(BASE), language.locale)
            return I18n(language, bundle)
        }
    }
}
