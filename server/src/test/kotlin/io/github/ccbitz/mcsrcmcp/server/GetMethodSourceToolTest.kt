package io.github.ccbitz.mcsrcmcp.server

import io.github.ccbitz.mcsrcmcp.core.Indexer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class GetMethodSourceToolTest {

    private val fixtures = listOf(
        "net/minecraft/Recipe",
        "net/minecraft/Recipe\$Visitor",
        "net/minecraft/Recipe\$Builder",
        "net/minecraft/Item",
        "net/minecraft/BlockItem",
    )

    private val classes: Map<String, ByteArray> = fixtures.associateWith { name ->
        javaClass.classLoader.getResourceAsStream("$name.class")?.readBytes()
            ?: error("fixture class not found on test classpath: $name")
    }

    private val indexData = Indexer().apply { classes.values.forEach { index(it) } }.data()

    private fun method(className: String, member: String, maxLines: Int = 1500): MethodSourceResult =
        when (val outcome = getMethodSourceToolLogic(indexData, classes, className, member, maxLines)) {
            is MethodSourceOutcome.Found -> outcome.result
            is MethodSourceOutcome.Ambiguous -> fail("expected one method, got candidates ${outcome.candidates}")
        }

    private fun classLines(internalName: String): List<String> = DecompileService.decompileClass(classes, internalName).lines()

    @Test
    fun `returns one method with its annotations, numbered as in the class source`() {
        val result = method("net.minecraft.Recipe", "toString")

        val lines = result.source.lines()
        assertEquals("@Override", lines.first().trim())
        assertTrue(lines.any { "return \"Recipe[\" + this.id + \"]\";" in it })
        assertEquals("}", lines.last().trim())
        // The numbers must be the class's own, so find_declaration and get_class_source agree.
        assertEquals(lines, classLines("net/minecraft/Recipe").subList(result.startLine - 1, result.endLine))
        assertEquals("toString()Ljava/lang/String;", result.member)
    }

    @Test
    fun `a name with overloads lists them to pick from`() {
        val outcome = getMethodSourceToolLogic(indexData, classes, "net.minecraft.Recipe", "render")

        assertEquals(
            MethodSourceOutcome.Ambiguous(listOf("render()Ljava/lang/String;", "render(I)Ljava/lang/String;")),
            outcome,
        )
    }

    // Braces inside a string and a char literal would end or extend a naive brace count. (The
    // fixture's comment brace doesn't survive compilation; the lexer's comment skip covers the
    // "// $VF: ..." notes Vineflower writes itself.)
    @Test
    fun `an overload picked by descriptor spans its whole body`() {
        val result = method("net.minecraft.Recipe", "render(I)Ljava/lang/String;")

        val text = result.source
        assertTrue("\"{{\"" in text && "'}'" in text, text)
        assertTrue("this.render()" in text, text)
        assertEquals("}", text.lines().last().trim())
        assertFalse("toString" in text)
        assertFalse("render()" in text.lines().first())
    }

    @Test
    fun `a constructor is reachable by init or by the class's simple name`() {
        val byInit = method("net.minecraft.Recipe", "<init>")
        val bySimpleName = method("net.minecraft.Recipe", "Recipe")

        assertTrue("this.id = id;" in byInit.source)
        assertEquals(byInit, bySimpleName)
    }

    @Test
    fun `a method of an inner class is found inside the outer class's source`() {
        val result = method("net.minecraft.Recipe\$Builder", "build")

        assertTrue("return new Recipe(this.id);" in result.source)
        assertEquals(result.source.lines(), classLines("net/minecraft/Recipe").subList(result.startLine - 1, result.endLine))
    }

    @Test
    fun `an abstract method is its one declaration line`() {
        val result = method("net.minecraft.Recipe\$Visitor", "visit")

        assertEquals(1, result.source.lines().size)
        assertTrue(result.source.trim().endsWith(";"))
    }

    @Test
    fun `an inherited method comes from the class that declares it`() {
        val result = method("net.minecraft.BlockItem", "getMaxStackSize")

        assertEquals("net.minecraft.Item", result.className)
        assertTrue("return this.maxStackSize;" in result.source)
    }

    @Test
    fun `max_lines truncates a long method and says so`() {
        val result = method("net.minecraft.Recipe", "render(I)Ljava/lang/String;", maxLines = 2)

        assertTrue(result.truncated)
        assertEquals(2, result.source.lines().size)
        assertEquals(result.startLine + 1, result.endLine)
    }

    // What compiled fixtures can't carry: comments (Vineflower writes its own "// $VF:" notes),
    // text blocks, escaped quotes, and braces inside an annotation argument in the signature.
    @Test
    fun `the body scan skips braces in comments, literals and the signature`() {
        val source = """
            void m(@A({1, 2}) int x) {
               // } not the end
               /* } nor this */
               String s = "\"}";
               String t = ""${'"'}
                  }
                  ""${'"'};
               char c = '\'';
               if (x > 0) { x--; }
            }
            void next() {}
        """.trimIndent()

        val end = methodEnd(source, source.indexOf("m(") + 1)
        assertEquals(source.indexOf("}\nvoid next"), end)
    }

    @Test
    fun `a body-less declaration ends at its semicolon`() {
        val source = "abstract void m(int x);\nvoid n() {}"
        assertEquals(source.indexOf(';'), methodEnd(source, source.indexOf("m(") + 1))
    }

    @Test
    fun `an unknown method throws`() {
        assertThrows(MemberNotFoundException::class.java) {
            getMethodSourceToolLogic(indexData, classes, "net.minecraft.Recipe", "doesNotExist")
        }
    }

}
