package dev.veqzo.aimtune.app.accel

import dev.veqzo.aimtune.core.accel.RawAccelArgs
import dev.veqzo.aimtune.core.accel.RawAccelCapMode
import dev.veqzo.aimtune.core.accel.RawAccelMode
import dev.veqzo.aimtune.core.accel.RawAccelProfile
import kotlin.test.Test
import kotlin.test.assertTrue

class RefineDiffTest {
    private fun classic(capMode: RawAccelCapMode) = RawAccelProfile(
        name = "default",
        args = RawAccelArgs(
            mode = RawAccelMode.CLASSIC, gain = true, acceleration = 0.005, exponentClassic = 2.0, inputOffset = 10.0,
            capX = 62.5, capY = 1.6, capMode = capMode,
        ),
        outputDpi = 600.0,
        deviceDpi = 800.0,
    )

    @Test
    fun `in_out shows the acceleration raw accel derives from the cap, not the ignored field`() {
        val text = RefineDiff.describe(classic(RawAccelCapMode.IN_OUT))
        assertTrue("acceleration 0.00571 (from the cap)" in text, text)
    }

    @Test
    fun `output cap mode shows the acceleration field itself`() {
        val text = RefineDiff.describe(classic(RawAccelCapMode.OUTPUT))
        assertTrue("acceleration 0.005  exponent" in text, text)
    }
}
