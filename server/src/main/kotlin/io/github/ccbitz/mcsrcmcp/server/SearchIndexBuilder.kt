package io.github.ccbitz.mcsrcmcp.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import java.nio.file.Path

class SearchIndexBuilder(
    private val workspace: VersionWorkspace,
    private val cacheDir: Path,
) {
    /**
     * Decompiles every class and writes it, plus every text asset, straight out to the on-disk
     * index. Entries used to be accumulated in two in-memory lists and handed back whole, which
     * meant the builder's peak was the entire decompiled codebase - and then that peak stayed
     * resident as the returned index. Now nothing bigger than one class's source is ever held.
     */
    suspend fun build(onProgress: (Int) -> Unit = {}): SearchIndex {
        val sourceCacheDir = cacheDir.resolve("source/$SOURCE_CACHE_CONFIG_VERSION")
        val classNames = workspace.remappedClasses.keys
            .filter { !it.contains('$') }
            .sorted()
        val textAssetPaths = workspace.assets.values
            .filter { it.isText }
            .map { it.path }
            .sorted()

        val writer = SearchIndexStore.writer(cacheDir, workspace.versionId)
        return writer.use {
            for ((index, internalName) in classNames.withIndex()) {
                if (!coroutineContext.isActive) break

                val source = withContext(Dispatchers.IO) {
                    DecompileService.decompileClass(
                        workspace.remappedClasses,
                        internalName,
                        cacheDir = sourceCacheDir,
                    )
                }

                writer.add(SearchEntryKind.SOURCE, internalName.replace('/', '.'), source)

                val percent = ((index + 1) * 100 / classNames.size).coerceAtMost(100)
                onProgress(percent)
            }

            // One jar open for all of them rather than one per asset. batch's lambda isn't suspend,
            // so cancellation is observed through the Job rather than `coroutineContext.isActive`.
            val job = coroutineContext[Job]
            workspace.assetSource.batch { assets ->
                for (path in textAssetPaths) {
                    if (job?.isActive == false) break
                    val text = assets.readText(path) ?: continue
                    writer.add(SearchEntryKind.ASSET, path, text)
                }
            }

            writer.finish()
        }
    }
}
