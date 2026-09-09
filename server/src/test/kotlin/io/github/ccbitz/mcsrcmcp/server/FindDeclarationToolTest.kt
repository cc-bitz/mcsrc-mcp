package io.github.ccbitz.mcsrcmcp.server

import io.github.ccbitz.mcsrcmcp.core.BytecodePrinter
import io.github.ccbitz.mcsrcmcp.core.Indexer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FindDeclarationToolTest {
    private fun loadFixture(internalName: String): ByteArray {
        val stream = javaClass.classLoader.getResourceAsStream("$internalName.class")
            ?: error("fixture class not found on test classpath: $internalName")
        return stream.readBytes()
    }

    private val classes = listOf(
        "net/minecraft/Item",
        "net/minecraft/BlockItem",
        "net/minecraft/Chest",
        "net/minecraft/Barrel",
        "net/minecraft/Storage",
    ).associateWith { loadFixture(it) }

    private val indexData = Indexer().apply { classes.keys.forEach { index(loadFixture(it)) } }.data()

    // Line numbers move whenever Vineflower's formatting does, so tests locate their use site by
    // what it says rather than pinning a number that would rot.
    private fun lineContaining(internalName: String, text: String): Int {
        val source = DecompileService.decompileWithTokens(classes, internalName).source
        val index = source.lines().indexOfFirst { it.contains(text) }
        check(index >= 0) { "no line containing '$text' in decompiled $internalName" }
        return index + 1
    }

    @Test
    fun `resolves a call at a use site up to the class that actually declares it`() {
        val line = lineContaining("net/minecraft/Storage", "getMaxStackSize")

        val result = findDeclarationToolLogic(indexData, classes, "net.minecraft.Storage", UseSite.SourceLine(line))

        val target = result.targets.single { it.symbol == "getMaxStackSize" }
        assertEquals("net.minecraft.BlockItem", target.owner, "the call site's static type is BlockItem")
        assertEquals("net.minecraft.Item", target.declaringClass, "but Item is what declares the method")
        assertEquals("getMaxStackSize()I", target.member)
    }

    @Test
    fun `two same-named calls to different classes on one line stay distinct`() {
        val line = lineContaining("net/minecraft/Storage", "chest.size()")

        val result = findDeclarationToolLogic(indexData, classes, "net.minecraft.Storage", UseSite.SourceLine(line))

        // Nothing in the text tells these apart - both read "size" - so resolving by name against
        // the index would collapse them onto one class. The owners come off Vineflower's tokens.
        val sizeCalls = result.targets.filter { it.symbol == "size" }
        assertEquals(2, sizeCalls.size, "expected both size() calls on the line, got: $sizeCalls")
        assertEquals(listOf("net.minecraft.Chest", "net.minecraft.Barrel"), sizeCalls.map { it.declaringClass })
        assertTrue(sizeCalls[0].column < sizeCalls[1].column, "targets are ordered by column")
    }

    @Test
    fun `reports the line the declaration sits on in the declaring class`() {
        val line = lineContaining("net/minecraft/Storage", "getMaxStackSize")

        val result = findDeclarationToolLogic(indexData, classes, "net.minecraft.Storage", UseSite.SourceLine(line))

        val target = result.targets.single { it.symbol == "getMaxStackSize" }
        val itemSource = DecompileService.decompileWithTokens(classes, "net/minecraft/Item").source
        val declarationLine = target.declarationLine
        assertNotNull(declarationLine)
        assertTrue(
            itemSource.lines()[declarationLine!! - 1].contains("getMaxStackSize"),
            "declarationLine must point at the declaration in Item's own source, got line $declarationLine",
        )
        assertTrue(target.declarationSnippet!!.contains("getMaxStackSize"))
    }

    @Test
    fun `symbol narrows a line down to one of its symbols`() {
        val line = lineContaining("net/minecraft/Storage", "chest.size()")

        val result = findDeclarationToolLogic(indexData, classes, "net.minecraft.Storage", UseSite.SourceLine(line, symbol = "chest"))

        assertEquals(listOf("chest"), result.targets.map { it.symbol })
    }

    @Test
    fun `column picks the symbol under it`() {
        val line = lineContaining("net/minecraft/Storage", "chest.size()")
        val all = findDeclarationToolLogic(indexData, classes, "net.minecraft.Storage", UseSite.SourceLine(line))
        val second = all.targets.filter { it.symbol == "size" }[1]

        val result = findDeclarationToolLogic(indexData, classes, "net.minecraft.Storage", UseSite.SourceLine(line, column = second.column + 1))

        assertEquals(listOf(second.column), result.targets.map { it.column })
        assertEquals("net.minecraft.Barrel", result.targets.single().declaringClass)
    }

    @Test
    fun `a line past the end of the class is rejected`() {
        assertThrows(LineOutOfRangeException::class.java) {
            findDeclarationToolLogic(indexData, classes, "net.minecraft.Storage", UseSite.SourceLine(100_000))
        }
    }

    @Test
    fun `an unknown class throws`() {
        assertThrows(ClassNotFoundInIndexException::class.java) {
            findDeclarationToolLogic(indexData, classes, "net.minecraft.DoesNotExist", UseSite.SourceLine(1))
        }
    }

    private fun bytecodeLineContaining(internalName: String, text: String): Int {
        val printed = BytecodePrinter.print(*arrayOf(classes.getValue(internalName)))
        val index = printed.lines().indexOfFirst { it.contains(text) }
        check(index >= 0) { "no bytecode line containing '$text' in $internalName" }
        return index + 1
    }

    @Test
    fun `resolves the target of an instruction at a bytecode line`() {
        val line = bytecodeLineContaining("net/minecraft/Storage", "INVOKEVIRTUAL net/minecraft/BlockItem.getMaxStackSize")

        val result = findDeclarationToolLogic(indexData, classes, "net.minecraft.Storage", UseSite.BytecodeLine(line))

        val target = result.targets.single()
        assertEquals("net.minecraft.BlockItem", target.owner)
        assertEquals("net.minecraft.Item", target.declaringClass)
        assertEquals("getMaxStackSize()I", target.member)
        assertNotNull(target.declarationLine, "a bytecode-resolved target still reports where the declaration is")
    }

    @Test
    fun `resolves a field instruction at a bytecode line`() {
        val line = bytecodeLineContaining("net/minecraft/Storage", "maxStackSize")

        val result = findDeclarationToolLogic(indexData, classes, "net.minecraft.Storage", UseSite.BytecodeLine(line))

        val target = result.targets.single()
        assertEquals(TokenKind.FIELD, target.kind)
        assertEquals("net.minecraft.Item", target.declaringClass)
        assertEquals("maxStackSize: I", target.member)
    }

    @Test
    fun `a bytecode line holding no reference resolves to nothing`() {
        val line = bytecodeLineContaining("net/minecraft/Storage", "MAXSTACK")

        val result = findDeclarationToolLogic(indexData, classes, "net.minecraft.Storage", UseSite.BytecodeLine(line))

        assertTrue(result.targets.isEmpty(), "MAXSTACK names no class or member, got: ${result.targets}")
    }
}
