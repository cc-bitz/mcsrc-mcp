package io.github.ccbitz.mcsrcmcp.server

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class GetBytecodeToolTest {
    private fun loadFixture(internalName: String): ByteArray {
        val stream = javaClass.classLoader.getResourceAsStream("$internalName.class")
            ?: error("fixture class not found on test classpath: $internalName")
        return stream.readBytes()
    }

    private val classes = mapOf(
        "net/minecraft/Item" to loadFixture("net/minecraft/Item"),
    )

    @Test
    fun `prints bytecode by dotted name`() {
        val result = getBytecodeToolLogic(classes, "net.minecraft.Item")

        assertEquals("net.minecraft.Item", result.className)
        assertTrue(result.bytecode.contains("getMaxStackSize"))
        assertFalse(result.truncated)
    }

    @Test
    fun `unknown class throws`() {
        assertThrows(ClassNotFoundInIndexException::class.java) {
            getBytecodeToolLogic(classes, "net.minecraft.DoesNotExist")
        }
    }
}
