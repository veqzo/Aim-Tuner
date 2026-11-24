package dev.veqzo.aimtune.core.harness.review

import dev.veqzo.aimtune.core.metrics.computeRoundMetrics
import dev.veqzo.aimtune.core.scenario.ScenarioKind
import dev.veqzo.aimtune.core.scenario.TrainerGeometry
import dev.veqzo.aimtune.core.sim.HeadlessRound
import dev.veqzo.aimtune.core.sim.SimulatedPlayer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.math.hypot

/**
 * Where the simulated hand's speed lies against the user's real hand (M22 refine calibration). The real figures come from the
 * stored rounds of the user's database (accel_refine session, 800 DPI, 48.7 cm/360, their profile, per report with motion,
 * speed = hypot(dx, dy) / DPI / dt in in/s, dt below 50 ms): share of reports above the profile's offset of 10 in/s and above
 * 20 in/s, and the 50/90/99 % quantiles. Prints the same figures for simulated rounds, so the hand speed can be fitted.
 */
@EnabledIfEnvironmentVariable(named = "AIMTUNE_REVIEW", matches = ".+")
class RefineSpeedCalibrationTest {
    private val cm360 = 48.74
    private val dpi = 800.0

    private class Speeds(val values: List<Double>) {
        fun share(above: Double) = 100.0 * values.count { it > above } / values.size.coerceAtLeast(1)
        fun quantile(p: Double): Double = values.sorted().let { if (it.isEmpty()) 0.0 else it[(p * (it.size - 1)).toInt()] }
        fun line() = "reports %6d  p50 %5.1f  p90 %5.1f  p99 %5.1f  >10 %5.1f %%  >20 %5.1f %%".format(
            values.size, quantile(0.5), quantile(0.9), quantile(0.99), share(10.0), share(20.0),
        )
    }

    private class Played(val speeds: Speeds, val timeToHitMs: Double, val firstShotAccuracy: Double, val turnMs: Double?)

    private fun play(kind: ScenarioKind, player: SimulatedPlayer, geometry: TrainerGeometry?, rounds: Int): Played {
        val per = parallelList(rounds) { r ->
            val recording = HeadlessRound.of(kind, player, dpi, cm360, 500L + r, ROUND_SECONDS, geometry = geometry)
            val out = ArrayList<Double>()
            var previous = -1L
            for (e in recording.mouseEvents) {
                if (previous >= 0 && (e.dx != 0 || e.dy != 0)) {
                    val dt = (e.timeNanos - previous) / 1e9
                    if (dt > 0.0 && dt < 0.05) out += hypot(e.dx.toDouble(), e.dy.toDouble()) / dpi / dt
                }
                previous = e.timeNanos
            }
            out to computeRoundMetrics(recording)
        }
        val metrics = per.map { it.second }
        return Played(
            Speeds(per.flatMap { it.first }), metrics.mapNotNull { it.meanTimeToHitMs }.average(),
            metrics.mapNotNull { it.firstShotAccuracy }.average(), metrics.mapNotNull { it.flashMeanTurnMs }.average().takeIf { !it.isNaN() },
        )
    }

    @Test
    fun speedsAgainstTheRealHand() {
        val out = StringBuilder()
        out.appendLine("== simulated hand speed (in/s per report with motion) @ git ${gitShortHash()} | $cm360 cm/360, $dpi DPI, plain ==")
        out.appendLine("real (user, accel_refine session, own profile): flick 7.8 %/0.3 % p50 1.8 p90 9 p99 16 | switching 16 %/1.9 % p50 2.5 p90 12.5 p99 21 | micro 0.1 %/0.1 % p50 1.3 p90 3 p99 6 | flash 34.6 %/22.5 % p50 3.7 p90 28 p99 38")
        out.appendLine("real timing: flick tth 678 ms, first-shot 0.75 | switching tth 764 | micro tth 578 | flash tth 731, turn 356 ms")
        val kinds = listOf(ScenarioKind.FLICK, ScenarioKind.SWITCHING, ScenarioKind.MICRO, ScenarioKind.FLASH)
        val geometry = TrainerGeometry.DEFAULT
        for (slope in listOf(40.0, 25.0, 15.0)) {
            for (maxSpeed in listOf(150.0, 300.0)) {
                val player = SimulatedPlayer.forHeadSizedTargets(50.0).copy(maxHandSpeedCmPerS = maxSpeed, fittsSlopeMs = slope, fittsInterceptMs = 40.0)
                out.appendLine("-- game geometry, fittsSlopeMs $slope, fittsInterceptMs 40, maxHandSpeedCmPerS $maxSpeed")
                for (kind in kinds) {
                    val played = play(kind, player, geometry, 10)
                    out.appendLine("   %-10s %s | tth %4.0f  first-shot %.2f  turn %s".format(kind.id, played.speeds.line(), played.timeToHitMs, played.firstShotAccuracy, played.turnMs?.let { "%.0f".format(it) } ?: "-"))
                }
            }
        }
        write("refine-speed-calibration.txt", out.toString())
    }
}
