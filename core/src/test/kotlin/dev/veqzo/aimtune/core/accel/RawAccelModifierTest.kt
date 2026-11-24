package dev.veqzo.aimtune.core.accel

import dev.veqzo.aimtune.core.input.MouseEvent
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Runs fixed report streams through [RawAccelModifier] and through the literal test-side port [Ref] of the Raw Accel 1.7.1
 * sources and compares the continuous output (1e-9, relative above 1) and the integer output (exactly, carry included).
 */
class RawAccelModifierTest {
    private val ms = 1_000_000L

    private class Lcg(var state: Long) {
        fun next(bound: Int): Int {
            state = state * 6364136223846793005L + 1442695040888963407L
            return ((state ushr 33) % bound).toInt()
        }
    }

    /** The report streams of the plan: horizontal, diagonal, near-axis, left/up, pauses, 8 kHz bursts, zero reports, flicks. */
    private fun stream(): List<MouseEvent> {
        val rng = Lcg(42)
        val events = mutableListOf<MouseEvent>()
        var t = 5_000_000_000L
        fun add(dtNanos: Long, dx: Int, dy: Int) {
            t += dtNanos
            events += MouseEvent(t, dx, dy)
        }
        for (k in 1..120) add(ms, k / 2 + 1, 0) // pure horizontal, growing speed
        for (k in 1..80) add(ms, k, (k * 7) / 10) // diagonal
        for (k in 1..60) add(ms, 40, 1 + k % 3) // near the horizontal axis, inside a 5 degree snap
        for (k in 1..60) add(ms, 1 + k % 2, 40) // near the vertical axis
        for (k in 1..80) add(ms, -(rng.next(30) + 1), if (k % 3 == 0) -(rng.next(20) + 1) else rng.next(20)) // left and up
        for (k in 1..40) add(ms, rng.next(25), -(rng.next(25) + 1))
        add(250 * ms, 12, 5) // pause above 100 ms
        for (k in 1..20) add(ms, 9, -4)
        add(130 * ms, -20, 0)
        for (k in 1..400) add(125_000L, rng.next(7) - 3, rng.next(7) - 3) // 8 kHz burst with zero reports in it
        for (k in 1..60) add(ms + rng.next(300_000), rng.next(60) - 30, rng.next(60) - 30) // jittery 1 kHz
        add(ms, 300, 40) // flick
        add(ms, -280, 0)
        add(2 * ms, 0, 0)
        add(ms, 5, 5)
        return events
    }

    private fun near(expected: Double, actual: Double, tolerance: Double, what: String) {
        val bound = tolerance * max(1.0, abs(expected))
        assertTrue(abs(expected - actual) <= bound, "$what: expected $expected, was $actual")
    }

    /** Compares [RawAccelModifier] with [Ref.Driver] on [events]; returns the integer outputs. */
    private fun compare(profile: RawAccelProfile, events: List<MouseEvent> = stream(), tolerance: Double = 1e-9): List<MouseEvent> {
        val continuous = RawAccelModifier(profile)
        val integer = RawAccelModifier(profile)
        val ref = Ref.Driver(profile)
        val out = mutableListOf<MouseEvent>()
        for ((i, e) in events.withIndex()) {
            val expected = ref.report(e.timeNanos, e.dx, e.dy)
            val c = continuous.modifyContinuous(e)
            near(expected.continuous?.x ?: 0.0, c.x, tolerance, "event $i x (${profile.name})")
            near(expected.continuous?.y ?: 0.0, c.y, tolerance, "event $i y (${profile.name})")
            val actual = integer.modify(e)
            assertEquals(expected.x, actual.dx, "event $i dx (${profile.name})")
            assertEquals(expected.y, actual.dy, "event $i dy (${profile.name})")
            assertEquals(e.timeNanos, actual.timeNanos)
            out += actual
        }
        return out
    }

    private fun profile(
        args: RawAccelArgs,
        name: String = "p",
        accelY: RawAccelArgs = args,
        speed: RawAccelSpeedArgs = RawAccelSpeedArgs(),
        outputDpi: Double = 800.0,
        deviceDpi: Double = 800.0,
        domain: RawAccelXY = RawAccelXY.ONE,
        range: RawAccelXY = RawAccelXY.ONE,
        yx: Double = 1.0,
        lr: Double = 1.0,
        ud: Double = 1.0,
        rotation: Double = 0.0,
        snap: Double = 0.0,
        cap: Double = 0.0,
        device: RawAccelDevice = RawAccelDevice(),
    ) = RawAccelProfile(name, args, outputDpi, deviceDpi, accelY, speed, domain, range, yx, lr, ud, rotation, snap, cap, device)

    private val classic = RawAccelArgs(RawAccelMode.CLASSIC, gain = true, acceleration = 0.012, inputOffset = 4.0, capX = 60.0, capY = 2.0, capMode = RawAccelCapMode.OUTPUT)
    private val powerGain = RawAccelArgs(RawAccelMode.POWER, gain = true, scale = 0.2, exponentPower = 0.4, outputOffset = 0.3, capMode = RawAccelCapMode.OUTPUT, capY = 3.0)
    private val jumpSmooth = RawAccelArgs(RawAccelMode.JUMP, gain = false, capX = 30.0, capY = 1.8, smooth = 0.5)
    private val syncGain = RawAccelArgs(RawAccelMode.SYNCHRONOUS, gain = true, gamma = 1.4, motivity = 2.0, syncSpeed = 20.0, smooth = 0.5)

    // ---- the user's real profile end to end ----

    private val user = RawAccelProfile(
        "default",
        RawAccelArgs(RawAccelMode.CLASSIC, gain = true, inputOffset = 10.0, capX = 62.5, capY = 1.6, capMode = RawAccelCapMode.IN_OUT),
        outputDpi = 600.0,
        deviceDpi = 800.0,
    )

    @Test
    fun `the user profile matches the port on every stream`() {
        compare(user)
    }

    @Test
    fun `the user profile scales a steady 1 kHz horizontal move by the curve and the DPI ratio`() {
        // 10 counts per ms at 800 DPI is 12.5 in/s; from the existing RawAccelCurveTest the curve is 1.0 below the offset of 10
        // and 1.0285714 at 20 in/s, so 25 counts per ms (31.25 in/s) is well in the rising part.
        val events = (1..2000).map { MouseEvent(it * ms, 25, 0) }
        val out = compare(user, events)
        val total = out.sumOf { it.dx }
        // the first report has no predecessor and sees 100 ms, so the curve is 1 there
        val expected = (1999 * RawAccelCurve(user.args).multiplier(31.25) + 1) * 25 * 600.0 / 800.0
        assertTrue(abs(total - expected) <= 1.0, "total $total vs $expected")
        assertTrue(out.all { it.dy == 0 })
    }

    @Test
    fun `no acceleration and equal DPI pass every count through unchanged`() {
        val flat = RawAccelProfile("flat", RawAccelArgs(RawAccelMode.NOACCEL), 800.0, 800.0)
        val events = stream()
        val out = compare(flat, events)
        assertEquals(events.map { it.dx to it.dy }, out.map { it.dx to it.dy })
    }

    // ---- every mode and cap mode ----

    @Test
    fun `power modes match the port`() {
        for (gain in listOf(true, false)) for (capMode in RawAccelCapMode.entries) {
            val args = RawAccelArgs(
                RawAccelMode.POWER, gain = gain, scale = 0.15, exponentPower = 0.35, outputOffset = 0.25,
                capX = 45.0, capY = 2.5, capMode = capMode,
            )
            compare(profile(args, "power ${if (gain) "gain" else "legacy"} ${capMode.json}"))
        }
        compare(profile(RawAccelArgs(RawAccelMode.POWER, gain = true, scale = 0.5, exponentPower = 0.3, outputOffset = 0.0, capMode = RawAccelCapMode.OUTPUT, capY = 0.0), "power uncapped"))
        // the input cap lies at or below the offset in input terms (accel-power.hpp:119-123)
        compare(profile(RawAccelArgs(RawAccelMode.POWER, gain = true, scale = 0.5, exponentPower = 0.3, outputOffset = 0.9, capMode = RawAccelCapMode.INPUT, capX = 0.01), "power cap in below offset"))
    }

    @Test
    fun `power curve values match the port on a speed grid`() {
        val speeds = listOf(0.0, 0.01, 0.5, 1.0, 5.0, 10.0, 20.0, 45.0, 62.5, 100.0, 400.0, 5000.0)
        for (gain in listOf(true, false)) for (capMode in RawAccelCapMode.entries) for (offset in listOf(0.0, 0.25)) {
            val args = RawAccelArgs(
                RawAccelMode.POWER, gain = gain, scale = 0.15, exponentPower = 0.35, outputOffset = offset,
                capX = 45.0, capY = 2.5, capMode = capMode,
            )
            val ref = Ref.makeFn(args)
            val curve = RawAccelCurve(args)
            for (s in speeds) near(ref(s), curve.multiplier(s), 1e-9, "power $gain ${capMode.json} offset $offset at $s")
        }
    }

    @Test
    fun `jump modes match the port`() {
        for (gain in listOf(true, false)) for (smooth in listOf(0.0, 0.3, 1.0)) {
            val args = RawAccelArgs(RawAccelMode.JUMP, gain = gain, capX = 25.0, capY = 1.7, smooth = smooth)
            compare(profile(args, "jump ${if (gain) "gain" else "legacy"} smooth $smooth"))
            val ref = Ref.makeFn(args)
            val curve = RawAccelCurve(args)
            for (s in listOf(0.0, 0.5, 5.0, 24.9, 25.0, 25.1, 60.0, 1000.0)) near(ref(s), curve.multiplier(s), 1e-9, "jump $gain $smooth at $s")
        }
        // a step shorter than 1 / smooth has no smoothing (accel-jump.hpp:19-21)
        compare(profile(RawAccelArgs(RawAccelMode.JUMP, gain = false, capX = 1.5, capY = 0.6, smooth = 0.4), "jump falling"))
    }

    @Test
    fun `synchronous legacy matches the port`() {
        for (smooth in listOf(0.0, 0.2, 0.5, 1.0)) {
            val args = RawAccelArgs(RawAccelMode.SYNCHRONOUS, gain = false, gamma = 1.3, motivity = 2.2, syncSpeed = 18.0, smooth = smooth)
            compare(profile(args, "sync legacy smooth $smooth"))
            val ref = Ref.makeFn(args)
            val curve = RawAccelCurve(args)
            for (s in listOf(0.001, 0.5, 5.0, 17.9, 18.0, 18.1, 60.0, 4000.0)) near(ref(s), curve.multiplier(s), 1e-9, "sync $smooth at $s")
        }
    }

    @Test
    fun `synchronous gain table matches the port to float precision`() {
        for (smooth in listOf(0.0, 0.25, 0.5, 1.0)) {
            val args = RawAccelArgs(RawAccelMode.SYNCHRONOUS, gain = true, gamma = 1.3, motivity = 2.2, syncSpeed = 18.0, smooth = smooth)
            val ref = Ref.makeFn(args)
            val curve = RawAccelCurve(args)
            // below 2^-3 (table start), on table nodes, between nodes, at the end of the table and beyond it (extrapolation)
            val speeds = listOf(0.0, 0.01, 0.125, 0.13, 0.3, 1.0, 1.0625, 3.3, 18.0, 33.3, 100.0, 255.9, 256.0, 511.0, 512.0, 700.0, 5000.0)
            for (s in speeds) near(ref(s), curve.multiplier(s), 1e-6, "sync gain $smooth at $s")
            compare(profile(args, "sync gain smooth $smooth"), tolerance = 1e-6)
        }
    }

    @Test
    fun `classic modes run through the modifier like the port`() {
        for (gain in listOf(true, false)) for (capMode in RawAccelCapMode.entries) {
            val args = RawAccelArgs(RawAccelMode.CLASSIC, gain = gain, acceleration = 0.02, exponentClassic = 2.2, inputOffset = 3.0, capX = 40.0, capY = 1.8, capMode = capMode)
            compare(profile(args, "classic ${if (gain) "gain" else "legacy"} ${capMode.json}"))
        }
    }

    // ---- the order table ----

    @Test
    fun `rotation and snap and the reference angle`() {
        compare(profile(classic, rotation = 17.5))
        compare(profile(classic, snap = 5.0))
        compare(profile(classic, snap = 45.0, rotation = -8.0))
        compare(profile(powerGain, snap = 3.0, range = RawAccelXY(1.0, 0.5)))
    }

    @Test
    fun `snap turns a near axis move into an axis move`() {
        val p = profile(RawAccelArgs(RawAccelMode.NOACCEL), snap = 5.0)
        val out = RawAccelModifier(p)
        val e = out.modifyContinuous(MouseEvent(1_000_000_000, 40, 2))
        assertEquals(0.0, e.y, 1e-12)
        assertEquals(kotlin.math.sqrt(40.0 * 40 + 2 * 2), e.x, 1e-9)
        val v = RawAccelModifier(p).modifyContinuous(MouseEvent(1_000_000_000, -3, 50))
        assertEquals(0.0, v.x, 1e-12)
        assertTrue(v.y > 50.0)
    }

    @Test
    fun `speed cap scales the movement itself`() {
        compare(profile(classic, cap = 25.0))
        compare(profile(syncGain, cap = 80.0, rotation = 3.0))
    }

    @Test
    fun `domain weights and range weights and directional range`() {
        compare(profile(classic, domain = RawAccelXY(1.4, 0.6)))
        compare(profile(classic, range = RawAccelXY(1.0, 0.4)))
        compare(profile(jumpSmooth, range = RawAccelXY(0.5, 1.0), snap = 2.0))
        compare(profile(powerGain, range = RawAccelXY(0.7, 0.7)))
    }

    @Test
    fun `output ratios yx lr ud`() {
        compare(profile(classic, yx = 1.25, lr = 0.8, ud = 1.3))
        compare(profile(classic, yx = -1.0, lr = 1.0, ud = 0.5))
        val p = profile(RawAccelArgs(RawAccelMode.NOACCEL), lr = 0.5, ud = 2.0)
        val m = RawAccelModifier(p)
        val r = m.modifyContinuous(MouseEvent(1_000_000_000, -10, -10))
        assertEquals(-5.0, r.x, 1e-12)
        assertEquals(-20.0, r.y, 1e-12)
        val right = RawAccelModifier(p).modifyContinuous(MouseEvent(1_000_000_000, 10, 10))
        assertEquals(10.0, right.x, 1e-12)
        assertEquals(10.0, right.y, 1e-12)
    }

    @Test
    fun `by component uses its own acceleration per axis`() {
        val sep = RawAccelSpeedArgs(whole = false)
        compare(profile(classic, accelY = powerGain, speed = sep))
        compare(profile(jumpSmooth, accelY = syncGain, speed = sep, range = RawAccelXY(1.0, 0.5), domain = RawAccelXY(1.2, 0.8)), tolerance = 1e-6)
        compare(profile(classic, accelY = classic.copy(acceleration = 0.03), speed = sep, rotation = 11.0, snap = 4.0))
    }

    @Test
    fun `lp norms and the max norm`() {
        for (norm in listOf(1.0, 3.0, 2.0, 15.9, 16.0, 40.0)) {
            compare(profile(classic, speed = RawAccelSpeedArgs(lpNorm = norm), name = "lp $norm"))
        }
    }

    @Test
    fun `smoothers one at a time and together`() {
        val half = listOf(0.8, 3.0, 12.0)
        for (h in half) {
            compare(profile(classic, speed = RawAccelSpeedArgs(inputHalfLife = h), name = "input $h"))
            compare(profile(classic, speed = RawAccelSpeedArgs(scaleHalfLife = h), name = "scale $h"))
            compare(profile(classic, speed = RawAccelSpeedArgs(outputHalfLife = h), name = "output $h"))
        }
        val all = RawAccelSpeedArgs(inputHalfLife = 3.0, scaleHalfLife = 4.0, outputHalfLife = 2.5)
        compare(profile(classic, speed = all, name = "all"))
        compare(profile(classic, accelY = powerGain, speed = all.copy(whole = false), name = "all by component"))
        compare(profile(jumpSmooth, speed = all.copy(lpNorm = 3.0), snap = 2.0, lr = 0.9, name = "all lp3"))
    }

    // ---- time, carry, clock ----

    @Test
    fun `a zero report advances the clock`() {
        // legacy jump: 1 up to 30 in/s, 1.8 from there. 30 counts at 1000 DPI after 1 ms is 30 in/s, after 2 ms 15 in/s.
        val jump = profile(RawAccelArgs(RawAccelMode.JUMP, gain = false, capX = 30.0, capY = 1.8, smooth = 0.0), outputDpi = 1000.0, deviceDpi = 1000.0)
        val withZero = RawAccelModifier(jump)
        withZero.modifyContinuous(MouseEvent(0, 1, 0))
        withZero.modifyContinuous(MouseEvent(ms, 0, 0))
        assertEquals(30 * 1.8, withZero.modifyContinuous(MouseEvent(2 * ms, 30, 0)).x, 1e-9)
        val without = RawAccelModifier(jump)
        without.modifyContinuous(MouseEvent(0, 1, 0))
        assertEquals(30.0, without.modifyContinuous(MouseEvent(2 * ms, 30, 0)).x, 1e-9)
    }

    @Test
    fun `the first report and a pause use the longest time`() {
        val jump = profile(RawAccelArgs(RawAccelMode.JUMP, gain = false, capX = 30.0, capY = 1.8, smooth = 0.0), outputDpi = 1000.0, deviceDpi = 1000.0)
        val m = RawAccelModifier(jump)
        assertEquals(500.0, m.modifyContinuous(MouseEvent(1_000_000_000, 500, 0)).x, 1e-9) // 500 counts in 100 ms = 5 in/s
        assertEquals(500.0, m.modifyContinuous(MouseEvent(1_500_000_000, 500, 0)).x, 1e-9) // after 500 ms still 100 ms
        m.reset()
        assertEquals(500.0, m.modifyContinuous(MouseEvent(1_500_001_000, 500, 0)).x, 1e-9)
    }

    @Test
    fun `eight kHz reports are clamped to the minimum time`() {
        val dev = RawAccelDevice(pollingRate = 8000)
        compare(profile(classic, device = dev))
        compare(profile(classic, device = dev.copy(pollTimeLock = true)))
        compare(profile(classic, device = RawAccelDevice(pollingRate = 1000, pollTimeLock = true), deviceDpi = 0.0, outputDpi = 1000.0))
        compare(profile(classic, device = RawAccelDevice(timeMin = 0.5, timeMax = 20.0)))
    }

    @Test
    fun `the carry keeps the fractions and the sum follows the continuous total`() {
        val p = profile(RawAccelArgs(RawAccelMode.NOACCEL), outputDpi = 1200.0, deviceDpi = 800.0) // factor 1.5
        val m = RawAccelModifier(p)
        val events = (1..1000).map { MouseEvent(it * ms, 3, -1) }
        val out = events.map { m.modify(it) }
        assertEquals(4500, out.sumOf { it.dx }, "x")
        assertEquals(-1500, out.sumOf { it.dy }, "y")
        compare(p, events)
    }

    @Test
    fun `reset forgets carry and smoothers`() {
        val p = profile(classic, speed = RawAccelSpeedArgs(inputHalfLife = 3.0, outputHalfLife = 2.0), outputDpi = 1300.0)
        val events = stream().take(300)
        val first = RawAccelModifier(p)
        val a = events.map { first.modify(it) }
        first.reset()
        val b = events.map { first.modify(it) }
        assertEquals(a, b)
        assertNotEquals(0, a.sumOf { abs(it.dx) })
    }
}
