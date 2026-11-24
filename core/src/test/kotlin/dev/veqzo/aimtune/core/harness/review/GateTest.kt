package dev.veqzo.aimtune.core.harness.review

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.test.Test

/**
 * The gate: sensitivity recovery, curve detection, flat false positives and strength search over the [gateModels].
 * Set AIMTUNE_REVIEW; AIMTUNE_GATE_TAG names the report (default "before"), AIMTUNE_GATE_SECTIONS picks sections
 * (comma list of S,C,F,A), AIMTUNE_SEEDS scales all seed counts (40 = 1.0).
 */
@EnabledIfEnvironmentVariable(named = "AIMTUNE_REVIEW", matches = ".+")
class GateTest {
    @Test
    fun gate() {
        val tag = System.getenv("AIMTUNE_GATE_TAG")?.takeIf { it.isNotBlank() } ?: "before"
        val sections = (System.getenv("AIMTUNE_GATE_SECTIONS")?.takeIf { it.isNotBlank() } ?: "S,C,F,A,N")
            .split(',').map { it.trim().uppercase() }.filter { it.isNotEmpty() }.map { it[0] }.toSet()
        val scale = (System.getenv("AIMTUNE_SEEDS")?.toIntOrNull() ?: 40) / 40.0
        val bounded = System.getenv("AIMTUNE_GATE_CURVE")?.lowercase() == "bounded"
        val cfg = GateConfig(
            tag = tag, sections = sections, seedScale = scale,
            curve = if (bounded) BoundedCurveMethod else LegacyCurveMethod, curveName = if (bounded) "bounded fitAccel" else "legacy fitAccel",
        )
        write("gate-$tag.txt", runGate(cfg))
    }
}
