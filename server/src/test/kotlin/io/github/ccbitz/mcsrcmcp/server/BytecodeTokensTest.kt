package io.github.ccbitz.mcsrcmcp.server

import io.github.ccbitz.mcsrcmcp.core.IndexData
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider

/**
 * Resolves symbols on source lines from the class's own bytecode, the way a fork's patched lines are
 * resolved: the fixture is compiled with -g from exactly the text the tokens are checked against, as
 * Paper's jar is compiled from its patched source.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BytecodeTokensTest {

    private val widget = """
        package demo;

        import java.util.ArrayList;
        import java.util.List;

        public class Widget extends Base implements Runnable {
            public static final int LIMIT = 16;
            private final List<String> names = new ArrayList<>();
            private int count;

            public Widget(String first) {
                super(first.length());
                this.names.add(first);
            }

            public void add(String name) {
                this.names.add(name);
                this.count++;
            }

            public void add(String name, int times) {
                for (int i = 0; i < times; i++) {
                    add(name);
                }
            }

            @Override
            public void run() {
                Object value = this.names.get(0);
                String text = (String) value; // add(value) in a comment
                System.out.println("add(" + text + ")");
                Runnable again = this::run;
                int limit = Base.MAX + LIMIT;
            }

            abstract static class Shape {
                abstract int area();

                abstract int area(int scale);
            }
        }
    """.trimIndent() + "\n"

    private val base = """
        package demo;

        public class Base {
            public static final int MAX = 4;

            protected Base(int size) {
            }
        }
    """.trimIndent() + "\n"

    private lateinit var nest: Map<String, ByteArray>
    private lateinit var index: IndexData
    private lateinit var tokens: List<SourceToken>

    @BeforeAll
    fun compile() {
        val dir = Files.createTempDirectory("bytecode-tokens")
        val src = Files.createDirectories(dir.resolve("src/demo"))
        Files.writeString(src.resolve("Widget.java"), widget)
        Files.writeString(src.resolve("Base.java"), base)
        val out = Files.createDirectories(dir.resolve("out"))
        val exit = ToolProvider.getSystemJavaCompiler().run(null, null, null, "-g", "-d", out.toString(), src.resolve("Widget.java").toString(), src.resolve("Base.java").toString())
        assertEquals(0, exit)

        val classes = Files.walk(out).use { s -> s.filter { it.toString().endsWith(".class") }.toList() }
            .associate { out.relativize(it).toString().replace('\\', '/').removeSuffix(".class") to Files.readAllBytes(it) }
        val (indexer, _) = runBlocking { WorkspaceIndexing.indexMappedClasses(classes.values.toList()) }
        index = indexer.data()
        nest = classes.filterKeys { it == "demo/Widget" || it.startsWith("demo/Widget$") }
        tokens = BytecodeTokens.derive(widget, (1..lineCount(widget)).toSet(), nest, index)
    }

    private fun lineOf(text: String, occurrence: Int = 0): Int {
        var at = -1
        repeat(occurrence + 1) { at = widget.indexOf(text, at + 1) }
        check(at >= 0) { "fixture has no \"$text\"" }
        return LineIndex(widget).lineOf(at)
    }

    private fun on(line: Int): List<Pair<String, SourceToken>> {
        val lines = LineIndex(widget)
        return tokens.filter { lines.lineOf(it.start) == line }.map { widget.substring(it.start, it.start + it.length) to it }
    }

    private fun only(line: Int, symbol: String): SourceToken {
        val found = on(line).filter { it.first == symbol }
        assertEquals(1, found.size, "tokens for \"$symbol\" on line $line: $found")
        return found.single().second
    }

    @Test
    fun `calls and field accesses resolve to their exact owner and descriptor`() {
        val line = lineOf("this.names.add(first)")

        assertEquals(TokenMember("names", "Ljava/util/List;"), only(line, "names").member)
        assertEquals("demo/Widget", only(line, "names").className)
        assertEquals(TokenMember("add", "(Ljava/lang/Object;)Z"), only(line, "add").member)
        assertEquals("java/util/List", only(line, "add").className)
    }

    // The loop body's call - the first "add(name);" in the text is names.add(name).
    private val loopCall get() = lineOf("add(name);", occurrence = 1)

    @Test
    fun `an overloaded call resolves to the overload the compiler picked`() {
        val call = only(loopCall, "add")

        assertEquals(TokenMember("add", "(Ljava/lang/String;)V"), call.member)
        assertEquals(false, call.declaration)
    }

    @Test
    fun `method and constructor declarations carry their descriptors`() {
        val single = only(lineOf("public void add(String name) {"), "add")
        val double = only(lineOf("public void add(String name, int times)"), "add")
        val constructor = only(lineOf("public Widget(String first)"), "Widget")

        assertEquals(TokenMember("add", "(Ljava/lang/String;)V") to true, single.member to single.declaration)
        assertEquals(TokenMember("add", "(Ljava/lang/String;I)V") to true, double.member to double.declaration)
        assertEquals(TokenMember("<init>", "(Ljava/lang/String;)V") to true, constructor.member to constructor.declaration)
    }

    // Abstract methods have no code, so no line numbers: they're matched in declaration order.
    @Test
    fun `code-less methods are declared in source order`() {
        val first = only(lineOf("abstract int area();"), "area")
        val second = only(lineOf("abstract int area(int scale);"), "area")

        assertEquals("demo/Widget\$Shape", first.className)
        assertEquals("()I", first.member!!.descriptor)
        assertEquals("(I)I", second.member!!.descriptor)
        assertTrue(first.declaration && second.declaration)
    }

    @Test
    fun `field declarations are found with and without an initializer`() {
        val names = only(lineOf("private final List<String> names"), "names")
        val count = only(lineOf("private int count;"), "count")
        val limit = only(lineOf("public static final int LIMIT"), "LIMIT")

        assertEquals(TokenMember("names", "Ljava/util/List;") to true, names.member to names.declaration)
        assertEquals(TokenMember("count", "I") to true, count.member to count.declaration)
        assertEquals(TokenMember("LIMIT", "I") to true, limit.member to limit.declaration)
    }

    @Test
    fun `class declarations resolve to the nest class they name`() {
        assertEquals("demo/Widget" to true, only(lineOf("public class Widget"), "Widget").let { it.className to it.declaration })
        assertEquals("demo/Widget\$Shape" to true, only(lineOf("abstract static class Shape"), "Shape").let { it.className to it.declaration })
    }

    @Test
    fun `a cast names its type and locals resolve as locals`() {
        val line = lineOf("String text = (String) value;")

        val strings = on(line).filter { it.first == "String" }
        assertTrue(strings.isNotEmpty() && strings.all { it.second.kind == TokenKind.CLASS && it.second.className == "java/lang/String" }, "$strings")
        assertEquals(TokenKind.LOCAL, only(line, "value").kind)
        assertEquals(TokenKind.LOCAL, only(line, "text").kind)
    }

    @Test
    fun `names inside strings and comments are not symbols`() {
        val comment = lineOf("// add(value) in a comment")
        val string = lineOf("System.out.println(\"add(\"")

        assertEquals(emptyList<String>(), on(comment).filter { it.first == "add" }.map { it.first })
        assertEquals(emptyList<String>(), on(string).filter { it.first == "add" }.map { it.first })
        assertEquals(TokenMember("println", "(Ljava/lang/String;)V"), only(string, "println").member)
    }

    @Test
    fun `a method reference resolves like a call`() {
        assertEquals(TokenMember("run", "()V"), only(lineOf("this::run"), "run").member)
    }

    // Constants are inlined, so there's no instruction naming them: they resolve through the index.
    @Test
    fun `constants resolve through the type that qualifies them, or the nest`() {
        val line = lineOf("int limit = Base.MAX + LIMIT;")

        assertEquals("demo/Base" to TokenMember("MAX", "I"), only(line, "MAX").let { it.className to it.member })
        assertEquals("demo/Widget" to TokenMember("LIMIT", "I"), only(line, "LIMIT").let { it.className to it.member })
        assertEquals("demo/Base", only(line, "Base").className)
    }

    @Test
    fun `a type named only in a declaration resolves through its import`() {
        val list = on(lineOf("private final List<String> names")).first { it.first == "List" }.second

        assertEquals(TokenKind.CLASS to "java/util/List", list.kind to list.className)
    }

    // A carried Vineflower token whose member the fork no longer has - Paper changed a callee's
    // signature but not this call line - must not be served; its line gets resolved from bytecode.
    @Test
    fun `a carried token for a member the fork doesn't declare is dropped and its line returned`() {
        val stale = SourceToken(LineIndex(widget).startOf(loopCall)!! + widget.lines()[loopCall - 1].indexOf("add"), 3, TokenKind.METHOD, "demo/Widget", TokenMember("add", "(I)V"), declaration = false)
        val fine = SourceToken(widget.indexOf("names.add(first)"), 5, TokenKind.FIELD, "demo/Widget", TokenMember("names", "Ljava/util/List;"), declaration = false)
        val jdk = SourceToken(widget.indexOf("add(first)"), 3, TokenKind.METHOD, "java/util/List", TokenMember("add", "(Ljava/lang/Object;)Z"), declaration = false)

        val (kept, lines) = BytecodeTokens.verifyCarried(listOf(stale, fine, jdk), index, widget)

        assertEquals(listOf(fine, jdk), kept)
        assertEquals(setOf(loopCall), lines)
    }

    @Test
    fun `only the requested lines get tokens`() {
        val line = lineOf("this.count++;")
        val some = BytecodeTokens.derive(widget, setOf(line), nest, index)

        val lines = LineIndex(widget)
        assertTrue(some.isNotEmpty())
        assertTrue(some.all { lines.lineOf(it.start) == line })
    }

}
