package io.github.ccbitz.mcsrcmcp.server

import io.github.ccbitz.mcsrcmcp.core.IndexData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.cancellation.CancellationException

/**
 * Gives each fork build unit its [TreeSources]: the tree at once when the unit already has one, and
 * otherwise decompiles right away while the tree builds in the background - a first read never waits
 * on the mache decompile. A bundle without a mache, or with one the server can't build, settles on
 * decompiles and records that in the unit, so it isn't retried every start.
 */
class ForkSourceTrees(
    private val macheTrees: MacheTreeBuilder,
    private val fetcher: BlobFetcher,
    private val scope: CoroutineScope,
) {

    fun sourcesFor(
        label: String,
        unitDir: Path?,
        poolDir: Path?,
        classes: Map<String, ByteArray>,
        index: IndexData,
        bundle: DevBundle,
        mcVersion: String,
        serverJar: suspend () -> Path,
        bundleZip: ByteArray?,
    ): ClassSources {
        val fallback = DecompiledSources(classes, poolDir)
        if (unitDir == null || poolDir == null) return fallback

        val sources = TreeSources(classes, index, poolDir, fallback)
        ForkSourceTree.readManifest(unitDir)?.let { tree ->
            sources.publish(tree)
            return sources
        }

        // An index built from the decompiles would disagree with the tree about every line; the
        // next search rebuilds it, waiting for the tree first.
        for (name in STALE_SEARCH_INDEX) Files.deleteIfExists(unitDir.resolve(name))

        val build = scope.launch(Dispatchers.IO) {
            try {
                val zip = bundleZip ?: fetcher.fetch(bundle.zipUrl)
                val config = parseDevBundleConfig(
                    PaperclipPatcher.readZipEntry(zip, "config.json")?.toString(Charsets.UTF_8)
                        ?: throw VariantSetupException("$label: the dev bundle has no config.json"),
                )
                val mache = config.mache
                val patchDir = config.patchDir
                val tree = if (mache == null || patchDir == null) null else macheTrees.treeFor(mcVersion, mache, serverJar())
                if (tree == null) {
                    ForkSourceTree.writeManifest(unitDir, emptyMap())
                    sources.publish(emptyMap())
                    return@launch
                }

                val result = ForkSourceTree.build(tree, zip, patchDir!!, poolDir)
                ForkSourceTree.writeManifest(unitDir, result.tree)
                sources.publish(result.tree)
                val failed = if (result.failed.isEmpty()) "" else "; ${result.failed.size} patches didn't apply, those classes stay decompiled"
                System.err.println("mcsrc-mcp: $label now serves its source tree (${result.tree.size} classes$failed)")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                System.err.println("mcsrc-mcp: $label's source tree failed, serving decompiles: $e")
            }
        }
        // However the build ends - published, failed, cancelled, or never started because the scope
        // was already gone - the sources settle, or the search index would wait on them forever.
        // After a publish this is a no-op.
        build.invokeOnCompletion { sources.fail() }
        return sources
    }

    private companion object {

        val STALE_SEARCH_INDEX = listOf("search-index-v2.txt", "search-index-v2.toc")

    }

}
