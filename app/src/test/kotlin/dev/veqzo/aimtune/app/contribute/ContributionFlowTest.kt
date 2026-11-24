package dev.veqzo.aimtune.app.contribute

import dev.veqzo.aimtune.storage.ResearchExportResult
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ContributionFlowTest {
    private val config = ContributionConfig("veqzo/Aim-Tuner", "training-data.yml")
    private val written = ResearchExportResult(Paths.get("out/aimtune-research.json.gz"), 2, 10, 4096)

    private val revealed = mutableListOf<Path>()
    private val opened = mutableListOf<String>()
    private var exports = 0

    private fun flow(
        result: CompletableFuture<Result<ResearchExportResult>> = CompletableFuture.completedFuture(Result.success(written)),
        reveal: Boolean = true,
        browse: Boolean = true,
    ) = ContributionFlow(
        config, "1.0.0",
        export = { exports++; result },
        reveal = { revealed.add(it); reveal },
        browse = { opened.add(it); browse },
    )

    @Test
    fun `nothing happens before a click`() {
        val f = flow()
        assertEquals(ContributionState.Intro, f.state)
        assertFalse(f.poll())
        f.revealFile()
        f.openIssue()
        assertEquals(0, exports)
        assertTrue(revealed.isEmpty() && opened.isEmpty())
    }

    @Test
    fun `each step runs only when asked`() {
        val pending = CompletableFuture<Result<ResearchExportResult>>()
        val f = flow(pending)
        f.start()
        assertEquals(ContributionState.Exporting, f.state)
        f.start()
        assertEquals(1, exports, "a second click while writing starts no second export")
        pending.complete(Result.success(written))
        assertTrue(f.poll())
        val ready = assertIs<ContributionState.Ready>(f.state)
        assertTrue(ready.link.isSuccess)
        assertTrue(opened.isEmpty(), "the browser is not opened by the export")

        f.revealFile()
        assertEquals(listOf(written.file), revealed)
        assertTrue(opened.isEmpty(), "showing the file opens no browser")
        f.openIssue()
        assertEquals(1, opened.size)
        assertTrue(opened.single().startsWith("https://github.com/veqzo/Aim-Tuner/issues/new?"))
        assertFalse(assertIs<ContributionState.Ready>(f.state).browserFailed)
    }

    @Test
    fun `an export failure is shown and nothing is opened`() {
        val f = flow(CompletableFuture.completedFuture(Result.failure(IllegalStateException("disk full"))))
        f.start()
        assertEquals(ContributionState.Failed(FailReason.EXPORT), f.state)
        val thrown = flow(CompletableFuture.failedFuture(RuntimeException("boom")))
        thrown.start()
        assertEquals(ContributionState.Failed(FailReason.EXPORT), thrown.state)
        assertTrue(opened.isEmpty() && revealed.isEmpty())
    }

    @Test
    fun `an empty dataset is not offered for sharing`() {
        val f = flow(CompletableFuture.completedFuture(Result.success(ResearchExportResult(written.file, 0, 0, 40))))
        f.start()
        assertEquals(ContributionState.Failed(FailReason.EMPTY), f.state)
        f.openIssue()
        assertTrue(opened.isEmpty())
    }

    @Test
    fun `browser and explorer failures are reported, also when they throw`() {
        val f = flow(reveal = false, browse = false)
        f.start()
        f.revealFile()
        f.openIssue()
        val ready = assertIs<ContributionState.Ready>(f.state)
        assertTrue(ready.revealFailed && ready.browserFailed)

        val throwing = ContributionFlow(config, "1.0.0", { CompletableFuture.completedFuture(Result.success(written)) }, { error("no explorer") }, { error("no browser") })
        throwing.start()
        throwing.revealFile()
        throwing.openIssue()
        val again = assertIs<ContributionState.Ready>(throwing.state)
        assertTrue(again.revealFailed && again.browserFailed)
    }

    @Test
    fun `cancel goes back to the start at any step and drops a running export`() {
        val pending = CompletableFuture<Result<ResearchExportResult>>()
        val f = flow(pending)
        f.start()
        f.cancel()
        assertEquals(ContributionState.Intro, f.state)
        pending.complete(Result.success(written))
        assertFalse(f.poll(), "a cancelled export is forgotten")
        assertEquals(ContributionState.Intro, f.state)

        val done = flow()
        done.start()
        done.cancel()
        assertEquals(ContributionState.Intro, done.state)
        assertTrue(opened.isEmpty())
    }

    @Test
    fun `the contribution code opens no connection of its own`() {
        val sources = File("src/main/kotlin/dev/veqzo/aimtune/app/contribute").listFiles { f -> f.extension == "kt" }.orEmpty()
        assertTrue(sources.isNotEmpty(), "sources not found from ${File(".").absolutePath}")
        val forbidden = listOf("java.net.http", "HttpClient", "URLConnection", "openConnection", "Socket", "URL(", "Gdx.net.sendHttpRequest")
        sources.forEach { file ->
            val text = file.readText()
            forbidden.forEach { assertFalse(text.contains(it), "${file.name} uses $it") }
        }
    }
}
