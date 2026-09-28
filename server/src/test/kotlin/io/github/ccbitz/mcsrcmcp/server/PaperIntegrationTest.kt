package io.github.ccbitz.mcsrcmcp.server

import io.github.ccbitz.mcsrcmcp.cache.BlobStore
import io.github.ccbitz.mcsrcmcp.cache.VersionListEntry
import io.github.ccbitz.mcsrcmcp.cache.VersionResolver
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.nio.file.Files

/**
 * The dev-bundle pipeline against the real repos (repo.papermc.io, repo.purpurmc.org) and
 * piston-meta, end to end for every registered fork: dev-bundle resolution, bundle download,
 * paperclip bsdiff apply, indexing of the mojang-mapped fork jar, and one real decompile of a
 * fork-authored class. Heavy (tens of MB, minutes), so it only runs when asked to:
 *
 *   MCSRC_INTEGRATION=1 ./gradlew :server:test --tests '*PaperIntegrationTest*'
 *
 * Forks trail Mojang - Folia may have no build for the current latest release - so each fork runs
 * against the newest release that fork actually publishes a build for.
 */
@EnabledIfEnvironmentVariable(named = "MCSRC_INTEGRATION", matches = "1")
class PaperIntegrationTest {
    @Test
    fun `prepares every fork's sources and decompiles a fork class`() = runBlocking {
        val cacheRoot = Files.createTempDirectory("mcsrc-fork-e2e")
        val fetcher = HttpBlobFetcher()
        val blobStore = BlobStore(cacheRoot.resolve("blobs"))
        val metadata = VersionMetadataCache(fetcher, cacheRoot)
        val manifest = metadata.manifest()
        val releases = manifest.versions.filter { it.type == "release" }.sortedByDescending { it.releaseTime }

        for (variant in Variants.ALL) {
            val repoUrl = variant.devBundleRepository ?: continue
            val devBundles = DevBundleRepository(variant.id, repoUrl, fetcher, cacheRoot.resolve("meta"))
            val builder = DevBundleWorkspaceBuilder(variant.id, blobStore, fetcher, cacheRoot, devBundles)

            // Newest release this fork has a build for - the fork's own version list is ground truth.
            val bundle = releases.firstNotNullOfOrNull { release ->
                runCatching { devBundles.resolve(release.id, null) }.getOrNull()
            } ?: error("${variant.id} publishes no build for any known release - is its repository URL right?")

            val version = VersionResolver.resolve(devBundleMinecraftVersion(bundle.version)!!, manifest.versions)
            val detail = metadata.detail(version)
            assertNotNull(detail.downloads.server, "${variant.id}: ${version.id} needs a server artifact to binpatch")

            val workspaceId = "${variant.id}/${bundle.version}"
            val workspace = builder.build(WorkspaceRequest(variant.id, null, workspaceId, bundle), version, detail)

            assertTrue(workspace.indexData.classes().keys.any { it.startsWith("net/minecraft/") }, "$workspaceId: no vanilla classes")
            val forkClass = workspace.indexData.classes().keys.first { it.startsWith("org/bukkit/craftbukkit/") }
            assertTrue(workspace.assets.isEmpty(), "$workspaceId: forks ship no client assets")

            // The fork's own code decompiles with tokens, off the real patched jar.
            val decompiled = DecompileService.decompileWithTokens(workspace.remappedClasses, forkClass, cacheDir = workspace.sourceCacheDir)
            assertTrue(decompiled.source.contains("class "), "$workspaceId: expected Java source for $forkClass")
            assertTrue(decompiled.tokens.isNotEmpty(), "$workspaceId: expected tokens for $forkClass")
            assertTrue(Files.list(workspace.sourceCacheDir!!).use { s -> s.count() } > 0, "$workspaceId: source cache stayed empty")

            // A warm rebuild hits the derived cache with zero fetches.
            val warm = builder.build(WorkspaceRequest(variant.id, null, workspaceId, bundle), version, detail)
            assertTrue(warm.remappedClasses.containsKey(forkClass), "$workspaceId: warm rebuild lost the index")

            CacheEviction.evictVersion(cacheRoot, workspaceId)
            assertFalse(Files.exists(cacheRoot.resolve("derived").resolve(workspaceId)))
        }
    }
}
