package dev.veqzo.aimtune.core.harness.review

import dev.veqzo.aimtune.core.accel.AbCondition
import dev.veqzo.aimtune.core.accel.AbOutcome
import dev.veqzo.aimtune.core.accel.AbStatus
import dev.veqzo.aimtune.core.accel.AccelAbPlan
import dev.veqzo.aimtune.core.accel.AccelAnalysis
import dev.veqzo.aimtune.core.accel.AccelCurve
import dev.veqzo.aimtune.core.accel.AccelProcessor
import dev.veqzo.aimtune.core.accel.NaturalCurve
import dev.veqzo.aimtune.core.accel.evaluateAb
import dev.veqzo.aimtune.core.accel.fitAccel
import dev.veqzo.aimtune.core.accel.movementSamples
import dev.veqzo.aimtune.core.analysis.segmentSubmovements
import dev.veqzo.aimtune.core.harness.TEST_DPI
import dev.veqzo.aimtune.core.harness.runAccelPipeline
import dev.veqzo.aimtune.core.input.MouseEvent
import dev.veqzo.aimtune.core.metrics.computeRoundMetrics
import dev.veqzo.aimtune.core.scenario.ScenarioKind
import dev.veqzo.aimtune.core.score.MetricWeights
import dev.veqzo.aimtune.core.score.scoreInputs
import dev.veqzo.aimtune.core.sim.HeadlessRound
import dev.veqzo.aimtune.core.sim.SimulatedPlayer
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.math.exp
import kotlin.random.Random
import kotlin.test.Test

/** Review experiments for the acceleration part: false positives, flat players, speed measurement. */
@EnabledIfEnvironmentVariable(named = "AIMTUNE_REVIEW", matches = ".+")
class ReviewAccelTest {
    private val clickKinds = listOf(ScenarioKind.FLICK, ScenarioKind.SWITCHING, ScenarioKind.MICRO)
    private val rising = SimulatedPlayer(gainPreference = { v -> 1.0 + 0.5 * (1.0 - exp(-v / 6.0)) })
    private val falling = SimulatedPlayer(gainPreference = { v -> 1.0 - 0.3 * (1.0 - exp(-v / 6.0)) })

    @Test
    fun falsePositivesAndSigns() {
        val runs = (System.getenv("AIMTUNE_RUNS") ?: "300").toInt()
        val out = StringBuilder()

        // 1. A/B with no real difference: plain against plain, different noise per round, as in AccelPipelineTest.
        val player = SimulatedPlayer()
        val verdicts = parallelList(runs) { r ->
            val seed = 10_000L + r
            val plan = AccelAbPlan(clickKinds, seed)
            val outcomes = mutableListOf<AbOutcome>()
            var verdict = evaluateAb(emptyList(), MetricWeights.DEFAULT)
            for (index in 0 until 2 * plan.maxPairs) {
                val round = plan.round(index)
                val roundSeed = round.seed + if (round.condition == AbCondition.ACCEL) 5_000 else 0
                outcomes += AbOutcome(round.pair, round.condition, computeRoundMetrics(HeadlessRound.of(round.scenario, player, TEST_DPI, 55.0, roundSeed, 45)).scoreInputs())
                if (index % 2 == 1) {
                    verdict = evaluateAb(outcomes, MetricWeights.DEFAULT)
                    if (verdict.status != AbStatus.NEED_MORE_ROUNDS) break
                }
            }
            verdict
        }
        val better = verdicts.count { it.status == AbStatus.ACCEL_BETTER }
        val worse = verdicts.count { it.status == AbStatus.ACCEL_WORSE }
        out.appendLine("== A/B, no real difference, $runs runs, up to 12 pairs: 'curve better' $better (%.1f%%), 'curve worse' $worse (%.1f%%), undecided ${verdicts.count { it.status == AbStatus.NO_DIFFERENCE }}; decided pairs median %d ==".format(
            100.0 * better / runs, 100.0 * worse / runs, verdicts.map { it.pairs }.sorted()[runs / 2]))

        // 2. Pipeline on flat, rising and falling players.
        fun pipeline(label: String, p: SimulatedPlayer, n: Int, base: Double = 55.0, rounds: Int = 15) {
            val rs = parallelList(n) { runAccelPipeline(p, base, 20_000L + it, analysisRounds = rounds) }
            val offered = rs.filter { it.fit.recommended != null }
            val confirmed = offered.count { it.verdict?.status == AbStatus.ACCEL_BETTER }
            val worseC = offered.count { it.verdict?.status == AbStatus.ACCEL_WORSE }
            val monotoneUp = offered.count { f -> val c = f.fit.recommended!!; c.curve.multiplier(30.0) > c.curve.multiplier(3.0) }
            out.appendLine(
                "  %-34s n=%d rounds=%d: curve offered %3d (%.0f%%), of those curve rises with speed %d, confirmed better %d, worse %d, undecided %d".format(
                    label, n, rounds, offered.size, 100.0 * offered.size / n, monotoneUp, confirmed, worseC, offered.count { it.verdict?.status == AbStatus.NO_DIFFERENCE }))
        }
        out.appendLine("== Pipeline: does the analysis offer a curve? ==")
        pipeline("flat expectation", SimulatedPlayer(), 100)
        pipeline("flat expectation, noise x1.6", SimulatedPlayer(noiseLevel = 1.6), 100)
        pipeline("flat expectation, 8 rounds", SimulatedPlayer(), 100, rounds = 8)
        pipeline("flat, opt 80 cm", SimulatedPlayer().withOptimum(80.0), 60, base = 80.0)
        pipeline("rising gain 1 -> 1.5", rising, 60)
        pipeline("falling gain 1 -> 0.7", falling, 60)
        write("review-accel.txt", out.toString())
    }

    /** Detection power: how often is a curve offered and confirmed when the true gain change is small? */
    @Test
    fun detectionPower() {
        val n = (System.getenv("AIMTUNE_RUNS") ?: "40").toInt()
        val out = StringBuilder("== Detection power by strength of the speed-dependent gain (gain at high speed relative to low speed) ==\n")
        for (rounds in listOf(15, 30)) for (change in listOf(0.0, 0.08, 0.15, 0.25, -0.08, -0.15, -0.25)) {
            val player = SimulatedPlayer(gainPreference = { v -> 1.0 + change * (1.0 - exp(-v / 6.0)) })
            val rs = parallelList(n) { runAccelPipeline(player, 55.0, 30_000L + it, analysisRounds = rounds) }
            val offered = rs.filter { it.fit.recommended != null }
            val right = offered.count { f -> val c = f.fit.recommended!!; (c.curve.multiplier(30.0) > c.curve.multiplier(3.0)) == (change > 0) }
            val confirmed = offered.count { it.verdict?.status == AbStatus.ACCEL_BETTER }
            out.appendLine("  rounds=%2d gain change %+4.0f%%: offered %2d of %d, right direction %2d, confirmed better %2d".format(rounds, change * 100, offered.size, n, right, confirmed))
            write("review-accel-power.txt", out.toString())
        }
    }

    /** Speed measurement: per-report speed (what the driver sees) against the smoothed peak the analysis fits, at several polling rates. */
    @Test
    fun speedMeasurement() {
        val out = StringBuilder("== One 30 degree reach in 150 ms at 800 DPI, 47 cm/360 (true peak = 1.875 x mean) ==\n")
        val dpi = 800.0
        val cm360 = 47.0
        val countsTotal = 30.0 / (360.0 * 2.54 / (dpi * cm360))
        val curve: AccelCurve = NaturalCurve(0.2, 1.5)
        val truePeakIps = 1.875 * (countsTotal / 150.0) * 1000.0 / dpi
        out.appendLine("counts in reach %.0f, true peak %.1f in/s (= %.2f counts/ms)".format(countsTotal, truePeakIps, 1.875 * countsTotal / 150.0))
        fun reach(rateHz: Int, jitterMs: Double, batchMs: Double, seed: Long): List<MouseEvent> {
            val random = Random(seed)
            val events = mutableListOf<MouseEvent>()
            val stepNs = 1_000_000_000L / rateHz
            var emitted = 0L
            var t = 0L
            while (t <= 150_000_000L) {
                val tau = t / 150_000_000.0
                val s = tau * tau * tau * (10 + tau * (-15 + 6 * tau))
                val pos = Math.round(s * countsTotal)
                val dx = (pos - emitted).toInt()
                if (dx != 0) {
                    var stamp = 1_000_000_000L + t + (if (jitterMs > 0) random.nextDouble(-jitterMs, jitterMs) * 1e6 else 0.0).toLong()
                    if (batchMs > 0) stamp = (stamp / (batchMs * 1e6).toLong()) * (batchMs * 1e6).toLong()
                    events += MouseEvent(stamp, dx, 0)
                    emitted = pos
                }
                t += stepNs
            }
            return events.sortedBy { it.timeNanos }
        }
        out.appendLine("%-34s %7s %14s %14s %16s %18s".format("condition", "events", "max report in/s", "smoothed peak", "curve@report max", "curve@smoothed peak"))
        for ((label, rate, jit, batch) in listOf(
            Cond("1 kHz ideal", 1000, 0.0, 0.0), Cond("1 kHz jitter +-0.3 ms", 1000, 0.3, 0.0), Cond("4 kHz ideal", 4000, 0.0, 0.0),
            Cond("8 kHz ideal", 8000, 0.0, 0.0), Cond("8 kHz jitter +-0.05 ms", 8000, 0.05, 0.0),
            Cond("1 kHz, stamps batched per 8 ms frame", 1000, 0.0, 8.0), Cond("1 kHz, stamps batched per 16.7 ms", 1000, 0.0, 16.667),
        )) {
            val ev = reach(rate, jit, batch, 1)
            val proc = AccelProcessor(curve, dpi)
            val speeds = ev.map { proc.speedOf(it) }
            val sub = segmentSubmovements(ev, dpi).maxByOrNull { it.peakSpeedCountsPerMs }
            val smoothed = sub?.let { AccelProcessor.speedInPerSecond(it.peakSpeedCountsPerMs, dpi) } ?: Double.NaN
            out.appendLine("%-34s %7d %14.1f %14.1f %16.2f %18.2f".format(label, ev.size, speeds.max(), smoothed, curve.multiplier(speeds.max()), curve.multiplier(smoothed)))
        }
        write("review-speed.txt", out.toString())
    }

    private data class Cond(val label: String, val rate: Int, val jitter: Double, val batch: Double)
}
