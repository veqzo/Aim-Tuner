package de.yuuto.aimtune.app.update

import com.sun.net.httpserver.HttpServer
import de.yuuto.aimtune.core.update.Version
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The parts of the in-app update: which files of a release may be used, the download with its checks, the install kind and the flow. */
class UpdateInstallTest {
    @TempDir
    lateinit var dir: Path

    private var server: HttpServer? = null

    @AfterTest
    fun stop() {
        server?.stop(0)
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private val prefix = "https://github.com/${UpdateChecker.REPO}/releases/download/v0.5.0/"

    private fun assetJson(name: String, url: String = prefix + name, size: Long = 10, digest: String = "sha256:" + "a".repeat(64)) =
        """{"name":"$name","browser_download_url":"$url","size":$size,"digest":"$digest"}"""

    private fun parse(vararg assets: String) = UpdateChecker().parse(
        """{"tag_name":"v0.5.0","html_url":"https://github.com/${UpdateChecker.REPO}/releases/tag/v0.5.0","draft":false,"prerelease":false,"assets":[${assets.joinToString(",")}]}""",
    )

    @Test
    fun `a release lists its installer and zip when they are files of this repository with a size and a checksum`() {
        val release = checkNotNull(parse(assetJson("AimTune-v0.5.0-setup.exe"), assetJson("AimTune-v0.5.0-windows.zip")))
        assertEquals("AimTune-v0.5.0-setup.exe", release.installer?.name)
        assertEquals("AimTune-v0.5.0-windows.zip", release.portableZip?.name)
        assertEquals("a".repeat(64), release.installer?.sha256)
    }

    @Test
    fun `a file from elsewhere, without a checksum, of no size or with an odd name is not offered`() {
        val release = checkNotNull(
            parse(
                assetJson("AimTune-v0.5.0-setup.exe", url = "https://evil.example/releases/download/v0.5.0/AimTune-v0.5.0-setup.exe"),
                assetJson("AimTune-v0.5.0-windows.zip", digest = "md5:123"),
                assetJson("../setup.exe"),
                assetJson("other-setup.exe", size = 0),
                assetJson("other2-setup.exe", url = prefix + "different.exe"),
            ),
        )
        assertNull(release.installer)
        assertNull(release.portableZip)
        assertTrue(release.assets.isEmpty())
    }

    private fun serve(bytes: ByteArray, status: Int = 200): UpdateDownloader {
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        http.createContext("/file") { exchange ->
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        http.start()
        server = http
        return UpdateDownloader(dir.resolve("update"), trustedHost = { it == "127.0.0.1" }, requireHttps = false)
    }

    private fun assetFor(bytes: ByteArray, size: Long = bytes.size.toLong(), digest: String = sha256(bytes)) =
        UpdateAsset("AimTune-v0.5.0-setup.exe", "http://127.0.0.1:${server!!.address.port}/file", size, digest)

    @Test
    fun `a file with the announced size and checksum is kept under its name`() {
        val bytes = ByteArray(200_000) { (it % 251).toByte() }
        val downloader = serve(bytes)
        var last = 0L
        val file = downloader.download(assetFor(bytes)) { done, _ -> last = done }
        assertTrue(Files.isRegularFile(file))
        assertEquals(bytes.size.toLong(), last)
        assertEquals(sha256(bytes), sha256(Files.readAllBytes(file)))
        assertEquals("AimTune-v0.5.0-setup.exe", file.fileName.toString())
        assertEquals(listOf(file.fileName.toString()), Files.list(file.parent).use { l -> l.map { it.fileName.toString() }.toList() }, "the folder of a download holds only the file")
        downloader.verify(file, assetFor(bytes))
        Files.write(file, ByteArray(bytes.size) { 9 })
        assertFailsWith<UpdateDownloadException> { downloader.verify(file, assetFor(bytes)) }
    }

    @Test
    fun `a wrong checksum, a larger or smaller file and a bad status leave no file`() {
        val bytes = ByteArray(1_000) { 7 }
        val downloader = serve(bytes)
        for (asset in listOf(assetFor(bytes, digest = "0".repeat(64)), assetFor(bytes, size = 500), assetFor(bytes, size = 2_000))) {
            assertFailsWith<UpdateDownloadException> { downloader.download(asset) }
            assertEquals(0, Files.list(dir.resolve("update")).use { it.count() }, "nothing is left behind")
        }
    }

    @Test
    fun `a download from a host that is not GitHub's or not over https is refused`() {
        val bytes = ByteArray(10) { 1 }
        serve(bytes)
        val strict = UpdateDownloader(dir.resolve("update"), requireHttps = false)
        assertFailsWith<UpdateDownloadException> { strict.download(assetFor(bytes)) }
        val noTls = UpdateDownloader(dir.resolve("update"), trustedHost = { true })
        assertFailsWith<UpdateDownloadException> { noTls.download(assetFor(bytes)) }
        assertTrue(UpdateDownloader.isGitHubHost("objects.githubusercontent.com"))
        assertTrue(UpdateDownloader.isGitHubHost("github.com"))
        assertFalse(UpdateDownloader.isGitHubHost("evilgithub.com"))
        assertFalse(UpdateDownloader.isGitHubHost("github.com.evil.example"))
    }

    @Test
    fun `the install kind follows where the launcher lives`() {
        val programFiles = dir.resolve("Program Files")
        assertEquals(InstallKind.INSTALLED, UpdateApplier.kindOf(programFiles.resolve("AimTune").resolve("AimTune.exe"), listOf(programFiles)))
        assertEquals(InstallKind.PORTABLE, UpdateApplier.kindOf(dir.resolve("Downloads").resolve("AimTune").resolve("AimTune.exe"), listOf(programFiles)))
        assertEquals(InstallKind.UNKNOWN, UpdateApplier.kindOf(dir.resolve("jdk").resolve("bin").resolve("java.exe"), listOf(programFiles)))
        assertEquals(InstallKind.UNKNOWN, UpdateApplier.kindOf(null, listOf(programFiles)))
        assertEquals(InstallKind.PORTABLE, UpdateApplier.kindOf(dir.resolve("Program Files evil").resolve("AimTune").resolve("AimTune.exe"), listOf(programFiles)), "a name that only starts like the folder")
    }

    private val release = ReleaseInfo(
        Version(0, 5, 0), "https://github.com/x/y/releases/tag/v0.5.0",
        listOf(UpdateAsset("AimTune-v0.5.0-setup.exe", prefix + "AimTune-v0.5.0-setup.exe", 10, "a".repeat(64)), UpdateAsset("AimTune-v0.5.0-windows.zip", prefix + "AimTune-v0.5.0-windows.zip", 10, "b".repeat(64))),
    )

    private fun flow(kind: InstallKind, latest: ReleaseInfo? = release, states: MutableList<UpdateState>, applied: MutableList<Pair<InstallKind, Path>>, finished: () -> Unit = {}): UpdateFlow {
        val bytes = ByteArray(10) { 3 }
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        http.createContext("/file") { exchange ->
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        http.start()
        server = http
        val hash = sha256(bytes)
        val local = "http://127.0.0.1:${http.address.port}/file"
        val served = latest?.copy(assets = latest.assets.map { it.copy(url = local, sha256 = hash) })
        return UpdateFlow(
            workDir = dir.resolve("update"), kind = kind, launcher = dir.resolve("AimTune.exe"), latest = { served },
            downloader = UpdateDownloader(dir.resolve("update"), trustedHost = { true }, requireHttps = false),
            apply = { k, file, _, _ -> applied += k to file }, onState = { states += it }, onFinished = finished,
        )
    }

    @Test
    fun `an installed copy runs the installer, a portable one the zip, and the app is told to close`() {
        for ((kind, name) in listOf(InstallKind.INSTALLED to "AimTune-v0.5.0-setup.exe", InstallKind.PORTABLE to "AimTune-v0.5.0-windows.zip")) {
            val states = mutableListOf<UpdateState>()
            val applied = mutableListOf<Pair<InstallKind, Path>>()
            var finished = false
            flow(kind, states = states, applied = applied) { finished = true }.run(release)
            assertEquals(listOf(kind), applied.map { it.first })
            assertEquals(name, applied.single().second.fileName.toString())
            assertTrue(finished)
            assertTrue(states.last() is UpdateState.Applying, "$states")
        }
    }

    @Test
    fun `nothing runs when the release is gone, older, has no file for this install or the run is not packaged`() {
        val cases = listOf(
            InstallKind.INSTALLED to null,
            InstallKind.INSTALLED to release.copy(version = Version(0, 4, 0)),
            InstallKind.INSTALLED to release.copy(assets = emptyList()),
            InstallKind.UNKNOWN to release,
        )
        for ((kind, latest) in cases) {
            val states = mutableListOf<UpdateState>()
            val applied = mutableListOf<Pair<InstallKind, Path>>()
            flow(kind, latest, states, applied).run(release)
            assertTrue(applied.isEmpty())
            assertTrue(states.last() is UpdateState.Failed, "$kind $latest: $states")
        }
    }
}
