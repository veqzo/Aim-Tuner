package dev.veqzo.aimtune.storage.review

import dev.veqzo.aimtune.core.accel.settings.AxisRatio
import dev.veqzo.aimtune.core.accel.settings.AxisRatioAnalysis
import dev.veqzo.aimtune.core.accel.settings.ReachSpread
import dev.veqzo.aimtune.core.accel.settings.ReportSpeedProfile
import dev.veqzo.aimtune.core.accel.settings.RotationAnalysis
import dev.veqzo.aimtune.core.accel.settings.SnapReplay
import dev.veqzo.aimtune.core.accel.settings.firstReaches
import dev.veqzo.aimtune.core.accel.settings.reachSpread
import dev.veqzo.aimtune.storage.Database
import dev.veqzo.aimtune.storage.RoundRepository
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test

/**
 * Runs the passive setting analyses (M22e) once on the plain rounds of a COPY of a real database (AIMTUNE_REAL_DB) and prints
 * the spreads of its first movements, to compare with the simulated hand. Opening the copy migrates it and may write to it, so
 * never point this at the live database. Writes the report to build/reports/real-passive.txt.
 */
@EnabledIfEnvironmentVariable(named = "AIMTUNE_REAL_DB", matches = ".+")
class RealDataPassiveTest {
    @Test
    fun analysesOnRealRounds() {
        val out = StringBuilder()
        Database.open(Path.of(System.getenv("AIMTUNE_REAL_DB"))).use { db ->
            val repository = RoundRepository(db)
            val rows = repository.rounds()
            out.appendLine("rounds in database: ${rows.size}; plain: ${rows.count { it.accel == null }}")
            val plain = rows.filter { it.accel == null }
            out.appendLine("plain rounds by scenario: ${plain.groupingBy { it.scenario }.eachCount()}; cm/360 ${plain.map { it.cm360 }.distinct()}")
            val recordings = plain.mapNotNull { repository.loadRecording(it.id) }
            out.appendLine("dpi ${recordings.map { it.dpi }.distinct()}, mouse reports per round ${recordings.map { it.mouseEvents.size }}")
            val reaches = firstReaches(recordings)
            out.appendLine("usable first reaches: ${reaches.size} (per scenario ${reaches.groupingBy { it.scenarioId }.eachCount()})")
            out.appendLine("real spread: ${format(reachSpread(reaches))}")
            for (scenario in reaches.map { it.scenarioId }.distinct()) {
                out.appendLine("  $scenario only: ${format(reachSpread(reaches.filter { it.scenarioId == scenario }))}")
            }
            out.appendLine("rotation: ${RotationAnalysis.analyze(reaches)}")
            for (ratio in AxisRatio.entries) out.appendLine("$ratio: ${AxisRatioAnalysis.analyze(reaches, ratio)}")
            val snap = SnapReplay.analyze(recordings)
            out.appendLine("snap: ${snap.result}")
            snap.rows.forEach { out.appendLine("  $it") }
            out.appendLine("speed: ${ReportSpeedProfile.summarize(recordings)}")
        }
        println(out)
        val dir = Path.of("build", "reports")
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("real-passive.txt"), out.toString())
    }

    private fun format(s: ReachSpread): String {
        fun one(label: String, v: dev.veqzo.aimtune.core.accel.settings.Spread?) =
            if (v == null) "$label n/a" else "%s sd %.3f robust %.3f (n=%d)".format(label, v.sd, v.robustSd, v.n)
        return "reaches ${s.reaches}; ${one("angle deg", s.angle)}; ${one("ln(got/need) horizontal", s.horizontalLogRatio)}; ${one("vertical", s.verticalLogRatio)}"
    }
}
