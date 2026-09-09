package io.github.ccbitz.mcsrcmcp.server

import io.github.ccbitz.mcsrcmcp.cache.BlobStore
import java.nio.file.Path
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir

/**
 * Real-network, real-subprocess verification of the whole get_report pipeline: metadata ->
 * library fetches -> classpath -> net.minecraft.data.Main --reports -> cached tree -> serving.
 * Opt-in (MCSRC_INTEGRATION=1) so CI never pays ~80MB of downloads or a JDK 25 subprocess;
 * run it manually whenever the datagen path changes:
 *
 *   MCSRC_INTEGRATION=1 ./gradlew :server:test --tests '*DatagenIntegrationTest*'
 */
@EnabledIfEnvironmentVariable(named = "MCSRC_INTEGRATION", matches = "1")
class DatagenIntegrationTest {
    @Test
    fun `generates and serves reports for a real version end to end`(@TempDir tmp: Path) = runTest(timeout = 15.minutes) {
        val fetcher = HttpBlobFetcher()
        val metadata = VersionMetadataCache(fetcher, null)
        val version = metadata.manifest().versions.first { it.type == "release" }
        val detail = metadata.detail(version)

        val blobStore = BlobStore(tmp.resolve("blobs"))
        val clientJar = blobStore.put(fetcher.fetch(detail.downloads.client.url), detail.downloads.client.sha1)

        val generator = ReportGenerator(blobStore, fetcher, tmp.resolve("cache-root"), CoroutineScope(SupervisorJob() + Dispatchers.Default))
        val reportsDir = generator.ensureReports(version.id, detail, clientJar, tmp.resolve("derived"))

        assertTrue(ReportGenerator.isComplete(reportsDir))
        val listing = listReportToolLogic(reportsDir)
        assertTrue(listing.entries.any { it.path == "registries.json" }, "expected registries.json in $listing")
        assertTrue(listing.entries.any { it.path == "minecraft" && it.isDirectory })

        val registries = getReportToolLogic(reportsDir, "registries")
        assertTrue(registries.content.contains("minecraft:block"), "registries.json should name the block registry")
        assertTrue(registries.totalLines > 100)

        // A second ensure call must be instant and return the same tree - the cache hit path.
        val again = generator.ensureReports(version.id, detail, clientJar, tmp.resolve("derived"))
        assertTrue(ReportGenerator.isComplete(again))
    }
}
