package io.github.ccbitz.mcsrcmcp.server

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.Comparator

private const val DEFAULT_CACHE_TTL_DAYS = 30L
private const val DEFAULT_MAX_SIZE_GB = 10L

fun resolveCacheTtl(env: Map<String, String> = System.getenv()): Duration {
    val raw = env["MCSRC_MCP_CACHE_TTL_DAYS"] ?: return Duration.ofDays(DEFAULT_CACHE_TTL_DAYS)
    val days = raw.toLongOrNull() ?: return Duration.ofDays(DEFAULT_CACHE_TTL_DAYS)
    return Duration.ofDays(days)
}

fun resolveCacheMaxSizeBytes(env: Map<String, String> = System.getenv()): Long {
    val raw = env["MCSRC_MCP_CACHE_MAX_SIZE_GB"] ?: return DEFAULT_MAX_SIZE_GB * 1024 * 1024 * 1024
    val gb = raw.toLongOrNull() ?: return DEFAULT_MAX_SIZE_GB * 1024 * 1024 * 1024
    return gb * 1024 * 1024 * 1024
}

/**
 * Deletes derived-cache directories (a version's remapped.jar + index.bin + source subtree as
 * one unit) that haven't been read in over [ttl], then if the remaining derived cache is still
 * over [maxSizeBytes] deletes the least-recently-used units (by index.bin last-modified time)
 * until it fits. Versions in [warmVersions] are never evicted, even if they're old or large.
 * "Read" is tracked via index.bin's last-modified time, which DerivedCacheStore.load()
 * explicitly bumps to now on every cache hit.
 */
object CacheEviction {
    fun evict(
        cacheRoot: Path,
        ttl: Duration,
        maxSizeBytes: Long,
        warmVersions: Set<String> = emptySet(),
        now: Instant = Instant.now(),
    ) {
        evictStale(cacheRoot, ttl, now)
        evictOverBudget(cacheRoot, maxSizeBytes, warmVersions)
    }

    fun evictStale(cacheRoot: Path, ttl: Duration, now: Instant = Instant.now()) {
        val derivedRoot = cacheRoot.resolve("derived")
        if (!Files.isDirectory(derivedRoot)) {
            return
        }

        val cutoff = now.minus(ttl)

        // A derived unit is any directory holding an index.bin. Variant workspaces nest one level
        // deeper than vanilla (derived/paper/<build>/<hash-dir>), so this walks rather than
        // assuming exactly two levels; the marker files variant latest-tracking leaves between
        // build directories are plain files and don't match. The units are collected before any
        // deletion - evicting while the lazy walk is still traversing would crash it with
        // NoSuchFileException on the directory that just got deleted under it.
        val unitDirs = Files.walk(derivedRoot).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.fileName.toString() == "index.bin" }
                .map { it.parent }
                .toList()
        }
        for (dir in unitDirs) {
            evictIfStale(dir, cutoff)
        }
        dropOrphanedForkState(derivedRoot)
        deleteEmptyDirs(derivedRoot)
    }

    fun evictOverBudget(cacheRoot: Path, maxSizeBytes: Long, warmVersions: Set<String> = emptySet()) {
        val derivedRoot = cacheRoot.resolve("derived")
        if (!Files.isDirectory(derivedRoot)) {
            return
        }
        if (maxSizeBytes < 0) {
            return
        }

        val units = mutableListOf<CacheUnit>()
        Files.walk(derivedRoot).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.fileName.toString() == "index.bin" }
                .forEach { indexFile ->
                    val dir = indexFile.parent
                    val workspaceId = derivedRoot.relativize(dir.parent).toString().replace('\\', '/')
                    val lastUsed = Files.getLastModifiedTime(indexFile).toInstant()
                    units.add(CacheUnit(dir, directorySize(dir), lastUsed, isWarm = workspaceId in warmVersions))
                }
        }

        var total = units.sumOf { it.size }
        if (total <= maxSizeBytes) {
            return
        }

        val sorted = units.sortedBy { it.lastUsed }
        for (unit in sorted) {
            if (total <= maxSizeBytes) break
            if (unit.isWarm) continue
            deleteRecursively(unit.dir)
            total -= unit.size
        }

        dropOrphanedForkState(derivedRoot)
        deleteEmptyDirs(derivedRoot)
    }

    /**
     * Deletes the entire derived-cache directory for [versionId] (remapped jar, index, and
     * cached decompiled source) regardless of TTL or warm status. Leaves raw downloaded blobs
     * untouched - those are content-addressed and shared, rarely what "clear the cache" means.
     */
    fun evictVersion(cacheRoot: Path, versionId: String) {
        deleteRecursively(cacheRoot.resolve("derived").resolve(versionId))
    }

    /** [evictVersion] for every version's derived cache. */
    fun evictAllDerived(cacheRoot: Path) {
        deleteRecursively(cacheRoot.resolve("derived"))
    }

    private data class CacheUnit(
        val dir: Path,
        val size: Long,
        val lastUsed: Instant,
        val isWarm: Boolean,
    )

    private fun evictIfStale(cacheDir: Path, cutoff: Instant) {
        val indexFile = cacheDir.resolve("index.bin")
        if (!Files.exists(indexFile)) {
            return
        }

        val lastUsed = Files.getLastModifiedTime(indexFile).toInstant()
        if (lastUsed.isBefore(cutoff)) {
            deleteRecursively(cacheDir)
        }
    }

    /**
     * A fork's shared decompile cache (derived/<fork>/<mcVersion>/source-cache) and its latest
     * marker (derived/<fork>/<mcVersion>.latest) belong to no unit, so the unit passes never touch
     * them - without this the shared cache would outlive every build that used it, forever. Both go
     * once no build of their Minecraft version is left on disk.
     */
    private fun dropOrphanedForkState(derivedRoot: Path) {
        for (variant in Variants.ALL) {
            if (variant.devBundleRepository == null) continue
            val forkDir = derivedRoot.resolve(variant.id)
            if (!Files.isDirectory(forkDir)) continue

            val children = Files.list(forkDir).use { it.toList() }
            // Build directories are named by dev bundle version; the index.bin lives one level down,
            // in the build's hash directory.
            val versionsWithBuilds = children
                .filter { child ->
                    Files.isDirectory(child) &&
                        Files.find(child, 2, { path, attrs -> attrs.isRegularFile && path.fileName.toString() == "index.bin" })
                            .use { it.findAny().isPresent }
                }
                .mapNotNull { devBundleMinecraftVersion(it.fileName.toString()) }
                .toSet()

            for (child in children) {
                val name = child.fileName.toString()
                val mcVersion = when {
                    Files.isRegularFile(child) && name.endsWith(".latest") -> name.removeSuffix(".latest")
                    Files.isDirectory(child) && devBundleMinecraftVersion(name) == null -> name
                    else -> continue
                }
                if (mcVersion !in versionsWithBuilds) deleteRecursively(child)
            }
        }
    }

    private fun deleteEmptyDirs(root: Path) {
        // Bottom-up, so a chain of emptied variant directories collapses in one pass. Collected
        // before deleting for the same mid-walk-deletion reason as evictStale.
        val dirs = Files.walk(root).use { stream ->
            stream.sorted(Comparator.reverseOrder())
                .filter { Files.isDirectory(it) && it != root }
                .toList()
        }
        for (dir in dirs) {
            Files.newDirectoryStream(dir).use { entries ->
                if (!entries.iterator().hasNext()) Files.deleteIfExists(dir)
            }
        }
    }

    private fun directorySize(dir: Path): Long {
        var size = 0L
        Files.walk(dir).use { stream ->
            stream.filter { Files.isRegularFile(it) }.forEach { size += Files.size(it) }
        }
        return size
    }

    private fun deleteRecursively(dir: Path) {
        if (!Files.exists(dir)) return
        Files.walk(dir).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }
}
