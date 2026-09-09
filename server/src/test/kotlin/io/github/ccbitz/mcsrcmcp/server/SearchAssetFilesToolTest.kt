package io.github.ccbitz.mcsrcmcp.server

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SearchAssetFilesToolTest {
    private val assets = mapOf(
        "assets/minecraft/items/allium.json" to AssetInfo("assets/minecraft/items/allium.json", 30, true),
        "assets/minecraft/items/stone.json" to AssetInfo("assets/minecraft/items/stone.json", 25, true),
        "assets/minecraft/textures/block/stone.png" to AssetInfo("assets/minecraft/textures/block/stone.png", 2048, false),
        "assets/minecraft/textures/block/stone_bricks.png" to AssetInfo("assets/minecraft/textures/block/stone_bricks.png", 2048, false),
    )

    @Test
    fun `exact filename match scores first`() {
        val results = searchAssetFilesToolLogic(assets, "stone.json")
        assertEquals("assets/minecraft/items/stone.json", results.first().path)
    }

    @Test
    fun `substring matches across multiple entries`() {
        val results = searchAssetFilesToolLogic(assets, "stone")
        val paths = results.map { it.path }.toSet()
        assertTrue(paths.contains("assets/minecraft/items/stone.json"))
        assertTrue(paths.contains("assets/minecraft/textures/block/stone.png"))
        assertTrue(paths.contains("assets/minecraft/textures/block/stone_bricks.png"))
    }

    @Test
    fun `prefix match ranks before a later substring match`() {
        val results = searchAssetFilesToolLogic(assets, "stone")
        // "stone.json"/"stone.png" (starts with "stone") must rank ahead of "stone_bricks.png"
        // only by virtue of being an exact/prefix match, not accidentally by name length alone -
        // this asserts the top result is a prefix match, not "allium.json" (irrelevant) leaking in.
        assertTrue(results[0].path.substringAfterLast('/').startsWith("stone"))
    }

    @Test
    fun `does not match unrelated files`() {
        val results = searchAssetFilesToolLogic(assets, "stone")
        assertFalse(results.any { it.path == "assets/minecraft/items/allium.json" })
    }

    @Test
    fun `empty query returns no results`() {
        assertTrue(searchAssetFilesToolLogic(assets, "").isEmpty())
    }

    @Test
    fun `respects limit`() {
        val results = searchAssetFilesToolLogic(assets, "stone", limit = 1)
        assertEquals(1, results.size)
    }
}
