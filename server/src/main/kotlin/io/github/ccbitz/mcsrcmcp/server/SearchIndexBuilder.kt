package io.github.ccbitz.mcsrcmcp.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
     * resident as the returned index. Now nothing bigger than one batch of sources is ever held:
     * classes decompile in batches sized to [DecompileService.parallelism] (the pool bounds real
     * concurrency; the batch bounds how many decompiled sources sit in memory ahead of the
     * single-threaded writer), and each finished batch is written and dropped before the next
     * starts. Batch order follows the sorted class list, so the index is byte-identical to the
     * sequential build this replaced.
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
            // A cancelled build falls out of the coroutineScope as CancellationException, so
            // writer.use discards the temp files rather than finish() publishing a partial index
            // - the check per batch just avoids starting work the cancellation already doomed.
            coroutineScope {
                val batches = classNames.chunked(DecompileService.parallelism)
                for ((batchIndex, batch) in batches.withIndex()) {
                    if (!coroutineContext.isActive) return@coroutineScope

                    val sources = batch.map { internalName ->
                        async(Dispatchers.IO) {
                            DecompileService.decompileClass(
                                workspace.remappedClasses,
                                internalName,
                                cacheDir = sourceCacheDir,
                            )
                        }
                    }.awaitAll()

                    for ((internalName, source) in batch.zip(sources)) {
                        writer.add(SearchEntryKind.SOURCE, internalName.replace('/', '.'), source)
                    }

                    onProgress(((batchIndex + 1) * 100 / batches.size).coerceAtMost(100))
                }
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
