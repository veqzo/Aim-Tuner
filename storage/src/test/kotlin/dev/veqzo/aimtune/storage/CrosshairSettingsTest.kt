package dev.veqzo.aimtune.storage

import dev.veqzo.aimtune.core.crosshair.Crosshair
import dev.veqzo.aimtune.core.crosshair.CrosshairLines
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class CrosshairSettingsTest {
    @TempDir
    lateinit var dir: Path

    private val tenz = "0;P;c;5;o;1;d;1;z;3;f;0;0b;0;1b;0"
    private val other = "0;s;1;P;c;7;h;0;0l;4;0o;0;0a;1;0f;0;1b;0;S;c;1;o;1"

    @Test
    fun `without saved crosshairs the default crosshair is drawn`() {
        assertEquals(Crosshair(), AppSettings().activeCrosshair())
        assertNull(AppSettings().activeSavedCrosshair())
    }

    @Test
    fun `adding selects the new crosshair, and profiles can be renamed, duplicated, selected and deleted`() {
        var s = AppSettings().withNewCrosshair("  TenZ ", tenz)
        assertEquals("crosshair-1", s.activeCrosshair)
        assertEquals("TenZ", s.crosshairs.single().name)
        assertEquals(3, s.activeCrosshair().dotThickness)

        s = s.withNewCrosshair("Other", other)
        assertEquals("crosshair-2", s.activeCrosshair)
        assertEquals(7, s.activeCrosshair().color.preset)
        s = s.withCrosshairSelected("crosshair-1")
        assertEquals(3, s.activeCrosshair().dotThickness)

        s = s.withCrosshairRenamed("crosshair-2", "Pink one")
        assertEquals(listOf("TenZ", "Pink one"), s.crosshairs.map { it.name })

        s = s.withCrosshairDuplicated("crosshair-1")
        assertEquals("TenZ copy", s.crosshairs.last().name)
        assertEquals(tenz, s.crosshairs.last().code)
        assertEquals("crosshair-3", s.activeCrosshair)

        s = s.withCrosshairCode("crosshair-3", other)
        assertEquals(7, s.activeCrosshair().color.preset)

        s = s.withCrosshairDeleted("crosshair-3")
        assertEquals(listOf("crosshair-1", "crosshair-2"), s.crosshairs.map { it.id })
        assertEquals("crosshair-1", s.activeCrosshair, "the first remaining one is selected after deleting the active one")
        s = s.withCrosshairDeleted("crosshair-2").withCrosshairDeleted("crosshair-1")
        assertNull(s.activeCrosshair)
        assertEquals(Crosshair(), s.activeCrosshair())
        assertEquals(s, s.withCrosshairSelected("missing"))
    }

    @Test
    fun `ids are not reused after a delete and a long name is shortened for a copy`() {
        var s = AppSettings().withNewCrosshair("a", tenz).withNewCrosshair("b", tenz).withCrosshairDeleted("crosshair-1")
        s = s.withNewCrosshair("c", tenz)
        assertEquals(listOf("crosshair-2", "crosshair-3"), s.crosshairs.map { it.id })
        val long = AppSettings().withNewCrosshair("x".repeat(MAX_CROSSHAIR_NAME), tenz).withCrosshairDuplicated("crosshair-1")
        assertEquals(MAX_CROSSHAIR_NAME, long.crosshairs.last().name.length)
        long.validate()
    }

    @Test
    fun `crosshairs survive saving and loading and bad ones are rejected`() {
        val store = SettingsStore(dir.resolve("settings.json"))
        val s = AppSettings().withNewCrosshair("TenZ", tenz).withNewCrosshair("Other", other)
        store.save(s)
        assertEquals(s, store.load())
        assertEquals(Crosshair.fromCodeOf(other), store.load().activeCrosshair())

        assertFailsWith<SettingsException> { store.save(s.copy(crosshairs = listOf(SavedCrosshair("a", "Bad", "hello")))) }
        assertFailsWith<SettingsException> { store.save(s.copy(crosshairs = listOf(SavedCrosshair("a", " ", tenz)))) }
        assertFailsWith<SettingsException> { store.save(s.copy(crosshairs = s.crosshairs + s.crosshairs.first())) }
        assertFailsWith<SettingsException> { store.save(s.copy(activeCrosshair = "nope")) }
        assertEquals(CrosshairLines.INNER, Crosshair().inner)
    }

    private fun Crosshair.Companion.fromCodeOf(code: String) =
        fromCode(dev.veqzo.aimtune.core.crosshair.CrosshairCode.parse(code))
}
