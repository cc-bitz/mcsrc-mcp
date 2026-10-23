package io.github.ccbitz.mcsrcmcp.server

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

// Sources read ahead of the writer. A slow class at the head of the line (BlockStateData takes ~2s in
// a per-class decompile, the average class ~40ms) would otherwise idle the rest of the pool behind it;
// this many keeps the other threads busy for that long, at a few MB of buffered sources.
private const val READ_AHEAD = 256

// The share of progress the bulk decompile reports when it runs; the reads after it are cache hits.
private const val BULK_PROGRESS_SHARE = 80

class SearchIndexBuilder(
    private val workspace: VersionWorkspace,
    private val cacheDir: Path,
) {
    /**
     * Decompiles every class and writes it, plus every text asset, straight out to the on-disk
     * index, holding no more than [READ_AHEAD] decompiled sources at a time.
     *
     * Classes the source pool doesn't have yet are decompiled first, in bulk, by [BulkDecompiler];
     * whatever that leaves - everything, when it doesn't run - is decompiled here, at most
     * [DecompileService.parallelism] at a time. That bound is the pool's, not a batch's: each class
     * gets a permit when the one ahead of it finishes rather than when its whole batch does, so one
     * slow class no longer idles the other threads. The permits also keep [DecompileService]'s queue
     * short, which matters twice over: its timeout counts time spent queued, and a get_class_source
     * arriving mid-build waits behind whatever is queued. Entries are written in sorted class order,
     * so the index is byte-identical to the sequential build this replaced.
     */
    suspend fun build(onProgress: (Int) -> Unit = {}): SearchIndex {
        // A fork's real source may still be building; indexing the decompiles in the meantime would
        // leave search results whose lines don't match what get_class_source then shows.
        workspace.sources.awaitSettled()
        val classNames = workspace.remappedClasses.keys
            .filter { !it.contains('$') }
            .sorted()
        val textAssetPaths = workspace.assets.values
            .filter { it.isText }
            .map { it.path }
            .sorted()

        // Hashes the jar and stats a file per class - ~100ms for a whole version, once per build, and
        // mostly hashing, so it stays on the caller's dispatcher.
        val pending = workspace.sources.pendingDecompiles(classNames)
        val bulkPercent = AtomicInteger()
        val bulkRan = pending != null && BulkDecompiler.warm(pending) { fraction ->
            val percent = (fraction * BULK_PROGRESS_SHARE).toInt()
            bulkPercent.set(percent)
            onProgress(percent)
        }
        // Reads take the rest of the bar from wherever the bulk decompile left it - all of it when that
        // didn't run, and from where it stopped when it failed partway - so the percent never goes back.
        val readsFrom = if (bulkRan) BULK_PROGRESS_SHARE else bulkPercent.get()
        val readShare = 100 - readsFrom

        val writer = SearchIndexStore.writer(cacheDir, workspace.versionId)
        return writer.use {
            // A cancelled build falls out of the coroutineScope as CancellationException, so
            // writer.use discards the temp files rather than finish() publishing a partial index.
            coroutineScope {
                val permits = Semaphore(DecompileService.parallelism)
                val window = ArrayDeque<Deferred<String>>(READ_AHEAD)
                var launched = 0
                var lastPercent = -1
                for ((index, internalName) in classNames.withIndex()) {
                    // Semaphore is fair - permits go out in the order reads ask for them, which is close
                    // to launch order - so the head of the window waits behind a handful of reads at
                    // most, never behind the whole window.
                    while (launched < classNames.size && launched - index < READ_AHEAD) {
                        val next = classNames[launched++]
                        window.addLast(async(Dispatchers.IO) { permits.withPermit { workspace.sources.source(next) } })
                    }
                    // An already-completed Deferred hands back its value without checking for
                    // cancellation, so a build cancelled with the window full would otherwise run on.
                    coroutineContext.ensureActive()
                    writer.add(SearchEntryKind.SOURCE, internalName.replace('/', '.'), window.removeFirst().await())

                    val percent = readsFrom + (index + 1) * readShare / classNames.size
                    if (percent != lastPercent) {
                        onProgress(percent.coerceAtMost(100))
                        lastPercent = percent
                    }
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
