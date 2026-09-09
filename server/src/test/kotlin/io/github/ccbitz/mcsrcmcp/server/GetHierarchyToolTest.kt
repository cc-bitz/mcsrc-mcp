package io.github.ccbitz.mcsrcmcp.server

import io.github.ccbitz.mcsrcmcp.core.Indexer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class GetHierarchyToolTest {
    private fun loadFixture(internalName: String): ByteArray {
        val stream = javaClass.classLoader.getResourceAsStream("$internalName.class")
            ?: error("fixture class not found on test classpath: $internalName")
        return stream.readBytes()
    }

    private fun buildIndex(): io.github.ccbitz.mcsrcmcp.core.IndexData {
        val indexer = Indexer()
        for (name in listOf("net/minecraft/Animal", "net/minecraft/Dog", "net/minecraft/Bird", "net/minecraft/Sparrow")) {
            indexer.indexDeclarations(loadFixture(name))
        }
        return indexer.data()
    }

    @Test
    fun `supertypes includes both superclass and interfaces`() {
        val result = getHierarchyToolLogic(buildIndex(), "net.minecraft.Sparrow", direction = "supertypes")

        assertEquals("net.minecraft.Sparrow", result.className)
        assertEquals("supertypes", result.direction)
        val parentNames = result.root.children.map { it.className }.toSet()
        assertEquals(setOf("net.minecraft.Animal", "net.minecraft.Bird"), parentNames)
    }

    @Test
    fun `supertypes stops at classes not present in the index, eg java-lang-Object`() {
        val result = getHierarchyToolLogic(buildIndex(), "net.minecraft.Animal", direction = "supertypes")

        assertTrue(result.root.children.isEmpty(), "Animal's superclass is java.lang.Object, which is never indexed and must not appear")
    }

    @Test
    fun `subtypes finds classes that declare this class as a supertype`() {
        val result = getHierarchyToolLogic(buildIndex(), "net.minecraft.Animal", direction = "subtypes")

        val childNames = result.root.children.map { it.className }.toSet()
        assertTrue(childNames.containsAll(setOf("net.minecraft.Dog", "net.minecraft.Sparrow")))
    }

    @Test
    fun `depth limits how many levels are expanded`() {
        val depthZero = getHierarchyToolLogic(buildIndex(), "net.minecraft.Sparrow", direction = "supertypes", depth = 0)
        assertTrue(depthZero.root.children.isEmpty())

        val depthOne = getHierarchyToolLogic(buildIndex(), "net.minecraft.Sparrow", direction = "supertypes", depth = 1)
        assertEquals(2, depthOne.root.children.size)
        assertTrue(depthOne.root.children.all { it.children.isEmpty() })
    }

    @Test
    fun `unknown class throws`() {
        assertThrows(ClassNotFoundInIndexException::class.java) {
            getHierarchyToolLogic(buildIndex(), "net.minecraft.DoesNotExist")
        }
    }

    @Test
    fun `invalid direction throws`() {
        assertThrows(InvalidHierarchyDirectionException::class.java) {
            getHierarchyToolLogic(buildIndex(), "net.minecraft.Sparrow", direction = "sideways")
        }
    }
}
