package dev.veqzo.aimtune.core.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VersionTest {
    @Test
    fun `tags and plain versions are read, suffixes ignored`() {
        assertEquals(Version(1, 2, 3), Version.parse("v1.2.3"))
        assertEquals(Version(1, 2, 3), Version.parse("1.2.3"))
        assertEquals(Version(1, 2, 0), Version.parse("1.2"))
        assertEquals(Version(2, 0, 0), Version.parse(" V2 "))
        assertEquals(Version(1, 2, 3), Version.parse("v1.2.3-beta.1"))
        assertEquals(Version(1, 2, 3), Version.parse("1.2.3+build5"))
    }

    @Test
    fun `anything else is not a version`() {
        listOf("", "v", "latest", "1.x.3", "1..3", "1.2.3.4", "-1.2.3", "1.2.3a", "99999999999.0.0").forEach { assertNull(Version.parse(it), it) }
    }

    @Test
    fun `versions compare by number, not by text`() {
        assertTrue(Version(1, 10, 0) > Version(1, 9, 0))
        assertTrue(Version(2, 0, 0) > Version(1, 99, 99))
        assertTrue(Version(0, 1, 1) > Version(0, 1, 0))
        assertEquals(0, Version(1, 2, 3).compareTo(Version(1, 2, 3)))
    }
}
