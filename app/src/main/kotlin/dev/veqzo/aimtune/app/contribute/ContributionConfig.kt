package dev.veqzo.aimtune.app.contribute

import java.io.IOException
import java.util.Properties

/** Where a contribution issue is opened: an `owner/name` repository on GitHub and the file name of its issue form. */
data class ContributionConfig(val repository: String, val template: String)

/** Whether this build offers "Contribute training data". */
sealed interface ContributionAvailability {
    data class Enabled(val config: ContributionConfig) : ContributionAvailability

    /** Switched off in the build (the default until the open legal questions are settled, see ADR 0007). */
    data object Disabled : ContributionAvailability

    /** Switched on with a value that does not pass the checks; the feature stays off. */
    data class Invalid(val reason: String) : ContributionAvailability
}

/**
 * Reads `contribution.properties`, which `processResources` fills from the Gradle properties `aimtune.contribution.*`
 * (gradle.properties). The whole feature is temporary: see docs/RESEARCH-EXPORT.md "Contributions" for how to switch it on and remove it.
 */
object Contributions {
    const val RESOURCE = "/contribution.properties"

    /** GitHub user and organisation names: letters, digits and single hyphens, at most 39 characters. */
    private val owner = Regex("^[A-Za-z0-9](?:[A-Za-z0-9]|-(?=[A-Za-z0-9])){0,38}$")
    private val name = Regex("^[A-Za-z0-9._-]{1,100}$")
    private val template = Regex("^[A-Za-z0-9_-][A-Za-z0-9._-]{0,99}\\.ya?ml$")

    /** The setting of this build. */
    val current: ContributionAvailability by lazy { load() }

    fun load(): ContributionAvailability = try {
        Contributions::class.java.getResourceAsStream(RESOURCE)?.use { stream -> fromProperties(Properties().also { it.load(stream) }) }
            ?: ContributionAvailability.Disabled
    } catch (e: IOException) {
        ContributionAvailability.Invalid("unreadable ${RESOURCE.removePrefix("/")}: ${e.message}")
    }

    fun fromProperties(properties: Properties): ContributionAvailability {
        if (properties.getProperty("enabled")?.trim()?.lowercase() != "true") return ContributionAvailability.Disabled
        val repository = properties.getProperty("repository")?.trim().orEmpty()
        val file = properties.getProperty("template")?.trim().orEmpty()
        validRepository(repository)?.let { return ContributionAvailability.Invalid(it) }
        if (!template.matches(file)) return ContributionAvailability.Invalid("template must be a .yml file name, was '$file'")
        return ContributionAvailability.Enabled(ContributionConfig(repository, file))
    }

    /** Null when [repository] is a valid `owner/name`, otherwise the reason. */
    fun validRepository(repository: String): String? {
        val parts = repository.split('/')
        return when {
            parts.size != 2 -> "repository must look like owner/name, was '$repository'"
            !owner.matches(parts[0]) -> "invalid repository owner '${parts[0]}'"
            !name.matches(parts[1]) || parts[1] == "." || parts[1] == ".." -> "invalid repository name '${parts[1]}'"
            else -> null
        }
    }
}
