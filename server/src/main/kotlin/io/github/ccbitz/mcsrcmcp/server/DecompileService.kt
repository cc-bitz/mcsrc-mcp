package io.github.ccbitz.mcsrcmcp.server

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.java.decompiler.api.Decompiler
import org.jetbrains.java.decompiler.main.extern.IContextSource
import org.jetbrains.java.decompiler.main.extern.IResultSaver
import org.jetbrains.java.decompiler.main.extern.TextTokenVisitor
import org.jetbrains.java.decompiler.struct.gen.FieldDescriptor
import org.jetbrains.java.decompiler.struct.gen.MethodDescriptor
import org.jetbrains.java.decompiler.util.token.TextRange
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.jar.Manifest

class DecompileTimeoutException(className: String) :
    RuntimeException("decompilation of $className timed out")

class DecompileFailedException(className: String, cause: Throwable) :
    RuntimeException("decompilation of $className failed: ${cause.message}", cause)

/** [IContextSource] backed by an in-memory map of internal class name -> class bytes. */
private class InMemoryContextSource(
    private val sourceName: String,
    private val classes: Map<String, ByteArray>,
) : IContextSource {
    override fun getName(): String = sourceName

    override fun getEntries(): IContextSource.Entries {
        val entries = classes.keys.map { IContextSource.Entry.atBase(it) }
        return IContextSource.Entries(entries, emptyList(), emptyList())
    }

    override fun getInputStream(resource: String): InputStream? {
        val internalName = resource.removeSuffix(IContextSource.CLASS_SUFFIX)
        val bytes = classes[internalName] ?: return null
        return ByteArrayInputStream(bytes)
    }

    // Only a context source registered via .inputs() (an "own" unit, per ContextUnit.save())
    // ever has this called - Fernflower calls createOutputSink(resultSaver) and requires a
    // non-null sink for any own unit, throwing IllegalStateException otherwise. The sink just
    // delegates each decompiled class straight to the IResultSaver the Decompiler was built
    // with (our CapturingResultSaver), which is exactly how the interface is meant to be used.
    override fun createOutputSink(saver: IResultSaver): IContextSource.IOutputSink {
        return object : IContextSource.IOutputSink {
            override fun begin() {}

            override fun acceptClass(qualifiedName: String, fileName: String, content: String, mapping: IntArray?) {
                saver.saveClassFile(sourceName, qualifiedName, fileName, content, mapping)
            }

            override fun acceptDirectory(directory: String) {}
            override fun acceptOther(path: String) {}
            override fun close() {}
        }
    }
}

/** [IResultSaver] that captures decompiled class text instead of writing files. */
private class CapturingResultSaver : IResultSaver {
    val captured = StringBuilder()

    override fun saveFolder(path: String) {}
    override fun copyFile(source: String, path: String, entryName: String) {}

    override fun saveClassFile(path: String, qualifiedName: String, entryName: String, content: String, mapping: IntArray?) {
        captured.append(content)
    }

    override fun createArchive(path: String, archiveName: String, manifest: Manifest?) {}
    override fun saveDirEntry(path: String, archiveName: String, entryName: String) {}
    override fun copyEntry(source: String, path: String, archiveName: String, entry: String) {}

    override fun saveClassEntry(path: String, archiveName: String, qualifiedName: String, entryName: String, content: String) {
        captured.append(content)
    }

    override fun closeArchive(path: String, archiveName: String) {}
}

/**
 * Collects Vineflower's own text tokens (exposed upstream since 1.10). Every
 * class/member/variable Vineflower writes is reported with the character range it occupies in the
 * emitted source and the owner and descriptor it resolved to, which is what makes a use site
 * resolvable without re-parsing the Java or guessing between overloads.
 *
 * Tokens are keyed by the content they were collected from because one decompile can emit several
 * files; the caller matches the source it kept against this map rather than assuming a single one.
 */
private class TokenCollector(next: TextTokenVisitor) : TextTokenVisitor(next) {
    val tokensByContent = mutableMapOf<String, List<SourceToken>>()

    private var content: String? = null
    private var tokens = mutableListOf<SourceToken>()

    override fun start(content: String) {
        this.content = content
        tokens = mutableListOf()
        super.start(content)
    }

    override fun visitClass(range: TextRange, declaration: Boolean, className: String) {
        add(range, TokenKind.CLASS, className, null, declaration)
        super.visitClass(range, declaration, className)
    }

    override fun visitField(range: TextRange, declaration: Boolean, className: String, name: String, descriptor: FieldDescriptor) {
        add(range, TokenKind.FIELD, className, TokenMember(name, descriptor.descriptorString), declaration)
        super.visitField(range, declaration, className, name, descriptor)
    }

    // MethodDescriptor carries no descriptorString the way FieldDescriptor does; its toString is
    // the descriptor ("(params)ret", each VarType rendering as its own descriptor form), and
    // DecompileServiceTest pins that so a Vineflower change to it fails loudly rather than
    // silently producing members that never match the index.
    override fun visitMethod(range: TextRange, declaration: Boolean, className: String, name: String, descriptor: MethodDescriptor) {
        add(range, TokenKind.METHOD, className, TokenMember(name, descriptor.toString()), declaration)
        super.visitMethod(range, declaration, className, name, descriptor)
    }

    override fun visitParameter(
        range: TextRange,
        declaration: Boolean,
        className: String,
        methodName: String,
        methodDescriptor: MethodDescriptor,
        index: Int,
        name: String?,
    ) {
        add(range, TokenKind.PARAMETER, className, null, declaration)
        super.visitParameter(range, declaration, className, methodName, methodDescriptor, index, name)
    }

    override fun visitLocal(
        range: TextRange,
        declaration: Boolean,
        className: String,
        methodName: String,
        methodDescriptor: MethodDescriptor,
        index: Int,
        name: String?,
    ) {
        add(range, TokenKind.LOCAL, className, null, declaration)
        super.visitLocal(range, declaration, className, methodName, methodDescriptor, index, name)
    }

    override fun end() {
        content?.let { tokensByContent[it] = tokens.sortedBy { token -> token.start } }
        content = null
        super.end()
    }

    private fun add(range: TextRange, kind: TokenKind, className: String, member: TokenMember?, declaration: Boolean) {
        tokens.add(SourceToken(range.start, range.length, kind, className, member, declaration))
    }
}

private const val TEXT_TOKEN_VISITOR_PROPERTY = "text_token_visitor"

object DecompileService {
    // A dedicated single-thread executor: serializes all decompile calls (Vineflower's
    // thread-safety across concurrent Decompiler instances is not documented/verified, so
    // don't assume it), and gives that thread a bumped stack size to reduce the odds of
    // StackOverflowError on pathological deeply-nested Minecraft methods.
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(null, runnable, "vineflower-decompile", 64L * 1024 * 1024)
    }

    fun decompileWithTokens(
        classes: Map<String, ByteArray>,
        className: String,
        timeoutMs: Long = 30_000,
        cacheDir: Path? = null,
    ): DecompiledClass {
        val cacheFile = cacheDir?.resolve("${sanitizeForCacheFilename(className)}.java")
        val tokenFile = cacheDir?.resolve("${sanitizeForCacheFilename(className)}.tokens.json")

        // Both files or neither: decompileClass writes the source on its own, so finding a .java
        // says nothing about whether tokens were ever collected for it. Token offsets are only
        // valid against the exact text they came from, so serving a half-populated pair would be
        // worse than paying for the decompile again.
        if (cacheFile != null && tokenFile != null && Files.exists(cacheFile) && Files.exists(tokenFile)) {
            val tokens = Json.decodeFromString<List<SourceToken>>(Files.readString(tokenFile))
            return DecompiledClass(Files.readString(cacheFile), tokens)
        }

        val collector = TokenCollector(TextTokenVisitor.EMPTY)
        val result = runDecompile(classes, className, timeoutMs, collector)

        if (cacheFile != null && tokenFile != null) {
            writeAtomic(cacheFile, result.source)
            writeAtomic(tokenFile, Json.encodeToString(result.tokens))
        }

        return result
    }

    fun decompileClass(
        classes: Map<String, ByteArray>,
        className: String,
        timeoutMs: Long = 30_000,
        cacheDir: Path? = null,
    ): String {
        val cacheFile = cacheDir?.resolve("${sanitizeForCacheFilename(className)}.java")
        if (cacheFile != null && Files.exists(cacheFile)) {
            return Files.readString(cacheFile)
        }

        val source = runDecompile(classes, className, timeoutMs, collector = null).source

        if (cacheFile != null) {
            writeAtomic(cacheFile, source)
        }

        return source
    }

    // Token collection is opt-in rather than always-on: get_class_source runs this on every read
    // and has no use for tokens, and a large class produces tens of thousands of them.
    private fun runDecompile(
        classes: Map<String, ByteArray>,
        className: String,
        timeoutMs: Long,
        collector: TokenCollector?,
    ): DecompiledClass {
        // Requesting an inner class ("Outer$Inner") decompiles the outer class - Vineflower
        // inlines inner classes into their outer class's source, matching how a human reads it.
        val outerClassName = className.substringBefore('$')
        if (outerClassName !in classes) {
            throw ClassNotFoundInIndexException(className)
        }

        val targetPrefix = "$outerClassName$"
        val targetClasses = classes.filterKeys { it == outerClassName || it.startsWith(targetPrefix) }

        val future = executor.submit<DecompiledClass> {
            // Two sources: `inputs` is exactly what gets decompiled and emitted; `libraries` is
            // the whole jar, available for type resolution (supertypes, field/parameter types,
            // etc.) without being decompiled or emitted itself. This is Vineflower's documented
            // way to decompile one class with full-classpath context (vineflower.org/usage-code).
            val targetSource = InMemoryContextSource("target", targetClasses)
            val librarySource = InMemoryContextSource("classpath", classes)
            val saver = CapturingResultSaver()

            val builder = Decompiler.builder()
                .inputs(targetSource)
                .libraries(librarySource)
                .output(saver)

            if (collector != null) {
                // TextTokenVisitor.addVisitor() is the documented way in, but it only works from
                // inside a live DecompilerContext - it appends to a list held under this property
                // on the current context, and there is no context until decompile() is running.
                // Seeding the same property up front is the way to register from outside; the key
                // is TextTokenVisitor.PROPERTY_NAME, which is private, so it is spelled out here
                // and pinned by DecompileServiceTest.
                builder.option(TEXT_TOKEN_VISITOR_PROPERTY, mutableListOf(TextTokenVisitor.Factory { collector }))
            }

            try {
                builder.build().decompile()
            } catch (e: Exception) {
                throw DecompileFailedException(className, e)
            }

            val source = saver.captured.toString()
            DecompiledClass(source, collector?.tokensByContent?.get(source) ?: emptyList())
        }

        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            throw DecompileTimeoutException(className)
        }
    }

    // '/' is not a valid filename character - class internal names always contain it as a
    // package separator, so this must run before writing the cache file. '$' (inner classes)
    // is a valid filename character on Windows/macOS/Linux and needs no substitution.
    private fun sanitizeForCacheFilename(className: String): String = className.replace('/', '.')

    private fun writeAtomic(target: Path, content: String) {
        Files.createDirectories(target.parent)
        val tmp = Files.createTempFile(target.parent, "src-", ".tmp")
        try {
            Files.writeString(tmp, content)
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }
}
