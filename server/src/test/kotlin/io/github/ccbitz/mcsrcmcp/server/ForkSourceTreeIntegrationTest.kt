package io.github.ccbitz.mcsrcmcp.server

import io.github.ccbitz.mcsrcmcp.cache.BlobStore
import io.github.ccbitz.mcsrcmcp.cache.VersionResolver
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.nio.file.Files
import java.nio.file.Path

/**
 * The whole source-tree pipeline against real artifacts - codebook, mache's decompile, mache's and
 * Paper's patches - for the newest Paper 26.3 build. Heavy (~100MB of downloads, a whole-jar decompile),
 * so it only runs when asked to:
 *
 *   MCSRC_INTEGRATION=1 ./gradlew :server:test --tests '*ForkSourceTreeIntegrationTest*'
 *
 * MCSRC_BUNDLE_ZIP pins a local bundle instead of the newest; with MCSRC_PARITY_TREE also set (a
 * directory of sources paperweight's own tooling produced from that bundle), every class is compared
 * byte for byte.
 */
@EnabledIfEnvironmentVariable(named = "MCSRC_INTEGRATION", matches = "1")
class ForkSourceTreeIntegrationTest {

    @Test
    fun `Paper's patches apply to the tree and its sources carry Paper's comments`() = runBlocking<Unit> {
        val cacheRoot = Files.createTempDirectory("mcsrc-tree-e2e")
        val fetcher = HttpBlobFetcher()
        val blobStore = BlobStore(cacheRoot.resolve("blobs"))
        val metadata = VersionMetadataCache(fetcher, cacheRoot)
        val manifest = metadata.manifest()

        val bundleZip = System.getenv("MCSRC_BUNDLE_ZIP")?.let { Files.readAllBytes(Path.of(it)) } ?: run {
            val paper = Variants.ALL.first { it.id == Variants.PAPER }
            val bundle = DevBundleRepository(paper.id, paper.devBundleRepository!!, fetcher, cacheRoot.resolve("meta")).resolve("26.3", null)
            fetcher.fetch(bundle.zipUrl)
        }
        val config = parseDevBundleConfig(PaperclipPatcher.readZipEntry(bundleZip, "config.json")!!.toString(Charsets.UTF_8))
        val detail = metadata.detail(VersionResolver.resolve(config.minecraftVersion, manifest.versions))
        val server = detail.downloads.server!!
        val serverJar = blobStore.pathIfPresent(server.sha1) ?: blobStore.put(fetcher.fetch(server.url), server.sha1)

        val mache = MacheTreeBuilder(cacheRoot, MavenArtifacts(fetcher, blobStore)).treeFor(config.minecraftVersion, config.mache!!, serverJar)!!
        val result = ForkSourceTree.build(mache, bundleZip, config.patchDir!!, cacheRoot.resolve("pool"))

        assertEquals(emptyList<String>(), result.failed, "patches that didn't apply")
        val level = Files.readString(cacheRoot.resolve("pool").resolve(result.tree.getValue("net/minecraft/world/level/Level") + ".java"))
        assertTrue(level.contains("// Paper - "), "Level has no Paper comments")
        assertTrue(result.tree.keys.any { it.startsWith("org/bukkit/craftbukkit/") }, "no CraftBukkit sources")

        System.getenv("MCSRC_PARITY_TREE")?.let { parity ->
            val root = Path.of(parity)
            // DiffPatch writes the platform's line separator; the tree is always '\n', as on Linux.
            val mismatched = result.tree.filter { (name, key) ->
                val theirs = root.resolve("$name.java")
                Files.exists(theirs) && Files.readString(theirs).replace("\r\n", "\n") != Files.readString(cacheRoot.resolve("pool").resolve("$key.java"))
            }.keys
            assertEquals(emptySet<String>(), mismatched, "classes that differ from paperweight's output")
        }
    }

}
