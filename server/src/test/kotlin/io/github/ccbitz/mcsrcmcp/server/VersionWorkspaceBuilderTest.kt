package io.github.ccbitz.mcsrcmcp.server

import io.github.ccbitz.mcsrcmcp.cache.BlobStore
import io.github.ccbitz.mcsrcmcp.cache.DownloadArtifact
import io.github.ccbitz.mcsrcmcp.cache.VersionDetail
import io.github.ccbitz.mcsrcmcp.cache.VersionDownloads
import io.github.ccbitz.mcsrcmcp.cache.VersionListEntry
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class VersionWorkspaceBuilderTest {
    // Same fixture shape as core's ClassFileRemapperTest, compiled independently in this module
    // (net.minecraft.Dog calls a static method on net.minecraft.Animal).
    private fun classResourceBytes(internalName: String): ByteArray {
        val stream = javaClass.classLoader.getResourceAsStream("$internalName.class")
            ?: error("fixture class not found on test classpath: $internalName")
        return stream.readBytes()
    }

    private fun sha1(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun buildFixtureJar(): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for (name in listOf("net/minecraft/Animal", "net/minecraft/Dog")) {
                zip.putNextEntry(ZipEntry("$name.class"))
                zip.write(classResourceBytes(name))
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry("assets/minecraft/lang/en_us.json"))
            zip.write("""{"item.minecraft.allium":"Allium"}""".toByteArray(StandardCharsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("assets/minecraft/textures/block/stone.png"))
            zip.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)) // fake PNG magic bytes - content doesn't matter, only that it's binary/non-text
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("assets/minecraft/shaders/core/rendertype_end_portal.fsh"))
            zip.write("#version 150\nvoid main() {}\n".toByteArray(StandardCharsets.UTF_8))
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    private val mappingText = """
        net.minecraft.wolf.Beast -> net.minecraft.Animal:
            1:1:void makeNoise() -> staticSound
        net.minecraft.wolf.Hound -> net.minecraft.Dog:
            2:2:void woof() -> run
    """.trimIndent()

    private class RecordingFetcher(private val bytesByUrl: Map<String, ByteArray>) : BlobFetcher {
        var callCount = 0
            private set

        override suspend fun fetch(url: String): ByteArray {
            callCount++
            return bytesByUrl[url] ?: error("unexpected fetch: $url")
        }
    }

    @Test
    fun `builds a workspace with remap-capable index from a fixture jar and mappings`(@TempDir tempDir: Path) = runTest {
        val jarBytes = buildFixtureJar()
        val mappingBytes = mappingText.toByteArray(StandardCharsets.UTF_8)

        val version = VersionListEntry("1.99-test", "release", "https://example.invalid/1.99-test.json",
            "2024-01-01T00:00:00+00:00", "2024-01-01T00:00:00+00:00", sha1(jarBytes))
        val detail = VersionDetail(
            VersionDownloads(
                client = DownloadArtifact("https://example.invalid/client.jar", sha1(jarBytes), jarBytes.size.toLong()),
                clientMappings = DownloadArtifact("https://example.invalid/client.txt", sha1(mappingBytes), mappingBytes.size.toLong()),
            )
        )

        val fetcher = RecordingFetcher(mapOf(
            "https://example.invalid/client.jar" to jarBytes,
            "https://example.invalid/client.txt" to mappingBytes,
        ))
        val blobStore = BlobStore(tempDir)
        val builder = VersionWorkspaceBuilder(blobStore, fetcher)

        val workspace = builder.build(version, detail)

        assertEquals("1.99-test", workspace.versionId)
        // The workspace's index is keyed by the DEOBFUSCATED (remapped) name, not the
        // as-downloaded name - "net/minecraft/Dog" must NOT appear; only its remapped identity
        // does. An earlier version of this builder indexed the pre-remap declarations and
        // never re-indexed the remapped output, so get_class_outline could never find a class
        // by its real (deobfuscated) name against an actually-obfuscated jar.
        assertNull(workspace.indexData.classes()["net/minecraft/Dog"])
        assertNotNull(workspace.indexData.classes()["net/minecraft/wolf/Hound"])
        assertNotNull(workspace.remapper)
        assertTrue(workspace.remappedClasses.containsKey("net/minecraft/wolf/Beast"))
        assertTrue(workspace.remappedClasses.containsKey("net/minecraft/wolf/Hound"))
        assertFalse(workspace.remappedClasses.containsKey("net/minecraft/Animal"))
        assertFalse(workspace.remappedClasses.containsKey("net/minecraft/Dog"))

        val remapped = workspace.remapper!!.remap(classResourceBytes("net/minecraft/Animal"))
        var remappedName: String? = null
        org.objectweb.asm.ClassReader(remapped).accept(object : org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
            override fun visit(version: Int, access: Int, name: String, signature: String?, superName: String?, interfaces: Array<out String>?) {
                remappedName = name
            }
        }, 0)
        assertEquals("net/minecraft/wolf/Beast", remappedName)

        assertEquals(2, fetcher.callCount)
    }

    @Test
    fun `does not re-fetch blobs already present in the store`(@TempDir tempDir: Path) = runTest {
        val jarBytes = buildFixtureJar()
        val mappingBytes = mappingText.toByteArray(StandardCharsets.UTF_8)
        val blobStore = BlobStore(tempDir)
        blobStore.put(jarBytes, sha1(jarBytes))
        blobStore.put(mappingBytes, sha1(mappingBytes))

        val version = VersionListEntry("1.99-test", "release", "https://example.invalid/1.99-test.json",
            "2024-01-01T00:00:00+00:00", "2024-01-01T00:00:00+00:00", sha1(jarBytes))
        val detail = VersionDetail(
            VersionDownloads(
                client = DownloadArtifact("https://example.invalid/client.jar", sha1(jarBytes), jarBytes.size.toLong()),
                clientMappings = DownloadArtifact("https://example.invalid/client.txt", sha1(mappingBytes), mappingBytes.size.toLong()),
            )
        )

        val fetcher = RecordingFetcher(emptyMap()) // any call fails the test via error()
        val builder = VersionWorkspaceBuilder(blobStore, fetcher)

        val workspace = builder.build(version, detail)

        assertEquals(0, fetcher.callCount)
        assertNotNull(workspace.remapper)
    }

    @Test
    fun `retains a queryable reference index built from the remapped bytecode`(@TempDir tempDir: Path) = runTest {
        val jarBytes = buildFixtureJar()
        val mappingBytes = mappingText.toByteArray(StandardCharsets.UTF_8)

        val version = VersionListEntry("1.99-test", "release", "https://example.invalid/1.99-test.json",
            "2024-01-01T00:00:00+00:00", "2024-01-01T00:00:00+00:00", sha1(jarBytes))
        val detail = VersionDetail(
            VersionDownloads(
                client = DownloadArtifact("https://example.invalid/client.jar", sha1(jarBytes), jarBytes.size.toLong()),
                clientMappings = DownloadArtifact("https://example.invalid/client.txt", sha1(mappingBytes), mappingBytes.size.toLong()),
            )
        )
        val fetcher = RecordingFetcher(mapOf(
            "https://example.invalid/client.jar" to jarBytes,
            "https://example.invalid/client.txt" to mappingBytes,
        ))
        val blobStore = BlobStore(tempDir)
        val builder = VersionWorkspaceBuilder(blobStore, fetcher)

        val workspace = builder.build(version, detail)

        // Dog#run() (remapped to Hound#woof()) calls the static Animal#staticSound()
        // (remapped to Beast#makeNoise()). Pass 2 indexes the REMAPPED bytecode, so both the
        // reference key and the caller value in the retained Indexer are in deobfuscated terms.
        val refs = workspace.referenceIndexer.references("net/minecraft/wolf/Beast:makeNoise:()V")
        assertTrue(refs.contains("m:net/minecraft/wolf/Hound:woof:()V"))
    }

    @Test
    fun `retains asset metadata and text content for non-class jar entries`(@TempDir tempDir: Path) = runTest {
        val jarBytes = buildFixtureJar()
        val mappingBytes = mappingText.toByteArray(StandardCharsets.UTF_8)

        val version = VersionListEntry("1.99-test", "release", "https://example.invalid/1.99-test.json",
            "2024-01-01T00:00:00+00:00", "2024-01-01T00:00:00+00:00", sha1(jarBytes))
        val detail = VersionDetail(
            VersionDownloads(
                client = DownloadArtifact("https://example.invalid/client.jar", sha1(jarBytes), jarBytes.size.toLong()),
                clientMappings = DownloadArtifact("https://example.invalid/client.txt", sha1(mappingBytes), mappingBytes.size.toLong()),
            )
        )
        val fetcher = RecordingFetcher(mapOf(
            "https://example.invalid/client.jar" to jarBytes,
            "https://example.invalid/client.txt" to mappingBytes,
        ))
        val blobStore = BlobStore(tempDir)
        val builder = VersionWorkspaceBuilder(blobStore, fetcher)

        val workspace = builder.build(version, detail)

        val langAsset = workspace.assets["assets/minecraft/lang/en_us.json"]
        assertNotNull(langAsset)
        assertTrue(langAsset!!.isText)
        // Asset text is read back out of the client jar on demand rather than decoded up front, so
        // these assert through assetSource - the listing itself only carries path/size/isText.
        assertEquals("""{"item.minecraft.allium":"Allium"}""", workspace.assetSource.readText("assets/minecraft/lang/en_us.json"))

        val textureAsset = workspace.assets["assets/minecraft/textures/block/stone.png"]
        assertNotNull(textureAsset)
        assertFalse(textureAsset!!.isText)

        val shaderAsset = workspace.assets["assets/minecraft/shaders/core/rendertype_end_portal.fsh"]
        assertNotNull(shaderAsset)
        assertTrue(shaderAsset!!.isText)
        assertEquals("#version 150\nvoid main() {}\n", workspace.assetSource.readText("assets/minecraft/shaders/core/rendertype_end_portal.fsh"))

        // .class entries never leak into the asset listing
        assertTrue(workspace.assets.keys.none { it.endsWith(".class") })
    }

    @Test
    fun `persists and reloads the derived cache across separate builder instances`(@TempDir tempDir: Path) = runTest {
        val jarBytes = buildFixtureJar()
        val mappingBytes = mappingText.toByteArray(StandardCharsets.UTF_8)

        val version = VersionListEntry("1.99-test", "release", "https://example.invalid/1.99-test.json",
            "2024-01-01T00:00:00+00:00", "2024-01-01T00:00:00+00:00", sha1(jarBytes))
        val detail = VersionDetail(
            VersionDownloads(
                client = DownloadArtifact("https://example.invalid/client.jar", sha1(jarBytes), jarBytes.size.toLong()),
                clientMappings = DownloadArtifact("https://example.invalid/client.txt", sha1(mappingBytes), mappingBytes.size.toLong()),
            )
        )
        val blobsDir = tempDir.resolve("blobs")
        val cacheRoot = tempDir.resolve("cacheroot")
        val fetcher = RecordingFetcher(mapOf(
            "https://example.invalid/client.jar" to jarBytes,
            "https://example.invalid/client.txt" to mappingBytes,
        ))
        val blobStore = BlobStore(blobsDir)

        val firstWorkspace = VersionWorkspaceBuilder(blobStore, fetcher, cacheRoot).build(version, detail)
        val cacheDir = firstWorkspace.cacheDir!!
        assertTrue(Files.exists(cacheDir.resolve("remapped.jar")))
        assertTrue(Files.exists(cacheDir.resolve("index.json")))

        // A brand new builder instance, same blobStore/cacheRoot - proves the second build()
        // genuinely reloads from disk rather than relying on any in-memory state carried over
        // from the first builder.
        val secondWorkspace = VersionWorkspaceBuilder(blobStore, fetcher, cacheRoot).build(version, detail)

        assertNull(secondWorkspace.indexData.classes()["net/minecraft/Dog"])
        assertNotNull(secondWorkspace.indexData.classes()["net/minecraft/wolf/Hound"])
        assertTrue(secondWorkspace.remappedClasses.containsKey("net/minecraft/wolf/Beast"))
        // Null on a cache hit by design: nothing is left to remap, and building one would mean
        // re-reading every raw class to run the declaration pass it needs.
        assertNull(secondWorkspace.remapper)

        val refs = secondWorkspace.referenceIndexer.references("net/minecraft/wolf/Beast:makeNoise:()V")
        assertTrue(refs.contains("m:net/minecraft/wolf/Hound:woof:()V"))
    }

    @Test
    fun `cacheDir is null when no cacheRoot is configured`(@TempDir tempDir: Path) = runTest {
        val jarBytes = buildFixtureJar()
        val mappingBytes = mappingText.toByteArray(StandardCharsets.UTF_8)
        val version = VersionListEntry("1.99-test", "release", "https://example.invalid/1.99-test.json",
            "2024-01-01T00:00:00+00:00", "2024-01-01T00:00:00+00:00", sha1(jarBytes))
        val detail = VersionDetail(
            VersionDownloads(
                client = DownloadArtifact("https://example.invalid/client.jar", sha1(jarBytes), jarBytes.size.toLong()),
                clientMappings = DownloadArtifact("https://example.invalid/client.txt", sha1(mappingBytes), mappingBytes.size.toLong()),
            )
        )
        val fetcher = RecordingFetcher(mapOf(
            "https://example.invalid/client.jar" to jarBytes,
            "https://example.invalid/client.txt" to mappingBytes,
        ))
        val blobStore = BlobStore(tempDir)

        val workspace = VersionWorkspaceBuilder(blobStore, fetcher).build(version, detail) // 2-arg, no cacheRoot

        assertNull(workspace.cacheDir)
    }
}
