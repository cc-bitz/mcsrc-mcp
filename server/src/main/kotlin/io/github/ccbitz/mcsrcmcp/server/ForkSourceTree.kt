package io.github.ccbitz.mcsrcmcp.server

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.HexFormat
import java.util.zip.ZipInputStream

/**
 * What a source-tree pool entry knows beyond its text: the Vineflower tokens carried through mache's and
 * the fork's patches, and the lines neither covers - the patches wrote them - which are resolved from
 * the fork's bytecode when asked for.
 */
@Serializable
data class TreeEntry(val carried: List<SourceToken>, val uncoveredLines: List<Int>)

/**
 * A fork build's real source: the [MacheTree] for its Minecraft version with the dev bundle's patches
 * applied and its new files added - what paperweight userdev's `applyDevBundlePatches` produces, so the
 * text Paper's developers read, comments and all.
 *
 * Every class goes into the shared tree directory ([SourcePools.treeDir]) as `<key>.java` +
 * `<key>.tree.json`, keyed by what it holds, so a build whose patches mostly didn't change since the
 * last one - or another fork carrying the same patches - writes almost nothing. A build unit records
 * which key each of its classes uses in [MANIFEST_FILE], and lists the keys for eviction.
 */
object ForkSourceTree {

    const val MANIFEST_FILE = "source-tree.txt"
    const val ENTRY_SUFFIX = ".tree.json"

    // Bump when what an entry holds for the same text changes, so old entries aren't reused as-is.
    private const val TREE_ENTRY_VERSION = "tree-v1"

    private val json = Json

    /**
     * @property tree outer class internal name -> pool key, for every class the build serves from source.
     * @property failed classes whose fork patch didn't apply; they fall back to decompiled bytecode.
     * @property written pool entries this build had to write, i.e. that no earlier build had.
     */
    class Result(val tree: Map<String, String>, val failed: List<String>, val written: Int)

    fun build(mache: MacheTree, bundleZip: ByteArray, patchDir: String, poolDir: Path): Result {
        val prefix = patchDir.trimEnd('/') + "/"
        val patches = HashMap<String, String>()
        val newFiles = HashMap<String, String>()
        ZipInputStream(bundleZip.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val name = entry.name
                if (!entry.isDirectory && name.startsWith(prefix)) {
                    val path = name.removePrefix(prefix)
                    when {
                        path.endsWith(".java.patch") -> patches[path.removeSuffix(".java.patch")] = zip.readBytes().toString(Charsets.UTF_8)
                        path.endsWith(".java") -> newFiles[path.removeSuffix(".java")] = zip.readBytes().toString(Charsets.UTF_8)
                    }
                }
                entry = zip.nextEntry
            }
        }

        Files.createDirectories(poolDir)
        val tree = HashMap<String, String>()
        val failed = mutableListOf<String>()
        var written = 0
        fun store(internalName: String, text: String, entry: TreeEntry) {
            val entryJson = json.encodeToString(entry)
            val key = sha1Hex("$TREE_ENTRY_VERSION\u0000$text\u0000$entryJson")
            val javaFile = poolDir.resolve("$key.java")
            val entryFile = poolDir.resolve("$key$ENTRY_SUFFIX")
            if (!Files.exists(javaFile) || !Files.exists(entryFile)) {
                writeAtomic(entryFile, entryJson)
                writeAtomic(javaFile, text)
                written++
            }
            tree[internalName] = key
        }

        mache.forEach { internalName, source ->
            val patch = patches[internalName]
            if (patch == null) {
                store(internalName, source.text, TreeEntry(source.tokens, source.uncoveredLines.sorted()))
                return@forEach
            }
            val patched = try {
                SourcePatch.apply(source.text, patch)
            } catch (e: PatchFailedException) {
                failed.add(internalName)
                return@forEach
            }
            val carried = carryTokens(source.text, source.tokens, patched.text, patched.originFor)
            val uncovered = carried.uncoveredLines + carryLines(source.uncoveredLines, patched.originFor)
            store(internalName, patched.text, TreeEntry(carried.tokens, uncovered.sorted()))
        }

        for ((internalName, text) in newFiles) {
            store(internalName, text, TreeEntry(emptyList(), (1..lineCount(text)).toList()))
        }

        return Result(tree, failed.sorted(), written)
    }

    /**
     * Records [tree] in [unitDir] and adds its keys to the unit's pool key list, so eviction keeps the
     * entries. Call after [SourcePools.writeKeys]: that one skips a unit that already has a list.
     */
    fun writeManifest(unitDir: Path, tree: Map<String, String>) {
        val lines = listOf(MANIFEST_HEADER) + tree.entries.sortedBy { it.key }.map { "${it.key}\t${it.value}" }
        writeAtomic(unitDir.resolve(MANIFEST_FILE), lines.joinToString("\n"))
        if (tree.isNotEmpty()) SourcePools.addKeys(unitDir, tree.values.toSet())
    }

    /**
     * The unit's tree, or null when it has none yet - or has one written under another
     * [MANIFEST_HEADER], which then gets rebuilt. An empty map is a settled "no tree" (the bundle has
     * no mache, or one this server can't build), kept so a restart doesn't refetch the bundle to
     * find that out again.
     */
    fun readManifest(unitDir: Path): Map<String, String>? {
        val file = unitDir.resolve(MANIFEST_FILE)
        if (!Files.exists(file)) return null
        val lines = Files.readAllLines(file)
        if (lines.firstOrNull() != MANIFEST_HEADER) return null
        return lines.drop(1).filter { '\t' in it }.associate { it.substringBefore('\t') to it.substringAfter('\t') }
    }

    // Bump to have every unit rebuild its tree - a newly supported mache, a fix to how trees are made.
    // v2: entries moved from each fork's pool to the shared tree directory.
    private const val MANIFEST_HEADER = "#source-tree v2"

    private fun sha1Hex(text: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8)))

    private fun writeAtomic(target: Path, content: String) {
        Files.createDirectories(target.parent)
        val tmp = Files.createTempFile(target.parent, "tree-", ".tmp")
        try {
            Files.writeString(tmp, content)
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: java.nio.file.AccessDeniedException) {
                // Windows won't replace a file a reader holds open. The key names this exact content,
                // so whatever is there already is it; only an absent target is a failure.
                if (!Files.exists(target)) throw e
            }
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

}
