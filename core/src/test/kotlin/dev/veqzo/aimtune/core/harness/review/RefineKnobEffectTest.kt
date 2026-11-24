package dev.veqzo.aimtune.core.harness.review

import dev.veqzo.aimtune.core.accel.RawAccelArgs
import dev.veqzo.aimtune.core.accel.RawAccelCapMode
import dev.veqzo.aimtune.core.accel.RawAccelMode
import dev.veqzo.aimtune.core.accel.RawAccelProfile
import dev.veqzo.aimtune.core.accel.RefineParameter
import dev.veqzo.aimtune.core.accel.adjusted
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/** What the two refine knobs do to the user's profile: its curve multiplier (without the DPI factor) at hand speeds in in/s. */
@EnabledIfEnvironmentVariable(named = "AIMTUNE_REVIEW", matches = ".+")
class RefineKnobEffectTest {
    private val imported = RawAccelProfile(
        "default", RawAccelArgs(RawAccelMode.CLASSIC, gain = true, inputOffset = 10.0, capX = 62.5, capY = 1.6, capMode = RawAccelCapMode.IN_OUT), 600.0, 800.0,
    )

    @Test
    fun knobs() {
        val speeds = listOf(5.0, 10.0, 15.0, 20.0, 30.0, 40.0, 62.5, 80.0, 120.0)
        val out = StringBuilder()
        out.appendLine("== curve multiplier of the user's profile at hand speeds (in/s) @ git ${gitShortHash()} ==")
        out.appendLine("%-15s".format("speed") + speeds.joinToString("  ") { "%6.1f".format(it) })
        val variants = listOf(
            "imported" to imported,
            "HIGH_GAIN 0.75" to imported.adjusted(RefineParameter.HIGH_GAIN, 0.75),
            "HIGH_GAIN 1.33" to imported.adjusted(RefineParameter.HIGH_GAIN, 1.33),
            "TRANSITION 0.5" to imported.adjusted(RefineParameter.TRANSITION, 0.5),
            "TRANSITION 2.0" to imported.adjusted(RefineParameter.TRANSITION, 2.0),
        )
        for ((name, profile) in variants) out.appendLine("%-15s".format(name) + speeds.joinToString("  ") { "%6.3f".format(profile.curve.multiplier(it)) })
        write("refine-knob-effect.txt", out.toString())
    }
}
