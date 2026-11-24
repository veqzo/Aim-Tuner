package de.yuuto.aimtune.app.update

import java.nio.file.Files
import java.nio.file.Path

/** How this copy of the app got onto the machine, which decides how an update is put in place. */
enum class InstallKind {
    /** Under Program Files or the user's Programs folder: the installer of the release upgrades it. */
    INSTALLED,

    /** An unpacked zip somewhere else: its files are replaced by those of the new zip. */
    PORTABLE,

    /** Not started from the packaged launcher (a run from Gradle or an IDE): nothing to replace. */
    UNKNOWN,
}

/** What an update does with the file it downloaded. The app is closed by the caller once [apply] returns successfully. */
object UpdateApplier {
    private const val LAUNCHER = "AimTune.exe"

    /** The kind of install the launcher at [launcher] belongs to; [installRoots] are the folders installers put programs in. */
    fun kindOf(launcher: Path?, installRoots: List<Path>): InstallKind {
        if (launcher == null || launcher.fileName?.toString()?.equals(LAUNCHER, ignoreCase = true) != true) return InstallKind.UNKNOWN
        val normalized = launcher.toAbsolutePath().normalize()
        return if (installRoots.any { normalized.startsWith(it.toAbsolutePath().normalize()) }) InstallKind.INSTALLED else InstallKind.PORTABLE
    }

    /** The launcher of the running app (`AimTune.exe`), or null when the app was not started from it. */
    fun runningLauncher(): Path? = ProcessHandle.current().info().command().map { Path.of(it) }.orElse(null)

    fun installRoots(): List<Path> = listOfNotNull(
        System.getenv("ProgramFiles"), System.getenv("ProgramFiles(x86)"), System.getenv("ProgramW6432"), System.getenv("LOCALAPPDATA")?.let { "$it\\Programs" },
    ).filter { it.isNotBlank() }.map { Path.of(it) }

    /**
     * Starts what puts [file] in place and returns; the caller then closes the app. An installed copy runs the installer (its own
     * window and, where needed, the Windows permission prompt); a portable copy runs a small script that waits for this process to end,
     * unpacks the zip next to it and starts the new launcher. Nothing is run through a shell string: the paths are separate arguments.
     */
    fun apply(kind: InstallKind, file: Path, launcher: Path?, workDir: Path) {
        when (kind) {
            InstallKind.INSTALLED -> ProcessBuilder(file.toAbsolutePath().toString()).directory(file.toAbsolutePath().parent.toFile()).start()
            InstallKind.PORTABLE -> {
                val target = checkNotNull(launcher) { "no launcher to restart" }.toAbsolutePath().parent
                val script = workDir.resolve("apply-update.ps1")
                Files.writeString(script, PORTABLE_SCRIPT)
                val powershell = Path.of(System.getenv("SystemRoot") ?: "C:\\Windows", "System32", "WindowsPowerShell", "v1.0", "powershell.exe")
                check(Files.isRegularFile(powershell)) { "Windows PowerShell was not found" }
                ProcessBuilder(
                    powershell.toString(), "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden", "-File", script.toString(),
                    "-ProcessId", ProcessHandle.current().pid().toString(), "-Zip", file.toAbsolutePath().toString(), "-Target", target.toString(),
                ).start()
            }
            InstallKind.UNKNOWN -> error("this run cannot update itself")
        }
    }

    /**
     * Waits for the old process, unpacks, then swaps the folders: the old one is renamed to `.old`, the new one copied in its place and,
     * if that fails, the old one renamed back. The launcher is started in every case, and then it is either the new app or the old one
     * untouched, never a mix. A reason for a failure is written next to the zip.
     */
    private val PORTABLE_SCRIPT = """
        param([int]${'$'}ProcessId, [string]${'$'}Zip, [string]${'$'}Target)
        ${'$'}ErrorActionPreference = 'Stop'
        ${'$'}launcher = Join-Path ${'$'}Target 'AimTune.exe'
        ${'$'}old = ${'$'}Target.TrimEnd('\') + '.old'
        ${'$'}stage = Join-Path ([System.IO.Path]::GetTempPath()) ('aimtune-update-' + [guid]::NewGuid())
        try {
            Wait-Process -Id ${'$'}ProcessId -Timeout 60 -ErrorAction SilentlyContinue
            if (Get-Process -Id ${'$'}ProcessId -ErrorAction SilentlyContinue) { throw 'the app did not close' }
            Expand-Archive -LiteralPath ${'$'}Zip -DestinationPath ${'$'}stage -Force
            ${'$'}source = Join-Path ${'$'}stage 'AimTune'
            if (-not (Test-Path (Join-Path ${'$'}source 'AimTune.exe'))) { throw 'the archive has no AimTune folder' }
            if (Test-Path -LiteralPath ${'$'}old) { Remove-Item -LiteralPath ${'$'}old -Recurse -Force }
            Move-Item -LiteralPath ${'$'}Target -Destination ${'$'}old
            try {
                Copy-Item -LiteralPath ${'$'}source -Destination ${'$'}Target -Recurse
            } catch {
                if (Test-Path -LiteralPath ${'$'}Target) { Remove-Item -LiteralPath ${'$'}Target -Recurse -Force -ErrorAction SilentlyContinue }
                Move-Item -LiteralPath ${'$'}old -Destination ${'$'}Target
                throw
            }
            Remove-Item -LiteralPath ${'$'}old -Recurse -Force -ErrorAction SilentlyContinue
        } catch {
            try { ${'$'}_ | Out-String | Set-Content -LiteralPath (Join-Path (Split-Path ${'$'}Zip) 'update-error.txt') } catch { }
        } finally {
            Remove-Item -LiteralPath ${'$'}stage -Recurse -Force -ErrorAction SilentlyContinue
        }
        if (Test-Path -LiteralPath ${'$'}launcher) { Start-Process -FilePath ${'$'}launcher }
    """.trimIndent()
}
