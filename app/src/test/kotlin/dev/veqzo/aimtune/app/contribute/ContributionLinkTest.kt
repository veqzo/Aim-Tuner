package dev.veqzo.aimtune.app.contribute

import dev.veqzo.aimtune.storage.ResearchExportResult
import java.net.URI
import java.net.URLDecoder
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContributionLinkTest {
    private val config = ContributionConfig("veqzo/Aim-Tuner", "training-data.yml")
    private val export = ResearchExportResult(Paths.get("C:/Users/PrivateUserName/AppData/Roaming/AimTune/exports/research/aimtune-research.json.gz"), 3, 42, 2_150_000)

    private fun query(url: String): Map<String, String> = URI(url).rawQuery.split('&').associate {
        val (k, v) = it.split('=', limit = 2)
        k to URLDecoder.decode(v, Charsets.UTF_8)
    }

    @Test
    fun `the summary holds versions and counts only`() {
        val summary = contributionSummary(export, "1.2.3")
        assertEquals("AimTune: 1.2.3\nExport format: 2\nSessions: 3\nRounds: 42\nFile size: 2100 KB", summary)
        assertFalse(summary.contains("PrivateUserName") || summary.contains("aimtune-research"))
        assertEquals("Training data: 42 rounds", contributionTitle(export))
    }

    @Test
    fun `an empty export still gives a summary`() {
        val empty = ResearchExportResult(Paths.get("x.json.gz"), 0, 0, 0)
        assertTrue(contributionSummary(empty, "1.0.0").contains("Rounds: 0"))
        assertTrue(contributionSummary(empty, "1.0.0").endsWith("File size: 0 KB"))
    }

    @Test
    fun `the link opens the form of the repository with encoded parameters`() {
        val summary = contributionSummary(export, "1.2.3")
        val url = ContributionLink.build(config, "A & B #1 ä", summary).getOrThrow()
        assertTrue(url.startsWith("https://github.com/veqzo/Aim-Tuner/issues/new?"))
        val uri = URI(url)
        assertEquals("github.com", uri.host)
        assertEquals("/veqzo/Aim-Tuner/issues/new", uri.path)
        assertFalse(url.contains(' ') || url.contains('\n') || url.contains('ä') || url.substringAfter('?').contains('#'))
        val q = query(url)
        assertEquals(setOf("template", "title", ContributionLink.SUMMARY_FIELD), q.keys)
        assertEquals("training-data.yml", q["template"])
        assertEquals("A & B #1 ä", q["title"])
        assertEquals(summary, q[ContributionLink.SUMMARY_FIELD])
        assertFalse(url.contains("PrivateUserName") || url.contains("json.gz"), "a path in the link")
    }

    @Test
    fun `a long summary is dropped and a link that stays too long fails`() {
        val long = "x".repeat(ContributionLink.MAX_LENGTH)
        val withoutSummary = ContributionLink.build(config, "t", long).getOrThrow()
        assertFalse(ContributionLink.SUMMARY_FIELD in query(withoutSummary))
        assertTrue(withoutSummary.length <= ContributionLink.MAX_LENGTH)
        assertTrue(ContributionLink.build(config, "t".repeat(ContributionLink.MAX_LENGTH), null).isFailure)
        assertTrue(ContributionLink.build(config, "t", "s", maxLength = 20).isFailure)
    }

    @Test
    fun `an invalid repository gives no link`() {
        assertTrue(ContributionLink.build(ContributionConfig("evil.com/x?a=", "training-data.yml"), "t", "s").isFailure)
    }

    @Test
    fun `the issue form in the docs has the summary field the link fills`() {
        val form = java.io.File("../docs/contribution/training-data.yml").readText()
        assertTrue(form.contains("id: ${ContributionLink.SUMMARY_FIELD}"))
        assertTrue(form.contains("required: true"))
    }
}
