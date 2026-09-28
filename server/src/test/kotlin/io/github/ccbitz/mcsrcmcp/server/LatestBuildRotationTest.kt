package io.github.ccbitz.mcsrcmcp.server

import io.github.ccbitz.mcsrcmcp.cache.DownloadArtifact
import io.github.ccbitz.mcsrcmcp.cache.VersionDetail
import io.github.ccbitz.mcsrcmcp.cache.VersionDownloads
import io.github.ccbitz.mcsrcmcp.cache.VersionListEntry
import io.github.ccbitz.mcsrcmcp.core.IndexData
import io.github.ccbitz.mcsrcmcp.core.Indexer
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

// Variant workspaces resolved without a build pin are "latest" builds; this covers the rotation
// that keeps them from accumulating on disk: the previous latest build's derived cache is evicted
// (and its in-memory state dropped), while the shared content-keyed decompile cache is swept down
// to the keys the new build's classes can still hit. Pinned builds never rotate anything.
class LatestBuildRotationTest {
    private val version = VersionListEntry("1.99-test", "release", "https://example.invalid/v.json",
        "2024-01-01T00:00:00+00:00", "2024-01-01T00:00:00+00:00", "0".repeat(40))
    private val detail = VersionDetail(VersionDownloads(
        client = DownloadArtifact("https://example.invalid/client.jar", "0".repeat(40), 1),
    ))

    private class FakeVariantBuilder(override val variant: String) : VersionWorkspaceBuilder {
        lateinit var makeWorkspace: (WorkspaceRequest) -> VersionWorkspace

        override suspend fun build(request: WorkspaceRequest, version: VersionListEntry, detail: VersionDetail): VersionWorkspace =
            makeWorkspace(request)
    }

    private fun derivedDir(cacheRoot: Path, workspaceId: String): Path =
        cacheRoot.resolve("derived").resolve(workspaceId).resolve("u")

    // Real class bytes, not arbitrary ones: prepare() starts the background full-text index
    // build, which decompiles every class in the workspace - fake bytes would crash it.
    private fun realClassBytes(internalName: String): ByteArray {
        val writer = org.objectweb.asm.ClassWriter(0)
        writer.visit(
            org.objectweb.asm.Opcodes.V17,
            org.objectweb.asm.Opcodes.ACC_PUBLIC,
            internalName,
            null,
            "java/lang/Object",
            null,
        )
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun workspace(cacheRoot: Path, workspaceId: String, classBytes: Map<String, ByteArray>): VersionWorkspace {
        val dir = derivedDir(cacheRoot, workspaceId)
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("index.bin"), "not-really-binary")
        val sharedSource = cacheRoot.resolve("derived").resolve("paper").resolve("1.99-test")
            .resolve("source-cache").resolve(SOURCE_CACHE_CONFIG_VERSION)
        return VersionWorkspace(workspaceId, IndexData.empty(), null, classBytes, Indexer(), emptyMap(), EmptyAssetSource, dir, sharedSource)
    }

    @Test
    fun `a new latest build evicts the previous one and sweeps orphaned source cache entries`(@TempDir cacheRoot: Path) = runTest {
        val classBytes = mapOf("a/A" to realClassBytes("a/A"))
        val keepKey = DecompileService.sourceCacheKey(classBytes, "a/A")
        val builder = FakeVariantBuilder(Variants.PAPER)
        val cache = WorkspaceCache()
        val preparer = VersionPreparer(cache, VariantBuilders(listOf(builder)), this, cacheRoot)

        builder.makeWorkspace = { request ->
            val ws = workspace(cacheRoot, request.workspaceId, classBytes)
            // The shared cache only has entries because some build wrote them; pretend build one
            // wrote the live key plus one that build two no longer matches.
            Files.createDirectories(ws.sourceCacheDir!!)
            Files.writeString(ws.sourceCacheDir.resolve("$keepKey.java"), "kept")
            Files.writeString(ws.sourceCacheDir.resolve("orphaned0000000000000000000000000000000.java"), "orphan")
            ws
        }

        preparer.prepare(WorkspaceRequest(Variants.PAPER, null, "paper/build.1"), version, detail)
        val marker = cacheRoot.resolve("derived").resolve("paper").resolve("1.99-test.latest")
        assertEquals("paper/build.1", Files.readString(marker).trim())
        assertTrue(Files.exists(derivedDir(cacheRoot, "paper/build.1").resolve("index.bin")))

        preparer.prepare(WorkspaceRequest(Variants.PAPER, null, "paper/build.2"), version, detail)

        // The previous latest build is gone from disk and memory...
        assertFalse(Files.exists(derivedDir(cacheRoot, "paper/build.1")))
        assertNull(cache.get("paper/build.1"))
        // ...the marker points at the new build...
        assertEquals("paper/build.2", Files.readString(marker).trim())
        // ...and the shared source cache kept exactly the entry the new build's classes hit.
        assertTrue(Files.exists(cacheRoot.resolve("derived").resolve("paper").resolve("1.99-test")
            .resolve("source-cache").resolve(SOURCE_CACHE_CONFIG_VERSION).resolve("$keepKey.java")))
        assertFalse(Files.exists(cacheRoot.resolve("derived").resolve("paper").resolve("1.99-test")
            .resolve("source-cache").resolve(SOURCE_CACHE_CONFIG_VERSION).resolve("orphaned0000000000000000000000000000000.java")))
    }

    @Test
    fun `the sweep leaves in-flight writes alone and keeps keys of warm workspaces sharing the directory`(@TempDir cacheRoot: Path) = runTest {
        val latestBytes = mapOf("a/A" to realClassBytes("a/A"))
        val pinnedBytes = mapOf("p/P" to realClassBytes("p/P"))
        val pinnedKey = DecompileService.sourceCacheKey(pinnedBytes, "p/P")
        val builder = FakeVariantBuilder(Variants.PAPER)
        val cache = WorkspaceCache()
        val preparer = VersionPreparer(cache, VariantBuilders(listOf(builder)), this, cacheRoot)

        builder.makeWorkspace = { request ->
            val bytes = when (request.workspaceId) {
                "paper/build.1", "paper/build.2" -> latestBytes
                else -> pinnedBytes
            }
            workspace(cacheRoot, request.workspaceId, bytes)
        }

        // A pinned build goes warm first and writes its own cache entry, plus an in-flight write
        // a concurrent decompile happens to have open.
        preparer.prepare(WorkspaceRequest(Variants.PAPER, "build.9", "paper/build.9"), version, detail)
        val sharedSource = cacheRoot.resolve("derived").resolve("paper").resolve("1.99-test")
            .resolve("source-cache").resolve(SOURCE_CACHE_CONFIG_VERSION)
        Files.createDirectories(sharedSource)
        Files.writeString(sharedSource.resolve("$pinnedKey.java"), "pinned")
        Files.writeString(sharedSource.resolve("src-123456.tmp"), "in-flight write")
        // ...and the latest build's own entry, so the sweep has something of its own to keep.
        Files.writeString(sharedSource.resolve("${DecompileService.sourceCacheKey(latestBytes, "a/A")}.java"), "latest")

        preparer.prepare(WorkspaceRequest(Variants.PAPER, null, "paper/build.1"), version, detail)

        // The sweep keeps the pinned workspace's entry (it is still warm and shares the dir)...
        assertTrue(Files.exists(sharedSource.resolve("$pinnedKey.java")), "a warm pinned build's cache entry must survive the sweep")
        // ...and never touches another writer's temp file...
        assertTrue(Files.exists(sharedSource.resolve("src-123456.tmp")), "in-flight writes are not the sweep's to delete")
        // ...while the latest build's own entry survives too.
        assertTrue(Files.exists(sharedSource.resolve("${DecompileService.sourceCacheKey(latestBytes, "a/A")}.java")))
    }

    @Test
    fun `a pinned build never rotates the latest marker`(@TempDir cacheRoot: Path) = runTest {
        val classBytes = mapOf("a/A" to realClassBytes("a/A"))
        val builder = FakeVariantBuilder(Variants.PAPER)
        val preparer = VersionPreparer(WorkspaceCache(), VariantBuilders(listOf(builder)), this, cacheRoot)
        builder.makeWorkspace = { workspace(cacheRoot, it.workspaceId, classBytes) }

        preparer.prepare(WorkspaceRequest(Variants.PAPER, null, "paper/build.1"), version, detail)
        preparer.prepare(WorkspaceRequest(Variants.PAPER, "build.9", "paper/build.9"), version, detail)

        val marker = cacheRoot.resolve("derived").resolve("paper").resolve("1.99-test.latest")
        assertEquals("paper/build.1", Files.readString(marker).trim())
        // The pinned build's derived cache survives too - the caller asked for that exact build.
        assertTrue(Files.exists(derivedDir(cacheRoot, "paper/build.9").resolve("index.bin")))
    }

    @Test
    fun `vanilla prepares never touch the latest marker`(@TempDir cacheRoot: Path) = runTest {
        val builder = FakeVariantBuilder(Variants.VANILLA)
        val preparer = VersionPreparer(WorkspaceCache(), VariantBuilders(listOf(builder)), this, cacheRoot)
        builder.makeWorkspace = { workspace(cacheRoot, it.workspaceId, emptyMap()) }

        preparer.prepare(WorkspaceRequest(Variants.VANILLA, null, "1.99-test"), version, detail)

        assertFalse(Files.exists(cacheRoot.resolve("derived").resolve("vanilla").resolve("1.99-test.latest")))
    }
}
