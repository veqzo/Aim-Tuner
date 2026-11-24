package dev.veqzo.aimtune.core.harness.review

import dev.veqzo.aimtune.core.harness.TEST_DPI
import dev.veqzo.aimtune.core.harness.playBlindTest
import dev.veqzo.aimtune.core.metrics.computeRoundMetrics
import dev.veqzo.aimtune.core.metrics.describeStaticRounds
import dev.veqzo.aimtune.core.scenario.ScenarioKind
import dev.veqzo.aimtune.core.scenario.ScenarioSchedule
import dev.veqzo.aimtune.core.score.MetricWeights
import dev.veqzo.aimtune.core.sim.HeadlessRound
import dev.veqzo.aimtune.core.sim.SimulatedPlayer
import dev.veqzo.aimtune.core.units.SearchRange
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.nio.file.Files
import java.nio.file.Path

/**
 * Gate of M15a (moving-target metrics): (a) static rounds are unchanged, (b) moving reaches never reach the speed-error
 * analysis, so mixing strafing rounds into the calibration does not raise the false-offer rate of flat players, (c) the
 * sensitivity optimizer with strafing scenarios in the schedule, against flick only (reported, no threshold).
 */
@EnabledIfEnvironmentVariable(named = "AIMTUNE_REVIEW", matches = ".+")
class M15aGateTest {
    private val range = SearchRange(20.0, 140.0)

    @Test
    fun gate() {
        val out = StringBuilder()
        out.appendLine("== M15a gate @ git ${gitShortHash()} ==")
        staticUnchanged(out)
        out.appendLine()
        falseOffers(out)
        out.appendLine()
        optimizer(out)
        write("gate-m15a.txt", out.toString())
    }

    private fun staticUnchanged(out: StringBuilder) {
        val fixture = Path.of("src", "test", "resources", "metrics", "static-metrics.txt")
        val same = Files.readString(fixture).replace("\r\n", "\n") == describeStaticRounds()
        out.appendLine("-- (a) static scenarios (flick, micro, switching; 18 seeded rounds, all movements and first reaches) against the fixture recorded before M15a: ${if (same) "IDENTICAL" else "DIFFERENT"}")
        check(same) { "static metrics changed" }
    }

    private class Arm(val offered: Boolean, val samples: Int, val strafeMovements: Int, val strafeUsed: Int)

    private fun falseOffers(out: StringBuilder) {
        val n = 40
        val rounds = 15
        val arms = listOf(
            "micro x$rounds" to listOf(ScenarioKind.MICRO),
            "micro x$rounds + micro-strafe x$rounds (alternating)" to listOf(ScenarioKind.MICRO, ScenarioKind.MICRO_STRAFE),
            "micro-strafe x$rounds" to listOf(ScenarioKind.MICRO_STRAFE),
        )
        out.appendLine("-- (b) flat players (no gain preference), $n seeds per row: curves offered by the legacy analysis, usable samples, strafe movements and how many of them reached the analysis")
        out.appendLine("model      arm                                                    rounds  offered  samples/run  strafe mvts  strafe used")
        for (model in listOf("DEFAULT" to { p: SimulatedPlayer -> p }, "RANGE" to { p: SimulatedPlayer -> p.copy(rangeBias = 0.05) })) {
            for ((label, kinds) in arms) {
                val total = if (kinds.size == 1) rounds else 2 * rounds
                val runs = parallelList(n) { s ->
                    val player = model.second(SimulatedPlayer())
                    val metrics = (0 until total).map { computeRoundMetrics(HeadlessRound.of(kinds[it % kinds.size], player, TEST_DPI, CALIBRATION_BASE_CM360, (200_000L + s) * 1_000 + it, ROUND_SECONDS)) }
                    val strafeMovements = metrics.filterIndexed { i, _ -> kinds[i % kinds.size].strafes }.flatMap { it.movements }
                    val samples = dev.veqzo.aimtune.core.accel.movementSamples(metrics, TEST_DPI)
                    val strafeUsed = dev.veqzo.aimtune.core.accel.movementSamples(metrics.filterIndexed { i, _ -> kinds[i % kinds.size].strafes }, TEST_DPI)
                    Arm(LegacyCurveMethod.recommend(metrics, TEST_DPI) != null, samples.size, strafeMovements.size, strafeUsed.size)
                }
                out.appendLine(
                    "%-10s %-54s %6d  %3d/%-3d  %11.1f  %11.1f  %11.1f".format(
                        model.first, label, total, runs.count { it.offered }, n, runs.map { it.samples }.average(),
                        runs.map { it.strafeMovements }.average(), runs.map { it.strafeUsed }.average(),
                    ),
                )
            }
        }
        out.appendLine("(strafe used = movements of strafing rounds that entered the analysis: only reaches toward a target that stood still during the whole reach)")
    }

    private fun optimizer(out: StringBuilder) {
        val n = 30
        val rounds = 15
        val player = SimulatedPlayer().withOptimum(50.0)
        val strafeSchedule = ScenarioSchedule(setOf(ScenarioKind.FLICK, ScenarioKind.MICRO_STRAFE, ScenarioKind.SWITCH_STRAFE), MetricWeights.DEFAULT)
        val arms = listOf<Pair<String, (Int) -> ScenarioKind>>(
            "flick only" to { ScenarioKind.FLICK },
            "flick + micro-strafe + switch-strafe (schedule)" to { strafeSchedule.kindFor(it) },
            "micro-strafe only" to { ScenarioKind.MICRO_STRAFE },
        )
        out.appendLine("-- (c) sensitivity optimizer, optimum 50 cm/360, range 20-140, $rounds rounds, $n seeds (report only)")
        out.appendLine("schedule shares: " + strafeSchedule.shares.entries.joinToString { "%s %.0f%%".format(it.key.id, it.value * 100) })
        out.appendLine("arm                                              median  p25-p75      medErr   conf mean")
        for ((label, kindFor) in arms) {
            val results = parallelList(n) { s -> checkNotNull(playBlindTest(player, range, rounds, 3000L + s, scenarioFor = kindFor).result) }
            val optima = results.map { it.optimumCm360 }
            out.appendLine(
                "%-48s %6.1f  %5.1f-%-5.1f  x%.3f  %5.0f".format(
                    label, median(optima), percentileOf(optima, 0.25), percentileOf(optima, 0.75),
                    median(optima.map { errorFactorOf(it, 50.0) }), results.map { it.confidencePercent.toDouble() }.average(),
                ),
            )
        }
    }
}
