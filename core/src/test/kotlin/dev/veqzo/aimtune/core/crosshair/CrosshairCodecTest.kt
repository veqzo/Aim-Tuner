package dev.veqzo.aimtune.core.crosshair

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CrosshairCodecTest {
    /** Codes of professional players as listed on thespike.gg, The Loadout and Sportskeeda (docs/CROSSHAIR.md). */
    private val realCodes = listOf(
        "0;P;h;0;0t;1;0l;4;0o;0;0a;1;0f;0;1b;0",
        "0;s;1;P;c;5;o;1;d;1;z;3;0b;0;1b;0;S;s;0.628;o;1",
        "0;P;h;0;d;1;f;0;0l;3;0o;1;0a;1;0f;0;1b;0",
        "0;s;1;P;h;0;s;0;0l;3;0o;0;0a;1;0f;0;1b;0",
        "0;P;c;5;o;1;d;1;z;3;f;0;0b;0;1b;0",
        "0;s;1;P;c;5;o;1;0l;4;0v;3;0g;1;0o;0;0a;1;0f;0;1b;0;S;c;0;s;1.2;o;1",
        "0;s;1;P;c;7;h;0;0l;4;0o;0;0a;1;0f;0;1b;0;S;c;1;o;1",
        "0;P;o;1;0t;1;0l;2;0a;1;0f;0;1b;0",
        "0;P;c;1;h;0;f;0;s;0;0t;1;0l;2;0o;2;0a;1;0f;0;1b;0",
        "0;P;h;0;0l;4;0o;0;0a;1;0f;0;1t;0;1l;0;1o;0;1a;0",
        "0;s;1;P;c;8;t;2;o;1;d;1;b;1;a;0.462;f;0;0t;10;0l;14;0v;0;0g;1;0o;5;0a;0.308;0f;0;1t;1;1l;6;1v;0;1g;1;1o;18;1a;0;1m;0;1f;0;S;c;5;s;1.398",
        "0;s;1;P;c;4;u;DB0B8AFF;o;1;d;1;z;6;a;0;f;0;m;1;0t;10;0l;10;0v;0;0g;1;0o;17;0a;0;0f;0;1t;6;1l;0;1v;3;1g;1;1o;0;1a;1;1m;0;1e;0.281;S;b;1;c;8;t;DB0B8AFF",
        "0;p;0;s;1;P;c;1;t;2;o;1;f;0;0o;2;0a;1;0f;0;1b;0;A;d;1;0b;0;1b;0;S;o;0.502",
        "0;s;1;P;c;5;o;1;d;1;f;0;s;0;0l;0;0a;1;0f;0;1t;0;1l;0;1o;0;1a;0;1f;0;S;c;1;o;1",
        "0;s;1;P;u;D099E2FF;o;1;d;1;0b;0;1b;0;S;c;0;s;0.5;o;1",
        "0;c;1;s;1;P;c;6;t;2;o;1;d;1;z;3;m;1;0b;0;1b;0;S;c;0",
        "0;s;1;P;c;5;o;1;f;0;m;1;0l;2;0v;2;0g;1;0o;0;0a;1;0f;0;1b;0;S;c;5;s;0.7;o;1",
    )

    @Test
    fun `real codes survive code to code unchanged, also through the typed model`() {
        for (text in realCodes) {
            val code = CrosshairCode.parse(text)
            assertEquals(text, code.format(), "raw round trip")
            val typed = Crosshair.fromCode(code)
            assertEquals(text, typed.applyTo(code).format(), "typed round trip of $text")
        }
    }

    @Test
    fun `typed values are read with the defaults of the game for missing keys`() {
        val tenz = Crosshair.fromCode(CrosshairCode.parse("0;P;c;5;o;1;d;1;z;3;f;0;0b;0;1b;0"))
        assertEquals(CrosshairColor(preset = 5), tenz.color)
        assertTrue(tenz.outlines, "outlines default to on")
        assertEquals(1.0, tenz.outlineOpacity)
        assertEquals(1, tenz.outlineThickness)
        assertTrue(tenz.dot)
        assertEquals(3, tenz.dotThickness)
        assertFalse(tenz.inner.show)
        assertFalse(tenz.outer.show)

        val plain = Crosshair.fromCode(CrosshairCode.parse("0;P;c;1"))
        assertEquals(CrosshairLines.INNER, plain.inner)
        assertEquals(CrosshairLines.OUTER, plain.outer)
        assertEquals(1, plain.color.preset)
    }

    @Test
    fun `a complex code gives the right line values`() {
        val c = Crosshair.fromCode(CrosshairCode.parse(realCodes[10]))
        assertEquals("", c.color.customHex ?: "", "custom color without a hex value keeps the preset")
        assertEquals(CrosshairLines(true, 10, 0.308, 5, 14, verticalLength = 0, firingError = false), c.inner)
        assertEquals(CrosshairLines(true, 1, 0.0, 18, 6, verticalLength = 0, firingError = false), c.outer)
        assertEquals(0.462, c.dotOpacity)
        val hex = Crosshair.fromCode(CrosshairCode.parse("0;P;c;8;u;ff00AA80;b;1"))
        assertEquals("FF00AA80", hex.color.customHex)
        assertEquals(0xFF00AA80.toInt(), hex.color.rgba())
    }

    @Test
    fun `editing keeps unknown keys, other sections and their order`() {
        val original = "0;p;0;s;1;P;c;1;t;2;o;1;s;0;0o;2;0a;1;0f;0;1b;0;xyz;7;A;d;1;0b;0;1b;0;S;o;0.502"
        val code = CrosshairCode.parse(original)
        val edited = Crosshair.fromCode(code).copy(dot = true, dotThickness = 4, inner = CrosshairLines(length = 9, offset = 2, firingError = false, opacity = 1.0)).applyTo(code)
        val text = edited.format()
        assertTrue(";xyz;7;A;d;1;0b;0;1b;0;S;o;0.502" in text, text)
        assertTrue(text.startsWith("0;p;0;s;1;P;c;1;t;2;o;1;d;1;z;4;"), text)
        assertEquals(Crosshair.fromCode(edited).dotThickness, 4)
        assertEquals(9, Crosshair.fromCode(edited).inner.length)
        assertEquals(2, Crosshair.fromCode(CrosshairCode.parse(text)).inner.offset)
        // The primary section's unknown `s;0` is still there, and A and S are untouched.
        assertEquals("0", edited.primary?.value("s"))
        assertEquals("0.502", edited.sections.single { it.marker == "S" }.value("o"))
    }

    @Test
    fun `a new crosshair is exported as a valid code that reads back the same`() {
        val crosshair = Crosshair(
            color = CrosshairColor(customHex = "12AB34FF"), outlines = false, dot = true, dotThickness = 3,
            inner = CrosshairLines(thickness = 1, length = 4, offset = 0, opacity = 1.0, verticalLength = 3, firingError = false),
            outer = CrosshairLines(show = false),
        )
        val text = crosshair.toCode().format()
        assertTrue(text.startsWith("0;P;"), text)
        assertEquals(crosshair, Crosshair.fromCode(CrosshairCode.parse(text)))
        assertEquals(Crosshair(), Crosshair.fromCode(CrosshairCode.parse(Crosshair().toCode().format())))
    }

    @Test
    fun `numbers are written the way the game writes them`() {
        assertEquals("1", Crosshair.number(1.0))
        assertEquals("0.35", Crosshair.number(0.35))
        assertEquals("0.462", Crosshair.number(0.462))
        assertEquals("0", Crosshair.number(0.0))
    }

    @Test
    fun `invalid codes give a clear message`() {
        fun message(text: String) = assertFailsWith<CrosshairCodeException> { Crosshair.fromCode(CrosshairCode.parse(text)) }.message.orEmpty()
        assertTrue("empty" in message(""))
        assertTrue("version" in message("P;c;5"))
        assertTrue("spaces" in message("hello world"), message("hello world"))
        assertTrue("version" in message("helloworld"), message("helloworld"))
        assertTrue("no value" in message("0;P;c"))
        assertTrue("no value" in message("0;P;c;5;0l"))
        assertTrue("empty key" in message("0;P;;5"))
        assertTrue("primary" in message("0;S;c;5"))
        assertTrue("not a number" in message("0;P;t;thick"))
        assertTrue("spaces" in message("0;P;c; 5"))
        assertTrue("too long" in message("0;P;" + "c;5;".repeat(600)))
    }

    @Test
    fun `surrounding spaces and a trailing semicolon are accepted`() {
        assertEquals("0;P;c;5", CrosshairCode.parse("  0;P;c;5; \n").format())
        assertNull(CrosshairCode.parse("0;P;c;5").sections.single().value("x"))
        assertNotNull(CrosshairCode.parse("0;P").primary)
    }
}
