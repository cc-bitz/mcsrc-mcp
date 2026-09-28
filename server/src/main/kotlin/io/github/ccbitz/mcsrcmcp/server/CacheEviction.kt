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
        for (unit in listUnits(derivedRoot)) {
            if (Files.getLastModifiedTime(unit.indexFile).toInstant().isBefore(cutoff)) {
                deleteRecursively(unit.dir)
            }
        }
        dropOrphanedForkState(derivedRoot)
        deleteEmptyWorkspaceDirs(derivedRoot)
    }

    fun evictOverBudget(cacheRoot: Path, maxSizeBytes: Long, warmVersions: Set<String> = emptySet()) {
        val derivedRoot = cacheRoot.resolve("derived")
        if (!Files.isDirectory(derivedRoot)) {
            return
        }
        if (maxSizeBytes < 0) {
            return
        }

        // Sizing is the one step that has to read every file in a unit - there is no recorded size
        // to trust instead - so it is the only full walk eviction does.
        val units = listUnits(derivedRoot).map { unit ->
            val lastUsed = Files.getLastModifiedTime(unit.indexFile).toInstant()
            CacheUnit(unit.dir, directorySize(unit.dir), lastUsed, isWarm = unit.workspaceId in warmVersions)
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
        deleteEmptyWorkspaceDirs(derivedRoot)
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

    /** One derived unit: a hash directory holding an index.bin, and the workspace id it belongs to. */
    private class DerivedUnit(val dir: Path, val workspaceId: String) {

        val indexFile: Path get() = dir.resolve("index.bin")

    }

    /**
     * Lists every derived unit from the layout the builders write, rather than searching for
     * index.bin files: vanilla at derived/<version>/<hash-dir> and forks at
     * derived/<fork>/<build>/<hash-dir> (see [DerivedCacheStore.directoryFor]). Searching meant
     * walking every cached decompile inside every unit and every fork's shared source cache - tens
     * of thousands of files on a well-used cache - while this costs one listing per version or
     * build directory, however many classes have been decompiled.
     */
    private fun listUnits(derivedRoot: Path): List<DerivedUnit> {
        val units = mutableListOf<DerivedUnit>()

        fun addUnitsIn(workspaceDir: Path, workspaceId: String) {
            for (hashDir in subdirectories(workspaceDir)) {
                if (Files.isRegularFile(hashDir.resolve("index.bin"))) units.add(DerivedUnit(hashDir, workspaceId))
            }
        }

        for (top in subdirectories(derivedRoot)) {
            val name = top.fileName.toString()
            if (Variants.byId(name)?.devBundleRepository == null) {
                addUnitsIn(top, name)
                continue
            }
            for (build in subdirectories(top)) {
                val buildName = build.fileName.toString()
                // Everything else at this level is a per-Minecraft-version shared source cache.
                if (devBundleMinecraftVersion(buildName) != null) addUnitsIn(build, "$name/$buildName")
            }
        }
        return units
    }

    private fun subdirectories(dir: Path): List<Path> =
        Files.list(dir).use { entries -> entries.filter { Files.isDirectory(it) }.toList() }

    /**
     * A fork's shared decompile cache (derived/<fork>/<mcVersion>/source-cache) and its latest
     * marker (derived/<fork>/<mcVersion>.latest) belong to no unit, so the unit passes never touch
     * them - without this the shared cache would outlive every build that used it, forever. Both go
     * once no build of their Minecraft version is left on disk.
     */
    private fun dropOrphanedForkState(derivedRoot: Path) {
        val liveWorkspaceIds = listUnits(derivedRoot).map { it.workspaceId }
        for (variant in Variants.ALL) {
            if (variant.devBundleRepository == null) continue
            val forkDir = derivedRoot.resolve(variant.id)
            if (!Files.isDirectory(forkDir)) continue

            val children = Files.list(forkDir).use { it.toList() }
            val versionsWithBuilds = liveWorkspaceIds
                .filter { it.startsWith("${variant.id}/") }
                .mapNotNull { devBundleMinecraftVersion(it.substringAfter('/')) }
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

    /**
     * Drops version, build and fork directories that eviction left empty. Only those levels: an
     * empty directory inside a live unit or source cache may be one a build just created and is
     * about to write into, and deleting it would fail that write.
     */
    private fun deleteEmptyWorkspaceDirs(derivedRoot: Path) {
        for (top in subdirectories(derivedRoot)) {
            if (Variants.byId(top.fileName.toString())?.devBundleRepository != null) {
                for (build in subdirectories(top)) deleteIfEmpty(build)
            }
            deleteIfEmpty(top)
        }
    }

    private fun deleteIfEmpty(dir: Path) {
        val empty = Files.newDirectoryStream(dir).use { !it.iterator().hasNext() }
        if (empty) Files.deleteIfExists(dir)
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
