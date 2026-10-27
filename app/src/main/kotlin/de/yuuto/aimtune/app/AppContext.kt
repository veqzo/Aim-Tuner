package de.yuuto.aimtune.app

import com.badlogic.gdx.Game
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Screen
import de.yuuto.aimtune.app.accel.AccelPhase
import de.yuuto.aimtune.app.accel.AccelRun
import de.yuuto.aimtune.app.audio.FlashSounds
import de.yuuto.aimtune.app.update.AppVersion
import de.yuuto.aimtune.app.update.ReleaseInfo
import de.yuuto.aimtune.app.audio.HitSounds
import de.yuuto.aimtune.app.accel.AccelSession
import de.yuuto.aimtune.app.accel.LastCalibration
import de.yuuto.aimtune.app.accel.LastSearch
import de.yuuto.aimtune.app.accel.PriorCalibration
import de.yuuto.aimtune.app.accel.HubSession
import de.yuuto.aimtune.app.accel.ImportedProfile
import de.yuuto.aimtune.app.accel.PatchedCopy
import de.yuuto.aimtune.app.accel.RawAccelSetting
import de.yuuto.aimtune.app.accel.RefineOutcome
import de.yuuto.aimtune.app.accel.RefineRun
import de.yuuto.aimtune.app.accel.SettingsAnalyses
import de.yuuto.aimtune.app.accel.RunsUse
import de.yuuto.aimtune.app.screens.RawAccelExportScreen
import de.yuuto.aimtune.app.screens.RawAccelHubScreen
import de.yuuto.aimtune.app.screens.RawAccelSettingScreen
import de.yuuto.aimtune.app.accel.RefineImport
import de.yuuto.aimtune.app.accel.RefineSession
import de.yuuto.aimtune.app.accel.RefineSource
import de.yuuto.aimtune.app.screens.RefineBetweenScreen
import de.yuuto.aimtune.app.screens.RefineImportScreen
import de.yuuto.aimtune.app.screens.RefineResultScreen
import de.yuuto.aimtune.app.screens.RefineStageScreen
import de.yuuto.aimtune.core.accel.RawAccelProfile
import de.yuuto.aimtune.core.accel.RefinePhase
import de.yuuto.aimtune.app.blind.AxisSummary
import de.yuuto.aimtune.app.blind.BlindRun
import de.yuuto.aimtune.app.blind.BlindSession
import de.yuuto.aimtune.app.blind.BlindTest
import de.yuuto.aimtune.app.blind.PastRound
import de.yuuto.aimtune.app.screens.AccelAnalysisScreen
import de.yuuto.aimtune.app.screens.AccelBetweenScreen
import de.yuuto.aimtune.app.screens.AccelIntroScreen
import de.yuuto.aimtune.app.screens.AccelLastAnalysisScreen
import de.yuuto.aimtune.app.screens.AccelLastSearchScreen
import de.yuuto.aimtune.app.screens.SoundSettingsScreen
import de.yuuto.aimtune.app.screens.AccelResultScreen
import de.yuuto.aimtune.app.screens.AccelSearchResultScreen
import de.yuuto.aimtune.app.history.HistoryEntry
import de.yuuto.aimtune.app.i18n.I18n
import de.yuuto.aimtune.app.i18n.Language
import de.yuuto.aimtune.app.history.buildHistory
import de.yuuto.aimtune.app.screens.BetweenRoundsScreen
import de.yuuto.aimtune.app.screens.CrosshairScreen
import de.yuuto.aimtune.app.screens.HistoryScreen
import de.yuuto.aimtune.app.screens.ShareDataScreen
import de.yuuto.aimtune.app.screens.StoredCounts
import de.yuuto.aimtune.app.screens.MainMenuScreen
import de.yuuto.aimtune.app.screens.OnboardingScreen
import de.yuuto.aimtune.app.screens.PreTestScreen
import de.yuuto.aimtune.app.screens.ResultsData
import de.yuuto.aimtune.app.screens.ResultsScreen
import de.yuuto.aimtune.app.screens.SettingsScreen
import de.yuuto.aimtune.app.screens.TrainerScreen
import de.yuuto.aimtune.app.screens.UiScreen
import de.yuuto.aimtune.app.setup.SetupDraft
import de.yuuto.aimtune.app.setup.SetupStep
import de.yuuto.aimtune.app.ui.UiHolder
import de.yuuto.aimtune.core.accel.AbStatus
import de.yuuto.aimtune.core.accel.AccelFit
import de.yuuto.aimtune.core.scenario.ScenarioKind
import de.yuuto.aimtune.core.score.FlashImportance
import de.yuuto.aimtune.core.scenario.ScenarioSchedule
import de.yuuto.aimtune.core.optimizer.OptimizationResult
import de.yuuto.aimtune.core.score.MetricWeights
import de.yuuto.aimtune.core.units.AimStyle
import de.yuuto.aimtune.core.units.searchRange
import de.yuuto.aimtune.games.GameProfiles
import de.yuuto.aimtune.input.RawMouseSource
import de.yuuto.aimtune.storage.CurveFinder
import de.yuuto.aimtune.app.files.ExportFiles
import de.yuuto.aimtune.app.files.FileDialogs
import de.yuuto.aimtune.app.files.TinyfdFileDialogs
import de.yuuto.aimtune.storage.RawAccelExport
import de.yuuto.aimtune.storage.ResearchExportResult
import de.yuuto.aimtune.storage.SettingsException
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

/**
 * The services every screen shares, and the navigation between screens. Created once in
 * [AimTuneApp.create]; screens never construct each other.
 */
class AppContext(
    private val game: Game,
    val options: LaunchOptions,
    val ui: UiHolder,
    val profiles: GameProfiles,
    val settings: SettingsHolder,
    val processor: RoundProcessor,
    val source: RawMouseSource,
    val gate: MouseGate,
    /** Problems found at startup (unreadable settings or game files) to show on the main menu. */
    val startupProblems: List<String>,
    /** Where exported Raw Accel files go; null without a data folder. */
    val exportDir: Path? = null,
    /** Where research exports go; null without a data folder. */
    val researchDir: Path? = null,
    /** Where the player drops Raw Accel settings.json files to refine; null without a data folder. */
    val importDir: Path? = null,
    /** The native open and save dialogs; a fake in the screenshot tour and in tests. */
    val fileDialogs: FileDialogs = TinyfdFileDialogs(),
) {
    private var nextSeed = options.seed ?: System.nanoTime()
    /** A newer release than this build, found by the update check; null when none is known. Set on the render thread. */
    @Volatile
    var availableUpdate: ReleaseInfo? = null

    /** What the Raw Accel hub keeps: the imported profile, the last refine result, the ticked recommendations and the analyses. */
    val hub = HubSession(settings, processor::settingsAnalyses, { processor.storedCount })

    /** Where the open settings screen returns to (a screen that linked to it), or null for the menu; the sound screen passes it on. */
    var settingsReturn: (() -> Unit)? = null
        private set

    private val flashSoundsDelegate = lazy { FlashSounds() }
    private val hitSoundsDelegate = lazy { HitSounds() }

    /** The hit sound; created on first use for the same reason. */
    val hitSounds: HitSounds get() = hitSoundsDelegate.value

    /** The flash cue sounds; created on first use because loading them needs the audio device. */
    val flashSounds: FlashSounds get() = flashSoundsDelegate.value

    /** Frees what the audio device holds; call when the app closes. */
    fun disposeAudio() {
        if (flashSoundsDelegate.isInitialized()) flashSoundsDelegate.value.dispose()
        if (hitSoundsDelegate.isInitialized()) hitSoundsDelegate.value.dispose()
    }
    private var loadedI18n: I18n? = null
    private var languageOverride: Language? = null

    /** The texts in the language of the settings; reloaded when the language changes. */
    val i18n: I18n
        get() {
            val language = languageOverride ?: Language.fromCode(settings.current.language)
            return loadedI18n?.takeIf { it.language == language } ?: I18n.load(language).also { loadedI18n = it }
        }

    /** The user's confirmation, for this run, that Raw Accel is off or linear (it cannot be detected). */
    var rawAccelConfirmed = false

    /**
     * Whether a new blind strength search builds on the stored searches. Off starts it from nothing, so it tries the
     * whole strength range again; the stored rounds are kept (their plain rounds still feed the calibration).
     */
    var usePriorSearch = true

    /** Whether the curve runs are played at the finder's result instead of the game sensitivity; only honoured when that result is confident. */
    var useFinderResult = false

    /** Whether the analysis path calibrates against the curve imported in the hub; honoured only when there is a usable one (docs/FIND-CURVE-REDESIGN.md step 1). */
    var useOwnCurve = true

    val isSetUp: Boolean
        get() = SetupDraft.fromSettings(settings.current).let { draft -> SetupStep.entries.all { draft.issue(it, profiles) == null } }

    /** Config for a trainer session at the current sensitivity, or null until setup is complete. */
    fun trainerConfig(): TrainerConfig? = trainerConfigFor(settings.current, profiles, nextSeed.also { nextSeed++ })

    /** Shows the UI in [language] before it is saved (the settings screen); cleared when the menu or settings open again. */
    fun previewLanguage(language: Language) {
        languageOverride = language
    }

    fun showMenu() {
        languageOverride = null
        switchTo(MainMenuScreen(this))
    }

    /** Start test: onboarding on first use, then the pre-test notice. */
    fun showStart() = if (isSetUp) showPreTest() else showOnboarding(afterFinish = ::showPreTest)

    fun showOnboarding(startStep: SetupStep = SetupStep.DPI, afterFinish: () -> Unit = ::showMenu) =
        switchTo(OnboardingScreen(this, SetupDraft.fromSettings(settings.current), startStep, afterFinish))

    fun showPreTest() = switchTo(PreTestScreen(this))

    /** The pre-test notice of the Range mode round set (menu entry "Range mode"). */
    fun showRangePreTest() = switchTo(PreTestScreen(this, rangeMode = true))

    /** Range mode from the menu: onboarding first if needed, then its notice; a game without a Range block has no such mode. */
    fun showRangeMode() = if (isSetUp) showRangePreTest() else showOnboarding(afterFinish = ::showRangePreTest)

    /** Whether the configured game has a Range mode. */
    fun hasRangeMode(): Boolean = trainerConfig()?.profile?.geometry?.range != null

    /** The settings; Save, Back and Escape go to [returnTo] when given (the screen that linked here), else to the menu. */
    fun showSettings(returnTo: (() -> Unit)? = null) {
        languageOverride = null
        settingsReturn = returnTo
        if (isSetUp) switchTo(SettingsScreen(this, returnTo)) else showOnboarding(afterFinish = returnTo ?: ::showMenu)
    }

    fun showCrosshair() {
        languageOverride = null
        switchTo(CrosshairScreen(this))
    }

    /** Starts a new blind test run, merging the stored rounds of this game and DPI; onboarding first if needed. */
    fun startBlindTest(rangeMode: Boolean = false) {
        val config = trainerConfig()
        val setup = settings.current
        val pad = setup.padWidthCm
        if (config == null || pad == null) return showOnboarding(afterFinish = if (rangeMode) ::showRangePreTest else ::showPreTest)
        val range = searchRange(setup.aimStyleOrNull() ?: AimStyle.MIXED, config.cm360, pad)
        val weights = metricWeights()
        val enabled = try {
            setup.enabledScenarios()
        } catch (e: SettingsException) {
            ScenarioKind.entries.toSet()
        }
        // A game without a Range mode cannot play range rounds, even if the setting still lists them.
        val hasRange = config.profile.geometry.range != null
        if (rangeMode && !hasRange) return showMenu()
        // Range mode plays range and flick in equal shares, whatever the saved toggles say; no flash, no tracking.
        val playable = if (rangeMode) setOf(ScenarioKind.FLICK, ScenarioKind.RANGE) else if (hasRange) enabled else enabled - ScenarioKind.RANGE
        val past = processor.pastBlindRounds(setup.gameId, config.dpi)
        val seed = nextSeed++
        val test = BlindTest(range, weights, past, seed)
        val schedule = ScenarioSchedule(playable.ifEmpty { setOf(ScenarioKind.FLICK) }, weights)
        showBlindRound(BlindRun(test, runKey = System.currentTimeMillis(), baseConfig = config, nextSeed = nextSeed++, schedule = schedule))
    }

    /** One blind round at the sensitivity the optimizer picked (hidden from the user). */
    fun showBlindRound(run: BlindRun) = switchTo(TrainerScreen(this, run.nextRoundConfig(), BlindSession(this, run)))

    fun showBetweenRounds(run: BlindRun) = switchTo(BetweenRoundsScreen(this, run))

    /** Ends the run and shows what it found; back to the menu when nothing was played or stored. */
    fun stopBlindTest(run: BlindRun) {
        val result = run.test.step().result
        val data = ResultsData(run.profile, run.dpi, run.currentCm360, result, run.test.totalRounds, run.test.sessionCount, run.test.scoredRounds(), run.test.axisSummary())
        run.test.close()
        if (run.test.totalRounds == 0) showMenu() else switchTo(ResultsScreen(this, data))
    }

    /** Results of the stored blind rounds, computed now; null until onboarding is complete. */
    fun showStoredResults() {
        val stored = storedAnalysis() ?: return showOnboarding(afterFinish = ::showMenu)
        val config = stored.config
        val data = ResultsData(
            config.profile, config.dpi, config.cm360, stored.result, stored.rounds.size, stored.rounds.map { it.sessionId }.distinct().size,
            BlindTest.scoreRounds(stored.weights, stored.rounds),
            AxisSummary.of(stored.rounds.map { it.axes }),
        )
        switchTo(ResultsScreen(this, data))
    }

    /** The stored blind rounds of the current game and DPI and what they say; null until onboarding is complete. */
    private fun storedAnalysis(): StoredAnalysis? {
        val config = trainerConfig() ?: return null
        val setup = settings.current
        val pad = setup.padWidthCm ?: return null
        val range = searchRange(setup.aimStyleOrNull() ?: AimStyle.MIXED, config.cm360, pad)
        val weights = metricWeights()
        val rounds = processor.pastBlindRounds(setup.gameId, config.dpi)
        return StoredAnalysis(config, weights, rounds, BlindTest.analyzeStored(range, weights, rounds))
    }

    private fun metricWeights(): MetricWeights = try {
        settings.current.toMetricWeights()
    } catch (e: SettingsException) {
        MetricWeights.DEFAULT
    }

    private class StoredAnalysis(val config: TrainerConfig, val weights: MetricWeights, val rounds: List<PastRound>, val result: OptimizationResult?)

    /**
     * The sensitivity the acceleration steps are played at: the configured game sensitivity, plus the finder's result for
     * the player to choose; null until the setup is complete.
     */
    fun accelBaseline(): AccelBaseline? {
        val config = trainerConfig() ?: return null
        val setup = settings.current
        val sensitivity = setup.inGameSensitivity ?: return null
        val imported = hub.restoreImported()
        val choice = imported?.let { RefineImport.choose(it.source, it.profileName, config.dpi) }
        val own = choice?.profile?.takeIf { useOwnCurve }
        val finder = storedAnalysis()?.let { stored ->
            stored.result?.let {
                val sensitivity = config.profile.formatSensitivity(config.profile.sensitivityFor(config.dpi, it.optimumCm360))
                FinderResult(it.optimumCm360, it.confidencePercent, stored.rounds.size, sensitivity, processor.priorSearch(config.dpi, it.optimumCm360, own).size)
            }
        }
        return AccelBaseline(
            config.cm360, config.profile.formatSensitivity(sensitivity), config.profile.name, finder, processor.priorSearch(config.dpi, config.cm360, own).size,
            ownCurve = choice?.profile, ownCurveLabel = imported?.let { "${it.fileLabel} · ${it.profileName}" }, ownCurveFileDpi = choice?.dpiMismatch, settingsDpi = config.dpi,
        )
    }

    /** Volume of the flash and hit sounds and the brightness of the flash white-out. */
    fun showSoundSettings() = switchTo(SoundSettingsScreen(this))

    fun showAccel(baseline: AccelBaseline? = accelBaseline()) = switchTo(AccelIntroScreen(this, baseline))

    /** The sensitivity every Raw Accel run is played at, with where it comes from; null until the setup is complete. */
    fun runsUse(baseline: AccelBaseline? = accelBaseline()): RunsUse? {
        val dpi = trainerConfig()?.dpi ?: return null
        return baseline?.let { RunsUse.of(it, dpi, useFinderResult) }
    }

    /** The Raw Accel hub; the passive analyses of the stored rounds are computed in the background. */
    fun showRawAccelHub(analyses: CompletableFuture<SettingsAnalyses> = hub.analyses()) =
        switchTo(RawAccelHubScreen(this, analyses, hub.restoreImported()))

    /** The page of one Raw Accel setting. */
    fun showRawAccelSetting(setting: RawAccelSetting, analyses: CompletableFuture<SettingsAnalyses> = hub.analyses()) =
        switchTo(RawAccelSettingScreen(this, setting, analyses))

    /** The export of the patched copy of the imported file, with the refine result and the ticked recommendations. */
    fun showPatchedExport(analyses: CompletableFuture<SettingsAnalyses> = hub.analyses()) = switchTo(RawAccelExportScreen(this, analyses))

    /** Remembers the imported profile for the hub (and its file in the settings). */
    fun importProfile(profile: ImportedProfile) = hub.setImported(profile)

    /** The newest blind strength search, analysed again from its stored rounds. */
    fun showLastSearch(last: LastSearch? = processor.lastSearch(metricWeights(), accelBaseline()?.ownCurve?.takeIf { useOwnCurve })) = switchTo(AccelLastSearchScreen(this, last))

    /** The analysis of the stored calibration rounds, computed again from them. */
    fun showLastAccelAnalysis(last: LastCalibration? = processor.lastCalibration()) = switchTo(AccelLastAnalysisScreen(this, last))

    /** The screen that exports the stored rounds for a researcher; the counts are read in the background. */
    fun showShareData() = switchTo(
        ShareDataScreen(this, CompletableFuture.supplyAsync { processor.storedHistory().let { StoredCounts(it.sessions.size, it.rounds.size) } }),
    )

    /** Writes the research file; fails with the reason when there is no data folder. */
    fun exportResearch(): CompletableFuture<Result<ResearchExportResult>> {
        val dir = researchDir ?: return CompletableFuture.completedFuture(Result.failure(IllegalStateException("no data folder to write to")))
        return processor.exportResearch(dir, APP_VERSION)
    }

    /** The history of stored sessions; the recommendation after each one is computed in the background. */
    fun showHistory() = switchTo(HistoryScreen(this, loadHistory()))

    private fun loadHistory(): CompletableFuture<List<HistoryEntry>> {
        val config = trainerConfig()
        val setup = settings.current
        val pad = setup.padWidthCm
        if (config == null || pad == null) return CompletableFuture.completedFuture(emptyList())
        val range = searchRange(setup.aimStyleOrNull() ?: AimStyle.MIXED, config.cm360, pad)
        val weights = metricWeights()
        return CompletableFuture.supplyAsync {
            val stored = processor.storedHistory()
            buildHistory(stored.sessions, stored.rounds, weights, range)
        }
    }

    /** Whether curves are bounded to the measured speeds (curve finder v2); the launch option overrides the setting. */
    val boundedCurves: Boolean get() = (options.curveFinder ?: settings.current.curveFinder) == CurveFinder.V2

    /** Starts the acceleration steps at the game sensitivity (or the finder's confident result, if chosen); onboarding until the setup is complete. */
    fun startAccelRun(search: Boolean = false) {
        if (!source.hasReportTimestamps) return showAccel()
        val baseline = accelBaseline() ?: return showOnboarding(afterFinish = ::showMenu)
        val config = trainerConfig() ?: return showOnboarding(afterFinish = ::showMenu)
        val base = config.copy(cm360 = baseline.cm360(useFinderResult))
        // A run looks for the earlier rounds of what it starts with: the calibration or the search.
        // On top of the player's own curve both paths play it: the analysis as its base, the search scaling its rise.
        val own = baseline.ownCurve?.takeIf { useOwnCurve }
        val prior = if (search) PriorCalibration.NONE else processor.priorCalibration(base.dpi, base.cm360, own)
        val priorSearch = if (search && usePriorSearch) processor.priorSearch(base.dpi, base.cm360, own) else emptyList()
        val run = AccelRun(
            base, metricWeights(), runKey = System.currentTimeMillis(), seed = nextSeed++, prior = prior, startWithSearch = search,
            flash = settings.current.flashImportance() != FlashImportance.NEVER, priorSearch = priorSearch,
            boundedCurves = boundedCurves, baseCurve = own,
        )
        showAccelStep(run)
    }

    /** Starts the blind test of [curve], which the stored calibration rounds gave, at the setup they were played with. */
    fun startAccelTest(curve: AccelFit, last: LastCalibration) {
        if (!source.hasReportTimestamps || !rawAccelConfirmed) return showAccel()
        val stored = storedAnalysis() ?: return showOnboarding(afterFinish = ::showMenu)
        val base = stored.config.copy(dpi = last.dpi, cm360 = last.cm360)
        showAccelStep(
            AccelRun(base, stored.weights, runKey = System.currentTimeMillis(), seed = nextSeed++, flash = settings.current.flashImportance() != FlashImportance.NEVER, startWithTest = curve, boundedCurves = boundedCurves),
        )
    }

    /** The screen that fits the run: a round while one is due, otherwise the analysis or the verdict. */
    fun showAccelStep(run: AccelRun) = switchTo(
        when (run.phase) {
            AccelPhase.CALIBRATION, AccelPhase.SEARCH, AccelPhase.TEST -> AccelBetweenScreen(this, run)
            AccelPhase.ANALYSIS -> AccelAnalysisScreen(this, run)
            AccelPhase.SEARCH_RESULT -> AccelSearchResultScreen(this, run)
            AccelPhase.DONE -> AccelResultScreen(this, run)
        },
    )

    fun showAccelRound(run: AccelRun) = switchTo(TrainerScreen(this, run.nextRoundConfig(), AccelSession(this, run)))

    /** Writes the Raw Accel settings.json of the tested curve; the path, or the reason it failed. */
    fun exportAccel(run: AccelRun): Result<Path> = exportAccel(run.candidate, run.dpi)

    /** Writes the Raw Accel settings.json of [fit] for a mouse at [dpi]; the path, or the reason it failed. */
    fun exportAccel(fit: AccelFit?, dpi: Double): Result<Path> = runCatching {
        val curve = checkNotNull(fit) { "there is no curve to export" }
        val dir = checkNotNull(exportDir) { "no data folder to write to" }
        RawAccelExport.write(dir, curve.curve, dpi, curve.sensitivityFactor)
    }

    /** Writes the Raw Accel settings.json of the tested curve to [path], chosen in the save dialog. */
    fun saveAccelAs(run: AccelRun, path: Path): Result<Path> = runCatching {
        val curve = checkNotNull(run.candidate) { "there is no curve to export" }
        RawAccelExport.settingsJson(curve.curve, run.dpi, curve.sensitivityFactor)
    }.mapCatching { ExportFiles.saveText(path, it).getOrThrow() }

    /** The import step of "Refine my Raw Accel curve". */
    fun showRefine() = switchTo(RefineImportScreen(this, imported = hub.imported))

    /** Starts the refine run for [profile] of [source] at the game sensitivity of the settings. */
    fun startRefineRun(source: RefineSource, profile: RawAccelProfile) {
        if (!this.source.hasReportTimestamps || !rawAccelConfirmed) return showRefine()
        val config = trainerConfig() ?: return showOnboarding(afterFinish = ::showMenu)
        showRefineStep(
            RefineRun(config, metricWeights(), runKey = System.currentTimeMillis(), seed = nextSeed++, source = source, profile = profile, flash = settings.current.flashImportance() != FlashImportance.NEVER),
        )
    }

    /** The screen that fits the refine run: a round while one is due, the stage result, or the verdict. */
    fun showRefineStep(run: RefineRun) = switchTo(
        when (run.phase) {
            RefinePhase.WARMUP, RefinePhase.SEARCH, RefinePhase.TEST -> RefineBetweenScreen(this, run)
            RefinePhase.STAGE_RESULT -> RefineStageScreen(this, run)
            RefinePhase.DONE -> {
                if (run.engine.verdict?.status == AbStatus.ACCEL_BETTER) hub.rememberRefine(RefineOutcome(run.source, run.profile, run.engine.candidate))
                RefineResultScreen(this, run)
            }
        },
    )

    fun showRefineRound(run: RefineRun) = switchTo(TrainerScreen(this, run.nextRoundConfig(), RefineSession(this, run)))

    /** The patched copy of a finished refine run, with the recommendations ticked on the setting pages. */
    fun patchedCopy(run: RefineRun, analyses: SettingsAnalyses? = null): PatchedCopy =
        PatchedCopy(run.source, run.profile, run.engine.candidate, hub.passiveChanges(analyses))

    /** Writes the patched copy into the export folder; the path, or the reason it failed. */
    fun exportPatched(copy: PatchedCopy): Result<Path> = runCatching {
        val dir = checkNotNull(exportDir) { "no data folder to write to" }
        RawAccelExport.write(dir, copy.root(), copy.exportName)
    }

    /** Like [exportPatched], to [path] chosen in the save dialog. */
    fun savePatchedAs(copy: PatchedCopy, path: Path): Result<Path> = runCatching { copy.root() }
        .mapCatching { ExportFiles.savePatched(path, it, copy.exportName).getOrThrow() }

    /** Number of stored blind rounds for the current setup, for the menu. */
    fun storedRoundCount(): Int {
        val config = trainerConfig() ?: return 0
        return processor.pastBlindRounds(settings.current.gameId, config.dpi).size
    }

    fun showTrainer() {
        val config = trainerConfig()
        if (config == null) showOnboarding(afterFinish = ::showPreTest) else switchTo(TrainerScreen(this, config))
    }

    /** Shows [screen] and disposes the one it replaces. */
    fun switchTo(screen: Screen) {
        val old = game.screen
        game.screen = screen
        old?.dispose()
    }

    /** Rebuilds the skin if the window size changed enough, and the current screen with it. */
    fun onResize() {
        val old = ui.updateFor(Gdx.graphics.backBufferHeight) ?: return
        (game.screen as? UiScreen)?.rebuild()
        old.dispose()
    }

    fun onWindowFocusLost() {
        (game.screen as? UiScreen)?.onWindowFocusLost()
        (game.screen as? TrainerScreen)?.onWindowFocusLost()
    }

    val currentScreen: Screen? get() = game.screen
}

private val APP_VERSION = AppVersion.current
