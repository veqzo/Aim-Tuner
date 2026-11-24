package dev.veqzo.aimtune.storage

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/** The second, separately tested patch step: the passive recommendations change those keys of the chosen profile and nothing else. */
class RawAccelPassivePatchTest {
    private val original = Json.parseToJsonElement(checkNotNull(javaClass.getResource("/rawaccel/user-classic-gain.json")) { "fixture missing" }.readText()).jsonObject
    private val rotationKey = "Degrees of rotation"
    private val yxKey = "Y/X output DPI ratio (vertical sens multiplier)"
    private val lrKey = "L/R output DPI ratio (left sens multiplier)"

    private fun profiles(root: JsonObject) = (root.getValue("profiles") as JsonArray).map { it.jsonObject }

    /** The file as the export builds it: the user's profile plus a copy named "copy", which the passive step then changes. */
    private val withCopy: JsonObject = JsonObject(
        original + ("profiles" to JsonArray(profiles(original) + JsonObject(profiles(original).first() + ("name" to JsonPrimitive("copy"))))),
    )

    /** Reparses the text the export writes, so the check is on what lands in the file. */
    private fun reparsed(root: JsonObject) = Json.parseToJsonElement(RawAccelExport.patchedText(root)).jsonObject

    private fun changedKeys(before: JsonObject, after: JsonObject) = (before.keys + after.keys).filter { before[it] != after[it] }.toSet()

    @Test
    fun `only the three keys of the chosen profile change`() {
        val patched = reparsed(RawAccelPassivePatch.apply(withCopy, "copy", PassiveChanges(rotationDegrees = -1.5, yxRatio = 1.08, lrRatio = 0.97)))
        assertEquals(withCopy.keys.toList(), patched.keys.toList(), "top-level key order")
        for (key in withCopy.keys - "profiles") assertEquals(withCopy.getValue(key), patched.getValue(key), key)
        val (user, copyBefore) = profiles(withCopy)
        val (userAfter, copyAfter) = profiles(patched)
        assertEquals(user, userAfter, "the user's own profile is untouched")
        assertEquals(copyBefore.keys.toList(), copyAfter.keys.toList(), "key order inside the profile")
        assertEquals(setOf(rotationKey, yxKey, lrKey), changedKeys(copyBefore, copyAfter))
        assertEquals(-1.5, (copyAfter.getValue(rotationKey) as JsonPrimitive).content.toDouble())
        assertEquals(1.08, (copyAfter.getValue(yxKey) as JsonPrimitive).content.toDouble())
        assertEquals(0.97, (copyAfter.getValue(lrKey) as JsonPrimitive).content.toDouble())
    }

    @Test
    fun `a single recommendation changes a single key and keeps the others as written`() {
        val patched = reparsed(RawAccelPassivePatch.apply(withCopy, "copy", PassiveChanges(rotationDegrees = 2.0)))
        assertEquals(setOf(rotationKey), changedKeys(profiles(withCopy)[1], profiles(patched)[1]))
    }

    @Test
    fun `no changes return the same tree`() {
        assertSame(withCopy, RawAccelPassivePatch.apply(withCopy, "copy", PassiveChanges()))
    }

    @Test
    fun `values Raw Accel would refuse are refused here`() {
        assertFailsWith<IllegalArgumentException> { RawAccelPassivePatch.apply(withCopy, "copy", PassiveChanges(yxRatio = 0.0)) }
        assertFailsWith<IllegalArgumentException> { RawAccelPassivePatch.apply(withCopy, "copy", PassiveChanges(lrRatio = -1.0)) }
        assertFailsWith<IllegalArgumentException> { RawAccelPassivePatch.apply(withCopy, "copy", PassiveChanges(rotationDegrees = Double.NaN)) }
    }

    @Test
    fun `an unknown profile is refused`() {
        assertFailsWith<RawAccelImportException> { RawAccelPassivePatch.apply(withCopy, "nope", PassiveChanges(rotationDegrees = 1.0)) }
    }

    @Test
    fun `the patched file still imports and plays the new values`() {
        val patched = RawAccelPassivePatch.apply(withCopy, "copy", PassiveChanges(rotationDegrees = -1.5, yxRatio = 1.08, lrRatio = 0.97))
        val file = RawAccelImport.parse(Json.encodeToString(JsonElement.serializer(), patched))
        val profile = (RawAccelImport.profile(file, "copy") as RawAccelImportResult.Imported).profile
        assertEquals(listOf(-1.5, 1.08, 0.97), listOf(profile.rotationDegrees, profile.yxRatio, profile.lrRatio))
    }
}
