package dev.veqzo.aimtune.app.i18n

import dev.veqzo.aimtune.app.accel.RawAccelSetting
import dev.veqzo.aimtune.app.accel.SettingKind
import dev.veqzo.aimtune.core.scenario.ScenarioKind
import java.io.File
import java.io.InputStreamReader
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Keeps the English and German bundles in step and every key used in the code defined. */
class BundlesTest {
    private fun load(name: String): Map<String, String> {
        val stream = checkNotNull(javaClass.classLoader.getResourceAsStream("assets/i18n/$name")) { "missing bundle $name" }
        val properties = Properties().apply { InputStreamReader(stream, Charsets.UTF_8).use(::load) }
        return properties.entries.associate { (k, v) -> k as String to v as String }
    }

    private val english = load("messages.properties")
    private val german = load("messages_de.properties")
    private val placeholder = Regex("""\{(\d+)}""")

    private fun placeholders(text: String) = placeholder.findAll(text).map { it.groupValues[1] }.toSortedSet()

    @Test
    fun `both languages define the same keys`() {
        assertEquals(english.keys.sorted(), german.keys.sorted())
    }

    @Test
    fun `no text is empty and both languages use the same placeholders`() {
        english.forEach { (key, en) ->
            val de = german.getValue(key)
            assertTrue(en.isNotBlank() && de.isNotBlank(), "empty text for $key")
            assertEquals(placeholders(en), placeholders(de), "placeholders of $key differ")
        }
    }

    @Test
    fun `every key the code refers to exists`() {
        val keyLike = Regex(""""((?:hub|accel|refine|between|common|hud|menu|metric|onboarding|pretest|results|round|scenario|settings|setup|history|crosshair|update|share|contribute)\.[a-z_.]+)"""")
        val sources = File("src/main/kotlin").walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue(sources.isNotEmpty(), "source folder not found from ${File(".").absolutePath}")
        val used = sources.flatMap { file -> keyLike.findAll(file.readText()).map { it.groupValues[1] to file.name }.toList() }
        // "settings.json" (the export file name) looks like a settings.* key but is a file name.
        val missing = used.filter { (key, _) -> key !in english && !key.endsWith(".json") }
        assertTrue(missing.isEmpty(), "keys without a text: $missing")
    }

    @Test
    fun `computed keys exist`() {
        ScenarioKind.entries.forEach {
            assertTrue("scenario.${it.id}" in english && "scenario.${it.id}.round" in english, "scenario ${it.id}")
            assertTrue("hud.instruction.${it.id}" in english, "instruction ${it.id}")
        }
        listOf("date", "game", "rounds", "cm", "confidence", "type").forEach { assertTrue("history.col_$it" in english, it) }
    }

    @Test
    fun `every Raw Accel setting has its texts`() {
        RawAccelSetting.entries.forEach { setting ->
            val keys = listOf("name", "what", "formula", "why") + if (setting.kind == SettingKind.INFO) listOf("recommended", "not_measured") else emptyList()
            keys.forEach { assertTrue("hub.set.${setting.id}.$it" in english, "hub.set.${setting.id}.$it") }
        }
    }

    @Test
    fun `languages are found by their stored code`() {
        assertEquals(Language.GERMAN, Language.fromCode("de"))
        assertEquals(Language.ENGLISH, Language.fromCode("en"))
        assertEquals(Language.ENGLISH, Language.fromCode(null))
        assertEquals(Language.ENGLISH, Language.fromCode("fr"))
    }
}
