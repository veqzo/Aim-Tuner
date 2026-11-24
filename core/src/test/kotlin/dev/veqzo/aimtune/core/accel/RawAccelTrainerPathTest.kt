package dev.veqzo.aimtune.core.accel

import dev.veqzo.aimtune.core.input.MouseEvent
import dev.veqzo.aimtune.core.recording.RoundRecording
import dev.veqzo.aimtune.core.recording.ViewTrack
import dev.veqzo.aimtune.core.scenario.ScenarioKind
import dev.veqzo.aimtune.core.sim.HeadlessRound
import dev.veqzo.aimtune.core.sim.SimulatedPlayer
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The trainer path (what the view gets through [AccelModel.newModifier], as [ViewTrack] and `HeadlessRound` use it) against the
 * literal test-side port [Ref] of the Raw Accel driver: report by report the same integer counts, for a stream shaped like a real
 * round (flick bursts, diagonals, small corrections, pauses). The trainer only passes reports with movement on, so the stream has
 * no zero reports.
 */
class RawAccelTrainerPathTest {
    private val ms = 1_000_000L

    private class Lcg(var state: Long) {
        fun next(bound: Int): Int {
            state = state * 6364136223846793005L + 1442695040888963407L
            return ((state ushr 33) % bound).toInt()
        }
    }

    /** A 1 kHz stream with minimum-jerk-like flicks to the right, left, up and down, diagonals, small corrections and pauses. */
    private fun roundLikeStream(): List<MouseEvent> {
        val rng = Lcg(7)
        val events = ArrayList<MouseEvent>()
        var t = 5_000_000_000L
        fun tick(dx: Int, dy: Int) {
            t += ms
            if (dx != 0 || dy != 0) events += MouseEvent(t, dx, dy)
        }
        fun pause(millis: Int) { t += millis * ms }
        fun burst(totalX: Int, totalY: Int, millis: Int) {
            var doneX = 0
            var doneY = 0
            for (k in 1..millis) {
                val tau = k.toDouble() / millis
                val s = tau * tau * tau * (10 + tau * (-15 + 6 * tau))
                val x = (totalX * s).toInt()
                val y = (totalY * s).toInt()
                tick(x - doneX, y - doneY)
                doneX = x
                doneY = y
            }
        }
        repeat(60) { round ->
            val sign = if (round % 2 == 0) 1 else -1
            burst(sign * (150 + rng.next(300)), (rng.next(160) - 80), 110 + rng.next(60)) // flick
            repeat(4 + rng.next(4)) { tick(rng.next(5) - 2, rng.next(5) - 2) } // settle
            burst(rng.next(30) - 15, rng.next(24) - 12, 60 + rng.next(30)) // correction
            if (round % 5 == 0) burst(rng.next(60) - 30, -(40 + rng.next(120)), 90) // up
            if (round % 7 == 0) burst(rng.next(40) - 20, 40 + rng.next(100), 90) // down
            if (round % 6 == 0) burst(120 + rng.next(80), 110 + rng.next(80), 130) // diagonal
            pause(120 + rng.next(400)) // reaction time, sometimes above 100 ms
        }
        return events
    }

    private val user = RawAccelProfile(
        "default",
        RawAccelArgs(RawAccelMode.CLASSIC, gain = true, inputOffset = 10.0, capX = 62.5, capY = 1.6, capMode = RawAccelCapMode.IN_OUT),
        outputDpi = 600.0,
        deviceDpi = 800.0,
    )

    private val everything = RawAccelProfile(
        "everything",
        RawAccelArgs(RawAccelMode.CLASSIC, gain = false, acceleration = 0.02, inputOffset = 5.0, capX = 40.0, capY = 1.5, capMode = RawAccelCapMode.OUTPUT),
        outputDpi = 800.0,
        deviceDpi = 800.0,
        speed = RawAccelSpeedArgs(whole = true, inputHalfLife = 2.0),
        yxRatio = 0.9,
        lrRatio = 1.05,
        udRatio = 0.95,
        rotationDegrees = 3.0,
        snapDegrees = 5.0,
    )

    private fun recording(events: List<MouseEvent>, model: AccelModel?) = RoundRecording(
        scenarioId = "flick", seed = 1, dpi = 800.0, cm360 = 40.0, startNanos = 0, endNanos = events.last().timeNanos + ms,
        mouseEvents = events, targetEvents = emptyList(), accel = model,
    )

    private fun assertCountsMatchPort(profile: RawAccelProfile, events: List<MouseEvent>) {
        val track = ViewTrack(recording(events, profile))
        val ref = Ref.Driver(profile)
        var changed = 0
        for ((i, e) in events.withIndex()) {
            val expected = ref.report(e.timeNanos, e.dx, e.dy)
            assertEquals(expected.x.toDouble(), track.movedX[i], "report $i x (${profile.name})")
            assertEquals(expected.y.toDouble(), track.movedY[i], "report $i y (${profile.name})")
            val inLength = hypot(e.dx.toDouble(), e.dy.toDouble())
            assertEquals(hypot(expected.x.toDouble(), expected.y.toDouble()) / inLength, track.scales[i], 1e-12, "scale of report $i")
            if (expected.x != e.dx || expected.y != e.dy) changed++
        }
        assertTrue(changed > events.size / 10, "the profile changes a good share of the reports ($changed of ${events.size})")
    }

    @Test
    fun `the user's profile plays the exact driver counts on a round-like stream`() {
        val events = roundLikeStream()
        assertTrue(events.size > 5_000, "${events.size} reports")
        assertCountsMatchPort(user, events)
    }

    @Test
    fun `rotation, snap, ratios and input smoothing play the exact driver counts`() {
        assertCountsMatchPort(everything, roundLikeStream())
    }

    @Test
    fun `a headless round with a profile replays to the driver counts of its recorded reports`() {
        for (profile in listOf(user, everything)) {
            val recording = HeadlessRound.of(ScenarioKind.FLICK, SimulatedPlayer(), 800.0, 40.0, seed = 3, roundSeconds = 15, accel = profile)
            assertTrue(recording.mouseEvents.size > 300)
            val track = ViewTrack(recording)
            val ref = Ref.Driver(profile)
            for ((i, e) in recording.mouseEvents.withIndex()) {
                val expected = ref.report(e.timeNanos, e.dx, e.dy)
                assertEquals(expected.x.toDouble(), track.movedX[i], "${profile.name} report $i x")
                assertEquals(expected.y.toDouble(), track.movedY[i], "${profile.name} report $i y")
            }
            assertEquals(profile, recording.accel)
        }
    }

    @Test
    fun `a fresh modifier replays a round to the same view as when it was played`() {
        val events = roundLikeStream()
        val recording = recording(events, everything)
        val first = ViewTrack(recording)
        val second = ViewTrack(recording)
        assertEquals(first.scales.toList(), second.scales.toList())
        assertEquals(first.viewAt(events.last().timeNanos), second.viewAt(events.last().timeNanos))
        assertNotEquals(ViewTrack(recording.copy(accel = null)).viewAt(events.last().timeNanos), first.viewAt(events.last().timeNanos))
    }

    @Test
    fun `a curve model keeps the finder's continuous arithmetic and a profile is no finder curve`() {
        val curve = NaturalCurve(0.25, 2.2)
        val modifier = curve.newModifier(800.0)
        val processor = AccelProcessor(curve, 800.0)
        for (e in roundLikeStream().take(500)) {
            val scale = processor.scaleFor(e)
            val move = modifier.modify(e)
            assertEquals(scale, move.scale)
            assertEquals(e.dx * scale, move.dx)
            assertEquals(e.dy * scale, move.dy)
        }
        assertEquals(curve, curve.finderCurve)
        assertEquals(null, user.finderCurve)
        assertEquals(user.totalMultiplier(30.0), user.planningGain(30.0, 800.0), 1e-12)
    }

    @Test
    fun `stored texts decode to the right model, rawaccel1 stays a curve only`() {
        assertEquals(everything, AccelSpec.decodeModel(AccelSpec.encodeModel(everything)))
        assertTrue(AccelSpec.encodeModel(everything).startsWith("rawaccel2;"))
        val curve = user.curve
        assertEquals(curve, AccelSpec.decodeModel(AccelSpec.encodeModel(curve)))
        assertTrue(AccelSpec.decodeModel(AccelSpec.encodeModel(curve)) is RawAccelCurve)
        assertTrue(AccelSpec.isProfile(AccelSpec.encodeProfile(user)) && !AccelSpec.isProfile(AccelSpec.encode(curve)))
    }
}
