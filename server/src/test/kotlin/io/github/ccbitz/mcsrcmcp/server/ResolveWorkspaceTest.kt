package io.github.ccbitz.mcsrcmcp.server

import io.github.ccbitz.mcsrcmcp.cache.BlobStore
import io.github.ccbitz.mcsrcmcp.cache.DownloadArtifact
import io.github.ccbitz.mcsrcmcp.cache.VersionDetail
import io.github.ccbitz.mcsrcmcp.cache.VersionDownloads
import io.github.ccbitz.mcsrcmcp.cache.VersionListEntry
import io.github.ccbitz.mcsrcmcp.cache.VersionManifest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// resolveWorkspace used to bounce a cache-miss straight back to the caller with "call
// prepare_version first" - these cover its replacement behavior: preparing the version
// transparently, inline, instead of making every read tool a two-call dance.
class ResolveWorkspaceTest {
    private fun classResourceBytes(internalName: String): ByteArray {
        val stream = javaClass.classLoader.getResourceAsStream("$internalName.class")
            ?: error("fixture class not found on test classpath: $internalName")
        return stream.readBytes()
    }

    private fun sha1(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun buildFixtureJar(): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for (name in listOf("net/minecraft/Animal", "net/minecraft/Dog")) {
                zip.putNextEntry(ZipEntry("$name.class"))
                zip.write(classResourceBytes(name))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private val mappingText = """
        net.minecraft.wolf.Beast -> net.minecraft.Animal:
            1:1:void makeNoise() -> staticSound
        net.minecraft.wolf.Hound -> net.minecraft.Dog:
            2:2:void woof() -> run
    """.trimIndent()

    private class RecordingFetcher(private val bytesByUrl: Map<String, ByteArray>) : BlobFetcher {
        override suspend fun fetch(url: String): ByteArray = bytesByUrl[url] ?: error("unexpected fetch: $url")
    }

    // A no-cacheRoot VersionWorkspaceBuilder resolves synchronously (no on-disk index to build),
    // so prepare() reaches Ready on its first call - lets this test assert the auto-prepare
    // wiring itself without also depending on pollUntilReady's async/timeout behavior, which has
    // its own dedicated tests in VersionPreparerTest.
    @Test
    fun `resolveWorkspace prepares a not-yet-warm version instead of erroring`(@TempDir tempDir: Path) = runTest {
        val jarBytes = buildFixtureJar()
        val mappingBytes = mappingText.toByteArray(StandardCharsets.UTF_8)

        val version = VersionListEntry(
            "1.99-test", "release", "https://example.invalid/1.99-test.json",
            "2024-01-01T00:00:00+00:00", "2024-01-01T00:00:00+00:00", sha1(jarBytes),
        )
        val detail = VersionDetail(
            VersionDownloads(
                client = DownloadArtifact("https://example.invalid/client.jar", sha1(jarBytes), jarBytes.size.toLong()),
                clientMappings = DownloadArtifact("https://example.invalid/client.txt", sha1(mappingBytes), mappingBytes.size.toLong()),
            ),
        )

        val fetcher = RecordingFetcher(mapOf(
            VERSION_MANIFEST_URL to Json.encodeToString(VersionManifest(listOf(version))).toByteArray(StandardCharsets.UTF_8),
            version.url to Json.encodeToString(detail).toByteArray(StandardCharsets.UTF_8),
            "https://example.invalid/client.jar" to jarBytes,
            "https://example.invalid/client.txt" to mappingBytes,
        ))
        val eulaGate = EulaGate(tempDir.resolve("eula.txt"), env = mapOf("MCSRC_MCP_ACCEPT_EULA" to "1"))
        val workspaceCache = WorkspaceCache()
        val versionPreparer = VersionPreparer(workspaceCache, VersionWorkspaceBuilder(BlobStore(tempDir.resolve("blobs")), fetcher), this)

        assertNull(workspaceCache.get("1.99-test"))

        val progress = mutableListOf<Int>()
        val resolved = resolveWorkspace(VersionMetadataCache(fetcher), eulaGate, workspaceCache, versionPreparer, "1.99-test") { progress.add(it) }

        assertTrue(resolved is ResolvedWorkspace.Ok, "expected Ok, got $resolved")
        resolved as ResolvedWorkspace.Ok
        assertEquals("1.99-test", resolved.versionId)
        assertTrue(resolved.workspace.remappedClasses.containsKey("net/minecraft/wolf/Hound"))
        assertNotNull(workspaceCache.get("1.99-test"), "should now be warm for subsequent calls")
    }

    @Test
    fun `resolveWorkspace returns the already-warm workspace without touching the preparer`(@TempDir tempDir: Path) = runTest {
        val jarBytes = buildFixtureJar()
        val version = VersionListEntry(
            "1.99-test", "release", "https://example.invalid/1.99-test.json",
            "2024-01-01T00:00:00+00:00", "2024-01-01T00:00:00+00:00", sha1(jarBytes),
        )
        val detail = VersionDetail(
            VersionDownloads(client = DownloadArtifact("https://example.invalid/client.jar", sha1(jarBytes), jarBytes.size.toLong())),
        )
        val fetcher = RecordingFetcher(mapOf(
            VERSION_MANIFEST_URL to Json.encodeToString(VersionManifest(listOf(version))).toByteArray(StandardCharsets.UTF_8),
        ))
        val eulaGate = EulaGate(tempDir.resolve("eula.txt"), env = mapOf("MCSRC_MCP_ACCEPT_EULA" to "1"))
        val workspaceCache = WorkspaceCache()
        // A VersionPreparer wired to a fetcher that only answers the manifest - if resolveWorkspace
        // tried to (re)prepare an already-cached workspace, VersionWorkspaceBuilder.build would
        // fetch the detail/jar URLs and this fake would fail the test via error().
        val versionPreparer = VersionPreparer(workspaceCache, VersionWorkspaceBuilder(BlobStore(tempDir.resolve("blobs")), fetcher), this)
        val prewarmedWorkspace = VersionWorkspace("1.99-test", io.github.ccbitz.mcsrcmcp.core.IndexData.empty(), null, emptyMap(), io.github.ccbitz.mcsrcmcp.core.Indexer(), emptyMap(), EmptyAssetSource, null)
        workspaceCache.put("1.99-test", prewarmedWorkspace)

        val resolved = resolveWorkspace(VersionMetadataCache(fetcher), eulaGate, workspaceCache, versionPreparer, "1.99-test")

        assertTrue(resolved is ResolvedWorkspace.Ok, "expected Ok, got $resolved")
        assertSame(prewarmedWorkspace, (resolved as ResolvedWorkspace.Ok).workspace)
    }
}
