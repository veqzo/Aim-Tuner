package dev.veqzo.aimtune.app.contribute

import dev.veqzo.aimtune.storage.ResearchExport
import dev.veqzo.aimtune.storage.ResearchExportResult
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * The text that the link puts into the issue form: counts and versions only. No measurement, no path and no time; the
 * data itself is only in the file, which the person attaches or links themselves. The issue goes to the maintainer, so the
 * text is English.
 */
fun contributionSummary(export: ResearchExportResult, appVersion: String): String = buildString {
    appendLine("AimTune: $appVersion")
    appendLine("Export format: ${ResearchExport.FORMAT_VERSION}")
    appendLine("Sessions: ${export.sessions}")
    appendLine("Rounds: ${export.rounds}")
    append("File size: ${(export.bytes + BYTES_PER_KB - 1) / BYTES_PER_KB} KB")
}

fun contributionTitle(export: ResearchExportResult): String = "Training data: ${export.rounds} rounds"

/**
 * Builds the link to a new issue from the issue form (GitHub docs, "Creating an issue from a URL query": `template`, `title`
 * and the form field ids as parameters). Only builds a string; nothing here touches the network. A link longer than
 * [maxLength] drops the summary, which the person can then type into the form; it fails when even that is too long.
 */
object ContributionLink {
    /** GitHub answers 414 above an unstated server limit; this stays well below the usual 8 KB. */
    const val MAX_LENGTH = 6000

    /** The id of the summary field in .github/ISSUE_TEMPLATE/training-data.yml. */
    const val SUMMARY_FIELD = "summary"

    fun build(config: ContributionConfig, title: String, summary: String?, maxLength: Int = MAX_LENGTH): Result<String> {
        Contributions.validRepository(config.repository)?.let { return Result.failure(IllegalArgumentException(it)) }
        val base = "https://github.com/${config.repository}/issues/new?template=${encode(config.template)}&title=${encode(title)}"
        val full = summary?.let { "$base&$SUMMARY_FIELD=${encode(it)}" }
        return when {
            full != null && full.length <= maxLength -> Result.success(full)
            base.length <= maxLength -> Result.success(base)
            else -> Result.failure(IllegalArgumentException("the issue link would be ${base.length} characters, more than $maxLength"))
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)
}

private const val BYTES_PER_KB = 1024L
