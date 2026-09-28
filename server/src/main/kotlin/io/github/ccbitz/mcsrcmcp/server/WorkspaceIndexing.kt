package io.github.ccbitz.mcsrcmcp.server

import io.github.ccbitz.mcsrcmcp.core.Indexer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.objectweb.asm.ClassReader
import java.util.zip.ZipFile
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

// .vsh/.fsh/.glsl are plain GLSL source (core/post-process shaders). These are the text extensions
// under assets/ in the jars this project indexes; .lang and .properties are kept for older/legacy
// asset layouts even though current jars no longer use them.
internal val TEXT_ASSET_EXTENSIONS = setOf(".json", ".mcmeta", ".lang", ".txt", ".properties", ".vsh", ".fsh", ".glsl")

// Width of the remap/index passes. Each worker's Indexer duplicates only the hash tables over the
// same strings, but a ceiling well above the core count buys nothing - the passes are pure CPU -
// and every extra copy of the reference tables is heap under the server's 1.5GB cap. Capped at the
// class count by shardedPass itself, so small (test) jars run a single worker and stay sequential.
internal val REMAP_PARALLELISM = Runtime.getRuntime().availableProcessors().coerceAtMost(8)

/**
 * Indexing passes shared by every variant builder. A fork's jar is already mojang-mapped, so it
 * skips the remap step entirely, but vanilla and the forks index through the same sharded passes.
 */
internal object WorkspaceIndexing {
    // ZipFile rather than ZipInputStream: the central directory gives every entry's uncompressed
    // size up front, so asset entries (textures, sounds - the bulk of a client jar) are catalogued
    // without ever being decompressed or held. Re-enumerating an open ZipFile is free, so the two
    // readers stay separate and a derived-cache hit simply never calls readClasses.
    fun readAssets(zip: ZipFile): Map<String, AssetInfo> {
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
    fun readClasses(zip: ZipFile): List<ByteArray> {
        val classes = ArrayList<ByteArray>()
        for (entry in zip.entries()) {
            if (entry.isDirectory || !entry.name.endsWith(".class")) continue
            classes.add(zip.getInputStream(entry).use { it.readBytes() })
        }
        return classes
    }

    /**
     * Indexes [classes] with no remapping - for jars that are already mojang-mapped. Returns the
     * merged indexer plus the class map keyed by each class's own (post-index) internal name.
     */
    suspend fun indexMappedClasses(classes: List<ByteArray>): Pair<Indexer, Map<String, ByteArray>> {
        val pass = shardedPass(classes, REMAP_PARALLELISM) { indexer, classBytes ->
            indexer.index(classBytes)
            classBytes
        }
        val indexer = mergeIndexers(pass.indexers)

        // ClassReader.className reads just the constant pool header for the class's own internal
        // name - cheap, no full visitor traversal needed. Assembling in claim order (jar entry
        // order) keeps the map's iteration order stable across runs even though workers finish in
        // any order.
        val byName = LinkedHashMap<String, ByteArray>(classes.size)
        for (outputBytes in pass.outputs) {
            if (outputBytes != null) byName[ClassReader(outputBytes).className] = outputBytes
        }
        return indexer to byName
    }

    /**
     * Runs [visit] over every class on [parallelism] coroutines, one [Indexer] each - Indexer's
     * plain HashMaps aren't thread-safe, and per-worker state makes the merge lock-free (see
     * [Indexer.addAll]). A class is claimed by index from a shared cursor, so sharding is
     * race-free without partitioning up front. When [visit] returns bytes, they land in
     * [ShardedPass.outputs] at the claimed position and the raw copy is dropped immediately - the
     * sequential loop this replaced freed each raw class as its remap was produced, and the peak
     * heap of a cold build shouldn't regress just because the passes got wider.
     */
    suspend fun shardedPass(
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
    fun mergeIndexers(indexers: List<Indexer>): Indexer {
        val merged = indexers.first()
        for (other in indexers.drop(1)) {
            merged.addAll(other)
            other.clear()
        }
        return merged
    }

    class ShardedPass(val indexers: List<Indexer>, val outputs: Array<ByteArray?>)
}
