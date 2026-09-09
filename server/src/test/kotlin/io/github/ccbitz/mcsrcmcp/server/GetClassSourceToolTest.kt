package io.github.ccbitz.mcsrcmcp.server

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class GetClassSourceToolTest {
    private fun loadFixture(internalName: String): ByteArray {
        val stream = javaClass.classLoader.getResourceAsStream("$internalName.class")
            ?: error("fixture class not found on test classpath: $internalName")
        return stream.readBytes()
    }

    private val classes = mapOf(
        "net/minecraft/Item" to loadFixture("net/minecraft/Item"),
        "net/minecraft/BlockItem" to loadFixture("net/minecraft/BlockItem"),
    )

    @Test
    fun `decompiles by dotted name and reports total line count`() {
        val result = getClassSourceToolLogic(classes, "net.minecraft.Item")

        assertEquals("net.minecraft.Item", result.className)
        assertTrue(result.source.contains("class Item"))
        assertTrue(result.totalLines > 0)
        assertFalse(result.truncated)
        assertEquals(1, result.startLine)
    }

    @Test
    fun `paginates with start_line and max_lines`() {
        val full = getClassSourceToolLogic(classes, "net.minecraft.Item")
        val firstLineOnly = getClassSourceToolLogic(classes, "net.minecraft.Item", startLine = 1, maxLines = 1)

        assertEquals(full.source.lines().first(), firstLineOnly.source)
        assertEquals(full.totalLines, firstLineOnly.totalLines)
        assertTrue(firstLineOnly.truncated || full.totalLines == 1)
    }

    @Test
    fun `unknown class throws`() {
        assertThrows(ClassNotFoundInIndexException::class.java) {
            getClassSourceToolLogic(classes, "net.minecraft.DoesNotExist")
        }
    }

    @Test
    fun `forwards sourceCacheDir to DecompileService`(@org.junit.jupiter.api.io.TempDir tempDir: Path) {
        Files.writeString(tempDir.resolve("net.minecraft.Item.java"), "SENTINEL_CACHED_CONTENT")

        val result = getClassSourceToolLogic(classes, "net.minecraft.Item", sourceCacheDir = tempDir)

        assertEquals("SENTINEL_CACHED_CONTENT", result.source)
    }
}
