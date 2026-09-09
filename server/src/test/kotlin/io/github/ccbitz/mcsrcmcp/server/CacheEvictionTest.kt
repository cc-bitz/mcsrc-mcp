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
        val indexFile = dir.resolve("index.json")
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
        assertTrue(Files.exists(fresh.resolve("index.json")))
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
