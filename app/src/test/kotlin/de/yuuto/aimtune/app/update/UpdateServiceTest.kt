package de.yuuto.aimtune.app.update

import de.yuuto.aimtune.core.update.Version
import de.yuuto.aimtune.storage.AppSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UpdateServiceTest {
    private val now = 1_800_000_000L
    private val newer = ReleaseInfo(Version(0, 2, 0), "https://github.com/x/y/releases/tag/v0.2.0")
    private var calls = 0

    private fun service(release: ReleaseInfo? = newer, current: Version = Version(0, 1, 0)) =
        UpdateService(latest = { calls++; release }, current = current, nowEpochSeconds = { now }, background = Runnable::run)

    private fun check(service: UpdateService, settings: AppSettings): Pair<Long, ReleaseInfo?>? {
        var result: Pair<Long, ReleaseInfo?>? = null
        service.checkIfDue(settings) { at, release -> result = at to release }
        return result
    }

    @Test
    fun `a check runs when none happened yet and reports the time and the release`() {
        assertEquals(now to newer, check(service(), AppSettings()))
        assertEquals(1, calls)
    }

    @Test
    fun `at most one check a day, a failed one is tried again after an hour`() {
        assertNull(check(service(), AppSettings(lastUpdateCheckEpochSeconds = now - 3_600)))
        assertEquals(0, calls)
        // A failed check (null release) is stored as if it was made early, so a missing network or a repository that was private is asked again soon, not tomorrow.
        val failed = check(service(release = null), AppSettings(lastUpdateCheckEpochSeconds = now - UpdateService.CHECK_INTERVAL_SECONDS))
        assertEquals(UpdateService.stampOf(now, null) to null, failed)
        val stored = AppSettings(lastUpdateCheckEpochSeconds = checkNotNull(failed).first)
        assertEquals(false, service().isDue(stored), "not due right after")
        assertEquals(true, UpdateService(latest = { null }, current = Version(0, 1, 0), nowEpochSeconds = { now + UpdateService.RETRY_AFTER_FAILURE_SECONDS }).isDue(stored), "due after the retry time")
    }

    @Test
    fun `switched off means no request and no hint`() {
        val off = AppSettings(checkForUpdates = false, latestKnownVersion = "9.0.0", latestKnownUrl = "https://github.com/x/y")
        assertNull(check(service(), off))
        assertEquals(0, calls)
        assertNull(service().known(off))
    }

    @Test
    fun `a failing check function never escapes`() {
        val broken = UpdateService(latest = { error("boom") }, current = Version(0, 1, 0), nowEpochSeconds = { now }, background = Runnable::run)
        assertEquals(UpdateService.stampOf(now, null) to null, check(broken, AppSettings()))
    }

    @Test
    fun `the remembered release is a hint only while it is newer than this build`() {
        val settings = AppSettings(latestKnownVersion = "0.2.0", latestKnownUrl = newer.url)
        assertEquals(newer, service().known(settings))
        assertNull(service(current = Version(0, 2, 0)).known(settings), "after the update the hint goes away")
        assertNull(service().known(AppSettings()), "nothing known yet")
        assertNull(service().known(settings.copy(latestKnownVersion = "garbage")))
    }
}
