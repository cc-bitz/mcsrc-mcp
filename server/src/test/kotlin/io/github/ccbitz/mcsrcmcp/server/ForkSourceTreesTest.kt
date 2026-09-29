package io.github.ccbitz.mcsrcmcp.server

import io.github.ccbitz.mcsrcmcp.cache.BlobStore
import io.github.ccbitz.mcsrcmcp.core.IndexData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ForkSourceTreesTest {

    private object NoNetwork : BlobFetcher {

        override suspend fun fetch(url: String): ByteArray = error("unexpected fetch of $url")

    }

    private fun bundleZip(config: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("config.json"))
            zip.write(config.toByteArray())
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    private fun trees(dir: Path, scope: CoroutineScope) =
        ForkSourceTrees(MacheTreeBuilder(dir, MavenArtifacts(NoNetwork, BlobStore(dir.resolve("blobs")))), NoNetwork, scope)

    private fun sourcesFor(trees: ForkSourceTrees, dir: Path, bundleZip: ByteArray?): ClassSources {
        val unit = Files.createDirectories(dir.resolve("unit"))
        val pool = Files.createDirectories(dir.resolve("pool"))
        return trees.sourcesFor(
            label = "paper/test",
            unitDir = unit,
            poolDir = pool,
            treeDir = dir.resolve("trees"),
            classes = emptyMap(),
            index = IndexData.empty(),
            bundle = DevBundle("26.3.build.1", "https://repo.example/bundle.zip"),
            mcVersion = "26.3",
            serverJar = { error("no server jar needed") },
            bundleZip = bundleZip,
        )
    }

    @Test
    fun `a unit with a manifest serves its tree at once`(@TempDir dir: Path) = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob())
        try {
            val unit = Files.createDirectories(dir.resolve("unit"))
            ForkSourceTree.writeManifest(unit, mapOf("demo/Foo" to "k"))

            val sources = sourcesFor(trees(dir, scope), dir, bundleZip = null) as TreeSources

            assertTrue(sources.isPublished)
        } finally {
            scope.cancel()
        }
    }

    // Pre-mache bundles (and ones with no patches) keep today's decompiles - and say so on disk, so a
    // restart doesn't fetch the bundle again just to find that out.
    @Test
    fun `a bundle without a mache settles on decompiles and records that`(@TempDir dir: Path) = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob())
        try {
            val sources = sourcesFor(trees(dir, scope), dir, bundleZip("""{ "minecraftVersion": "1.20.6" }""")) as TreeSources

            withTimeout(5_000) { sources.awaitSettled() }
            assertEquals(emptyMap<String, String>(), ForkSourceTree.readManifest(dir.resolve("unit")))
        } finally {
            scope.cancel()
        }
    }

    // An index built from the decompiles before the tree existed would disagree with what
    // get_class_source now serves; it has to be rebuilt once the tree is there.
    @Test
    fun `a unit without a tree loses its old search index`(@TempDir dir: Path) = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob())
        try {
            val unit = Files.createDirectories(dir.resolve("unit"))
            Files.writeString(unit.resolve("search-index-v2.txt"), "old")
            Files.writeString(unit.resolve("search-index-v2.toc"), "old")

            sourcesFor(trees(dir, scope), dir, bundleZip("""{ "minecraftVersion": "1.20.6" }"""))

            assertFalse(Files.exists(unit.resolve("search-index-v2.txt")))
            assertFalse(Files.exists(unit.resolve("search-index-v2.toc")))
        } finally {
            scope.cancel()
        }
    }

    // The search index waits for the tree to settle; a build that never ran must still settle it.
    @Test
    fun `a build that never starts still settles`(@TempDir dir: Path) = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob())
        scope.cancel()

        val sources = sourcesFor(trees(dir, scope), dir, bundleZip("""{ "minecraftVersion": "26.3" }""")) as TreeSources

        withTimeout(5_000) { sources.awaitSettled() }
    }

    @Test
    fun `a manifest from another tree version reads as none`(@TempDir dir: Path) {
        val unit = Files.createDirectories(dir.resolve("unit"))
        Files.writeString(unit.resolve(ForkSourceTree.MANIFEST_FILE), "demo/Foo\tk\n")

        assertEquals(null, ForkSourceTree.readManifest(unit))
    }

}
