package dev.veqzo.aimtune.storage.review

import dev.veqzo.aimtune.core.analysis.segmentSubmovements
import dev.veqzo.aimtune.core.metrics.computeRoundMetrics
import dev.veqzo.aimtune.storage.Database
import dev.veqzo.aimtune.storage.RoundRepository
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.nio.file.Path
import kotlin.math.hypot
import kotlin.test.Test

/**
 * Review tool (docs/history/REVIEW.md): reads a copy of a real aimtune.db (env AIMTUNE_REAL_DB) and reports timing,
 * batching and segmentation statistics of the recorded rounds. Skipped unless the variable is set.
 */
@EnabledIfEnvironmentVariable(named = "AIMTUNE_REAL_DB", matches = ".+")
class RealDataReviewTest {
    private fun pct(sorted: List<Double>, p: Double) = if (sorted.isEmpty()) Double.NaN else sorted[((sorted.size - 1) * p).toInt()]

    @Test
    fun report() {
        val db = Database.open(Path.of(System.getenv("AIMTUNE_REAL_DB")))
        val repo = RoundRepository(db)
        val out = StringBuilder()
        val sessions = repo.sessions()
        out.appendLine("sessions=${sessions.size} kinds=${sessions.groupingBy { it.kind }.eachCount()}")
        val rounds = repo.rounds()
        out.appendLine("rounds=${rounds.size} byScenario=${rounds.groupingBy { it.scenario }.eachCount()} blind=${rounds.count { it.blind }} accel=${rounds.count { it.accel != null }}")
        val dts = mutableListOf<Double>()
        var zeroDt = 0
        var events = 0
        var maxGapMs = 0.0
        val perRoundRate = mutableListOf<Double>()
        val subPerMovement = mutableListOf<Int>()
        var totalSubs = 0
        val subDurMs = mutableListOf<Double>()
        val peakSpeedIps = mutableListOf<Double>()
        var firstErrors = mutableListOf<Double>()
        var movementsTotal = 0
        var movementsWithFirst = 0
        var nonFlickNoMove = 0
        var sumAbsCounts = 0.0
        val dxs = mutableListOf<Double>()
        var largeDelta = 0
        val bad = mutableListOf<Triple<Double, Double, Double>>() // dist, error, reactionMs for e < -0.5
        val good = mutableListOf<Triple<Double, Double, Double>>()
        val byDist = sortedMapOf<Int, MutableList<Double>>()
        var diag = ""
        val perScenario = sortedMapOf<String, IntArray>()
        for (row in rounds) {
            val rec = repo.loadRecording(row.id) ?: continue
            val ev = rec.mouseEvents
            events += ev.size
            for (i in 1 until ev.size) {
                val dt = (ev[i].timeNanos - ev[i - 1].timeNanos) / 1e6
                if (dt == 0.0) zeroDt++
                if (dt < 50) dts += dt
                maxGapMs = maxOf(maxGapMs, dt)
            }
            for (e in ev) { dxs += hypot(e.dx.toDouble(), e.dy.toDouble()); if (hypot(e.dx.toDouble(), e.dy.toDouble()) > 30) largeDelta++ }
            perRoundRate += ev.size / ((rec.endNanos - rec.startNanos) / 1e9)
            val subs = segmentSubmovements(ev, rec.dpi)
            totalSubs += subs.size
            subDurMs += subs.map { it.durationNanos / 1e6 }
            peakSpeedIps += subs.map { it.peakSpeedCountsPerMs * 1000.0 / rec.dpi }
            if (rec.scenarioId != "tracking") {
                val m = computeRoundMetrics(rec)
                movementsTotal += m.movements.size
                movementsWithFirst += m.movements.count { it.firstErrorRatio != null }
                perScenario.getOrPut(rec.scenarioId) { IntArray(2) }.let { it[0] += m.movements.size; it[1] += m.movements.count { mv -> mv.firstErrorRatio != null } }
                subPerMovement += m.movements.map { it.correctiveSubmovements + 1 }
                firstErrors += m.movements.mapNotNull { it.firstErrorRatio }
                for (mv in m.movements) {
                    val e = mv.firstErrorRatio ?: continue
                    val t = Triple(mv.initialDistanceDegrees, e, (mv.reactionNanos ?: 0L) / 1e6)
                    if (e < -0.5) bad += t else good += t
                    byDist.getOrPut((mv.initialDistanceDegrees / 5).toInt().coerceAtMost(8)) { mutableListOf() } += e
                }
            }
        }
        val s = dts.sorted()
        out.appendLine("events=$events zeroDt=$zeroDt (%.2f%%) maxGapMs=%.0f".format(100.0 * zeroDt / maxOf(1, events), maxGapMs))
        out.appendLine("dt ms (<50ms): p5=%.2f p25=%.2f p50=%.2f p75=%.2f p95=%.2f p99=%.2f".format(pct(s, .05), pct(s, .25), pct(s, .5), pct(s, .75), pct(s, .95), pct(s, .99)))
        out.appendLine("events/s per round: mean=%.0f min=%.0f max=%.0f".format(perRoundRate.average(), perRoundRate.min(), perRoundRate.max()))
        val d = dxs.sorted()
        out.appendLine("|counts| per event: p50=%.1f p95=%.1f p99=%.1f max=%.0f; events>30 counts=$largeDelta".format(pct(d, .5), pct(d, .95), pct(d, .99), d.last()))
        out.appendLine("submovements total=$totalSubs, per round %.1f; dur ms p5/p50/p95=%.0f/%.0f/%.0f; peak speed in/s p5/p50/p95=%.1f/%.1f/%.1f".format(
            totalSubs.toDouble() / rounds.size, pct(subDurMs.sorted(), .05), pct(subDurMs.sorted(), .5), pct(subDurMs.sorted(), .95),
            pct(peakSpeedIps.sorted(), .05), pct(peakSpeedIps.sorted(), .5), pct(peakSpeedIps.sorted(), .95)))
        out.appendLine("click-scenario movements=$movementsTotal withFirstSubmovement=$movementsWithFirst; submovements/movement mean=%.2f hist=${subPerMovement.groupingBy { minOf(it, 5) }.eachCount().toSortedMap()}".format(subPerMovement.average()))
        val fe = firstErrors.sorted()
        out.appendLine("first error ratio: mean=%.3f p10=%.2f p50=%.2f p90=%.2f".format(firstErrors.average(), pct(fe, .1), pct(fe, .5), pct(fe, .9)))
        val byCm = rounds.groupBy { "%.1f".format(it.cm360) }.mapValues { (_, v) -> v.size }.toSortedMap()
        diag = "e<-0.5: ${bad.size} of ${bad.size + good.size}; mean dist bad=%.1f good=%.1f deg; mean reaction ms bad=%.0f good=%.0f; reaction<=0 bad=${bad.count { it.third <= 0 }} good=${good.count { it.third <= 0 }}".format(
            bad.map { it.first }.average(), good.map { it.first }.average(), bad.map { it.third }.average(), good.map { it.third }.average()) +
            "\nerror by initial distance bucket (5deg): " + byDist.map { (k, v) -> "${k * 5}+:n=${v.size},median=%.2f,p10=%.2f".format(pct(v.sorted(), .5), pct(v.sorted(), .1)) }
        out.appendLine(diag)
        out.appendLine("movements with a first reach per scenario: " + perScenario.map { (k, v) -> "$k ${v[1]}/${v[0]}" })
        out.appendLine("rounds per cm360: $byCm")
        out.appendLine("scenario x metrics sample: " + rounds.take(3).map { it.metrics.filterKeys { k -> k in setOf("accuracy", "mean_time_to_hit_ms", "error_spread", "hits", "shots") } })
        println(out)
        java.nio.file.Files.writeString(Path.of("build", "real-data-review.txt"), out.toString())
        db.close()
    }
}
