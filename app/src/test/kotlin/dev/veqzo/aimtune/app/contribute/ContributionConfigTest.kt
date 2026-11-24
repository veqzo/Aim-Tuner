package dev.veqzo.aimtune.app.contribute

import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class ContributionConfigTest {
    private fun props(enabled: String?, repository: String = "veqzo/Aim-Tuner", template: String = "training-data.yml") = Properties().apply {
        enabled?.let { setProperty("enabled", it) }
        setProperty("repository", repository)
        setProperty("template", template)
    }

    @Test
    fun `a valid switched-on configuration is enabled`() {
        assertEquals(ContributionAvailability.Enabled(ContributionConfig("veqzo/Aim-Tuner", "training-data.yml")), Contributions.fromProperties(props("true")))
    }

    @Test
    fun `anything but true leaves the feature off`() {
        listOf(null, "", "false", "yes", "1").forEach { assertEquals(ContributionAvailability.Disabled, Contributions.fromProperties(props(it)), "enabled=$it") }
        assertEquals(ContributionAvailability.Disabled, Contributions.fromProperties(Properties()))
    }

    @Test
    fun `invalid repositories and templates keep the feature off`() {
        listOf("", "veqzo", "veqzo/", "/Aim-Tuner", "a/b/c", "-veqzo/x", "veq--zo/x", "veqzo/..", "veqzo/a b", "evil.com/x?y", "veqzo/x#y", "\${repository}")
            .forEach { assertIs<ContributionAvailability.Invalid>(Contributions.fromProperties(props("true", repository = it)), it) }
        listOf("", "training-data", "../x.yml", "a/b.yml", "x.yml?y=1", "x.md")
            .forEach { assertIs<ContributionAvailability.Invalid>(Contributions.fromProperties(props("true", template = it)), it) }
    }

    @Test
    fun `repository names follow the GitHub rules`() {
        assertNull(Contributions.validRepository("veqzo/Aim-Tuner"))
        assertNull(Contributions.validRepository("a1/repo.name_x"))
        assertIs<String>(Contributions.validRepository("a".repeat(40) + "/x"))
    }

    @Test
    fun `this build is switched off by default`() {
        // gradle.properties keeps the feature off until the legal questions are settled (ADR 0007).
        assertEquals(ContributionAvailability.Disabled, Contributions.load())
    }
}
