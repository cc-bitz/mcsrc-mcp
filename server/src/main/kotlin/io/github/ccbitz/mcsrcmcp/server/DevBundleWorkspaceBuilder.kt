package io.github.ccbitz.mcsrcmcp.server

import io.github.ccbitz.mcsrcmcp.cache.BlobStore
import io.github.ccbitz.mcsrcmcp.cache.VersionDetail
import io.github.ccbitz.mcsrcmcp.cache.VersionListEntry
import io.github.ccbitz.mcsrcmcp.core.IndexData
import io.github.ccbitz.mcsrcmcp.core.Indexer
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipInputStream

class VariantSetupException(message: String) : IllegalStateException(message)

// Bump when fork indexing changes what a saved index holds, so builds indexed the old way are
// rebuilt rather than loaded. f2: references to the fork's own classes are kept, not just Mojang's.
// Vanilla's DERIVED_CACHE_VERSION is separate: a fork-only change shouldn't rebuild every vanilla
// version. CacheEviction drops the build's old directory once the new one exists.
private const val FORK_INDEX_VERSION = "f2"

/**
 * Builds a fork's workspace from the artifacts every paperweight fork (Paper, Folia, Purpur)
 * already publishes:
 *
 * 1. resolve the dev bundle for the Minecraft version (latest build, or a pinned one),
 * 2. download the bundle zip and the vanilla server jar it binpatches,
 * 3. apply the bundle's mojang-mapped paperclip patches (whole-jar bsdiff + sha256 checks),
 * 4. index the resulting jar - it is already mojang-mapped, so no remap pass runs at all.
 * 5. in the background, build the fork's real source from the same bundle ([ForkSourceTrees]);
 *    decompiles serve every read until it's there.
 *
 * The heavy derived outputs (class jar, index) land in a per-build directory keyed by the server
 * jar hash and the bundle's zip URL, so a warm build is found without downloading the ~26MB bundle
 * at all; the per-class decompile cache is the fork's [SourcePools] pool for the Minecraft version,
 * shared across builds.
 */
class DevBundleWorkspaceBuilder(
    override val variant: String,
    private val blobStore: BlobStore,
    private val fetcher: BlobFetcher,
    private val cacheRoot: Path?,
    private val devBundles: DevBundleRepository,
    // Null serves decompiles only - tests, and servers with nowhere to build a tree.
    private val sourceTrees: ForkSourceTrees? = null,
) : VersionWorkspaceBuilder {
    override suspend fun build(request: WorkspaceRequest, version: VersionListEntry, detail: VersionDetail): VersionWorkspace {
        val serverArtifact = detail.downloads.server
            ?: throw VariantSetupException("Mojang published no server jar for ${version.id}, so $variant sources cannot be built")
        val bundle = request.bundle ?: devBundles.resolve(version.id, request.build)
        val sourceCacheDir = cacheRoot?.let { SourcePools.dir(it, variant, version.id) }
        val serverJar: suspend () -> Path = {
            blobStore.pathIfPresent(serverArtifact.sha1) ?: blobStore.put(fetcher.fetch(serverArtifact.url), serverArtifact.sha1)
        }
        fun sourcesFor(unitDir: Path?, classes: Map<String, ByteArray>, index: IndexData, bundleZip: ByteArray?): ClassSources =
            sourceTrees?.sourcesFor(request.workspaceId, unitDir, sourceCacheDir, classes, index, bundle, version.id, serverJar, bundleZip)
                ?: DecompiledSources(classes, sourceCacheDir)

        // The server jar's hash pins the Minecraft version and the zip URL pins the build (a
        // snapshot's URL carries its publish timestamp), which is everything the output depends on
        // - besides how it was indexed, which FORK_INDEX_VERSION stands in for.
        val buildKey = sha1Hex("$FORK_INDEX_VERSION|${bundle.zipUrl}".toByteArray())
        val derivedDir = cacheRoot?.let { DerivedCacheStore.directoryFor(it, request.workspaceId, serverArtifact.sha1, buildKey) }
        derivedDir?.let { DerivedCacheStore.load(it) }?.let { cached ->
            SourcePools.writeKeys(derivedDir, cached.remappedClasses)
            return VersionWorkspace(
                request.workspaceId,
                cached.indexData,
                remapper = null,
                cached.remappedClasses,
                Indexer().apply { loadReferences(cached.references.mapValues { it.value.toSet() }) },
                emptyMap(),
                EmptyAssetSource,
                derivedDir,
                sourceCacheDir,
                sourcesFor(derivedDir, cached.remappedClasses, cached.indexData, bundleZip = null),
            )
        }

        // Kept past the paperclip step: the source tree reads its patches from the same bundle.
        val bundleZip = fetcher.fetch(bundle.zipUrl)
        val paperclipJar = run {
            val config = parseDevBundleConfig(
                PaperclipPatcher.readZipEntry(bundleZip, "config.json")?.toString(Charsets.UTF_8)
                    ?: throw VariantSetupException("$variant dev bundle ${bundle.version} has no config.json"),
            )
            if (config.minecraftVersion != version.id) {
                throw VariantSetupException("$variant dev bundle ${bundle.version} targets minecraft ${config.minecraftVersion}, not ${version.id}")
            }
            // Bundles from before paper's runtime went mojang-mapped (1.20.5) only ship a
            // spigot-mapped server; there is nothing here to read those with.
            val paperclipPath = config.mojangMappedPaperclipFile
                ?: throw VariantSetupException("$variant dev bundle ${bundle.version} ships no mojang-mapped server, so it can't be served")
            PaperclipPatcher.readZipEntry(bundleZip, paperclipPath)
                ?: throw VariantSetupException("$variant dev bundle ${bundle.version} has no $paperclipPath")
        }

        // The patch base is the server classes jar nested inside Mojang's bundler wrapper, not the
        // wrapper download itself - paperclip's originalHash covers the nested jar.
        val serverJarPath = serverJar()
        val patchedJar = PaperclipPatcher.apply(paperclipJar, PaperclipPatcher.extractVanillaServerJar(Files.readAllBytes(serverJarPath)))

        val classes = ZipInputStream(patchedJar.inputStream()).use { zip ->
            val classes = ArrayList<ByteArray>()
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.endsWith(".class")) classes.add(zip.readBytes())
                entry = zip.nextEntry
            }
            classes
        }

        val (indexer, classesByName) = WorkspaceIndexing.indexMappedClasses(classes)
        val indexData = indexer.data()
        if (derivedDir != null) {
            DerivedCacheStore.save(derivedDir, classesByName, indexData, indexer.allReferences().mapValues { it.value.toList() })
            SourcePools.writeKeys(derivedDir, classesByName)
        }

        return VersionWorkspace(
            request.workspaceId,
            indexData,
            remapper = null,
            classesByName,
            indexer,
            emptyMap(),
            EmptyAssetSource,
            derivedDir,
            sourceCacheDir,
            sourcesFor(derivedDir, classesByName, indexData, bundleZip),
        )
    }

    private fun sha1Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }
}
