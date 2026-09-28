package io.github.ccbitz.mcsrcmcp.server

import io.github.ccbitz.mcsrcmcp.cache.VersionDetail
import io.github.ccbitz.mcsrcmcp.cache.VersionListEntry
import io.github.ccbitz.mcsrcmcp.core.ClassFileRemapper
import io.github.ccbitz.mcsrcmcp.core.IndexData
import io.github.ccbitz.mcsrcmcp.core.Indexer
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
    /**
     * Where per-class decompiled sources (+ tokens) are cached. Vanilla keeps this inside its
     * derived directory; variant builders may point it at a directory shared across builds of the
     * same Minecraft version, because the cache is keyed by class content (see DecompileService) -
     * unchanged classes reuse their decompiles across builds. Null disables caching.
     */
    val sourceCacheDir: Path? = null,
)

/**
 * Builds the workspace for one variant. Implementations own the whole pipeline from artifacts to
 * an indexed [VersionWorkspace]; the shared remap/index passes live in [WorkspaceIndexing].
 */
interface VersionWorkspaceBuilder {
    val variant: String

    suspend fun build(request: WorkspaceRequest, version: VersionListEntry, detail: VersionDetail): VersionWorkspace
}

/** The registered builders, by variant id. */
class VariantBuilders(builders: List<VersionWorkspaceBuilder>) {
    private val byId = builders.associateBy { it.variant }

    fun forVariant(variant: String): VersionWorkspaceBuilder? = byId[variant]

    fun ids(): List<String> = byId.keys.toList()
}
