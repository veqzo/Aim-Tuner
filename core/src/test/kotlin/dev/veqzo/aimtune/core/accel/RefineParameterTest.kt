package dev.veqzo.aimtune.core.accel

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RefineParameterTest {
    private val speeds = listOf(0.5, 5.0, 10.0, 15.0, 25.0, 40.0, 62.5, 100.0, 300.0)

    private fun profile(args: RawAccelArgs, outputDpi: Double = 600.0, deviceDpi: Double = 800.0) = RawAccelProfile("p", args, outputDpi, deviceDpi)

    private val user = profile(RawAccelArgs(RawAccelMode.CLASSIC, gain = true, inputOffset = 10.0, capX = 62.5, capY = 1.6, capMode = RawAccelCapMode.IN_OUT))
    private val classicOut = profile(RawAccelArgs(RawAccelMode.CLASSIC, gain = false, acceleration = 0.03, inputOffset = 1.0, capY = 1.5, capMode = RawAccelCapMode.OUTPUT))
    private val classicUncapped = profile(RawAccelArgs(RawAccelMode.CLASSIC, gain = true, acceleration = 0.004, capX = 0.0, capY = 0.0, capMode = RawAccelCapMode.OUTPUT))
    private val classicIn = profile(RawAccelArgs(RawAccelMode.CLASSIC, gain = false, acceleration = 0.03, inputOffset = 2.0, capX = 30.0, capMode = RawAccelCapMode.INPUT))
    private val naturalRising = profile(RawAccelArgs(RawAccelMode.NATURAL, gain = false, decayRate = 0.15, limit = 2.0, inputOffset = 2.0))
    private val naturalFalling = profile(RawAccelArgs(RawAccelMode.NATURAL, gain = true, decayRate = 0.1, limit = 0.6))
    private val lut = profile(RawAccelArgs(RawAccelMode.LUT, gain = false, lut = listOf(2f, 0.9f, 5f, 1.0f, 30f, 1.6f)))
    private val noAccel = profile(RawAccelArgs(RawAccelMode.NOACCEL))
    private val all = listOf(user, classicOut, classicUncapped, classicIn, naturalRising, naturalFalling, lut, noAccel)

    private fun RawAccelProfile.curveAt(v: Double) = curve.multiplier(v)

    @Test
    fun `a factor of one is the profile itself`() {
        for (p in all) for (parameter in RefineParameter.entries.filter { p.supports(it) }) assertSame(p, p.adjusted(parameter, 1.0), "$parameter of ${p.args.mode}")
    }

    @Test
    fun `every supported parameter stays valid over the whole searched range`() {
        for (p in all) for (parameter in RefineParameter.entries.filter { p.supports(it) }) {
            for (i in 0..20) {
                val factor = parameter.min + (parameter.max - parameter.min) * i / 20
                val adjusted = p.adjusted(parameter, factor) // RawAccelArgs refuses what Raw Accel's validation refuses
                for (v in speeds) assertTrue(adjusted.curveAt(v).isFinite() && adjusted.curveAt(v) > 0, "$parameter $factor at $v in ${p.args.mode}")
            }
        }
    }

    @Test
    fun `noaccel and power have no stage`() {
        val power = profile(RawAccelArgs(RawAccelMode.POWER, gain = false, scale = 1.2, exponentPower = 0.3))
        for (p in listOf(noAccel, power)) {
            assertTrue(p.refineStages().isEmpty())
            assertFailsWith<IllegalArgumentException> { p.adjusted(RefineParameter.HIGH_GAIN, 1.1) }
        }
    }

    @Test
    fun `a velocity lut scales the rise above its first multiplier and keeps the first point`() {
        val adjusted = lut.adjusted(RefineParameter.HIGH_GAIN, 1.2)
        assertEquals(listOf(2f, 0.9f), adjusted.args.lut.take(2))
        assertEquals(0.9f + (1.0f - 0.9f) * 1.2f, adjusted.args.lut[3], 1e-6f)
        assertEquals(0.9f + (1.6f - 0.9f) * 1.2f, adjusted.args.lut[5], 1e-6f)
        assertEquals(lut.curveAt(1e-6), adjusted.curveAt(1e-6))
        assertEquals(listOf(2f, 5f, 30f), adjusted.args.lut.filterIndexed { i, _ -> i % 2 == 0 })
    }

    @Test
    fun `a gain lut keeps y over x as the multiplier it scales`() {
        val gainLut = profile(RawAccelArgs(RawAccelMode.LUT, gain = true, lut = listOf(2f, 1.8f, 5f, 5.0f, 30f, 48f))) // multipliers 0.9, 1.0, 1.6
        val adjusted = gainLut.adjusted(RefineParameter.HIGH_GAIN, 1.2)
        assertEquals(gainLut.curveAt(1e-6), adjusted.curveAt(1e-6))
        assertEquals(0.9 + 0.7 * 1.2, adjusted.curveAt(30.0), 1e-5)
        val stretched = gainLut.adjusted(RefineParameter.TRANSITION, 2.0)
        assertEquals(listOf(2f, 8f, 58f), stretched.args.lut.filterIndexed { i, _ -> i % 2 == 0 })
        assertEquals(gainLut.curveAt(30.0), stretched.curveAt(58.0), 1e-5, "the multiplier at the stretched speed is the old one")
        assertEquals(gainLut.curveAt(1e-6), stretched.curveAt(1e-6))
    }

    @Test
    fun `a velocity lut transition stretches the speed axis from the first point`() {
        val stretched = lut.adjusted(RefineParameter.TRANSITION, 0.5)
        assertEquals(listOf(2f, 3.5f, 16f), stretched.args.lut.filterIndexed { i, _ -> i % 2 == 0 })
        assertEquals(lut.args.lut.filterIndexed { i, _ -> i % 2 == 1 }, stretched.args.lut.filterIndexed { i, _ -> i % 2 == 1 })
        assertEquals(lut.curveAt(30.0), stretched.curveAt(16.0), 1e-6)
    }

    @Test
    fun `a flat lut has nothing to refine`() {
        assertTrue(profile(RawAccelArgs(RawAccelMode.LUT, gain = false, lut = listOf(2f, 1f, 5f, 1f))).refineStages().isEmpty())
    }

    @Test
    fun `jump scales the step height of a hard step and moves the step position without turning it smooth`() {
        val hard = profile(RawAccelArgs(RawAccelMode.JUMP, gain = false, capX = 10.0, capY = 2.0, smooth = 0.05))
        assertEquals(1.0 + 1.0 * 1.3, hard.adjusted(RefineParameter.HIGH_GAIN, 1.3).args.capY, 1e-12)
        assertEquals(15.0, hard.adjusted(RefineParameter.TRANSITION, 1.5).args.capX, 1e-12)
        assertTrue(hard.adjusted(RefineParameter.TRANSITION, 2.0).args.capX < 20.0, "stays below 1 / smooth = 20, so the step stays hard")
        val smooth = profile(RawAccelArgs(RawAccelMode.JUMP, gain = false, capX = 10.0, capY = 2.0, smooth = 0.5))
        assertFalse(smooth.supports(RefineParameter.HIGH_GAIN), "a smooth step has a low-speed multiplier the height would move")
        assertTrue(smooth.supports(RefineParameter.TRANSITION))
        assertTrue(smooth.adjusted(RefineParameter.TRANSITION, 0.5).args.capX >= 2.0, "never below 1 / smooth")
        assertEquals(hard.curveAt(1e-6), hard.adjusted(RefineParameter.HIGH_GAIN, 1.3).curveAt(1e-6))
    }

    @Test
    fun `synchronous moves the sync speed only, and only while the low-speed floor holds`() {
        val sync = profile(RawAccelArgs(RawAccelMode.SYNCHRONOUS, gain = false, syncSpeed = 20.0, smooth = 0.01))
        assertEquals(listOf(RefineParameter.TRANSITION), sync.refineStages())
        val adjusted = sync.adjusted(RefineParameter.TRANSITION, 1.5)
        assertEquals(30.0, adjusted.args.syncSpeed, 1e-12)
        assertEquals(sync.args.motivity, adjusted.args.motivity)
        assertEquals(sync.curveAt(1e-6), adjusted.curveAt(1e-6))
        // a soft sigmoid is not saturated far below the sync speed, so moving the sync speed would move the floor
        assertFalse(profile(RawAccelArgs(RawAccelMode.SYNCHRONOUS, gain = false, syncSpeed = 5.0, smooth = 1.0, gamma = 0.3)).supports(RefineParameter.TRANSITION))
    }

    @Test
    fun `by component applies the factor to both axes and needs both to support it`() {
        val y = RawAccelArgs(RawAccelMode.NATURAL, gain = false, decayRate = 0.15, limit = 2.0, inputOffset = 2.0)
        val byComponent = user.copy(accelY = y, speed = RawAccelSpeedArgs(whole = false))
        val adjusted = byComponent.adjusted(RefineParameter.HIGH_GAIN, 1.25)
        assertEquals(1.0 + 0.6 * 1.25, adjusted.args.capY, 1e-12)
        assertEquals(1.0 + 1.0 * 1.25, adjusted.accelY.limit, 1e-12)
        assertFalse(byComponent.copy(accelY = RawAccelArgs(RawAccelMode.NOACCEL)).supports(RefineParameter.HIGH_GAIN))
        // a whole profile never touches the vertical block
        assertEquals(user.accelY, user.adjusted(RefineParameter.HIGH_GAIN, 1.25).accelY)
    }

    @Test
    fun `adjusting changes shape fields only`() {
        for (p in all) for (stage in p.refineStages()) {
            assertEquals(RefineShape.base(p), RefineShape.base(p.adjusted(stage, 1.2)), "$stage ${p.args.mode}")
        }
    }

    @Test
    fun `high gain scales the capped output of a classic profile`() {
        val adjusted = user.adjusted(RefineParameter.HIGH_GAIN, 1.25)
        assertEquals(1.0 + 0.6 * 1.25, adjusted.args.capY, 1e-12)
        assertEquals(user.args.capX, adjusted.args.capX)
        // The speed the cap sits at, and everything above it, carries the scaled gain; below the offset nothing changes.
        assertEquals(1.0, adjusted.curveAt(5.0), 1e-12)
        assertTrue(adjusted.curveAt(300.0) > user.curveAt(300.0))
        val falling = classicOut.copy(args = classicOut.args.copy(capY = 0.7))
        assertEquals(1.0 - 0.3 * 0.75, falling.adjusted(RefineParameter.HIGH_GAIN, 0.75).args.capY, 1e-12)
    }

    @Test
    fun `high gain of a velocity classic curve with an output cap scales the plateau`() {
        val plateau = classicOut.curveAt(400.0) - 1.0
        assertEquals(plateau * 1.3, classicOut.adjusted(RefineParameter.HIGH_GAIN, 1.3).curveAt(400.0) - 1.0, 1e-12)
    }

    @Test
    fun `high gain without a cap output scales the acceleration so that the output at the cap scales`() {
        // input cap: the plateau is the output at capX, which is proportional to acceleration^(exponent - 1)
        val plateau = classicIn.curveAt(100.0) - 1.0
        assertEquals(plateau * 0.8, classicIn.adjusted(RefineParameter.HIGH_GAIN, 0.8).curveAt(100.0) - 1.0, 1e-9)
        assertTrue(classicUncapped.adjusted(RefineParameter.HIGH_GAIN, 1.2).args.acceleration > classicUncapped.args.acceleration)
    }

    @Test
    fun `high gain of a natural profile scales the limit and keeps the speed of the rise`() {
        val adjusted = naturalRising.adjusted(RefineParameter.HIGH_GAIN, 1.25)
        assertEquals(1.0 + 1.0 * 1.25, adjusted.args.limit, 1e-12)
        val before = naturalRising.args.decayRate / (naturalRising.args.limit - 1.0)
        assertEquals(before, adjusted.args.decayRate / (adjusted.args.limit - 1.0), 1e-12)
        // the same relative position on the way to the limit at every speed
        for (v in listOf(5.0, 10.0, 25.0)) {
            val share = (naturalRising.curveAt(v) - 1.0) / (naturalRising.args.limit - 1.0)
            assertEquals(share, (adjusted.curveAt(v) - 1.0) / (adjusted.args.limit - 1.0), 1e-9)
        }
        val falling = naturalFalling.adjusted(RefineParameter.HIGH_GAIN, 1.33)
        assertTrue(falling.args.limit < naturalFalling.args.limit && falling.args.limit > 0.0)
    }

    @Test
    fun `a limit or cap output scaled towards zero stays above the floor`() {
        val steep = profile(RawAccelArgs(RawAccelMode.NATURAL, limit = 0.1))
        assertTrue(steep.adjusted(RefineParameter.HIGH_GAIN, 1.33).args.limit >= 0.05)
        val capLow = profile(RawAccelArgs(RawAccelMode.CLASSIC, gain = true, inputOffset = 2.0, capX = 30.0, capY = 0.1, capMode = RawAccelCapMode.IN_OUT))
        assertTrue(capLow.adjusted(RefineParameter.HIGH_GAIN, 1.33).args.capY >= 0.05)
    }

    @Test
    fun `transition moves the offset of a classic curve and the cap point with it when it grows`() {
        val later = user.adjusted(RefineParameter.TRANSITION, 2.0)
        assertEquals(20.0, later.args.inputOffset, 1e-12)
        assertEquals(125.0, later.args.capX, 1e-12)
        val earlier = user.adjusted(RefineParameter.TRANSITION, 0.5)
        assertEquals(5.0, earlier.args.inputOffset, 1e-12)
        assertEquals(62.5, earlier.args.capX, 1e-12)
        assertTrue(earlier.curveAt(8.0) > user.curveAt(8.0), "an earlier start rises sooner")
        assertTrue(abs(later.curveAt(8.0) - 1.0) < 1e-12)
    }

    @Test
    fun `transition of a natural profile divides the decay rate`() {
        assertEquals(0.075, naturalRising.adjusted(RefineParameter.TRANSITION, 2.0).args.decayRate, 1e-12)
        assertEquals(0.3, naturalRising.adjusted(RefineParameter.TRANSITION, 0.5).args.decayRate, 1e-12)
        assertTrue(naturalRising.adjusted(RefineParameter.TRANSITION, 2.0).curveAt(10.0) < naturalRising.curveAt(10.0), "a slower approach is lower at moderate speed")
    }

    @Test
    fun `a classic curve without an offset has no transition and an unchanged high gain is skipped`() {
        assertFalse(classicUncapped.supports(RefineParameter.TRANSITION))
        val flat = profile(RawAccelArgs(RawAccelMode.CLASSIC, gain = true, inputOffset = 2.0, capX = 30.0, capY = 1.0, capMode = RawAccelCapMode.IN_OUT))
        assertFalse(flat.supports(RefineParameter.HIGH_GAIN))
        assertFalse(profile(RawAccelArgs(RawAccelMode.NATURAL, limit = 1.0)).supports(RefineParameter.HIGH_GAIN))
    }

    @Test
    fun `several factors apply in the order of the parameters and skip unsupported ones`() {
        val adjusted = user.adjusted(mapOf(RefineParameter.TRANSITION to 2.0, RefineParameter.HIGH_GAIN to 1.2))
        assertEquals(600.0, adjusted.outputDpi, 1e-9)
        assertEquals(1.72, adjusted.args.capY, 1e-12)
        assertEquals(20.0, adjusted.args.inputOffset, 1e-12)
        assertSame(noAccel, noAccel.adjusted(mapOf(RefineParameter.HIGH_GAIN to 1.2)))
    }

    @Test
    fun `excluding the neutral value needs a real change and a range on one side of one`() {
        val p = RefineParameter.HIGH_GAIN
        assertTrue(p.excludesNeutral(1.1, 1.04, 1.16))
        assertTrue(p.excludesNeutral(0.9, 0.86, 0.96))
        assertFalse(p.excludesNeutral(1.1, 0.95, 1.16))
        assertFalse(p.excludesNeutral(1.01, 1.005, 1.02), "within the neutral band")
    }
}
