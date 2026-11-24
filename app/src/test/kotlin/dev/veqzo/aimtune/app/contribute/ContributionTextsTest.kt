package dev.veqzo.aimtune.app.contribute

import java.io.InputStreamReader
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The contribution texts exist in English and German and never promise anonymity. */
class ContributionTextsTest {
    private fun load(name: String): Properties = Properties().apply {
        InputStreamReader(checkNotNull(ContributionTextsTest::class.java.classLoader.getResourceAsStream("assets/i18n/$name")), Charsets.UTF_8).use(::load)
    }

    private val keys = listOf(
        "button", "title", "intro", "voluntary", "steps", "public_title", "public", "create", "working", "ready", "ready_status",
        "show_file", "open_issue", "copy_link", "no_link", "browser_failed", "reveal_failed", "failed", "failed_status", "empty",
    ).map { "contribute.$it" }

    @Test
    fun `every contribution text exists in both languages`() {
        listOf("messages.properties", "messages_de.properties").map(::load).forEach { bundle ->
            keys.forEach { assertTrue(!bundle.getProperty(it).isNullOrBlank(), it) }
        }
    }

    @Test
    fun `the texts say that sharing is public and voluntary and do not claim anonymity`() {
        val en = load("messages.properties")
        val de = load("messages_de.properties")
        assertTrue(en.getProperty("contribute.public").contains("public") && en.getProperty("contribute.public").contains("GitHub account"))
        assertTrue(de.getProperty("contribute.public").contains("öffentlich") && de.getProperty("contribute.public").contains("GitHub-Konto"))
        assertTrue(en.getProperty("contribute.voluntary").contains("never uploads"))
        assertTrue(de.getProperty("contribute.voluntary").contains("lädt nichts hoch"))
        assertFalse(en.getProperty("contribute.public").contains("is anonymous"))
        assertFalse(de.getProperty("contribute.public").contains("ist anonym"))
    }
}
