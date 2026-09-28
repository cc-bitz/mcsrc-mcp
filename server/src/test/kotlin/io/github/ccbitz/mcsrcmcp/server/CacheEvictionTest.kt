package io.github.ccbitz.mcsrcmcp.server

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Duration
import java.time.Instant

class CacheEvictionTest {
    private fun makeDerivedDir(cacheRoot: Path, versionId: String, dirName: String, lastUsed: Instant): Path {
        val dir = cacheRoot.resolve("derived").resolve(versionId).resolve(dirName)
        Files.createDirectories(dir)
        val indexFile = dir.resolve("index.bin")
        Files.writeString(indexFile, "{}")
        Files.setLastModifiedTime(indexFile, FileTime.from(lastUsed))
        val sourceFile = dir.resolve("source").resolve("v1").resolve("net.minecraft.Foo.java")
        Files.createDirectories(sourceFile.parent)
        Files.writeString(sourceFile, "class Foo {}")
        return dir
    }

    @Test
    fun `evicts a derived directory whose index has not been read within the ttl`(@TempDir cacheRoot: Path) {
        val now = Instant.now()
        val stale = makeDerivedDir(cacheRoot, "1.0.0-old", "1-aaaaaaaaaaaa-none", now.minus(Duration.ofDays(60)))
        val fresh = makeDerivedDir(cacheRoot, "1.21.4", "1-bbbbbbbbbbbb-cccccccccccc", now.minus(Duration.ofDays(1)))

        CacheEviction.evictStale(cacheRoot, Duration.ofDays(30), now)

        assertFalse(Files.exists(stale))
        assertTrue(Files.exists(fresh))
        assertTrue(Files.exists(fresh.resolve("index.bin")))
    }

    @Test
    fun `evicting a derived directory also removes its source cache subdirectory`(@TempDir cacheRoot: Path) {
        val now = Instant.now()
        val stale = makeDerivedDir(cacheRoot, "1.0.0-old", "1-aaaaaaaaaaaa-none", now.minus(Duration.ofDays(60)))

        CacheEviction.evictStale(cacheRoot, Duration.ofDays(30), now)

        assertFalse(Files.exists(stale.resolve("source")))
    }

    @Test
    fun `removes now-empty version directories after evicting their only derived cache`(@TempDir cacheRoot: Path) {
        val now = Instant.now()
        makeDerivedDir(cacheRoot, "1.0.0-old", "1-aaaaaaaaaaaa-none", now.minus(Duration.ofDays(60)))

        CacheEviction.evictStale(cacheRoot, Duration.ofDays(30), now)

        assertFalse(Files.exists(cacheRoot.resolve("derived").resolve("1.0.0-old")))
    }

    @Test
    fun `does nothing when there is no derived directory yet`(@TempDir cacheRoot: Path) {
        assertDoesNotThrow {
            CacheEviction.evictStale(cacheRoot, Duration.ofDays(30))
        }
    }

    // A fork's shared source cache and latest marker belong to no derived unit; they go once no
    // build of their Minecraft version is left, and stay while one is.
    @Test
    fun `orphaned fork source caches and latest markers are dropped, live ones kept`(@TempDir cacheRoot: Path) {
        val derived = cacheRoot.resolve("derived").resolve("paper")

        val liveHashDir = derived.resolve("26.3.build.49").resolve("v2-serverhash-papercliphash")
        Files.createDirectories(liveHashDir)
        Files.writeString(liveHashDir.resolve("index.bin"), "{}")

        val liveSourceCache = derived.resolve("26.3").resolve("source-cache").resolve(SOURCE_CACHE_CONFIG_VERSION)
        Files.createDirectories(liveSourceCache)
        Files.writeString(liveSourceCache.resolve("a".repeat(40) + ".java"), "kept")
        Files.writeString(derived.resolve("26.3.latest"), "paper/26.3.build.49")

        val orphanSourceCache = derived.resolve("26.2").resolve("source-cache").resolve(SOURCE_CACHE_CONFIG_VERSION)
        Files.createDirectories(orphanSourceCache)
        Files.writeString(orphanSourceCache.resolve("b".repeat(40) + ".java"), "orphan")
        Files.writeString(derived.resolve("26.2.latest"), "paper/26.2.build.12")

        CacheEviction.evictStale(cacheRoot, Duration.ofDays(30))

        assertTrue(Files.exists(liveHashDir.resolve("index.bin")))
        assertTrue(Files.exists(liveSourceCache.resolve("a".repeat(40) + ".java")))
        assertTrue(Files.exists(derived.resolve("26.3.latest")))
        assertFalse(Files.exists(orphanSourceCache))
        assertFalse(Files.exists(derived.resolve("26.2.latest")))
    }

    @Test
    fun `stale fork builds are evicted and warm ones spared over budget`(@TempDir cacheRoot: Path) {
        val now = Instant.now()
        val stale = makeDerivedDir(cacheRoot, "paper/26.3.build.12", "v2-aaaa-bbbb", now.minus(Duration.ofDays(60)))
        val fresh = makeDerivedDir(cacheRoot, "paper/26.3.build.49", "v2-cccc-dddd", now.minus(Duration.ofDays(1)))

        CacheEviction.evictStale(cacheRoot, Duration.ofDays(30), now)

        assertFalse(Files.exists(stale.parent))
        assertTrue(Files.exists(fresh))

        Files.writeString(fresh.resolve("big.txt"), "x".repeat(2000))
        CacheEviction.evictOverBudget(cacheRoot, 1000, warmVersions = setOf("paper/26.3.build.49"))

        assertTrue(Files.exists(fresh))
    }

    // Units are found by layout, not by searching for index.bin: a shared source cache is never a
    // unit, whatever it holds, so the TTL pass leaves it to the orphan pass.
    @Test
    fun `a fork's shared source cache is never treated as a unit`(@TempDir cacheRoot: Path) {
        val now = Instant.now()
        makeDerivedDir(cacheRoot, "paper/26.3.build.49", "v2-cccc-dddd", now)
        val sourceCache = SourcePools.dir(cacheRoot, Variants.PAPER, "26.3")
        Files.createDirectories(sourceCache)
        val decoy = sourceCache.resolve("index.bin")
        Files.writeString(decoy, "{}")
        Files.setLastModifiedTime(decoy, FileTime.from(now.minus(Duration.ofDays(60))))

        CacheEviction.evictStale(cacheRoot, Duration.ofDays(30), now)

        assertTrue(Files.exists(decoy))
    }

    private fun writeKeys(unitDir: Path, vararg keys: String) {
        Files.writeString(unitDir.resolve(SourcePools.KEYS_FILE), keys.joinToString("\n"))
    }

    private fun writeEntry(pool: Path, key: String) {
        Files.createDirectories(pool)
        Files.writeString(pool.resolve("$key.java"), "class X {}")
        Files.writeString(pool.resolve("$key.tokens.json"), "[]")
    }

    // A class that changed in a later version leaves its old entry behind; once no version still
    // on disk lists that entry's key, it goes.
    @Test
    fun `pool entries no surviving unit lists are swept, shared ones kept`(@TempDir cacheRoot: Path) {
        val now = Instant.now()
        val rc = makeDerivedDir(cacheRoot, "26.3-rc-3", "2-aaaa-none", now.minus(Duration.ofDays(60)))
        val release = makeDerivedDir(cacheRoot, "26.3", "2-bbbb-none", now)
        writeKeys(rc, "a".repeat(40), "b".repeat(40))
        writeKeys(release, "b".repeat(40))
        val pool = SourcePools.dir(cacheRoot, Variants.VANILLA, "26.3")
        writeEntry(pool, "a".repeat(40))
        writeEntry(pool, "b".repeat(40))
        writeEntry(pool, "c".repeat(40))
        Files.writeString(pool.resolve("src-123.tmp"), "in-flight write")

        CacheEviction.evictStale(cacheRoot, Duration.ofDays(30), now)

        assertFalse(Files.exists(rc))
        assertFalse(Files.exists(pool.resolve("a".repeat(40) + ".java")), "only the evicted rc used this entry")
        assertFalse(Files.exists(pool.resolve("a".repeat(40) + ".tokens.json")))
        assertFalse(Files.exists(pool.resolve("c".repeat(40) + ".java")), "no unit used this entry")
        assertTrue(Files.exists(pool.resolve("b".repeat(40) + ".java")))
        assertTrue(Files.exists(pool.resolve("b".repeat(40) + ".tokens.json")))
        assertTrue(Files.exists(pool.resolve("src-123.tmp")), "in-flight writes are not the sweep's to delete")
    }

    @Test
    fun `a pool is left unswept while one of its units has no key list`(@TempDir cacheRoot: Path) {
        val now = Instant.now()
        makeDerivedDir(cacheRoot, "26.3", "2-bbbb-none", now)
        val pool = SourcePools.dir(cacheRoot, Variants.VANILLA, "26.3")
        writeEntry(pool, "c".repeat(40))

        CacheEviction.evictStale(cacheRoot, Duration.ofDays(30), now)

        assertTrue(Files.exists(pool.resolve("c".repeat(40) + ".java")))
    }

    @Test
    fun `a vanilla pool goes with the last version of its train`(@TempDir cacheRoot: Path) {
        val now = Instant.now()
        makeDerivedDir(cacheRoot, "26.2", "2-aaaa-none", now)
        writeEntry(SourcePools.dir(cacheRoot, Variants.VANILLA, "26.3"), "a".repeat(40))
        writeEntry(SourcePools.dir(cacheRoot, Variants.VANILLA, "26.2"), "b".repeat(40))

        CacheEviction.evictStale(cacheRoot, Duration.ofDays(30), now)

        assertFalse(Files.exists(cacheRoot.resolve("derived").resolve("vanilla").resolve("26.3")))
        assertTrue(Files.exists(cacheRoot.resolve("derived").resolve("vanilla").resolve("26.2")))
    }

    @Test
    fun `leftover per-unit sources and stale config versions are removed`(@TempDir cacheRoot: Path) {
        val now = Instant.now()
        val unit = makeDerivedDir(cacheRoot, "26.3", "2-bbbb-none", now)
        val staleConfig = cacheRoot.resolve("derived").resolve("vanilla").resolve("26.3").resolve("source-cache").resolve("v1")
        Files.createDirectories(staleConfig)
        Files.writeString(staleConfig.resolve("net.minecraft.Foo.java"), "old")
        writeEntry(SourcePools.dir(cacheRoot, Variants.VANILLA, "26.3"), "b".repeat(40))

        CacheEviction.evictStale(cacheRoot, Duration.ofDays(30), now)

        assertTrue(Files.exists(unit.resolve("index.bin")))
        assertFalse(Files.exists(unit.resolve("source")), "sources live in the pool now")
        assertFalse(Files.exists(staleConfig))
        assertTrue(Files.exists(SourcePools.dir(cacheRoot, Variants.VANILLA, "26.3").resolve("b".repeat(40) + ".java")))
    }

    // An indexing change leaves the build's old unit beside the new one; with no key list, it would
    // otherwise keep the pool unswept until the TTL took it.
    @Test
    fun `an older unit of the same workspace is dropped and stops blocking the pool sweep`(@TempDir cacheRoot: Path) {
        val now = Instant.now()
        val old = makeDerivedDir(cacheRoot, "paper/26.3.build.134-beta", "v2-server-oldbuild", now.minus(Duration.ofDays(2)))
        val current = makeDerivedDir(cacheRoot, "paper/26.3.build.134-beta", "v2-server-newbuild", now)
        writeKeys(current, "b".repeat(40))
        val pool = SourcePools.dir(cacheRoot, Variants.PAPER, "26.3")
        writeEntry(pool, "a".repeat(40))
        writeEntry(pool, "b".repeat(40))

        CacheEviction.evictStale(cacheRoot, Duration.ofDays(30), now)

        assertFalse(Files.exists(old))
        assertTrue(Files.exists(current))
        assertFalse(Files.exists(pool.resolve("a".repeat(40) + ".java")))
        assertTrue(Files.exists(pool.resolve("b".repeat(40) + ".java")))
    }

    @Test
    fun `superseded snapshots get the short ttl, releases the normal one`(@TempDir cacheRoot: Path) {
        val now = Instant.now()
        val snapshot = makeDerivedDir(cacheRoot, "26.3-rc-2", "2-aaaa-none", now.minus(Duration.ofDays(10)))
        val current = makeDerivedDir(cacheRoot, "26.4-snapshot-1", "2-cccc-none", now.minus(Duration.ofDays(10)))
        val release = makeDerivedDir(cacheRoot, "26.2", "2-bbbb-none", now.minus(Duration.ofDays(10)))

        CacheEviction.evictStale(cacheRoot, Duration.ofDays(30), now, Duration.ofDays(7), supersededSnapshots = setOf("26.3-rc-2"))

        assertFalse(Files.exists(snapshot))
        assertTrue(Files.exists(current), "the newest snapshot isn't superseded")
        assertTrue(Files.exists(release))
    }

    // The pools hold every decompiled class; leaving them out of the budget would let the cache
    // overrun it by a pool per train. Evicting a unit also shrinks its pool by what only it used.
    @Test
    fun `evictOverBudget counts pools and shrinks them with their units`(@TempDir cacheRoot: Path) {
        val now = Instant.now()
        val old = makeDerivedDir(cacheRoot, "26.3-rc-3", "2-aaaa-none", now.minus(Duration.ofDays(5)))
        val recent = makeDerivedDir(cacheRoot, "26.3", "2-bbbb-none", now)
        writeKeys(old, "a".repeat(40))
        writeKeys(recent, "b".repeat(40))
        val pool = SourcePools.dir(cacheRoot, Variants.VANILLA, "26.3")
        writeEntry(pool, "a".repeat(40))
        Files.writeString(pool.resolve("a".repeat(40) + ".java"), "x".repeat(3000))
        writeEntry(pool, "b".repeat(40))

        // Units alone fit; only the pool's big entry pushes the total over.
        CacheEviction.evictOverBudget(cacheRoot, 2000)

        assertFalse(Files.exists(old))
        assertFalse(Files.exists(pool.resolve("a".repeat(40) + ".java")))
        assertTrue(Files.exists(recent))
        assertTrue(Files.exists(pool.resolve("b".repeat(40) + ".java")))
    }

    @Test
    fun `resolveCacheTtl defaults to 30 days and honors the env override`() {
        assertEquals(Duration.ofDays(30), resolveCacheTtl(emptyMap()))
        assertEquals(Duration.ofDays(7), resolveCacheTtl(mapOf("MCSRC_MCP_CACHE_TTL_DAYS" to "7")))
        assertEquals(Duration.ofDays(30), resolveCacheTtl(mapOf("MCSRC_MCP_CACHE_TTL_DAYS" to "not-a-number")))
    }

    @Test
    fun `resolveCacheMaxSizeBytes defaults to 10GB and honors the env override`() {
        assertEquals(10L * 1024 * 1024 * 1024, resolveCacheMaxSizeBytes(emptyMap()))
        assertEquals(5L * 1024 * 1024 * 1024, resolveCacheMaxSizeBytes(mapOf("MCSRC_MCP_CACHE_MAX_SIZE_GB" to "5")))
        assertEquals(10L * 1024 * 1024 * 1024, resolveCacheMaxSizeBytes(mapOf("MCSRC_MCP_CACHE_MAX_SIZE_GB" to "not-a-number")))
    }

    @Test
    fun `evictOverBudget deletes least recently used derived directories until under budget`(@TempDir cacheRoot: Path) {
        val now = Instant.now()
        val old = makeDerivedDir(cacheRoot, "1.0.0-old", "1-aaaaaaaaaaaa-none", now.minus(Duration.ofDays(10)))
        val recent = makeDerivedDir(cacheRoot, "1.21.4", "1-bbbbbbbbbbbb-cccccccccccc", now)

        // Write enough data into the old directory to push the total over the small budget.
        Files.writeString(old.resolve("big.txt"), "x".repeat(2000))

        CacheEviction.evictOverBudget(cacheRoot, 1000)

        assertFalse(Files.exists(old))
        assertTrue(Files.exists(recent))
    }

    @Test
    fun `evictOverBudget never evicts warm versions`(@TempDir cacheRoot: Path) {
        val now = Instant.now()
        val warm = makeDerivedDir(cacheRoot, "1.21.4", "1-bbbbbbbbbbbb-cccccccccccc", now.minus(Duration.ofDays(10)))
        val oldCold = makeDerivedDir(cacheRoot, "1.0.0-old", "1-aaaaaaaaaaaa-none", now.minus(Duration.ofDays(5)))
        val recentCold = makeDerivedDir(cacheRoot, "1.20.1", "1-cccccccccccc-dddddddddddd", now)

        Files.writeString(warm.resolve("big.txt"), "x".repeat(2000))
        Files.writeString(oldCold.resolve("medium.txt"), "x".repeat(500))

        // Budget is less than warm+oldCold but more than warm alone, so the cold oldCold must be
        // evicted even though the even-larger warm version is older.
        CacheEviction.evictOverBudget(cacheRoot, 2200, warmVersions = setOf("1.21.4"))

        assertTrue(Files.exists(warm))
        assertFalse(Files.exists(oldCold))
        assertTrue(Files.exists(recentCold))
    }

    @Test
    fun `evictOverBudget does nothing when under budget`(@TempDir cacheRoot: Path) {
        val now = Instant.now()
        val dir = makeDerivedDir(cacheRoot, "1.21.4", "1-bbbbbbbbbbbb-cccccccccccc", now)

        CacheEviction.evictOverBudget(cacheRoot, 10L * 1024 * 1024 * 1024)

        assertTrue(Files.exists(dir))
    }

    @Test
    fun `evict combines ttl and size-budget passes`(@TempDir cacheRoot: Path) {
        val now = Instant.now()
        val staleBig = makeDerivedDir(cacheRoot, "1.0.0-old", "1-aaaaaaaaaaaa-none", now.minus(Duration.ofDays(60)))
        val recent = makeDerivedDir(cacheRoot, "1.21.4", "1-bbbbbbbbbbbb-cccccccccccc", now)

        Files.writeString(staleBig.resolve("big.txt"), "x".repeat(2000))

        CacheEviction.evict(cacheRoot, Duration.ofDays(30), 1000)

        assertFalse(Files.exists(staleBig))
        assertTrue(Files.exists(recent))
    }

    @Test
    fun `evictVersion deletes only the named version's derived cache`(@TempDir cacheRoot: Path) {
        val now = Instant.now()
        val target = makeDerivedDir(cacheRoot, "1.21.4", "1-aaaaaaaaaaaa-none", now)
        val other = makeDerivedDir(cacheRoot, "1.20.1", "1-bbbbbbbbbbbb-none", now)

        CacheEviction.evictVersion(cacheRoot, "1.21.4")

        assertFalse(Files.exists(target))
        assertFalse(Files.exists(cacheRoot.resolve("derived").resolve("1.21.4")))
        assertTrue(Files.exists(other))
    }

    // clear_cache on a fork drops its shared decompile cache this way - by then the directory is
    // full of entries, so a plain delete would throw DirectoryNotEmptyException.
    @Test
    fun `evictVersion removes a fork's populated shared source cache`(@TempDir cacheRoot: Path) {
        val sourceCache = SourcePools.dir(cacheRoot, Variants.PAPER, "26.3")
        Files.createDirectories(sourceCache)
        Files.writeString(sourceCache.resolve("a".repeat(40) + ".java"), "class Foo {}")

        CacheEviction.evictVersion(cacheRoot, "${Variants.PAPER}/26.3")

        assertFalse(Files.exists(cacheRoot.resolve("derived").resolve(Variants.PAPER).resolve("26.3")))
    }

    @Test
    fun `evictVersion leaves raw blobs untouched`(@TempDir cacheRoot: Path) {
        val now = Instant.now()
        makeDerivedDir(cacheRoot, "1.21.4", "1-aaaaaaaaaaaa-none", now)
        val blob = cacheRoot.resolve("blobs").resolve("ab").resolve("abcdef1234")
        Files.createDirectories(blob.parent)
        Files.writeString(blob, "raw jar bytes")

        CacheEviction.evictVersion(cacheRoot, "1.21.4")

        assertTrue(Files.exists(blob))
    }

    @Test
    fun `evictVersion does nothing when the version has no derived cache`(@TempDir cacheRoot: Path) {
        assertDoesNotThrow {
            CacheEviction.evictVersion(cacheRoot, "1.21.4")
        }
    }

    @Test
    fun `evictAllDerived deletes every version's derived cache but leaves blobs`(@TempDir cacheRoot: Path) {
        val now = Instant.now()
        val a = makeDerivedDir(cacheRoot, "1.21.4", "1-aaaaaaaaaaaa-none", now)
        val b = makeDerivedDir(cacheRoot, "1.20.1", "1-bbbbbbbbbbbb-none", now)
        val blob = cacheRoot.resolve("blobs").resolve("ab").resolve("abcdef1234")
        Files.createDirectories(blob.parent)
        Files.writeString(blob, "raw jar bytes")

        CacheEviction.evictAllDerived(cacheRoot)

        assertFalse(Files.exists(a))
        assertFalse(Files.exists(b))
        assertFalse(Files.exists(cacheRoot.resolve("derived")))
        assertTrue(Files.exists(blob))
    }
}
