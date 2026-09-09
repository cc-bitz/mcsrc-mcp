package io.github.ccbitz.mcsrcmcp.server

import io.github.ccbitz.mcsrcmcp.cache.BlobStore
import io.github.ccbitz.mcsrcmcp.cache.VersionDetail
import io.github.ccbitz.mcsrcmcp.cache.VersionListEntry
import io.github.ccbitz.mcsrcmcp.core.ClassFileRemapper
import io.github.ccbitz.mcsrcmcp.core.IndexData
import io.github.ccbitz.mcsrcmcp.core.Indexer
import org.objectweb.asm.ClassReader
import java.nio.file.Path
import java.util.zip.ZipFile

/** Outcome of a conditional GET: either a body, or the server confirming what we hold is current. */
sealed interface ConditionalFetch {
    data class Body(val bytes: ByteArray, val etag: String?) : ConditionalFetch
    data object NotModified : ConditionalFetch
}

interface BlobFetcher {
    suspend fun fetch(url: String): ByteArray

    /**
     * Fetches [url] unless [etag] still matches, in which case the server answers 304 and sends no
     * body. Defaulted to an unconditional fetch so a fetcher with nothing to revalidate against -
     * every test fake - needs no implementation; only [HttpBlobFetcher] has real headers to send.
     */
    suspend fun fetchIfNoneMatch(url: String, etag: String?): ConditionalFetch =
        ConditionalFetch.Body(fetch(url), null)
}

/**
 * Metadata for one non-class jar entry (a client asset). [isText] entries can be read as text via
 * [VersionWorkspace.assetSource]; any entry's raw bytes can be copied out with the extract tool -
 * [isText] only governs get_asset, not extraction.
 */
data class AssetInfo(val path: String, val sizeBytes: Int, val isText: Boolean)

/**
 * Reads assets on demand: text via [readText] (get_asset), raw bytes via [readBytes] (extract).
 *
 * The workspace used to eagerly decode every text asset into a `Map<String, String>` at build time
 * - tens of thousands of JSON model/blockstate files held as UTF-16 Strings for the process's
 * lifetime, whether or not `get_asset` was ever called. The client jar is already sitting in the
 * blob store, so reading the one asset that was actually asked for costs a jar open instead.
 */
interface AssetSource {
    fun readText(path: String): String?

    fun readBytes(path: String): ByteArray?

    /**
     * Runs [block] against a source that keeps the jar open for the whole batch. Reading every text
     * asset one-at-a-time would re-parse the jar's central directory (~40k entries) per asset.
     */
    fun <T> batch(block: (AssetSource) -> T): T = block(this)
}

object EmptyAssetSource : AssetSource {
    override fun readText(path: String): String? = null

    override fun readBytes(path: String): ByteArray? = null
}

class JarAssetSource(private val clientJarPath: Path) : AssetSource {
    override fun readText(path: String): String? = batch { it.readText(path) }

    override fun readBytes(path: String): ByteArray? = batch { it.readBytes(path) }

    override fun <T> batch(block: (AssetSource) -> T): T =
        ZipFile(clientJarPath.toFile()).use { zip -> block(OpenJar(zip)) }

    private class OpenJar(private val zip: ZipFile) : AssetSource {
        override fun readText(path: String): String? = readBytes(path)?.toString(Charsets.UTF_8)

        override fun readBytes(path: String): ByteArray? {
            val entry = zip.getEntry(path) ?: return null
            return zip.getInputStream(entry).use { it.readBytes() }
        }
    }
}

class VersionWorkspace(
    val versionId: String,
    val indexData: IndexData,
    val remapper: ClassFileRemapper?,
    val remappedClasses: Map<String, ByteArray>,
    val referenceIndexer: Indexer,
    val assets: Map<String, AssetInfo>,
    val assetSource: AssetSource,
    val cacheDir: Path?,
)

// .vsh/.fsh/.glsl are plain GLSL source (core/post-process shaders). These are the text extensions
// under assets/ in the client jars this project indexes; .lang and .properties are kept for
// older/legacy asset layouts even though current jars no longer use them.
private val TEXT_ASSET_EXTENSIONS = setOf(".json", ".mcmeta", ".lang", ".txt", ".properties", ".vsh", ".fsh", ".glsl")

// open: VersionPreparerTest's CountingBuilder subclasses this to intercept build() without
// re-implementing the fetch/cache/remap/asset-extraction wiring.
open class VersionWorkspaceBuilder(
    private val blobStore: BlobStore,
    private val fetcher: BlobFetcher,
    private val cacheRoot: Path? = null,
) {
    open suspend fun build(version: VersionListEntry, detail: VersionDetail): VersionWorkspace {
        // Path, not bytes: the client jar is ~39MB and the only parts we need are the class entries
        // on a cache miss - assets are read back from this same path on demand by JarAssetSource.
        val clientJarPath = fetchBlobPath(detail.downloads.client.url, detail.downloads.client.sha1)
        val derivedDir = cacheRoot?.let {
            DerivedCacheStore.directoryFor(it, version.id, detail.downloads.client.sha1, detail.downloads.clientMappings?.sha1)
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
                version.id,
                cached.indexData,
                // Null by design on this path: there is nothing left to remap, and the field has no
                // reader outside build(). Constructing one would mean paying for pass 1 again.
                remapper = null,
                cached.remappedClasses,
                loadedIndexer,
                ZipFile(clientJarPath.toFile()).use { readAssets(it) },
                JarAssetSource(clientJarPath),
                derivedDir,
            )
        }

        val mappingsBytes = detail.downloads.clientMappings?.let { fetchBlobBytes(it.url, it.sha1) }
        val (classEntries, assets) = ZipFile(clientJarPath.toFile()).use { zip ->
            readClasses(zip) to readAssets(zip)
        }

        // Pass 1: declarations-only index over the AS-DOWNLOADED bytes. ClassFileRemapper needs this
        // pre-remap (obfuscated) index to walk superclass chains when a member isn't declared on its
        // own obfuscated owner - so it runs only when there are mappings to build a remapper from.
        val remapper = mappingsBytes?.let { mappings ->
            val declarationIndexer = Indexer()
            for (classBytes in classEntries.values) {
                declarationIndexer.indexDeclarations(classBytes)
            }
            ClassFileRemapper(mappings, declarationIndexer.data())
        }

        // Pass 2 (full, expensive): index the REMAPPED bytes (or the original bytes when there's no
        // mapping, e.g. unobfuscated-by-default versions) - this is the index every tool that looks
        // up classes/members BY THEIR DEOBFUSCATED NAME actually queries, and also captures full
        // cross-references (find_references reads this via referenceIndexer).
        val finalIndexer = Indexer()
        val remappedClasses = LinkedHashMap<String, ByteArray>()
        // Drops each raw class as its remapped copy is produced. Holding both whole sets at once
        // doubled the peak heap of a cold build for no reason - nothing below reads the originals.
        val rawClasses = classEntries.values.iterator()
        while (rawClasses.hasNext()) {
            val classBytes = rawClasses.next()
            val outputBytes = remapper?.remap(classBytes) ?: classBytes
            finalIndexer.index(outputBytes)
            // ClassReader.className reads just the constant pool header for the class's own
            // (post-remap) internal name - cheap, no full visitor traversal needed.
            remappedClasses[ClassReader(outputBytes).className] = outputBytes
            rawClasses.remove()
        }

        val indexData = finalIndexer.data()
        if (derivedDir != null) {
            DerivedCacheStore.save(derivedDir, remappedClasses, indexData, finalIndexer.allReferences().mapValues { it.value.toList() })
        }

        return VersionWorkspace(
            version.id,
            indexData,
            remapper,
            remappedClasses,
            finalIndexer,
            assets,
            JarAssetSource(clientJarPath),
            derivedDir,
        )
    }

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

    // ZipFile rather than ZipInputStream: the central directory gives every entry's uncompressed
    // size up front, so asset entries (textures, sounds - the bulk of a client jar) are catalogued
    // without ever being decompressed or held. Re-enumerating an open ZipFile is free, so the two
    // readers stay separate and a cache hit simply never calls readClasses.
    private fun readAssets(zip: ZipFile): Map<String, AssetInfo> {
        val assets = LinkedHashMap<String, AssetInfo>()
        for (entry in zip.entries()) {
            if (entry.isDirectory || entry.name.endsWith(".class")) continue
            val isText = TEXT_ASSET_EXTENSIONS.any { entry.name.endsWith(it) }
            assets[entry.name] = AssetInfo(entry.name, entry.size.coerceAtLeast(0).toInt(), isText)
        }
        return assets
    }

    private fun readClasses(zip: ZipFile): MutableMap<String, ByteArray> {
        val classes = LinkedHashMap<String, ByteArray>()
        for (entry in zip.entries()) {
            if (entry.isDirectory || !entry.name.endsWith(".class")) continue
            classes[entry.name] = zip.getInputStream(entry).use { it.readBytes() }
        }
        return classes
    }
}
