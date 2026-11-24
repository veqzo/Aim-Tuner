package dev.veqzo.aimtune.core.harness.review

import dev.veqzo.aimtune.core.accel.AbCondition
import dev.veqzo.aimtune.core.accel.AbOutcome
import dev.veqzo.aimtune.core.accel.AbStatus
import dev.veqzo.aimtune.core.accel.AccelAbPlan
import dev.veqzo.aimtune.core.accel.AccelAnalysis
import dev.veqzo.aimtune.core.accel.AccelCurve
import dev.veqzo.aimtune.core.accel.NaturalCurve
import dev.veqzo.aimtune.core.accel.evaluateAb
import dev.veqzo.aimtune.core.accel.fitAccel
import dev.veqzo.aimtune.core.accel.movementSamples
import dev.veqzo.aimtune.core.harness.TEST_DPI
import dev.veqzo.aimtune.core.harness.geometricGrid
import dev.veqzo.aimtune.core.harness.parabolicOptimum
import dev.veqzo.aimtune.core.harness.playBlindTest
import dev.veqzo.aimtune.core.metrics.RoundMetrics
import dev.veqzo.aimtune.core.metrics.computeRoundMetrics
import dev.veqzo.aimtune.core.scenario.ArenaLayout
import dev.veqzo.aimtune.core.scenario.ElevationMix
import dev.veqzo.aimtune.core.scenario.ScenarioKind
import dev.veqzo.aimtune.core.scenario.ScenarioSchedule
import dev.veqzo.aimtune.core.scenario.TrainerGeometry
import dev.veqzo.aimtune.core.score.FlashImportance
import dev.veqzo.aimtune.core.score.MetricWeights
import dev.veqzo.aimtune.core.score.ScoreCalculator
import dev.veqzo.aimtune.core.score.ScoreMetric
import dev.veqzo.aimtune.core.score.scoreInputs
import dev.veqzo.aimtune.core.sim.HeadlessRound
import dev.veqzo.aimtune.core.sim.SimulatedPlayer
import dev.veqzo.aimtune.core.units.SearchRange
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.math.abs
import kotlin.math.exp
import kotlin.test.Test

/**
 * The simulation experiments before and after the vertical distribution of M11 (docs/ALGORITHMS.md "Vertical
 * distribution", the removed docs/ANALYSE.md (git history)). "before" is the fully 3D field of the first trainer versions (every pitch
 * in the field equally likely, flash cues up to 30 degrees high, no map features), "after" is the profile
 * default (head level with a small off-level share, 12 degree flash cues, corners and doorways).
 * Gated like the other review experiments: set AIMTUNE_REVIEW; AIMTUNE_RUNS scales the runs per cell.
 */
@EnabledIfEnvironmentVariable(named = "AIMTUNE_REVIEW", matches = ".+")
class VerticalExperimentsTest {
    private val runs = (System.getenv("AIMTUNE_RUNS") ?: "30").toInt()

    private val after = TrainerGeometry.DEFAULT
    private val before = TrainerGeometry.DEFAULT.copy(
        elevation = ElevationMix.UNIFORM,
        arena = ArenaLayout.NONE,
        flash = TrainerGeometry.DEFAULT.flash.copy(cueElevationDegrees = 30.0),
    )
    private val geometries = linkedMapOf("before (fully 3D)" to before, "after (head level)" to after)

    private fun round(geometry: TrainerGeometry, kind: ScenarioKind, player: SimulatedPlayer, cm360: Double, seed: Long, offset: Long = 0L): RoundMetrics =
        computeRoundMetrics(HeadlessRound.of(kind, player, TEST_DPI, cm360, seed, 45, offset, geometry = geometry))

    private fun meanScoresOf(inputs: List<List<Map<ScoreMetric, Double>>>, weights: MetricWeights): List<Double> {
        val scores = ScoreCalculator(weights).scoreRounds(inputs.flatten())
        var i = 0
        return inputs.map { group -> group.indices.map { scores[i++] }.filterNotNull().average() }
    }

    private fun gainPlayer(change: Double, base: SimulatedPlayer = SimulatedPlayer.forHeadSizedTargets(50.0)) =
        base.copy(gainPreference = { v -> 1.0 + change * (1.0 - exp(-v / 6.0)) })

    private val fastHand = SimulatedPlayer.forHeadSizedTargets(50.0).copy(fittsSlopeMs = 55.0, maxHandSpeedCmPerS = 60.0)

    /** The best sensitivity of the simulated player per scenario and for the default mix, and where the misses come from. */
    @Test
    fun optimumAndAxes() {
        val out = StringBuilder()
        val grid = geometricGrid(20.0, 160.0, 9)
        val kinds = listOf(ScenarioKind.FLICK, ScenarioKind.SWITCHING, ScenarioKind.MICRO, ScenarioKind.TRACKING, ScenarioKind.FLASH)
        for (optimum in listOf(35.0, 50.0, 70.0)) {
            val player = SimulatedPlayer.forHeadSizedTargets(optimum)
            out.appendLine("== player optimum $optimum cm/360 (head-sized targets), $runs rounds per sensitivity ==")
            for ((label, geometry) in geometries) {
                val perKind = kinds.associateWith { kind ->
                    grid.mapIndexed { gi, cm -> parallelList(runs) { r -> round(geometry, kind, player, cm, 20_000L + gi * 1_000 + r, r * 45_000_000_000L) } }
                }
                val optima = kinds.joinToString(", ") { kind ->
                    val inputs = perKind.getValue(kind).map { g -> g.map { it.scoreInputs() } }
                    val weights = if (kind == ScenarioKind.FLASH) MetricWeights(MetricWeights.DEFAULT.values + (ScoreMetric.FLASH_DODGE to FlashImportance.SOMETIMES.weight)) else MetricWeights.DEFAULT
                    "${kind.id} %.1f".format(parabolicOptimum(grid, meanScoresOf(inputs, weights)))
                }
                val shares = grid.indices.map { gi -> perKind.getValue(ScenarioKind.FLICK)[gi].mapNotNull { it.verticalErrorShare }.average() }
                out.appendLine("  %-20s optimum per scenario: %s".format(label, optima))
                out.appendLine("  %-20s flick vertical share of the endpoint error by sensitivity: %s".format("", shares.joinToString(" ") { "%.0f%%".format(100 * it) }))
            }
        }
        write("vertical-optimum.txt", out.toString())
    }

    /** The sensitivity finder (flick and flash at "sometimes") against the optimum of the blended score curves of the same geometry. */
    @Test
    fun sensitivityFinder() {
        val out = StringBuilder()
        val player = SimulatedPlayer.forHeadSizedTargets(50.0)
        val range = SearchRange(20.0, 140.0)
        val grid = geometricGrid(20.0, 140.0, 9)
        val enabled = setOf(ScenarioKind.FLICK, ScenarioKind.FLASH)
        val n = maxOf(runs / 2, 12)
        val weights = MetricWeights(MetricWeights.DEFAULT.values + (ScoreMetric.FLASH_DODGE to FlashImportance.SOMETIMES.weight))
        val schedule = ScenarioSchedule(enabled, weights)
        for ((label, geometry) in geometries) {
            val perKind = enabled.associateWith { kind ->
                grid.mapIndexed { gi, cm -> parallelList(24) { r -> round(geometry, kind, player, cm, 150_000L + gi * 1_000 + r, r * 45_000_000_000L).scoreInputs() } }
            }
            val blend = grid.indices.map { gi -> schedule.shares.entries.sumOf { (kind, share) -> share * meanScoresOf(perKind.getValue(kind), weights)[gi] } }
            val truth = parabolicOptimum(grid, blend)
            for (rounds in listOf(15, 30)) {
                val tests = parallelList(n) { s -> playBlindTest(player, range, rounds, 800L + s, scenarioFor = schedule::kindFor, weights = weights, geometry = geometry) }
                val errors = tests.map { errorFactorOf(checkNotNull(it.result).optimumCm360, truth) }.sorted()
                val found = tests.map { checkNotNull(it.result).optimumCm360 }.sorted()
                out.appendLine(
                    "  %-20s blended truth %.1f cm/360 | %2d rounds: median found %.1f, error factor median %.2f p90 %.2f, confidence %.0f%% (n=%d)".format(
                        label, truth, rounds, found[n / 2], errors[n / 2], errors[(n * 9) / 10], tests.map { checkNotNull(it.result).confidencePercent }.average(), n,
                    ),
                )
                write("vertical-sensitivity.txt", out.toString())
            }
        }
    }

    /** The error analysis on click rounds: how often a curve is offered, and in the right direction. */
    @Test
    fun curveDetection() {
        val out = StringBuilder()
        val kinds = listOf(ScenarioKind.FLICK, ScenarioKind.SWITCHING, ScenarioKind.MICRO)
        for ((variantName, base) in listOf("default hand, 50 cm/360" to SimulatedPlayer.forHeadSizedTargets(50.0), "fast hand, 50 cm/360" to fastHand)) {
            out.appendLine("== Curve offered by the error analysis, $runs simulated players per cell: $variantName ==")
            for (rounds in listOf(15, 30)) {
                for ((label, geometry) in geometries) {
                    val cells = listOf(0.0, -0.15, 0.15, -0.25, 0.25, 0.5).map { change ->
                        val player = gainPlayer(change, base)
                        val results = parallelList(runs) { run ->
                            val metrics = (0 until rounds).map { r -> round(geometry, kinds[r % kinds.size], player, 50.0, 80_000L + run * 100 + r) }
                            val analysis = AccelAnalysis(movementSamples(metrics, TEST_DPI))
                            val fit = fitAccel(analysis)
                            fit.recommended?.let { (it.curve.multiplier(30.0) > it.curve.multiplier(3.0)) == (change > 0) }
                        }
                        "%+4.0f%%: offered %2d (right %2d)".format(change * 100, results.count { it != null }, results.count { it == true })
                    }
                    out.appendLine("  %2d rounds %-20s %s".format(rounds, label, cells.joinToString(" | ")))
                    write("vertical-curve-detection.txt", out.toString())
                }
            }
        }
    }

    /** The blind strength search with flick and flash rounds. */
    @Test
    fun strengthSearch() {
        val n = maxOf(runs / 2, 12)
        val rounds = (System.getenv("AIMTUNE_ROUNDS") ?: "24").toInt()
        val out = StringBuilder("== Blind strength search, $rounds rounds, $n players per cell, flick + flash ==\n")
        val range = SearchRange(70.0, 145.0)
        fun curveOf(pseudo: Double): AccelCurve? = (pseudo / 100.0).let { r -> if (abs(r - 1.0) < 0.02) null else NaturalCurve(0.05, r) }
        val kinds = listOf(ScenarioKind.FLICK, ScenarioKind.FLASH)
        for ((variantName, base) in listOf("default hand" to SimulatedPlayer.forHeadSizedTargets(50.0), "fast hand" to fastHand)) {
            out.appendLine("-- $variantName --")
            for ((label, geometry) in geometries) {
                val cells = listOf(0.0, 0.15, 0.25, -0.15, -0.25).map { change ->
                    val player = gainPlayer(change, base)
                    val rows = parallelList(n) { s ->
                        val test = playBlindTest(player, range, rounds, 700L + s, scenarioFor = { kinds[it % kinds.size] }, accelOf = ::curveOf, playedCm360 = 50.0, geometry = geometry)
                        val result = checkNotNull(test.result)
                        doubleArrayOf(result.optimumCm360 / 100.0, if (result.intervalLowCm360 > 100.0 || result.intervalHighCm360 < 100.0) 1.0 else 0.0, result.confidencePercent.toDouble())
                    }
                    val strengths = rows.map { it[0] }.sorted()
                    "%+4.0f%%: x%.2f (p25 %.2f p75 %.2f) excl %2d/%d conf %2.0f%%".format(change * 100, strengths[n / 2], strengths[n / 4], strengths[(n * 3) / 4], rows.count { it[1] > 0.5 }, n, rows.map { it[2] }.average())
                }
                out.appendLine("  %-20s %s".format(label, cells.joinToString(" | ")))
                write("vertical-strength-search.txt", out.toString())
            }
        }
    }

    private fun abVerdict(geometry: TrainerGeometry, player: SimulatedPlayer, curve: AccelCurve?, kinds: List<ScenarioKind>, seed: Long): AbStatus {
        val plan = AccelAbPlan(kinds, seed)
        val outcomes = mutableListOf<AbOutcome>()
        var verdict = evaluateAb(emptyList(), MetricWeights.DEFAULT)
        for (index in 0 until 2 * plan.maxPairs) {
            val round = plan.round(index)
            val accelerated = round.condition == AbCondition.ACCEL
            val recording = HeadlessRound.run(
                player, TEST_DPI, 50.0, round.seed + if (accelerated) 7L else 0L, 45, index * 45L * 1_000_000_000L,
                round.scenario.create(45L * 1_000_000_000L, round.seed, geometry), if (accelerated) curve else null,
            )
            outcomes += AbOutcome(round.pair, round.condition, computeRoundMetrics(recording).scoreInputs())
            if (index % 2 == 1) {
                verdict = evaluateAb(outcomes, MetricWeights.DEFAULT)
                if (verdict.status != AbStatus.NEED_MORE_ROUNDS) break
            }
        }
        return verdict.status
    }

    /** The blind A/B test with flash pairs: false verdicts for two equal arms and the verdict on a curve that really helps. */
    @Test
    fun blindTest() {
        val out = StringBuilder()
        val n = maxOf(runs * 4, 120)
        val kinds = listOf(ScenarioKind.FLICK, ScenarioKind.FLASH, ScenarioKind.SWITCHING, ScenarioKind.FLASH, ScenarioKind.MICRO, ScenarioKind.FLASH)
        fun share(list: List<AbStatus>, status: AbStatus) = 100.0 * list.count { it == status } / list.size
        for ((variantName, base) in listOf("default hand" to SimulatedPlayer.forHeadSizedTargets(50.0), "fast hand" to fastHand)) for ((label, geometry) in geometries) {
            val nulls = parallelList(n) { abVerdict(geometry, gainPlayer(0.0, base), null, kinds, 5_000L + it) }
            out.appendLine(
                "  %-12s %-20s same arms (n=%d): better %.1f%%, worse %.1f%%, no difference %.0f%%, undecided %.0f%%".format(
                    variantName, label, n, share(nulls, AbStatus.ACCEL_BETTER), share(nulls, AbStatus.ACCEL_WORSE), share(nulls, AbStatus.NO_DIFFERENCE), share(nulls, AbStatus.NEED_MORE_ROUNDS),
                ),
            )
            for (change in listOf(-0.25, 0.25)) {
                val real = parallelList(minOf(n, 60)) { abVerdict(geometry, gainPlayer(change, base), NaturalCurve(0.05, 1.0 + change), kinds, 9_000L + it) }
                out.appendLine(
                    "      player wants %+.0f%%, curve of that strength: better %.0f%%, worse %.0f%%, no difference %.0f%%, undecided %.0f%% (n=%d)".format(
                        change * 100, share(real, AbStatus.ACCEL_BETTER), share(real, AbStatus.ACCEL_WORSE), share(real, AbStatus.NO_DIFFERENCE), share(real, AbStatus.NEED_MORE_ROUNDS), real.size,
                    ),
                )
            }
            write("vertical-blind-test.txt", out.toString())
        }
    }
}
