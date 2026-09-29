package io.github.ccbitz.mcsrcmcp.server

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SourcePatchTest {

    private val original = (1..10).joinToString("\n", postfix = "\n") { "line $it" }

    @Test
    fun `a modified line maps nowhere and its neighbours keep their origin`() {
        val patch = """
            --- a/Foo.java
            +++ b/Foo.java
            @@ -3,3 +_,3 @@
             line 3
            -line 4
            +line four
             line 5
        """.trimIndent()

        val patched = SourcePatch.apply(original, patch)

        assertEquals(original.replace("line 4\n", "line four\n"), patched.text)
        assertArrayEquals(intArrayOf(1, 2, 3, 0, 5, 6, 7, 8, 9, 10), patched.originFor)
    }

    @Test
    fun `added and removed lines shift everything after them`() {
        val patch = """
            --- a/Foo.java
            +++ b/Foo.java
            @@ -2,3 +2,4 @@
             line 2
            +added a
            +added b
             line 3
            -line 4
            @@ -8,2 +_,2 @@
             line 8
            -line 9
            +nine
        """.trimIndent()

        val patched = SourcePatch.apply(original, patch)

        val expected = listOf("line 1", "line 2", "added a", "added b", "line 3", "line 5", "line 6", "line 7", "line 8", "nine", "line 10")
        assertEquals(expected.joinToString("\n", postfix = "\n"), patched.text)
        assertArrayEquals(intArrayOf(1, 2, 0, 0, 3, 5, 6, 7, 8, 0, 10), patched.originFor)
    }

    // Paper's patches are applied strictly, the way paperweight applies them: a context or removed
    // line that doesn't match means this isn't the text the patch was made against.
    @Test
    fun `a mismatched context line fails the patch`() {
        val patch = """
            --- a/Foo.java
            +++ b/Foo.java
            @@ -3,2 +_,2 @@
             line 3
            -line 5
            +five
        """.trimIndent()

        assertThrows<PatchFailedException> { SourcePatch.apply(original, patch) }
    }

    @Test
    fun `a hunk past the end of the file fails the patch`() {
        val patch = """
            --- a/Foo.java
            +++ b/Foo.java
            @@ -10,2 +_,2 @@
             line 10
            -line 11
            +eleven
        """.trimIndent()

        assertThrows<PatchFailedException> { SourcePatch.apply(original, patch) }
    }

    @Test
    fun `a blank context line is a single space`() {
        val text = "a\n\nb\n"
        val patch = "--- a/F.java\n+++ b/F.java\n@@ -1,3 +_,3 @@\n a\n \n-b\n+c\n"

        assertEquals("a\n\nc\n", SourcePatch.apply(text, patch).text)
    }

}
