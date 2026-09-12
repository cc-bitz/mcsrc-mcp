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

        Files.newDirectoryStream(derivedRoot).use { versionDirs ->
            for (versionDir in versionDirs) {
                if (!Files.isDirectory(versionDir)) continue

                Files.newDirectoryStream(versionDir).use { cacheDirs ->
                    for (cacheDir in cacheDirs) {
                        evictIfStale(cacheDir, cutoff)
                    }
                }

                deleteIfEmpty(versionDir)
            }
        }
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
        Files.newDirectoryStream(derivedRoot).use { versionDirs ->
            for (versionDir in versionDirs) {
                if (!Files.isDirectory(versionDir)) continue
                val versionId = versionDir.fileName.toString()

                Files.newDirectoryStream(versionDir).use { cacheDirs ->
                    for (cacheDir in cacheDirs) {
                        if (!Files.isDirectory(cacheDir)) continue
                        val indexFile = cacheDir.resolve("index.bin")
                        if (!Files.exists(indexFile)) continue
                        val lastUsed = Files.getLastModifiedTime(indexFile).toInstant()
                        val size = directorySize(cacheDir)
                        units.add(CacheUnit(cacheDir, versionDir, size, lastUsed, versionId in warmVersions))
                    }
                }
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

        // Clean up any version directories that became empty.
        Files.newDirectoryStream(derivedRoot).use { versionDirs ->
            for (versionDir in versionDirs) {
                if (Files.isDirectory(versionDir)) {
                    deleteIfEmpty(versionDir)
                }
            }
        }
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
        val versionDir: Path,
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

    private fun deleteIfEmpty(dir: Path) {
        Files.newDirectoryStream(dir).use { stream ->
            if (!stream.iterator().hasNext()) {
                Files.deleteIfExists(dir)
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
