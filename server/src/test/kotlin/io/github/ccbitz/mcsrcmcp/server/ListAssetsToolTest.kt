package io.github.ccbitz.mcsrcmcp.server

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ListAssetsToolTest {
    private val assets = mapOf(
        "assets/minecraft/lang/en_us.json" to AssetInfo("assets/minecraft/lang/en_us.json", 42, true),
        "assets/minecraft/items/allium.json" to AssetInfo("assets/minecraft/items/allium.json", 30, true),
        "assets/minecraft/textures/block/stone.png" to AssetInfo("assets/minecraft/textures/block/stone.png", 2048, false),
    )

    @Test
    fun `lists all assets when prefix is empty`() {
        val result = listAssetsToolLogic(assets, "")
        assertEquals(3, result.assets.size)
        assertFalse(result.truncated)
    }

    @Test
    fun `filters by path prefix`() {
        val result = listAssetsToolLogic(assets, "assets/minecraft/items")
        assertEquals(1, result.assets.size)
        assertEquals("assets/minecraft/items/allium.json", result.assets[0].path)
    }

    @Test
    fun `reports isText per entry`() {
        val result = listAssetsToolLogic(assets, "assets/minecraft/textures")
        assertEquals(1, result.assets.size)
        assertFalse(result.assets[0].isText)
    }

    @Test
    fun `respects limit and reports truncation`() {
        val result = listAssetsToolLogic(assets, "", limit = 1)
        assertEquals(1, result.assets.size)
        assertTrue(result.truncated)
    }
}
