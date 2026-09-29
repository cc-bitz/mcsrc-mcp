package io.github.ccbitz.mcsrcmcp.server

import io.github.ccbitz.mcsrcmcp.cache.BlobStore
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MacheTreeTest {

    private val repo = "https://repo.example/"
    private val mache = MacheRef(repo, listOf("io.papermc:mache:26.3+build.1"))

    private val decompiled = "package demo;\n\npublic class Foo {\n    int bar() { return baz(); }\n}\n"
    private val bazStart = decompiled.indexOf("baz")

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
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

    private fun sha1(bytes: ByteArray) = MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun macheJson(dependencies: String, remapperArgs: String) = """
        {
          "minecraftVersion": "26.3",
          "macheVersion": "26.3+build.1",
          "dependencies": { $dependencies },
          "repositories": [ { "url": "$repo", "name": "PaperMC", "groups": [ "io.papermc" ] } ],
          "decompilerArgs": [ "--decompile-inner=true" ],
          "remapperArgs": [ $remapperArgs ]
        }
    """.trimIndent()

    private val supportedJson = macheJson(
        """
        "codebook": [ { "group": "io.papermc.codebook", "name": "codebook-cli", "version": "2.0.1", "classifier": "all" } ],
        "constants": [ { "group": "io.papermc.unpick-definitions", "name": "unpick-definitions", "version": "26.3-pre-2+build.1" } ],
        "decompiler": [ { "group": "org.vineflower", "name": "vineflower", "version": "1.12.0" } ]
        """,
        """ "--temp-dir={tempDir}", "--unpick-file={constantsFile}", "--output={output}", "--input={input}", "--input-classpath={inputClasspath}" """,
    )

    // 1.21.x: codebook also remaps, which needs mappings, parameter names and a remapper jar.
    private val obfuscatedJson = macheJson(
        """
        "codebook": [ { "group": "io.papermc.codebook", "name": "codebook-cli", "version": "1.0.18", "classifier": "all" } ],
        "paramMappings": [ { "group": "io.papermc.parchment.data", "name": "parchment", "version": "1" } ],
        "decompiler": [ { "group": "org.vineflower", "name": "vineflower", "version": "1.11.2" } ]
        """,
        """ "--mappings-file={mappingsFile}", "--output={output}", "--input={input}" """,
    )

    private val machePatch = "--- a/demo/Foo.java\n+++ b/demo/Foo.java\n@@ -1,2 +_,3 @@\n package demo;\n+// mache\n \n"

    private class Repo(val files: Map<String, ByteArray>) : BlobFetcher {

        override suspend fun fetch(url: String): ByteArray = files[url] ?: error("HTTP 404 fetching $url")

    }

    private fun repoWith(macheJson: String): Repo {
        val files = mutableMapOf<String, ByteArray>()
        fun publish(path: String, bytes: ByteArray) {
            files[repo + path] = bytes
            files["$repo$path.sha1"] = sha1(bytes).toByteArray()
        }
        publish(
            "io/papermc/mache/26.3%2Bbuild.1/mache-26.3%2Bbuild.1.zip",
            zip("mache.json" to macheJson.toByteArray(), "patches/demo/Foo.java.patch" to machePatch.toByteArray()),
        )
        publish("io/papermc/codebook/codebook-cli/2.0.1/codebook-cli-2.0.1-all.jar", "codebook".toByteArray())
        publish("io/papermc/unpick-definitions/unpick-definitions/26.3-pre-2%2Bbuild.1/unpick-definitions-26.3-pre-2%2Bbuild.1.zip", "defs".toByteArray())
        files["https://repo.maven.apache.org/maven2/org/vineflower/vineflower/1.12.0/vineflower-1.12.0.jar"] = "vf".toByteArray()
        files["https://repo.maven.apache.org/maven2/org/vineflower/vineflower/1.12.0/vineflower-1.12.0.jar.sha1"] = sha1("vf".toByteArray()).toByteArray()
        return Repo(files)
    }

    private fun bundlerJar(dir: Path): Path {
        val server = zip("demo/Foo.class" to byteArrayOf(1, 2, 3))
        val versions = "${PaperclipPatcher.sha256Hex(server)}\t26.3\t26.3/server-26.3.jar\n"
        val jar = dir.resolve("bundler.jar")
        Files.write(
            jar,
            zip(
                "META-INF/versions.list" to versions.toByteArray(),
                "META-INF/versions/26.3/server-26.3.jar" to server,
                "META-INF/libraries/org/example/lib/1/lib-1.jar" to zip("x.class" to byteArrayOf(0)),
            ),
        )
        return jar
    }

    // Stands in for codebook and the decompiler: writes what each would, and counts the runs.
    private inner class FakeChildren : ChildRunner {

        var codebookRuns = 0
        var decompilerRuns = 0

        override suspend fun run(command: List<String>, workDir: Path, timeout: Duration) {
            if (command.any { it.startsWith("--unpick-file=") }) {
                codebookRuns++
                val output = command.first { it.startsWith("--output=") }.removePrefix("--output=")
                Files.write(workDir.resolve(output), zip("demo/Foo.class" to byteArrayOf(1)))
            } else {
                decompilerRuns++
                val mainIndex = command.indexOf(MacheTreeBuilder.DECOMPILER_MAIN)
                val out = Path.of(command[mainIndex + 1])
                Files.createDirectories(out.resolve("demo"))
                Files.writeString(out.resolve("demo/Foo.java"), decompiled)
                Files.writeString(out.resolve("demo/Foo.tokens"), "$bazStart\t3\tmethod\tdemo/Foo\tbaz\t()I\t0\n")
            }
        }

    }

    @Test
    fun `a mache that needs mappings is unsupported and nothing runs`(@TempDir dir: Path) = runTest {
        val children = FakeChildren()
        val builder = MacheTreeBuilder(dir, MavenArtifacts(repoWith(obfuscatedJson), BlobStore(dir.resolve("blobs"))), children)

        assertNull(builder.treeFor("26.3", mache, bundlerJar(dir)))
        assertEquals(0, children.codebookRuns + children.decompilerRuns)
    }

    @Test
    fun `the tree holds mache-patched sources with their tokens carried`(@TempDir dir: Path) = runTest {
        val builder = MacheTreeBuilder(dir, MavenArtifacts(repoWith(supportedJson), BlobStore(dir.resolve("blobs"))), FakeChildren())

        val tree = builder.treeFor("26.3", mache, bundlerJar(dir))!!
        val (text, tokens) = tree.read("demo/Foo")!!

        assertTrue(text.contains("// mache"))
        assertEquals("baz", text.substring(tokens.single().start, tokens.single().start + 3))
        assertEquals(listOf("demo/Foo"), tree.classes())
        // The line mache's patch wrote has no Vineflower tokens; it's left for bytecode resolution.
        assertEquals(setOf(2), tree.read("demo/Foo")!!.uncoveredLines)
    }

    @Test
    fun `concurrent requests for one version build it once`(@TempDir dir: Path) = runTest {
        val children = FakeChildren()
        val builder = MacheTreeBuilder(dir, MavenArtifacts(repoWith(supportedJson), BlobStore(dir.resolve("blobs"))), children)
        val jar = bundlerJar(dir)

        val trees = (1..3).map { async { builder.treeFor("26.3", mache, jar) } }.awaitAll()

        assertTrue(trees.all { it != null })
        assertEquals(1, children.codebookRuns)
        assertEquals(1, children.decompilerRuns)
    }

    @Test
    fun `a built tree is reused without running anything`(@TempDir dir: Path) = runTest {
        val artifacts = MavenArtifacts(repoWith(supportedJson), BlobStore(dir.resolve("blobs")))
        MacheTreeBuilder(dir, artifacts, FakeChildren()).treeFor("26.3", mache, bundlerJar(dir))

        val children = FakeChildren()
        assertNotNull(MacheTreeBuilder(dir, artifacts, children).treeFor("26.3", mache, bundlerJar(dir)))
        assertEquals(0, children.codebookRuns + children.decompilerRuns)
    }

}
