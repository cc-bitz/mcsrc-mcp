package io.github.ccbitz.mcsrcmcp.server

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ListPackageToolTest {
    private val classNames = listOf(
        "net/minecraft/world/level/Level",
        "net/minecraft/world/level/LevelAccessor",
        "net/minecraft/world/level/block/Block",
        "net/minecraft/world/level/block/Blocks",
        "net/minecraft/world/entity/Entity",
        "net/minecraft/world/level/Level\$ExplosionInteraction", // inner class, excluded from listings
    )

    @Test
    fun `lists immediate subpackages and classes non-recursively`() {
        val result = listPackageToolLogic(classNames, "net.minecraft.world.level")

        assertEquals(listOf("block"), result.subpackages)
        assertEquals(listOf("Level", "LevelAccessor"), result.classes)
        assertFalse(result.truncated)
    }

    @Test
    fun `lists all classes recursively, no subpackages`() {
        val result = listPackageToolLogic(classNames, "net.minecraft.world.level", recursive = true)

        assertTrue(result.subpackages.isEmpty())
        assertEquals(listOf("Level", "LevelAccessor", "block.Block", "block.Blocks"), result.classes)
    }

    @Test
    fun `root package lists top-level subpackages`() {
        val result = listPackageToolLogic(classNames, "net.minecraft")

        assertEquals(listOf("world"), result.subpackages)
        assertTrue(result.classes.isEmpty())
    }

    @Test
    fun `respects limit and reports truncation`() {
        val result = listPackageToolLogic(classNames, "net.minecraft.world.level", limit = 1)

        assertEquals(1, result.classes.size)
        assertTrue(result.truncated)
    }
}
