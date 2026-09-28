package io.github.ccbitz.mcsrcmcp.server

import io.github.ccbitz.mcsrcmcp.cache.VersionDetail
import io.github.ccbitz.mcsrcmcp.cache.VersionListEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
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
    private val builders: VariantBuilders,
    private val scope: CoroutineScope,
    // Null keeps latest-build tracking off - a test, or a server with no cache root.
    private val cacheRoot: Path? = null,
) {
    private val inFlight = ConcurrentHashMap<String, Boolean>()
    private val indexJobs = ConcurrentHashMap<String, Job>()
    private val indexProgress = ConcurrentHashMap<String, Int>()
    private val indexes = ConcurrentHashMap<String, SearchIndex>()

    suspend fun prepare(version: VersionListEntry, detail: VersionDetail): PrepareVersionResult =
        prepare(WorkspaceRequest(Variants.VANILLA, null, version.id), version, detail)

    /**
     * Ready means the workspace itself is queryable - every tool except full-text search works
     * from it the moment this returns. The search index keeps building in the background;
     * [awaitSearchIndex] is the door for callers that need it. Ready used to be held until the
     * whole jar was decompiled, which made "prepare" a several-minute wait that almost no tool
     * needed - only search_code/search_assets ever read the index.
     */
    suspend fun prepare(request: WorkspaceRequest, version: VersionListEntry, detail: VersionDetail): PrepareVersionResult {
        val cachedWorkspace = cache.get(request.workspaceId)
        if (cachedWorkspace != null) {
            ensureIndexStarted(cachedWorkspace)
            return PrepareVersionResult.Ready(request.workspaceId)
        }

        if (inFlight.putIfAbsent(request.workspaceId, true) != null) {
            return PrepareVersionResult.Preparing(request.workspaceId, 0)
        }

        try {
            val builder = builders.forVariant(request.variant)
                ?: throw UnknownVariantException(request.variant, builders.ids())
            val workspace = builder.build(request, version, detail)
            cache.put(request.workspaceId, workspace)
            ensureIndexStarted(workspace)
            if (request.build == null && request.variant != Variants.VANILLA) {
                rotateLatest(request, version.id)
            }
            return PrepareVersionResult.Ready(request.workspaceId)
        } finally {
            inFlight.remove(request.workspaceId)
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
     * Drops all in-memory state for [workspaceId]: the cached workspace, its search index,
     * indexing progress, and the in-flight-build marker. Any index-build job still running for
     * it is cancelled rather than left to finish and repopulate state we just cleared. Used by
     * the clear_cache tool, alongside deleting the on-disk derived cache, so a cleared version
     * genuinely starts over on next use instead of still answering from memory.
     */
    fun clear(workspaceId: String) {
        cache.remove(workspaceId)
        indexJobs.remove(workspaceId)?.cancel()
        indexProgress.remove(workspaceId)
        indexes.remove(workspaceId)
        inFlight.remove(workspaceId)
    }

    /** [clear] for every workspace this preparer has touched. */
    fun clearAll() {
        val workspaceIds = cache.warmVersions() + indexJobs.keys + indexProgress.keys + indexes.keys + inFlight.keys
        for (workspaceId in workspaceIds.toSet()) {
            clear(workspaceId)
        }
    }

    /**
     * Variant builds resolved without an explicit pin are "latest" builds, and every active fork
     * republishes them often. Left alone, every build the user ever touched would leave a full
     * derived directory on disk - hundreds of near-identical indexes over time. So a successful
     * latest-build prepare records its workspace id in a marker file, and the next latest-build
     * prepare of the same Minecraft version evicts the previous one's derived cache and in-memory
     * state. Pinned builds (an explicit id/build argument) never write the marker and never get
     * rotated out: the caller asked for that exact build.
     *
     * The shared, content-keyed decompile pool ([SourcePools]) is deliberately kept: it is what
     * makes the next build cheap. It is swept down to the keys some build still on disk recorded -
     * the new one, and any pinned build - so entries only the rotated-out build could hit go with it.
     *
     * Housekeeping only: by the time this runs the workspace is built and cached, so a filesystem
     * error here (Windows refusing to delete a file a reader holds open, say) is logged rather
     * than turned into a failed prepare.
     */
    private fun rotateLatest(request: WorkspaceRequest, mcVersion: String) {
        val root = cacheRoot ?: return
        try {
            val variantDir = root.resolve("derived").resolve(request.variant)
            val marker = variantDir.resolve("$mcVersion.latest")
            val previous = runCatching { Files.readString(marker) }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }

            if (previous != null && previous != request.workspaceId) {
                clear(previous)
                CacheEviction.evictVersion(root, previous)
            }

            CacheEviction.sweepSourcePools(root)

            Files.createDirectories(variantDir)
            val tmp = Files.createTempFile(variantDir, "latest-", ".tmp")
            try {
                Files.writeString(tmp, request.workspaceId)
                Files.move(tmp, marker, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally {
                Files.deleteIfExists(tmp)
            }
        } catch (e: Exception) {
            System.err.println("mcsrc-mcp: latest-build cleanup for ${request.workspaceId} failed: $e")
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
