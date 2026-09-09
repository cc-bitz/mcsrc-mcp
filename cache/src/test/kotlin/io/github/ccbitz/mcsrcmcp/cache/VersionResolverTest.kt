package io.github.ccbitz.mcsrcmcp.cache

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class VersionResolverTest {
    private fun entry(id: String, type: String, releaseTime: String) =
        VersionListEntry(id, type, "https://example.invalid/$id.json", releaseTime, releaseTime, "0".repeat(40))

    private val versions = listOf(
        entry("1.21.3", "release", "2024-10-23T00:00:00+00:00"),
        entry("1.21.4", "release", "2024-12-03T00:00:00+00:00"),
        entry("24w50a", "snapshot", "2024-12-11T00:00:00+00:00"),
        entry("1.14.4", "release", "2019-10-14T00:00:00+00:00"),
        entry("1.14_combat-3", "old_snapshot", "2019-06-13T00:00:00+00:00"),
    )

    @Test
    fun `resolves exact id`() {
        assertEquals("1.14.4", VersionResolver.resolve("1.14.4", versions).id)
    }

    @Test
    fun `latest and latest-release pick the newest release`() {
        assertEquals("1.21.4", VersionResolver.resolve("latest", versions).id)
        assertEquals("1.21.4", VersionResolver.resolve("latest-release", versions).id)
    }

    @Test
    fun `latest-snapshot picks the newest release-or-snapshot`() {
        assertEquals("24w50a", VersionResolver.resolve("latest-snapshot", versions).id)
    }

    @Test
    fun `unique prefix resolves`() {
        assertEquals("24w50a", VersionResolver.resolve("24w50", versions).id)
    }

    @Test
    fun `ambiguous prefix throws with candidates`() {
        val ex = assertThrows(AmbiguousVersionException::class.java) {
            VersionResolver.resolve("1.21", versions)
        }
        assertEquals(setOf("1.21.3", "1.21.4"), ex.candidates.toSet())
    }

    @Test
    fun `unknown query throws`() {
        assertThrows(UnknownVersionException::class.java) {
            VersionResolver.resolve("nope", versions)
        }
    }

    @Test
    fun `versions released on or after 2025-12-16 are unobfuscated by default`() {
        val before = entry("25.1-snapshot-9", "snapshot", "2025-12-15T23:59:59+00:00")
        val onCutover = entry("26.1-snapshot-1", "snapshot", "2025-12-16T00:00:00+00:00")
        val after = entry("26.1-snapshot-2", "snapshot", "2025-12-20T00:00:00+00:00")

        assertFalse(isUnobfuscatedByDefault(before))
        assertTrue(isUnobfuscatedByDefault(onCutover))
        assertTrue(isUnobfuscatedByDefault(after))
    }

    @Test
    fun `known legacy unobfuscated ids and rd- prefix are unobfuscated`() {
        assertTrue(isUnobfuscatedByDefault(entry("c0.0.13a", "release", "2009-05-31T00:00:00+00:00")))
        assertTrue(isUnobfuscatedByDefault(entry("c0.0.11a", "release", "2009-05-16T00:00:00+00:00")))
        assertTrue(isUnobfuscatedByDefault(entry("rd-132211", "release", "2009-05-13T00:00:00+00:00")))
    }

    @Test
    fun `normal modern release is not unobfuscated`() {
        assertFalse(isUnobfuscatedByDefault(entry("1.21.4", "release", "2024-12-03T00:00:00+00:00")))
    }
}
