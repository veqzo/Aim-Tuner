package dev.veqzo.aimtune.app

import dev.veqzo.aimtune.storage.CurveFinder
import java.nio.file.Path
import java.nio.file.Paths

enum class SourceKind { AUTO, RAW, GLFW }

/** The size of the screenshot window in pixels. */
data class WindowSize(val width: Int, val height: Int)

class LaunchOptionsException(message: String) : Exception(message)

/**
 * Developer options from the command line. Everything about the user (DPI, game, sensitivity)
 * comes from onboarding and settings, not from here.
 */
data class LaunchOptions(
    /** First scenario seed; null means a fresh random seed per run (logged when a round starts). */
    val seed: Long?,
    val source: SourceKind,
    val vsync: Boolean,
    /** When set, the app walks through its screens with sample data, saves a PNG of each here and exits. */
    val screenshotDir: Path?,
    /** When set, every finished round is also written here as a raw event log (see `RoundLogFile`). */
    val recordDir: Path? = null,
    /** Language code of the sample setup in the screenshot tour; null is English. The user's own settings are not affected. */
    val screenshotLanguage: String? = null,
    /** Skip the daily check for a newer release on GitHub. */
    val noUpdateCheck: Boolean = false,
    /** Overrides the curve finder of the settings for this run; null uses the setting. */
    val curveFinder: CurveFinder? = null,
    /** Size of the hidden window of the screenshot tour; null is 1920x1080. */
    val windowSize: WindowSize? = null,
) {
    companion object {
        const val USAGE = """Usage: aimtune [options]
  --seed <n>           scenario seed of the first round, default random
  --source <s>         auto (default), raw (Windows Raw Input) or glfw
  --no-vsync           disable vertical sync
  --no-update-check    do not look for a newer release on GitHub
  --curve-finder <f>   legacy (fitted family) or v2 (bounded lookup table), default from the settings (v2)
  --screenshots <dir>  save a PNG of every screen into <dir> using sample data, then exit
  --language <code>    language of the screenshot tour (en or de), default en
  --window-size <WxH>  size of the screenshot tour's window in pixels, default 1920x1080 (e.g. 1280x720)
  --record <dir>       also save every finished round into <dir> as a raw event log (regression fixture)"""
    }
}

private val VALUE_OPTIONS = setOf("--seed", "--source", "--screenshots", "--record", "--language", "--curve-finder", "--window-size")

fun parseLaunchOptions(args: Array<String>): LaunchOptions {
    val values = mutableMapOf<String, String>()
    var vsync = true
    var noUpdateCheck = false
    var i = 0
    while (i < args.size) {
        val arg = args[i]
        when {
            arg == "--no-vsync" -> vsync = false
            arg == "--no-update-check" -> noUpdateCheck = true
            arg in VALUE_OPTIONS && i + 1 < args.size -> values[arg] = args[++i]
            arg in VALUE_OPTIONS -> throw LaunchOptionsException("missing value for $arg")
            else -> throw LaunchOptionsException("unknown option: $arg")
        }
        i++
    }
    val source = when (val raw = values["--source"]?.lowercase()) {
        null, "auto" -> SourceKind.AUTO
        "raw" -> SourceKind.RAW
        "glfw" -> SourceKind.GLFW
        else -> throw LaunchOptionsException("--source must be auto, raw or glfw, was '$raw'")
    }
    val curveFinder = when (val raw = values["--curve-finder"]?.lowercase()) {
        null -> null
        "legacy" -> CurveFinder.LEGACY
        "v2" -> CurveFinder.V2
        else -> throw LaunchOptionsException("--curve-finder must be legacy or v2, was '$raw'")
    }
    val windowSize = values["--window-size"]?.let { raw ->
        val parts = raw.lowercase().split('x').map { it.toIntOrNull() }
        if (parts.size != 2 || parts.any { it == null || it <= 0 }) throw LaunchOptionsException("--window-size must look like 1280x720, was '$raw'")
        WindowSize(checkNotNull(parts[0]), checkNotNull(parts[1]))
    }
    val seed = values["--seed"]?.let { it.toLongOrNull() ?: throw LaunchOptionsException("--seed must be an integer, was '$it'") }
    return LaunchOptions(seed, source, vsync, values["--screenshots"]?.let { Paths.get(it) }, values["--record"]?.let { Paths.get(it) }, values["--language"], noUpdateCheck, curveFinder, windowSize)
}
