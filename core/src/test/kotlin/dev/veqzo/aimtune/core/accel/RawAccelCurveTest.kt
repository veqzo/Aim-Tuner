package dev.veqzo.aimtune.core.accel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RawAccelCurveTest {
    private val speeds = listOf(0.0, 1.0, 5.0, 10.0, 20.0, 62.5, 100.0, 400.0)

    /** x1, y1, ... of a table with speeds 2, 5, 10, 30 in in/s; y are multipliers (Velocity) or output speeds (Gain). */
    private val lutVelocity = listOf(2f, 0.9f, 5f, 1.0f, 10f, 1.3f, 30f, 1.6f)
    private val lutGain = listOf(2f, 1.8f, 5f, 5f, 10f, 13f, 30f, 48f)

    private fun case(name: String, args: RawAccelArgs, expected: List<Double>) = Triple(name, args, expected)

    /**
     * Expected values come from a line-by-line Python port of Raw Accel 1.7.1's accel-classic.hpp, accel-natural.hpp and
     * accel-lookup.hpp (written from the source, not from the Kotlin), at the speeds above: zero, below the offset, exactly at a
     * cap, and far above.
     */
    private val cases = listOf(
        case("user", RawAccelArgs(RawAccelMode.CLASSIC, gain = true, inputOffset = 10.0, capX = 62.5, capY = 1.6, capMode = RawAccelCapMode.IN_OUT),
            listOf(1.0, 1.0, 1.0, 1.0, 1.0285714285714285, 1.252, 1.3825, 1.545625)),
        case("gain in", RawAccelArgs(RawAccelMode.CLASSIC, gain = true, acceleration = 0.01, exponentClassic = 2.3, inputOffset = 5.0, capX = 40.0, capMode = RawAccelCapMode.INPUT),
            listOf(1.0, 1.0, 1.0, 1.0101772632884227, 1.0636766049685442, 1.3545503543617208, 1.4419107442692918, 1.5511112316537554)),
        case("gain out", RawAccelArgs(RawAccelMode.CLASSIC, gain = true, acceleration = 0.02, inputOffset = 3.0, capY = 1.8, capMode = RawAccelCapMode.OUTPUT),
            listOf(1.0, 1.0, 1.016, 1.098, 1.2890000000000001, 1.6336, 1.6960000000000002, 1.774)),
        case("gain out falling", RawAccelArgs(RawAccelMode.CLASSIC, gain = true, acceleration = 0.01, exponentClassic = 2.5, capY = 0.7, capMode = RawAccelCapMode.OUTPUT),
            listOf(1.0, 0.999, 0.988819660112501, 0.9683772233983162, 0.9105572809000084, 0.7700669669890056, 0.7437918543681284, 0.7109479635920322)),
        case("gain io falling", RawAccelArgs(RawAccelMode.CLASSIC, gain = true, inputOffset = 2.0, capX = 30.0, capY = 0.75, capMode = RawAccelCapMode.IN_OUT),
            listOf(1.0, 1.0, 0.9919642857142857, 0.9714285714285714, 0.9276785714285715, 0.8140000000000001, 0.79, 0.76)),
        case("gain uncapped", RawAccelArgs(RawAccelMode.CLASSIC, gain = true, acceleration = 0.004, capX = 0.0, capY = 0.0, capMode = RawAccelCapMode.OUTPUT),
            listOf(1.0, 1.004, 1.02, 1.04, 1.08, 1.25, 1.4, 2.6)),
        case("vel io", RawAccelArgs(RawAccelMode.CLASSIC, gain = false, exponentClassic = 2.2, inputOffset = 4.0, capX = 50.0, capY = 1.5, capMode = RawAccelCapMode.IN_OUT),
            listOf(1.0, 1.0, 1.0010987598328929, 1.0283012442717414, 1.1224353269058616, 1.5, 1.5, 1.5)),
        case("vel io falling", RawAccelArgs(RawAccelMode.CLASSIC, gain = false, capX = 40.0, capY = 0.8, capMode = RawAccelCapMode.IN_OUT),
            listOf(1.0, 0.995, 0.975, 0.95, 0.9, 0.8, 0.8, 0.8)),
        case("vel in", RawAccelArgs(RawAccelMode.CLASSIC, gain = false, acceleration = 0.03, inputOffset = 2.0, capX = 30.0, capMode = RawAccelCapMode.INPUT),
            listOf(1.0, 1.0, 1.054, 1.192, 1.486, 1.784, 1.784, 1.784)),
        case("vel out", RawAccelArgs(RawAccelMode.CLASSIC, gain = false, acceleration = 0.03, inputOffset = 1.0, capY = 1.5, capMode = RawAccelCapMode.OUTPUT),
            listOf(1.0, 1.0, 1.096, 1.2429999999999999, 1.5, 1.5, 1.5, 1.5)),
        case("vel out falling", RawAccelArgs(RawAccelMode.CLASSIC, gain = false, acceleration = 0.1, capY = 0.6, capMode = RawAccelCapMode.OUTPUT),
            listOf(1.0, 0.9, 0.6, 0.6, 0.6, 0.6, 0.6, 0.6)),
        case("nat gain", RawAccelArgs(RawAccelMode.NATURAL, gain = true, decayRate = 0.15, limit = 2.0),
            listOf(1.0, 1.0713865095003854, 1.2964887369880196, 1.4820867734322865, 1.6832623561226212, 1.8933423806117595, 1.9333333537268214, 1.9833333333333334)),
        case("nat gain offset", RawAccelArgs(RawAccelMode.NATURAL, gain = true, decayRate = 0.2, limit = 1.6, inputOffset = 4.0),
            listOf(1.0, 1.0, 1.0179512718065642, 1.2043603509825902, 1.3904345154994449, 1.53280000009787, 1.5580000000000003, 1.5895000000000001)),
        case("nat gain falling", RawAccelArgs(RawAccelMode.NATURAL, gain = true, decayRate = 0.1, limit = 0.6, inputOffset = 2.0),
            listOf(1.0, 1.0, 0.9288427031228752, 0.8183463546821419, 0.7191112802769406, 0.638399993089079, 0.6239999999996336, 0.6059999999999999)),
        case("nat vel", RawAccelArgs(RawAccelMode.NATURAL, gain = false, decayRate = 0.15, limit = 2.0),
            listOf(1.0, 1.1392920235749422, 1.5276334472589852, 1.77686983985157, 1.950212931632136, 1.9999151817647536, 1.9999996940976796, 2.0)),
        case("nat vel offset", RawAccelArgs(RawAccelMode.NATURAL, gain = false, decayRate = 0.2, limit = 1.6, inputOffset = 4.0),
            listOf(1.0, 1.0, 1.0340162427311452, 1.3112792980348194, 1.4776825840029608, 1.5615999980915327, 1.575999999999993, 1.594)),
        case("nat vel falling", RawAccelArgs(RawAccelMode.NATURAL, gain = false, decayRate = 0.1, limit = 0.6, inputOffset = 2.0),
            listOf(1.0, 1.0, 0.8733679726578435, 0.7233072906357161, 0.6439992387537672, 0.6128001045276796, 0.6080000000089758, 0.602)),
        case("lut vel", RawAccelArgs(RawAccelMode.LUT, gain = false, lut = lutVelocity),
            listOf(0.0, 0.8999999761581421, 1.0, 1.2999999523162842, 1.449999988079071, 2.0875001400709152, 2.650000274181366, 7.150001347064972)),
        case("lut gain", RawAccelArgs(RawAccelMode.LUT, gain = true, lut = lutGain),
            listOf(0.0, 0.8999999761581421, 1.0, 1.3, 1.525, 1.678, 1.705, 1.73875)),
        case("noaccel", RawAccelArgs(RawAccelMode.NOACCEL),
            listOf(1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0)),
    )

    @Test
    fun `every mode matches the reference port of the source at edge and ordinary speeds`() {
        for ((name, args, expected) in cases) {
            val curve = RawAccelCurve(args)
            speeds.zip(expected).forEach { (speed, value) -> assertEquals(value, curve.multiplier(speed), 1e-9, "$name at $speed in/s") }
        }
    }

    @Test
    fun `the user's profile reproduces the values read off Raw Accel`() {
        val curve = RawAccelCurve(user)
        val table = listOf(1.0, 1.0, 1.0286, 1.0762, 1.1286, 1.1829, 1.2520, 1.3825)
        listOf(1.0, 10.0, 20.0, 30.0, 40.0, 50.0, 62.5, 100.0).zip(table).forEach { (speed, value) ->
            assertEquals(value, curve.multiplier(speed), 1e-4, "at $speed in/s")
        }
        assertEquals(0.005714285714, assertNotNull(curve.derivedAcceleration), 1e-12)
    }

    @Test
    fun `the user's profile follows the closed form of its gain curve`() {
        // Gain G(v) = d/dv (v * (m(v) - 1)): 2a(v - 10) up to the cap point, constant 0.6 beyond it, with a = 0.6 / (2 * 52.5).
        val a = 0.6 / (2 * 52.5)
        val curve = RawAccelCurve(user)
        for (v in listOf(10.5, 20.0, 33.3, 50.0, 62.0)) {
            assertEquals(1 + a * (v - 10) * (v - 10) / v, curve.multiplier(v), 1e-9, "below the cap at $v")
        }
        val atCap = 1 + a * 52.5 * 52.5 / 62.5
        assertEquals(atCap, curve.multiplier(62.5), 1e-9)
        for (v in listOf(62.5, 80.0, 150.0, 900.0)) {
            // beyond the cap v * (m - 1) grows linearly with slope 0.6 from the value at the cap
            val expected = 1 + (62.5 * (atCap - 1) + 0.6 * (v - 62.5)) / v
            assertEquals(expected, curve.multiplier(v), 1e-9, "above the cap at $v")
        }
        // the gain is continuous at the cap: slopes of v * (m - 1) on both sides
        fun gain(v: Double) = (((v + 1e-6) * (curve.multiplier(v + 1e-6) - 1)) - ((v - 1e-6) * (curve.multiplier(v - 1e-6) - 1))) / 2e-6
        assertEquals(0.6, gain(62.5 - 1e-3), 1e-3)
        assertEquals(0.6, gain(62.5 + 1e-3), 1e-6)
    }

    @Test
    fun `the profile multiplies by output dpi over device dpi and treats a zero device dpi as 1000`() {
        val profile = RawAccelProfile("default", user, outputDpi = 600.0, deviceDpi = 800.0)
        assertEquals(1.3825 * 0.75, profile.totalMultiplier(100.0), 1e-4)
        assertEquals(0.75, profile.totalMultiplier(5.0), 1e-12)
        val unnormalized = RawAccelProfile("default", RawAccelArgs(RawAccelMode.NOACCEL), outputDpi = 500.0, deviceDpi = 0.0)
        assertEquals(0.5, unnormalized.totalMultiplier(5.0), 1e-12)
    }

    @Test
    fun `a gain lut is the output speed over the input speed and the table is extended beyond its last point`() {
        val gain = RawAccelCurve(RawAccelArgs(RawAccelMode.LUT, gain = true, lut = lutGain))
        assertEquals(0.0, gain.multiplier(0.0))
        assertEquals(1.8 / 2, gain.multiplier(2.0), 1e-6) // exactly the first point
        assertEquals(1.8 / 2, gain.multiplier(1.0), 1e-6) // below the first point: its y over its x
        assertEquals((13.0 + (48.0 - 13.0) * (15.0 - 10.0) / 20.0) / 15.0, gain.multiplier(15.0), 1e-6)
        // beyond 30 in/s the line through the last two points goes on: y(100) = 48 + 35 / 20 * 70 = 170.5
        assertEquals(170.5 / 100.0, gain.multiplier(100.0), 1e-9)
        val velocity = RawAccelCurve(RawAccelArgs(RawAccelMode.LUT, gain = false, lut = lutVelocity))
        assertEquals(1.6 + (1.6 - 1.3) / 20.0 * 70.0, velocity.multiplier(100.0), 1e-6)
    }

    @Test
    fun `classic caps of one and equal limits give a multiplier of one`() {
        val flat = RawAccelCurve(RawAccelArgs(RawAccelMode.CLASSIC, gain = true, acceleration = 0.05, capY = 1.0, capMode = RawAccelCapMode.OUTPUT))
        assertTrue(speeds.all { flat.multiplier(it) == 1.0 })
        val natural = RawAccelCurve(RawAccelArgs(RawAccelMode.NATURAL, gain = false, limit = 1.0))
        assertTrue(speeds.all { natural.multiplier(it) == 1.0 })
    }

    @Test
    fun `values raw accel would reject cannot be built`() {
        assertFailsWith<IllegalArgumentException> { RawAccelArgs(RawAccelMode.CLASSIC, acceleration = 0.0) }
        assertFailsWith<IllegalArgumentException> { RawAccelArgs(RawAccelMode.CLASSIC, exponentClassic = 1.0) }
        assertFailsWith<IllegalArgumentException> { RawAccelArgs(RawAccelMode.CLASSIC, inputOffset = -1.0) }
        assertFailsWith<IllegalArgumentException> { RawAccelArgs(RawAccelMode.CLASSIC, capMode = RawAccelCapMode.IN_OUT, capX = 0.0) }
        assertFailsWith<IllegalArgumentException> { RawAccelArgs(RawAccelMode.CLASSIC, inputOffset = 20.0, capX = 10.0, capMode = RawAccelCapMode.INPUT) }
        assertFailsWith<IllegalArgumentException> { RawAccelArgs(RawAccelMode.NATURAL, decayRate = 0.0) }
        assertFailsWith<IllegalArgumentException> { RawAccelArgs(RawAccelMode.LUT, lut = listOf(1f, 1f)) }
        assertFailsWith<IllegalArgumentException> { RawAccelArgs(RawAccelMode.LUT, lut = List(516) { 1f }) }
        assertFailsWith<IllegalArgumentException> { RawAccelProfile("x", user, outputDpi = 0.0, deviceDpi = 800.0) }
    }

    @Test
    fun `the spec text round-trips every case and the old forms still decode`() {
        for ((name, args, _) in cases) {
            val curve = RawAccelCurve(args)
            val text = AccelSpec.encode(curve)
            assertTrue(text.startsWith("rawaccel1;"), name)
            assertEquals(curve, AccelSpec.decode(text), name)
        }
        assertEquals(ClassicCurve(0.08, 2.0, 0.0, 0.0), AccelSpec.decode("classic;acceleration=0.08;exponent=2.0;inputOffset=0.0;cap=0.0"))
        assertFailsWith<IllegalArgumentException> { AccelSpec.decode("rawaccel1;mode=classic") }
        assertFailsWith<IllegalArgumentException> { AccelSpec.decode(AccelSpec.encode(RawAccelCurve(user)).replace("mode=classic", "mode=power")) }
    }

    private val user = RawAccelArgs(
        RawAccelMode.CLASSIC, gain = true, inputOffset = 10.0, capX = 62.5, capY = 1.6, capMode = RawAccelCapMode.IN_OUT,
    )
}
