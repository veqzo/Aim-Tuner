package dev.veqzo.aimtune.app.update

import com.sun.net.httpserver.HttpServer
import dev.veqzo.aimtune.core.update.Version
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UpdateCheckerTest {
    private var server: HttpServer? = null

    @AfterTest
    fun stop() {
        server?.stop(0)
    }

    /** A local stand-in for api.github.com answering the releases/latest route with [status] and [body]. */
    private fun checkerAnswering(status: Int, body: String): UpdateChecker {
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        http.createContext("/repos/${UpdateChecker.REPO}/releases/latest") { exchange ->
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        http.start()
        server = http
        return UpdateChecker(apiBase = "http://127.0.0.1:${http.address.port}")
    }

    private fun release(tag: String, url: String = "https://github.com/${UpdateChecker.REPO}/releases/tag/$tag", extra: String = "") =
        """{"tag_name":"$tag","html_url":"$url","draft":false,"prerelease":false$extra}"""

    @Test
    fun `a published release is returned with its page`() {
        val latest = checkerAnswering(200, release("v0.2.0")).latest()
        assertEquals(ReleaseInfo(Version(0, 2, 0), "https://github.com/${UpdateChecker.REPO}/releases/tag/v0.2.0"), latest)
    }

    @Test
    fun `a private repository answers 404 and the check just finds nothing`() {
        assertNull(checkerAnswering(404, """{"message":"Not Found"}""").latest())
    }

    @Test
    fun `rate limits, server errors and empty answers find nothing`() {
        listOf(403 to """{"message":"rate limit"}""", 429 to "", 500 to "oops", 200 to "").forEach { (status, body) ->
            assertNull(checkerAnswering(status, body).latest(), "$status")
            server?.stop(0)
        }
    }

    @Test
    fun `odd json, odd tags and foreign links find nothing`() {
        assertNull(checkerAnswering(200, "not json").latest())
        server?.stop(0)
        assertNull(checkerAnswering(200, "[1,2]").latest())
        server?.stop(0)
        assertNull(checkerAnswering(200, release("nightly")).latest())
        server?.stop(0)
        assertNull(checkerAnswering(200, release("v0.2.0", url = "https://evil.example/download")).latest())
        server?.stop(0)
        assertNull(checkerAnswering(200, """{"tag_name":"v9.0.0","html_url":"https://github.com/${UpdateChecker.REPO}/releases","draft":true}""").latest())
        server?.stop(0)
        assertNull(checkerAnswering(200, """{"tag_name":"v9.0.0","html_url":"https://github.com/${UpdateChecker.REPO}/releases","prerelease":true}""").latest())
    }

    @Test
    fun `no server at all finds nothing and does not throw`() {
        val port = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).let { s -> s.address.port.also { s.stop(0) } }
        assertNull(UpdateChecker(apiBase = "http://127.0.0.1:$port").latest())
    }
}
