package dev.veqzo.aimtune.core.accel

import dev.veqzo.aimtune.core.input.MouseEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AccelCurveTest {
    private val speeds = listOf(0.5, 2.0, 5.0, 10.0, 30.0)

    /** Expected values were computed from the formulas in Raw Accel 1.7.1's accel-*.hpp (Velocity / LEGACY form). */
    private fun check(curve: AccelCurve, expected: List<Double>) {
        speeds.zip(expected).forEach { (speed, value) -> assertEquals(value, curve.multiplier(speed), 1e-8, "$curve at $speed") }
    }

    @Test
    fun `classic follows the raw accel formula with and without a cap or offset`() {
        check(ClassicCurve(0.08), listOf(1.04, 1.16, 1.4, 1.8, 3.4))
        check(ClassicCurve(0.05, exponent = 2.5, inputOffset = 3.0, cap = 2.0), listOf(1.0, 1.0, 1.012649111, 1.144943955, 2.0))
    }

    @Test
    fun `classic with a cap below one falls from one toward the cap`() {
        check(ClassicCurve(0.1, cap = 0.6), listOf(0.95, 0.8, 0.6, 0.6, 0.6))
    }

    @Test
    fun `natural levels off at its limit, rising or falling`() {
        check(NaturalCurve(0.15, 2.0), listOf(1.072256514, 1.259181779, 1.527633447, 1.77686984, 1.988891003))
        check(NaturalCurve(0.2, 1.6, inputOffset = 4.0), listOf(1.0, 1.0, 1.034016243, 1.311279298, 1.519910439))
        check(NaturalCurve(0.1, 0.6), listOf(0.952998761, 0.842612264, 0.714601919, 0.632833999, 0.600221234))
    }

    @Test
    fun `power starts at its output offset and can be capped`() {
        check(PowerCurve(0.1, 0.5), listOf(1.0, 1.0, 1.003403077, 1.148148148, 1.781433524))
        check(PowerCurve(0.2, 0.3, cap = 1.4), listOf(1.0, 1.0, 1.096242463, 1.279265645, 1.4))
        check(PowerCurve(0.05, 0.4, outputOffset = 0.0), listOf(0.228652526, 0.398107171, 0.574349177, 0.757858283, 1.176079023))
    }

    @Test
    fun `no acceleration changes nothing`() {
        assertTrue(speeds.all { NoAccel.multiplier(it) == 1.0 })
    }

    @Test
    fun `parameters outside the ranges raw accel accepts are rejected`() {
        assertFailsWith<IllegalArgumentException> { ClassicCurve(0.0) }
        assertFailsWith<IllegalArgumentException> { ClassicCurve(0.1, exponent = 1.0) }
        assertFailsWith<IllegalArgumentException> { ClassicCurve(0.1, inputOffset = -1.0) }
        assertFailsWith<IllegalArgumentException> { NaturalCurve(0.0, 2.0) }
        assertFailsWith<IllegalArgumentException> { NaturalCurve(0.1, 1.0) }
        assertFailsWith<IllegalArgumentException> { NaturalCurve(0.1, 0.0) }
        assertFailsWith<IllegalArgumentException> { PowerCurve(0.0, 0.5) }
        assertFailsWith<IllegalArgumentException> { PowerCurve(0.1, 0.0) }
        assertFailsWith<IllegalArgumentException> { PowerCurve(0.1, 0.5, outputOffset = 2.0, cap = 1.5) }
    }
}

class AccelProcessorTest {
    private val ms = 1_000_000L

    @Test
    fun `one count per ms at 1000 dpi is one inch per second`() {
        val processor = AccelProcessor(NoAccel, dpi = 1000.0)
        processor.speedOf(MouseEvent(0, 1, 0))
        assertEquals(1.0, processor.speedOf(MouseEvent(ms, 1, 0)), 1e-9)
        assertEquals(5.0, processor.speedOf(MouseEvent(2 * ms, 3, 4)), 1e-9, "3-4-5 counts over 1 ms")
    }

    @Test
    fun `the dpi normalizes the speed`() {
        val processor = AccelProcessor(NoAccel, dpi = 800.0)
        processor.speedOf(MouseEvent(0, 1, 0))
        assertEquals(1.25, processor.speedOf(MouseEvent(ms, 1, 0)), 1e-9)
    }

    @Test
    fun `the time between reports is clamped like the driver does`() {
        val processor = AccelProcessor(NoAccel, dpi = 1000.0)
        // First report: the longest time (100 ms), so 10 counts are 0.1 counts/ms.
        assertEquals(0.1, processor.speedOf(MouseEvent(0, 10, 0)), 1e-9)
        // 1 s later: still the longest time.
        assertEquals(0.1, processor.speedOf(MouseEvent(1_000 * ms, 10, 0)), 1e-9)
        // Faster than the shortest time (0.0625 ms): clamped, so 1 count is 16 counts/ms.
        assertEquals(16.0, processor.speedOf(MouseEvent(1_000 * ms + 1_000, 1, 0)), 1e-9)
    }

    @Test
    fun `the scale is the curve's value at the report speed`() {
        val curve = ClassicCurve(0.08)
        val processor = AccelProcessor(curve, dpi = 1000.0)
        processor.scaleFor(MouseEvent(0, 1, 0))
        assertEquals(curve.multiplier(10.0), processor.scaleFor(MouseEvent(ms, 10, 0)), 1e-12)
        assertEquals(AccelProcessor.speedInPerSecond(2.0, 1600.0), 1.25, 1e-12)
    }
}

class AccelSpecTest {
    @Test
    fun `every curve survives a round trip through its text form`() {
        val curves = listOf(
            NoAccel,
            ClassicCurve(0.0831234567, exponent = 2.25, inputOffset = 1.5, cap = 1.8),
            NaturalCurve(0.123456789, 0.7, inputOffset = 2.0),
            PowerCurve(0.0421, 0.35, outputOffset = 1.0, cap = 1.6),
        )
        curves.forEach { assertEquals(it, AccelSpec.decode(AccelSpec.encode(it))) }
    }

    @Test
    fun `bad text is rejected with a reason`() {
        assertFailsWith<IllegalArgumentException> { AccelSpec.decode("jump;a=1") }
        assertFailsWith<IllegalArgumentException> { AccelSpec.decode("classic;acceleration=0.1") }
        assertFailsWith<IllegalArgumentException> { AccelSpec.decode("classic;acceleration=x;exponent=2;inputOffset=0;cap=0") }
        assertFailsWith<IllegalArgumentException> { AccelSpec.decode("classic;acceleration=-1;exponent=2;inputOffset=0;cap=0") }
    }

    private val lut = LookupCurve(listOf(2.0, 4.0, 8.0), listOf(1.0, 1.5, 1.25))

    @Test
    fun `lookup returns the exact multiplier at its points`() {
        assertEquals(1.0, lut.multiplier(2.0))
        assertEquals(1.5, lut.multiplier(4.0))
        assertEquals(1.25, lut.multiplier(8.0))
    }

    @Test
    fun `lookup interpolates linearly between points`() {
        assertEquals(1.25, lut.multiplier(3.0), 1e-12)
        assertEquals(1.375, lut.multiplier(6.0), 1e-12)
    }

    @Test
    fun `lookup is flat below the first and above the last point`() {
        assertEquals(1.0, lut.multiplier(0.1))
        assertEquals(1.25, lut.multiplier(100.0))
    }

    @Test
    fun `lookup rounds its points to float`() {
        val curve = LookupCurve(listOf(1.0, 2.0), listOf(1.1, 1.2))
        assertEquals(1.100000023841858, curve.multipliers[0])
        assertEquals(1.100000023841858, curve.multiplier(1.0))
    }

    @Test
    fun `lookup rejects invalid tables`() {
        assertFailsWith<IllegalArgumentException> { LookupCurve(listOf(1.0), listOf(1.0)) }
        val many = List(258) { 1.0 + it }
        assertFailsWith<IllegalArgumentException> { LookupCurve(many, many) }
        assertEquals(257, LookupCurve(many.take(257), many.take(257)).speeds.size)
        assertFailsWith<IllegalArgumentException> { LookupCurve(listOf(1.0, 1.0), listOf(1.0, 1.0)) }
        assertFailsWith<IllegalArgumentException> { LookupCurve(listOf(2.0, 1.0), listOf(1.0, 1.0)) }
        assertFailsWith<IllegalArgumentException> { LookupCurve(listOf(0.0, 1.0), listOf(1.0, 1.0)) }
        assertFailsWith<IllegalArgumentException> { LookupCurve(listOf(-1.0, 1.0), listOf(1.0, 1.0)) }
        assertFailsWith<IllegalArgumentException> { LookupCurve(listOf(1.0, 2.0), listOf(1.0, 0.0)) }
        assertFailsWith<IllegalArgumentException> { LookupCurve(listOf(1.0, 2.0), listOf(1.0, -0.5)) }
    }

    @Test
    fun `a lookup curve survives the spec text round trip`() {
        val curve = LookupCurve(listOf(1.5, 4.25, 9.0, 20.0), listOf(0.9, 1.1, 1.3, 1.31))
        assertEquals(curve, AccelSpec.decode(AccelSpec.encode(curve)))
        assertTrue(AccelSpec.encode(curve).startsWith("lut;points="))
    }

    @Test
    fun `malformed lookup specs are rejected`() {
        listOf(
            "lut",
            "lut;points=",
            "lut;points=1:1",
            "lut;points=1:1,2",
            "lut;points=1:1,2:x",
            "lut;points=a:1,2:1",
            "lut;points=2:1,1:1",
            "lut;points=1:1,2:-1",
            "lut;speeds=1:1,2:1",
            "lut;points=1:1:1,2:1",
        ).forEach { spec -> assertFailsWith<IllegalArgumentException>(spec) { AccelSpec.decode(spec) } }
    }
}
