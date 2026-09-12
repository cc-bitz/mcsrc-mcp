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

// awaitSearchIndex polls the in-memory progress map rather than joining the build job: join()
// can't report the percent as it climbs, and a search_code caller mid-wait is exactly who the
// progress notification exists for.
private const val INDEX_POLL_INTERVAL_MS = 500L

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

    /**
     * Ready means the workspace itself is queryable - every tool except full-text search works
     * from it the moment this returns. The search index keeps building in the background;
     * [awaitSearchIndex] is the door for callers that need it. Ready used to be held until the
     * whole jar was decompiled, which made "prepare" a several-minute wait that almost no tool
     * needed - only search_code/search_assets ever read the index.
     */
    suspend fun prepare(version: VersionListEntry, detail: VersionDetail): PrepareVersionResult {
        val cachedWorkspace = cache.get(version.id)
        if (cachedWorkspace != null) {
            ensureIndexStarted(cachedWorkspace)
            return PrepareVersionResult.Ready(version.id)
        }

        if (inFlight.putIfAbsent(version.id, true) != null) {
            return PrepareVersionResult.Preparing(version.id, 0)
        }

        try {
            val workspace = builder.build(version, detail)
            cache.put(version.id, workspace)
            ensureIndexStarted(workspace)
            return PrepareVersionResult.Ready(version.id)
        } finally {
            inFlight.remove(version.id)
        }
    }

    fun searchIndex(versionId: String): SearchIndex? = indexes[versionId]

    fun indexProgress(versionId: String): Int = indexProgress[versionId] ?: 0

    /**
     * The version's full-text index: from memory or disk if one exists, otherwise waiting out a
     * build in flight - starting one first if the last one died without publishing, since Ready no
     * longer gates on the index and a failed build must be retryable rather than something the
     * version never recovers from. Null only when no on-disk cache is configured, or a restarted
     * build failed again. [onProgress] sees each distinct build percent while the wait runs.
     */
    suspend fun awaitSearchIndex(
        workspace: VersionWorkspace,
        onProgress: suspend (percent: Int) -> Unit = {},
    ): SearchIndex? {
        indexFor(workspace)?.let { return it }
        startIndexBuild(workspace)
        val job = indexJobs[workspace.versionId] ?: return null
        var lastPercent = -1
        while (job.isActive) {
            val percent = indexProgress[workspace.versionId] ?: 0
            if (percent != lastPercent) {
                onProgress(percent)
                lastPercent = percent
            }
            delay(INDEX_POLL_INTERVAL_MS)
        }
        return indexes[workspace.versionId]
    }

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

    // Memory hit, else disk hit, else kick off a background build - a no-op when there is no
    // cache dir to build into or a build is already running.
    private fun ensureIndexStarted(workspace: VersionWorkspace) {
        if (indexFor(workspace) == null) {
            startIndexBuild(workspace)
        }
    }

    // Starts a background index build unless one is already running. Two calls racing on a cold
    // version can both reach the launch (this check-then-act isn't CAS'd); the cost is a rare
    // duplicated build, not corruption - SearchIndexWriter publishes by atomic move, last writer
    // wins, and both writers produce the same bytes from the same workspace.
    private fun startIndexBuild(workspace: VersionWorkspace) {
        val cacheDir = workspace.cacheDir ?: return
        val existing = indexJobs[workspace.versionId]
        if (existing != null && existing.isActive) return

        // A completed entry can only be residue of registering after scope.launch below (a job
        // that finished before its own map write). Its outcome is already reflected in indexes,
        // so drop it - awaitSearchIndex relies on being able to start a fresh build.
        indexJobs.remove(workspace.versionId, existing)

        val job = scope.launch {
            val builder = SearchIndexBuilder(workspace, cacheDir)
            val index = builder.build { percent ->
                indexProgress[workspace.versionId] = percent
            }
            indexes[workspace.versionId] = index
            indexProgress[workspace.versionId] = 100
        }

        indexJobs[workspace.versionId] = job
        job.invokeOnCompletion {
            indexJobs.remove(workspace.versionId, job)
            if (it != null) {
                indexProgress.remove(workspace.versionId)
                indexes.remove(workspace.versionId)
            }
        }
    }
}
