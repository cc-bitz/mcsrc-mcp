package io.github.ccbitz.mcsrcmcp.server

import io.github.ccbitz.mcsrcmcp.cache.VersionListEntry
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ListVersionsToolTest {
    private fun entry(id: String, type: String, releaseTime: String) =
        VersionListEntry(id, type, "https://example.invalid/$id.json", releaseTime, releaseTime, "0".repeat(40))

    private val versions = listOf(
        entry("1.21.3", "release", "2024-10-23T00:00:00+00:00"),
        entry("1.21.4", "release", "2024-12-03T00:00:00+00:00"),
        entry("24w50a", "snapshot", "2024-12-11T00:00:00+00:00"),
    )

    @Test
    fun `sorts newest first and marks obfuscation`() {
        val result = listVersionsToolLogic(versions)
        assertEquals(listOf("24w50a", "1.21.4", "1.21.3"), result.map { it.id })
        assertTrue(result.all { it.obfuscated })
    }

    @Test
    fun `filters by type`() {
        val result = listVersionsToolLogic(versions, typeFilter = "release")
        assertEquals(listOf("1.21.4", "1.21.3"), result.map { it.id })
    }

    @Test
    fun `respects limit`() {
        val result = listVersionsToolLogic(versions, limit = 1)
        assertEquals(1, result.size)
        assertEquals("24w50a", result[0].id)
    }
}
