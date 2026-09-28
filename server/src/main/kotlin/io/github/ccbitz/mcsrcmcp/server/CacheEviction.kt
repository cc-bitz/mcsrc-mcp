package io.github.ccbitz.mcsrcmcp.server

import io.github.ccbitz.mcsrcmcp.cache.VersionListEntry
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Comparator

private const val DEFAULT_CACHE_TTL_DAYS = 30L
private const val DEFAULT_SNAPSHOT_TTL_DAYS = 7L
private const val DEFAULT_MAX_SIZE_GB = 10L

fun resolveCacheTtl(env: Map<String, String> = System.getenv()): Duration {
    val raw = env["MCSRC_MCP_CACHE_TTL_DAYS"] ?: return Duration.ofDays(DEFAULT_CACHE_TTL_DAYS)
    val days = raw.toLongOrNull() ?: return Duration.ofDays(DEFAULT_CACHE_TTL_DAYS)
    return Duration.ofDays(days)
}

fun resolveSnapshotTtl(env: Map<String, String> = System.getenv()): Duration {
    val raw = env["MCSRC_MCP_SNAPSHOT_TTL_DAYS"] ?: return Duration.ofDays(DEFAULT_SNAPSHOT_TTL_DAYS)
    val days = raw.toLongOrNull() ?: return Duration.ofDays(DEFAULT_SNAPSHOT_TTL_DAYS)
    return Duration.ofDays(days)
}

fun resolveCacheMaxSizeBytes(env: Map<String, String> = System.getenv()): Long {
    val raw = env["MCSRC_MCP_CACHE_MAX_SIZE_GB"] ?: return DEFAULT_MAX_SIZE_GB * 1024 * 1024 * 1024
    val gb = raw.toLongOrNull() ?: return DEFAULT_MAX_SIZE_GB * 1024 * 1024 * 1024
    return gb * 1024 * 1024 * 1024
}

/**
 * Every non-release version (snapshot, pre-release, release candidate, old alpha/beta - the
 * manifest types them all apart from "release") that something newer has since superseded. These
 * are what pile up: one lands every week or so, each costs a full derived directory, and once the
 * next is out the old one is mostly wanted for diffing against its neighbour - days, not the month
 * a release keeps its cache. Releases never qualify: they are what projects pin to.
 */
fun supersededSnapshots(versions: List<VersionListEntry>): Set<String> {
    val newest = versions.maxOfOrNull { OffsetDateTime.parse(it.releaseTime) } ?: return emptySet()
    return versions
        .filter { it.type != "release" && OffsetDateTime.parse(it.releaseTime).isBefore(newest) }
        .mapTo(HashSet()) { it.id }
}

/**
 * Deletes derived-cache units (one workspace's remapped.jar + index.bin + search index) that
 * haven't been read in over their TTL - [ttl], or [snapshotTtl] for [supersededSnapshots] - then,
 * if the derived cache is still over [maxSizeBytes], the least-recently-used units until it fits.
 * Workspaces in [warmVersions] are never evicted, even if they're old or large. "Read" is tracked
 * via index.bin's last-modified time, which DerivedCacheStore.load() explicitly bumps to now on
 * every cache hit. The shared decompile pools ([SourcePools]) are swept after every pass down to
 * what the surviving units can still hit.
 */
object CacheEviction {

    fun evict(
        cacheRoot: Path,
        ttl: Duration,
        maxSizeBytes: Long,
        warmVersions: Set<String> = emptySet(),
        now: Instant = Instant.now(),
        snapshotTtl: Duration = ttl,
        supersededSnapshots: Set<String> = emptySet(),
    ) {
        evictStale(cacheRoot, ttl, now, snapshotTtl, supersededSnapshots)
        evictOverBudget(cacheRoot, maxSizeBytes, warmVersions)
    }

    fun evictStale(
        cacheRoot: Path,
        ttl: Duration,
        now: Instant = Instant.now(),
        snapshotTtl: Duration = ttl,
        supersededSnapshots: Set<String> = emptySet(),
    ) {
        val derivedRoot = cacheRoot.resolve("derived")
        if (!Files.isDirectory(derivedRoot)) {
            return
        }

        val cutoff = now.minus(ttl)
        // Never longer than the normal TTL: a short-lived class of version can't outlive a release.
        val snapshotCutoff = now.minus(minOf(ttl, snapshotTtl))
        for (unit in dropSupersededUnits(listUnits(derivedRoot))) {
            val unitCutoff = if (unit.workspaceId in supersededSnapshots) snapshotCutoff else cutoff
            if (Files.getLastModifiedTime(unit.indexFile).toInstant().isBefore(unitCutoff)) {
                deleteRecursively(unit.dir)
            } else {
                // Vanilla units kept their decompiled sources inside themselves before pools
                // existed; nothing reads that directory any more.
                deleteRecursively(unit.dir.resolve("source"))
            }
        }
        sweepSourcePools(cacheRoot)
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

        // Sizing is the one step that has to read every file - there is no recorded size to trust
        // instead - so it is the only full walk eviction does. Pools count against the budget
        // too: they hold every decompiled class, and leaving them out would let the cache overrun
        // it by a pool per release train.
        val units = listUnits(derivedRoot)
        val usersByPool = units.groupBy { it.poolDir(derivedRoot) }
        val poolSizes = usersByPool.keys.associateWithTo(HashMap()) { directorySize(it) }
        val sized = units.map { unit ->
            val lastUsed = Files.getLastModifiedTime(unit.indexFile).toInstant()
            CacheUnit(unit, directorySize(unit.dir), lastUsed, isWarm = unit.workspaceId in warmVersions)
        }

        var total = sized.sumOf { it.size } + poolSizes.values.sum()
        if (total <= maxSizeBytes) {
            return
        }

        for (candidate in sized.sortedBy { it.lastUsed }) {
            if (total <= maxSizeBytes) break
            if (candidate.isWarm) continue
            deleteRecursively(candidate.unit.dir)
            total -= candidate.size

            // Evicting a unit frees the pool entries only it could hit, so the pool shrinks with
            // it - otherwise the pass would go on evicting units to pay for entries already dead.
            val pool = candidate.unit.poolDir(derivedRoot)
            val users = usersByPool.getValue(pool).filter { Files.exists(it.dir) }
            if (users.isEmpty()) deleteRecursively(pool) else sweepPool(pool, users)
            val after = directorySize(pool)
            total -= poolSizes.getValue(pool) - after
            poolSizes[pool] = after
        }

        sweepSourcePools(cacheRoot)
        deleteEmptyWorkspaceDirs(derivedRoot)
    }

    /**
     * Pools and fork latest markers belong to no unit, so the unit passes never touch them. A pool
     * no unit on disk maps onto goes whole - otherwise it would outlive every workspace that used
     * it, forever. A live one is swept down to the keys its units recorded, so entries only a
     * deleted or rotated-out workspace could hit don't pile up. A fork's latest marker goes with
     * the last build of its Minecraft version.
     */
    fun sweepSourcePools(cacheRoot: Path) {
        val derivedRoot = cacheRoot.resolve("derived")
        if (!Files.isDirectory(derivedRoot)) return

        val usersByPool = dropSupersededUnits(listUnits(derivedRoot)).groupBy { it.poolDir(derivedRoot) }
        for (variant in Variants.ALL) {
            val variantDir = derivedRoot.resolve(variant.id)
            if (!Files.isDirectory(variantDir)) continue

            for (child in Files.list(variantDir).use { it.toList() }) {
                val name = child.fileName.toString()
                when {
                    // Named by Minecraft version, which is also a fork's pool key.
                    Files.isRegularFile(child) && name.endsWith(".latest") ->
                        if (variantDir.resolve(name.removeSuffix(".latest")) !in usersByPool) Files.deleteIfExists(child)

                    Files.isDirectory(child) && isPoolDir(variant, name) -> {
                        val users = usersByPool[child]
                        if (users == null) deleteRecursively(child) else sweepPool(child, users)
                    }
                }
            }
        }
    }

    /**
     * Deletes the entire derived-cache directory for [versionId] (remapped jar, index, and
     * search index) regardless of TTL or warm status. Leaves raw downloaded blobs untouched -
     * those are content-addressed and shared, rarely what "clear the cache" means.
     */
    fun evictVersion(cacheRoot: Path, versionId: String) {
        deleteRecursively(cacheRoot.resolve("derived").resolve(versionId))
    }

    /** [evictVersion] for every version's derived cache. */
    fun evictAllDerived(cacheRoot: Path) {
        deleteRecursively(cacheRoot.resolve("derived"))
    }

    private data class CacheUnit(
        val unit: DerivedUnit,
        val size: Long,
        val lastUsed: Instant,
        val isWarm: Boolean,
    )

    /**
     * One derived unit: a hash directory holding an index.bin, the workspace id it belongs to, and
     * the [SourcePools] pool its decompiles go to.
     */
    private class DerivedUnit(val dir: Path, val workspaceId: String, val variant: String, val poolKey: String) {

        val indexFile: Path get() = dir.resolve("index.bin")

        fun poolDir(derivedRoot: Path): Path = derivedRoot.resolve(variant).resolve(poolKey)

    }

    /**
     * Lists every derived unit from the layout the builders write, rather than searching for
     * index.bin files: vanilla at derived/<version>/<hash-dir> and forks at
     * derived/<fork>/<build>/<hash-dir> (see [DerivedCacheStore.directoryFor]). Searching meant
     * walking every cached decompile inside every unit and every pool - tens of thousands of files
     * on a well-used cache - while this costs one listing per version or build directory, however
     * many classes have been decompiled.
     */
    private fun listUnits(derivedRoot: Path): List<DerivedUnit> {
        val units = mutableListOf<DerivedUnit>()

        fun addUnitsIn(workspaceDir: Path, workspaceId: String, variant: String, poolKey: String) {
            for (hashDir in subdirectories(workspaceDir)) {
                if (Files.isRegularFile(hashDir.resolve("index.bin"))) {
                    units.add(DerivedUnit(hashDir, workspaceId, variant, poolKey))
                }
            }
        }

        for (top in subdirectories(derivedRoot)) {
            val name = top.fileName.toString()
            val variant = Variants.byId(name)
            when {
                variant == null ->
                    addUnitsIn(top, name, Variants.VANILLA, SourcePools.poolKey(Variants.VANILLA, name))

                // derived/vanilla holds vanilla's pools only; its units sit directly under derived/.
                variant.devBundleRepository == null -> continue

                else -> for (build in subdirectories(top)) {
                    val buildName = build.fileName.toString()
                    // Everything else at this level is a pool.
                    val mcVersion = devBundleMinecraftVersion(buildName) ?: continue
                    addUnitsIn(build, "$name/$buildName", name, SourcePools.poolKey(name, mcVersion))
                }
            }
        }
        return units
    }

    /**
     * Deletes all but the most recently used unit of each workspace and returns the survivors. Two
     * units for one workspace means a change to the index format or to how it's built
     * (DERIVED_CACHE_VERSION, FORK_INDEX_VERSION) left the old one behind: nothing loads it again,
     * and it predates key lists, so until it goes it would keep its pool from being swept.
     */
    private fun dropSupersededUnits(units: List<DerivedUnit>): List<DerivedUnit> =
        units.groupBy { it.workspaceId }.values.map { forWorkspace ->
            val current = forWorkspace.maxBy { Files.getLastModifiedTime(it.indexFile).toInstant() }
            for (unit in forWorkspace) if (unit !== current) deleteRecursively(unit.dir)
            current
        }

    // Under a fork's directory, builds and pools sit side by side; a build is named by its dev
    // bundle version, a pool by its Minecraft version. Vanilla's directory holds pools only.
    private fun isPoolDir(variant: Variant, name: String): Boolean =
        variant.devBundleRepository == null || devBundleMinecraftVersion(name) == null

    /**
     * Sweeps one pool: config-version directories no current code reads go, and so does every
     * entry none of [users] listed. A user with no key list yet (saved a moment ago, or from before
     * pools) could be using any entry, so then nothing is provably dead and the entries stay.
     */
    private fun sweepPool(poolDir: Path, users: List<DerivedUnit>) {
        val sourceCacheRoot = poolDir.resolve("source-cache")
        if (!Files.isDirectory(sourceCacheRoot)) return
        for (configDir in subdirectories(sourceCacheRoot)) {
            if (configDir.fileName.toString() != SOURCE_CACHE_CONFIG_VERSION) deleteRecursively(configDir)
        }

        val keep = HashSet<String>()
        for (unit in users) keep += SourcePools.readKeys(unit.dir) ?: return

        val entriesDir = sourceCacheRoot.resolve(SOURCE_CACHE_CONFIG_VERSION)
        if (!Files.isDirectory(entriesDir)) return
        Files.newDirectoryStream(entriesDir).use { entries ->
            for (entry in entries) {
                val name = entry.fileName.toString()
                val key = when {
                    name.endsWith(".tokens.json") -> name.removeSuffix(".tokens.json")
                    name.endsWith(".java") -> name.removeSuffix(".java")
                    // Anything else is not an entry - an in-flight write's temp file above all.
                    else -> continue
                }
                if (key in keep) continue
                try {
                    Files.deleteIfExists(entry)
                } catch (e: IOException) {
                    // Windows refuses to delete a file a reader has open; it goes next sweep.
                }
            }
        }
    }

    private fun subdirectories(dir: Path): List<Path> =
        Files.list(dir).use { entries -> entries.filter { Files.isDirectory(it) }.toList() }

    /**
     * Drops version, build, pool and variant directories that eviction left empty. Only those
     * levels: an empty directory inside a live unit or pool may be one a build just created and is
     * about to write into, and deleting it would fail that write.
     */
    private fun deleteEmptyWorkspaceDirs(derivedRoot: Path) {
        for (top in subdirectories(derivedRoot)) {
            if (Variants.byId(top.fileName.toString()) != null) {
                for (child in subdirectories(top)) deleteIfEmpty(child)
            }
            deleteIfEmpty(top)
        }
    }

    private fun deleteIfEmpty(dir: Path) {
        val empty = Files.newDirectoryStream(dir).use { !it.iterator().hasNext() }
        if (empty) Files.deleteIfExists(dir)
    }

    private fun directorySize(dir: Path): Long {
        if (!Files.exists(dir)) return 0
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
