package io.github.ccbitz.mcsrcmcp.server

import io.github.ccbitz.mcsrcmcp.core.Entry
import io.github.ccbitz.mcsrcmcp.core.MemberData
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SearchClassesToolTest {
    private val classNames = listOf(
        "net/minecraft/world/level/Level",
        "net/minecraft/world/level/LevelAccessor",
        "net/minecraft/world/level/LevelChunk",
        "net/minecraft/world/level/block/Block",
        "net/minecraft/world/level/Level\$ExplosionInteraction",
    )

    @Test
    fun `exact match scores first`() {
        val results = searchClassesToolLogic(classNames, "Level")
        assertEquals("net.minecraft.world.level.Level", results.first().className)
    }

    @Test
    fun `camelCase acronym matches`() {
        // "LevelChunk" -> acronym "LC"
        val results = searchClassesToolLogic(classNames, "lc")
        assertTrue(results.any { it.className == "net.minecraft.world.level.LevelChunk" })
    }

    @Test
    fun `substring match is included but ranked after prefix and acronym matches`() {
        val results = searchClassesToolLogic(classNames, "Chunk")
        assertEquals("net.minecraft.world.level.LevelChunk", results.first().className)
    }

    @Test
    fun `excludes inner classes from results`() {
        val results = searchClassesToolLogic(classNames, "Explosion")
        assertTrue(results.none { it.className.contains("$") })
    }

    @Test
    fun `empty query returns no results`() {
        assertTrue(searchClassesToolLogic(classNames, "").isEmpty())
    }

    @Test
    fun `respects limit`() {
        val results = searchClassesToolLogic(classNames, "e", limit = 2)
        assertEquals(2, results.size)
    }

    @Test
    fun `attaches bytecode size and member counts when the workspace data is given`() {
        val internalName = "net/minecraft/world/level/Level"
        val remappedClasses = mapOf(internalName to ByteArray(4096))
        val members = mapOf(
            internalName to MemberData(
                internalName,
                setOf(Entry.Method(internalName, "tick", "()V"), Entry.Method(internalName, "isRaining", "()Z")),
                setOf(Entry.Field(internalName, "isClientSide", "Z")),
            ),
        )

        val result = searchClassesToolLogic(classNames, "Level", remappedClasses = remappedClasses, members = members)
            .first { it.className == "net.minecraft.world.level.Level" }

        assertEquals(4096, result.size)
        assertEquals(2, result.nMethods)
        assertEquals(1, result.nFields)
    }

    @Test
    fun `bytecode size and member counts default to zero without workspace data`() {
        val result = searchClassesToolLogic(classNames, "Level").first()

        assertEquals(0, result.size)
        assertEquals(0, result.nMethods)
        assertEquals(0, result.nFields)
    }
}
