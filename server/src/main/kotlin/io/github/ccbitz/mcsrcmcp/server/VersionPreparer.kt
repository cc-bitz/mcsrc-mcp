package io.github.ccbitz.mcsrcmcp.server

import io.github.ccbitz.mcsrcmcp.cache.VersionDetail
import io.github.ccbitz.mcsrcmcp.cache.VersionListEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

sealed interface PrepareVersionResult {
    data class Ready(val versionId: String) : PrepareVersionResult
    data class Preparing(val versionId: String, val percent: Int) : PrepareVersionResult
}

/**
 * Calls [pollOnce] (typically [VersionPreparer.prepare]) repeatedly until it reports
 * [PrepareVersionResult.Ready] or [pollTimeout] elapses, reporting each distinct percent to
 * [onProgress] along the way. This lets a caller (e.g. an MCP tool handler) turn mcsrc-mcp's
 * own re-poll-until-ready pattern into a single suspending call with progress streamed out of
 * band, instead of making the client re-invoke the tool on every tick.
 *
 * Bounded by [pollTimeout] rather than waiting forever: a real prepare can run long enough
 * (a full version's worth of decompiling) that some MCP transports/clients may not tolerate an
 * indefinitely open call. On timeout this just returns the last known [PrepareVersionResult],
 * same as it always did - the caller falls back to the old poll-by-calling-again contract.
 */
suspend fun pollUntilReady(
    pollInterval: Duration,
    pollTimeout: Duration,
    onProgress: suspend (percent: Int) -> Unit,
    pollOnce: suspend () -> PrepareVersionResult,
): PrepareVersionResult {
    var current = pollOnce()
    withTimeoutOrNull(pollTimeout.toMillis()) {
        var lastPercent = -1
        while (current is PrepareVersionResult.Preparing) {
            val percent = (current as PrepareVersionResult.Preparing).percent
            if (percent != lastPercent) {
                onProgress(percent)
                lastPercent = percent
            }
            delay(pollInterval.toMillis())
            current = pollOnce()
        }
    }
    return current
}

class VersionPreparer(
    private val cache: WorkspaceCache,
    private val builder: VersionWorkspaceBuilder,
    private val scope: CoroutineScope,
) {
    private val inFlight = ConcurrentHashMap<String, Boolean>()
    private val indexJobs = ConcurrentHashMap<String, Job>()
    private val indexProgress = ConcurrentHashMap<String, Int>()
    private val indexes = ConcurrentHashMap<String, SearchIndex>()

    suspend fun prepare(version: VersionListEntry, detail: VersionDetail): PrepareVersionResult {
        val cachedWorkspace = cache.get(version.id)
        if (cachedWorkspace != null) {
            val index = indexFor(cachedWorkspace)
            if (index != null || cachedWorkspace.cacheDir == null) {
                return PrepareVersionResult.Ready(version.id)
            }
            startIndexBuild(cachedWorkspace)
            return PrepareVersionResult.Preparing(version.id, indexProgress[version.id] ?: 0)
        }

        if (inFlight.putIfAbsent(version.id, true) != null) {
            return PrepareVersionResult.Preparing(version.id, indexProgress[version.id] ?: 0)
        }

        try {
            val workspace = builder.build(version, detail)
            cache.put(version.id, workspace)
            val index = indexFor(workspace)
            if (index != null || workspace.cacheDir == null) {
                return PrepareVersionResult.Ready(version.id)
            }
            startIndexBuild(workspace)
            return PrepareVersionResult.Preparing(version.id, 0)
        } finally {
            inFlight.remove(version.id)
        }
    }

    fun searchIndex(versionId: String): SearchIndex? = indexes[versionId]

    fun indexProgress(versionId: String): Int = indexProgress[versionId] ?: 0

    /**
     * Drops all in-memory state for [versionId]: the cached workspace, its search index,
     * indexing progress, and the in-flight-build marker. Any index-build job still running for
     * it is cancelled rather than left to finish and repopulate state we just cleared. Used by
     * the clear_cache tool, alongside deleting the on-disk derived cache, so a cleared version
     * genuinely starts over on next use instead of still answering from memory.
     */
    fun clear(versionId: String) {
        cache.remove(versionId)
        indexJobs.remove(versionId)?.cancel()
        indexProgress.remove(versionId)
        indexes.remove(versionId)
        inFlight.remove(versionId)
    }

    /** [clear] for every version this preparer has touched. */
    fun clearAll() {
        val versionIds = cache.warmVersions() + indexJobs.keys + indexProgress.keys + indexes.keys + inFlight.keys
        for (versionId in versionIds.toSet()) {
            clear(versionId)
        }
    }

    private fun indexFor(workspace: VersionWorkspace): SearchIndex? {
        val cacheDir = workspace.cacheDir ?: return null
        return indexes[workspace.versionId]
            ?: runCatching { SearchIndexStore.load(cacheDir) }.getOrNull()
                ?.also { indexes[workspace.versionId] = it }
    }

    private fun startIndexBuild(workspace: VersionWorkspace) {
        val cacheDir = workspace.cacheDir ?: return
        if (indexJobs.putIfAbsent(workspace.versionId, Job()) != null) {
            return
        }

        val job = scope.launch {
            val builder = SearchIndexBuilder(workspace, cacheDir)
            val index = builder.build { percent ->
                indexProgress[workspace.versionId] = percent
            }
            indexes[workspace.versionId] = index
            indexProgress[workspace.versionId] = 100
        }

        job.invokeOnCompletion {
            indexJobs.remove(workspace.versionId)
            if (it != null) {
                indexProgress.remove(workspace.versionId)
                indexes.remove(workspace.versionId)
            }
        }

        indexJobs[workspace.versionId] = job
    }
}
