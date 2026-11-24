package de.yuuto.aimtune.app.update

import de.yuuto.aimtune.core.update.Version
import de.yuuto.aimtune.storage.AppSettings
import java.time.Instant

/**
 * Decides when to ask for the newest release (at most once a day, a failed try again after an hour, so a private repository
 * or a missing network is not asked at every start) and which release to tell the player about. The last answer is kept in the settings, so
 * the hint stays on the days the check is not due and goes away by itself once the app is updated.
 */
class UpdateService(
    private val latest: () -> ReleaseInfo?,
    private val current: Version,
    private val nowEpochSeconds: () -> Long = { Instant.now().epochSecond },
    private val background: (Runnable) -> Unit = { task -> Thread(task, "aimtune-update-check").apply { isDaemon = true }.start() },
) {
    /** The update to show from what the settings remember, or null. */
    fun known(settings: AppSettings): ReleaseInfo? {
        if (!settings.checkForUpdates) return null
        val version = settings.latestKnownVersion?.let(Version::parse) ?: return null
        val url = settings.latestKnownUrl ?: return null
        return ReleaseInfo(version, url).takeIf { version > current }
    }

    fun isDue(settings: AppSettings): Boolean =
        settings.checkForUpdates && nowEpochSeconds() - settings.lastUpdateCheckEpochSeconds >= CHECK_INTERVAL_SECONDS

    /**
     * Asks in the background when a check is due. [onDone] is called on that background thread with the checked time
     * and the release found (null when the check failed); it has to hand over to the UI thread itself.
     */
    fun checkIfDue(settings: AppSettings, onDone: (checkedAtEpochSeconds: Long, release: ReleaseInfo?) -> Unit) {
        if (!isDue(settings)) return
        background {
            val release = runCatching(latest).getOrNull()
            onDone(stampOf(nowEpochSeconds(), release), release)
        }
    }

    companion object {
        const val CHECK_INTERVAL_SECONDS = 24 * 60 * 60L

        /** A failed check is tried again after this long (no network, a repository that was private, a rate limit), not after a day. */
        const val RETRY_AFTER_FAILURE_SECONDS = 60 * 60L

        /** The time to store for a check made at [now]: a failed one is stored as if it was made early, so it comes due again after [RETRY_AFTER_FAILURE_SECONDS]. */
        fun stampOf(now: Long, release: ReleaseInfo?): Long = if (release != null) now else now - (CHECK_INTERVAL_SECONDS - RETRY_AFTER_FAILURE_SECONDS)
    }
}
