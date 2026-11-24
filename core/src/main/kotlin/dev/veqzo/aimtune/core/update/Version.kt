package dev.veqzo.aimtune.core.update

/** A release version `major.minor.patch`, compared numerically (1.10.0 is newer than 1.9.0). */
data class Version(val major: Int, val minor: Int, val patch: Int) : Comparable<Version> {
    override fun compareTo(other: Version): Int =
        compareValuesBy(this, other, Version::major, Version::minor, Version::patch)

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        /**
         * Reads `1.2.3`, `v1.2.3` or `1.2` (missing parts are 0); a suffix after `-` or `+` (`1.2.3-beta`) is ignored.
         * Anything else is null, so an odd tag never counts as an update.
         */
        fun parse(text: String): Version? {
            val core = text.trim().removePrefix("v").removePrefix("V").substringBefore('-').substringBefore('+')
            val parts = core.split('.')
            if (parts.size !in 1..3) return null
            val numbers = parts.map { part -> part.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toIntOrNull() ?: return null }
            return Version(numbers[0], numbers.getOrElse(1) { 0 }, numbers.getOrElse(2) { 0 })
        }
    }
}
