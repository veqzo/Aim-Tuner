package de.yuuto.aimtune.storage

import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** What "Start over" did: the backup file and how many sessions and rounds it removed from the live database. */
data class StartOverResult(val backup: Path, val sessions: Int, val rounds: Int)

private val BACKUP_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

/**
 * Settings "Start over" (M23d): copies the database into [backupDir], checks that the copy holds every round, and only then deletes
 * all sessions; the foreign keys remove their rounds, events, metrics and ratings with them. The settings file is not touched.
 * If the backup fails or is incomplete, nothing is deleted and the error is thrown.
 */
fun RoundRepository.startOver(database: Database, backupDir: Path, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): StartOverResult {
    val sessions = sessions().size
    val rounds = rounds().size
    val backup = backupDir.resolve(uniqueName(backupDir, BACKUP_STAMP.format(Instant.ofEpochMilli(nowMs).atZone(zone))))
    database.backupTo(backup)
    val copied = Database.open(backup).use { RoundRepository(it).rounds().size }
    check(copied == rounds) { "the backup holds $copied of $rounds rounds; nothing was deleted" }
    clearAll()
    return StartOverResult(backup, sessions, rounds)
}

private fun uniqueName(dir: Path, stamp: String): String =
    generateSequence(0) { it + 1 }.map { if (it == 0) "aimtune-$stamp.db" else "aimtune-$stamp-$it.db" }
        .first { !java.nio.file.Files.exists(dir.resolve(it)) }
