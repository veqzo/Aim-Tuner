package de.yuuto.aimtune.app

import de.yuuto.aimtune.app.accel.AccelRun
import de.yuuto.aimtune.app.accel.LastCalibration
import de.yuuto.aimtune.app.accel.LastSearch
import de.yuuto.aimtune.app.accel.PriorCalibration
import de.yuuto.aimtune.app.accel.PriorSearchRound
import de.yuuto.aimtune.app.accel.SearchBase
import de.yuuto.aimtune.app.accel.SettingsAnalyses
import de.yuuto.aimtune.app.accel.StoredSearches
import de.yuuto.aimtune.app.blind.AxisErrors
import de.yuuto.aimtune.app.blind.PastRound
import de.yuuto.aimtune.core.accel.AccelSpec
import de.yuuto.aimtune.core.accel.LookupCurve
import de.yuuto.aimtune.core.accel.RawAccelProfile
import de.yuuto.aimtune.core.accel.movementSamples
import de.yuuto.aimtune.core.metrics.RoundMetrics
import de.yuuto.aimtune.core.metrics.computeRoundMetrics
import de.yuuto.aimtune.core.recording.RoundRecording
import de.yuuto.aimtune.core.score.MetricWeights
import de.yuuto.aimtune.storage.Database
import de.yuuto.aimtune.storage.RoundLogFile
import de.yuuto.aimtune.storage.RoundRepository
import de.yuuto.aimtune.storage.ResearchExport
import de.yuuto.aimtune.storage.ResearchExportResult
import de.yuuto.aimtune.storage.RoundRow
import de.yuuto.aimtune.storage.SessionKind
import de.yuuto.aimtune.storage.SessionRow
import de.yuuto.aimtune.storage.scoreInputs
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** What the HUD shows about the most recent round's analysis. */
sealed interface RoundReport {
    data object Pending : RoundReport

    /** [saveError] is null when the round was stored, or the reason it was not. */
    data class Done(val metrics: RoundMetrics, val saveError: String?, val noMouseInput: Boolean = false) : RoundReport

    data class Failed(val message: String) : RoundReport
}

/** Everything the database holds about played sessions, for the history. */
data class StoredHistory(val sessions: List<SessionRow>, val rounds: List<RoundRow>)

/**
 * Computes metrics and stores finished rounds on a background thread so the render thread never
 * waits for segmentation or disk. A session is created on the first finished round and a new one
 * whenever DPI, game or blind test run change. Without a [database] rounds are still analysed but not stored.
 * With an [exportDir] every round is also written there as a raw event log, so real sessions can
 * become regression fixtures; a failing export is logged and never affects the round.
 */
class RoundProcessor(private val database: Database?, private val exportDir: Path? = null) : AutoCloseable {
    private val log = System.getLogger("aimtune.rounds")
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "aimtune-round-processor").apply { isDaemon = true } }
    private val repository = database?.let(::RoundRepository)

    // Only touched on the executor thread.
    private var sessionId: Long? = null
    private var sessionKey: List<Any?>? = null

    @Volatile
    var report: RoundReport? = null
        private set

    private val storedCounter = AtomicInteger()

    /** How many rounds this run has stored; the Raw Accel hub uses it to know when its analyses are out of date. */
    val storedCount: Int get() = storedCounter.get()

    init {
        // Rounds stored with an older metric definition are recomputed from their raw events before anything reads them.
        repository?.let { repo ->
            executor.execute {
                try {
                    val refresh = repo.refreshMetrics()
                    if (refresh.updated > 0 || refresh.failed > 0) {
                        log.log(System.Logger.Level.INFO, "recomputed metrics of ${refresh.updated} stored rounds, ${refresh.failed} could not be read")
                    }
                } catch (e: Exception) {
                    log.log(System.Logger.Level.ERROR, "recomputing stored metrics failed: ${e.message}", e)
                }
            }
        }
    }

    /** [runKey] separates the rounds of one blind test run into their own session; null keeps them with the game and DPI. [kind] is the session type the rounds belong to. */
    fun submit(
        recording: RoundRecording,
        startedAtMs: Long,
        blind: Boolean,
        gameId: String?,
        runKey: Long? = null,
        kind: SessionKind = SessionKind.SENSITIVITY,
    ) {
        report = RoundReport.Pending
        executor.execute {
            export(recording, startedAtMs)
            report = try {
                val metrics = computeRoundMetrics(recording)
                RoundReport.Done(metrics, store(recording, metrics, startedAtMs, blind, gameId, runKey, kind), recording.mouseEvents.isEmpty())
            } catch (e: Exception) {
                log.log(System.Logger.Level.ERROR, "round analysis failed", e)
                RoundReport.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /**
     * The stored blind rounds of this game and DPI from all sessions, for merging into a new test.
     * Reads on the storage thread (so it never overlaps a save) and waits for the answer; empty
     * without a database or if reading fails.
     */
    fun pastBlindRounds(gameId: String?, dpi: Double): List<PastRound> {
        val repo = repository ?: return emptyList()
        return try {
            executor.submit<List<PastRound>> {
                repo.blindRounds(gameId, dpi).map { PastRound(it.sessionId, it.cm360, it.scoreInputs(), it.scenario, AxisErrors.of(it)) }
            }.get()
        } catch (e: Exception) {
            log.log(System.Logger.Level.ERROR, "reading earlier rounds failed: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * The movements of earlier acceleration runs at this DPI and sensitivity, plain ones or, with a [baseCurve], those played with
     * exactly that curve (a different base would need a different analysis), read on the storage thread and analysed again from
     * the stored raw events; nothing without a database or if reading fails.
     */
    fun priorCalibration(dpi: Double, cm360: Double, baseCurve: RawAccelProfile? = null, stage: LookupCurve? = null): PriorCalibration {
        val repo = repository ?: return PriorCalibration.NONE
        return try {
            executor.submit<PriorCalibration> { readPriorCalibration(repo, dpi, cm360, (stage ?: baseCurve)?.let(AccelSpec::encodeModel)) }.get()
        } catch (e: Exception) {
            log.log(System.Logger.Level.ERROR, "reading earlier calibration rounds failed: ${e.message}", e)
            PriorCalibration.NONE
        }
    }

    /** The calibration data at the setup of the newest acceleration round, or null when none is stored. */
    fun lastCalibration(): LastCalibration? {
        val repo = repository ?: return null
        return try {
            executor.submit<LastCalibration?> {
                val (dpi, cm360) = repo.latestPlainAccelSetup(AccelRun.CLICK_SCENARIOS.map { it.id }) ?: return@submit null
                LastCalibration(dpi, cm360, readPriorCalibration(repo, dpi, cm360))
            }.get()
        } catch (e: Exception) {
            log.log(System.Logger.Level.ERROR, "reading the last calibration failed: ${e.message}", e)
            null
        }
    }

    /**
     * The stored blind strength searches at the setup of the newest one, analysed again together from their rounds; null when
     * there is none or reading fails. Read on the storage thread.
     */
    fun lastSearch(weights: MetricWeights, baseCurve: RawAccelProfile? = null): LastSearch? {
        val repo = repository ?: return null
        return try {
            executor.submit<LastSearch?> {
                val searches = repo.sessions().filter { it.kind != SessionKind.SENSITIVITY }.mapNotNull { session ->
                    val rounds = StoredSearches.searchRounds(session.kind, repo.rounds(session.id))
                    if (rounds.isEmpty()) null else StoredSearches.SearchSession(session.id, session.dpi, rounds)
                }
                StoredSearches.pool(searches, weights, baseCurve?.let { SearchBase(it, searches.first().dpi) })
            }.get()
        } catch (e: Exception) {
            log.log(System.Logger.Level.ERROR, "reading the last search failed: ${e.message}", e)
            null
        }
    }

    /**
     * The stored search rounds at this DPI and sensitivity for a new search to build on; none without a database or if reading fails.
     * With a [baseCurve] the rounds of searches on top of exactly that curve, otherwise those of the plain mouse's natural curves.
     */
    fun priorSearch(dpi: Double, cm360: Double, baseCurve: RawAccelProfile? = null): List<PriorSearchRound> {
        val repo = repository ?: return emptyList()
        return try {
            executor.submit<List<PriorSearchRound>> {
                val searches = repo.sessions().filter { it.kind != SessionKind.SENSITIVITY }.mapNotNull { session ->
                    val rounds = StoredSearches.searchRounds(session.kind, repo.rounds(session.id))
                    if (rounds.isEmpty()) null else StoredSearches.SearchSession(session.id, session.dpi, rounds)
                }
                StoredSearches.priorRounds(searches, dpi, cm360, baseCurve?.let { SearchBase(it, dpi) })
            }.get()
        } catch (e: Exception) {
            log.log(System.Logger.Level.ERROR, "reading earlier search rounds failed: ${e.message}", e)
            emptyList()
        }
    }

    private fun readPriorCalibration(repo: RoundRepository, dpi: Double, cm360: Double, spec: String? = null): PriorCalibration {
        val ids = repo.accelRoundIds(spec, dpi, cm360, AccelRun.CLICK_SCENARIOS.map { it.id }, PriorCalibration.CM360_TOLERANCE, PriorCalibration.MAX_ROUNDS)
        val metrics = ids.mapNotNull { id ->
            val recording = repo.loadRecording(id) ?: return@mapNotNull null
            try {
                computeRoundMetrics(recording)
            } catch (e: IllegalArgumentException) {
                null
            }
        }
        return PriorCalibration(metrics.size, movementSamples(metrics, dpi))
    }

    /**
     * The passive setting analyses of the newest [MAX_ANALYSED_ROUNDS] plain rounds. The rounds are read on the storage thread
     * (so the read never overlaps a save), the analyses run on the common pool; nothing blocks the caller. Without a database
     * or when reading fails the analyses see no rounds and say "not enough data".
     */
    fun settingsAnalyses(): CompletableFuture<SettingsAnalyses> {
        val repo = repository ?: return CompletableFuture.completedFuture(SettingsAnalyses.compute(emptyList()))
        val recordings = CompletableFuture<List<RoundRecording>>()
        executor.execute {
            recordings.complete(
                try {
                    repo.rounds().filter { it.accel == null }.takeLast(MAX_ANALYSED_ROUNDS).mapNotNull { repo.loadRecording(it.id) }
                } catch (e: Exception) {
                    log.log(System.Logger.Level.ERROR, "reading rounds for the setting analyses failed: ${e.message}", e)
                    emptyList()
                },
            )
        }
        return recordings.thenApplyAsync { SettingsAnalyses.compute(it) }
    }

    /** All stored sessions and rounds, read on the storage thread; empty without a database or if reading fails. */
    fun storedHistory(): StoredHistory {
        val repo = repository ?: return StoredHistory(emptyList(), emptyList())
        return try {
            executor.submit<StoredHistory> { StoredHistory(repo.sessions(), repo.rounds()) }.get()
        } catch (e: Exception) {
            log.log(System.Logger.Level.ERROR, "reading the history failed: ${e.message}", e)
            StoredHistory(emptyList(), emptyList())
        }
    }

    /** Writes all stored rounds as one research file into [dir] on the storage thread; the result says what was written or why not. */
    fun exportResearch(dir: Path, appVersion: String): CompletableFuture<Result<ResearchExportResult>> {
        val repo = repository ?: return CompletableFuture.completedFuture(Result.failure(IllegalStateException("storage unavailable")))
        val future = CompletableFuture<Result<ResearchExportResult>>()
        executor.execute {
            future.complete(runCatching { ResearchExport.write(dir, repo, appVersion) }.onFailure { log.log(System.Logger.Level.ERROR, "research export failed: ${it.message}", it) })
        }
        return future
    }

    private fun export(recording: RoundRecording, startedAtMs: Long) {
        val dir = exportDir ?: return
        try {
            RoundLogFile.write(recording, dir.resolve("round-$startedAtMs-seed${recording.seed}.json"))
        } catch (e: Exception) {
            log.log(System.Logger.Level.ERROR, "exporting round log failed: ${e.message}", e)
        }
    }

    /** Returns null on success or a short reason; storage problems must not lose the analysis. */
    private fun store(recording: RoundRecording, metrics: RoundMetrics, startedAtMs: Long, blind: Boolean, gameId: String?, runKey: Long?, kind: SessionKind): String? {
        val repo = repository ?: return "storage unavailable"
        return try {
            val key = listOf(recording.dpi, gameId, runKey, kind)
            val session = sessionId.takeIf { sessionKey == key }
                ?: repo.createSession(startedAtMs, gameId, recording.dpi, kind).also {
                    sessionId = it
                    sessionKey = key
                }
            repo.saveRound(session, recording, startedAtMs, blind, metrics)
            storedCounter.incrementAndGet()
            null
        } catch (e: Exception) {
            log.log(System.Logger.Level.ERROR, "saving round failed", e)
            e.message ?: e.javaClass.simpleName
        }
    }

    /** Waits for queued rounds to finish, then closes the database. */
    override fun close() {
        executor.shutdown()
        if (!executor.awaitTermination(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)) {
            log.log(System.Logger.Level.WARNING, "round processing did not finish before shutdown")
        }
        database?.close()
    }

    private companion object {
        const val SHUTDOWN_WAIT_SECONDS = 10L

        /** The analyses read this many plain rounds at most; the newest ones, so the memory and the time stay bounded. */
        const val MAX_ANALYSED_ROUNDS = 120
    }
}
