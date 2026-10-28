package de.yuuto.aimtune.app

import com.badlogic.gdx.Game
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Graphics
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3WindowAdapter
import de.yuuto.aimtune.app.files.FakeFileDialogs
import de.yuuto.aimtune.app.files.TinyfdFileDialogs
import de.yuuto.aimtune.app.input.GlfwMouseSource
import de.yuuto.aimtune.app.screens.MainMenuScreen
import de.yuuto.aimtune.app.screens.TrainerScreen
import de.yuuto.aimtune.app.update.AppVersion
import de.yuuto.aimtune.app.update.UpdateChecker
import de.yuuto.aimtune.app.update.UpdateService
import de.yuuto.aimtune.app.ui.Tokens
import de.yuuto.aimtune.app.ui.UiHolder
import de.yuuto.aimtune.games.GameProfiles
import de.yuuto.aimtune.input.RawMouseSource
import de.yuuto.aimtune.input.windows.WindowsRawInputSource
import de.yuuto.aimtune.storage.AppPaths
import de.yuuto.aimtune.storage.AppSettings
import de.yuuto.aimtune.storage.Database
import de.yuuto.aimtune.storage.SettingsStore
import org.lwjgl.glfw.GLFW

internal const val WINDOW_WIDTH = 1280
internal const val WINDOW_HEIGHT = 720

/** LibGDX entry point: wires the services together and hands over to the screens. */
class AimTuneApp(private val options: LaunchOptions) : Game() {
    private val log = System.getLogger("aimtune.app")
    private var context: AppContext? = null
    private var tour: ScreenshotTour? = null

    /** Reports window focus to the mouse gate; installed through the application configuration. */
    val windowListener = object : Lwjgl3WindowAdapter() {
        override fun focusGained() {
            context?.gate?.setWindowFocused(true)
        }

        override fun focusLost() {
            // Hard rule: no mouse input while unfocused.
            context?.gate?.setWindowFocused(false)
            context?.onWindowFocusLost()
        }
    }

    override fun create() {
        val tokens = Tokens.load()
        val sampleMode = options.screenshotDir != null
        val problems = mutableListOf<String>()

        val paths = if (sampleMode) null else resolvePaths(problems)
        val settings = if (sampleMode) SettingsHolder(null, sampleSettings(options.screenshotLanguage)) else loadSettings(paths, problems)
        val profiles = GameProfiles.load(paths?.dataDir?.resolve("games")).also { problems += it.problems }
        val database = if (paths == null) null else openDatabase(paths, problems)

        val source = startSource()
        val gate = MouseGate(source)
        gate.setWindowFocused(isWindowFocused())
        log.log(System.Logger.Level.INFO, "mouse source: ${source.name}")

        val ctx = AppContext(
            game = this,
            options = options,
            ui = UiHolder(tokens, Gdx.graphics.backBufferHeight),
            profiles = profiles,
            settings = settings,
            processor = RoundProcessor(database, options.recordDir),
            source = source,
            gate = gate,
            startupProblems = problems,
            exportDir = paths?.dataDir?.resolve("exports")?.resolve("rawaccel"),
            researchDir = paths?.dataDir?.resolve("exports")?.resolve("research"),
            importDir = paths?.dataDir?.resolve("imports")?.also { runCatching { java.nio.file.Files.createDirectories(it) } },
            // The tour must never open a real dialog.
            fileDialogs = if (sampleMode) FakeFileDialogs() else TinyfdFileDialogs(),
        )
        context = ctx
        val screenshotDir = options.screenshotDir
        if (screenshotDir == null) ctx.showMenu() else tour = ScreenshotTour(ctx, screenshotDir) { Gdx.app.exit() }
        if (screenshotDir == null && !options.noUpdateCheck) startUpdateCheck(ctx)
        if (screenshotDir == null && ctx.settings.current.fullscreen) setFullscreen(true)
    }

    /** Fullscreen in the display's current mode (no resolution change); windowed goes back to the start size. */
    private fun setFullscreen(on: Boolean) {
        if (on) Gdx.graphics.setFullscreenMode(Gdx.graphics.displayMode) else Gdx.graphics.setWindowedMode(WINDOW_WIDTH, WINDOW_HEIGHT)
    }

    /** F11 works on every screen but a running round, where a window change would lose the focus and abort it. */
    private fun toggleFullscreenOnF11() {
        val ctx = context ?: return
        if (!Gdx.input.isKeyJustPressed(Input.Keys.F11) || screen is TrainerScreen) return
        val on = !Gdx.graphics.isFullscreen
        setFullscreen(on)
        // A damaged settings file is never overwritten by a side effect.
        if (ctx.startupProblems.isEmpty()) ctx.settings.save(ctx.settings.current.copy(fullscreen = on))
    }

    /**
     * Looks for a newer release in the background. A failed check (no network, a private repository) changes nothing the
     * player sees. The result is only saved when the settings loaded cleanly, so a damaged file is never overwritten.
     */
    private fun startUpdateCheck(ctx: AppContext) {
        val service = UpdateService(latest = { UpdateChecker().latest() }, current = AppVersion.version)
        ctx.availableUpdate = service.known(ctx.settings.current)
        service.checkIfDue(ctx.settings.current) { checkedAt, release ->
            Gdx.app.postRunnable {
                ctx.recordUpdateCheck(checkedAt, release)
                (screen as? MainMenuScreen)?.rebuild()
            }
        }
    }

    override fun resize(width: Int, height: Int) {
        if (width == 0 || height == 0) return
        context?.onResize()
        super.resize(width, height)
    }

    override fun render() {
        if (tour == null) toggleFullscreenOnF11()
        super.render()
        tour?.afterRender()
    }

    override fun dispose() {
        super.dispose()
        screen?.dispose()
        context?.let {
            it.disposeAudio()
            it.ui.dispose()
            it.source.close()
            it.processor.close()
        }
    }

    private fun resolvePaths(problems: MutableList<String>): AppPaths? = try {
        AppPaths.default()
    } catch (e: IllegalStateException) {
        log.log(System.Logger.Level.ERROR, "no data directory: ${e.message}", e)
        problems += "${e.message} Settings and rounds will not be saved."
        null
    }

    private fun loadSettings(paths: AppPaths?, problems: MutableList<String>): SettingsHolder {
        if (paths == null) return SettingsHolder(null, AppSettings())
        val (holder, problem) = SettingsHolder.load(SettingsStore(paths.settingsFile))
        if (problem != null) {
            log.log(System.Logger.Level.ERROR, problem)
            problems += "$problem Using defaults; your file is not overwritten until you save."
        }
        return holder
    }

    /** A storage failure (unwritable data dir, newer schema) must not stop the trainer; rounds are then analysed but not saved. */
    private fun openDatabase(paths: AppPaths, problems: MutableList<String>): Database? = try {
        Database.open(paths.databaseFile)
    } catch (e: Exception) {
        log.log(System.Logger.Level.ERROR, "rounds will not be saved: ${e.message}", e)
        problems += "Rounds will not be saved: ${e.message}"
        null
    }

    private fun isWindowFocused(): Boolean =
        GLFW.glfwGetWindowAttrib(windowHandle(), GLFW.GLFW_FOCUSED) == GLFW.GLFW_TRUE

    private fun windowHandle(): Long = (Gdx.graphics as Lwjgl3Graphics).window.windowHandle

    /** Starts the chosen source; with `auto`, a Raw Input window that cannot be created falls back to GLFW instead of ending the app. */
    private fun startSource(): RawMouseSource {
        val chosen = createSource()
        try {
            chosen.start()
            return chosen
        } catch (e: IllegalStateException) {
            if (options.source != SourceKind.AUTO || chosen is GlfwMouseSource) throw e
            log.log(System.Logger.Level.ERROR, "Raw Input could not be started (${e.message}); using the GLFW fallback", e)
            chosen.close()
            return GlfwMouseSource(windowHandle()).also { it.start() }
        }
    }

    private fun createSource(): RawMouseSource {
        val isWindows = System.getProperty("os.name").startsWith("Windows")
        return when (options.source) {
            SourceKind.GLFW -> GlfwMouseSource(windowHandle())
            SourceKind.RAW -> {
                check(isWindows) { "--source raw needs Windows" }
                WindowsRawInputSource()
            }
            SourceKind.AUTO -> if (isWindows) WindowsRawInputSource() else GlfwMouseSource(windowHandle())
        }
    }

    /** Sample setup used only by the screenshot tour; it is never shown as the user's data. */
    private fun sampleSettings(language: String?) = AppSettings(
        language = language, dpi = 800.0, gameId = "cs2", inGameSensitivity = 1.2, aimStyle = "MIXED", padWidthCm = 45.0, padHeightCm = 40.0,
    )
}
