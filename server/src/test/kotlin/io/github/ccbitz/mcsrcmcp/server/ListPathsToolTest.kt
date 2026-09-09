package io.github.ccbitz.mcsrcmcp.server

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ListPathsToolTest {
    private val assets = mapOf(
        "assets/minecraft/lang/en_us.json" to AssetInfo("assets/minecraft/lang/en_us.json", 42, true),
        "assets/minecraft/textures/block/stone.png" to AssetInfo("assets/minecraft/textures/block/stone.png", 2048, false),
        "data/minecraft/worldgen/biome/plains.json" to AssetInfo("data/minecraft/worldgen/biome/plains.json", 4300, true),
        "data/minecraft/recipes/bread.json" to AssetInfo("data/minecraft/recipes/bread.json", 300, true),
        "pack.mcmeta" to AssetInfo("pack.mcmeta", 100, true),
    )

    @Test
    fun `root listing shows the assets and data trees`() {
        val listing = listPathsToolLogic(assets)
        assertEquals(
            listOf("assets/", "data/", "pack.mcmeta"),
            listing.entries.map { if (it.isDirectory) it.path + "/" else it.path },
        )
        assertEquals("", listing.prefix)
        assertFalse(listing.truncated)
    }

    @Test
    fun `one level under a data prefix shows worldgen categories and keeps full paths`() {
        val listing = listPathsToolLogic(assets, "data/minecraft")
        assertEquals(
            listOf("data/minecraft/recipes", "data/minecraft/worldgen"),
            listing.entries.map { it.path },
        )
        assertTrue(listing.entries.all { it.isDirectory })
    }

    @Test
    fun `file level shows sizes and full addressable paths`() {
        val listing = listPathsToolLogic(assets, "data/minecraft/worldgen/biome")
        val entry = listing.entries.single()
        assertEquals("data/minecraft/worldgen/biome/plains.json", entry.path)
        assertEquals(4300, entry.sizeBytes)
        assertFalse(entry.isDirectory)
    }

    @Test
    fun `prefix with no matches lists nothing`() {
        assertTrue(listPathsToolLogic(assets, "nothing/here").entries.isEmpty())
    }

    @Test
    fun `limit truncates the listing`() {
        val listing = listPathsToolLogic(assets, "", limit = 2)
        assertEquals(2, listing.entries.size)
        assertTrue(listing.truncated)
    }
}
