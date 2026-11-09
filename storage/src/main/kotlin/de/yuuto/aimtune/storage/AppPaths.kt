package de.yuuto.aimtune.storage

import java.nio.file.Path
import java.nio.file.Paths

/** Files AimTune keeps in its data directory. */
class AppPaths(val dataDir: Path) {
    val databaseFile: Path get() = dataDir.resolve("aimtune.db")
    val settingsFile: Path get() = dataDir.resolve("settings.json")

    /** Copies of the database that "Start over" made before it cleared the rounds. */
    val backupDir: Path get() = dataDir.resolve("backups")

    companion object {
        const val DATA_DIR_ENV = "AIMTUNE_DATA_DIR"

        /**
         * `AIMTUNE_DATA_DIR` if set, else `%APPDATA%\AimTune` on Windows, else
         * `$XDG_DATA_HOME/aimtune` or `~/.local/share/aimtune`.
         */
        fun default(env: Map<String, String> = System.getenv(), osName: String = System.getProperty("os.name")): AppPaths {
            env[DATA_DIR_ENV]?.takeIf { it.isNotBlank() }?.let { return AppPaths(Paths.get(it)) }
            if (osName.startsWith("Windows")) {
                val appData = env["APPDATA"]?.takeIf { it.isNotBlank() }
                    ?: error("APPDATA is not set; set $DATA_DIR_ENV to choose where AimTune stores its data")
                return AppPaths(Paths.get(appData, "AimTune"))
            }
            val base = env["XDG_DATA_HOME"]?.takeIf { it.isNotBlank() }?.let { Paths.get(it) }
                ?: Paths.get(System.getProperty("user.home"), ".local", "share")
            return AppPaths(base.resolve("aimtune"))
        }
    }
}
