package de.yuuto.aimtune.storage

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

/**
 * Schema migrations in order; the index + 1 is the schema version stored in `PRAGMA user_version`.
 * Never edit a released entry; add a new one.
 */
private val MIGRATIONS = listOf(
    """
    CREATE TABLE session (
        id INTEGER PRIMARY KEY,
        started_at_ms INTEGER NOT NULL,
        game_id TEXT,
        dpi REAL NOT NULL,
        kind TEXT NOT NULL
    );
    CREATE TABLE round (
        id INTEGER PRIMARY KEY,
        session_id INTEGER NOT NULL REFERENCES session(id) ON DELETE CASCADE,
        scenario TEXT NOT NULL,
        seed INTEGER NOT NULL,
        cm360 REAL NOT NULL,
        dpi REAL NOT NULL,
        blind INTEGER NOT NULL,
        started_at_ms INTEGER NOT NULL,
        start_nanos INTEGER NOT NULL,
        duration_nanos INTEGER NOT NULL,
        mouse_event_count INTEGER NOT NULL,
        mouse_events BLOB NOT NULL,
        metrics_version INTEGER NOT NULL
    );
    CREATE INDEX round_session ON round(session_id);
    CREATE TABLE target_event (
        round_id INTEGER NOT NULL REFERENCES round(id) ON DELETE CASCADE,
        seq INTEGER NOT NULL,
        type TEXT NOT NULL,
        time_offset_nanos INTEGER NOT NULL,
        target_id INTEGER,
        x REAL,
        y REAL,
        z REAL,
        radius REAL,
        PRIMARY KEY (round_id, seq)
    );
    CREATE TABLE metric (
        round_id INTEGER NOT NULL REFERENCES round(id) ON DELETE CASCADE,
        name TEXT NOT NULL,
        value REAL NOT NULL,
        PRIMARY KEY (round_id, name)
    );
    """.trimIndent(),
    // Version 2: the acceleration curve a round was played with (AccelSpec text), null for none.
    "ALTER TABLE round ADD COLUMN accel TEXT;",
    // Version 3: how the player rated a refine round after playing it (-1 worse, 0 same, 1 better); a note next to the score, not a metric.
    "CREATE TABLE round_feel (round_id INTEGER PRIMARY KEY REFERENCES round(id) ON DELETE CASCADE, vote INTEGER NOT NULL);",
)

/** One SQLite connection guarded by a lock; the app is single-user so one writer is enough. */
class Database private constructor(private val connection: Connection) : AutoCloseable {

    /** Runs [block] in a transaction; rolls back and rethrows if it fails. */
    fun <T> transaction(block: (Connection) -> T): T = synchronized(this) {
        try {
            val result = block(connection)
            connection.commit()
            result
        } catch (e: Exception) {
            connection.rollback()
            throw e
        }
    }

    val schemaVersion: Int
        get() = synchronized(this) {
            connection.createStatement().use { s -> s.executeQuery("PRAGMA user_version").use { it.next(); it.getInt(1) } }
        }

    override fun close() = synchronized(this) { connection.close() }

    companion object {
        val CURRENT_VERSION: Int = MIGRATIONS.size

        /** Opens (creating directories and the file if needed) and migrates the database at [file]. */
        fun open(file: Path): Database {
            file.parent?.let { Files.createDirectories(it) }
            val connection = DriverManager.getConnection("jdbc:sqlite:${file.toAbsolutePath()}")
            try {
                connection.createStatement().use {
                    it.execute("PRAGMA foreign_keys = ON")
                    it.execute("PRAGMA journal_mode = WAL")
                }
                connection.autoCommit = false
                migrate(connection)
            } catch (e: Exception) {
                connection.close()
                throw e
            }
            return Database(connection)
        }

        private fun migrate(connection: Connection) {
            val current = connection.createStatement().use { s -> s.executeQuery("PRAGMA user_version").use { it.next(); it.getInt(1) } }
            check(current <= MIGRATIONS.size) {
                "database schema version $current is newer than this app supports (${MIGRATIONS.size}); update AimTune"
            }
            for (version in current until MIGRATIONS.size) {
                try {
                    connection.createStatement().use { s ->
                        MIGRATIONS[version].split(';').map { it.trim() }.filter { it.isNotEmpty() }.forEach(s::execute)
                        s.execute("PRAGMA user_version = ${version + 1}")
                    }
                    connection.commit()
                } catch (e: Exception) {
                    connection.rollback()
                    throw IllegalStateException("migration to schema version ${version + 1} failed", e)
                }
            }
        }
    }
}
