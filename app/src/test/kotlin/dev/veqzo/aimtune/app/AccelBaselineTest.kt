package dev.veqzo.aimtune.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AccelBaselineTest {
    private fun baseline(confidence: Int?) = AccelBaseline(
        gameCm360 = 48.7, gameSensitivityText = "0.335", gameName = "Valorant",
        finder = confidence?.let { FinderResult(34.4, it, 40, "0.474", searchRounds = 30) }, gameSearchRounds = 5,
    )

    @Test
    fun `the game sensitivity is the default`() {
        assertEquals(48.7, baseline(80).cm360(useFinder = false))
        assertEquals(48.7, baseline(null).cm360(useFinder = false))
    }

    @Test
    fun `the finder result is played only when it is confident and chosen`() {
        assertEquals(34.4, baseline(50).cm360(useFinder = true))
        assertTrue(baseline(50).canUseFinder)
        assertFalse(baseline(49).canUseFinder)
        assertEquals(48.7, baseline(49).cm360(useFinder = true), "not confident enough")
        assertEquals(48.7, baseline(null).cm360(useFinder = true), "no result")
    }

    @Test
    fun `the stored search rounds follow the chosen sensitivity`() {
        assertEquals(5, baseline(80).searchRounds(useFinder = false))
        assertEquals(30, baseline(80).searchRounds(useFinder = true))
        assertEquals(5, baseline(30).searchRounds(useFinder = true))
    }
}
