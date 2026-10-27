package de.yuuto.aimtune.storage

import de.yuuto.aimtune.core.accel.AccelSpec
import de.yuuto.aimtune.core.math.Vec3
import de.yuuto.aimtune.core.metrics.RoundMetrics
import de.yuuto.aimtune.core.metrics.computeRoundMetrics
import de.yuuto.aimtune.core.recording.RoundRecording
import de.yuuto.aimtune.core.score.ScoreMetric
import de.yuuto.aimtune.core.scenario.Target
import de.yuuto.aimtune.core.scenario.TargetEvent
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Statement
import java.sql.Types

enum class SessionKind(val id: String) {
    SENSITIVITY("sensitivity"),
    ACCEL_AB("accel_ab"),

    /** The rounds of the blind strength search (sessions before this kind existed hold them inside [ACCEL_AB]). */
    ACCEL_SEARCH("accel_search"),

    /** The rounds of "Refine my Raw Accel curve": warm-up, stage searches and the test, all played with imported-profile variants. */
    ACCEL_REFINE("accel_refine"),
    ;

    companion object {
        fun fromId(id: String): SessionKind = entries.firstOrNull { it.id == id } ?: error("unknown session kind '$id'")
    }
}

data class SessionRow(val id: Long, val startedAtMs: Long, val gameId: String?, val dpi: Double, val kind: SessionKind)

/** A stored round without its raw events; [metrics] holds the values derived when it was saved. */
data class RoundRow(
    val id: Long,
    val sessionId: Long,
    val scenario: String,
    val seed: Long,
    val cm360: Double,
    val blind: Boolean,
    val startedAtMs: Long,
    val metrics: Map<String, Double>,
    /** [AccelSpec] text of the curve the round was played with; null for plain sensitivity. */
    val accel: String? = null,
)

/**
 * Version of the metric definitions written with each round; raise it when ALGORITHMS.md changes them.
 * Rounds with an older version are recomputed from their stored raw events ([RoundRepository.refreshMetrics]).
 * 2: the first reach of a movement must come after a reaction time and cover a share of the distance (docs/history/REVIEW.md C2);
 *    the accuracy the score uses is the first-shot accuracy (docs/history/REVIEW.md M6).
 * 3: horizontal and vertical endpoint error of the first movement (mean_abs_first_error_h_deg / _v_deg, mean_first_error_v_deg);
 *    older rounds get them from their stored events. Flash turn-away rounds (the dodge rate) came in with this version too.
 */
const val METRICS_VERSION = 4

/** How many stored rounds [RoundRepository.refreshMetrics] recomputed, and how many could not be (unreadable events). */
data class MetricsRefresh(val updated: Int, val failed: Int)

class RoundRepository(private val database: Database) {

    fun createSession(startedAtMs: Long, gameId: String?, dpi: Double, kind: SessionKind): Long = database.transaction { c ->
        c.prepareStatement("INSERT INTO session (started_at_ms, game_id, dpi, kind) VALUES (?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS).use {
            it.setLong(1, startedAtMs)
            it.setString(2, gameId)
            it.setDouble(3, dpi)
            it.setString(4, kind.id)
            it.executeUpdate()
            it.generatedKeys.use { keys -> keys.next(); keys.getLong(1) }
        }
    }

    fun sessions(): List<SessionRow> = database.transaction { c ->
        c.createStatement().use { s ->
            s.executeQuery("SELECT id, started_at_ms, game_id, dpi, kind FROM session ORDER BY started_at_ms, id").use { rs ->
                buildList {
                    while (rs.next()) add(SessionRow(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getDouble(4), SessionKind.fromId(rs.getString(5))))
                }
            }
        }
    }

    /** Stores the raw recording and its derived [metrics] in one transaction and returns the round id. */
    fun saveRound(sessionId: Long, recording: RoundRecording, startedAtMs: Long, blind: Boolean, metrics: RoundMetrics): Long =
        database.transaction { c ->
            val roundId = insertRound(c, sessionId, recording, startedAtMs, blind)
            insertTargetEvents(c, roundId, recording)
            insertMetrics(c, roundId, metrics.toNamedValues())
            roundId
        }

    fun loadRecording(roundId: Long): RoundRecording? = database.transaction { c ->
        c.prepareStatement(
            "SELECT scenario, seed, dpi, cm360, start_nanos, duration_nanos, mouse_event_count, mouse_events, accel FROM round WHERE id = ?",
        ).use { s ->
            s.setLong(1, roundId)
            s.executeQuery().use { rs ->
                if (!rs.next()) return@use null
                val start = rs.getLong(5)
                RoundRecording(
                    scenarioId = rs.getString(1),
                    seed = rs.getLong(2),
                    dpi = rs.getDouble(3),
                    cm360 = rs.getDouble(4),
                    startNanos = start,
                    endNanos = start + rs.getLong(6),
                    mouseEvents = MouseEventCodec.decode(rs.getBytes(8), rs.getInt(7), start),
                    targetEvents = loadTargetEvents(c, roundId, start),
                    accel = rs.getString(9)?.let(AccelSpec::decodeModel),
                )
            }
        }
    }

    /**
     * The blind rounds (of any scenario) of every sensitivity session with this game and DPI, oldest
     * first: the data the optimizer merges across sessions. Rounds played without blinding are left out.
     */
    fun blindRounds(gameId: String?, dpi: Double): List<RoundRow> =
        sessions().filter { it.kind == SessionKind.SENSITIVITY && it.gameId == gameId && it.dpi == dpi }
            .flatMap { rounds(it.id) }
            .filter { it.blind }

    /**
     * Ids of the rounds of acceleration sessions that were played without a curve at this DPI, at a sensitivity within
     * [tolerance] (a share, 0.03 = 3 %) of [cm360], in one of [scenarios], newest first and at most [limit]. They are plain-sensitivity
     * movements like the calibration rounds, so a later run can analyse them together with the rounds it plays itself.
     */
    fun plainAccelRoundIds(dpi: Double, cm360: Double, scenarios: List<String>, tolerance: Double, limit: Int): List<Long> =
        accelRoundIds(null, dpi, cm360, scenarios, tolerance, limit)

    /**
     * Like [plainAccelRoundIds], but for rounds played with one exact curve: [spec] is the stored curve text
     * ([AccelSpec.encodeModel]), null for plain. Refine sessions count for a curve (their warm-up and test rounds play the imported
     * profile as it is), not for plain. A different curve, even one field apart, is a different base and never matches.
     */
    fun accelRoundIds(spec: String?, dpi: Double, cm360: Double, scenarios: List<String>, tolerance: Double, limit: Int): List<Long> {
        if (scenarios.isEmpty()) return emptyList()
        val marks = scenarios.joinToString(",") { "?" }
        val kinds = if (spec == null) ACCEL_KINDS else "$ACCEL_KINDS, '${SessionKind.ACCEL_REFINE.id}'"
        val curve = if (spec == null) "r.accel IS NULL" else "r.accel = ?"
        return database.transaction { c ->
            c.prepareStatement(
                "SELECT r.id FROM round r JOIN session s ON s.id = r.session_id " +
                    "WHERE s.kind IN ($kinds) AND $curve AND ABS(r.dpi - ?) < 0.5 AND r.cm360 BETWEEN ? AND ? AND r.scenario IN ($marks) " +
                    "ORDER BY r.started_at_ms DESC, r.id DESC LIMIT ?",
            ).use { s ->
                var i = 1
                if (spec != null) s.setString(i++, spec)
                s.setDouble(i++, dpi)
                s.setDouble(i++, cm360 * (1 - tolerance))
                s.setDouble(i++, cm360 * (1 + tolerance))
                scenarios.forEach { s.setString(i++, it) }
                s.setInt(i, limit)
                s.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getLong(1)) } }
            }
        }
    }

    /** DPI and cm/360 of the newest plain round of an acceleration session in one of [scenarios], or null when there is none. */
    fun latestPlainAccelSetup(scenarios: List<String>): Pair<Double, Double>? {
        if (scenarios.isEmpty()) return null
        val marks = scenarios.joinToString(",") { "?" }
        return database.transaction { c ->
            c.prepareStatement(
                "SELECT r.dpi, r.cm360 FROM round r JOIN session s ON s.id = r.session_id " +
                    "WHERE s.kind IN ($ACCEL_KINDS) AND r.accel IS NULL AND r.scenario IN ($marks) ORDER BY r.started_at_ms DESC, r.id DESC LIMIT 1",
            ).use { s ->
                scenarios.forEachIndexed { i, id -> s.setString(1 + i, id) }
                s.executeQuery().use { rs -> if (rs.next()) rs.getDouble(1) to rs.getDouble(2) else null }
            }
        }
    }

    /**
     * Recomputes the stored metrics of every round written with an older [METRICS_VERSION] from its raw
     * events, so a changed metric definition applies to the whole history. A round whose events cannot be
     * read keeps its old values and version and is counted in [MetricsRefresh.failed].
     */
    fun refreshMetrics(compute: (RoundRecording) -> RoundMetrics = ::computeRoundMetrics): MetricsRefresh {
        val stale = database.transaction { c ->
            c.prepareStatement("SELECT id FROM round WHERE metrics_version < ?").use { s ->
                s.setInt(1, METRICS_VERSION)
                s.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getLong(1)) } }
            }
        }
        var updated = 0
        var failed = 0
        for (id in stale) {
            val metrics = try {
                compute(loadRecording(id) ?: continue)
            } catch (e: IllegalArgumentException) {
                failed++
                continue
            }
            database.transaction { c ->
                c.prepareStatement("DELETE FROM metric WHERE round_id = ?").use { it.setLong(1, id); it.executeUpdate() }
                insertMetrics(c, id, metrics.toNamedValues())
                c.prepareStatement("UPDATE round SET metrics_version = ? WHERE id = ?").use { it.setInt(1, METRICS_VERSION); it.setLong(2, id); it.executeUpdate() }
            }
            updated++
        }
        return MetricsRefresh(updated, failed)
    }

    /** Rounds of [sessionId], or of all sessions when null, oldest first. */
    fun rounds(sessionId: Long? = null): List<RoundRow> = database.transaction { c ->
        val where = if (sessionId == null) "" else "WHERE session_id = ?"
        val metrics = loadAllMetrics(c, sessionId)
        c.prepareStatement(
            "SELECT id, session_id, scenario, seed, cm360, blind, started_at_ms, accel FROM round $where ORDER BY started_at_ms, id",
        ).use { s ->
            if (sessionId != null) s.setLong(1, sessionId)
            s.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(roundRow(rs, metrics[rs.getLong(1)].orEmpty()))
                }
            }
        }
    }

    private fun roundRow(rs: ResultSet, metrics: Map<String, Double>) =
        RoundRow(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getLong(4), rs.getDouble(5), rs.getInt(6) != 0, rs.getLong(7), metrics, rs.getString(8))

    private fun insertRound(c: Connection, sessionId: Long, r: RoundRecording, startedAtMs: Long, blind: Boolean): Long =
        c.prepareStatement(
            "INSERT INTO round (session_id, scenario, seed, cm360, dpi, blind, started_at_ms, start_nanos, duration_nanos, " +
                "mouse_event_count, mouse_events, metrics_version, accel) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            Statement.RETURN_GENERATED_KEYS,
        ).use {
            it.setLong(1, sessionId)
            it.setString(2, r.scenarioId)
            it.setLong(3, r.seed)
            it.setDouble(4, r.cm360)
            it.setDouble(5, r.dpi)
            it.setInt(6, if (blind) 1 else 0)
            it.setLong(7, startedAtMs)
            it.setLong(8, r.startNanos)
            it.setLong(9, r.endNanos - r.startNanos)
            it.setInt(10, r.mouseEvents.size)
            it.setBytes(11, MouseEventCodec.encode(r.mouseEvents, r.startNanos))
            it.setInt(12, METRICS_VERSION)
            it.setString(13, r.accel?.let(AccelSpec::encodeModel))
            it.executeUpdate()
            it.generatedKeys.use { keys -> keys.next(); keys.getLong(1) }
        }

    private fun insertTargetEvents(c: Connection, roundId: Long, r: RoundRecording) {
        c.prepareStatement(
            "INSERT INTO target_event (round_id, seq, type, time_offset_nanos, target_id, x, y, z, radius) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
        ).use { s ->
            r.targetEvents.forEachIndexed { seq, event ->
                s.setLong(1, roundId)
                s.setInt(2, seq)
                s.setLong(4, event.timeNanos - r.startNanos)
                s.setNull(5, Types.INTEGER)
                listOf(6, 7, 8, 9).forEach { s.setNull(it, Types.REAL) }
                when (event) {
                    is TargetEvent.Spawned -> {
                        s.setString(3, SPAWNED)
                        s.setInt(5, event.target.id)
                        s.setDouble(6, event.target.center.x)
                        s.setDouble(7, event.target.center.y)
                        s.setDouble(8, event.target.center.z)
                        s.setDouble(9, event.target.radius)
                    }
                    is TargetEvent.Hit -> {
                        s.setString(3, HIT)
                        s.setInt(5, event.targetId)
                    }
                    is TargetEvent.Miss -> s.setString(3, MISS)
                    is TargetEvent.BodyHit -> {
                        s.setString(3, BODY_HIT)
                        s.setInt(5, event.targetId)
                    }
                    is TargetEvent.Despawned -> {
                        s.setString(3, DESPAWNED)
                        s.setInt(5, event.targetId)
                    }
                    is TargetEvent.Waypoint -> {
                        s.setString(3, WAYPOINT)
                        s.setInt(5, event.targetId)
                        s.setDouble(6, event.center.x)
                        s.setDouble(7, event.center.y)
                        s.setDouble(8, event.center.z)
                    }
                    // A flash reuses the numeric columns: target_id = flash id, x/y = yaw/pitch, z = pop time offset, radius = safe angle.
                    is TargetEvent.FlashCue -> {
                        s.setString(3, FLASH_CUE)
                        s.setInt(5, event.flashId)
                        s.setDouble(6, event.yawDegrees)
                        s.setDouble(7, event.pitchDegrees)
                        s.setDouble(8, (event.popNanos - r.startNanos).toDouble())
                        s.setDouble(9, event.safeAngleDegrees)
                    }
                    // x = 1 when dodged, y = end of the white-out as an offset (0 for none).
                    is TargetEvent.FlashPop -> {
                        s.setString(3, FLASH_POP)
                        s.setInt(5, event.flashId)
                        s.setDouble(6, if (event.dodged) 1.0 else 0.0)
                        s.setDouble(7, if (event.blindUntilNanos > 0L) (event.blindUntilNanos - r.startNanos).toDouble() else 0.0)
                    }
                }
                s.addBatch()
            }
            s.executeBatch()
        }
    }

    private fun insertMetrics(c: Connection, roundId: Long, values: Map<String, Double>) {
        c.prepareStatement("INSERT INTO metric (round_id, name, value) VALUES (?, ?, ?)").use { s ->
            for ((name, value) in values) {
                s.setLong(1, roundId)
                s.setString(2, name)
                s.setDouble(3, value)
                s.addBatch()
            }
            s.executeBatch()
        }
    }

    private fun loadTargetEvents(c: Connection, roundId: Long, startNanos: Long): List<TargetEvent> =
        c.prepareStatement(
            "SELECT type, time_offset_nanos, target_id, x, y, z, radius FROM target_event WHERE round_id = ? ORDER BY seq",
        ).use { s ->
            s.setLong(1, roundId)
            s.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        val time = startNanos + rs.getLong(2)
                        add(
                            when (val type = rs.getString(1)) {
                                SPAWNED -> TargetEvent.Spawned(time, Target(rs.getInt(3), Vec3(rs.getDouble(4), rs.getDouble(5), rs.getDouble(6)), rs.getDouble(7)))
                                HIT -> TargetEvent.Hit(time, rs.getInt(3))
                                MISS -> TargetEvent.Miss(time)
                                BODY_HIT -> TargetEvent.BodyHit(time, rs.getInt(3))
                                DESPAWNED -> TargetEvent.Despawned(time, rs.getInt(3))
                                WAYPOINT -> TargetEvent.Waypoint(time, rs.getInt(3), Vec3(rs.getDouble(4), rs.getDouble(5), rs.getDouble(6)))
                                FLASH_CUE -> TargetEvent.FlashCue(time, rs.getInt(3), rs.getDouble(4), rs.getDouble(5), startNanos + rs.getDouble(6).toLong(), rs.getDouble(7))
                                FLASH_POP -> TargetEvent.FlashPop(time, rs.getInt(3), rs.getDouble(4) > 0.5, rs.getDouble(5).toLong().let { if (it > 0L) startNanos + it else 0L })
                                else -> error("unknown target event type '$type'")
                            },
                        )
                    }
                }
            }
        }

    /** The metrics of all rounds (or of one session's) in one query, keyed by round id. */
    private fun loadAllMetrics(c: Connection, sessionId: Long?): Map<Long, Map<String, Double>> {
        val where = if (sessionId == null) "" else "WHERE r.session_id = ?"
        return c.prepareStatement("SELECT m.round_id, m.name, m.value FROM metric m JOIN round r ON r.id = m.round_id $where").use { s ->
            if (sessionId != null) s.setLong(1, sessionId)
            s.executeQuery().use { rs ->
                val byRound = HashMap<Long, MutableMap<String, Double>>()
                while (rs.next()) byRound.getOrPut(rs.getLong(1)) { HashMap() }[rs.getString(2)] = rs.getDouble(3)
                byRound
            }
        }
    }

    private fun loadMetrics(c: Connection, roundId: Long): Map<String, Double> =
        c.prepareStatement("SELECT name, value FROM metric WHERE round_id = ?").use { s ->
            s.setLong(1, roundId)
            s.executeQuery().use { rs ->
                buildMap { while (rs.next()) put(rs.getString(1), rs.getDouble(2)) }
            }
        }

    private companion object {
        const val ACCEL_KINDS = "'accel_ab', 'accel_search'"
        const val SPAWNED = "spawned"
        const val HIT = "hit"
        const val MISS = "miss"
        const val WAYPOINT = "waypoint"
        const val BODY_HIT = "body_hit"
        const val DESPAWNED = "despawned"
        const val FLASH_CUE = "flash_cue"
        const val FLASH_POP = "flash_pop"
    }
}

/** Stable names under which metrics are stored; unavailable (null) metrics are omitted. */
fun RoundMetrics.toNamedValues(): Map<String, Double> = buildMap {
    put("hits", hits.toDouble())
    put("shots", shots.toDouble())
    put("accuracy", accuracy)
    firstShotAccuracy?.let { put("first_shot_accuracy", it) }
    meanTimeToHitMs?.let { put("mean_time_to_hit_ms", it) }
    meanFirstError?.let { put("mean_first_error", it) }
    meanAbsFirstError?.let { put("mean_abs_first_error", it) }
    errorSpread?.let { put("error_spread", it) }
    meanCorrectiveSubmovements?.let { put("mean_corrective_submovements", it) }
    meanPathEfficiency?.let { put("mean_path_efficiency", it) }
    trackingOnTargetRatio?.let { put("tracking_on_target_ratio", it) }
    trackingMeanDistanceDegrees?.let { put("tracking_mean_distance_deg", it) }
    flashDodgeRate?.let { put("flash_dodge_rate", it) }
    flashMeanTurnMs?.let { put("flash_mean_turn_ms", it) }
    flashTurnPeakSpeed?.let { put("flash_turn_peak_speed", it) }
    meanAbsFirstErrorHDegrees?.let { put("mean_abs_first_error_h_deg", it) }
    meanAbsFirstErrorVDegrees?.let { put("mean_abs_first_error_v_deg", it) }
    meanFirstErrorVDegrees?.let { put("mean_first_error_v_deg", it) }
    meanLeadErrorDegrees?.let { put("mean_lead_error_deg", it) }
    meanAbsLeadErrorDegrees?.let { put("mean_abs_lead_error_deg", it) }
    meanAbsAimErrorDegrees?.let { put("mean_abs_aim_error_deg", it) }
    rangeKills?.let { put("range_kills", it.toDouble()) }
    rangeMedianTimeToKillMs?.let { put("range_median_ttk_ms", it) }
    rangeShotsPerKill?.let { put("range_shots_per_kill", it) }
    rangeOffScreenSpawns?.let { put("range_off_screen_spawns", it.toDouble()) }
    rangeMedianSearchMs?.let { put("range_median_search_ms", it) }
}

/** The stored metrics a round's score is computed from, as [de.yuuto.aimtune.core.metrics.RoundMetrics.scoreInputs] would give them. */
fun RoundRow.scoreInputs(): Map<ScoreMetric, Double> = buildMap {
    val shots = metrics["shots"] ?: 0.0
    (metrics["first_shot_accuracy"] ?: metrics["accuracy"])?.takeIf { shots > 0 }?.let { put(ScoreMetric.ACCURACY, it) }
    metrics["mean_time_to_hit_ms"]?.let { put(ScoreMetric.TIME_TO_HIT, it) }
    metrics["error_spread"]?.let { put(ScoreMetric.CONSISTENCY, it) }
    metrics["tracking_on_target_ratio"]?.let { put(ScoreMetric.TRACKING, it) }
    metrics["flash_dodge_rate"]?.let { put(ScoreMetric.FLASH_DODGE, it) }
}
