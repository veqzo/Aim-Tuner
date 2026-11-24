package dev.veqzo.aimtune.app.contribute

import dev.veqzo.aimtune.storage.ResearchExportResult
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

/** Where the contribution flow is. Each step after [Intro] only follows a click; [ContributionFlow.cancel] goes back to [Intro]. */
sealed interface ContributionState {
    /** What the file is for and what it holds; nothing is written yet. */
    data object Intro : ContributionState

    data object Exporting : ContributionState

    /** The file is written; [link] is the issue link or why there is none. [browserFailed]/[revealFailed] tell the screen what to say. */
    data class Ready(
        val export: ResearchExportResult,
        val link: Result<String>,
        val browserFailed: Boolean = false,
        val revealFailed: Boolean = false,
    ) : ContributionState

    data class Failed(val reason: FailReason) : ContributionState
}

enum class FailReason { EMPTY, EXPORT }

/**
 * The steps of "Contribute training data" without any UI, so they can be tested. It writes the file through the given
 * [export] (the existing research export), shows it with [reveal] and opens the issue with [browse], each only when the
 * screen calls it after a click. It never uploads anything and never opens a connection itself.
 */
class ContributionFlow(
    private val config: ContributionConfig,
    private val appVersion: String,
    private val export: () -> CompletableFuture<Result<ResearchExportResult>>,
    private val reveal: (Path) -> Boolean,
    private val browse: (String) -> Boolean,
) {
    var state: ContributionState = ContributionState.Intro
        private set
    private var running: CompletableFuture<Result<ResearchExportResult>>? = null

    /** Writes the file; ignored while a file is being written. */
    fun start() {
        if (state == ContributionState.Exporting) return
        running = export()
        state = ContributionState.Exporting
        poll()
    }

    /** Takes the finished export, if there is one; true when the state changed. Call every frame. */
    fun poll(): Boolean {
        val future = running ?: return false
        if (!future.isDone) return false
        running = null
        val result = runCatching { future.get() }.getOrElse { Result.failure(it) }
        state = result.fold(
            onSuccess = { written ->
                if (written.rounds == 0) {
                    ContributionState.Failed(FailReason.EMPTY)
                } else {
                    ContributionState.Ready(written, ContributionLink.build(config, contributionTitle(written), contributionSummary(written, appVersion)))
                }
            },
            onFailure = { ContributionState.Failed(FailReason.EXPORT) },
        )
        return true
    }

    /** Shows the file in Explorer. */
    fun revealFile() {
        val ready = state as? ContributionState.Ready ?: return
        val shown = runCatching { reveal(ready.export.file) }.getOrDefault(false)
        state = ready.copy(revealFailed = !shown)
    }

    /** Opens the prefilled issue in the default browser. */
    fun openIssue() {
        val ready = state as? ContributionState.Ready ?: return
        val url = ready.link.getOrNull() ?: return
        val opened = runCatching { browse(url) }.getOrDefault(false)
        state = ready.copy(browserFailed = !opened)
    }

    /** Back to the start; a file being written is still written, but the flow forgets it. Nothing is sent either way. */
    fun cancel() {
        running = null
        state = ContributionState.Intro
    }
}
