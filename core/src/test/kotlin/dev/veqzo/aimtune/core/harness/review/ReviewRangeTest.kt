package dev.veqzo.aimtune.core.harness.review

import dev.veqzo.aimtune.core.optimizer.Observation
import dev.veqzo.aimtune.core.optimizer.SensitivityOptimizer
import dev.veqzo.aimtune.core.units.AimStyle
import dev.veqzo.aimtune.core.units.SearchRange
import dev.veqzo.aimtune.core.units.searchRange
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.random.Random
import kotlin.test.Test

@EnabledIfEnvironmentVariable(named = "AIMTUNE_REVIEW", matches = ".+")
class ReviewRangeTest {
    @Test
    fun rangeAndScaling() {
        val out = StringBuilder("== Search range for style x pad width x current cm/360 ==\n")
        for (style in AimStyle.entries) for (pad in listOf(27.0, 32.0, 45.0, 90.0)) {
            out.append("%-6s pad %3.0f cm: ".format(style, pad))
            for (cur in listOf(15.0, 25.0, 40.0, 65.0, 100.0)) {
                val r = searchRange(style, cur, pad)
                out.append("cur %3.0f -> %5.1f-%5.1f (x%.2f)   ".format(cur, r.minCm360, r.maxCm360, r.maxCm360 / r.minCm360))
            }
            out.appendLine()
        }
        out.appendLine("extreme: cur 0.5 -> ${searchRange(AimStyle.MIXED, 0.5, 32.0)}, cur 3000 -> ${searchRange(AimStyle.MIXED, 3000.0, 32.0)}")
        out.appendLine()
        out.appendLine("== Optimizer cost vs. number of stored rounds (analyze + suggestNext, one thread) ==")
        val random = Random(1)
        for (n in listOf(30, 100, 200, 400)) {
            val range = SearchRange(20.0, 140.0)
            val history = List(n) { Observation((it / 10).toLong(), 20.0 * Math.pow(7.0, random.nextDouble()), 50.0 + 15 * random.nextDouble(), 0) }
            val optimizer = SensitivityOptimizer(range, 1L)
            val t0 = System.nanoTime()
            optimizer.analyze(history)
            optimizer.suggestNext(history)
            out.appendLine("  n=%4d: %.1f s".format(n, (System.nanoTime() - t0) / 1e9))
        }
        write("review-range.txt", out.toString())
    }
}
