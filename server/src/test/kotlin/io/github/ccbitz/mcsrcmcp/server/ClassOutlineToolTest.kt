package io.github.ccbitz.mcsrcmcp.server

import io.github.ccbitz.mcsrcmcp.core.Indexer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ClassOutlineToolTest {
    private fun loadFixture(internalName: String): ByteArray {
        val stream = javaClass.classLoader.getResourceAsStream("$internalName.class")
            ?: error("fixture class not found on test classpath: $internalName")
        return stream.readBytes()
    }

    private fun buildIndex(): io.github.ccbitz.mcsrcmcp.core.IndexData {
        val indexer = Indexer()
        indexer.indexDeclarations(loadFixture("net/minecraft/Item"))
        indexer.indexDeclarations(loadFixture("net/minecraft/BlockItem"))
        return indexer.data()
    }

    @Test
    fun `returns outline with dotted names for a class with inheritance and members`() {
        val outline = getClassOutlineToolLogic(buildIndex(), "net.minecraft.BlockItem")

        assertEquals("net.minecraft.BlockItem", outline.className)
        assertEquals("net.minecraft.Item", outline.superName)
        assertTrue(outline.interfaces.isEmpty())
        // Includes the implicit constructor: Indexer indexes <init> like any other method
        // (needed later for find_references on constructors), sorts before "placeBlock" (`<` < `p`).
        assertEquals(listOf("<init>()V", "placeBlock()Z"), outline.methods)
        assertTrue(outline.fields.isEmpty())
    }

    @Test
    fun `includes fields for the declaring class`() {
        val outline = getClassOutlineToolLogic(buildIndex(), "net.minecraft.Item")

        assertEquals("net.minecraft.Item", outline.className)
        assertEquals(1, outline.fields.size)
        assertTrue(outline.fields[0].startsWith("maxStackSize"))
    }

    @Test
    fun `throws for an unknown class`() {
        assertThrows(ClassNotFoundInIndexException::class.java) {
            getClassOutlineToolLogic(buildIndex(), "net.minecraft.DoesNotExist")
        }
    }
}
