package io.github.ccbitz.mcsrcmcp.server

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
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

// The manifest was previously fetched on every single tool call - resolveWorkspace asks for it
// before it checks whether the workspace is already warm. These pin the caching that replaced that.
class VersionMetadataCacheTest {
    private val version = VersionListEntry(
        "1.99-test", "release", "https://example.invalid/1.99-test.json",
        "2024-01-01T00:00:00+00:00", "2024-01-01T00:00:00+00:00", "abc123",
    )
    private val detail = VersionDetail(
        VersionDownloads(client = DownloadArtifact("https://example.invalid/client.jar", "abc123", 1L)),
    )
    private val manifestBytes = Json.encodeToString(VersionManifest(listOf(version))).toByteArray(StandardCharsets.UTF_8)
    private val detailBytes = Json.encodeToString(detail).toByteArray(StandardCharsets.UTF_8)

    private class MutableClock(var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = now
    }

    private inner class CountingFetcher(
        private val etag: String? = null,
        private val failAfter: Int = Int.MAX_VALUE,
    ) : BlobFetcher {
        var bodyFetches = 0
            private set
        var conditionalCalls = 0
            private set
        var lastEtagSent: String? = null
            private set

        override suspend fun fetch(url: String): ByteArray {
            bodyFetches++
            if (bodyFetches > failAfter) throw IOException("network down")
            return if (url == VERSION_MANIFEST_URL) manifestBytes else detailBytes
        }

        override suspend fun fetchIfNoneMatch(url: String, etag: String?): ConditionalFetch {
            conditionalCalls++
            lastEtagSent = etag
            if (conditionalCalls > failAfter) throw IOException("network down")
            if (etag != null && etag == this.etag) return ConditionalFetch.NotModified
            bodyFetches++
            return ConditionalFetch.Body(manifestBytes, this.etag)
        }
    }

    @Test
    fun `repeated calls inside the ttl hit the network once`() = runTest {
        val fetcher = CountingFetcher()
        val cache = VersionMetadataCache(fetcher, cacheDir = null, clock = MutableClock(Instant.EPOCH))

        repeat(20) { assertEquals(1, cache.manifest().versions.size) }

        assertEquals(1, fetcher.conditionalCalls, "20 tool calls must not mean 20 manifest fetches")
    }

    @Test
    fun `past the ttl it revalidates with the stored etag and a 304 costs no body`() = runTest {
        val clock = MutableClock(Instant.EPOCH)
        val fetcher = CountingFetcher(etag = "W/\"v1\"")
        val cache = VersionMetadataCache(fetcher, cacheDir = null, ttl = Duration.ofMinutes(15), clock = clock)

        cache.manifest()
        clock.now = Instant.EPOCH.plus(Duration.ofHours(1))
        cache.manifest()

        assertEquals(2, fetcher.conditionalCalls)
        assertEquals("W/\"v1\"", fetcher.lastEtagSent, "should have revalidated rather than re-downloaded blind")
        assertEquals(1, fetcher.bodyFetches, "the 304 must not have transferred a body")
    }

    // Every version already decompiled on disk is answerable without Mojang; refusing to serve them
    // because a refresh failed would make the cache pointless.
    @Test
    fun `a failed refresh falls back to the stale manifest`() = runTest {
        val clock = MutableClock(Instant.EPOCH)
        val fetcher = CountingFetcher(failAfter = 1)
        val cache = VersionMetadataCache(fetcher, cacheDir = null, ttl = Duration.ofMinutes(15), clock = clock)

        cache.manifest()
        clock.now = Instant.EPOCH.plus(Duration.ofHours(1))

        assertEquals(1, cache.manifest().versions.size, "should have served the stale copy")
    }

    @Test
    fun `a failure with nothing cached surfaces rather than being swallowed`() = runTest {
        val cache = VersionMetadataCache(CountingFetcher(failAfter = 0), cacheDir = null)

        val failure = runCatching { cache.manifest() }.exceptionOrNull()

        assertTrue(failure is IOException, "expected the fetch failure to surface, got $failure")
    }

    @Test
    fun `a fresh cache reuses the manifest left on disk by a previous process`(@TempDir tempDir: Path) = runTest {
        val clock = MutableClock(Instant.EPOCH)
        VersionMetadataCache(CountingFetcher(), tempDir, clock = clock).manifest()

        val afterRestart = CountingFetcher()
        val reloaded = VersionMetadataCache(afterRestart, tempDir, clock = clock).manifest()

        assertEquals(1, reloaded.versions.size)
        assertEquals(0, afterRestart.conditionalCalls, "a restart inside the ttl should not touch the network")
    }

    @Test
    fun `version details are cached on disk and never refetched`(@TempDir tempDir: Path) = runTest {
        val fetcher = CountingFetcher()
        val cache = VersionMetadataCache(fetcher, tempDir)

        cache.detail(version)
        val before = fetcher.bodyFetches
        cache.detail(version)
        VersionMetadataCache(CountingFetcher(), tempDir).detail(version)

        assertEquals(before, fetcher.bodyFetches, "detail URLs are content-addressed - one fetch, ever")
    }
}
