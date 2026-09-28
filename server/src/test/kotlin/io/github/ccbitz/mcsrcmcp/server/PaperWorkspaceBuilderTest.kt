package io.github.ccbitz.mcsrcmcp.server

import io.github.ccbitz.mcsrcmcp.cache.BlobStore
import io.github.ccbitz.mcsrcmcp.cache.DownloadArtifact
import io.github.ccbitz.mcsrcmcp.cache.VersionDetail
import io.github.ccbitz.mcsrcmcp.cache.VersionDownloads
import io.github.ccbitz.mcsrcmcp.cache.VersionListEntry
import io.sigpipe.jbsdiff.Diff
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// The full paper pipeline against fakes that mirror the real artifacts' shapes: a dev bundle zip
// (config.json + mojang-mapped paperclip jar), a paperclip bundle (patches.list + one whole-jar
// bsdiff), and Mojang's server jar it patches. Proves the pieces wire together into an indexed
// workspace without ever touching the network.
class PaperWorkspaceBuilderTest {
    private fun sha1(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun tinyClassBytes(internalName: String, fieldType: String? = null): ByteArray {
        val writer = org.objectweb.asm.ClassWriter(0)
        writer.visit(
            org.objectweb.asm.Opcodes.V17,
            org.objectweb.asm.Opcodes.ACC_PUBLIC,
            internalName,
            null,
            "java/lang/Object",
            null,
        )
        if (fieldType != null) {
            writer.visitField(org.objectweb.asm.Opcodes.ACC_PUBLIC, "ref", "L$fieldType;", null, null).visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

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

    private class RecordingFetcher(private val bytesByUrl: Map<String, ByteArray>) : BlobFetcher {
        var callCount = 0
            private set

        override suspend fun fetch(url: String): ByteArray {
            callCount++
            return bytesByUrl[url] ?: error("unexpected fetch: $url")
        }
    }

    @Test
    fun `builds an indexed workspace from dev bundle + server jar`(@TempDir tempDir: Path) = runTest {
        val vanillaNested = zipOf("net/minecraft/Old.class" to tinyClassBytes("net/minecraft/Old"))
        // Mojang ships the server as a bundler wrapper; paperclip patches the nested jar.
        val serverJar = zipOf(
            "META-INF/versions.list" to
                "${sha256Hex(vanillaNested)}\t1.99-test\t1.99-test/server-1.99-test.jar".toByteArray(),
            "META-INF/versions/1.99-test/server-1.99-test.jar" to vanillaNested,
        )
        // Paper's patched vanilla class refers to a Paper-only one - a reference find_references
        // has to be able to see, though its target is nowhere near net/minecraft.
        val paperJar = zipOf(
            "net/minecraft/Old.class" to tinyClassBytes("net/minecraft/Old", fieldType = "org/bukkit/craftbukkit/Added"),
            "org/bukkit/craftbukkit/Added.class" to tinyClassBytes("org/bukkit/craftbukkit/Added"),
        )
        val paperclip = paperclipJar(vanillaNested, paperJar)
        val bundleConfig = """
            {"minecraftVersion": "1.99-test", "mojangMappedPaperclipFile": "data/paperclip-mojang.jar"}
        """.trimIndent()
        val devBundle = zipOf(
            "config.json" to bundleConfig.toByteArray(),
            "data/paperclip-mojang.jar" to paperclip,
        )
        val paperUrl = Variants.byId(Variants.PAPER)!!.devBundleRepository!!
        val bundleUrl = "$paperUrl/1.99-test.build.1/dev-bundle-1.99-test.build.1.zip"

        val version = VersionListEntry("1.99-test", "release", "https://example.invalid/1.99-test.json",
            "2024-01-01T00:00:00+00:00", "2024-01-01T00:00:00+00:00", sha1(serverJar))
        val detail = VersionDetail(
            VersionDownloads(
                client = DownloadArtifact("https://example.invalid/client.jar", "0".repeat(40), 1),
                server = DownloadArtifact("https://example.invalid/server.jar", sha1(serverJar), serverJar.size.toLong()),
            ),
        )
        val fetcher = RecordingFetcher(mapOf(
            "$paperUrl/maven-metadata.xml" to
                "<metadata><version>1.99-test.build.1</version></metadata>".toByteArray(),
            bundleUrl to devBundle,
            "https://example.invalid/server.jar" to serverJar,
        ))

        val cacheRoot = tempDir.resolve("cacheroot")
        val builder = DevBundleWorkspaceBuilder(Variants.PAPER, BlobStore(tempDir.resolve("blobs")), fetcher, cacheRoot, DevBundleRepository(Variants.PAPER, paperUrl, fetcher))

        val bundle = DevBundle("1.99-test.build.1", bundleUrl)
        val request = WorkspaceRequest(Variants.PAPER, null, "paper/1.99-test.build.1", bundle)
        val workspace = builder.build(request, version, detail)

        assertEquals("paper/1.99-test.build.1", workspace.versionId)
        assertTrue(workspace.remappedClasses.containsKey("net/minecraft/Old"))
        assertTrue(workspace.remappedClasses.containsKey("org/bukkit/craftbukkit/Added"))
        assertTrue(workspace.indexData.classes().containsKey("org/bukkit/craftbukkit/Added"))
        val addedRef = "f:net/minecraft/Old:ref:Lorg/bukkit/craftbukkit/Added;"
        assertEquals(setOf(addedRef), workspace.referenceIndexer.references("org/bukkit/craftbukkit/Added"))
        // Paper ships no client assets, and nothing is left to remap.
        assertTrue(workspace.assets.isEmpty())
        assertNull(workspace.remapper)
        // The derived dir is the per-build hash dir; the source cache is shared per MC version.
        val derivedDir = workspace.cacheDir!!
        assertTrue(Files.exists(derivedDir.resolve("remapped.jar")))
        assertTrue(Files.exists(derivedDir.resolve("index.bin")))
        assertTrue(derivedDir.toString().contains("paper"))
        assertEquals(
            cacheRoot.resolve("derived").resolve("paper").resolve("1.99-test")
                .resolve("source-cache").resolve(SOURCE_CACHE_CONFIG_VERSION),
            workspace.sourceCacheDir,
        )
        // The pool sweep can only drop entries once every build using the pool says what it uses.
        val outerClasses = workspace.remappedClasses.keys.filter { '$' !in it }
        assertEquals(
            outerClasses.map { DecompileService.sourceCacheKey(workspace.remappedClasses, it) }.toSet(),
            SourcePools.readKeys(derivedDir),
        )
        assertTrue("org/bukkit/craftbukkit/Added" in outerClasses)

        // A second build reloads the index from the derived cache with zero fetches: the derived
        // directory is keyed by the server jar's sha1 and the bundle zip's URL, so a warm hit is
        // found before anything is downloaded.
        val before = fetcher.callCount
        val warm = builder.build(request, version, detail)
        assertEquals(before, fetcher.callCount)
        assertTrue(warm.remappedClasses.containsKey("org/bukkit/craftbukkit/Added"))
        assertEquals(setOf(addedRef), warm.referenceIndexer.references("org/bukkit/craftbukkit/Added"))
    }

    @Test
    fun `a dev bundle for the wrong minecraft version is rejected`(@TempDir tempDir: Path) = runTest {
        val vanillaNested = zipOf("net/minecraft/Old.class" to tinyClassBytes("net/minecraft/Old"))
        val serverJar = zipOf(
            "META-INF/versions.list" to
                "${sha256Hex(vanillaNested)}\t1.99-test\t1.99-test/server-1.99-test.jar".toByteArray(),
            "META-INF/versions/1.99-test/server-1.99-test.jar" to vanillaNested,
        )
        val paperJar = serverJar
        val paperclip = paperclipJar(vanillaNested, paperJar)
        val bundleConfig = """
            {"minecraftVersion": "other-version", "mojangMappedPaperclipFile": "data/paperclip-mojang.jar"}
        """.trimIndent()
        val devBundle = zipOf(
            "config.json" to bundleConfig.toByteArray(),
            "data/paperclip-mojang.jar" to paperclip,
        )
        val paperUrl = Variants.byId(Variants.PAPER)!!.devBundleRepository!!
        val bundleUrl = "$paperUrl/1.99-test.build.1/dev-bundle-1.99-test.build.1.zip"

        val version = VersionListEntry("1.99-test", "release", "https://example.invalid/1.99-test.json",
            "2024-01-01T00:00:00+00:00", "2024-01-01T00:00:00+00:00", sha1(serverJar))
        val detail = VersionDetail(
            VersionDownloads(
                client = DownloadArtifact("https://example.invalid/client.jar", "0".repeat(40), 1),
                server = DownloadArtifact("https://example.invalid/server.jar", sha1(serverJar), serverJar.size.toLong()),
            ),
        )
        val fetcher = RecordingFetcher(mapOf(
            "$paperUrl/maven-metadata.xml" to
                "<metadata><version>1.99-test.build.1</version></metadata>".toByteArray(),
            bundleUrl to devBundle,
            "https://example.invalid/server.jar" to serverJar,
        ))
        val builder = DevBundleWorkspaceBuilder(Variants.PAPER, BlobStore(tempDir.resolve("blobs")), fetcher, null, DevBundleRepository(Variants.PAPER, paperUrl, fetcher))

        val outcome = runCatching {
            builder.build(WorkspaceRequest(Variants.PAPER, null, "paper/1.99-test.build.1", DevBundle("1.99-test.build.1", bundleUrl)), version, detail)
        }
        assertTrue(outcome.exceptionOrNull() is VariantSetupException, "expected VariantSetupException, got ${outcome.exceptionOrNull()}")
    }
}
