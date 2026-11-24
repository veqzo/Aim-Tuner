package dev.veqzo.aimtune.core.crosshair

/** A key and its value as they stand in the code, e.g. `0l` and `4`. */
data class CodeEntry(val key: String, val value: String)

/**
 * One part of a code: the header (before the first marker, [marker] null) or the part after a section marker
 * (`P` primary, `A` aim down sights, `S` sniper scope, or any other single-word marker found in a code).
 */
data class CodeSection(val marker: String?, val entries: List<CodeEntry>) {
    fun value(key: String): String? = entries.lastOrNull { it.key == key }?.value
}

/** A code that cannot be read; the message says what is wrong and is meant for the user. */
class CrosshairCodeException(message: String) : IllegalArgumentException(message)

/**
 * A Valorant crosshair profile code as the list of its sections, keys and values, exactly as written: unknown
 * keys, their order and unknown sections survive [format] unchanged (docs/CROSSHAIR.md). The typed view the
 * editor and the trainer use is [Crosshair].
 */
data class CrosshairCode(val version: String, val sections: List<CodeSection>) {
    val primary: CodeSection? get() = sections.firstOrNull { it.marker == PRIMARY }

    fun format(): String = buildList {
        add(version)
        for (section in sections) {
            section.marker?.let { add(it) }
            for (entry in section.entries) {
                add(entry.key)
                add(entry.value)
            }
        }
    }.joinToString(";")

    /** This code with the section [marker] replaced by [section], or appended (after the sections it has) when it has none. */
    fun withSection(marker: String, section: CodeSection): CrosshairCode =
        if (sections.any { it.marker == marker }) copy(sections = sections.map { if (it.marker == marker) section else it })
        else copy(sections = sections + section)

    companion object {
        const val PRIMARY = "P"
        const val ADS = "A"
        const val SNIPER = "S"
        private val MARKERS = setOf(PRIMARY, ADS, SNIPER)
        private const val MAX_LENGTH = 2_000

        /** Parses [text]; surrounding spaces and one trailing `;` are ignored. @throws CrosshairCodeException with a message for the user. */
        fun parse(text: String): CrosshairCode {
            val trimmed = text.trim().removeSuffix(";")
            if (trimmed.isEmpty()) throw CrosshairCodeException("The code is empty.")
            if (trimmed.length > MAX_LENGTH) throw CrosshairCodeException("The code is too long to be a crosshair code.")
            if (trimmed.any { it.isWhitespace() }) throw CrosshairCodeException("A crosshair code has no spaces; copy the whole profile code from Valorant.")
            val atoms = trimmed.split(";")
            val version = atoms.first()
            if (version.isEmpty() || !version.all { it.isDigit() }) {
                throw CrosshairCodeException("The code must start with a version number such as '0;'; copy the whole profile code from Valorant.")
            }
            val sections = mutableListOf<CodeSection>()
            var marker: String? = null
            var entries = mutableListOf<CodeEntry>()
            var index = 1
            while (index < atoms.size) {
                val atom = atoms[index]
                if (atom in MARKERS) {
                    sections += CodeSection(marker, entries)
                    marker = atom
                    entries = mutableListOf()
                    index++
                    continue
                }
                if (atom.isEmpty()) throw CrosshairCodeException("The code has an empty key at position ${index + 1}.")
                if (index + 1 >= atoms.size || atoms[index + 1].isEmpty() || atoms[index + 1] in MARKERS) {
                    throw CrosshairCodeException("The key '$atom' has no value. Is the end of the code missing?")
                }
                entries += CodeEntry(atom, atoms[index + 1])
                index += 2
            }
            sections += CodeSection(marker, entries)
            if (sections.none { it.marker == PRIMARY }) throw CrosshairCodeException("The code has no primary crosshair section ('P').")
            // A code without header keys has no header section; format() would write nothing for it either way.
            val kept = if (sections.first().marker == null && sections.first().entries.isEmpty()) sections.drop(1) else sections
            return CrosshairCode(version, kept)
        }
    }
}
