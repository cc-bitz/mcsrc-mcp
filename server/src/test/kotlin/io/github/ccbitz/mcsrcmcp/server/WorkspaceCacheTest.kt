package io.github.ccbitz.mcsrcmcp.server

import io.github.ccbitz.mcsrcmcp.core.IndexData
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class WorkspaceCacheTest {
    private fun workspace(id: String) = VersionWorkspace(id, IndexData.empty(), null, emptyMap(), io.github.ccbitz.mcsrcmcp.core.Indexer(), emptyMap(), EmptyAssetSource, null)

    @Test
    fun `evicts the least recently put version beyond capacity`() {
        val cache = WorkspaceCache(maxWarm = 3)
        cache.put("a", workspace("a"))
        cache.put("b", workspace("b"))
        cache.put("c", workspace("c"))
        cache.put("d", workspace("d")) // evicts "a"

        assertNull(cache.get("a"))
        assertNotNull(cache.get("b"))
        assertNotNull(cache.get("c"))
        assertNotNull(cache.get("d"))
        assertEquals(listOf("b", "c", "d"), cache.warmVersions())
    }

    @Test
    fun `re-putting an existing version refreshes its position`() {
        val cache = WorkspaceCache(maxWarm = 2)
        cache.put("a", workspace("a"))
        cache.put("b", workspace("b"))
        cache.put("a", workspace("a")) // refresh "a"
        cache.put("c", workspace("c")) // should evict "b", not "a"

        assertNull(cache.get("b"))
        assertNotNull(cache.get("a"))
        assertNotNull(cache.get("c"))
    }

    @Test
    fun `remove drops one version without disturbing the others`() {
        val cache = WorkspaceCache(maxWarm = 3)
        cache.put("a", workspace("a"))
        cache.put("b", workspace("b"))

        cache.remove("a")

        assertNull(cache.get("a"))
        assertNotNull(cache.get("b"))
        assertEquals(listOf("b"), cache.warmVersions())
    }

    @Test
    fun `remove of a version not present does nothing`() {
        val cache = WorkspaceCache(maxWarm = 3)
        cache.put("a", workspace("a"))

        cache.remove("nonexistent")

        assertNotNull(cache.get("a"))
    }

    @Test
    fun `clear drops every version`() {
        val cache = WorkspaceCache(maxWarm = 3)
        cache.put("a", workspace("a"))
        cache.put("b", workspace("b"))

        cache.clear()

        assertNull(cache.get("a"))
        assertNull(cache.get("b"))
        assertEquals(emptyList<String>(), cache.warmVersions())
    }
}
