package io.github.ccbitz.mcsrcmcp.server

import io.github.ccbitz.mcsrcmcp.cache.BlobStore
import io.github.ccbitz.mcsrcmcp.cache.VersionDetail
import io.github.ccbitz.mcsrcmcp.cache.VersionListEntry
import io.github.ccbitz.mcsrcmcp.core.ClassFileRemapper
import io.github.ccbitz.mcsrcmcp.core.Indexer
import org.objectweb.asm.ClassReader
import java.nio.file.Path
import java.util.zip.ZipFile

class VanillaWorkspaceBuilder(
    private val blobStore: BlobStore,
    private val fetcher: BlobFetcher,
    private val cacheRoot: Path? = null,
) : VersionWorkspaceBuilder {
    override val variant: String = Variants.VANILLA

    override suspend fun build(request: WorkspaceRequest, version: VersionListEntry, detail: VersionDetail): VersionWorkspace {
        // Path, not bytes: the client jar is ~39MB and the only parts we need are the class entries
        // on a cache miss - assets are read back from this same path on demand by JarAssetSource.
        val clientJarPath = fetchBlobPath(detail.downloads.client.url, detail.downloads.client.sha1)
        val derivedDir = cacheRoot?.let {
            DerivedCacheStore.directoryFor(it, request.workspaceId, detail.downloads.client.sha1, detail.downloads.clientMappings?.sha1)
        }

        // The derived cache is consulted FIRST. It used to be read only after unconditionally
        // reading every raw class out of the client jar and running a declarations-only index over
        // all of them - ~490ms and a heap full of class bytes on a 26.2-sized version - solely to
        // construct a ClassFileRemapper that a cache hit never remaps anything with, and that no
        // code outside this function ever reads. On a version with no client mappings (26.2 among
        // them) that work produced a null remapper, so it was pure waste on both paths.
        val cached = derivedDir?.let { DerivedCacheStore.load(it) }
        if (cached != null) {
            val loadedIndexer = Indexer()
            loadedIndexer.loadReferences(cached.references.mapValues { it.value.toSet() })
            return VersionWorkspace(
                request.workspaceId,
                cached.indexData,
                // Null by design on this path: there is nothing left to remap, and the field has no
                // reader outside build(). Constructing one would mean paying for pass 1 again.
                remapper = null,
                cached.remappedClasses,
                loadedIndexer,
                ZipFile(clientJarPath.toFile()).use { WorkspaceIndexing.readAssets(it) },
                JarAssetSource(clientJarPath),
                derivedDir,
                sourceCacheDir = defaultSourceCacheDir(derivedDir),
            )
        }

        val mappingsBytes = detail.downloads.clientMappings?.let { fetchBlobBytes(it.url, it.sha1) }
        val (classList, assets) = ZipFile(clientJarPath.toFile()).use { zip ->
            WorkspaceIndexing.readClasses(zip) to WorkspaceIndexing.readAssets(zip)
        }

        // Pass 1: declarations-only index over the AS-DOWNLOADED bytes. ClassFileRemapper needs this
        // pre-remap (obfuscated) index to walk superclass chains when a member isn't declared on its
        // own obfuscated owner - so it runs only when there are mappings to build a remapper from.
        // Both passes below shard classes across workers; with mappings the jar is ~10k classes, and
        // remap+index is pure CPU on independent inputs.
        val remapper = mappingsBytes?.let { mappings ->
            val declarationIndexer = WorkspaceIndexing.mergeIndexers(
                WorkspaceIndexing.shardedPass(classList, REMAP_PARALLELISM) { indexer, classBytes ->
                    indexer.indexDeclarations(classBytes)
                    null
                }.indexers
            )
            ClassFileRemapper(mappings, declarationIndexer.data())
        }

        // Pass 2 (full, expensive): index the REMAPPED bytes (or the original bytes when there's no
        // mapping, e.g. unobfuscated-by-default versions) - this is the index every tool that looks
        // up classes/members BY THEIR DEOBFUSCATED NAME actually queries, and also captures full
        // cross-references (find_references reads this via referenceIndexer).
        val pass2 = WorkspaceIndexing.shardedPass(classList, REMAP_PARALLELISM) { indexer, classBytes ->
            val outputBytes = remapper?.remap(classBytes) ?: classBytes
            indexer.index(outputBytes)
            outputBytes
        }
        val finalIndexer = WorkspaceIndexing.mergeIndexers(pass2.indexers)

        // ClassReader.className reads just the constant pool header for the class's own
        // (post-remap) internal name - cheap, no full visitor traversal needed. Assembling in
        // claim order (jar entry order) keeps remapped.jar byte-identical across runs even
        // though workers finish in any order.
        val remappedClasses = LinkedHashMap<String, ByteArray>(classList.size)
        for (outputBytes in pass2.outputs) {
            if (outputBytes != null) remappedClasses[ClassReader(outputBytes).className] = outputBytes
        }

        val indexData = finalIndexer.data()
        if (derivedDir != null) {
            DerivedCacheStore.save(derivedDir, remappedClasses, indexData, finalIndexer.allReferences().mapValues { it.value.toList() })
        }

        return VersionWorkspace(
            request.workspaceId,
            indexData,
            remapper,
            remappedClasses,
            finalIndexer,
            assets,
            JarAssetSource(clientJarPath),
            derivedDir,
            sourceCacheDir = defaultSourceCacheDir(derivedDir),
        )
    }

    fun defaultSourceCacheDir(derivedDir: Path?): Path? =
        derivedDir?.resolve("source")?.resolve(SOURCE_CACHE_CONFIG_VERSION)

    private suspend fun fetchBlobPath(url: String, sha1: String): Path {
        blobStore.pathIfPresent(sha1)?.let { return it }
        return blobStore.put(fetcher.fetch(url), sha1)
    }

    private suspend fun fetchBlobBytes(url: String, sha1: String): ByteArray {
        blobStore.get(sha1)?.let { return it }
        val bytes = fetcher.fetch(url)
        blobStore.put(bytes, sha1)
        return bytes
    }
}
