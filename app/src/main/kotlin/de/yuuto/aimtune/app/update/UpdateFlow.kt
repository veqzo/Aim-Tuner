package de.yuuto.aimtune.app.update

import java.nio.file.Path

/** Where an update stands; the screen shows it. */
sealed interface UpdateState {
    /** Nothing started; [kind] says how this copy would be updated. */
    data class Idle(val kind: InstallKind) : UpdateState

    data object Preparing : UpdateState

    data class Downloading(val done: Long, val total: Long) : UpdateState

    /** The file is checked and the helper started: the app closes now. */
    data object Applying : UpdateState

    data class Failed(val reason: String) : UpdateState
}

/**
 * The steps of an in-app update, run on a background thread: ask GitHub again for the release (what was remembered is only a hint),
 * pick the installer or the zip by how this copy is installed, download and check it, start what puts it in place. The player
 * starts it and sees every step; nothing here runs on its own.
 */
class UpdateFlow(
    private val workDir: Path,
    private val kind: InstallKind,
    private val launcher: Path?,
    private val latest: () -> ReleaseInfo? = { UpdateChecker().latest() },
    private val downloader: UpdateDownloader = UpdateDownloader(workDir),
    private val apply: (InstallKind, Path, Path?, Path) -> Unit = UpdateApplier::apply,
    private val onState: (UpdateState) -> Unit,
    private val onFinished: () -> Unit,
) {
    @Volatile
    private var cancelled = false

    fun cancel() {
        cancelled = true
    }

    /** Runs the update; call it from a background thread. [target] is the version the player was told about. */
    fun run(target: ReleaseInfo) {
        try {
            onState(UpdateState.Preparing)
            val release = latest()?.takeIf { it.version >= target.version } ?: return onState(UpdateState.Failed(Reasons.UNREACHABLE))
            val asset = when (kind) {
                InstallKind.INSTALLED -> release.installer
                InstallKind.PORTABLE -> release.portableZip
                InstallKind.UNKNOWN -> return onState(UpdateState.Failed(Reasons.NOT_PACKAGED))
            } ?: return onState(UpdateState.Failed(Reasons.NO_FILE))
            var lastShown = 0L
            val file = downloader.download(asset, cancelled = { cancelled }) { done, total ->
                val now = System.nanoTime()
                if (now - lastShown >= PROGRESS_INTERVAL_NANOS || done == total) {
                    lastShown = now
                    onState(UpdateState.Downloading(done, total))
                }
            }
            if (cancelled) return onState(UpdateState.Idle(kind))
            downloader.verify(file, asset)
            onState(UpdateState.Applying)
            apply(kind, file, launcher, workDir)
            onFinished()
        } catch (e: UpdateDownloadException) {
            onState(if (cancelled) UpdateState.Idle(kind) else UpdateState.Failed(e.message ?: Reasons.UNREACHABLE))
        } catch (e: Exception) {
            System.getLogger("aimtune.update").log(System.Logger.Level.WARNING, "update failed: ${e.javaClass.simpleName}")
            onState(UpdateState.Failed(e.message ?: Reasons.UNREACHABLE))
        }
    }

    private companion object {
        /** The screen is rebuilt for every state, so the progress is shown a few times a second, not for every block read. */
        const val PROGRESS_INTERVAL_NANOS = 200_000_000L
    }

    object Reasons {
        const val UNREACHABLE = "GitHub could not be reached"
        const val NOT_PACKAGED = "this run is not the packaged app"
        const val NO_FILE = "the release has no file for this install"
    }
}
