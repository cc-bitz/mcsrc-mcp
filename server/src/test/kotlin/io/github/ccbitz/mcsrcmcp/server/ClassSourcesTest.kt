package io.github.ccbitz.mcsrcmcp.server

import io.github.ccbitz.mcsrcmcp.core.IndexData
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider

class ClassSourcesTest {

    // Stands in for a fork's patched source: comments a decompile of the bytecode can never show.
    private val thing = """
        package demo;

        // Paper - a comment only the source has
        public class Thing {
            int size;

            public int grow(int by) {
                this.size += by; // Paper - grow
                return helper(by);
            }

            private int helper(int x) {
                return x * 2;
            }
        }
    """.trimIndent() + "\n"

    private val other = "package demo;\n\npublic class Other {\n    public int value() {\n        return 1;\n    }\n}\n"

    private class Fixture(val classes: Map<String, ByteArray>, val index: IndexData, val pool: Path, val sources: TreeSources)

    private fun fixture(dir: Path, publish: Boolean = true): Fixture {
        val src = Files.createDirectories(dir.resolve("src/demo"))
        Files.writeString(src.resolve("Thing.java"), thing)
        Files.writeString(src.resolve("Other.java"), other)
        val out = Files.createDirectories(dir.resolve("out"))
        val exit = ToolProvider.getSystemJavaCompiler().run(null, null, null, "-g", "-d", out.toString(), src.resolve("Thing.java").toString(), src.resolve("Other.java").toString())
        assertEquals(0, exit)
        val classes = Files.walk(out).use { s -> s.filter { it.toString().endsWith(".class") }.toList() }
            .associate { out.relativize(it).toString().replace('\\', '/').removeSuffix(".class") to Files.readAllBytes(it) }
        val (indexer, _) = runBlocking { WorkspaceIndexing.indexMappedClasses(classes.values.toList()) }

        // Thing is in the tree with no carried tokens - every line left to bytecode, like a fork's own
        // class. Other isn't, so it has to come from a decompile.
        val pool = Files.createDirectories(dir.resolve("pool"))
        Files.writeString(pool.resolve("k1.java"), thing)
        Files.writeString(pool.resolve("k1${ForkSourceTree.ENTRY_SUFFIX}"), Json.encodeToString(TreeEntry(emptyList(), (1..lineCount(thing)).toList())))

        val sources = TreeSources(classes, indexer.data(), pool, DecompiledSources(classes, dir.resolve("decompiled")))
        if (publish) sources.publish(mapOf("demo/Thing" to "k1"))
        return Fixture(classes, indexer.data(), pool, sources)
    }

    @Test
    fun `a class in the tree is served from its source, comments and all`(@TempDir dir: Path) {
        val f = fixture(dir)

        assertEquals(thing, f.sources.source("demo/Thing"))
        assertTrue(f.sources.withTokens("demo/Thing").tokens.any { it.member?.name == "helper" })
    }

    @Test
    fun `a class the tree lacks is decompiled`(@TempDir dir: Path) {
        val f = fixture(dir)

        val source = f.sources.source("demo/Other")
        assertTrue(source.contains("class Other"))
        assertFalse(source.contains("Paper"))
    }

    // Reads before the tree is ready get the decompile; nothing waits on the tree but the search index.
    @Test
    fun `before the tree is published every class is decompiled`(@TempDir dir: Path) {
        val f = fixture(dir, publish = false)

        assertFalse(f.sources.source("demo/Thing").contains("// Paper"))
        f.sources.publish(mapOf("demo/Thing" to "k1"))
        assertTrue(f.sources.source("demo/Thing").contains("// Paper"))
    }

    @Test
    fun `awaitSettled returns once the tree failed as well as once it's published`(@TempDir dir: Path) = runBlocking<Unit> {
        val f = fixture(dir, publish = false)
        f.sources.fail()
        withTimeout(1_000) { f.sources.awaitSettled() }
        assertFalse(f.sources.source("demo/Thing").contains("// Paper"))
    }

    @Test
    fun `an inner class name serves its outer class's source`(@TempDir dir: Path) {
        val f = fixture(dir)

        assertEquals(thing, f.sources.source("demo/Thing\$Inner"))
    }

    @Test
    fun `get_class_source, get_method_source and find_declaration answer from the tree`(@TempDir dir: Path) {
        val f = fixture(dir)

        val classSource = getClassSourceToolLogic(f.classes, "demo.Thing", sources = f.sources)
        assertTrue(classSource.source.contains("// Paper - grow"))

        val method = getMethodSourceToolLogic(f.index, f.classes, "demo.Thing", "grow", sources = f.sources) as MethodSourceOutcome.Found
        assertTrue(method.result.source.contains("// Paper - grow"))
        assertEquals(7, method.result.startLine)

        val callLine = thing.lines().indexOfFirst { it.contains("return helper(by);") } + 1
        val declaration = findDeclarationToolLogic(f.index, f.classes, "demo.Thing", UseSite.SourceLine(callLine, "helper"), sources = f.sources)
        val target = declaration.targets.single()
        assertEquals("helper(I)I", target.member)
        assertEquals(thing.lines().indexOfFirst { it.contains("private int helper") } + 1, target.declarationLine)
    }

}
