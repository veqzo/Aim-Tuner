package dev.veqzo.aimtune.core.accel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Validation of `rawaccel-validate.hpp` and the lossless `rawaccel2` text form. */
class RawAccelProfileTest {
    private fun bad(message: String, build: () -> Unit) {
        val e = assertFailsWith<IllegalArgumentException>(message) { build() }
        assertTrue(e.message!!.isNotEmpty())
    }

    private val noaccel = RawAccelArgs(RawAccelMode.NOACCEL)

    private fun profile(
        speed: RawAccelSpeedArgs = RawAccelSpeedArgs(),
        domain: RawAccelXY = RawAccelXY.ONE,
        range: RawAccelXY = RawAccelXY.ONE,
        yx: Double = 1.0,
        lr: Double = 1.0,
        ud: Double = 1.0,
        snap: Double = 0.0,
        cap: Double = 0.0,
    ) = RawAccelProfile("p", noaccel, 800.0, 800.0, noaccel, speed, domain, range, yx, lr, ud, 0.0, snap, cap)

    @Test
    fun `accel args refuse what rawaccel-validate refuses`() {
        bad("negative output offset") { RawAccelArgs(RawAccelMode.POWER, outputOffset = -0.1) }
        bad("jump cap x 0") { RawAccelArgs(RawAccelMode.JUMP, capX = 0.0) }
        bad("jump cap y 0") { RawAccelArgs(RawAccelMode.JUMP, capY = 0.0) }
        bad("power io cap 0") { RawAccelArgs(RawAccelMode.POWER, capMode = RawAccelCapMode.IN_OUT, capX = 0.0) }
        bad("power io cap y 0") { RawAccelArgs(RawAccelMode.POWER, capMode = RawAccelCapMode.IN_OUT, capY = 0.0) }
        bad("power cap below offset") { RawAccelArgs(RawAccelMode.POWER, capMode = RawAccelCapMode.OUTPUT, capY = 0.5, outputOffset = 0.8) }
        bad("scale") { RawAccelArgs(RawAccelMode.POWER, scale = 0.0) }
        bad("gamma") { RawAccelArgs(RawAccelMode.SYNCHRONOUS, gamma = 0.0) }
        bad("motivity") { RawAccelArgs(RawAccelMode.SYNCHRONOUS, motivity = 1.0) }
        bad("exponent power") { RawAccelArgs(RawAccelMode.POWER, exponentPower = 0.0) }
        bad("sync speed") { RawAccelArgs(RawAccelMode.SYNCHRONOUS, syncSpeed = 0.0) }
        bad("smooth high") { RawAccelArgs(RawAccelMode.JUMP, smooth = 1.01) }
        bad("smooth low") { RawAccelArgs(RawAccelMode.JUMP, smooth = -0.01) }
        bad("lut too short") { RawAccelArgs(RawAccelMode.LUT, lut = listOf(1f, 1f)) }
        // allowed: power cap below the offset in input cap mode, classic without a cap
        RawAccelArgs(RawAccelMode.POWER, capMode = RawAccelCapMode.INPUT, capY = 0.5, outputOffset = 0.8)
        RawAccelArgs(RawAccelMode.JUMP, smooth = 0.0)
        RawAccelArgs(RawAccelMode.SYNCHRONOUS, smooth = 0.0)
    }

    @Test
    fun `profile and device checks`() {
        bad("speed cap") { profile(cap = -1.0) }
        bad("snap high") { profile(snap = 45.5) }
        bad("snap low") { profile(snap = -1.0) }
        bad("yx 0") { profile(yx = 0.0) }
        bad("domain") { profile(domain = RawAccelXY(0.0, 1.0)) }
        bad("lr") { profile(lr = 0.0) }
        bad("ud") { profile(ud = -1.0) }
        bad("lp") { profile(speed = RawAccelSpeedArgs(lpNorm = 0.0)) }
        bad("range") { profile(range = RawAccelXY(-0.1, 1.0)) }
        bad("polling") { RawAccelDevice(pollingRate = -1) }
        bad("time min") { RawAccelDevice(timeMin = 0.0) }
        bad("time max") { RawAccelDevice(timeMin = 2.0, timeMax = 1.0) }
        profile(snap = 45.0, yx = -1.0, range = RawAccelXY(0.0, 0.0), cap = 10.0)
    }

    private val everything = RawAccelProfile(
        name = "Wild; name=with ä = signs, and spaces",
        args = RawAccelArgs(
            RawAccelMode.LUT, gain = false, acceleration = 0.0123, exponentClassic = 2.7, inputOffset = 3.5, decayRate = 0.31,
            limit = 2.25, capX = 41.5, capY = 1.75, capMode = RawAccelCapMode.INPUT, lut = listOf(1f, 0.9f, 4.5f, 1.1f, 9f, 1.35f),
            outputOffset = 0.4, gamma = 1.7, motivity = 2.4, scale = 0.33, exponentPower = 0.27, syncSpeed = 17.5, smooth = 0.25,
        ),
        outputDpi = 1234.5,
        deviceDpi = 777.0,
        accelY = RawAccelArgs(
            RawAccelMode.SYNCHRONOUS, gain = true, acceleration = 0.02, exponentClassic = 3.1, inputOffset = 1.5, decayRate = 0.2,
            limit = 0.8, capX = 33.3, capY = 0.9, capMode = RawAccelCapMode.IN_OUT, outputOffset = 0.15, gamma = 0.6, motivity = 3.3,
            scale = 1.9, exponentPower = 0.7, syncSpeed = 9.5, smooth = 0.75,
        ),
        speed = RawAccelSpeedArgs(whole = false, lpNorm = 3.5, inputHalfLife = 1.1, scaleHalfLife = 2.2, outputHalfLife = 3.3),
        domain = RawAccelXY(1.25, 0.75),
        range = RawAccelXY(0.9, 1.15),
        yxRatio = 1.2,
        lrRatio = 0.85,
        udRatio = 1.4,
        rotationDegrees = -12.5,
        snapDegrees = 6.5,
        speedCap = 87.5,
        device = RawAccelDevice(pollingRate = 4000, pollTimeLock = true, timeMin = 0.1, timeMax = 55.5),
    )

    @Test
    fun `rawaccel2 round trips a profile with every field changed`() {
        val text = AccelSpec.encodeProfile(everything)
        assertTrue(text.startsWith("rawaccel2;"))
        assertEquals(everything, AccelSpec.decodeProfile(text))
        assertEquals(text, AccelSpec.encodeProfile(AccelSpec.decodeProfile(text)))
        assertEquals(everything.curve, AccelSpec.decode(text))
    }

    @Test
    fun `rawaccel2 round trips the defaults and every new mode`() {
        val defaults = RawAccelProfile("default", RawAccelArgs(RawAccelMode.NOACCEL), 1000.0, 0.0)
        assertEquals(defaults, AccelSpec.decodeProfile(AccelSpec.encodeProfile(defaults)))
        for (mode in RawAccelMode.entries.filter { it != RawAccelMode.LUT }) {
            val p = RawAccelProfile("m", RawAccelArgs(mode, gain = false), 600.0, 800.0)
            assertEquals(p, AccelSpec.decodeProfile(AccelSpec.encodeProfile(p)))
        }
    }

    @Test
    fun `rawaccel2 rejects broken text and rawaccel1 still decodes`() {
        val text = AccelSpec.encodeProfile(everything)
        assertFailsWith<IllegalArgumentException> { AccelSpec.decodeProfile(text.replace(";whole=false", "")) }
        assertFailsWith<IllegalArgumentException> { AccelSpec.decodeProfile(text.replace("x.mode=lut", "x.mode=bogus")) }
        assertFailsWith<IllegalArgumentException> { AccelSpec.decodeProfile(text.replace("snapDegrees=6.5", "snapDegrees=90.0")) }
        assertFailsWith<IllegalArgumentException> { AccelSpec.decodeProfile("rawaccel1;mode=noaccel") }
        val v1 = RawAccelCurve(RawAccelArgs(RawAccelMode.CLASSIC, inputOffset = 2.0, capMode = RawAccelCapMode.INPUT, capX = 20.0))
        assertEquals(v1, AccelSpec.decode(AccelSpec.encode(v1)))
        assertTrue(AccelSpec.encode(v1).startsWith("rawaccel1;"))
    }
}
