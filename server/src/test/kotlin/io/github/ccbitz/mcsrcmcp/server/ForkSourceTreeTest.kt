package io.github.ccbitz.mcsrcmcp.server

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ForkSourceTreeTest {

    private val foo = "package demo;\n\nclass Foo {\n    int a() { return b(); }\n}\n"
    private val bar = "package demo;\n\nclass Bar {\n}\n"
    private val baz = "package demo;\n\nclass Baz {\n    void c() { d(); }\n}\n"
    private val newFile = "package demo;\n\n// Paper's own class\nclass Added {\n}\n"

    private fun token(text: String, symbol: String) =
        SourceToken(text.indexOf(symbol), symbol.length, TokenKind.METHOD, "demo/X", TokenMember(symbol, "()V"), declaration = false)

    private fun zip(target: Path?, vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, text) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
        target?.let { Files.write(it, out.toByteArray()) }
        return out.toByteArray()
    }

    private fun macheTree(dir: Path): MacheTree {
        val path = dir.resolve("tree.zip")
        zip(
            path,
            "demo/Foo.java" to foo,
            "demo/Foo.tokens" to formatSidecarTokens(listOf(token(foo, "b"))),
            "demo/Bar.java" to bar,
            "demo/Bar.tokens" to "",
            "demo/Baz.java" to baz,
            "demo/Baz.tokens" to formatSidecarTokens(listOf(token(baz, "d"))),
            "demo/Baz.uncovered" to "4",
        )
        return MacheTree(path)
    }

    private val fooPatch = "--- a/demo/Foo.java\n+++ b/demo/Foo.java\n@@ -3,1 +_,2 @@\n class Foo {\n+    // Paper - a comment\n"
    private val barPatch = "--- a/demo/Bar.java\n+++ b/demo/Bar.java\n@@ -3,1 +_,1 @@\n-class NotBar {\n+class Bar2 {\n"

    private fun bundle(fooPatch: String = this.fooPatch) = zip(
        null,
        "patches/demo/Foo.java.patch" to fooPatch,
        "patches/demo/Bar.java.patch" to barPatch,
        "patches/demo/Added.java" to newFile,
        "patches/demo/notes.txt" to "not a source",
        "config.json" to "{}",
    )

    private fun entry(pool: Path, key: String): TreeEntry =
        Json.decodeFromString(Files.readString(pool.resolve("$key${ForkSourceTree.ENTRY_SUFFIX}")))

    @Test
    fun `patched, untouched and new classes land in the pool and a failed patch is left out`(@TempDir dir: Path) {
        val pool = dir.resolve("pool")

        val result = ForkSourceTree.build(macheTree(dir), bundle(), "patches", pool)

        assertEquals(setOf("demo/Foo", "demo/Baz", "demo/Added"), result.tree.keys)
        assertEquals(listOf("demo/Bar"), result.failed)
        assertTrue(Files.readString(pool.resolve(result.tree.getValue("demo/Foo") + ".java")).contains("// Paper - a comment"))
        assertEquals(newFile, Files.readString(pool.resolve(result.tree.getValue("demo/Added") + ".java")))
    }

    @Test
    fun `tokens follow the fork's patch and its lines are left for bytecode`(@TempDir dir: Path) {
        val pool = dir.resolve("pool")

        val result = ForkSourceTree.build(macheTree(dir), bundle(), "patches", pool)

        val fooEntry = entry(pool, result.tree.getValue("demo/Foo"))
        val patchedFoo = Files.readString(pool.resolve(result.tree.getValue("demo/Foo") + ".java"))
        assertEquals("b", patchedFoo.substring(fooEntry.carried.single().start, fooEntry.carried.single().start + 1))
        assertEquals(listOf(4), fooEntry.uncoveredLines)
        // Untouched by the fork: mache's uncovered line is still uncovered.
        assertEquals(listOf(4), entry(pool, result.tree.getValue("demo/Baz")).uncoveredLines)
        // The fork's own file has no tokens at all.
        assertEquals((1..5).toList(), entry(pool, result.tree.getValue("demo/Added")).uncoveredLines)
    }

    // The pool is content-keyed: a new build whose patches mostly didn't change writes only what did.
    @Test
    fun `a second build reuses every entry its patches didn't change`(@TempDir dir: Path) {
        val pool = dir.resolve("pool")
        val tree = macheTree(dir)
        val first = ForkSourceTree.build(tree, bundle(), "patches", pool)

        val changed = fooPatch.replace("a comment", "another comment")
        val second = ForkSourceTree.build(tree, bundle(changed), "patches", pool)

        assertEquals(1, second.written)
        assertEquals(first.tree.getValue("demo/Baz"), second.tree.getValue("demo/Baz"))
        assertTrue(first.tree.getValue("demo/Foo") != second.tree.getValue("demo/Foo"))
    }

    @Test
    fun `the manifest round-trips and its keys are kept for eviction`(@TempDir dir: Path) {
        val unit = Files.createDirectories(dir.resolve("unit"))
        Files.writeString(unit.resolve(SourcePools.KEYS_FILE), "classkey")
        assertNull(ForkSourceTree.readManifest(unit))

        ForkSourceTree.writeManifest(unit, mapOf("demo/Foo" to "k1", "demo/Added" to "k2"))

        assertEquals(mapOf("demo/Foo" to "k1", "demo/Added" to "k2"), ForkSourceTree.readManifest(unit))
        assertEquals(setOf("classkey", "k1", "k2"), SourcePools.readKeys(unit))
    }

}
