package io.github.ccbitz.mcsrcmcp.server

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TokenCarryTest {

    private val original = """
        class Foo {
            void a() { bar(); }
            void b() { baz(); }
        }
    """.trimIndent() + "\n"

    private fun tokenAt(text: String, symbol: String, occurrence: Int = 0): SourceToken {
        var at = -1
        repeat(occurrence + 1) { at = text.indexOf(symbol, at + 1) }
        return SourceToken(at, symbol.length, TokenKind.METHOD, "Foo", TokenMember(symbol, "()V"), declaration = false)
    }

    private fun spanOf(text: String, token: SourceToken) = text.substring(token.start, token.start + token.length)

    @Test
    fun `a token on an unchanged line follows its line`() {
        val patch = "--- a/Foo.java\n+++ b/Foo.java\n@@ -1,2 +_,3 @@\n class Foo {\n+    int added;\n     void a() { bar(); }\n"
        val patched = SourcePatch.apply(original, patch)
        val bar = tokenAt(original, "bar")

        val carried = carryTokens(original, listOf(bar), patched.text, patched.originFor)

        assertEquals(1, carried.tokens.size)
        assertEquals("bar", spanOf(patched.text, carried.tokens.single()))
        assertEquals(setOf(2), carried.uncoveredLines)
    }

    @Test
    fun `a token on a changed line is dropped and the line reported uncovered`() {
        val patch = "--- a/Foo.java\n+++ b/Foo.java\n@@ -3,1 +_,1 @@\n-    void b() { baz(); }\n+    void b() { baz(1); }\n"
        val patched = SourcePatch.apply(original, patch)

        val carried = carryTokens(original, listOf(tokenAt(original, "bar"), tokenAt(original, "baz")), patched.text, patched.originFor)

        assertEquals(listOf("bar"), carried.tokens.map { spanOf(patched.text, it) })
        assertEquals(setOf(3), carried.uncoveredLines)
    }

    // mache's patches, then the fork's: carrying through each in turn is the composition.
    @Test
    fun `carrying twice composes both patches`() {
        val first = SourcePatch.apply(original, "--- a/F\n+++ b/F\n@@ -1,1 +_,2 @@\n class Foo {\n+    // one\n")
        val second = SourcePatch.apply(first.text, "--- a/F\n+++ b/F\n@@ -1,1 +_,2 @@\n class Foo {\n+    // two\n")

        val once = carryTokens(original, listOf(tokenAt(original, "baz")), first.text, first.originFor)
        val twice = carryTokens(first.text, once.tokens, second.text, second.originFor)

        assertEquals("baz", spanOf(second.text, twice.tokens.single()))
        assertEquals(5, LineIndex(second.text).lineOf(twice.tokens.single().start))
    }

    @Test
    fun `uncovered lines follow their line through a later patch`() {
        val patched = SourcePatch.apply(original, "--- a/F\n+++ b/F\n@@ -1,1 +_,2 @@\n class Foo {\n+    // added\n")

        assertEquals(setOf(4), carryLines(setOf(3), patched.originFor))
    }

    @Test
    fun `sidecar token lines parse into source tokens`() {
        val text = "10\t3\tmethod\tdemo/Foo\tbar\t()V\t0\n0\t5\tclass\tdemo/Foo\t-\t-\t1\n"

        assertEquals(
            listOf(
                SourceToken(10, 3, TokenKind.METHOD, "demo/Foo", TokenMember("bar", "()V"), declaration = false),
                SourceToken(0, 5, TokenKind.CLASS, "demo/Foo", null, declaration = true),
            ),
            parseSidecarTokens(text),
        )
    }

}
