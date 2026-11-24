package dev.veqzo.aimtune.storage

import dev.veqzo.aimtune.core.crosshair.Crosshair
import dev.veqzo.aimtune.core.crosshair.CrosshairCode
import dev.veqzo.aimtune.core.crosshair.CrosshairCodeException
import kotlinx.serialization.Serializable

/**
 * A named crosshair. It is stored as its Valorant profile code, so it can be pasted back into the game
 * and keeps every key AimTune does not know (docs/CROSSHAIR.md).
 */
@Serializable
data class SavedCrosshair(val id: String, val name: String, val code: String) {
    /** The primary crosshair of [code]; the default crosshair when the code cannot be read (settings are validated on save). */
    fun crosshair(): Crosshair = runCatching { Crosshair.fromCode(CrosshairCode.parse(code)) }.getOrDefault(Crosshair())
}

/** @throws SettingsException describing the first crosshair problem: a name, an id, an unreadable code or an unknown active crosshair. */
internal fun AppSettings.validateCrosshairs() {
    val ids = crosshairs.map { it.id }
    if (ids.size != ids.toSet().size) throw SettingsException("crosshair ids must be unique")
    for (c in crosshairs) {
        if (c.id.isBlank()) throw SettingsException("a crosshair has no id")
        if (c.name.isBlank() || c.name.length > MAX_CROSSHAIR_NAME) throw SettingsException("a crosshair name must have 1-$MAX_CROSSHAIR_NAME characters")
        try {
            Crosshair.fromCode(CrosshairCode.parse(c.code))
        } catch (e: CrosshairCodeException) {
            throw SettingsException("crosshair '${c.name}': ${e.message}", e)
        }
    }
    if (activeCrosshair != null && activeCrosshair !in ids) throw SettingsException("unknown active crosshair '$activeCrosshair'")
}

const val MAX_CROSSHAIR_NAME = 40

/** The crosshair every scenario draws: the selected profile, the first one when none is selected, or the default crosshair when there are none. */
fun AppSettings.activeCrosshair(): Crosshair = activeSavedCrosshair()?.crosshair() ?: Crosshair()

fun AppSettings.activeSavedCrosshair(): SavedCrosshair? = crosshairs.firstOrNull { it.id == activeCrosshair } ?: crosshairs.firstOrNull()

/** Adds a crosshair with the given [code] (already checked by the caller) and selects it. */
fun AppSettings.withNewCrosshair(name: String, code: String): AppSettings {
    val id = nextCrosshairId()
    return copy(crosshairs = crosshairs + SavedCrosshair(id, name.trim(), code.trim()), activeCrosshair = id)
}

fun AppSettings.withCrosshairCode(id: String, code: String): AppSettings =
    copy(crosshairs = crosshairs.map { if (it.id == id) it.copy(code = code.trim()) else it })

fun AppSettings.withCrosshairRenamed(id: String, name: String): AppSettings =
    copy(crosshairs = crosshairs.map { if (it.id == id) it.copy(name = name.trim()) else it })

/** A copy of crosshair [id] named "<name> copy", selected. */
fun AppSettings.withCrosshairDuplicated(id: String): AppSettings {
    val source = crosshairs.firstOrNull { it.id == id } ?: return this
    return withNewCrosshair("${source.name.take(MAX_CROSSHAIR_NAME - COPY_SUFFIX.length)}$COPY_SUFFIX", source.code)
}

/** Removes crosshair [id]; when it was the selected one, the first remaining one is selected (or none). */
fun AppSettings.withCrosshairDeleted(id: String): AppSettings {
    val remaining = crosshairs.filter { it.id != id }
    return copy(crosshairs = remaining, activeCrosshair = if (activeCrosshair == id || remaining.none { it.id == activeCrosshair }) remaining.firstOrNull()?.id else activeCrosshair)
}

fun AppSettings.withCrosshairSelected(id: String): AppSettings = if (crosshairs.any { it.id == id }) copy(activeCrosshair = id) else this

private fun AppSettings.nextCrosshairId(): String {
    val highest = crosshairs.mapNotNull { it.id.removePrefix("crosshair-").toIntOrNull() }.maxOrNull() ?: 0
    return "crosshair-${highest + 1}"
}

private const val COPY_SUFFIX = " copy"
