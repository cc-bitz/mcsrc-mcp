package io.github.ccbitz.mcsrcmcp.server

import io.github.ccbitz.mcsrcmcp.core.ClassData
import io.github.ccbitz.mcsrcmcp.core.Entry
import io.github.ccbitz.mcsrcmcp.core.IndexData
import io.github.ccbitz.mcsrcmcp.core.MemberData
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

// Bump whenever core's Indexer/ClassFileRemapper logic changes in a way that would change
// remap/index output for the same inputs. Old derived directories under a stale version number
// are simply never looked up again - they're inert, not actively cleaned up.
const val DERIVED_CACHE_VERSION = 1

data class DerivedCacheData(
    val remappedClasses: Map<String, ByteArray>,
    val indexData: IndexData,
    val references: Map<String, List<String>>,
)

@Serializable
private data class PersistedClassData(val name: String, val superName: String?, val interfaces: List<String>, val access: Int)

@Serializable
private data class PersistedMember(val name: String, val desc: String)

@Serializable
private data class PersistedMemberData(val className: String, val methods: List<PersistedMember>, val fields: List<PersistedMember>)

@Serializable
private data class PersistedIndex(
    val classes: List<PersistedClassData>,
    val members: List<PersistedMemberData>,
    val references: Map<String, List<String>>,
)

private val derivedCacheJson = Json { ignoreUnknownKeys = true }

// decodeFromStream/encodeToStream are the only way to avoid materializing a ~100MB index.json as a
// String on either side of the JSON codec; they've been stable in practice for several releases and
// this is the single place the opt-in applies.
@OptIn(ExperimentalSerializationApi::class)
object DerivedCacheStore {
    fun directoryFor(cacheRoot: Path, versionId: String, clientSha1: String, mappingsSha1: String?): Path {
        val mapPart = mappingsSha1?.take(12) ?: "none"
        return cacheRoot.resolve("derived").resolve(versionId).resolve("$DERIVED_CACHE_VERSION-${clientSha1.take(12)}-$mapPart")
    }

    fun load(derivedDir: Path): DerivedCacheData? {
        val jarFile = derivedDir.resolve("remapped.jar")
        val indexFile = derivedDir.resolve("index.json")
        if (!Files.exists(jarFile) || !Files.exists(indexFile)) {
            return null
        }

        return try {
            val remappedClasses = readClassJar(jarFile)
            // Streamed, not Files.readString + decodeFromString: index.json runs to ~100MB for a
            // modern version, and reading it as one String meant holding the entire file in the
            // heap as well as the object graph parsed out of it.
            val persisted: PersistedIndex = Files.newInputStream(indexFile).buffered().use {
                derivedCacheJson.decodeFromStream(it)
            }

            val classes = persisted.classes.associate { it.name to ClassData(it.name, it.superName, it.interfaces, it.access) }
            val members = persisted.members.associate {
                it.className to MemberData(
                    it.className,
                    it.methods.map { m -> Entry.Method(it.className, m.name, m.desc) }.toSet(),
                    it.fields.map { f -> Entry.Field(it.className, f.name, f.desc) }.toSet(),
                )
            }

            // Mark this derived cache as just-used (Task 8's CacheEviction TTL sweep reads
            // this file's last-modified time to decide what's stale - simply reading the file
            // does not update it, so this must be explicit).
            Files.setLastModifiedTime(indexFile, FileTime.from(Instant.now()))

            DerivedCacheData(remappedClasses, IndexData(classes, members), persisted.references)
        } catch (e: Exception) {
            null // corrupt or partial cache - treat as a miss, the caller rebuilds
        }
    }

    fun save(derivedDir: Path, remappedClasses: Map<String, ByteArray>, indexData: IndexData, references: Map<String, List<String>>) {
        Files.createDirectories(derivedDir)

        val persisted = PersistedIndex(
            classes = indexData.classes().values.map { PersistedClassData(it.name(), it.superName(), it.interfaces(), it.access()) },
            members = indexData.members().values.map {
                PersistedMemberData(
                    it.className(),
                    it.methods().map { m -> PersistedMember(m.name(), m.desc()) },
                    it.fields().map { f -> PersistedMember(f.name(), f.desc()) },
                )
            },
            references = references,
        )

        writeAtomic(derivedDir.resolve("index.json")) { derivedCacheJson.encodeToStream(persisted, it) }
        writeClassJar(derivedDir.resolve("remapped.jar"), remappedClasses)
    }

    // Streams from the file rather than Files.readAllBytes + ZipInputStream: the compressed jar was
    // being held whole alongside the exploded map it produces, doubling the peak of the cache-hit
    // path for the duration of the read.
    private fun readClassJar(jarFile: Path): Map<String, ByteArray> {
        val result = LinkedHashMap<String, ByteArray>()
        ZipFile(jarFile.toFile()).use { zip ->
            for (entry in zip.entries()) {
                if (!entry.isDirectory && entry.name.endsWith(".class")) {
                    result[entry.name.removeSuffix(".class")] = zip.getInputStream(entry).use { it.readBytes() }
                }
            }
        }
        return result
    }

    // Likewise streams straight to the temp file instead of building the whole jar in a
    // ByteArrayOutputStream and then handing that array to writeAtomic.
    private fun writeClassJar(target: Path, remappedClasses: Map<String, ByteArray>) {
        writeAtomic(target) { out ->
            ZipOutputStream(out).use { zip ->
                for ((internalName, bytes) in remappedClasses) {
                    zip.putNextEntry(ZipEntry("$internalName.class"))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        }
    }

    private fun writeAtomic(target: Path, bytes: ByteArray) = writeAtomic(target) { it.write(bytes) }

    private fun writeAtomic(target: Path, write: (OutputStream) -> Unit) {
        Files.createDirectories(target.parent)
        val tmp = Files.createTempFile(target.parent, "tmp-", ".tmp")
        try {
            Files.newOutputStream(tmp).buffered().use(write)
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }
}
