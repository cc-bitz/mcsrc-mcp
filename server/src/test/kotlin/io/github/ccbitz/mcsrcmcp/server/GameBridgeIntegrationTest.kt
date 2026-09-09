package io.github.ccbitz.mcsrcmcp.server

import io.github.ccbitz.mcsrcmcp.cache.BlobStore
import io.github.ccbitz.mcsrcmcp.cache.VersionDetail
import java.nio.file.Path
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir

/**
 * Real-network, real-subprocess verification of the game bridge: metadata -> library fetches ->
 * sidecar JVM (JDK 25 for 26.2's classes) -> SharedConstants/Bootstrap boot -> reflective static
 * read -> bounded JSON -> per-query cache. Opt-in like DatagenIntegrationTest:
 *
 *   MCSRC_INTEGRATION=1 ./gradlew :server:test --tests '*GameBridgeIntegrationTest*'
 */
@EnabledIfEnvironmentVariable(named = "MCSRC_INTEGRATION", matches = "1")
class GameBridgeIntegrationTest {
    @Test
    fun `reads a static field from the booted game end to end`(@TempDir tmp: Path) = runTest(timeout = 10.minutes) {
        val fetcher = HttpBlobFetcher()
        val metadata = VersionMetadataCache(fetcher, null)
        val version = metadata.manifest().versions.first { it.type == "release" }
        val detail = metadata.detail(version)

        val blobStore = BlobStore(tmp.resolve("blobs"))
        val clientJar = blobStore.put(fetcher.fetch(detail.downloads.client.url), detail.downloads.client.sha1)
        val derivedDir = tmp.resolve("derived")

        val bridge = GameBridge(blobStore, fetcher, tmp.resolve("cache-root"))
        suspend fun read() = bridge.readStaticField(
            version.id, detail, clientJar, derivedDir,
            "net.minecraft.world.level.block.ComposterBlock", "COMPOSTABLES",
        )

        val value = read()
        // Registry values collapse to their ids; floats come through as JSON numbers. If the
        // bootstrap contract drifted (COMPOSTABLES filled by a method Bootstrap no longer calls),
        // this fails with an empty {} and the fix is a 'call' op before the read.
        assertTrue(value.contains("minecraft:"), "expected registry ids in COMPOSTABLES, got: ${value.take(300)}")
        assertTrue(value.length > 100, "expected a populated compostables map, got: ${value.take(300)}")

        // The per-query cache: after killing the resident JVM, the same read still answers -
        // from disk, not from a process.
        bridge.clearAll()
        assertEquals(value, read())
    }
}
