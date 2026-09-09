package io.github.ccbitz.mcsrcmcp.cache

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Paths

class CacheRootTest {
    @Test
    fun `env override wins on any OS`() {
        val path = CacheRoot.resolve(mapOf("MCSRC_MCP_CACHE_DIR" to "/custom/cache"), "Windows 11")
        assertEquals(Paths.get("/custom/cache"), path)
    }

    @Test
    fun `windows uses LOCALAPPDATA`() {
        val path = CacheRoot.resolve(mapOf("LOCALAPPDATA" to "C:\\Users\\me\\AppData\\Local"), "Windows 11")
        assertEquals(Paths.get("C:\\Users\\me\\AppData\\Local", "mcsrc-mcp", "cache"), path)
    }

    @Test
    fun `mac uses Library Caches`() {
        val path = CacheRoot.resolve(mapOf("HOME" to "/Users/me"), "Mac OS X")
        assertEquals(Paths.get("/Users/me", "Library", "Caches", "mcsrc-mcp"), path)
    }

    @Test
    fun `linux uses XDG_CACHE_HOME when set`() {
        val path = CacheRoot.resolve(mapOf("XDG_CACHE_HOME" to "/home/me/.cache"), "Linux")
        assertEquals(Paths.get("/home/me/.cache", "mcsrc-mcp"), path)
    }
}
