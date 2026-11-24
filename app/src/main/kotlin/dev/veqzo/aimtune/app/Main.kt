package dev.veqzo.aimtune.app

import com.badlogic.gdx.Files
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import dev.veqzo.aimtune.app.ui.Tokens
import kotlin.system.exitProcess

private const val SCREENSHOT_WIDTH = 1920
private const val SCREENSHOT_HEIGHT = 1080
private const val IDLE_FPS = 30
private const val DEPTH_BITS = 24
private const val MSAA_SAMPLES = 4

// The app icon (docs/design/icon) in the sizes Windows picks from for the title bar and the taskbar.
private val WINDOW_ICONS = arrayOf(16, 32, 48, 128).map { "assets/icon/aimtune-$it.png" }.toTypedArray()

fun main(args: Array<String>) {
    val options = try {
        parseLaunchOptions(args)
    } catch (e: LaunchOptionsException) {
        System.err.println("error: ${e.message}\n\n${LaunchOptions.USAGE}")
        exitProcess(2)
    }
    val app = AimTuneApp(options)
    val screenshots = options.screenshotDir != null
    val config = Lwjgl3ApplicationConfiguration().apply {
        setTitle("AimTune")
        setWindowIcon(Files.FileType.Classpath, *WINDOW_ICONS)
        val tour = options.windowSize ?: WindowSize(SCREENSHOT_WIDTH, SCREENSHOT_HEIGHT)
        setWindowedMode(if (screenshots) tour.width else WINDOW_WIDTH, if (screenshots) tour.height else WINDOW_HEIGHT)
        // The screenshot tour renders in a hidden window and does not wait for vertical sync.
        useVsync(options.vsync && !screenshots)
        setInitialVisible(!screenshots)
        setIdleFPS(IDLE_FPS)
        setBackBufferConfig(8, 8, 8, 8, DEPTH_BITS, 0, MSAA_SAMPLES)
        setInitialBackgroundColor(Tokens.load().bg)
        setWindowListener(app.windowListener)
    }
    Lwjgl3Application(app, config)
}
