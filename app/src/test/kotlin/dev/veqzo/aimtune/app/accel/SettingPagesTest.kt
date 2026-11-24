package dev.veqzo.aimtune.app.accel

import dev.veqzo.aimtune.core.accel.settings.PassiveResult
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingPagesTest {
    private val en = bundle("messages.properties")
    private val de = bundle("messages_de.properties")
    private val analyses = sampleSettingsAnalyses()

    private fun pages(a: SettingsAnalyses? = analyses, profile: Boolean = true, tr: Translate = en) = SettingPages(tr, a, if (profile) sampleProfile() else null)

    @Test
    fun `every setting has a row and a page in both languages`() {
        for (tr in listOf(en, de)) {
            val pages = pages(tr = tr)
            assertEquals(RawAccelSetting.entries, pages.rows().map { it.setting })
            for (setting in RawAccelSetting.entries) {
                val page = pages.page(setting)
                listOf(page.name, page.what, page.formula, page.current, page.recommended, page.why).forEach { assertTrue(it.isNotBlank(), "${setting.id}: $page") }
                assertEquals(setting.kind == SettingKind.INFO, page.notMeasured != null, setting.id)
            }
        }
    }

    @Test
    fun `badges follow the kind and the u d row shows the number but never recommends`() {
        val rows = pages().rows().associateBy { it.setting }
        assertEquals("Analysis", rows.getValue(RawAccelSetting.ROTATION).badge)
        assertEquals("Info", rows.getValue(RawAccelSetting.U_D).badge)
        val ud = pages(analyses.copy(ud = PassiveResult.Recommend(0.85, 0.7, 0.95, 90)))
        assertEquals("Measured 0.85 · for information only", ud.state(RawAccelSetting.U_D))
        assertTrue("No recommendation" in ud.page(RawAccelSetting.U_D).recommended)
        assertNull(ud.page(RawAccelSetting.U_D).exportable)
    }

    @Test
    fun `an analysis row says recommended, no change or not enough data with the counts`() {
        val p = pages()
        assertEquals("Recommended -1.5° · from 112 reaches", p.state(RawAccelSetting.ROTATION))
        assertEquals("No change needed · from 80 reaches", p.state(RawAccelSetting.L_R))
        val rotation = p.page(RawAccelSetting.ROTATION)
        assertEquals("-1.5°", rotation.recommended)
        assertEquals(listOf("Result -1.5° (range -2.3° to -0.8°) from 112 reaches"), rotation.result)
        val thin = pages(analyses.copy(yx = PassiveResult.NotEnoughData(9, 30)))
        assertEquals("Not enough data: 9 of 30 reaches", thin.state(RawAccelSetting.Y_X))
        assertEquals(listOf("Not enough data: 9 of 30 reaches"), thin.page(RawAccelSetting.Y_X).result)
        assertTrue("Keep 1.00" in thin.page(RawAccelSetting.Y_X).recommended)
    }

    @Test
    fun `a recommendation can be ticked only for the passive rows and only with an imported profile`() {
        assertEquals(-1.5, pages().page(RawAccelSetting.ROTATION).exportable)
        assertEquals(1.08, pages().page(RawAccelSetting.Y_X).exportable)
        assertNull(pages().page(RawAccelSetting.L_R).exportable, "no change, nothing to add")
        assertNull(pages(profile = false).page(RawAccelSetting.ROTATION).exportable)
        assertEquals(setOf(RawAccelSetting.ROTATION, RawAccelSetting.Y_X, RawAccelSetting.L_R), RawAccelSetting.entries.filter { it.passive }.toSet())
    }

    @Test
    fun `info rows show the replay rows, the speed percentiles and the recommendations of the decisions`() {
        val p = pages()
        val snap = p.page(RawAccelSetting.SNAP)
        assertEquals("0° (off)", snap.recommended)
        assertTrue(snap.result.any { "snap 10.0°: +46 %" in it }, snap.result.toString())
        assertEquals(
            listOf("Hand speed over 213000 moving reports, in inches per second:", "p50 2.5   p90 11.3   p95 15.2   p99 23.9"),
            p.page(RawAccelSetting.SPEED_CAP).result,
        )
        assertEquals("0 (off)", p.page(RawAccelSetting.SPEED_CAP).recommended)
        assertEquals("0 for all three", p.page(RawAccelSetting.SMOOTHING).recommended)
        assertEquals("1 and 1 (no stretch)", p.page(RawAccelSetting.STRETCH).recommended)
        assertEquals("Whole, Lp norm 2", p.page(RawAccelSetting.COMPONENT).recommended)
        assertEquals("Automatic: rate 0, constant time interval off", p.page(RawAccelSetting.POLLING).recommended)
    }

    @Test
    fun `the current value comes from the imported profile`() {
        val profile = sampleProfile()
        val p = pages()
        assertEquals("0°", p.page(RawAccelSetting.ROTATION).current)
        assertEquals("1.00", p.page(RawAccelSetting.Y_X).current)
        assertEquals("off", p.page(RawAccelSetting.SNAP).current)
        assertEquals("Whole, Lp norm ${"%.1f".format(Locale.ROOT, profile.speed.lpNorm)}", p.page(RawAccelSetting.COMPONENT).current)
        assertTrue("No profile imported" in pages(profile = false).page(RawAccelSetting.ROTATION).current)
    }

    @Test
    fun `while the analyses are computed the rows say so, and without data snap still recommends zero`() {
        val computing = pages(null)
        assertEquals("Computing…", computing.state(RawAccelSetting.ROTATION))
        assertTrue(computing.page(RawAccelSetting.ROTATION).result.isEmpty())
        val empty = SettingsAnalyses.compute(emptyList())
        assertEquals("Recommended 0° (off)", pages(empty).state(RawAccelSetting.SNAP))
        assertEquals("Not enough data: 0 of 60 reaches", pages(empty).state(RawAccelSetting.ROTATION))
        assertTrue("fewer than 200" in pages(empty).page(RawAccelSetting.SPEED_CAP).result.single())
    }

    @Test
    fun `german texts are used with their own wording`() {
        assertEquals("Empfohlen -1.5° · aus 112 Zielbewegungen", pages(tr = de).state(RawAccelSetting.ROTATION))
        assertEquals("Analyse", pages(tr = de).rows().first().badge)
    }
}
