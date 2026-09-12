package io.github.ccbitz.mcsrcmcp.server

import io.github.ccbitz.mcsrcmcp.core.ClassData
import io.github.ccbitz.mcsrcmcp.core.Entry
import io.github.ccbitz.mcsrcmcp.core.IndexData
import io.github.ccbitz.mcsrcmcp.core.MemberData
import java.io.DataInputStream
import java.io.DataOutputStream
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
// remap/index output for the same inputs, or the persisted format itself changes - v2 replaced the
// original ~100MB index.json (kotlinx JSON, tens of seconds to encode and decode per version) with
// the flat binary layout below. Old derived directories under a stale version number are simply
// never looked up again - they're inert, not actively cleaned up.
const val DERIVED_CACHE_VERSION = 2

data class DerivedCacheData(
    val remappedClasses: Map<String, ByteArray>,
    val indexData: IndexData,
    val references: Map<String, List<String>>,
)

// index.bin layout, all big-endian, strings as DataOutput.writeUTF (every name/descriptor in an
// index is far under writeUTF's 64KB ceiling). Hand-rolled rather than a serialization framework
// deliberately: the payload is millions of fixed-shape records, and walking them with DataOutput
// calls is both smaller than the JSON it replaced (~40% of its size) and an order of magnitude
// cheaper to produce, since no intermediate object graph or string escaping ever exists.
//
//     UTF "mcsrc-index"            - magic; the directory name already pins the format version,
//                                    this catches a truncated/hand-mangled file on the first read
//     int classCount
//       per class: UTF name, hasSuper:boolean, [UTF super], int ifaceCount, UTF ifaces..., int access
//     int memberClassCount
//       per class: UTF className, int methodCount, (UTF name, UTF desc)..., int fieldCount, (UTF name, UTF desc)...
//     int referenceKeyCount
//       per key: UTF key, int valueCount, UTF values...
object DerivedCacheStore {

    private const val INDEX_FILE = "index.bin"
    private const val INDEX_MAGIC = "mcsrc-index"

    fun directoryFor(cacheRoot: Path, versionId: String, clientSha1: String, mappingsSha1: String?): Path {
        val mapPart = mappingsSha1?.take(12) ?: "none"
        return cacheRoot.resolve("derived").resolve(versionId).resolve("$DERIVED_CACHE_VERSION-${clientSha1.take(12)}-$mapPart")
    }

    fun load(derivedDir: Path): DerivedCacheData? {
        val jarFile = derivedDir.resolve("remapped.jar")
        val indexFile = derivedDir.resolve(INDEX_FILE)
        if (!Files.exists(jarFile) || !Files.exists(indexFile)) {
            return null
        }

        return try {
            val remappedClasses = readClassJar(jarFile)
            val (classes, members, references) = DataInputStream(Files.newInputStream(indexFile).buffered()).use { input ->
                if (input.readUTF() != INDEX_MAGIC) error("unrecognized index file")
                readIndex(input)
            }

            // Mark this derived cache as just-used (Task 8's CacheEviction TTL sweep reads
            // this file's last-modified time to decide what's stale - simply reading the file
            // does not update it, so this must be explicit).
            Files.setLastModifiedTime(indexFile, FileTime.from(Instant.now()))

            DerivedCacheData(remappedClasses, IndexData(classes, members), references)
        } catch (e: Exception) {
            null // corrupt or partial cache - treat as a miss, the caller rebuilds
        }
    }

    private fun readIndex(input: DataInputStream): Triple<Map<String, ClassData>, Map<String, MemberData>, Map<String, List<String>>> {
        val classCount = input.readInt()
        val classes = HashMap<String, ClassData>(classCount)
        repeat(classCount) {
            val name = input.readUTF()
            val superName = if (input.readBoolean()) input.readUTF() else null
            val interfaceCount = input.readInt()
            val interfaces = ArrayList<String>(interfaceCount)
            repeat(interfaceCount) { interfaces.add(input.readUTF()) }
            classes[name] = ClassData(name, superName, interfaces, input.readInt())
        }

        val memberClassCount = input.readInt()
        val members = HashMap<String, MemberData>(memberClassCount)
        repeat(memberClassCount) {
            val className = input.readUTF()
            val methodCount = input.readInt()
            val methods = HashSet<Entry.Method>(methodCount)
            repeat(methodCount) { methods.add(Entry.Method(className, input.readUTF(), input.readUTF())) }
            val fieldCount = input.readInt()
            val fields = HashSet<Entry.Field>(fieldCount)
            repeat(fieldCount) { fields.add(Entry.Field(className, input.readUTF(), input.readUTF())) }
            members[className] = MemberData(className, methods, fields)
        }

        val referenceKeyCount = input.readInt()
        val references = HashMap<String, List<String>>(referenceKeyCount)
        repeat(referenceKeyCount) {
            val key = input.readUTF()
            val valueCount = input.readInt()
            val values = ArrayList<String>(valueCount)
            repeat(valueCount) { values.add(input.readUTF()) }
            references[key] = values
        }
        return Triple(classes, members, references)
    }

    fun save(derivedDir: Path, remappedClasses: Map<String, ByteArray>, indexData: IndexData, references: Map<String, List<String>>) {
        Files.createDirectories(derivedDir)

        writeAtomic(derivedDir.resolve(INDEX_FILE)) { out ->
            DataOutputStream(out.buffered()).use { output ->
                output.writeUTF(INDEX_MAGIC)
                writeIndex(output, indexData, references)
            }
        }
        writeClassJar(derivedDir.resolve("remapped.jar"), remappedClasses)
    }

    private fun writeIndex(output: DataOutputStream, indexData: IndexData, references: Map<String, List<String>>) {
        val classes = indexData.classes()
        output.writeInt(classes.size)
        for (classData in classes.values) {
            output.writeUTF(classData.name())
            val superName = classData.superName()
            if (superName == null) {
                output.writeBoolean(false)
            } else {
                output.writeBoolean(true)
                output.writeUTF(superName)
            }
            output.writeInt(classData.interfaces().size)
            for (interfaceName in classData.interfaces()) {
                output.writeUTF(interfaceName)
            }
            output.writeInt(classData.access())
        }

        val members = indexData.members()
        output.writeInt(members.size)
        for (memberData in members.values) {
            output.writeUTF(memberData.className())
            output.writeInt(memberData.methods().size)
            for (method in memberData.methods()) {
                output.writeUTF(method.name())
                output.writeUTF(method.desc())
            }
            output.writeInt(memberData.fields().size)
            for (field in memberData.fields()) {
                output.writeUTF(field.name())
                output.writeUTF(field.desc())
            }
        }

        output.writeInt(references.size)
        for ((key, values) in references) {
            output.writeUTF(key)
            output.writeInt(values.size)
            for (value in values) {
                output.writeUTF(value)
            }
        }
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
