package dev.veqzo.aimtune.core.crosshair

import kotlin.math.roundToInt

/** The color of the crosshair: one of the eight presets of Valorant, or a custom `RRGGBBAA` hex color. */
data class CrosshairColor(val preset: Int = 0, val customHex: String? = null) {
    init {
        require(preset in 0..PRESETS.lastIndex) { "color preset must be 0..${PRESETS.lastIndex}" }
        require(customHex == null || HEX.matches(customHex)) { "custom color must be 8 hex digits (RRGGBBAA)" }
    }

    /** The color as 0xRRGGBBAA; the alpha of a custom color is its own, the presets are opaque. */
    fun rgba(): Int = customHex?.toLong(16)?.toInt() ?: ((PRESETS[preset] shl 8) or 0xFF)

    companion object {
        /** White, green, yellow-green, green-yellow, yellow, cyan, pink, red (RGB of the reader at carloshenryck/valorant-crosshair, docs/CROSSHAIR.md). */
        val PRESETS = listOf(0xFFFFFF, 0x00FF00, 0x7FFF00, 0xDFFF00, 0xFFFF00, 0x00FFFF, 0xFF00FF, 0xFF0000)
        private val HEX = Regex("[0-9A-Fa-f]{8}")
    }
}

/** One set of four lines (inner or outer). Sizes are screen pixels, opacities 0..1. */
data class CrosshairLines(
    val show: Boolean = true,
    val thickness: Int = 2,
    val opacity: Double = 0.8,
    /** Distance from the center to the start of each line. */
    val offset: Int = 3,
    /** Length of the horizontal lines, and of the vertical ones unless [verticalLength] is set. */
    val length: Int = 6,
    /** Length of the vertical lines when it differs from [length] (`g;1` in the code). */
    val verticalLength: Int? = null,
    /** Shown only in the game, where the crosshair spreads while moving or firing; the trainer has no spread and draws none (docs/CROSSHAIR.md). */
    val movementError: Boolean = false,
    val firingError: Boolean = true,
) {
    val effectiveVerticalLength: Int get() = verticalLength ?: length

    companion object {
        val INNER = CrosshairLines()
        val OUTER = CrosshairLines(opacity = 0.35, offset = 10, length = 2)
    }
}

/**
 * The primary crosshair of a Valorant profile code as the editor and the trainer see it. The typed values
 * are read from and written to a [CrosshairCode] ([fromCode], [applyTo]); keys the model does not know stay
 * in the code as they are. Value ranges follow docs/CROSSHAIR.md; [clamped] limits to them.
 */
data class Crosshair(
    val color: CrosshairColor = CrosshairColor(preset = 5),
    val outlines: Boolean = true,
    val outlineThickness: Int = 1,
    val outlineOpacity: Double = 0.5,
    val dot: Boolean = false,
    val dotThickness: Int = 2,
    val dotOpacity: Double = 1.0,
    val fadeWithFiringError: Boolean = false,
    val inner: CrosshairLines = CrosshairLines.INNER,
    val outer: CrosshairLines = CrosshairLines.OUTER,
) {
    /** This crosshair with every size and opacity limited to the range the game allows. */
    fun clamped(): Crosshair = copy(
        outlineThickness = outlineThickness.coerceIn(OUTLINE_THICKNESS),
        outlineOpacity = outlineOpacity.coerceIn(OPACITY),
        dotThickness = dotThickness.coerceIn(DOT_THICKNESS),
        dotOpacity = dotOpacity.coerceIn(OPACITY),
        inner = inner.clamped(),
        outer = outer.clamped(),
    )

    private fun CrosshairLines.clamped() = copy(
        thickness = thickness.coerceIn(LINE_THICKNESS),
        opacity = opacity.coerceIn(OPACITY),
        offset = offset.coerceIn(LINE_OFFSET),
        length = length.coerceIn(LINE_LENGTH),
        verticalLength = verticalLength?.coerceIn(LINE_LENGTH),
    )

    /** [code] with this crosshair written into its primary section; other sections and unknown keys are untouched. */
    fun applyTo(code: CrosshairCode): CrosshairCode {
        val old = code.primary ?: CodeSection(CrosshairCode.PRIMARY, emptyList())
        val writer = EntryWriter(old.entries.toMutableList(), P_ORDER)
        val default = Crosshair()
        // Only what differs from what the code already says is written, so a code the model cannot fully express
        // (e.g. a custom color without its hex value) comes back unchanged when nothing was edited.
        val base = runCatching { fromCode(code) }.getOrNull()
        if (color != base?.color && color.customHex != null) {
            writer.put("c", "8", "0")
            writer.put("u", color.customHex.uppercase(), "")
            writer.put("b", "1", "0")
        } else if (color != base?.color) {
            writer.put("c", color.preset.toString(), "0")
            writer.putIfPresent("b", "0")
        }
        writer.put("h", flag(outlines), "1")
        writer.put("t", outlineThickness.toString(), "1")
        writer.put("o", number(outlineOpacity), "0.5")
        writer.put("d", flag(dot), "0")
        writer.put("z", dotThickness.toString(), "2")
        writer.put("a", number(dotOpacity), "1")
        writer.put("f", flag(fadeWithFiringError), flag(default.fadeWithFiringError))
        if (inner != base?.inner) writeLines(writer, "0", inner, CrosshairLines.INNER)
        if (outer != base?.outer) writeLines(writer, "1", outer, CrosshairLines.OUTER)
        return code.withSection(CrosshairCode.PRIMARY, CodeSection(CrosshairCode.PRIMARY, writer.entries))
    }

    /** A code (version 0, primary section only) for this crosshair, for export. */
    fun toCode(): CrosshairCode = applyTo(CrosshairCode("0", listOf(CodeSection(CrosshairCode.PRIMARY, emptyList()))))

    private fun writeLines(writer: EntryWriter, prefix: String, lines: CrosshairLines, default: CrosshairLines) {
        // Valorant writes "Nb;0" for hidden lines and nothing else for them; the other keys stay as they were.
        writer.put("${prefix}b", flag(lines.show), "1")
        writer.put("${prefix}t", lines.thickness.toString(), default.thickness.toString())
        writer.put("${prefix}l", lines.length.toString(), default.length.toString())
        val vertical = lines.verticalLength
        if (vertical != null) {
            writer.put("${prefix}v", vertical.toString(), "")
            writer.put("${prefix}g", "1", "0")
        } else {
            writer.putIfPresent("${prefix}g", "0")
        }
        writer.put("${prefix}o", lines.offset.toString(), default.offset.toString())
        writer.put("${prefix}a", number(lines.opacity), number(default.opacity))
        writer.put("${prefix}m", flag(lines.movementError), "0")
        writer.put("${prefix}f", flag(lines.firingError), "1")
    }

    companion object {
        val OUTLINE_THICKNESS = 1..6
        val DOT_THICKNESS = 1..6
        val LINE_THICKNESS = 1..10
        val LINE_OFFSET = 0..20
        val LINE_LENGTH = 0..20
        val OPACITY = 0.0..1.0

        /** Canonical order of the primary keys, used to place new keys where the game writes them. */
        private val P_ORDER = listOf("c", "u", "b", "h", "t", "o", "d", "z", "a", "f", "m", "s") +
            listOf("0", "1").flatMap { p -> listOf("b", "t", "l", "v", "g", "o", "a", "m", "s", "e", "f").map { p + it } }

        /** Reads the primary section of [code]; missing keys have the game's defaults. @throws CrosshairCodeException when a known key has a value that is not a number. */
        fun fromCode(code: CrosshairCode): Crosshair {
            val p = code.primary ?: throw CrosshairCodeException("The code has no primary crosshair section ('P').")
            val d = Crosshair()
            val customHex = p.value("u")?.takeIf { (p.value("c") == "8" || p.value("b") == "1") && it.matches(Regex("[0-9A-Fa-f]{8}")) }
            // A missing or unknown preset is the game's default, white; the default of a new AimTune crosshair (cyan) is not the game's.
            val preset = p.int("c")?.takeIf { it in CrosshairColor.PRESETS.indices } ?: 0
            return Crosshair(
                color = CrosshairColor(preset, customHex?.uppercase()),
                outlines = p.flag("h", d.outlines),
                outlineThickness = p.int("t") ?: d.outlineThickness,
                outlineOpacity = p.double("o") ?: d.outlineOpacity,
                dot = p.flag("d", d.dot),
                dotThickness = p.int("z") ?: d.dotThickness,
                dotOpacity = p.double("a") ?: d.dotOpacity,
                fadeWithFiringError = p.flag("f", d.fadeWithFiringError),
                inner = readLines(p, "0", CrosshairLines.INNER),
                outer = readLines(p, "1", CrosshairLines.OUTER),
            )
        }

        private fun readLines(p: CodeSection, prefix: String, d: CrosshairLines): CrosshairLines {
            val length = p.int("${prefix}l") ?: d.length
            return CrosshairLines(
                show = p.flag("${prefix}b", d.show),
                thickness = p.int("${prefix}t") ?: d.thickness,
                opacity = p.double("${prefix}a") ?: d.opacity,
                offset = p.int("${prefix}o") ?: d.offset,
                length = length,
                verticalLength = if (p.flag("${prefix}g", false)) p.int("${prefix}v") ?: length else null,
                movementError = p.flag("${prefix}m", d.movementError),
                firingError = p.flag("${prefix}f", d.firingError),
            )
        }

        private fun flag(value: Boolean) = if (value) "1" else "0"

        /** Numbers the way the game writes them: "1" for a whole value, "0.35" otherwise. */
        fun number(value: Double): String {
            val rounded = (value * 1000).roundToInt() / 1000.0
            return if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString() else rounded.toString()
        }

        private fun CodeSection.double(key: String): Double? = value(key)?.let {
            it.toDoubleOrNull()?.takeIf { v -> v.isFinite() } ?: throw CrosshairCodeException("The value '$it' of '$key' is not a number.")
        }

        private fun CodeSection.int(key: String): Int? = double(key)?.roundToInt()

        private fun CodeSection.flag(key: String, default: Boolean): Boolean = double(key)?.let { it != 0.0 } ?: default
    }
}

/** Sets keys of a section's entries in place; a new key is inserted after the last entry that comes before it in [order], and omitted when it has its default. */
private class EntryWriter(val entries: MutableList<CodeEntry>, private val order: List<String>) {
    fun put(key: String, value: String, default: String) {
        val at = entries.indexOfLast { it.key == key }
        if (at >= 0) {
            entries[at] = entries[at].copy(value = value)
            return
        }
        if (value == default) return
        val rank = order.indexOf(key)
        var insertAt = entries.size
        if (rank >= 0) {
            val after = entries.indexOfLast { order.indexOf(it.key).let { r -> r in 0 until rank } }
            insertAt = after + 1
        }
        entries.add(insertAt, CodeEntry(key, value))
    }

    /** Changes [key] only when the code has it. */
    fun putIfPresent(key: String, value: String) {
        val at = entries.indexOfLast { it.key == key }
        if (at >= 0) entries[at] = entries[at].copy(value = value)
    }
}
