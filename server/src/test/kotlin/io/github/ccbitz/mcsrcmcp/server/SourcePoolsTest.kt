package io.github.ccbitz.mcsrcmcp.server

import io.github.ccbitz.mcsrcmcp.cache.VersionListEntry
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class SourcePoolsTest {

    // Release to snapshot (92% reusable) and release to release (65%) are where the reuse is, and
    // fresh decompiles of every at-risk class across both showed no drift - so vanilla is one pool.
    @Test
    fun `every vanilla version shares one pool`() {
        val versions = listOf("26.3", "26.3-pre-2", "26.3-rc-3", "26.4-snapshot-1", "26.2", "1.21.4", "24w33a", "b1.7.3")
        assertEquals(1, versions.map { SourcePools.poolKey(Variants.VANILLA, it) }.toSet().size)
    }

    @Test
    fun `vanilla pools together, forks by exact Minecraft version`(@TempDir cacheRoot: Path) {
        val derived = cacheRoot.resolve("derived")
        assertEquals(
            derived.resolve("vanilla").resolve(SourcePools.VANILLA_POOL).resolve("source-cache").resolve(SOURCE_CACHE_CONFIG_VERSION),
            SourcePools.dir(cacheRoot, Variants.VANILLA, "26.3-rc-3"),
        )
        assertEquals(
            derived.resolve("paper").resolve("26.3").resolve("source-cache").resolve(SOURCE_CACHE_CONFIG_VERSION),
            SourcePools.dir(cacheRoot, Variants.PAPER, "26.3"),
        )
        assertNotEquals(SourcePools.dir(cacheRoot, Variants.PAPER, "26.3"), SourcePools.dir(cacheRoot, Variants.PAPER, "26.2"))
    }

    @Test
    fun `key list round-trips and is written once`(@TempDir unitDir: Path) {
        val classes = mapOf("a/A" to byteArrayOf(1), "a/A\$Inner" to byteArrayOf(2), "b/B" to byteArrayOf(3))

        assertNull(SourcePools.readKeys(unitDir))
        SourcePools.writeKeys(unitDir, classes)

        val expected = setOf(DecompileService.sourceCacheKey(classes, "a/A"), DecompileService.sourceCacheKey(classes, "b/B"))
        assertEquals(expected, SourcePools.readKeys(unitDir))

        // A unit's classes never change once saved, so a second write leaves the list alone.
        SourcePools.writeKeys(unitDir, mapOf("c/C" to byteArrayOf(4)))
        assertEquals(expected, SourcePools.readKeys(unitDir))
    }

    private fun entry(id: String, type: String, releaseTime: String) =
        VersionListEntry(id, type, "https://example.invalid/$id.json", releaseTime, releaseTime, "0".repeat(40))

    @Test
    fun `superseded snapshots are the non-releases something newer replaced`() {
        val versions = listOf(
            entry("26.4-snapshot-1", "snapshot", "2026-09-20T10:00:00+00:00"),
            entry("26.3", "release", "2026-09-10T10:00:00+00:00"),
            entry("26.3-rc-3", "snapshot", "2026-09-08T10:00:00+00:00"),
            entry("26.3-rc-2", "snapshot", "2026-09-05T10:00:00+00:00"),
            entry("26.2", "release", "2026-06-10T10:00:00+00:00"),
            entry("b1.7.3", "old_beta", "2011-07-08T00:00:00+00:00"),
        )

        // The newest snapshot is still current; releases never qualify, however old.
        assertEquals(setOf("26.3-rc-3", "26.3-rc-2", "b1.7.3"), supersededSnapshots(versions))
    }

}
