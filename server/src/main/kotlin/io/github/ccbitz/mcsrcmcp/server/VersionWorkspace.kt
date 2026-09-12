package io.github.ccbitz.mcsrcmcp.server

import io.github.ccbitz.mcsrcmcp.cache.BlobStore
import io.github.ccbitz.mcsrcmcp.cache.VersionDetail
import io.github.ccbitz.mcsrcmcp.cache.VersionListEntry
import io.github.ccbitz.mcsrcmcp.core.ClassFileRemapper
import io.github.ccbitz.mcsrcmcp.core.IndexData
import io.github.ccbitz.mcsrcmcp.core.Indexer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.objectweb.asm.ClassReader
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipFile
import kotlin.math.max

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

// Width of the remap/index passes. Each worker's Indexer duplicates only the hash tables over the
// same strings, but a ceiling well above the core count buys nothing - the passes are pure CPU -
// and every extra copy of the reference tables is heap under the server's 1.5GB cap. Capped at the
// class count by shardedPass itself, so small (test) jars run a single worker and stay sequential.
private val REMAP_PARALLELISM = Runtime.getRuntime().availableProcessors().coerceAtMost(8)

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
        val (classList, assets) = ZipFile(clientJarPath.toFile()).use { zip ->
            readClasses(zip) to readAssets(zip)
        }

        // Pass 1: declarations-only index over the AS-DOWNLOADED bytes. ClassFileRemapper needs this
        // pre-remap (obfuscated) index to walk superclass chains when a member isn't declared on its
        // own obfuscated owner - so it runs only when there are mappings to build a remapper from.
        // Both passes below shard classes across workers (see shardedPass); with mappings the jar is
        // ~10k classes, and remap+index is pure CPU on independent inputs.
        val remapper = mappingsBytes?.let { mappings ->
            val declarationIndexer = mergeIndexers(
                shardedPass(classList, REMAP_PARALLELISM) { indexer, classBytes ->
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
        val pass2 = shardedPass(classList, REMAP_PARALLELISM) { indexer, classBytes ->
            val outputBytes = remapper?.remap(classBytes) ?: classBytes
            indexer.index(outputBytes)
            outputBytes
        }
        val finalIndexer = mergeIndexers(pass2.indexers)

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

    // A list, not a map: nothing downstream needs the entry name (the post-remap internal name is
    // re-read from each class), only the jar's entry order, which this preserves.
    private fun readClasses(zip: ZipFile): List<ByteArray> {
        val classes = ArrayList<ByteArray>()
        for (entry in zip.entries()) {
            if (entry.isDirectory || !entry.name.endsWith(".class")) continue
            classes.add(zip.getInputStream(entry).use { it.readBytes() })
        }
        return classes
    }

    private class ShardedPass(val indexers: List<Indexer>, val outputs: Array<ByteArray?>)

    /**
     * Runs [visit] over every class on [parallelism] coroutines, one [Indexer] each - Indexer's
     * plain HashMaps aren't thread-safe, and per-worker state makes the merge lock-free (see
     * [Indexer.addAll]). A class is claimed by index from a shared cursor, so sharding is
     * race-free without partitioning up front. When [visit] returns bytes, they land in
     * [ShardedPass.outputs] at the claimed position and the raw copy is dropped immediately - the
     * sequential loop this replaces freed each raw class as its remap was produced, and the peak
     * heap of a cold build shouldn't regress just because the passes got wider.
     */
    private suspend fun shardedPass(
        classList: List<ByteArray>,
        parallelism: Int,
        visit: (Indexer, ByteArray) -> ByteArray?,
    ): ShardedPass = coroutineScope {
        val rawClasses = ArrayList<ByteArray?>(classList.size).apply { addAll(classList) }
        val outputs = arrayOfNulls<ByteArray>(rawClasses.size)
        // Capped at the class count: a two-class test jar runs one worker, which is exactly the
        // old sequential loop with no scheduling to speak of.
        val width = parallelism.coerceIn(1, max(1, rawClasses.size))
        val cursor = AtomicInteger()
        val indexers = (0 until width).map {
            async(Dispatchers.Default) {
                val indexer = Indexer()
                while (true) {
                    val next = cursor.getAndIncrement()
                    if (next >= rawClasses.size) break
                    val classBytes = rawClasses[next] ?: continue
                    val outputBytes = visit(indexer, classBytes)
                    if (outputBytes != null) {
                        outputs[next] = outputBytes
                        rawClasses[next] = null
                    }
                }
                indexer
            }
        }.awaitAll()
        ShardedPass(indexers, outputs)
    }

    // The first worker's Indexer becomes the merged one (mutated in place) so the merge never
    // holds a third copy of the reference graph; each absorbed worker is cleared right after so
    // its share is collectable while the rest are still merging.
    private fun mergeIndexers(indexers: List<Indexer>): Indexer {
        val merged = indexers.first()
        for (other in indexers.drop(1)) {
            merged.addAll(other)
            other.clear()
        }
        return merged
    }
}
