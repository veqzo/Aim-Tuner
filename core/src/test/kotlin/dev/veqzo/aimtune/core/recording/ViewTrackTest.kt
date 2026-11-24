package dev.veqzo.aimtune.core.recording

import dev.veqzo.aimtune.core.accel.AccelProcessor
import dev.veqzo.aimtune.core.accel.ClassicCurve
import dev.veqzo.aimtune.core.input.MouseEvent
import kotlin.test.Test
import kotlin.test.assertEquals

class ViewTrackTest {
    private val events = listOf(MouseEvent(10, 100, 0), MouseEvent(20, 0, 50), MouseEvent(30, 100, 0))
    private val recording = RoundRecording("flick", 1, dpi = 800.0, cm360 = 40.0, startNanos = 0, endNanos = 100, mouseEvents = events, targetEvents = emptyList())
    private val dpc = recording.degreesPerCount

    @Test
    fun `view before the first event is the start orientation`() {
        val v = ViewTrack(recording).viewAt(5)
        assertEquals(0.0, v.yawDegrees)
        assertEquals(0.0, v.pitchDegrees)
    }

    @Test
    fun `the acceleration curve scales the counts of each report`() {
        val curve = ClassicCurve(0.08)
        val accelerated = recording.copy(accel = curve)
        val track = ViewTrack(accelerated)
        // 100 counts after a first report (longest time, 100 ms): 100 counts / 100 ms at 800 dpi = 1.25 in/s.
        val firstScale = curve.multiplier(1.25)
        assertEquals(firstScale, track.scales[0], 1e-12)
        assertEquals(100 * firstScale * dpc, track.viewAt(10).yawDegrees, 1e-9)
        // The second report (50 counts down) comes 10 ns later, i.e. the shortest time clamps the speed.
        assertEquals(curve.multiplier(AccelProcessor.speedInPerSecond(50.0 / AccelProcessor.MIN_TIME_MS, 800.0)), track.scales[1], 1e-12)
        assertEquals(ViewTrack(recording).scales.toList(), List(3) { 1.0 })
    }

    @Test
    fun `view accumulates counts up to the requested time`() {
        val track = ViewTrack(recording)
        assertEquals(100 * dpc, track.viewAt(10).yawDegrees, 1e-9)
        val mid = track.viewAt(25)
        assertEquals(100 * dpc, mid.yawDegrees, 1e-9)
        assertEquals(-50 * dpc, mid.pitchDegrees, 1e-9)
        assertEquals(200 * dpc, track.viewAt(1000).yawDegrees, 1e-9)
    }
}
