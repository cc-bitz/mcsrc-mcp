package io.github.ccbitz.mcsrcmcp.server

import io.github.ccbitz.mcsrcmcp.core.IndexData
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path

/**
 * Where a workspace's class sources come from. Every tool that shows or resolves source goes through
 * this, so a fork serving its real patched source and vanilla serving decompiles look the same to them.
 *
 * An inner class's name answers with its outer class's source, as the decompiler always has.
 *
 * @throws ClassNotFoundInIndexException From either method, when the class has no source here.
 */
interface ClassSources {

    fun source(internalName: String): String

    fun withTokens(internalName: String): DecompiledClass

    /** Returns once the sources are what they'll stay - [TreeSources] may still be switching over. */
    suspend fun awaitSettled() {}

}

/** Today's sources: Vineflower's decompile of the workspace's classes, cached in its source pool. */
class DecompiledSources(private val classes: Map<String, ByteArray>, private val cacheDir: Path?) : ClassSources {

    override fun source(internalName: String): String = DecompileService.decompileClass(classes, internalName, cacheDir = cacheDir)

    override fun withTokens(internalName: String): DecompiledClass = DecompileService.decompileWithTokens(classes, internalName, cacheDir = cacheDir)

}

/**
 * A fork's real source ([ForkSourceTree]) once its tree is published, and [fallback]'s decompiles
 * before that and for any class the tree doesn't hold - one whose patch didn't apply, or anything
 * built without source.
 *
 * Tokens are Vineflower's where the patches left a line alone, verified against the fork's index,
 * and [BytecodeTokens] everywhere else, so find_declaration and get_method_source work on Paper's own
 * lines too.
 */
class TreeSources(
    private val classes: Map<String, ByteArray>,
    private val index: IndexData,
    private val poolDir: Path,
    private val fallback: ClassSources,
) : ClassSources {

    @Volatile
    private var tree: Map<String, String>? = null
    private val settled = CompletableDeferred<Unit>()

    // find_declaration asks for the same few classes again and again - the one being read and the
    // ones its symbols resolve into - and resolving a big class's fork lines from bytecode isn't free.
    private val recent = object : LinkedHashMap<String, DecompiledClass>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, DecompiledClass>?) = size > RECENT_CLASSES
    }

    private val nests: Map<String, List<String>> by lazy { classes.keys.groupBy { it.substringBefore('$') } }

    val isPublished: Boolean get() = tree != null

    fun publish(tree: Map<String, String>) {
        this.tree = tree
        synchronized(recent) { recent.clear() }
        settled.complete(Unit)
    }

    /** The tree won't come: every class stays a decompile. */
    fun fail() {
        settled.complete(Unit)
    }

    override suspend fun awaitSettled() = settled.await()

    override fun source(internalName: String): String {
        val key = keyFor(internalName) ?: return fallback.source(internalName)
        return readText(poolDir.resolve("$key.java")) ?: fallback.source(internalName)
    }

    override fun withTokens(internalName: String): DecompiledClass {
        val outer = internalName.substringBefore('$')
        val key = keyFor(internalName) ?: return fallback.withTokens(internalName)
        synchronized(recent) { recent[key] }?.let { return it }

        val text = readText(poolDir.resolve("$key.java")) ?: return fallback.withTokens(internalName)
        val entryJson = readText(poolDir.resolve("$key${ForkSourceTree.ENTRY_SUFFIX}")) ?: return fallback.withTokens(internalName)
        val entry = Json.decodeFromString<TreeEntry>(entryJson)

        val (carried, stale) = BytecodeTokens.verifyCarried(entry.carried, index, text)
        val nest = nests[outer].orEmpty().associateWith { classes.getValue(it) }
        val derived = BytecodeTokens.derive(text, entry.uncoveredLines.toSet() + stale, nest, index)
        val result = DecompiledClass(text, (carried + derived).sortedBy { it.start })
        synchronized(recent) { recent[key] = result }
        return result
    }

    private fun keyFor(internalName: String): String? = tree?.get(internalName.substringBefore('$'))

    // An entry can vanish under a reader - clear_cache, or eviction racing a request - and then the
    // class is simply decompiled again.
    private fun readText(path: Path): String? = try {
        Files.readString(path)
    } catch (e: NoSuchFileException) {
        null
    }

    private companion object {

        const val RECENT_CLASSES = 32

    }

}
