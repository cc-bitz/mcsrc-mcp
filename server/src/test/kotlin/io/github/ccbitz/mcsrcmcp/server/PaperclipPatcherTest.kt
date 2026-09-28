package io.github.ccbitz.mcsrcmcp.server

import io.sigpipe.jbsdiff.Diff
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PaperclipPatcherTest {
    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    // A realistic single-entry paperclip bundle: location "versions", the whole jar patched.
    private fun paperclipJar(original: ByteArray, patched: ByteArray): ByteArray {
        val patch = ByteArrayOutputStream().use { out ->
            Diff.diff(original, patched, out)
            out.toByteArray()
        }
        val list = """
            versions	${sha256Hex(original)}	${sha256Hex(patch)}	${sha256Hex(patched)}	1.99/server-1.99.jar	1.99/server-1.99.jar.patch	1.99/paper-1.99.jar
        """.trimIndent()
        return zipOf(
            "META-INF/patches.list" to list.toByteArray(),
            "META-INF/versions/1.99/server-1.99.jar.patch" to patch,
        )
    }

    @Test
    fun `applies a real bsdiff round-trip and verifies every hash`() {
        val original = zipOf("a.class" to "original bytes".toByteArray())
        val patched = zipOf(
            "a.class" to "original bytes".toByteArray(),
            "b/Added.class" to "new class data".toByteArray(),
        )

        val result = PaperclipPatcher.apply(paperclipJar(original, patched), original)

        assertArrayEquals(patched, result)
    }

    @Test
    fun `a tampered original jar is rejected before any patch runs`() {
        val original = zipOf("a.class" to "original bytes".toByteArray())
        val patched = zipOf("a.class" to "changed".toByteArray())

        val tampered = zipOf("a.class" to "tampered bytes".toByteArray())
        val e = assertThrows<PaperclipPatchException> { PaperclipPatcher.apply(paperclipJar(original, patched), tampered) }
        assertTrue(e.message!!.contains("original jar hash mismatch"))
    }

    @Test
    fun `a tampered patch resource is rejected`() {
        val original = zipOf("a.class" to "original bytes".toByteArray())
        val patched = zipOf("a.class" to "changed".toByteArray())
        val good = paperclipJar(original, patched)
        // Flip one byte inside the patch payload - the list still says the old patch hash.
        val needle = "server-1.99.jar.patch".toByteArray()
        var idx = -1
        outer@ for (i in 0..good.size - needle.size) {
            for (j in needle.indices) {
                if (good[i + j] != needle[j]) continue@outer
            }
            idx = i
            break
        }
        check(idx >= 0) { "patch entry name not found in fixture" }
        good[idx + 100] = (good[idx + 100].toInt() xor 0x55).toByte()

        assertThrows<PaperclipPatchException> { PaperclipPatcher.apply(good, original) }
    }

    @Test
    fun `a bundle patching more than one original file is refused`() {
        val original = zipOf("a.class" to "a".toByteArray())
        val patch = ByteArrayOutputStream().use { out ->
            Diff.diff(original, original, out)
            out.toByteArray()
        }
        val list = """
            versions	${sha256Hex(original)}	${sha256Hex(patch)}	${sha256Hex(original)}	one.jar	one.patch	one-out.jar
            versions	${sha256Hex(original)}	${sha256Hex(patch)}	${sha256Hex(original)}	two.jar	two.patch	two-out.jar
        """.trimIndent()
        val bundle = zipOf(
            "META-INF/patches.list" to list.toByteArray(),
            "META-INF/versions/one.patch" to patch,
            "META-INF/versions/two.patch" to patch,
        )

        val e = assertThrows<PaperclipPatchException> { PaperclipPatcher.apply(bundle, original) }
        assertTrue(e.message!!.contains("unsupported paperclip bundle"))
    }

    @Test
    fun `extracts the nested server jar from a bundler-format download and verifies its hash`() {
        val nested = zipOf("net/minecraft/Old.class" to "real server classes".toByteArray())
        val bundler = zipOf(
            "META-INF/versions.list" to
                "${sha256Hex(nested)}\t1.99-test\t1.99-test/server-1.99-test.jar".toByteArray(),
            "META-INF/versions/1.99-test/server-1.99-test.jar" to nested,
            "META-INF/libraries/some/Library.jar" to "a library".toByteArray(),
        )

        assertArrayEquals(nested, PaperclipPatcher.extractVanillaServerJar(bundler))

        val wrongHash = zipOf(
            "META-INF/versions.list" to
                "${"0".repeat(64)}\t1.99-test\t1.99-test/server-1.99-test.jar".toByteArray(),
            "META-INF/versions/1.99-test/server-1.99-test.jar" to nested,
        )
        val e = assertThrows<PaperclipPatchException> { PaperclipPatcher.extractVanillaServerJar(wrongHash) }
        assertTrue(e.message!!.contains("nested server jar hash mismatch"))

        val notABundler = zipOf("net/minecraft/Old.class" to "x".toByteArray())
        assertThrows<PaperclipPatchException> { PaperclipPatcher.extractVanillaServerJar(notABundler) }
    }

    @Test
    fun `patches list parsing skips blanks and comments and needs all seven fields`() {
        val entries = PaperclipPatcher.parsePatchesList(
            """
            # a comment line
            ${" "}
            versions	aa	bb	cc	orig	pat	out
            """.trimIndent(),
        )
        assertEquals(1, entries.size)
        assertEquals("versions", entries[0].location)
        assertEquals("orig", entries[0].originalPath)
        assertEquals("out", entries[0].outputPath)
        assertTrue(entries[0].originalHash.contentEquals(byteArrayOf(0xaa.toByte())))

        assertThrows<IllegalArgumentException> { PaperclipPatcher.parsePatchesList("versions\taa\tbb\tcc") }
    }
}
