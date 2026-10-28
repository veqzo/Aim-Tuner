package de.yuuto.aimtune.app

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.PixmapIO
import com.badlogic.gdx.utils.BufferUtils
import com.badlogic.gdx.utils.ScreenUtils
import de.yuuto.aimtune.app.accel.AccelPhase
import de.yuuto.aimtune.app.accel.LastCalibration
import de.yuuto.aimtune.app.accel.LastSearch
import de.yuuto.aimtune.app.accel.PriorCalibration
import de.yuuto.aimtune.app.accel.SettingsAnalyses
import de.yuuto.aimtune.app.accel.StageProgress
import de.yuuto.aimtune.app.accel.sampleAccelRun
import de.yuuto.aimtune.app.accel.sampleDraftRun
import de.yuuto.aimtune.app.accel.sampleOwnCurve
import de.yuuto.aimtune.app.screens.AccelIntroScreen
import de.yuuto.aimtune.core.accel.FeltStage
import de.yuuto.aimtune.core.accel.FeltStepFamily
import de.yuuto.aimtune.core.accel.HoldReason
import de.yuuto.aimtune.core.accel.PlanState
import de.yuuto.aimtune.core.accel.StageAction
import de.yuuto.aimtune.core.accel.StageDecision
import de.yuuto.aimtune.core.accel.StageMisfit
import de.yuuto.aimtune.core.accel.StageSession
import de.yuuto.aimtune.app.blind.sampleBlindRun
import de.yuuto.aimtune.app.history.HistoryEntry
import de.yuuto.aimtune.app.accel.sampleRefineRun
import de.yuuto.aimtune.app.accel.sampleRefineText
import de.yuuto.aimtune.app.accel.ImportedProfile
import de.yuuto.aimtune.app.accel.RawAccelSetting
import de.yuuto.aimtune.app.accel.RefineImport
import de.yuuto.aimtune.app.accel.sampleSettingsAnalyses
import de.yuuto.aimtune.app.screens.RefineImportScreen
import de.yuuto.aimtune.app.screens.HistoryScreen
import de.yuuto.aimtune.app.screens.ShareDataScreen
import de.yuuto.aimtune.app.screens.StoredCounts
import de.yuuto.aimtune.app.screens.TrainerScreen
import de.yuuto.aimtune.app.setup.SetupStep
import de.yuuto.aimtune.core.scenario.ScenarioKind
import de.yuuto.aimtune.storage.SessionKind
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

private const val FRAMES_BEFORE_CAPTURE = 4
private const val OPAQUE = 255.toByte()
private const val BYTES_PER_PIXEL = 4
private const val RANGE_SEED_TRIES = 500L
private const val RANGE_TOUR_BEARING = 20.0
private val RANGE_TOUR_DISTANCE = 7.0..12.0

/**
 * Developer tool (`--screenshots <dir>`): shows every screen with sample data, saves a PNG of
 * each and exits. It runs on in-memory settings and never touches the user's data. Used to check
 * layouts without a person clicking through the app, and later for the README screenshots.
 */
class ScreenshotTour(private val ctx: AppContext, private val dir: Path, private val exit: () -> Unit) {
    private class Step(val name: String, val show: () -> Unit)

    private val log = System.getLogger("aimtune.screenshots")
    private val steps = listOf(
        Step("menu") { ctx.showMenu() },
        Step("onboarding-1-dpi") { ctx.showOnboarding(SetupStep.DPI) },
        Step("onboarding-2-game") { ctx.showOnboarding(SetupStep.GAME) },
        Step("onboarding-3-style") { ctx.showOnboarding(SetupStep.STYLE) },
        Step("onboarding-4-pad") { ctx.showOnboarding(SetupStep.PAD) },
        Step("pretest") { ctx.showPreTest() },
        Step("settings") { ctx.showSettings() },
        Step("sound-settings") { ctx.showSoundSettings() },
        Step("crosshair") { ctx.showCrosshair() },
        Step("trainer-ready") { ctx.showTrainer() },
        Step("trainer-running") { (ctx.currentScreen as? TrainerScreen)?.startRoundForTour() },
        Step("micro-ready") { showScenario(ScenarioKind.MICRO) },
        Step("micro-running") { (ctx.currentScreen as? TrainerScreen)?.startRoundForTour() },
        Step("tracking-ready") { showScenario(ScenarioKind.TRACKING) },
        Step("tracking-running") { (ctx.currentScreen as? TrainerScreen)?.startRoundForTour() },
        Step("switching-ready") { showScenario(ScenarioKind.SWITCHING) },
        Step("switching-running") { (ctx.currentScreen as? TrainerScreen)?.startRoundForTour() },
        Step("range-ready") { showScenario(ScenarioKind.RANGE) },
        Step("range-running") { (ctx.currentScreen as? TrainerScreen)?.startRoundForTour() },
        Step("hub") { showHub { ctx.showRawAccelHub(it) } },
        Step("hub-setting-info") { showHub { ctx.showRawAccelSetting(RawAccelSetting.SNAP, it) } },
        Step("hub-setting-analysis") { showHub { ctx.showRawAccelSetting(RawAccelSetting.ROTATION, it) } },
        Step("hub-export") { showHub { ctx.hub.setTicked(RawAccelSetting.ROTATION, true); ctx.showPatchedExport(it) } },
        Step("accel-intro") { ctx.showAccel() },
        Step("accel-intro-prior") { ctx.showAccel(AccelBaseline(48.7, "0.335", "Valorant", FinderResult(34.4, 61, 40, "0.474", searchRounds = 50), gameSearchRounds = 12)) },
        Step("accel-intro-plan") {
            val stage = FeltStepFamily(sampleOwnCurve, 800.0).table(0.08, 9.0, 22.0)
            val state = PlanState(1, listOf(StageSession(StageMisfit(-0.02, 0.01), 50.0, 15, 0L)), StageDecision(StageAction.HOLD, HoldReason.SESSIONS))
            val baseline = AccelBaseline(48.7, "0.335", "Valorant", gameSearchRounds = 0, ownCurve = sampleOwnCurve, ownCurveLabel = "settings.json: default", settingsDpi = 800.0)
            ctx.switchTo(AccelIntroScreen(ctx, baseline, StageProgress(state, stage, FeltStage(0.08, 9.0, 22.0), mapOf(0 to 3, 1 to 1))))
        },
        Step("accel-draft") { ctx.trainerConfig()?.let(::sampleDraftRun)?.let(ctx::showAccelStep) },
        Step("accel-last") { accelSample(AccelPhase.ANALYSIS)?.let { ctx.showLastAccelAnalysis(LastCalibration(it.dpi, it.baseCm360, PriorCalibration(it.calibrationDone, it.calibrationSamples))) } },
        Step("accel-last-search") { accelSample(AccelPhase.SEARCH_RESULT)?.let { ctx.showLastSearch(LastSearch(it.dpi, it.baseCm360, it.searchRounds, 2, it.searchResult)) } },
        Step("accel-calibration") { accelSample(AccelPhase.CALIBRATION)?.let(ctx::showAccelStep) },
        Step("accel-analysis") { accelSample(AccelPhase.ANALYSIS)?.let(ctx::showAccelStep) },
        Step("accel-search") { accelSample(AccelPhase.SEARCH)?.let(ctx::showAccelStep) },
        Step("accel-search-result") { accelSample(AccelPhase.SEARCH_RESULT)?.let(ctx::showAccelStep) },
        Step("accel-test") { accelSample(AccelPhase.TEST)?.let(ctx::showAccelStep) },
        Step("accel-result") { accelSample(AccelPhase.DONE)?.let(ctx::showAccelStep) },
        Step("refine-import") { ctx.switchTo(RefineImportScreen(ctx, sampleRefineText())) },
        Step("refine-stage") { ctx.trainerConfig()?.let(::sampleRefineRun)?.let(ctx::showRefineStep) },
        Step("blind-ready") { sample?.let(ctx::showBlindRound) },
        Step("blind-running") { (ctx.currentScreen as? TrainerScreen)?.startRoundForTour() },
        Step("between-rounds") { sample?.let(ctx::showBetweenRounds) },
        Step("results") { sample?.let(ctx::stopBlindTest) },
        Step("history") { ctx.switchTo(HistoryScreen(ctx, CompletableFuture.completedFuture(sampleHistory()))) },
        Step("settings-range") { showRangeSettings() },
        Step("menu-range") { ctx.showMenu() },
        Step("range-pretest") { ctx.showRangePreTest() },
        Step("share-data") { ctx.switchTo(ShareDataScreen(ctx, CompletableFuture.completedFuture(StoredCounts(3, 42)))) },
    )
    private val sample by lazy { ctx.trainerConfig()?.let(::sampleBlindRun) }
    private var index = -1
    private var frames = 0

    private fun sampleHistory(): List<HistoryEntry> {
        val day = 24L * 60 * 60 * 1000
        val start = System.currentTimeMillis() - 9 * day
        val trend = listOf(47.5 to 31, 45.8 to 44, 44.1 to 52, 44.9 to 63, 43.6 to 71, 43.9 to 78, 43.4 to 83)
        return trend.mapIndexed { i, (cm, confidence) ->
            HistoryEntry(i + 1L, start + i * day, "valorant", SessionKind.SENSITIVITY, 10, cm, confidence)
        } + HistoryEntry(8, start + 8 * day, "valorant", SessionKind.ACCEL_AB, 14, null, null)
    }

    /** The settings of a game with Range mode and the Range tile switched on, which shows the extra row of options. */
    private fun showRangeSettings() {
        val current = ctx.settings.current
        ctx.settings.save(current.copy(gameId = "valorant", scenarios = (current.scenarios + ScenarioKind.RANGE.id).distinct()))
        ctx.showSettings()
    }

    /** The hub and its pages with made-up analyses and the sample profile imported; the tick on the rotation is cleared again. */
    private fun showHub(show: (CompletableFuture<SettingsAnalyses>) -> Unit) {
        val source = sampleRefineText()?.let { RefineImport.parse(it, "settings.json").getOrNull() }
        ctx.hub.useSample(source?.let { ImportedProfile(it, it.file.profileNames.first()) })
        show(CompletableFuture.completedFuture(sampleSettingsAnalyses()))
    }

    private fun accelSample(until: AccelPhase) = ctx.trainerConfig()?.let { sampleAccelRun(it, until) }

    private fun showScenario(kind: ScenarioKind) {
        ctx.trainerConfig()?.let {
            val config = if (kind == ScenarioKind.RANGE) it.copy(scenario = kind, seed = rangeTourSeed(it)) else it.copy(scenario = kind)
            ctx.switchTo(TrainerScreen(ctx, config))
        }
    }

    /** A seed whose first bot stands in front of the player, 7-12 m away, so the screenshot shows it. */
    private fun rangeTourSeed(config: TrainerConfig): Long = (config.seed until config.seed + RANGE_SEED_TRIES).first { seed ->
        val scenario = ScenarioKind.RANGE.create(1_000_000_000L, seed, config.geometry, config.rangeOptions).also { it.start(0L) }
        val head = scenario.targets.first().center
        Math.abs(Math.toDegrees(Math.atan2(head.x, -head.z))) < RANGE_TOUR_BEARING && Math.hypot(head.x, head.z) in RANGE_TOUR_DISTANCE
    }

    /** Call after every rendered frame. */
    fun afterRender() {
        if (index < 0) {
            Files.createDirectories(dir)
            advance()
            return
        }
        if (++frames < FRAMES_BEFORE_CAPTURE) return
        capture(steps[index].name)
        advance()
    }

    private fun advance() {
        index++
        frames = 0
        if (index >= steps.size) {
            log.log(System.Logger.Level.INFO, "screenshots saved to $dir")
            exit()
            return
        }
        steps[index].show()
    }

    private fun capture(name: String) {
        val width = Gdx.graphics.backBufferWidth
        val height = Gdx.graphics.backBufferHeight
        val pixels = ScreenUtils.getFrameBufferPixels(0, 0, width, height, true)
        // The frame buffer's alpha is not meaningful; make the whole image opaque, as shown on screen.
        for (i in BYTES_PER_PIXEL - 1 until pixels.size step BYTES_PER_PIXEL) pixels[i] = OPAQUE
        val pixmap = Pixmap(width, height, Pixmap.Format.RGBA8888)
        BufferUtils.copy(pixels, 0, pixmap.pixels, pixels.size)
        PixmapIO.writePNG(Gdx.files.absolute(dir.resolve("$name.png").toAbsolutePath().toString()), pixmap)
        pixmap.dispose()
        log.log(System.Logger.Level.INFO, "saved $name.png (${width}x$height)")
    }
}
