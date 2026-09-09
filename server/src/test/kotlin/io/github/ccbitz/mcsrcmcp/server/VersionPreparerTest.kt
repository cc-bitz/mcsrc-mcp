package io.github.ccbitz.mcsrcmcp.server

import io.github.ccbitz.mcsrcmcp.cache.DownloadArtifact
import io.github.ccbitz.mcsrcmcp.cache.VersionDetail
import io.github.ccbitz.mcsrcmcp.cache.VersionDownloads
import io.github.ccbitz.mcsrcmcp.cache.VersionListEntry
import io.github.ccbitz.mcsrcmcp.core.IndexData
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Duration

class VersionPreparerTest {
    private val version = VersionListEntry("1.99-test", "release", "https://example.invalid/v.json",
        "2024-01-01T00:00:00+00:00", "2024-01-01T00:00:00+00:00", "0".repeat(40))
    private val detail = VersionDetail(VersionDownloads(
        client = DownloadArtifact("https://example.invalid/client.jar", "0".repeat(40), 1),
    ))

    private class CountingBuilder : VersionWorkspaceBuilder(
        blobStore = io.github.ccbitz.mcsrcmcp.cache.BlobStore(java.nio.file.Files.createTempDirectory("mcsrc-mcp-test")),
        fetcher = object : BlobFetcher {
            override suspend fun fetch(url: String): ByteArray = ByteArray(0)
        },
    ) {
        var buildCount = 0
            private set

        override suspend fun build(version: VersionListEntry, detail: VersionDetail): VersionWorkspace {
            buildCount++
            return VersionWorkspace(version.id, IndexData.empty(), null, emptyMap(), io.github.ccbitz.mcsrcmcp.core.Indexer(), emptyMap(), EmptyAssetSource, null)
        }
    }

    @Test
    fun `first prepare builds and caches the workspace`() = runTest {
        val cache = WorkspaceCache()
        val builder = CountingBuilder()
        val preparer = VersionPreparer(cache, builder, this)

        val result = preparer.prepare(version, detail)

        assertTrue(result is PrepareVersionResult.Ready)
        assertEquals(1, builder.buildCount)
        assertNotNull(cache.get("1.99-test"))
    }

    @Test
    fun `second prepare for an already-warm version does not rebuild`() = runTest {
        val cache = WorkspaceCache()
        val builder = CountingBuilder()
        val preparer = VersionPreparer(cache, builder, this)

        val first = preparer.prepare(version, detail)
        println("first result: $first, cacheDir=${cache.get("1.99-test")?.cacheDir}")
        val second = preparer.prepare(version, detail)

        println("second result: $second")
        assertTrue(second is PrepareVersionResult.Ready)
        assertEquals(1, builder.buildCount)
    }

    @Test
    fun `pollUntilReady returns immediately without reporting progress when already ready`() = runTest {
        val progress = mutableListOf<Int>()

        val result = pollUntilReady(
            pollInterval = Duration.ofSeconds(1),
            pollTimeout = Duration.ofSeconds(10),
            onProgress = { progress.add(it) },
        ) { PrepareVersionResult.Ready("1.99-test") }

        assertTrue(result is PrepareVersionResult.Ready)
        assertEquals(emptyList<Int>(), progress)
    }

    @Test
    fun `pollUntilReady reports each new percent and returns ready once the build finishes`() = runTest {
        val steps = listOf(
            PrepareVersionResult.Preparing("1.99-test", 0),
            PrepareVersionResult.Preparing("1.99-test", 0),
            PrepareVersionResult.Preparing("1.99-test", 50),
            PrepareVersionResult.Ready("1.99-test"),
        ).iterator()
        val progress = mutableListOf<Int>()

        val result = pollUntilReady(
            pollInterval = Duration.ofSeconds(1),
            pollTimeout = Duration.ofSeconds(10),
            onProgress = { progress.add(it) },
        ) { steps.next() }

        assertTrue(result is PrepareVersionResult.Ready)
        assertEquals(listOf(0, 50), progress)
    }

    @Test
    fun `clear drops a warm version so the next prepare rebuilds it`() = runTest {
        val cache = WorkspaceCache()
        val builder = CountingBuilder()
        val preparer = VersionPreparer(cache, builder, this)
        preparer.prepare(version, detail)
        assertEquals(1, builder.buildCount)

        preparer.clear("1.99-test")

        assertNull(cache.get("1.99-test"))
        val result = preparer.prepare(version, detail)
        assertTrue(result is PrepareVersionResult.Ready)
        assertEquals(2, builder.buildCount, "clear should make the next prepare() do real work again")
    }

    @Test
    fun `clear of a version never prepared does nothing`() = runTest {
        val cache = WorkspaceCache()
        val preparer = VersionPreparer(cache, CountingBuilder(), this)

        assertDoesNotThrow { preparer.clear("never-prepared") }
    }

    @Test
    fun `clearAll drops every warm version`() = runTest {
        val cache = WorkspaceCache()
        val builder = CountingBuilder()
        val preparer = VersionPreparer(cache, builder, this)
        val otherVersion = version.copy(id = "1.98-test")
        preparer.prepare(version, detail)
        preparer.prepare(otherVersion, detail)
        assertEquals(2, builder.buildCount)

        preparer.clearAll()

        assertNull(cache.get("1.99-test"))
        assertNull(cache.get("1.98-test"))
        preparer.prepare(version, detail)
        assertEquals(3, builder.buildCount, "clearAll should make prepare() do real work again")
    }

    @Test
    fun `pollUntilReady gives up after the timeout and returns the last known state`() = runTest {
        val result = pollUntilReady(
            pollInterval = Duration.ofSeconds(1),
            pollTimeout = Duration.ofSeconds(5),
            onProgress = {},
        ) { PrepareVersionResult.Preparing("1.99-test", 7) }

        assertTrue(result is PrepareVersionResult.Preparing)
        assertEquals(7, (result as PrepareVersionResult.Preparing).percent)
    }
}
