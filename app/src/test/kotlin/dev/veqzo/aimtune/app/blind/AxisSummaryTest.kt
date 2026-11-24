package dev.veqzo.aimtune.app.blind

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AxisSummaryTest {
    private fun rounds(h: Double, v: Double, bias: Double, count: Int = 5) = List(count) { AxisErrors(h, v, bias) }

    @Test
    fun `mostly vertical misses are named with the direction of the bias`() {
        assertEquals(AxisSummary.Verdict.VERTICAL_HIGH, assertNotNull(AxisSummary.of(rounds(1.0, 3.0, 0.8))).verdict)
        assertEquals(AxisSummary.Verdict.VERTICAL_LOW, assertNotNull(AxisSummary.of(rounds(1.0, 3.0, -0.8))).verdict)
        assertEquals(AxisSummary.Verdict.VERTICAL, assertNotNull(AxisSummary.of(rounds(1.0, 3.0, 0.1))).verdict)
        assertEquals(0.75, assertNotNull(AxisSummary.of(rounds(1.0, 3.0, 0.1))).verticalShare, 1e-9)
    }

    @Test
    fun `horizontal and mixed misses`() {
        assertEquals(AxisSummary.Verdict.HORIZONTAL, assertNotNull(AxisSummary.of(rounds(3.0, 1.0, 0.0))).verdict)
        assertEquals(AxisSummary.Verdict.BALANCED, assertNotNull(AxisSummary.of(rounds(2.0, 2.0, 0.0))).verdict)
    }

    @Test
    fun `too few rounds or no movements give no summary`() {
        assertNull(AxisSummary.of(rounds(1.0, 1.0, 0.0, count = 2)))
        assertNull(AxisSummary.of(List(5) { null }))
        assertNull(AxisSummary.of(rounds(0.0, 0.0, 0.0)))
        assertNull(AxisSummary.of(rounds(1.0, 1.0, 0.0, count = 2) + List(5) { null }))
    }
}
