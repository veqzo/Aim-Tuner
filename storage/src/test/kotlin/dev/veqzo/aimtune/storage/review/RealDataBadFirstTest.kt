package dev.veqzo.aimtune.storage.review

import dev.veqzo.aimtune.core.analysis.segmentSubmovements
import dev.veqzo.aimtune.core.metrics.computeRoundMetrics
import dev.veqzo.aimtune.core.scenario.TargetEvent
import dev.veqzo.aimtune.storage.Database
import dev.veqzo.aimtune.storage.RoundRepository
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.nio.file.Path
import kotlin.test.Test

/** Review tool: lists click movements for which no first reach was found, with the submovements in their window. */
@EnabledIfEnvironmentVariable(named = "AIMTUNE_REAL_DB", matches = ".+")
class RealDataBadFirstTest {
    @Test
    fun listMovementsWithoutFirstReach() {
        val db = Database.open(Path.of(System.getenv("AIMTUNE_REAL_DB")))
        val repo = RoundRepository(db)
        val flick = repo.rounds().first { it.scenario == "flick" }
        val rec = repo.loadRecording(flick.id)!!
        val subs = segmentSubmovements(rec.mouseEvents, rec.dpi)
        val m = computeRoundMetrics(rec)
        val spawns = rec.targetEvents.filterIsInstance<TargetEvent.Spawned>().associateBy { it.target.id }
        val hits = rec.targetEvents.filterIsInstance<TargetEvent.Hit>()
        var shown = 0
        hits.forEach { h ->
            val mv = m.movements.firstOrNull { it.targetId == h.targetId } ?: return@forEach
            if (mv.firstErrorRatio != null || shown >= 8) return@forEach
            shown++
            val t0 = spawns.getValue(h.targetId).timeNanos
            println("NOFIRST target=${h.targetId} dist=%.1f ttk=%.0fms".format(mv.initialDistanceDegrees, mv.timeToTargetNanos / 1e6))
            subs.filter { it.peakNanos in t0..h.timeNanos }.forEach {
                println("   sub start=%+.0f peak=%+.0f end=%+.0f ms; counts=(%d,%d) peak=%.1f in/s".format((it.startNanos - t0) / 1e6, (it.peakNanos - t0) / 1e6, (it.endNanos - t0) / 1e6, it.dx, it.dy, it.peakSpeedCountsPerMs * 1000 / rec.dpi))
            }
        }
        db.close()
    }
}
