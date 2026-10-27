package de.yuuto.aimtune.core.harness.review

import de.yuuto.aimtune.core.accel.AccelAnalysis
import de.yuuto.aimtune.core.accel.AccelCurve
import de.yuuto.aimtune.core.accel.AccelScenarios
import de.yuuto.aimtune.core.accel.CurveStrength
import de.yuuto.aimtune.core.accel.NaturalCurve
import de.yuuto.aimtune.core.accel.RawAccelArgs
import de.yuuto.aimtune.core.accel.RawAccelCapMode
import de.yuuto.aimtune.core.accel.RawAccelMode
import de.yuuto.aimtune.core.accel.RawAccelProfile
import de.yuuto.aimtune.core.accel.RefineParameter
import de.yuuto.aimtune.core.accel.adjusted
import de.yuuto.aimtune.core.accel.boundedRecommendation
import de.yuuto.aimtune.core.accel.fitAccel
import de.yuuto.aimtune.core.accel.movementSamples
import de.yuuto.aimtune.core.harness.TEST_DPI
import de.yuuto.aimtune.core.harness.geometricGrid
import de.yuuto.aimtune.core.harness.playBlindTest
import de.yuuto.aimtune.core.metrics.RoundMetrics
import de.yuuto.aimtune.core.metrics.computeRoundMetrics
import de.yuuto.aimtune.core.scenario.TrainerGeometry
import de.yuuto.aimtune.core.score.MetricWeights
import de.yuuto.aimtune.core.score.ScoreCalculator
import de.yuuto.aimtune.core.score.scoreInputs
import de.yuuto.aimtune.core.sim.HandAdaptation
import de.yuuto.aimtune.core.sim.HeadlessRound
import de.yuuto.aimtune.core.sim.SimulatedPlayer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.math.abs
import kotlin.math.ln

/**
 * Step 0 of docs/FIND-CURVE-REDESIGN.md: the reference case through today's "Find a curve" paths, no product change.
 *
 * Simulated people whose hand is adapted to the user's own Raw Accel profile (classic, gain form, offset 10, cap 62.5 in/s at 1.6,
 * output DPI 600 at device DPI 800) and whose true wish ("truth") is that profile, a weaker or stronger one, a later transition, a
 * different family, or no curve. Three paths:
 *
 *  - SEARCH: the strength search as the app runs it (24 blind rounds, natural curve of strength s played over plain, flash rounds on).
 *  - CALIB-PLAIN: 15 plain click rounds, error analysis, fit (bounded), as the app does it today.
 *  - CALIB-OWN: the same 15 rounds played with the user's profile (the proposed step 1, not in the app yet). The analysis points are
 *    then read as a correction to the delivered gain; the report shows how large the expected correction is against the noise.
 *
 * "shape error" = mean |ln(g(v)/g(vm)) - ln(truth(v)/truth(vm))| over the 5th..95th percentile of the hand speeds, g normalised at the
 * median speed vm, so that only the shape counts. AIMTUNE_SEEDS scales the run counts (40 = the numbers in the table).
 * Reports: core/build/reports/harness/gate-find-redesign-0-{default,real}.txt.
 */
@EnabledIfEnvironmentVariable(named = "AIMTUNE_REVIEW", matches = ".+")
class FindCurveReferenceGateTest {
    private class Hand(val name: String, val gameCm360: Double, val geometry: TrainerGeometry?, val base: SimulatedPlayer, val startScale: Double)

    private val hands = listOf(
        Hand("default", 55.0, null, SimulatedPlayer(), 0.95),
        Hand("real", 48.74, TrainerGeometry.DEFAULT, SimulatedPlayer(fittsInterceptMs = 40.0, fittsSlopeMs = 25.0, maxHandSpeedCmPerS = 300.0), 0.35),
    )

    private val imported = RawAccelProfile(
        "default", RawAccelArgs(RawAccelMode.CLASSIC, gain = true, inputOffset = 10.0, capX = 62.5, capY = 1.6, capMode = RawAccelCapMode.IN_OUT), 600.0, 800.0,
    )
    private val offset = imported.args.inputOffset
    private val scale = (System.getenv("AIMTUNE_SEEDS")?.toIntOrNull() ?: 40) / 40.0
    private fun n(base: Int) = (base * scale).toInt().coerceAtLeast(4)

    private class Truth(val name: String, val curve: (Double) -> Double, val runs: Int)

    private val truths = listOf(
        Truth("OWN (truth = profile)", { imported.curve.multiplier(it) }, n(24)),
        Truth("CAP x0.5", { imported.adjusted(RefineParameter.HIGH_GAIN, 0.5).curve.multiplier(it) }, n(24)),
        Truth("CAP x2.0", { imported.adjusted(RefineParameter.HIGH_GAIN, 2.0).curve.multiplier(it) }, n(24)),
        Truth("TRANS x2.0 (later rise)", { imported.adjusted(RefineParameter.TRANSITION, 2.0).curve.multiplier(it) }, n(24)),
        Truth("NATURAL limit 1.3", { NaturalCurve(0.05, 1.3).multiplier(it) }, n(24)),
        Truth("PLAIN (truth = linear)", { 1.0 }, n(24)),
    )

    private fun player(hand: Hand, truth: Truth, optimumScale: Double): SimulatedPlayer =
        hand.base.copy(
            optimumScale = optimumScale, gainPreference = truth.curve,
            // A fresh, mutable hand per simulated person, settled on the user's profile.
            adaptation = HandAdaptation.adaptedTo(truth.curve, delivered = { v -> imported.curve.multiplier(v) }),
        )

    /** Optimum scale at which the user's profile, played at the game sensitivity, is the best level for a stateless hand (as in RefineGateTest). */
    private fun calibrate(hand: Hand, log: StringBuilder): Double {
        val kinds = AccelScenarios.of(true)
        fun best(scaleNow: Double): Double {
            val grid = geometricGrid(0.75, 1.33, 9)
            val per = 120
            val base = hand.base.copy(optimumScale = scaleNow, gainPreference = { imported.curve.multiplier(it) })
            val inputs = grid.indices.map { gi ->
                parallelList(per) { r ->
                    val index = gi * per + r
                    computeRoundMetrics(
                        HeadlessRound.of(
                            kinds[r % kinds.size], base, TEST_DPI, hand.gameCm360 / grid[gi], 55_000L + index, ROUND_SECONDS,
                            index * ROUND_SECONDS * NANOS_PER_SECOND, accel = imported, geometry = hand.geometry,
                        ),
                    ).scoreInputs()
                }
            }
            val scores = ScoreCalculator(MetricWeights.DEFAULT).scoreRounds(inputs.flatten(), grid.indices.flatMap { gi -> List(per) { kinds[it % kinds.size].id } })
            var i = 0
            val means = grid.indices.map { (0 until per).mapNotNull { scores[i++] }.average() }
            return SmoothCurve(grid, means, degree = 2).argmax(grid.first(), grid.last())
        }
        var scaleNow = hand.startScale
        repeat(3) { step ->
            val k = best(scaleNow)
            log.appendLine("  calibration ${hand.name} step ${step + 1}: optimumScale %.4f -> best sensitivity factor %.3f".format(scaleNow, k))
            scaleNow *= k
        }
        return scaleNow
    }

    private fun shapeError(gain: (Double) -> Double, truth: (Double) -> Double, analysis: AccelAnalysis): Double {
        val lo = analysis.speedQuantile(0.05)
        val hi = analysis.speedQuantile(0.95)
        val mid = analysis.speedQuantile(0.5)
        if (lo <= 0.0 || hi <= lo) return Double.NaN
        val grid = geometricGrid(lo, hi, 12)
        return grid.map { v -> abs(ln(gain(v) / gain(mid)) - ln(truth(v) / truth(mid))) }.average()
    }

    private fun rounds(player: SimulatedPlayer, hand: Hand, seed: Long, count: Int, accel: RawAccelProfile?): List<RoundMetrics> {
        val kinds = AccelScenarios.CLICK
        return (0 until count).map {
            computeRoundMetrics(
                HeadlessRound.of(kinds[it % kinds.size], player, TEST_DPI, hand.gameCm360, seed * 1_000 + it, ROUND_SECONDS, it * ROUND_SECONDS * NANOS_PER_SECOND, accel = accel, geometry = hand.geometry),
            )
        }
    }

    private class SearchRun(val offered: Boolean, val strength: Double, val shapeError: Double)
    private class CalibRun(val offered: Boolean, val shapeError: Double, val above10: Double, val above20: Double, val q5: Double, val q50: Double, val q95: Double)
    private class OwnRun(val bins: Int, val signal: Double, val error: Double, val noise: Double, val offered: Boolean)

    private fun search(hand: Hand, truth: Truth, optimumScale: Double, seed: Long): SearchRun {
        val kinds = AccelScenarios.of(true)
        val result = checkNotNull(
            playBlindTest(
                player(hand, truth, optimumScale), CurveStrength.searchRange(true), 24, seed, scenarioFor = { kinds[it % kinds.size] },
                geometry = hand.geometry, accelOf = { p -> CurveStrength.curve(CurveStrength.fromPseudo(p)) }, playedCm360 = hand.gameCm360,
            ).result,
        )
        val s = CurveStrength.fromPseudo(result.optimumCm360)
        val offered = CurveStrength.excludesPlain(s, CurveStrength.fromPseudo(result.intervalLowCm360), CurveStrength.fromPseudo(result.intervalHighCm360))
        // The delivered gain is plain (1.0) times the natural curve of the strength, compared with the truth's shape at the hand speeds of a plain calibration.
        val analysis = AccelAnalysis(movementSamples(rounds(player(hand, truth, optimumScale), hand, seed + 1, 6, null), TEST_DPI))
        val curve: AccelCurve = NaturalCurve(CurveStrength.DECAY, s)
        return SearchRun(offered, s, shapeError({ curve.multiplier(it) }, truth.curve, analysis))
    }

    private fun calibPlain(hand: Hand, truth: Truth, optimumScale: Double, seed: Long): CalibRun {
        val metrics = rounds(player(hand, truth, optimumScale), hand, seed, 15, null)
        val analysis = AccelAnalysis(movementSamples(metrics, TEST_DPI))
        val fit = boundedRecommendation(fitAccel(analysis), analysis)
        val used = analysis.used.map { it.speed }
        fun share(limit: Double) = if (used.isEmpty()) 0.0 else 100.0 * used.count { it > limit } / used.size
        return CalibRun(
            fit != null, if (fit == null) Double.NaN else shapeError({ fit.totalGain(it) }, truth.curve, analysis),
            share(offset), share(2 * offset), analysis.speedQuantile(0.05), analysis.speedQuantile(0.5), analysis.speedQuantile(0.95),
        )
    }

    private fun calibOwn(hand: Hand, truth: Truth, optimumScale: Double, seed: Long): OwnRun {
        val metrics = rounds(player(hand, truth, optimumScale), hand, seed, 15, imported)
        val analysis = AccelAnalysis(movementSamples(metrics, TEST_DPI))
        val points = analysis.points
        if (points.isEmpty()) return OwnRun(0, Double.NaN, Double.NaN, Double.NaN, false)
        // Expected correction of the delivered gain, normalised like the analysis (median over the points is 0).
        val expected = points.map { ln(truth.curve(it.speed) / imported.curve.multiplier(it.speed)) }
        val centre = expected.sorted()[expected.size / 2]
        val signal = expected.map { abs(it - centre) }.average()
        val centred = points.map { it.logGain }.sorted()[points.size / 2]
        val error = points.indices.map { abs((points[it].logGain - centred) - (expected[it] - centre)) }.average()
        val fit = fitAccel(analysis).recommended
        return OwnRun(points.size, signal, error, points.map { it.standardError }.average(), fit != null)
    }

    @Test fun defaultHand() = runHand(hands[0])

    @Test fun realHand() = runHand(hands[1])

    private fun runHand(hand: Hand) {
        val out = StringBuilder()
        out.appendLine("== GATE find-redesign-0 @ git ${gitShortHash()} | hand ${hand.name} | game ${hand.gameCm360} cm/360 | profile: classic gain, offset 10, cap 62.5 -> 1.6, output 600 / device 800 | no product change ==")
        val log = StringBuilder()
        val optimumScale = calibrate(hand, log)
        val started = System.currentTimeMillis()
        val searchRows = StringBuilder()
        val plainRows = StringBuilder()
        val ownRows = StringBuilder()
        for (truth in truths) {
            val search = parallelList(truth.runs) { s -> search(hand, truth, optimumScale, 7_000L + s) }
            val plain = parallelList(truth.runs) { s -> calibPlain(hand, truth, optimumScale, 8_000L + s) }
            val own = parallelList(truth.runs) { s -> calibOwn(hand, truth, optimumScale, 9_000L + s) }
            fun pct(count: Int, total: Int) = "%5.1f%%".format(100.0 * count / total)
            searchRows.appendLine(
                "%-26s %4d  %6s  %8.3f  %10.3f".format(
                    truth.name, search.size, pct(search.count { it.offered }, search.size), median(search.map { it.strength }), meanOf(search.map { it.shapeError }),
                ),
            )
            plainRows.appendLine(
                "%-26s %4d  %6s  %10.3f | %6.1f%% %6.1f%% | %5.1f %5.1f %5.1f".format(
                    truth.name, plain.size, pct(plain.count { it.offered }, plain.size), meanOf(plain.map { it.shapeError }),
                    meanOf(plain.map { it.above10 }), meanOf(plain.map { it.above20 }),
                    median(plain.map { it.q5 }), median(plain.map { it.q50 }), median(plain.map { it.q95 }),
                ),
            )
            ownRows.appendLine(
                "%-26s %4d  %5.1f  %12.4f  %11.4f  %12.4f  %6s".format(
                    truth.name, own.size, meanOf(own.map { it.bins.toDouble() }), meanOf(own.map { it.signal }), meanOf(own.map { it.error }), meanOf(own.map { it.noise }),
                    pct(own.count { it.offered }, own.size),
                ),
            )
            out.appendLine("[${truth.name} done, elapsed %d s]".format((System.currentTimeMillis() - started) / 1000))
        }
        out.appendLine()
        out.appendLine("-- calibration of the simulated hand (target sensitivity factor 1.00)")
        out.append(log)
        out.appendLine()
        out.appendLine("-- SEARCH (today's primary path): 24 blind rounds, flash on. offered = the interval excludes 'no curve'; strength = median best strength; shape err vs the truth")
        out.appendLine("truth                      runs  offered  strength  shape err")
        out.append(searchRows)
        out.appendLine()
        out.appendLine("-- CALIB-PLAIN (today's analysis path): 15 plain rounds, bounded fit. offered = a curve is recommended. Speeds in in/s: share of used reaches above the profile's offset (10) and above twice that, q5/q50/q95 median")
        out.appendLine("truth                      runs  offered  shape err  | >10 in/s  >20 in/s |   q5   q50   q95")
        out.append(plainRows)
        out.appendLine()
        out.appendLine("-- CALIB-OWN (step 1 preview): 15 rounds played with the user's profile; analysis points read as a correction of the delivered gain. signal = mean |expected correction| (log, centred); error = mean |measured - expected|; noise = mean standard error; offered = fit beats 'no change'")
        out.appendLine("truth                      runs   bins  signal(log)  error(log)  noise(log)    offered")
        out.append(ownRows)
        out.appendLine()
        out.appendLine("reading: a path identifies the truth when offered is high for the non-OWN/non-PLAIN truths, low for OWN and PLAIN (null), and shape err is clearly below the spread of the truths' own shapes; CALIB-OWN is identifiable where signal > error ~ noise.")
        write("gate-find-redesign-0-${hand.name}.txt", out.toString())
    }
}
