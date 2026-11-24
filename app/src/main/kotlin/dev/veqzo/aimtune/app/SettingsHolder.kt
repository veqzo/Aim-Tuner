package dev.veqzo.aimtune.app

import dev.veqzo.aimtune.storage.AppSettings
import dev.veqzo.aimtune.storage.SettingsException
import dev.veqzo.aimtune.storage.SettingsStore

/** The settings in use, persisted through [store] (or kept only in memory when it is null). */
class SettingsHolder(private val store: SettingsStore?, initial: AppSettings) {
    var current: AppSettings = initial
        private set

    /** Validates and saves [settings]. Returns null on success, or a message for the user. */
    fun save(settings: AppSettings): String? = try {
        store?.save(settings)
        current = settings
        null
    } catch (e: SettingsException) {
        e.message ?: "Settings could not be saved."
    }

    companion object {
        /**
         * Loads from [store]. An unreadable or invalid file is reported in [loadProblem]; the app then
         * starts with defaults but never overwrites the file until the user saves again.
         */
        fun load(store: SettingsStore): Pair<SettingsHolder, String?> = try {
            SettingsHolder(store, store.load()) to null
        } catch (e: SettingsException) {
            SettingsHolder(store, AppSettings()) to e.message
        }
    }
}
