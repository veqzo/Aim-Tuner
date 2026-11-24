package dev.veqzo.aimtune.app.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class TokensTest {
    @Test
    fun `hex and rgba colors parse`() {
        val c = Tokens.parseColor("#9184d9")
        assertEquals(0x91 / 255f, c.r, 1e-6f)
        assertEquals(0xd9 / 255f, c.b, 1e-6f)
        assertEquals(1f, c.a)
        val d = Tokens.parseColor("rgba(233,233,237,0.16)")
        assertEquals(233 / 255f, d.r, 1e-6f)
        assertEquals(0.16f, d.a, 1e-6f)
    }

    @Test
    fun `the bundled design tokens load with the values of the design`() {
        val t = Tokens.load()
        assertEquals(Tokens.parseColor("#121314"), t.bg)
        assertEquals(Tokens.parseColor("#1B1D1F"), t.surface)
        assertEquals(Tokens.parseColor("#FFB020"), t.accent)
        assertEquals(Tokens.parseColor("#2E220C"), t.accent(900))
        assertEquals(Tokens.parseColor("#7D838A"), t.neutral(500))
        assertEquals(Tokens.parseColor("#0D0E0F"), t.well)
        assertEquals(10f, t.radiusMd)
        assertEquals(1080f, t.screenHeight)
        assertEquals(64f, t.margin)
        assertEquals(150f, t.fontSize("numberXl"))
        assertEquals(19f, t.fontSize("body"))
        assertEquals(0.22f, t.motionBase)
    }
}
