package dev.veqzo.aimtune.app.accel

import dev.veqzo.aimtune.core.accel.settings.PassiveResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsAnalysesTest {
    @Test
    fun `no stored rounds give honest not enough data answers and no speed summary`() {
        val a = SettingsAnalyses.compute(emptyList())
        assertEquals(0, a.rounds)
        assertEquals(0, a.reaches)
        listOf(RawAccelSetting.ROTATION, RawAccelSetting.Y_X, RawAccelSetting.L_R, RawAccelSetting.U_D).forEach {
            assertTrue(a.result(it) is PassiveResult.NotEnoughData, it.id)
        }
        assertNull(a.result(RawAccelSetting.SNAP))
        assertNull(a.speed)
    }
}
