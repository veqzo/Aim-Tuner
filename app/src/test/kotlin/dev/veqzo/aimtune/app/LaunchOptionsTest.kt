package dev.veqzo.aimtune.app

import dev.veqzo.aimtune.storage.CurveFinder
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LaunchOptionsTest {
    private fun parse(vararg args: String) = parseLaunchOptions(arrayOf(*args))

    @Test
    fun `no arguments give the defaults`() {
        val o = parse()
        assertNull(o.seed)
        assertEquals(SourceKind.AUTO, o.source)
        assertTrue(o.vsync)
        assertNull(o.screenshotDir)
        assertNull(o.recordDir)
    }

    @Test
    fun `all options are parsed`() {
        val o = parse("--seed", "9", "--source", "glfw", "--no-vsync", "--no-update-check", "--screenshots", "out", "--record", "logs")
        assertEquals(9L, o.seed)
        assertTrue(o.noUpdateCheck)
        assertEquals(SourceKind.GLFW, o.source)
        assertFalse(o.vsync)
        assertEquals(Paths.get("out"), o.screenshotDir)
        assertEquals(Paths.get("logs"), o.recordDir)
    }

    @Test
    fun `the curve finder is parsed and unset by default`() {
        assertNull(parse().curveFinder)
        assertEquals(CurveFinder.LEGACY, parse("--curve-finder", "legacy").curveFinder)
        assertEquals(CurveFinder.V2, parse("--curve-finder", "V2").curveFinder)
        assertFailsWith<LaunchOptionsException> { parse("--curve-finder", "v3") }
    }

    @Test
    fun `the screenshot window size is parsed and unset by default`() {
        assertNull(parse().windowSize)
        assertEquals(WindowSize(1280, 720), parse("--window-size", "1280x720").windowSize)
        assertEquals(WindowSize(1100, 1000), parse("--window-size", "1100X1000").windowSize)
        listOf("1280", "1280x", "x720", "0x720", "1280x-720", "axb").forEach { bad ->
            assertFailsWith<LaunchOptionsException>(bad) { parse("--window-size", bad) }
        }
    }

    @Test
    fun `invalid input is rejected with the option name`() {
        assertFailsWith<LaunchOptionsException> { parse("--seed", "abc") }
        assertFailsWith<LaunchOptionsException> { parse("--source", "x") }
        assertFailsWith<LaunchOptionsException> { parse("--seed") }
        val e = assertFailsWith<LaunchOptionsException> { parse("--dpi", "800") }
        assertTrue("--dpi" in e.message.orEmpty())
    }
}
