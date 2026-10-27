package de.yuuto.aimtune.app.accel

import de.yuuto.aimtune.core.accel.OwnCurveStrength
import de.yuuto.aimtune.core.accel.LookupCurve
import de.yuuto.aimtune.core.accel.RawAccelProfile
import de.yuuto.aimtune.storage.PassiveChanges
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PatchedCopyTest {
    private val imported = sampleImport()
    private val profile = sampleProfile()

    private fun copy(passive: PassiveChanges, refined: RawAccelProfile? = null) = PatchedCopy(imported.source, profile, refined, passive)

    private fun profiles(root: JsonObject) = (root.getValue("profiles") as JsonArray).map { it.jsonObject }

    @Test
    fun `passive recommendations alone make a copy that differs in those keys only and are marked not blind-tested`() {
        val copy = copy(PassiveChanges(rotationDegrees = -1.5, yxRatio = 1.08))
        assertEquals("${profile.name} (AimTune)", copy.exportName)
        assertEquals(
            listOf(PatchedChange("rotation", "0", "-1.5", passive = true), PatchedChange("Y/X ratio", "1", "1.08", passive = true)),
            copy.changes(),
        )
        val original = imported.source.file.root
        val patched = copy.root()
        assertEquals(profiles(original).size + 1, profiles(patched).size)
        assertEquals(profiles(original), profiles(patched).dropLast(1), "the user's profiles are as they were")
        val before = profiles(original).first { it["name"]?.jsonPrimitive?.content == profile.name }
        val after = profiles(patched).last()
        assertEquals(copy.exportName, after.getValue("name").jsonPrimitive.content)
        val changed = (before.keys + after.keys).filter { before[it] != after[it] }.toSet()
        assertEquals(setOf("name", "Degrees of rotation", "Y/X output DPI ratio (vertical sens multiplier)"), changed)
    }

    @Test
    fun `a recommendation that equals what the profile has is not rewritten`() {
        val copy = copy(PassiveChanges(rotationDegrees = profile.rotationDegrees, yxRatio = profile.yxRatio))
        assertFalse(copy.hasChanges)
        assertTrue(copy.changes().isEmpty())
    }

    @Test
    fun `the refine result and the passive step end up in one copy, curve fields first`() {
        val refined = profile.copy(args = profile.args.copy(capY = profile.args.capY + 0.1))
        val copy = copy(PassiveChanges(lrRatio = 0.97), refined)
        assertEquals(listOf(false, true), copy.changes().map { it.passive })
        val root = copy.root()
        assertEquals(JsonPrimitive(0.97), profiles(root).last().getValue("L/R output DPI ratio (left sens multiplier)"))
        assertEquals(profiles(imported.source.file.root), profiles(root).dropLast(1))
    }

    @Test
    fun `a draft stage makes a copy that plays the table, listed as one blind-testable change`() {
        val table = OwnCurveStrength(profile, 800.0).model(1.3) as LookupCurve
        val stage = DraftStage(imported.source, imported.profileName, table, 1.3)
        val copy = PatchedCopy(imported.source, profile, null, PassiveChanges(), stage)
        assertTrue(copy.hasChanges)
        val change = copy.changes().single()
        assertEquals("curve", change.field)
        assertFalse(change.passive)
        assertTrue("x1.30" in change.new, change.new)
        val patched = profiles(copy.root())
        assertEquals(profiles(imported.source.file.root), patched.dropLast(1), "the user's profiles are as they were")
        assertEquals(copy.exportName, patched.last().getValue("name").jsonPrimitive.content)
        assertEquals("lut", patched.last().getValue("Whole or horizontal accel parameters").jsonObject.getValue("mode").jsonPrimitive.content)
    }
}
